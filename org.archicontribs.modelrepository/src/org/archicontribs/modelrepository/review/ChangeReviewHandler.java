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
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
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
        // Build current model cache for O(1) lookups during reverts
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
            
            // For deletions, we need to restore in dependency order
            // For additions/modifications, order doesn't matter as much
            if(info.getChangeType() == ChangeInfo.DELETED) {
                EObject headObj = info.getEObject(ChangeInfo.HEAD);
                if(headObj instanceof IArchimateRelationship) {
                    relationReverts.add(info);
                } else if(headObj instanceof IDiagramModel) {
                    diagramReverts.add(info);
                } else if(headObj instanceof IArchimateElement) {
                    elementReverts.add(info);
                } else {
                    otherReverts.add(info);
                }
            } else {
                // Additions and modifications can be processed in any order
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
     * @param copy The copied object (will be modified)
     * @param headObject The original object from HEAD model (for reference)
     */
    private void resolveCrossModelReferences(EObject copy, EObject headObject) {
        // Resolve references on the root object
        resolveReferencesOnObject(copy);
        
        // Only iterate through children for objects that have them (diagrams, folders)
        // Elements and relationships don't have children with cross-model references
        if(copy instanceof IDiagramModel || copy instanceof IFolder) {
            Iterator<EObject> it = copy.eAllContents();
            while(it.hasNext()) {
                EObject child = it.next();
                resolveReferencesOnObject(child);
            }
        }
    }
    
    /**
     * Resolve cross-model references on a single object.
     * 
     * PERFORMANCE: Uses early returns and only checks relevant instanceof types.
     */
    private void resolveReferencesOnObject(EObject obj) {
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
            resolveDiagramConnectionReference(diagramConn);
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
     */
    private void resolveDiagramConnectionReference(IDiagramModelArchimateConnection diagramConn) {
        IArchimateRelationship headRel = diagramConn.getArchimateRelationship();
        if(headRel != null) {
            String relId = getIdentifierId(headRel);
            IIdentifier currentRel = lookupInCurrentModel(relId);
            if(currentRel instanceof IArchimateRelationship rel) {
                diagramConn.setArchimateRelationship(rel);
                logDebug("  resolved diagram connection relationship: " + rel.getName()); //$NON-NLS-1$
            } else {
                logDebug("  WARNING: diagram connection relationship not found: " + relId); //$NON-NLS-1$
            }
        }
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
        
        // Copy all features from HEAD to current
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
