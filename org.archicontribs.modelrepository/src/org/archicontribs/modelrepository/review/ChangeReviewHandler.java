/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.review;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.archicontribs.modelrepository.grafico.GraficoModelImporter;
import org.archicontribs.modelrepository.grafico.IArchiRepository;
import org.archicontribs.modelrepository.grafico.IGraficoConstants;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.window.Window;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.swt.widgets.Shell;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateDiagramModel;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelConnection;
import com.archimatetool.model.IDiagramModelContainer;
import com.archimatetool.model.IDiagramModelObject;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.IProfile;
import com.archimatetool.model.IProfiles;
import com.archimatetool.model.util.ArchimateModelUtils;


/**
 * Handler for reviewing changes between HEAD and current model
 * 
 * Similar to MergeConflictHandler but for local change review.
 * 
 * @author Raimond Brookman
 */
public class ChangeReviewHandler {
    
    /**
     * Enable debug logging for review changes feature.
     * Enable with JVM arg: -Dreview.debug.logging=true
     * 
     * Logs appear in:
     * - Eclipse Error Log view (Window → Show View → Error Log)
     * - .metadata/.log file in your workspace
     */
    private static final boolean DEBUG_LOGGING = Boolean.getBoolean("review.debug.logging"); //$NON-NLS-1$
    
    /**
     * Log debug message if DEBUG_LOGGING is enabled.
     */
    private static void logDebug(String message) {
        if (!DEBUG_LOGGING) return;
        ModelRepositoryPlugin.getInstance().log(IStatus.INFO, "[ReviewChanges] " + message, null); //$NON-NLS-1$
    }
    
    private IArchiRepository fArchiRepo;
    private Shell fShell;
    
    private List<ChangeInfo> fChangeInfos;
    
    private IArchimateModel fCurrentModel;
    private IArchimateModel fHeadModel;
    
    // Cache for HEAD model ID lookups - safe to cache because HEAD model is immutable during reverts
    private Map<String, IIdentifier> fHeadModelIdCache;
    
    // Cache for current model ID lookups - incrementally updated as objects are added/removed
    // This is safe because we explicitly update the cache when we modify the model
    private Map<String, IIdentifier> fCurrentModelIdCache;
    
    private IProgressMonitor fProgressMonitor;

    /**
     * Constructor
     * 
     * @param repo The repository
     * @param shell The parent shell for dialogs
     */
    public ChangeReviewHandler(IArchiRepository repo, Shell shell) {
        fArchiRepo = repo;
        fShell = shell;
    }
    
    /**
     * Initialize the handler by loading current model and detecting changes.
     * 
     * PERFORMANCE: HEAD model is NOT loaded here - it's loaded lazily only when
     * needed for applying reverts. This makes the initial analysis fast.
     * 
     * @param pm Progress monitor
     * @throws IOException If loading fails
     * @throws GitAPIException If git operations fail
     */
    public void init(IProgressMonitor pm) throws IOException, GitAPIException {
        fProgressMonitor = pm;
        
        // Current model is the loaded one
        fCurrentModel = fArchiRepo.locateModel();
        if(fCurrentModel == null) {
            throw new IOException(Messages.ChangeReviewHandler_0);
        }
        
        // Load HEAD model - needed for displaying deleted items and for comparison
        // Loading from git objects is fast (no filesystem I/O)
        logDebug("Loading HEAD model from git objects"); //$NON-NLS-1$
        fHeadModel = extractModel(IGraficoConstants.HEAD);
        
        // Build HEAD model ID cache for O(1) lookups during reverts
        // Safe to cache because HEAD model is immutable during revert operations
        buildHeadModelIdCache();
        
        // Build current model ID cache for O(1) lookups during dependency analysis
        // This makes diagram dependency analysis fast when user marks diagrams for revert
        buildCurrentModelIdCache();
        
        // Build list of changes from git status (fast - no file I/O needed)
        fChangeInfos = new ArrayList<>();
        buildChangeList();
    }
    
    /**
     * Ensure the HEAD model is loaded. Called lazily before applying reverts.
     * 
     * @param pm Progress monitor
     * @throws IOException If loading fails
     */
    public void ensureHeadModelLoaded(IProgressMonitor pm) throws IOException {
        if(fHeadModel != null) {
            return; // Already loaded
        }
        
        fProgressMonitor = pm;
        fHeadModel = extractModel(IGraficoConstants.HEAD);
        
        // Build HEAD model ID cache for O(1) lookups during reverts
        buildHeadModelIdCache();
    }
    
    /**
     * Build the list of changes from git status
     */
    private void buildChangeList() throws IOException, GitAPIException {
        try(Git git = Git.open(fArchiRepo.getLocalRepositoryFolder())) {
            Status status = git.status().call();
            
            // Added files
            Set<String> added = status.getAdded();
            for(String path : added) {
                if(isModelFile(path)) {
                    fChangeInfos.add(new ChangeInfo(path, ChangeInfo.ADDED, this));
                }
            }
            
            // Deleted files (staged)
            Set<String> removed = status.getRemoved();
            for(String path : removed) {
                if(isModelFile(path)) {
                    fChangeInfos.add(new ChangeInfo(path, ChangeInfo.DELETED, this));
                }
            }
            
            // Modified files
            Set<String> changed = status.getChanged();
            for(String path : changed) {
                if(isModelFile(path)) {
                    fChangeInfos.add(new ChangeInfo(path, ChangeInfo.MODIFIED, this));
                }
            }
            
            // Also check unstaged changes
            Set<String> modified = status.getModified();
            for(String path : modified) {
                if(isModelFile(path) && !containsPath(path)) {
                    fChangeInfos.add(new ChangeInfo(path, ChangeInfo.MODIFIED, this));
                }
            }
            
            // Untracked files (new, not yet staged)
            Set<String> untracked = status.getUntracked();
            for(String path : untracked) {
                if(isModelFile(path) && !containsPath(path)) {
                    fChangeInfos.add(new ChangeInfo(path, ChangeInfo.ADDED, this));
                }
            }
            
            // Missing files (deleted but not yet staged)
            Set<String> missing = status.getMissing();
            for(String path : missing) {
                if(isModelFile(path) && !containsPath(path)) {
                    fChangeInfos.add(new ChangeInfo(path, ChangeInfo.DELETED, this));
                }
            }
        }
    }
    
    /**
     * Check if a path is already in the change list
     */
    private boolean containsPath(String path) {
        for(ChangeInfo info : fChangeInfos) {
            if(info.getXMLPath().equals(path)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Check if a path is a model element file (not folder.xml, images, etc.)
     * 
     * folder.xml files define folder structure, not individual elements.
     * Element files have the pattern: ElementType_id-xxxx.xml
     */
    private boolean isModelFile(String path) {
        if(!path.endsWith(".xml")) { //$NON-NLS-1$
            return false;
        }
        // Exclude folder.xml files - they define folder structure, not elements
        if(path.endsWith(IGraficoConstants.FOLDER_XML)) {
            return false;
        }
        // Must be under the model folder
        if(!path.startsWith(IGraficoConstants.MODEL_FOLDER + "/")) { //$NON-NLS-1$
            return false;
        }
        return true;
    }
    
    /**
     * Open the review dialog
     * 
     * @return true if user clicked OK
     */
    public boolean openReviewDialog() {
        Dialog dialog = new ReviewChangesDialog(fShell, this);
        return dialog.open() == Window.OK;
    }
    
    /**
     * Analyze diagram dependencies for a ChangeInfo representing a diagram.
     * 
     * This scans the diagram from HEAD model and identifies visual objects
     * that reference elements/relationships that don't exist in the current model.
     * 
     * @param info The ChangeInfo for a diagram (must be a diagram for this to do anything)
     * @return List of dependencies, empty if none or if not a diagram
     */
    public List<DiagramDependencyInfo> analyzeDiagramDependencies(ChangeInfo info) {
        List<DiagramDependencyInfo> dependencies = new ArrayList<>();
        
        // Only analyze diagrams that are being reverted from HEAD
        EObject headObj = info.getEObject(ChangeInfo.HEAD);
        if (!(headObj instanceof IDiagramModel headDiagram)) {
            return dependencies;
        }
        
        // Track which concepts are missing and how many visual objects reference them
        Map<String, Integer> elementRefCounts = new HashMap<>();
        Map<String, Integer> relationRefCounts = new HashMap<>();
        Map<String, IArchimateElement> missingElements = new HashMap<>();
        Map<String, IArchimateRelationship> missingRelations = new HashMap<>();
        
        // Scan all diagram contents recursively
        scanDiagramForMissingConcepts(headDiagram, elementRefCounts, relationRefCounts, 
                                       missingElements, missingRelations);
        
        // Create DiagramDependencyInfo for each missing element
        for (Map.Entry<String, IArchimateElement> entry : missingElements.entrySet()) {
            int refCount = elementRefCounts.getOrDefault(entry.getKey(), 1);
            dependencies.add(new DiagramDependencyInfo(entry.getValue(), refCount, info));
        }
        
        // Create DiagramDependencyInfo for each missing relationship
        for (Map.Entry<String, IArchimateRelationship> entry : missingRelations.entrySet()) {
            int refCount = relationRefCounts.getOrDefault(entry.getKey(), 1);
            dependencies.add(new DiagramDependencyInfo(entry.getValue(), refCount, info));
        }
        
        logDebug("analyzeDiagramDependencies: " + dependencies.size() + " missing concepts for " + headDiagram.getName()); //$NON-NLS-1$ //$NON-NLS-2$
        
        return dependencies;
    }
    
    /**
     * Recursively scan a diagram container for visual objects that reference missing concepts
     */
    private void scanDiagramForMissingConcepts(IDiagramModelContainer container,
            Map<String, Integer> elementRefCounts, Map<String, Integer> relationRefCounts,
            Map<String, IArchimateElement> missingElements, Map<String, IArchimateRelationship> missingRelations) {
        
        for (IDiagramModelObject child : container.getChildren()) {
            // Check diagram objects that reference ArchiMate elements
            if (child instanceof IDiagramModelArchimateObject diagramObj) {
                IArchimateElement headElement = diagramObj.getArchimateElement();
                if (headElement != null) {
                    String elementId = headElement.getId();
                    // Check if element exists in current model
                    if (lookupInCurrentModel(elementId) == null) {
                        // Element is missing - track it
                        missingElements.putIfAbsent(elementId, headElement);
                        elementRefCounts.merge(elementId, 1, Integer::sum);
                    }
                }
                
                // Check connections on this object
                for (var conn : diagramObj.getSourceConnections()) {
                    if (conn instanceof IDiagramModelArchimateConnection diagramConn) {
                        IArchimateRelationship headRel = diagramConn.getArchimateRelationship();
                        if (headRel != null) {
                            String relId = headRel.getId();
                            if (lookupInCurrentModel(relId) == null) {
                                missingRelations.putIfAbsent(relId, headRel);
                                relationRefCounts.merge(relId, 1, Integer::sum);
                            }
                        }
                    }
                }
            }
            
            // Recurse into nested containers
            if (child instanceof IDiagramModelContainer nestedContainer) {
                scanDiagramForMissingConcepts(nestedContainer, elementRefCounts, relationRefCounts,
                                              missingElements, missingRelations);
            }
        }
    }

    /**
     * Apply the user's revert choices to the current model.
     * 
     * Order of operations is critical for deletions:
     * 1. Elements first (so relationships can resolve their source/target)
     * 2. Relationships second
     * 3. Diagrams last (they may reference elements and relationships)
     * 
     * @throws IOException If revert fails
     */
    public void applyReverts() throws IOException {
        // Rebuild current model cache to ensure it's fresh before applying reverts
        // (The model may have changed since init() was called)
        // We'll update this cache incrementally as we add/remove objects
        buildCurrentModelIdCache();
        
        // Collect reverts to apply, sorted by type for correct ordering
        List<ChangeInfo> elementReverts = new ArrayList<>();
        List<ChangeInfo> relationReverts = new ArrayList<>();
        List<ChangeInfo> diagramReverts = new ArrayList<>();
        List<ChangeInfo> otherReverts = new ArrayList<>();
        
        for(ChangeInfo info : fChangeInfos) {
            if(!info.isRevert()) {
                continue;
            }
            
            EObject headObj = info.getEObject(ChangeInfo.HEAD);
            
            // Diagrams (both DELETED and MODIFIED) need special handling
            // They may have dependencies that need to be pre-restored
            if(headObj instanceof IDiagramModel) {
                diagramReverts.add(info);
            }
            // For deletions, we need to restore in dependency order
            else if(info.getChangeType() == ChangeInfo.DELETED) {
                if(headObj instanceof IArchimateRelationship) {
                    relationReverts.add(info);
                } else if(headObj instanceof IArchimateElement) {
                    elementReverts.add(info);
                } else {
                    otherReverts.add(info);
                }
            } else {
                // Additions and modifications (except diagrams) can be processed in any order
                otherReverts.add(info);
            }
        }
        
        logDebug("applyReverts: " + elementReverts.size() + " elements, " + 
                 relationReverts.size() + " relations, " + 
                 diagramReverts.size() + " diagrams, " +
                 otherReverts.size() + " other"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        
        // Phase 1: Pre-restore any missing profiles/specializations that will be needed
        // This ensures profiles exist before elements that reference them are processed
        preRestoreMissingProfiles(elementReverts);
        preRestoreMissingProfiles(relationReverts);
        
        // Phase 1b: Pre-restore concepts that diagram dependencies require
        // This ensures elements/relationships exist before diagrams reference them
        // This handles both DELETED and MODIFIED diagrams
        preRestoreDiagramDependencies(diagramReverts);
        
        // Phase 2: Apply in correct order: elements, relations, diagrams, other
        for(ChangeInfo info : elementReverts) {
            applyRevert(info);
        }
        for(ChangeInfo info : relationReverts) {
            applyRevert(info);
        }
        for(ChangeInfo info : diagramReverts) {
            applyRevert(info);
        }
        for(ChangeInfo info : otherReverts) {
            applyRevert(info);
        }
        
        // Save the model to persist the reverts
        IEditorModelManager.INSTANCE.saveModel(fCurrentModel);
    }
    
    /**
     * Pre-restore any missing profiles that are referenced by the given reverts.
     * 
     * This ensures that when elements are restored, their profile references
     * can be resolved correctly. Without this, if a profile was deleted along
     * with the elements that use it, the profile would be missing during element restore.
     * 
     * @param reverts List of change infos to check for profile references
     */
    private void preRestoreMissingProfiles(List<ChangeInfo> reverts) {
        for(ChangeInfo info : reverts) {
            if(info.getChangeType() != ChangeInfo.DELETED) {
                continue;
            }
            
            EObject headObj = info.getEObject(ChangeInfo.HEAD);
            if(!(headObj instanceof IProfiles profilesObj)) {
                continue;
            }
            
            for(IProfile headProfile : profilesObj.getProfiles()) {
                String profileId = getIdentifierId(headProfile);
                
                // Check if profile exists in current model
                IIdentifier existing = lookupInCurrentModel(profileId);
                if(existing instanceof IProfile) {
                    continue; // Already exists
                }
                
                // Profile is missing - restore from HEAD
                IProfile resolvedHeadProfile = resolveProfileFromHead(profileId);
                if(resolvedHeadProfile != null) {
                    copyProfileFromHead(resolvedHeadProfile);
                    logDebug("preRestoreMissingProfiles: restored profile " + profileId); //$NON-NLS-1$
                }
            }
        }
    }
    
    /**
     * Pre-restore concepts (elements/relationships) that diagram dependencies require.
     * 
     * For each diagram being reverted that has dependencies marked as RESTORE_CONCEPT,
     * this method restores those concepts from HEAD before the diagram is processed.
     * 
     * @param diagramReverts List of diagram ChangeInfos to check
     */
    private void preRestoreDiagramDependencies(List<ChangeInfo> diagramReverts) {
        // Collect all concepts that need to be restored
        Set<String> conceptsToRestore = new java.util.HashSet<>();
        Map<String, DiagramDependencyInfo> conceptToDepInfo = new HashMap<>();
        
        for(ChangeInfo info : diagramReverts) {
            if(!info.hasDependencies()) {
                continue;
            }
            
            for(DiagramDependencyInfo dep : info.getDependencies()) {
                if(dep.shouldRestore() && !conceptsToRestore.contains(dep.getConceptId())) {
                    conceptsToRestore.add(dep.getConceptId());
                    conceptToDepInfo.put(dep.getConceptId(), dep);
                }
            }
        }
        
        logDebug("preRestoreDiagramDependencies: " + conceptsToRestore.size() + " concepts to restore"); //$NON-NLS-1$ //$NON-NLS-2$
        
        // Restore each concept from HEAD
        // Order: elements first, then relationships (since relationships reference elements)
        List<DiagramDependencyInfo> elements = new ArrayList<>();
        List<DiagramDependencyInfo> relationships = new ArrayList<>();
        
        for(String conceptId : conceptsToRestore) {
            DiagramDependencyInfo dep = conceptToDepInfo.get(conceptId);
            if(dep.isElement()) {
                elements.add(dep);
            } else {
                relationships.add(dep);
            }
        }
        
        // Restore elements first
        for(DiagramDependencyInfo dep : elements) {
            restoreConceptFromDependency(dep);
        }
        
        // Then restore relationships
        for(DiagramDependencyInfo dep : relationships) {
            restoreConceptFromDependency(dep);
        }
    }
    
    /**
     * Restore a concept (element or relationship) from a diagram dependency.
     */
    private void restoreConceptFromDependency(DiagramDependencyInfo dep) {
        EObject headConcept = dep.getHeadConcept();
        if(headConcept == null) {
            logDebug("restoreConceptFromDependency: headConcept is null for " + dep.getConceptId()); //$NON-NLS-1$
            return;
        }
        
        // Check if already exists in current model
        if(lookupInCurrentModel(dep.getConceptId()) != null) {
            logDebug("restoreConceptFromDependency: concept already exists: " + dep.getConceptId()); //$NON-NLS-1$
            return;
        }
        
        logDebug("restoreConceptFromDependency: restoring " + dep.getConceptType() + " " + dep.getConceptId()); //$NON-NLS-1$ //$NON-NLS-2$
        
        // Create a copy
        EObject copy = EcoreUtil.copy(headConcept);
        
        // Restore original IDs
        restoreOriginalIds(headConcept, copy);
        
        // Resolve cross-model references
        resolveCrossModelReferences(copy, headConcept);
        
        // Find target folder - use the folder from HEAD model
        EObject container = headConcept.eContainer();
        if(container instanceof IFolder headFolder) {
            IFolder targetFolder = findOrRestoreFolder(headFolder);
            if(targetFolder != null) {
                if(copy instanceof IArchimateElement || copy instanceof IArchimateRelationship) {
                    targetFolder.getElements().add((IIdentifier)copy);
                    addToCurrentModelCache((IIdentifier)copy);
                    logDebug("  Added concept to folder: " + targetFolder.getName()); //$NON-NLS-1$
                }
            }
        }
    }

    /**
     * Find or restore a folder in the current model that corresponds to a folder from HEAD.
     * 
     * @param headFolder The folder from HEAD model
     * @return The corresponding folder in current model (existing or restored)
     */
    private IFolder findOrRestoreFolder(IFolder headFolder) {
        // First try to find the folder by ID in current model
        IIdentifier existing = lookupInCurrentModel(headFolder.getId());
        if(existing instanceof IFolder) {
            return (IFolder) existing;
        }
        
        // Not found - restore the folder hierarchy
        return restoreFolderHierarchy(headFolder);
    }

    /**
     * Remove visual objects from a diagram copy for dependencies marked as REMOVE_FROM_DIAGRAM.
     * This is called during diagram revert, after the diagram is copied but before references are resolved.
     * 
     * IMPORTANT: When removing a visual object (element), we must also remove any connections
     * that have this object as their source or target. Otherwise the connections will have
     * invalid references and fail Archi's validation.
     * 
     * @param diagramCopy The copied diagram to modify
     * @param dependencies The list of dependencies to check
     */
    private void removeVisualObjectsForDependencies(IDiagramModel diagramCopy, List<DiagramDependencyInfo> dependencies) {
        // Collect concept IDs that should be removed from the diagram
        Set<String> conceptIdsToRemove = new java.util.HashSet<>();
        for(DiagramDependencyInfo dep : dependencies) {
            if(dep.getUserChoice() == DiagramDependencyInfo.REMOVE_FROM_DIAGRAM) {
                conceptIdsToRemove.add(dep.getConceptId());
            }
        }
        
        if(conceptIdsToRemove.isEmpty()) {
            return;
        }
        
        logDebug("removeVisualObjectsForDependencies: removing " + conceptIdsToRemove.size() + " concepts from diagram"); //$NON-NLS-1$ //$NON-NLS-2$
        
        // Phase 1: Collect visual objects to remove and build a set of those objects
        Set<EObject> visualObjectsToRemove = new java.util.HashSet<>();
        List<IDiagramModelArchimateConnection> allConnections = new ArrayList<>();
        
        // Walk through all diagram contents
        for(Iterator<EObject> iter = diagramCopy.eAllContents(); iter.hasNext();) {
            EObject child = iter.next();
            
            // Check if this is an ArchiMate object referencing a concept to remove
            if(child instanceof IDiagramModelArchimateObject dmo) {
                // The archimateElement reference still points to HEAD model at this point
                IArchimateElement element = dmo.getArchimateElement();
                if(element != null && conceptIdsToRemove.contains(element.getId())) {
                    visualObjectsToRemove.add(child);
                    logDebug("  Marking for removal: object for element " + element.getId()); //$NON-NLS-1$
                }
            }
            // Collect all connections - we'll check source/target later
            else if(child instanceof IDiagramModelArchimateConnection dmc) {
                allConnections.add(dmc);
                
                // Also check if the relationship itself should be removed
                IArchimateRelationship relationship = dmc.getArchimateRelationship();
                if(relationship != null && conceptIdsToRemove.contains(relationship.getId())) {
                    visualObjectsToRemove.add(child);
                    logDebug("  Marking for removal: connection for relationship " + relationship.getId()); //$NON-NLS-1$
                }
            }
        }
        
        // Phase 2: Find connections whose source or target is a visual object being removed
        // These must also be removed, or they'll have invalid references
        for(IDiagramModelArchimateConnection conn : allConnections) {
            if(visualObjectsToRemove.contains(conn)) {
                continue; // Already marked for removal
            }
            
            // IConnectable is the common interface for source/target - cast to EObject for Set contains
            EObject source = (EObject)conn.getSource();
            EObject target = (EObject)conn.getTarget();
            
            if(visualObjectsToRemove.contains(source) || visualObjectsToRemove.contains(target)) {
                visualObjectsToRemove.add(conn);
                IArchimateRelationship relationship = conn.getArchimateRelationship();
                String relId = relationship != null ? relationship.getId() : "?"; //$NON-NLS-1$
                logDebug("  Marking for removal: connection " + relId + " (source/target being removed)"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        
        // Phase 3: Remove the collected objects
        for(EObject obj : visualObjectsToRemove) {
            EcoreUtil.remove(obj);
        }
        
        logDebug("  Removed " + visualObjectsToRemove.size() + " visual objects from diagram"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Apply a single revert
     */
    private void applyRevert(ChangeInfo info) throws IOException {
        switch(info.getChangeType()) {
            case ChangeInfo.ADDED:
                // Revert addition = remove from model
                revertAddition(info);
                break;
                
            case ChangeInfo.DELETED:
                // Revert deletion = restore from HEAD
                revertDeletion(info);
                break;
                
            case ChangeInfo.MODIFIED:
                // Revert modification = restore from HEAD
                revertModification(info);
                break;
        }
    }
    
    /**
     * Revert an addition by removing the element from the model
     */
    private void revertAddition(ChangeInfo info) {
        EObject current = info.getEObject(ChangeInfo.CURRENT);
        if(current != null) {
            // Update cache before removal
            if(current instanceof IIdentifier identifier) {
                removeFromCurrentModelCache(identifier.getId());
            }
            // Remove from parent
            EcoreUtil.remove(current);
        }
    }
    
    /**
     * Revert a deletion by restoring the element from HEAD
     */
    private void revertDeletion(ChangeInfo info) {
        EObject headObject = info.getEObject(ChangeInfo.HEAD);
        if(headObject == null) {
            logDebug("revertDeletion: headObject is null for " + info.getXMLPath()); //$NON-NLS-1$
            return;
        }
        
        logDebug("revertDeletion: " + info.getXMLPath()); //$NON-NLS-1$
        logDebug("  headObject class: " + headObject.getClass().getSimpleName()); //$NON-NLS-1$
        if(headObject instanceof IIdentifier) {
            logDebug("  headObject ID: " + ((IIdentifier)headObject).getId()); //$NON-NLS-1$
        }
        
        // Create a copy - this generates new IDs
        EObject copy = EcoreUtil.copy(headObject);
        
        // Restore original IDs from the HEAD object
        // This is important for maintaining references and enabling cascade restore
        restoreOriginalIds(headObject, copy);
        
        if(copy instanceof IIdentifier) {
            logDebug("  copy ID after restoreOriginalIds: " + ((IIdentifier)copy).getId()); //$NON-NLS-1$
        }
        
        // For diagrams with dependencies marked as REMOVE_FROM_DIAGRAM,
        // remove those visual objects BEFORE resolving references.
        // This must happen before resolveCrossModelReferences() because the concept
        // IDs still reference HEAD model objects at this point.
        if(copy instanceof IDiagramModel diagramCopy && info.hasDependencies()) {
            removeVisualObjectsForDependencies(diagramCopy, info.getDependencies());
        }
        
        // Resolve all cross-model references from HEAD model to current model
        // EcoreUtil.copy() keeps references to HEAD model objects, which won't work
        resolveCrossModelReferences(copy, headObject);
        
        // Find the original parent folder in HEAD and the corresponding folder in current model
        IFolder targetFolder = findTargetFolderFromPath(info.getXMLPath(), headObject);
        logDebug("  targetFolder: " + (targetFolder != null ? targetFolder.getName() + " (id=" + targetFolder.getId() + ")" : "null")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        
        if(targetFolder != null) {
            if(copy instanceof IArchimateElement || copy instanceof IArchimateRelationship) {
                targetFolder.getElements().add((IIdentifier)copy);
                addToCurrentModelCache((IIdentifier)copy);
                logDebug("  Added element to folder"); //$NON-NLS-1$
            }
            else if(copy instanceof IDiagramModel) {
                targetFolder.getElements().add((IDiagramModel)copy);
                addToCurrentModelCache((IDiagramModel)copy);
                logDebug("  Added diagram to folder"); //$NON-NLS-1$
            }
            else if(copy instanceof IFolder) {
                targetFolder.getFolders().add((IFolder)copy);
                addToCurrentModelCache((IFolder)copy);
                logDebug("  Added subfolder to folder"); //$NON-NLS-1$
            }
        }
    }
    
    /**
     * Resolve all cross-model references from HEAD model to current model.
     * 
     * After EcoreUtil.copy(), all EMF references still point to objects in the HEAD model.
     * This method walks through the copied object and its contents (if needed), resolving references to:
     * - Relationship source/target
     * - Element/relationship profiles (specializations)
     * - Diagram object archimateElement references
     * - Diagram connection archimateRelationship references
     * 
     * PERFORMANCE: Only iterates through children for diagrams. Elements and relationships
     * are simple objects that don't need deep traversal.
     * 
     * For diagrams, any connections that reference relationships not found in the current model
     * will be removed from the diagram.
     * 
     * @param copy The copied object (will be modified)
     * @param headObject The original object from HEAD model (for reference)
     */
    private void resolveCrossModelReferences(EObject copy, EObject headObject) {
        // Track unresolved connections for diagrams
        Set<IDiagramModelArchimateConnection> unresolvedConnections = new java.util.HashSet<>();
        
        // Resolve references on the root object
        resolveReferencesOnObjectTrackingUnresolved(copy, unresolvedConnections);
        
        // Only iterate through children for objects that have them (diagrams, folders)
        // Elements and relationships don't have children with cross-model references
        if(copy instanceof IDiagramModel || copy instanceof IFolder) {
            Iterator<EObject> it = copy.eAllContents();
            while(it.hasNext()) {
                EObject child = it.next();
                resolveReferencesOnObjectTrackingUnresolved(child, unresolvedConnections);
            }
        }
        
        // Remove connections that couldn't be resolved
        // These have relationships that don't exist in the current model
        if(!unresolvedConnections.isEmpty()) {
            logDebug("  Removing " + unresolvedConnections.size() + " unresolved connections from diagram"); //$NON-NLS-1$ //$NON-NLS-2$
            for(IDiagramModelArchimateConnection conn : unresolvedConnections) {
                EcoreUtil.remove(conn);
            }
        }
    }
    
    /**
     * Resolve cross-model references on a single object.
     * 
     * PERFORMANCE: Uses early returns and only checks relevant instanceof types.
     */
    private void resolveReferencesOnObject(EObject obj) {
        resolveReferencesOnObjectTrackingUnresolved(obj, null);
    }
    
    /**
     * Resolve cross-model references on a single object, tracking unresolved connections.
     * 
     * @param obj The object to resolve references on
     * @param unresolvedConnections If non-null, connections that fail to resolve are added here
     */
    private void resolveReferencesOnObjectTrackingUnresolved(EObject obj, Set<IDiagramModelArchimateConnection> unresolvedConnections) {
        // Handle relationship source/target
        if(obj instanceof IArchimateRelationship rel) {
            resolveRelationshipReferences(rel);
        }
        
        // Handle profiles (specializations) on elements and relationships
        if(obj instanceof IProfiles profiles && !profiles.getProfiles().isEmpty()) {
            resolveProfileReferences(profiles);
        }
        
        // Handle diagram object archimateElement reference
        if(obj instanceof IDiagramModelArchimateObject diagramObj) {
            resolveDiagramObjectReference(diagramObj);
        }
        
        // Handle diagram connection archimateRelationship reference
        if(obj instanceof IDiagramModelArchimateConnection diagramConn) {
            boolean resolved = resolveDiagramConnectionReference(diagramConn);
            if(!resolved && unresolvedConnections != null) {
                unresolvedConnections.add(diagramConn);
            }
        }
    }
    
    /**
     * Resolve relationship source/target references from HEAD model to current model.
     */
    private void resolveRelationshipReferences(IArchimateRelationship rel) {
        // Source - use getIdentifierId to handle proxies correctly
        IArchimateConcept source = rel.getSource();
        if(source != null) {
            String sourceId = getIdentifierId(source);
            IIdentifier currentSource = lookupInCurrentModel(sourceId);
            if(currentSource instanceof IArchimateConcept concept) {
                rel.setSource(concept);
                logDebug("  resolved relationship source: " + concept.getName()); //$NON-NLS-1$
            } else {
                logDebug("  WARNING: relationship source not found: " + sourceId); //$NON-NLS-1$
            }
        }
        
        // Target - use getIdentifierId to handle proxies correctly
        IArchimateConcept target = rel.getTarget();
        if(target != null) {
            String targetId = getIdentifierId(target);
            IIdentifier currentTarget = lookupInCurrentModel(targetId);
            if(currentTarget instanceof IArchimateConcept concept) {
                rel.setTarget(concept);
                logDebug("  resolved relationship target: " + concept.getName()); //$NON-NLS-1$
            } else {
                logDebug("  WARNING: relationship target not found: " + targetId); //$NON-NLS-1$
            }
        }
    }
    
    /**
     * Resolve profile references from HEAD model to current model.
     * Profiles are stored in the model's profile folder and referenced by elements.
     * 
     * If a profile doesn't exist in the current model, it will be copied from HEAD
     * and added to the current model's profiles.
     */
    private void resolveProfileReferences(IProfiles profilesObj) {
        List<IProfile> headProfiles = new ArrayList<>(profilesObj.getProfiles());
        if(headProfiles.isEmpty()) {
            return;
        }
        
        // Clear and re-add profiles from current model (or copied from HEAD)
        profilesObj.getProfiles().clear();
        
        for(IProfile headProfile : headProfiles) {
            // Get the profile ID - handle both resolved objects and proxies
            // For proxies (from cross-file references like folder.xml#id-xxx),
            // we need to extract the ID from the URI fragment, not getId()
            String profileId = getIdentifierId(headProfile);
            logDebug("  looking up profile: " + profileId + " (proxy=" + headProfile.eIsProxy() + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            
            IIdentifier currentProfile = lookupInCurrentModel(profileId);
            if(currentProfile instanceof IProfile profile) {
                profilesObj.getProfiles().add(profile);
                logDebug("  resolved profile: " + profile.getName()); //$NON-NLS-1$
            } else {
                // Profile doesn't exist in current model - try to get from HEAD model
                IProfile resolvedHeadProfile = resolveProfileFromHead(profileId);
                if(resolvedHeadProfile != null) {
                    // Copy the resolved profile from HEAD
                    IProfile copiedProfile = copyProfileFromHead(resolvedHeadProfile);
                    if(copiedProfile != null) {
                        profilesObj.getProfiles().add(copiedProfile);
                        logDebug("  copied profile from HEAD: " + copiedProfile.getName()); //$NON-NLS-1$
                    } else {
                        logDebug("  WARNING: could not copy profile: " + profileId); //$NON-NLS-1$
                    }
                } else {
                    logDebug("  WARNING: profile not found in HEAD or current model: " + profileId); //$NON-NLS-1$
                }
            }
        }
    }
    
    /**
     * Get the ID from an IIdentifier object, handling both resolved and proxy objects.
     * 
     * For proxy objects (from cross-file references like folder.xml#id-xxx),
     * we extract the ID from the EMF URI fragment rather than calling getId()
     * which would return the proxy's internal ID.
     * 
     * This method is used by all cross-model reference resolution to correctly
     * identify objects that may have been loaded as proxies from GRAFICO format.
     * 
     * @param identifier The identifier (may be a proxy)
     * @return The object's ID, or null if identifier is null
     */
    private String getIdentifierId(IIdentifier identifier) {
        if(identifier == null) {
            return null;
        }
        if(identifier.eIsProxy()) {
            // For proxies, extract ID from URI fragment (e.g., "folder.xml#id-xxx" -> "id-xxx")
            return EcoreUtil.getURI(identifier).fragment();
        }
        return identifier.getId();
    }
    
    /**
     * Resolve a profile by ID from the HEAD model.
     * Uses cached ID lookup for O(1) performance.
     * 
     * @param profileId The profile ID
     * @return The resolved profile from HEAD model, or null if not found
     */
    private IProfile resolveProfileFromHead(String profileId) {
        if(fHeadModel == null) {
            return null;
        }
        IIdentifier obj = lookupInHeadModel(profileId);
        return obj instanceof IProfile ? (IProfile) obj : null;
    }
    
    /**
     * Copy a profile from HEAD model to current model.
     * The profile is added to the current model's profiles collection if not already present.
     * 
     * @param headProfile The profile from HEAD model
     * @return The profile in current model (either existing or newly copied), or null if copy failed
     */
    private IProfile copyProfileFromHead(IProfile headProfile) {
        // Check if already added (in case multiple elements reference the same deleted profile)
        String profileId = headProfile.getId();
        IIdentifier existing = lookupInCurrentModel(profileId);
        if(existing instanceof IProfile existingProfile) {
            logDebug("  profile already in current model: " + existingProfile.getName()); //$NON-NLS-1$
            return existingProfile;
        }
        
        // Copy the profile
        IProfile copiedProfile = EcoreUtil.copy(headProfile);
        
        // Restore the original ID (EcoreUtil.copy generates new IDs)
        copiedProfile.setId(profileId);
        
        // Add to current model's profiles and update cache
        fCurrentModel.getProfiles().add(copiedProfile);
        addToCurrentModelCache(copiedProfile);
        logDebug("  added profile to current model: " + copiedProfile.getName() + " (id=" + copiedProfile.getId() + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        
        return copiedProfile;
    }
    
    /**
     * Resolve diagram object's archimateElement reference from HEAD model to current model.
     */
    private void resolveDiagramObjectReference(IDiagramModelArchimateObject diagramObj) {
        IArchimateElement headElement = diagramObj.getArchimateElement();
        if(headElement != null) {
            String elementId = getIdentifierId(headElement);
            IIdentifier currentElement = lookupInCurrentModel(elementId);
            if(currentElement instanceof IArchimateElement element) {
                diagramObj.setArchimateElement(element);
                logDebug("  resolved diagram object element: " + element.getName()); //$NON-NLS-1$
            } else {
                logDebug("  WARNING: diagram object element not found: " + elementId); //$NON-NLS-1$
            }
        }
    }
    
    /**
     * Resolve diagram connection's archimateRelationship reference from HEAD model to current model.
     * 
     * @param diagramConn The diagram connection to resolve
     * @return true if the relationship was successfully resolved, false if the relationship doesn't exist in current model
     */
    private boolean resolveDiagramConnectionReference(IDiagramModelArchimateConnection diagramConn) {
        IArchimateRelationship headRel = diagramConn.getArchimateRelationship();
        if(headRel != null) {
            String relId = getIdentifierId(headRel);
            IIdentifier currentRel = lookupInCurrentModel(relId);
            if(currentRel instanceof IArchimateRelationship rel) {
                diagramConn.setArchimateRelationship(rel);
                logDebug("  resolved diagram connection relationship: " + rel.getName()); //$NON-NLS-1$
                
                // Validate that connection endpoints match relationship endpoints
                if(!validateConnectionEndpoints(diagramConn, rel)) {
                    logDebug("  WARNING: connection endpoints don't match relationship - will remove"); //$NON-NLS-1$
                    return false;
                }
                
                return true;
            } else {
                logDebug("  WARNING: diagram connection relationship not found: " + relId); //$NON-NLS-1$
                return false;
            }
        }
        return true; // No relationship to resolve
    }
    
    /**
     * Validate that a diagram connection's visual endpoints match the relationship's endpoints.
     * 
     * Archi's validation rule:
     * - connection.source.archimateElement == connection.archimateRelationship.source
     * - connection.target.archimateElement == connection.archimateRelationship.target
     * 
     * @param conn The diagram connection
     * @param rel The resolved relationship
     * @return true if endpoints match, false if there's a mismatch
     */
    private boolean validateConnectionEndpoints(IDiagramModelArchimateConnection conn, IArchimateRelationship rel) {
        String connId = conn.getId();
        
        // Get visual endpoints
        IConnectable visualSource = conn.getSource();
        IConnectable visualTarget = conn.getTarget();
        
        // Get relationship endpoints
        IArchimateConcept relSource = rel.getSource();
        IArchimateConcept relTarget = rel.getTarget();
        
        boolean valid = true;
        
        // Log detailed info for debugging
        logDebug("    validateConnectionEndpoints for connection " + connId); //$NON-NLS-1$
        logDebug("      relationship: " + rel.getId() + " (" + rel.eClass().getName() + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        logDebug("      rel.source: " + (relSource != null ? relSource.getId() + " (" + relSource.getName() + ")" : "null")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        logDebug("      rel.target: " + (relTarget != null ? relTarget.getId() + " (" + relTarget.getName() + ")" : "null")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        
        // Check source
        if(visualSource instanceof IDiagramModelArchimateObject visualSourceObj) {
            IArchimateElement visualSourceElement = visualSourceObj.getArchimateElement();
            logDebug("      visualSource.element: " + (visualSourceElement != null ? visualSourceElement.getId() + " (" + visualSourceElement.getName() + ")" : "null")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            
            if(visualSourceElement != relSource) {
                logDebug("      MISMATCH: visual source element != relationship source"); //$NON-NLS-1$
                valid = false;
            }
        } else if(visualSource instanceof IDiagramModelArchimateConnection nestedConn) {
            // Connection-to-connection (relationship on relationship)
            IArchimateRelationship nestedRel = nestedConn.getArchimateRelationship();
            logDebug("      visualSource is nested connection: " + (nestedRel != null ? nestedRel.getId() : "null")); //$NON-NLS-1$ //$NON-NLS-2$
            if(nestedRel != relSource) {
                logDebug("      MISMATCH: nested connection relationship != relationship source"); //$NON-NLS-1$
                valid = false;
            }
        } else {
            logDebug("      visualSource type: " + (visualSource != null ? visualSource.getClass().getSimpleName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        
        // Check target
        if(visualTarget instanceof IDiagramModelArchimateObject visualTargetObj) {
            IArchimateElement visualTargetElement = visualTargetObj.getArchimateElement();
            logDebug("      visualTarget.element: " + (visualTargetElement != null ? visualTargetElement.getId() + " (" + visualTargetElement.getName() + ")" : "null")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            
            if(visualTargetElement != relTarget) {
                logDebug("      MISMATCH: visual target element != relationship target"); //$NON-NLS-1$
                valid = false;
            }
        } else if(visualTarget instanceof IDiagramModelArchimateConnection nestedConn) {
            // Connection-to-connection (relationship on relationship)
            IArchimateRelationship nestedRel = nestedConn.getArchimateRelationship();
            logDebug("      visualTarget is nested connection: " + (nestedRel != null ? nestedRel.getId() : "null")); //$NON-NLS-1$ //$NON-NLS-2$
            if(nestedRel != relTarget) {
                logDebug("      MISMATCH: nested connection relationship != relationship target"); //$NON-NLS-1$
                valid = false;
            }
        } else {
            logDebug("      visualTarget type: " + (visualTarget != null ? visualTarget.getClass().getSimpleName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        
        return valid;
    }
    
    /**
     * Resolve relationship source/target references from HEAD model to current model.
     * 
     * After EcoreUtil.copy(), the source and target are still pointing to objects
     * in the HEAD model. We need to find the corresponding objects in the current model
     * and update the references.
     * 
     * @param relCopy The copied relationship (in current model)
     * @param headRel The original relationship from HEAD model
     * @deprecated Use resolveCrossModelReferences instead
     */
    @SuppressWarnings("unused")
    private void resolveRelationshipReferences(IArchimateRelationship relCopy, IArchimateRelationship headRel) {
        // Get source/target from HEAD relationship (which has correct IDs)
        IArchimateConcept headSource = headRel.getSource();
        IArchimateConcept headTarget = headRel.getTarget();
        
        if(headSource != null) {
            String sourceId = headSource.getId();
            logDebug("  resolving source ID: " + sourceId); //$NON-NLS-1$
            
            // Find corresponding object in current model
            IIdentifier currentSource = lookupInCurrentModel(sourceId);
            if(currentSource instanceof IArchimateConcept concept) {
                relCopy.setSource(concept);
                logDebug("  resolved source to: " + concept.getName()); //$NON-NLS-1$
            } else {
                logDebug("  WARNING: source not found in current model: " + sourceId); //$NON-NLS-1$
            }
        }
        
        if(headTarget != null) {
            String targetId = headTarget.getId();
            logDebug("  resolving target ID: " + targetId); //$NON-NLS-1$
            
            // Find corresponding object in current model
            IIdentifier currentTarget = lookupInCurrentModel(targetId);
            if(currentTarget instanceof IArchimateConcept concept) {
                relCopy.setTarget(concept);
                logDebug("  resolved target to: " + concept.getName()); //$NON-NLS-1$
            } else {
                logDebug("  WARNING: target not found in current model: " + targetId); //$NON-NLS-1$
            }
        }
    }
    
    /**
     * Recursively restore original IDs from source to target after EcoreUtil.copy()
     */
    private void restoreOriginalIds(EObject source, EObject target) {
        // Restore ID on this object
        if(source instanceof IIdentifier sourceId && target instanceof IIdentifier targetId) {
            targetId.setId(sourceId.getId());
        }
        
        // Recursively restore IDs on contained objects
        Iterator<EObject> sourceIt = source.eAllContents();
        Iterator<EObject> targetIt = target.eAllContents();
        
        while(sourceIt.hasNext() && targetIt.hasNext()) {
            EObject sourceChild = sourceIt.next();
            EObject targetChild = targetIt.next();
            
            if(sourceChild instanceof IIdentifier sourceChildId && targetChild instanceof IIdentifier targetChildId) {
                targetChildId.setId(sourceChildId.getId());
            }
        }
    }
    
    /**
     * Revert a modification by replacing current values with HEAD values
     */
    private void revertModification(ChangeInfo info) {
        EObject current = info.getEObject(ChangeInfo.CURRENT);
        EObject head = info.getEObject(ChangeInfo.HEAD);
        
        if(current == null || head == null) {
            return;
        }
        
        logDebug("revertModification: " + info.getXMLPath()); //$NON-NLS-1$
        
        // For diagrams, we need special handling because diagrams contain child objects
        // (visual objects and connections) that reference other model objects.
        // Simple property copying would give us references to HEAD model objects,
        // which would be wrong. Instead, we need to:
        // 1. Remove all existing children from current diagram
        // 2. Copy children from HEAD diagram (using EcoreUtil.copy for deep copy)
        // 3. Resolve cross-model references in the copied children
        if(current instanceof IDiagramModel currentDiagram && head instanceof IDiagramModel headDiagram) {
            revertDiagramModification(currentDiagram, headDiagram, info);
            return;
        }
        
        // For non-diagrams, copy all features from HEAD to current
        for(org.eclipse.emf.ecore.EStructuralFeature feature : head.eClass().getEAllStructuralFeatures()) {
            if(feature.isChangeable() && !feature.isDerived()) {
                try {
                    current.eSet(feature, head.eGet(feature));
                }
                catch(Exception e) {
                    // Some features may not be copyable, skip them
                    logDebug("  failed to copy feature " + feature.getName() + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        
        // After copying features, resolve any cross-model references
        // This handles profiles, relationship source/target, diagram object references, etc.
        resolveCrossModelReferences(current, head);
    }
    
    /**
     * Revert a modified diagram by replacing its contents with HEAD contents.
     * 
     * This method handles the complex case of diagrams which contain child objects
     * (IDiagramModelArchimateObject, IDiagramModelArchimateConnection, etc.) that
     * reference other model objects.
     * 
     * The approach is:
     * 1. Copy scalar properties (name, documentation, viewpoint) from HEAD
     * 2. Clear current diagram's children
     * 3. Copy children from HEAD using EcoreUtil.copy (deep copy with new IDs)
     * 4. Restore original IDs on copied children
     * 5. Remove visual objects for dependencies marked as REMOVE_FROM_DIAGRAM
     * 6. Resolve cross-model references in copied children
     * 7. Add copied children to current diagram
     * 
     * @param currentDiagram The diagram in the current model
     * @param headDiagram The diagram from HEAD model
     * @param info The ChangeInfo with dependency information
     */
    private void revertDiagramModification(IDiagramModel currentDiagram, IDiagramModel headDiagram, ChangeInfo info) {
        logDebug("revertDiagramModification: " + headDiagram.getName()); //$NON-NLS-1$
        
        // Step 1: Copy scalar properties from HEAD to current
        currentDiagram.setName(headDiagram.getName());
        currentDiagram.setDocumentation(headDiagram.getDocumentation());
        
        // Copy Archimate-specific properties if applicable
        if(currentDiagram instanceof IArchimateDiagramModel current && headDiagram instanceof IArchimateDiagramModel headArchi) {
            current.setViewpoint(headArchi.getViewpoint());
            current.setConnectionRouterType(headArchi.getConnectionRouterType());
        }
        
        // Step 2: Clear current diagram's children
        currentDiagram.getChildren().clear();
        
        // Step 3: Copy children from HEAD (deep copy)
        List<IDiagramModelObject> copiedChildren = new ArrayList<>();
        for(IDiagramModelObject headChild : headDiagram.getChildren()) {
            EObject copy = EcoreUtil.copy(headChild);
            if(copy instanceof IDiagramModelObject dmo) {
                copiedChildren.add(dmo);
            }
        }
        
        // Step 4: Restore original IDs on copied children
        for(int i = 0; i < copiedChildren.size(); i++) {
            restoreOriginalIds(headDiagram.getChildren().get(i), copiedChildren.get(i));
        }
        
        // Step 5: Remove visual objects for dependencies marked as REMOVE_FROM_DIAGRAM
        // For this, we need to temporarily add children to a container, use a list-based approach
        if(info.hasDependencies()) {
            removeVisualObjectsFromList(copiedChildren, info.getDependencies());
        }
        
        // Step 6: Resolve cross-model references in copied children
        // Track connections that fail to resolve so we can remove them
        Set<IDiagramModelArchimateConnection> unresolvedConnections = new java.util.HashSet<>();
        for(IDiagramModelObject child : copiedChildren) {
            resolveReferencesOnObjectTrackingUnresolved(child, unresolvedConnections);
            // Recurse into all descendants
            for(Iterator<EObject> it = child.eAllContents(); it.hasNext();) {
                resolveReferencesOnObjectTrackingUnresolved(it.next(), unresolvedConnections);
            }
        }
        
        // Step 6b: Remove connections that couldn't be resolved
        // These have relationships that don't exist in the current model
        if(!unresolvedConnections.isEmpty()) {
            logDebug("  Removing " + unresolvedConnections.size() + " unresolved connections"); //$NON-NLS-1$ //$NON-NLS-2$
            for(IDiagramModelArchimateConnection conn : unresolvedConnections) {
                EcoreUtil.remove(conn);
            }
        }
        
        // Step 7: Add copied children to current diagram
        currentDiagram.getChildren().addAll(copiedChildren);
        
        logDebug("  Reverted diagram with " + copiedChildren.size() + " top-level children"); //$NON-NLS-1$ //$NON-NLS-2$
    }
    
    /**
     * Remove visual objects from a list of diagram children for dependencies marked as REMOVE_FROM_DIAGRAM.
     * 
     * This is similar to removeVisualObjectsForDependencies but works on a list rather than a diagram.
     * 
     * @param children The list of diagram children to modify
     * @param dependencies The list of dependencies to check
     */
    private void removeVisualObjectsFromList(List<IDiagramModelObject> children, List<DiagramDependencyInfo> dependencies) {
        // Collect concept IDs that should be removed
        Set<String> conceptIdsToRemove = new java.util.HashSet<>();
        for(DiagramDependencyInfo dep : dependencies) {
            if(dep.getUserChoice() == DiagramDependencyInfo.REMOVE_FROM_DIAGRAM) {
                conceptIdsToRemove.add(dep.getConceptId());
            }
        }
        
        if(conceptIdsToRemove.isEmpty()) {
            return;
        }
        
        logDebug("removeVisualObjectsFromList: removing concepts " + conceptIdsToRemove); //$NON-NLS-1$
        
        // Phase 1: Collect visual objects to remove and connections
        // Use Set<EObject> since we store both IDiagramModelObject and IDiagramModelArchimateConnection
        Set<EObject> objectsToRemove = new java.util.HashSet<>();
        List<IDiagramModelArchimateConnection> allConnections = new ArrayList<>();
        
        // Recursive helper to collect objects
        collectObjectsToRemove(children, conceptIdsToRemove, objectsToRemove, allConnections);
        
        // Phase 2: Find connections whose source or target is being removed
        for(IDiagramModelArchimateConnection conn : allConnections) {
            if(objectsToRemove.contains(conn)) {
                continue;
            }
            
            // IConnectable is the common interface for source/target - cast to EObject for Set contains
            EObject source = (EObject)conn.getSource();
            EObject target = (EObject)conn.getTarget();
            
            if(objectsToRemove.contains(source) || objectsToRemove.contains(target)) {
                objectsToRemove.add(conn);
                logDebug("  Marking connection for removal (source/target removed)"); //$NON-NLS-1$
            }
        }
        
        // Phase 3: Remove from children list and nested containers
        removeObjectsRecursively(children, objectsToRemove);
        
        logDebug("  Removed " + objectsToRemove.size() + " visual objects"); //$NON-NLS-1$ //$NON-NLS-2$
    }
    
    /**
     * Recursively collect objects to remove from a list of diagram children.
     */
    private void collectObjectsToRemove(List<IDiagramModelObject> children, Set<String> conceptIdsToRemove,
            Set<EObject> objectsToRemove, List<IDiagramModelArchimateConnection> allConnections) {
        
        for(IDiagramModelObject child : children) {
            // Check ArchiMate objects
            if(child instanceof IDiagramModelArchimateObject dmo) {
                IArchimateElement element = dmo.getArchimateElement();
                if(element != null && conceptIdsToRemove.contains(element.getId())) {
                    objectsToRemove.add(child);
                }
                
                // Collect connections from this object
                for(IDiagramModelConnection conn : dmo.getSourceConnections()) {
                    if(conn instanceof IDiagramModelArchimateConnection dmc) {
                        allConnections.add(dmc);
                        
                        IArchimateRelationship rel = dmc.getArchimateRelationship();
                        if(rel != null && conceptIdsToRemove.contains(rel.getId())) {
                            objectsToRemove.add(dmc);
                        }
                    }
                }
            }
            
            // Recurse into containers
            if(child instanceof IDiagramModelContainer container) {
                collectObjectsToRemove(container.getChildren(), conceptIdsToRemove, objectsToRemove, allConnections);
            }
        }
    }
    
    /**
     * Recursively remove objects from a list of diagram children.
     * 
     * IMPORTANT: Connections must be removed BEFORE their source objects are removed,
     * because connections are contained by their source object. If we remove the source
     * first, the connection becomes orphaned.
     */
    private void removeObjectsRecursively(List<IDiagramModelObject> children, Set<EObject> objectsToRemove) {
        // FIRST: Remove connections from ALL objects (before removing any visual objects)
        // This includes objects that will be removed, to properly clean up connections
        for(IDiagramModelObject child : new ArrayList<>(children)) {
            // Remove connections from this object's source and target lists
            child.getSourceConnections().removeIf(conn -> objectsToRemove.contains(conn));
            child.getTargetConnections().removeIf(conn -> objectsToRemove.contains(conn));
            
            // Recurse into containers to remove connections from nested objects
            if(child instanceof IDiagramModelContainer container) {
                removeConnectionsRecursively(container.getChildren(), objectsToRemove);
            }
        }
        
        // SECOND: Remove visual objects
        children.removeIf(objectsToRemove::contains);
        
        // Recurse into containers to remove nested visual objects
        for(IDiagramModelObject child : children) {
            if(child instanceof IDiagramModelContainer container) {
                removeObjectsRecursively(container.getChildren(), objectsToRemove);
            }
        }
    }
    
    /**
     * Recursively remove connections from diagram children (helper for removeObjectsRecursively).
     */
    private void removeConnectionsRecursively(List<IDiagramModelObject> children, Set<EObject> objectsToRemove) {
        for(IDiagramModelObject child : children) {
            child.getSourceConnections().removeIf(conn -> objectsToRemove.contains(conn));
            child.getTargetConnections().removeIf(conn -> objectsToRemove.contains(conn));
            
            if(child instanceof IDiagramModelContainer container) {
                removeConnectionsRecursively(container.getChildren(), objectsToRemove);
            }
        }
    }

    /**
     * Find the target folder in the current model based on the XML path from GRAFICO.
     * 
     * The XML path format is: model/<type>/<subfolder1>/<subfolder2>/.../<id>/<id>.xml
     * For example: model/business/MySubfolder/id-123/id-123.xml
     * 
     * We need to find the parent folder in the HEAD model and then find/create
     * the corresponding folder path in the current model.
     * 
     * If the folder doesn't exist in the current model, we walk up the HEAD folder
     * hierarchy until we find an existing folder (or the root), then recreate the
     * missing folder chain.
     * 
     * @param xmlPath The XML path from git status
     * @param headObject The object from HEAD model (to find its parent)
     * @return The target folder in the current model, or null if not found
     */
    private IFolder findTargetFolderFromPath(String xmlPath, EObject headObject) {
        // Find the parent folder of the object in the HEAD model
        EObject parent = headObject.eContainer();
        logDebug("findTargetFolderFromPath: parent = " + parent); //$NON-NLS-1$
        
        if(!(parent instanceof IFolder headFolder)) {
            logDebug("  parent is not IFolder, returning null"); //$NON-NLS-1$
            return null;
        }
        
        // Get the folder's ID - we'll use this to find the corresponding folder in current model
        String folderId = headFolder.getId();
        logDebug("  headFolder: " + headFolder.getName() + " (id=" + folderId + ", type=" + headFolder.getType() + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        
        // Try to find folder with same ID in current model
        IIdentifier currentFolder = lookupInCurrentModel(folderId);
        logDebug("  found in current model by ID: " + currentFolder); //$NON-NLS-1$
        
        if(currentFolder instanceof IFolder) {
            return (IFolder) currentFolder;
        }
        
        // Folder doesn't exist in current model - restore the folder hierarchy from HEAD
        logDebug("  folder not found in current model, restoring from HEAD hierarchy"); //$NON-NLS-1$
        return restoreFolderHierarchy(headFolder);
    }
    
    /**
     * Restore a folder hierarchy from HEAD model to current model.
     * 
     * Walks up the HEAD folder hierarchy until finding an existing folder (or root folder)
     * in the current model, then recreates the missing folders down to the target.
     * 
     * @param headFolder The folder from HEAD model to restore
     * @return The restored (or existing) folder in current model
     */
    private IFolder restoreFolderHierarchy(IFolder headFolder) {
        // Check if this folder already exists in current model
        IIdentifier existing = lookupInCurrentModel(headFolder.getId());
        if(existing instanceof IFolder) {
            return (IFolder) existing;
        }
        
        // Check if this is a root folder type (has no parent or parent is the model)
        EObject parentContainer = headFolder.eContainer();
        if(parentContainer instanceof IArchimateModel || headFolder.getType() != FolderType.USER) {
            // This is a root-level folder - get it from current model by type
            if(headFolder.getType() != FolderType.USER) {
                return fCurrentModel.getFolder(headFolder.getType());
            }
            // USER type folder at root level shouldn't happen, but handle it
            logDebug("  WARNING: USER folder at root level: " + headFolder.getName()); //$NON-NLS-1$
            return null;
        }
        
        // Parent is another folder - recursively restore it first
        if(!(parentContainer instanceof IFolder parentHeadFolder)) {
            logDebug("  WARNING: parent is not a folder: " + parentContainer); //$NON-NLS-1$
            return null;
        }
        
        IFolder parentInCurrent = restoreFolderHierarchy(parentHeadFolder);
        if(parentInCurrent == null) {
            logDebug("  WARNING: could not restore parent folder"); //$NON-NLS-1$
            return null;
        }
        
        // Now copy the folder from HEAD (without contents) and add to parent
        IFolder folderCopy = EcoreUtil.copy(headFolder);
        
        // Restore original ID
        folderCopy.setId(headFolder.getId());
        
        // Clear elements and subfolders (we only want the folder itself)
        folderCopy.getElements().clear();
        folderCopy.getFolders().clear();
        
        // Add to parent and update cache
        parentInCurrent.getFolders().add(folderCopy);
        addToCurrentModelCache(folderCopy);
        logDebug("  restored folder: " + folderCopy.getName() + " (id=" + folderCopy.getId() + ") under " + parentInCurrent.getName()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        
        return folderCopy;
    }
    
    /**
     * Find a folder in the current model based on the GRAFICO path.
     * 
     * @param xmlPath The path like "model/business/subfolder/id/id.xml"
     * @return The folder in the current model
     */
    private IFolder findFolderByPath(String xmlPath) {
        // Parse the path: model/<type>/...
        String[] parts = xmlPath.replace("\\", "/").split("/"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if(parts.length < 2) {
            return null;
        }
        
        // parts[0] = "model", parts[1] = folder type
        String folderTypeName = parts[1];
        FolderType folderType = getFolderTypeFromName(folderTypeName);
        
        if(folderType == null) {
            return null;
        }
        
        // Get the root folder for this type
        return fCurrentModel.getFolder(folderType);
    }
    
    /**
     * Map folder name from GRAFICO path to FolderType
     */
    private FolderType getFolderTypeFromName(String name) {
        return switch(name.toLowerCase()) {
            case "strategy" -> FolderType.STRATEGY; //$NON-NLS-1$
            case "business" -> FolderType.BUSINESS; //$NON-NLS-1$
            case "application" -> FolderType.APPLICATION; //$NON-NLS-1$
            case "technology" -> FolderType.TECHNOLOGY; //$NON-NLS-1$
            case "motivation" -> FolderType.MOTIVATION; //$NON-NLS-1$
            case "implementation_migration" -> FolderType.IMPLEMENTATION_MIGRATION; //$NON-NLS-1$
            case "other" -> FolderType.OTHER; //$NON-NLS-1$
            case "relations" -> FolderType.RELATIONS; //$NON-NLS-1$
            case "diagrams" -> FolderType.DIAGRAMS; //$NON-NLS-1$
            default -> null;
        };
    }
    
    /**
     * @return The repository
     */
    public IArchiRepository getArchiRepository() {
        return fArchiRepo;
    }
    
    /**
     * @return The list of change infos
     */
    public List<ChangeInfo> getChangeInfos() {
        return fChangeInfos;
    }
    
    /**
     * @return The current in-memory model
     */
    public IArchimateModel getCurrentModel() {
        return fCurrentModel;
    }
    
    /**
     * @return The HEAD model (from last commit), or null if not yet loaded
     */
    public IArchimateModel getHeadModel() {
        return fHeadModel;
    }
    
    /**
     * @return true if HEAD model has been loaded
     */
    public boolean isHeadModelLoaded() {
        return fHeadModel != null;
    }
    
    /**
     * @return true if there are any changes marked for revert
     */
    public boolean hasReverts() {
        for(ChangeInfo info : fChangeInfos) {
            if(info.isRevert()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extract a model from a git ref.
     * 
     * PERFORMANCE: Uses parallel file extraction to speed up the process.
     * Files are extracted to a temp folder, then imported.
     * 
     * @param ref The git ref (e.g., "HEAD")
     * @return The loaded model
     * @throws IOException If extraction fails
     */
    private IArchimateModel extractModel(String ref) throws IOException {
        // OPTIMIZED: Load directly from git objects without any filesystem I/O
        // This is much faster than extracting to temp folder and reading back
        try(Repository repository = Git.open(fArchiRepo.getLocalRepositoryFolder()).getRepository()) {
            RevCommit commit = null;
            
            // Get the commit
            try(RevWalk revWalk = new RevWalk(repository)) {
                ObjectId objectID = repository.resolve(ref);
                if(objectID != null) {
                    commit = revWalk.parseCommit(objectID);
                }
                revWalk.dispose();
            }
            
            if(commit == null) {
                throw new IOException(Messages.ChangeReviewHandler_1);
            }
            
            // Use the optimized commit-based importer (no filesystem I/O!)
            GraficoModelImporter importer = new GraficoModelImporter(repository, commit.getTree());
            return importer.importFromCommit(fProgressMonitor);
        }
        catch(IOException e) {
            throw e;
        }
        catch(Exception e) {
            throw new IOException(e);
        }
    }
    
    // ==================== HEAD Model ID Cache ====================
    // These methods provide O(1) lookups in the HEAD model.
    // The cache is safe because HEAD model is immutable during revert operations.
    // Current model lookups must NOT use this cache - the model changes during reverts.
    
    /**
     * Build the HEAD model ID cache for O(1) lookups.
     * Called when HEAD model is loaded.
     */
    private void buildHeadModelIdCache() {
        if(fHeadModel == null) {
            return;
        }
        
        fHeadModelIdCache = new HashMap<>();
        
        // Cache all identifiable objects from HEAD model
        for(Iterator<EObject> iter = fHeadModel.eAllContents(); iter.hasNext();) {
            EObject eObject = iter.next();
            if(eObject instanceof IIdentifier identifier) {
                fHeadModelIdCache.put(identifier.getId(), identifier);
            }
        }
        
        logDebug("Built HEAD model ID cache with " + fHeadModelIdCache.size() + " entries"); //$NON-NLS-1$ //$NON-NLS-2$
    }
    
    /**
     * Look up an object by ID in the HEAD model cache.
     * Falls back to linear search if cache is not available.
     * 
     * @param id The ID to look up
     * @return The object, or null if not found
     */
    private IIdentifier lookupInHeadModel(String id) {
        if(id == null) {
            return null;
        }
        
        // Use cache if available
        if(fHeadModelIdCache != null) {
            return fHeadModelIdCache.get(id);
        }
        
        // Fallback to linear search
        EObject obj = ArchimateModelUtils.getObjectByID(fHeadModel, id);
        return obj instanceof IIdentifier ? (IIdentifier) obj : null;
    }
    
    // ==================== Current Model ID Cache ====================
    // These methods provide O(1) lookups in the current model.
    // The cache is incrementally updated as objects are added/removed during reverts.
    
    /**
     * Build the current model ID cache for O(1) lookups.
     * Called at the start of applyReverts().
     */
    private void buildCurrentModelIdCache() {
        if(fCurrentModel == null) {
            return;
        }
        
        fCurrentModelIdCache = new HashMap<>();
        
        // Cache all identifiable objects from current model
        for(Iterator<EObject> iter = fCurrentModel.eAllContents(); iter.hasNext();) {
            EObject eObject = iter.next();
            if(eObject instanceof IIdentifier identifier) {
                fCurrentModelIdCache.put(identifier.getId(), identifier);
            }
        }
        
        logDebug("Built current model ID cache with " + fCurrentModelIdCache.size() + " entries"); //$NON-NLS-1$ //$NON-NLS-2$
    }
    
    /**
     * Look up an object by ID in the current model cache.
     * Falls back to linear search if cache is not available.
     * 
     * @param id The ID to look up
     * @return The object, or null if not found
     */
    private IIdentifier lookupInCurrentModel(String id) {
        if(id == null) {
            return null;
        }
        
        // Use cache if available
        if(fCurrentModelIdCache != null) {
            return fCurrentModelIdCache.get(id);
        }
        
        // Fallback to linear search
        EObject obj = ArchimateModelUtils.getObjectByID(fCurrentModel, id);
        return obj instanceof IIdentifier ? (IIdentifier) obj : null;
    }
    
    /**
     * Add an object to the current model cache.
     * Call this when adding objects to the model during reverts.
     * 
     * @param obj The object to add to the cache
     */
    private void addToCurrentModelCache(IIdentifier obj) {
        if(fCurrentModelIdCache != null && obj != null) {
            fCurrentModelIdCache.put(obj.getId(), obj);
        }
    }
    
    /**
     * Remove an object from the current model cache.
     * Call this when removing objects from the model during reverts.
     * 
     * @param id The ID of the object to remove
     */
    private void removeFromCurrentModelCache(String id) {
        if(fCurrentModelIdCache != null && id != null) {
            fCurrentModelIdCache.remove(id);
        }
    }
}
