/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.merge;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.archicontribs.modelrepository.grafico.GraficoModelImporter;
import org.archicontribs.modelrepository.grafico.GraficoUtils;
import org.archicontribs.modelrepository.grafico.IArchiRepository;
import org.archicontribs.modelrepository.grafico.IGraficoConstants;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.window.Window;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CheckoutCommand.Stage;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.RmCommand;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.swt.widgets.Shell;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.INameable;

/**
 * Handle Merge Conflicts on a MergeResult
 * 
 * @author Phillip Beauvoir
 */
public class MergeConflictHandler {
    
    /**
     * Represents a detected folder move with its two conflicting locations 
     * and related element conflicts.
     */
    static class MoveGroup {
        final String moveGroupId;
        final String folderName;
        final String oldLocationBreadcrumb;
        final String newLocationBreadcrumb;
        final List<MergeObjectInfo> relatedInfos;
        /** The folder.xml MergeObjectInfos (one per conflicting location) */
        final List<MergeObjectInfo> folderInfos;
        /** The folder path in ours (HEAD) - relative to repo root */
        final String oursFolderPath;
        /** The folder path in theirs - relative to repo root */
        final String theirsFolderPath;
        
        /**
         * True when OURS (HEAD) moved the folder and THEIRS modified elements at the
         * old location. This reverses which git stage has content at each path:
         * normally OURS stage has content at oursFolderPath, but when oursIsTheMover,
         * THEIRS stage has content at oursFolderPath (the old location) instead.
         */
        final boolean oursIsTheMover;
        
        /**
         * The user's location choice for this folder move.
         * OURS = keep old location, THEIRS = keep new location.
         * Default = THEIRS (keep new).
         * This is separate from individual element content choices.
         */
        int locationChoice = MergeObjectInfo.THEIRS;
        
        MoveGroup(String moveGroupId, String folderName, 
                  String oldLocationBreadcrumb, String newLocationBreadcrumb,
                  List<MergeObjectInfo> relatedInfos, List<MergeObjectInfo> folderInfos,
                  String oursFolderPath, String theirsFolderPath,
                  boolean oursIsTheMover) {
            this.moveGroupId = moveGroupId;
            this.folderName = folderName;
            this.oldLocationBreadcrumb = oldLocationBreadcrumb;
            this.newLocationBreadcrumb = newLocationBreadcrumb;
            this.relatedInfos = relatedInfos;
            this.folderInfos = folderInfos;
            this.oursFolderPath = oursFolderPath;
            this.theirsFolderPath = theirsFolderPath;
            this.oursIsTheMover = oursIsTheMover;
        }
    }
    
    private IArchiRepository fArchiRepo;
    private MergeResult fMergeResult;
    private Shell fShell;
    
    private String fTheirRef;
    
    private List<MergeObjectInfo> fMergeObjectInfos;
    
    private List<MoveGroup> fMoveGroups;
    
    private IArchimateModel fOurModel, fTheirModel;
    
    private IProgressMonitor fProgressMonitor;

    public MergeConflictHandler(MergeResult mergeResult, String theirRef, IArchiRepository repo, Shell shell) {
        fMergeResult = mergeResult;
        fArchiRepo = repo;
        fTheirRef = theirRef;
        fShell = shell;
    }
    
    public void init(IProgressMonitor pm) throws IOException, GitAPIException {
        long initStart = System.nanoTime();
        log(IStatus.INFO, "[MergeConflictHandler] init() start - " + fMergeResult.getConflicts().size() + " conflicts"); //$NON-NLS-1$ //$NON-NLS-2$
        
        // This could be null if Rebase is the default behaviour on the repo rather than merge when a Pull is done
        if(fMergeResult == null) {
            throw new IOException("MergeResult was null"); //$NON-NLS-1$
        }
        
        fProgressMonitor = pm;

        // Our model is the current loaded one
        long t = System.nanoTime();
        fOurModel = fArchiRepo.locateModel();
        log(IStatus.INFO, "[MergeConflictHandler] locateModel: " + (System.nanoTime() - t) / 1_000_000 + "ms"); //$NON-NLS-1$ //$NON-NLS-2$
        if(fOurModel == null) {
            throw new IOException(Messages.MergeConflictHandler_0);
        }
        
        // Their model needs to be extracted
        t = System.nanoTime();
        fTheirModel = extractModel(getTheirRef());
        log(IStatus.INFO, "[MergeConflictHandler] extractModel(" + getTheirRef() + "): " + (System.nanoTime() - t) / 1_000_000 + "ms"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        
        // Create Merge Infos
        t = System.nanoTime();
        fMergeObjectInfos = new ArrayList<MergeObjectInfo>();
        for(String xmlPath : fMergeResult.getConflicts().keySet()) {
            fMergeObjectInfos.add(new MergeObjectInfo(xmlPath, this));
        }
        log(IStatus.INFO, "[MergeConflictHandler] create MergeObjectInfos (" + fMergeObjectInfos.size() + " items): " + (System.nanoTime() - t) / 1_000_000 + "ms"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        
        // For items where one side appears "deleted" (null), try to resolve the
        // element by ID in the other model. This handles element/folder moves:
        // Git sees a move as delete + create, so the file at the new path shows
        // "Deleted by us" even though the element exists in our model (just at a
        // different location). After resolving, both sides are populated and the
        // conflict shows as "Modified" with different locations instead.
        t = System.nanoTime();
        for(MergeObjectInfo info : fMergeObjectInfos) {
            info.resolveMovedObject();
        }
        log(IStatus.INFO, "[MergeConflictHandler] resolveMovedObjects: " + (System.nanoTime() - t) / 1_000_000 + "ms"); //$NON-NLS-1$ //$NON-NLS-2$
        
        // Detect folder moves among the conflicts and link related items
        t = System.nanoTime();
        detectFolderMoves();
        log(IStatus.INFO, "[MergeConflictHandler] detectFolderMoves: " + (System.nanoTime() - t) / 1_000_000 + "ms, groups=" + (fMoveGroups != null ? fMoveGroups.size() : 0)); //$NON-NLS-1$ //$NON-NLS-2$
        
        log(IStatus.INFO, "[MergeConflictHandler] init() total: " + (System.nanoTime() - initStart) / 1_000_000 + "ms"); //$NON-NLS-1$ //$NON-NLS-2$
    }
    
    /**
     * Package-private init with explicit models, for testing without an Eclipse workbench.
     */
    void init(IProgressMonitor pm, IArchimateModel ourModel, IArchimateModel theirModel) throws IOException, GitAPIException {
        if(fMergeResult == null) {
            throw new IOException("MergeResult was null"); //$NON-NLS-1$
        }
        
        fProgressMonitor = pm;
        fOurModel = ourModel;
        fTheirModel = theirModel;
        
        fMergeObjectInfos = new ArrayList<MergeObjectInfo>();
        for(String xmlPath : fMergeResult.getConflicts().keySet()) {
            fMergeObjectInfos.add(new MergeObjectInfo(xmlPath, this));
        }
        
        for(MergeObjectInfo info : fMergeObjectInfos) {
            info.resolveMovedObject();
        }
        
        detectFolderMoves();
    }
    
    /**
     * Detect folder moves among conflicting files.
     * 
     * A folder move is detected when either:
     * - Two folder.xml conflicts share the same folder ID at different paths (both sides conflict)
     * - A single folder.xml conflict has an ID that matches a non-conflicting folder.xml on disk
     *   (typical case: old path is a delete/modify conflict, new path was auto-merged)
     * 
     * When detected, all conflicts under the moved folder paths are tagged with a
     * {@link MergeObjectInfo#setMoveGroupId(String)} so the dialog can auto-resolve
     * related conflicts when the user chooses a direction for the folder.
     */
    private void detectFolderMoves() {
        fMoveGroups = new ArrayList<>();
        
        // Pass 1: Index folder.xml conflicts by their folder ID
        Map<String, List<MergeObjectInfo>> folderIdToInfos = indexFolderConflictsByID();
        
        // Collect all conflict folder paths (to exclude from disk scan)
        Set<String> conflictFolderPaths = new HashSet<>();
        for(List<MergeObjectInfo> infoList : folderIdToInfos.values()) {
            for(MergeObjectInfo info : infoList) {
                conflictFolderPaths.add(info.getFolderPath());
            }
        }
        
        // Track which folder paths we've already created MoveGroups for
        Set<String> processedFolderPaths = new HashSet<>();
        
        // Pass 2: Create MoveGroups from folder.xml conflicts
        createMoveGroupsFromFolderConflicts(folderIdToInfos, conflictFolderPaths, processedFolderPaths);
        
        // Pass 3: Detect moves for element-only conflicts (no folder.xml in conflict list)
        detectMovesFromElementConflicts(processedFolderPaths);
    }
    
    /**
     * Pass 1: Index all conflicting folder.xml files by their folder ID.
     */
    private Map<String, List<MergeObjectInfo>> indexFolderConflictsByID() {
        Map<String, List<MergeObjectInfo>> folderIdToInfos = new HashMap<>();
        
        for(MergeObjectInfo info : fMergeObjectInfos) {
            if(!info.isFolderXml()) {
                continue;
            }
            
            String folderId = getFolderIdFromConflict(info);
            if(folderId != null) {
                folderIdToInfos.computeIfAbsent(folderId, k -> new ArrayList<>()).add(info);
            }
        }
        return folderIdToInfos;
    }
    
    /**
     * Pass 2: Create MoveGroups from paired or single folder.xml conflicts.
     * Paired = two folder.xml conflicts share the same ID at different paths.
     * Single = one folder.xml conflict whose ID matches a non-conflicting folder on disk.
     */
    private void createMoveGroupsFromFolderConflicts(Map<String, List<MergeObjectInfo>> folderIdToInfos,
            Set<String> conflictFolderPaths, Set<String> processedFolderPaths) {
        for(Map.Entry<String, List<MergeObjectInfo>> entry : folderIdToInfos.entrySet()) {
            String folderId = entry.getKey();
            List<MergeObjectInfo> infos = entry.getValue();
            
            String oursFolderPath = null;
            String theirsFolderPath = null;
            
            if(infos.size() >= 2) {
                // Both paths are in conflicts (paired conflicts)
                byte[] headContent = null;
                try {
                    headContent = fArchiRepo.getFileContents(
                            infos.get(0).getXMLPath(), getLocalRef());
                } catch(IOException e) { /* Fall through */ }
                
                oursFolderPath = (headContent != null)
                        ? infos.get(0).getFolderPath() : infos.get(1).getFolderPath();
                theirsFolderPath = (headContent != null)
                        ? infos.get(1).getFolderPath() : infos.get(0).getFolderPath();
            } else {
                // Single conflict: search disk for a matching non-conflict folder.xml
                String diskMatchPath = findFolderOnDisk(folderId, conflictFolderPaths);
                if(diskMatchPath == null) {
                    continue; // No matching folder on disk = not a move
                }
                
                MergeObjectInfo conflictInfo = infos.get(0);
                byte[] headContent = null;
                try {
                    headContent = fArchiRepo.getFileContents(
                            conflictInfo.getXMLPath(), getLocalRef());
                } catch(IOException e) { /* Fall through */ }
                
                if(headContent != null) {
                    oursFolderPath = conflictInfo.getFolderPath();
                    theirsFolderPath = diskMatchPath;
                } else {
                    oursFolderPath = diskMatchPath;
                    theirsFolderPath = conflictInfo.getFolderPath();
                }
            }
            
            processedFolderPaths.add(oursFolderPath);
            processedFolderPaths.add(theirsFolderPath);
            buildMoveGroup(folderId, infos, oursFolderPath, theirsFolderPath);
        }
    }
    
    /**
     * Pass 3: Detect moves from element-only conflicts where no folder.xml is in conflict.
     * This happens when branch A moves a folder and branch B modifies an element inside it.
     * Git auto-merges the folder.xml but the element file conflicts.
     */
    private void detectMovesFromElementConflicts(Set<String> processedFolderPaths) {
        for(MergeObjectInfo info : fMergeObjectInfos) {
            if(info.isFolderXml() || info.isPartOfMove() || !info.isResolvedAsMove()) {
                continue;
            }
            String folderPath = info.getFolderPath();
            if(processedFolderPaths.contains(folderPath)) {
                continue;
            }
            
            // The element exists in both models (after resolveMovedObject).
            EObject oursElement = info.getEObject(MergeObjectInfo.OURS);
            EObject theirsElement = info.getEObject(MergeObjectInfo.THEIRS);
            if(oursElement == null || theirsElement == null) {
                continue;
            }
            
            // Get containing folders from both models — must be same folder ID
            EObject oursContainer = oursElement.eContainer();
            EObject theirsContainer = theirsElement.eContainer();
            if(!(oursContainer instanceof IFolder) || !(theirsContainer instanceof IFolder)) {
                continue;
            }
            
            String folderId = ((com.archimatetool.model.IIdentifier)oursContainer).getId();
            String theirsFolderId = ((com.archimatetool.model.IIdentifier)theirsContainer).getId();
            if(!folderId.equals(theirsFolderId)) {
                continue; // Different folders entirely — not a simple move
            }
            
            // Same folder ID at different paths = folder move.
            Set<String> exclude = new HashSet<>();
            exclude.add(folderPath);
            exclude.addAll(processedFolderPaths);
            String otherPath = findFolderOnDisk(folderId, exclude);
            if(otherPath == null) {
                continue;
            }
            
            // Determine which side is the mover.
            byte[] headContent = null;
            try {
                headContent = fArchiRepo.getFileContents(info.getXMLPath(), getLocalRef());
            } catch(IOException e) { /* Fall through */ }
            boolean oursIsTheMover = (headContent == null);
            
            processedFolderPaths.add(folderPath);
            processedFolderPaths.add(otherPath);
            
            buildMoveGroup(folderId, new ArrayList<>(), folderPath, otherPath, oursIsTheMover);
        }
    }
    
    /**
     * Build a MoveGroup from a detected folder move, tag all related conflict infos,
     * and add it to the fMoveGroups list.
     */
    private void buildMoveGroup(String folderId, List<MergeObjectInfo> folderConflictInfos,
                                String oursFolderPath, String theirsFolderPath) {
        buildMoveGroup(folderId, folderConflictInfos, oursFolderPath, theirsFolderPath, false);
    }
    
    private void buildMoveGroup(String folderId, List<MergeObjectInfo> folderConflictInfos,
                                String oursFolderPath, String theirsFolderPath,
                                boolean oursIsTheMover) {
        String moveGroupId = "move:" + folderId; //$NON-NLS-1$
        
        // Get folder name from either model
        EObject folderObj = com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(fOurModel, folderId);
        if(folderObj == null) {
            folderObj = com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(fTheirModel, folderId);
        }
        String folderName = (folderObj instanceof INameable) ? ((INameable)folderObj).getName() : ""; //$NON-NLS-1$
        
        // Build breadcrumbs from models.
        // When THEIRS moved (normal): ours model = old location, theirs model = new location.
        // When OURS moved: ours model = new location, theirs model = old location → swap.
        EObject oursFolder = com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(fOurModel, folderId);
        EObject theirsFolder = com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(fTheirModel, folderId);
        String oursBreadcrumb = (oursFolder != null) ? buildBreadcrumb(oursFolder) : stripModelPrefix(oursFolderPath);
        String theirsBreadcrumb = (theirsFolder != null) ? buildBreadcrumb(theirsFolder) : stripModelPrefix(theirsFolderPath);
        String oldBreadcrumb = oursIsTheMover ? theirsBreadcrumb : oursBreadcrumb;
        String newBreadcrumb = oursIsTheMover ? oursBreadcrumb : theirsBreadcrumb;
        
        String moveDesc = Messages.MergeConflictHandler_3 + oldBreadcrumb + " \u2194 " + newBreadcrumb; //$NON-NLS-1$
        
        // Tag folder.xml conflicts
        for(MergeObjectInfo folderInfo : folderConflictInfos) {
            folderInfo.setMoveGroupId(moveGroupId);
            folderInfo.setMoveDescription(moveDesc);
        }
        
        // Collect all related infos (folder.xml conflicts + element conflicts under both paths)
        List<MergeObjectInfo> allRelated = new ArrayList<>(folderConflictInfos);
        for(MergeObjectInfo info : fMergeObjectInfos) {
            if(!info.isFolderXml()
                    && (info.getFolderPath().equals(oursFolderPath) || info.getFolderPath().equals(theirsFolderPath))
                    && info.getMoveGroupId() == null) {
                info.setMoveGroupId(moveGroupId);
                info.setMoveDescription(moveDesc);
                allRelated.add(info);
            }
        }
        
        fMoveGroups.add(new MoveGroup(moveGroupId, folderName,
                oldBreadcrumb, newBreadcrumb, allRelated, folderConflictInfos,
                oursFolderPath, theirsFolderPath, oursIsTheMover));
    }
    
    /**
     * Scan the model directory for a folder.xml with the given ID,
     * skipping directories whose paths are in the exclude set.
     * @return The relative folder path (from repo root) or null if not found
     */
    private String findFolderOnDisk(String folderId, Set<String> excludePaths) {
        File modelDir = new File(fArchiRepo.getLocalRepositoryFolder(), IGraficoConstants.MODEL_FOLDER);
        if(!modelDir.isDirectory()) return null;
        
        File repoRoot = fArchiRepo.getLocalRepositoryFolder();
        File found = GraficoUtils.findFolderDirById(modelDir, folderId, dir -> {
            String rel = repoRoot.toPath().relativize(dir.toPath()).toString().replace('\\', '/');
            return !excludePaths.contains(rel);
        });
        if(found == null) return null;
        return repoRoot.toPath().relativize(found.toPath()).toString().replace('\\', '/');
    }
    
    private static String stripModelPrefix(String path) {
        if(path.startsWith(IGraficoConstants.MODEL_FOLDER + "/")) { //$NON-NLS-1$
            return path.substring(IGraficoConstants.MODEL_FOLDER.length() + 1);
        }
        return path;
    }
    
    /**
     * Build a human-readable breadcrumb for a folder.xml conflict entry.
     * Uses the loaded models to resolve folder IDs to names.
     * Falls back to the raw folder path if the folder is not in either model.
     */
    private String buildBreadcrumbForFolder(MergeObjectInfo folderInfo) {
        // The folder.xml's EObject (if loaded) is the folder itself
        EObject eObject = folderInfo.getDefaultEObject();
        if(eObject != null) {
            return buildBreadcrumb(eObject);
        }
        
        // Fallback: strip "model/" prefix from the raw path
        String path = folderInfo.getFolderPath();
        if(path.startsWith(IGraficoConstants.MODEL_FOLDER + "/")) { //$NON-NLS-1$
            path = path.substring(IGraficoConstants.MODEL_FOLDER.length() + 1);
        }
        return path;
    }
    
    /**
     * Extract the folder ID from a conflicting folder.xml.
     * Tries ours first (HEAD content), then theirs.
     */
    private String getFolderIdFromConflict(MergeObjectInfo folderInfo) {
        String xmlPath = folderInfo.getXMLPath();
        
        // Try ours
        try {
            byte[] content = fArchiRepo.getFileContents(xmlPath, getLocalRef());
            if(content != null) {
                return GraficoUtils.extractIdFromFolderXml(content);
            }
        } catch(IOException e) {
            // Fall through
        }
        
        // Try theirs
        try {
            byte[] content = fArchiRepo.getFileContents(xmlPath, getTheirRef());
            if(content != null) {
                return GraficoUtils.extractIdFromFolderXml(content);
            }
        } catch(IOException e) {
            // Fall through
        }
        
        return null;
    }
    
    public boolean openConflictsDialog(String message) {
        log(IStatus.INFO, "[MergeConflictHandler] openConflictsDialog() opening merge conflict dialog"); //$NON-NLS-1$
        Dialog dialog = new ConflictsDialog(fShell, this, message);
        boolean result = dialog.open() == Window.OK ? true : false;
        log(IStatus.INFO, "[MergeConflictHandler] openConflictsDialog() result=" + result); //$NON-NLS-1$
        return result;
    }
    
    /**
     * Merge ours and theirs but don't commit
     * @throws IOException
     * @throws GitAPIException
     */
    public void merge() throws IOException, GitAPIException {
        long mergeStart = System.nanoTime();
        
        // Categorize conflict infos by resolution type
        List<String> ours = new ArrayList<>();
        List<String> theirs = new ArrayList<>();
        List<String> deletions = new ArrayList<>();
        List<MergeObjectInfo> moveResolvedInfos = new ArrayList<>();
        categorizeConflictChoices(ours, theirs, deletions, moveResolvedInfos);
        
        log(IStatus.INFO, "[MergeConflictHandler] merge() checkout: ours=" + ours.size() + ", theirs=" + theirs.size() //$NON-NLS-1$ //$NON-NLS-2$
                + ", deletions=" + deletions.size() //$NON-NLS-1$
                + ", moveResolved=" + moveResolvedInfos.size() //$NON-NLS-1$
                + ", moveGroupItems=" + getMergeObjectInfos().stream().filter(MergeObjectInfo::isPartOfMove).count()); //$NON-NLS-1$
        
        // Apply checkouts and resolve move-resolved elements
        long t = System.nanoTime();
        try(Git git = Git.open(fArchiRepo.getLocalRepositoryFolder())) {
            if(!ours.isEmpty()) {
                checkout(git, Stage.OURS, ours);
            }
            if(!theirs.isEmpty()) {
                checkout(git, Stage.THEIRS, theirs);
            }
            if(!deletions.isEmpty()) {
                resolveToDeleted(git, deletions);
            }
            if(!moveResolvedInfos.isEmpty()) {
                resolveMoveResolvedElements(git, moveResolvedInfos);
            }
        }
        log(IStatus.INFO, "[MergeConflictHandler] merge() checkout done: " + (System.nanoTime() - t) / 1_000_000 + "ms"); //$NON-NLS-1$ //$NON-NLS-2$
        
        // Consolidate move groups
        t = System.nanoTime();
        consolidateMoveGroups();
        log(IStatus.INFO, "[MergeConflictHandler] merge() consolidateMoveGroups done: " + (System.nanoTime() - t) / 1_000_000 + "ms"); //$NON-NLS-1$ //$NON-NLS-2$
        
        logGitStatusAfterMerge();
        log(IStatus.INFO, "[MergeConflictHandler] merge() total: " + (System.nanoTime() - mergeStart) / 1_000_000 + "ms"); //$NON-NLS-1$ //$NON-NLS-2$
    }
    
    /**
     * Categorize conflict choices into ours/theirs checkout lists, deletions, and move-resolved list.
     * Move group items are skipped (handled by consolidateMoveGroups).
     * 
     * When user chooses a side that deleted the file (EObject is null and not resolved as move),
     * the path goes into the deletions list instead of ours/theirs, because git checkout --stage
     * cannot check out a non-existent stage.
     */
    private void categorizeConflictChoices(List<String> ours, List<String> theirs,
            List<String> deletions, List<MergeObjectInfo> moveResolvedInfos) {
        for(MergeObjectInfo info : getMergeObjectInfos()) {
            if(info.isPartOfMove()) {
                continue;
            }
            if(info.isResolvedAsMove()) {
                moveResolvedInfos.add(info);
                log(IStatus.INFO, "[MergeConflictHandler] merge() resolvedAsMove: " + info.getXMLPath() //$NON-NLS-1$
                        + ", userChoice=" + (info.getUserChoice() == MergeObjectInfo.OURS ? "OURS" : "THEIRS")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                continue;
            }
            if(info.getUserChoice() == MergeObjectInfo.OURS) {
                if(info.isDeletedBy(MergeObjectInfo.OURS)) {
                    deletions.add(info.getXMLPath());
                } else {
                    ours.add(info.getXMLPath());
                }
            } else {
                if(info.isDeletedBy(MergeObjectInfo.THEIRS)) {
                    deletions.add(info.getXMLPath());
                } else {
                    theirs.add(info.getXMLPath());
                }
            }
        }
    }
    
    /**
     * Log git status after merge for diagnostics.
     */
    private void logGitStatusAfterMerge() throws IOException, GitAPIException {
        try(Git git = Git.open(fArchiRepo.getLocalRepositoryFolder())) {
            org.eclipse.jgit.api.Status gitStatus = git.status().call();
            log(IStatus.INFO, "[MergeConflictHandler] merge() git status: clean=" + gitStatus.isClean() //$NON-NLS-1$
                    + ", conflicting=" + gitStatus.getConflicting().size() //$NON-NLS-1$
                    + ", changed=" + gitStatus.getChanged().size() //$NON-NLS-1$
                    + ", added=" + gitStatus.getAdded().size() //$NON-NLS-1$
                    + ", removed=" + gitStatus.getRemoved().size() //$NON-NLS-1$
                    + ", modified=" + gitStatus.getModified().size() //$NON-NLS-1$
                    + ", untracked=" + gitStatus.getUntracked().size()); //$NON-NLS-1$
            if(!gitStatus.getConflicting().isEmpty()) {
                log(IStatus.WARNING, "[MergeConflictHandler] STILL CONFLICTING: " + gitStatus.getConflicting()); //$NON-NLS-1$
            }
        }
    }
    /**
     * Handle resolvedAsMove elements that are NOT part of a MoveGroup.
     * These are elements that moved between different folders (different folder IDs),
     * not a whole-folder move. Git sees delete+modify → conflict at the old path.
     * The element also exists at the new path (auto-merged by git).
     * 
     * <p>For each element:
     * <ul>
     * <li>Determine which side has content at the conflict path (OURS or THEIRS)</li>
     * <li>Checkout that stage to resolve conflict markers</li>
     * <li>Find the element's new path from the model that moved it</li>
     * <li>If user chose the modifier's content: copy to new path, delete old path</li>
     * <li>If user chose the mover's content: just delete old path (new path already correct)</li>
     * <li>git-add both paths to resolve the index</li>
     * </ul></p>
     */
    private void resolveMoveResolvedElements(Git git, List<MergeObjectInfo> infos) throws IOException, GitAPIException {
        File repoRoot = fArchiRepo.getLocalRepositoryFolder();
        Set<String> pathsToAdd = new LinkedHashSet<>();
        
        for(MergeObjectInfo info : infos) {
            resolveSingleMoveResolvedElement(git, repoRoot, info, pathsToAdd);
        }
        
        // Git-add all affected paths to resolve conflicts in the index
        if(!pathsToAdd.isEmpty()) {
            log(IStatus.INFO, "[MergeConflictHandler] resolveMoveResolved: staging " + pathsToAdd.size() + " paths: " + pathsToAdd); //$NON-NLS-1$ //$NON-NLS-2$
            stagePathsWithJGit(git, pathsToAdd);
        }
    }
    
    /**
     * Resolve a single move-resolved element: checkout the correct stage,
     * compute the new path, apply content choice, and collect paths to stage.
     */
    private void resolveSingleMoveResolvedElement(Git git, File repoRoot, MergeObjectInfo info,
            Set<String> pathsToAdd) throws IOException, GitAPIException {
        String oldXmlPath = info.getXMLPath();
        File oldFile = new File(repoRoot, oldXmlPath);
        
        // Determine which side has content at the conflict path
        boolean oursHasContent = hasContentAtRef(oldXmlPath, getLocalRef());
        
        // Checkout the stage that has content to get a clean file
        Stage resolveStage = oursHasContent ? Stage.OURS : Stage.THEIRS;
        checkout(git, resolveStage, List.of(oldXmlPath));
        
        // Find the new path from the model that MOVED the element
        EObject movedElement = oursHasContent
                ? info.getEObject(MergeObjectInfo.THEIRS)   // OURS modified, THEIRS moved
                : info.getEObject(MergeObjectInfo.OURS);    // THEIRS modified, OURS moved
        
        String newXmlPath = computeGraficoPath(movedElement);
        File newFile = (newXmlPath != null) ? new File(repoRoot, newXmlPath) : null;
        
        log(IStatus.INFO, "[MergeConflictHandler] resolveMoveResolved: " + oldXmlPath //$NON-NLS-1$
                + " → " + newXmlPath //$NON-NLS-1$
                + ", resolveStage=" + resolveStage //$NON-NLS-1$
                + ", userChoice=" + (info.getUserChoice() == MergeObjectInfo.OURS ? "OURS" : "THEIRS")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        
        if(newFile != null && !oldXmlPath.equals(newXmlPath)) {
            applyMoveResolvedContentChoice(oldFile, newFile, info, oursHasContent);
            pathsToAdd.add(oldXmlPath);
            pathsToAdd.add(newXmlPath);
        } else {
            // Can't determine new path — just stage old path to resolve conflict
            pathsToAdd.add(oldXmlPath);
        }
    }
    
    /**
     * Check whether the repository has file content at a given ref.
     */
    private boolean hasContentAtRef(String xmlPath, String ref) {
        try {
            byte[] content = fArchiRepo.getFileContents(xmlPath, ref);
            return content != null;
        } catch(IOException e) {
            return false;
        }
    }
    
    /**
     * Apply the user's content choice for a move-resolved element:
     * copy modifier's content to new path if chosen, then delete old file.
     */
    private void applyMoveResolvedContentChoice(File oldFile, File newFile,
            MergeObjectInfo info, boolean oursHasContent) throws IOException {
        // Determine if user wants the modifier's content
        boolean userWantsModifierContent = oursHasContent
                ? (info.getUserChoice() == MergeObjectInfo.OURS)    // OURS = modifier
                : (info.getUserChoice() == MergeObjectInfo.THEIRS); // THEIRS = modifier
        
        if(userWantsModifierContent) {
            newFile.getParentFile().mkdirs();
            Files.copy(oldFile.toPath(), newFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        // else: new path already has the mover's content (auto-merged)
        
        oldFile.delete();
    }
    
    /**
     * Compute the GRAFICO file path for a model element by traversing its containment hierarchy.
     * @return path relative to repo root (e.g. "model/application/folderX/BusinessActor_id-abc.xml"), or null
     */
    private String computeGraficoPath(EObject element) {
        if(element == null) {
            return null;
        }
        
        // Build filename: ElementType_id-xxx.xml
        String className = element.getClass().getSimpleName();
        // EMF implementation classes end with "Impl" — strip it
        if(className.endsWith("Impl")) { //$NON-NLS-1$
            className = className.substring(0, className.length() - 4);
        }
        String id = (element instanceof IIdentifier) ? ((IIdentifier)element).getId() : null;
        if(id == null) {
            return null;
        }
        String filename = className + "_" + id + ".xml"; //$NON-NLS-1$ //$NON-NLS-2$
        
        // Build folder path by walking up the containment tree
        StringBuilder folderPath = new StringBuilder();
        EObject container = element.eContainer();
        List<String> segments = new ArrayList<>();
        while(container instanceof IFolder folder) {
            String folderName = (folder.getType() == FolderType.USER)
                    ? folder.getId() : folder.getType().toString();
            segments.add(0, folderName);
            container = container.eContainer();
        }
        
        if(segments.isEmpty()) {
            return null;
        }
        
        folderPath.append(IGraficoConstants.MODEL_FOLDER);
        for(String seg : segments) {
            folderPath.append('/').append(seg);
        }
        
        return folderPath + "/" + filename; //$NON-NLS-1$
    }

    /**
     * After merge(), consolidate move groups by resolving chosen-path conflicts,
     * moving unique elements from the unchosen path to the chosen path,
     * deleting the unchosen path, and staging all changes.
     * 
     * <p>merge() skips move-group items so that git checkout is not attempted
     * for paths where the chosen stage may not exist (e.g. "theirs" at the old
     * location when theirs deleted it). This method handles everything instead.</p>
     */
    private void consolidateMoveGroups() throws IOException, GitAPIException {
        if(fMoveGroups == null || fMoveGroups.isEmpty()) {
            log(IStatus.INFO, "[MergeConflictHandler] consolidateMoveGroups: no move groups, skipping"); //$NON-NLS-1$
            return;
        }
        
        log(IStatus.INFO, "[MergeConflictHandler] consolidateMoveGroups: processing " + fMoveGroups.size() + " move group(s)"); //$NON-NLS-1$ //$NON-NLS-2$
        File repoRoot = fArchiRepo.getLocalRepositoryFolder();
        
        for(MoveGroup group : fMoveGroups) {
            consolidateSingleMoveGroup(repoRoot, group);
        }
        
        // 6. Stage all changes (new, modified, deleted) in affected directories only
        stageMoveGroupPaths(repoRoot);
    }
    
    /**
     * Consolidate a single move group: resolve conflicts at both paths,
     * move unique elements to the chosen path, and clean up the unchosen path.
     */
    private void consolidateSingleMoveGroup(File repoRoot, MoveGroup group) throws IOException, GitAPIException {
        int locChoice = group.locationChoice;
        
        String chosenPath = (locChoice == MergeObjectInfo.OURS)
                ? group.oursFolderPath : group.theirsFolderPath;
        String unchosenPath = (locChoice == MergeObjectInfo.OURS)
                ? group.theirsFolderPath : group.oursFolderPath;
        
        log(IStatus.INFO, "[MergeConflictHandler] consolidate group '" + group.folderName //$NON-NLS-1$
                + "': choice=" + (locChoice == MergeObjectInfo.OURS ? "OURS" : "THEIRS") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + ", chosen=" + chosenPath + ", unchosen=" + unchosenPath //$NON-NLS-1$ //$NON-NLS-2$
                + ", relatedInfos=" + group.relatedInfos.size() //$NON-NLS-1$
                + ", folderInfos=" + group.folderInfos.size()); //$NON-NLS-1$
        
        File chosenDir = new File(repoRoot, chosenPath);
        File unchosenDir = new File(repoRoot, unchosenPath);
        chosenDir.mkdirs();
        
        // Build set of conflicting element filenames at the unchosen path
        Map<String, MergeObjectInfo> unchosenConflictElements = collectUnchosenConflictElements(group, unchosenPath);
        
        // 1-2. Resolve conflict markers at the unchosen path
        resolveConflictsAtUnchosenPath(repoRoot, group, unchosenPath, unchosenConflictElements);
        
        // 3. Resolve conflict markers at the chosen path per user choice
        resolveConflictsAtChosenPath(repoRoot, group, chosenPath);
        
        // 4. Handle files at the unchosen path: move/copy/delete
        migrateFilesFromUnchosenPath(unchosenDir, chosenDir, unchosenConflictElements, group);
        
        // 5. Delete unchosen directory if now empty
        deleteDirectoryIfEmpty(unchosenDir);
    }
    
    /**
     * Build a map of filename → MergeObjectInfo for conflicting elements at the unchosen path.
     */
    private Map<String, MergeObjectInfo> collectUnchosenConflictElements(MoveGroup group, String unchosenPath) {
        Map<String, MergeObjectInfo> result = new HashMap<>();
        for(MergeObjectInfo info : group.relatedInfos) {
            if(info.isFolderXml()) continue;
            if(info.getFolderPath().equals(unchosenPath)) {
                String filename = info.getXMLPath()
                        .substring(info.getXMLPath().lastIndexOf('/') + 1);
                result.put(filename, info);
            }
        }
        return result;
    }
    
    /**
     * Resolve conflict markers at the unchosen path: checkout the correct git stage
     * for folder.xml and all conflicting elements.
     */
    private void resolveConflictsAtUnchosenPath(File repoRoot, MoveGroup group, String unchosenPath,
            Map<String, MergeObjectInfo> unchosenConflictElements) throws IOException, GitAPIException {
        // Folder.xml conflicts at the unchosen path
        List<String> pathsToResolve = new ArrayList<>();
        for(MergeObjectInfo info : group.folderInfos) {
            if(info.getFolderPath().equals(unchosenPath)) {
                pathsToResolve.add(info.getXMLPath());
            }
        }
        
        // Element conflicts at the unchosen path
        for(Map.Entry<String, MergeObjectInfo> entry : unchosenConflictElements.entrySet()) {
            pathsToResolve.add(entry.getValue().getXMLPath());
        }
        
        if(!pathsToResolve.isEmpty()) {
            try(Git git = Git.open(repoRoot)) {
                // Determine which git stage has content at the unchosen path.
                boolean oursStageAtUnchosen = unchosenPath.equals(group.oursFolderPath);
                if(group.oursIsTheMover) {
                    oursStageAtUnchosen = !oursStageAtUnchosen;
                }
                Stage stage = oursStageAtUnchosen ? Stage.OURS : Stage.THEIRS;
                checkout(git, stage, pathsToResolve);
            }
        }
    }
    
    /**
     * Resolve conflict markers at the chosen path: checkout per user's content choice.
     */
    private void resolveConflictsAtChosenPath(File repoRoot, MoveGroup group, String chosenPath)
            throws IOException, GitAPIException {
        List<String> oursAtChosen = new ArrayList<>();
        List<String> theirsAtChosen = new ArrayList<>();
        
        for(MergeObjectInfo info : group.relatedInfos) {
            if(!info.getFolderPath().equals(chosenPath)) continue;
            if(info.getUserChoice() == MergeObjectInfo.OURS) {
                oursAtChosen.add(info.getXMLPath());
            } else {
                theirsAtChosen.add(info.getXMLPath());
            }
        }
        
        if(!oursAtChosen.isEmpty() || !theirsAtChosen.isEmpty()) {
            try(Git git = Git.open(repoRoot)) {
                if(!oursAtChosen.isEmpty()) {
                    checkout(git, Stage.OURS, oursAtChosen);
                }
                if(!theirsAtChosen.isEmpty()) {
                    checkout(git, Stage.THEIRS, theirsAtChosen);
                }
            }
        }
    }
    
    /**
     * Migrate files from the unchosen directory to the chosen directory.
     * Conflicting elements use the content the user chose; non-conflicting
     * unique elements are moved; duplicates are deleted.
     */
    private void migrateFilesFromUnchosenPath(File unchosenDir, File chosenDir,
            Map<String, MergeObjectInfo> unchosenConflictElements, MoveGroup group) throws IOException {
        if(!unchosenDir.isDirectory()) return;
        
        File[] xmlFiles = unchosenDir.listFiles(
                (d, name) -> name.endsWith(".xml")); //$NON-NLS-1$
        if(xmlFiles == null) return;
        
        for(File f : xmlFiles) {
            if(IGraficoConstants.FOLDER_XML.equals(f.getName())) {
                f.delete(); // Remove unchosen folder.xml
                continue;
            }
            
            File dest = new File(chosenDir, f.getName());
            MergeObjectInfo conflictInfo = unchosenConflictElements.get(f.getName());
            
            if(conflictInfo != null) {
                // Determine which side's content is at the unchosen path (resolved in step 2).
                int contentAtUnchosen = group.oursIsTheMover
                        ? MergeObjectInfo.THEIRS : MergeObjectInfo.OURS;
                if(conflictInfo.getUserChoice() == contentAtUnchosen) {
                    // User wants the content version that's at the unchosen path.
                    Files.copy(f.toPath(), dest.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                f.delete();
            }
            else if(!dest.exists()) {
                // Non-conflicting unique element — move to chosen path
                Files.move(f.toPath(), dest.toPath());
            }
            else {
                // Non-conflicting duplicate — just delete
                f.delete();
            }
        }
    }
    
    /**
     * Stage all affected paths from all move groups via native git or JGit fallback.
     */
    private void stageMoveGroupPaths(File repoRoot) throws IOException, GitAPIException {
        Set<String> affectedPaths = new LinkedHashSet<>();
        for(MoveGroup group : fMoveGroups) {
            int locChoice = group.locationChoice;
            affectedPaths.add((locChoice == MergeObjectInfo.OURS)
                    ? group.oursFolderPath : group.theirsFolderPath);
            affectedPaths.add((locChoice == MergeObjectInfo.OURS)
                    ? group.theirsFolderPath : group.oursFolderPath);
        }
        
        log(IStatus.INFO, "[MergeConflictHandler] staging " + affectedPaths.size() //$NON-NLS-1$
                + " affected path(s): " + affectedPaths); //$NON-NLS-1$
        
        if(!tryNativeGitAdd(repoRoot, affectedPaths)) {
            try(Git git = Git.open(repoRoot)) {
                stagePathsWithJGit(git, affectedPaths);
            }
        }
    }
    
    /**
     * Delete a directory if it is empty (no files or subdirectories).
     */
    private static void deleteDirectoryIfEmpty(File dir) {
        if(dir.isDirectory()) {
            File[] remaining = dir.listFiles();
            if(remaining == null || remaining.length == 0) {
                dir.delete();
            }
        }
    }
    
    /**
     * Stage paths using JGit — adds new files and updates/removes existing ones.
     */
    private static void stagePathsWithJGit(Git git, Set<String> paths) throws GitAPIException {
        AddCommand addNew = git.add();
        AddCommand addUpdated = git.add().setUpdate(true);
        for(String path : paths) {
            addNew.addFilepattern(path);
            addUpdated.addFilepattern(path);
        }
        addNew.call();
        addUpdated.call();
    }
    
    /**
     * Try native git add -A for specific paths (much faster than JGit for large repos).
     * 
     * @param repoRoot the repository root directory
     * @param paths repo-relative paths to stage
     * @return true if native git succeeded, false if native git is not available
     * @throws IOException if the git command failed
     */
    private static boolean tryNativeGitAdd(File repoRoot, Set<String> paths) throws IOException {
        try {
            List<String> command = new ArrayList<>();
            command.add("git"); //$NON-NLS-1$
            command.add("add"); //$NON-NLS-1$
            command.add("-A"); //$NON-NLS-1$
            command.add("--"); //$NON-NLS-1$
            command.addAll(paths);
            
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(repoRoot);
            pb.redirectErrorStream(true);
            
            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while((line = reader.readLine()) != null) {
                    output.append(line).append("\n"); //$NON-NLS-1$
                }
            }
            
            int exitCode = process.waitFor();
            if(exitCode != 0) {
                throw new IOException("Git add failed: " + output.toString()); //$NON-NLS-1$
            }
            
            return true;
        }
        catch(IOException ex) {
            String message = ex.getMessage();
            if(message != null && (message.contains("Cannot run program") || message.contains("not found"))) { //$NON-NLS-1$ //$NON-NLS-2$
                return false;
            }
            throw ex;
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Git add interrupted", ex); //$NON-NLS-1$
        }
    }
    
    /**
     * Log a message via the plugin logger, tolerating null plugin instance (e.g. in tests)
     */
    private static void log(int severity, String message) {
        ModelRepositoryPlugin plugin = ModelRepositoryPlugin.getInstance();
        if(plugin != null) {
            plugin.log(severity, message, null);
        }
    }
    
    String getLocalRef() {
        // We assume that we are at HEAD
        return IGraficoConstants.HEAD;
    }
    
    String getTheirRef() {
        return fTheirRef;
    }
    
    public void resetToLocalState() throws IOException, GitAPIException {
        // Reset HARD  which will lose all changes
        try(Git git = Git.open(fArchiRepo.getLocalRepositoryFolder())) {
            ResetCommand resetCommand = git.reset();
            resetCommand.setRef(getLocalRef());
            resetCommand.setMode(ResetType.HARD);
            resetCommand.call();
        }
    }
    
    // Check out conflicting files either from us or them
    private void checkout(Git git, Stage stage, List<String> paths) throws GitAPIException {
        CheckoutCommand checkoutCommand = git.checkout();
        checkoutCommand.setStage(stage);
        checkoutCommand.addPaths(paths);
        checkoutCommand.call();
    }
    
    /**
     * Resolve conflicts where the user chose to accept a deletion.
     * Git checkout --stage cannot check out a non-existent stage, so we
     * delete the file from the working tree and stage the removal.
     */
    private void resolveToDeleted(Git git, List<String> paths) throws IOException, GitAPIException {
        File repoRoot = fArchiRepo.getLocalRepositoryFolder();
        for(String path : paths) {
            File file = new File(repoRoot, path);
            if(file.exists()) {
                file.delete();
            }
        }
        // Stage the deletions to resolve the conflicts in the index
        RmCommand rm = git.rm();
        for(String path : paths) {
            rm.addFilepattern(path);
        }
        rm.call();
        
        log(IStatus.INFO, "[MergeConflictHandler] resolveToDeleted: " + paths.size() + " file(s): " + paths); //$NON-NLS-1$ //$NON-NLS-2$
    }
    
    IArchiRepository getArchiRepository() {
        return fArchiRepo;
    }
    
    List<MergeObjectInfo> getMergeObjectInfos() {
        return fMergeObjectInfos;
    }
    
    IArchimateModel getOurModel() {
        return fOurModel;
    }
    
    IArchimateModel getTheirModel() {
        return fTheirModel;
    }
    
    List<MoveGroup> getMoveGroups() {
        return fMoveGroups;
    }
    
    boolean hasMoveGroups() {
        return fMoveGroups != null && !fMoveGroups.isEmpty();
    }
    
    /**
     * Build a human-readable breadcrumb path for an EObject by walking up its
     * containment hierarchy. For example: "Application / Shared / Subfolder".
     * Returns empty string for null input.
     */
    static String buildBreadcrumb(EObject eObject) {
        if(eObject == null) {
            return ""; //$NON-NLS-1$
        }
        
        List<String> parts = new ArrayList<>();
        EObject current = eObject;
        
        // If the object itself is a folder, start with its container
        // If it's an element, start with the element's container (its folder)
        if(!(current instanceof IFolder)) {
            current = current.eContainer();
        }
        
        while(current != null && !(current instanceof IArchimateModel)) {
            if(current instanceof INameable) {
                String name = ((INameable)current).getName();
                if(name != null && !name.isEmpty()) {
                    parts.add(0, name);
                }
            }
            current = current.eContainer();
        }
        
        return String.join(" / ", parts); //$NON-NLS-1$
    }
    
    /**
     * Build a breadcrumb for the given EObject, trying our model first, then theirs.
     * Useful for elements where one side may be null (deleted).
     */
    String buildBreadcrumbForElement(EObject ourObject, EObject theirObject) {
        EObject obj = ourObject != null ? ourObject : theirObject;
        if(obj != null) {
            return buildBreadcrumb(obj);
        }
        return ""; //$NON-NLS-1$
    }

    /**
     * Extract a model from either our latest commit or their latest online commit
     * ref = "refs/head/master" or "origin/master"
     * 
     * PERFORMANCE: Uses importFromCommit() to read directly from git pack objects
     * without any filesystem I/O. This is typically 5-10x faster than the old approach
     * of extracting all files to a temp folder and reading them back.
     * 
     */
    private IArchimateModel extractModel(String ref) throws IOException {
        try(Repository repository = Git.open(fArchiRepo.getLocalRepositoryFolder()).getRepository()) {
            RevCommit commit = null;
            
            try(RevWalk revWalk = new RevWalk(repository)) {
                ObjectId objectID = repository.resolve(ref);
                if(objectID != null) {
                    commit = revWalk.parseCommit(objectID);
                }
                revWalk.dispose();
            }
            
            if(commit == null) {
                throw new IOException(Messages.MergeConflictHandler_1);
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
    
    // ==================== Cross-Path Deletion Detection ====================
    
    /**
     * Detect and remove elements that were deleted by one merge parent but
     * moved/added by the other, causing them to "leak" to the new location
     * after an auto-merge.
     * 
     * <p>This handles the case where Git sees:
     * <ul>
     * <li>Both parents delete a file at path X → auto-resolved (no conflict)</li>
     * <li>One parent adds the same element at path Y → auto-merged</li>
     * </ul>
     * Result: element appears at path Y even though the other parent deleted it.
     * This method detects such leaked elements and removes them.</p>
     * 
     * <p>Should be called after merge (clean or conflicting) and before
     * repairMissingFolderXml(), as Phase 1.5 of the merge pipeline.</p>
     * 
     * <p>Performance: Uses native {@code git diff --diff-filter=D} to find deleted
     * files (one process call per parent, returns only deleted paths). Falls back
     * to JGit TreeWalk if native git is not available.</p>
     * 
     * @param repo the JGit repository (must have a working tree)
     * @param oursCommitId the commit ID of our branch (before merge)
     * @param theirsCommitId the commit ID of the branch being merged in
     * @return the number of leaked element files removed
     * @throws IOException if an I/O error occurs
     * @throws GitAPIException if a git operation fails
     */
    public static int detectAndRemoveCrossPathDeletions(Repository repo, ObjectId oursCommitId,
            ObjectId theirsCommitId) throws IOException, GitAPIException {
        
        long t = System.nanoTime();
        File repoRoot = repo.getWorkTree();
        
        // Find merge base
        String mergeBaseSha;
        try(RevWalk rw = new RevWalk(repo)) {
            RevCommit oursCommit = rw.parseCommit(oursCommitId);
            RevCommit theirsCommit = rw.parseCommit(theirsCommitId);
            rw.setRevFilter(RevFilter.MERGE_BASE);
            rw.markStart(oursCommit);
            rw.markStart(theirsCommit);
            RevCommit mergeBase = rw.next();
            if(mergeBase == null) {
                return 0;
            }
            mergeBaseSha = mergeBase.getName();
        }
        
        // Collect element IDs deleted by each parent (base→ours, base→theirs)
        Set<String> deletedIds = new HashSet<>();
        
        boolean nativeGitAvailable = collectDeletedIdsNative(repoRoot, mergeBaseSha,
                oursCommitId.getName(), theirsCommitId.getName(), deletedIds);
        
        if(!nativeGitAvailable) {
            // Fallback: use JGit TreeWalk (slower but always available)
            collectDeletedIdsJGit(repo, oursCommitId, theirsCommitId, mergeBaseSha, deletedIds);
        }
        
        if(deletedIds.isEmpty()) {
            return 0;
        }
        
        // Find leaked element files in the working tree
        File modelDir = new File(repoRoot, IGraficoConstants.MODEL_FOLDER);
        if(!modelDir.isDirectory()) {
            return 0;
        }
        
        List<String> leakedPaths = new ArrayList<>();
        findLeakedElementFiles(modelDir, repoRoot, deletedIds, leakedPaths);
        
        if(leakedPaths.isEmpty()) {
            return 0;
        }
        
        // Delete leaked files and stage the removals
        try(Git git = new Git(repo)) {
            RmCommand rm = git.rm();
            for(String path : leakedPaths) {
                File file = new File(repoRoot, path);
                if(file.exists()) {
                    file.delete();
                }
                rm.addFilepattern(path);
            }
            rm.call();
        }
        
        log(IStatus.INFO, "[MergeConflictHandler] detectAndRemoveCrossPathDeletions: removed " //$NON-NLS-1$
                + leakedPaths.size() + " leaked file(s) in " //$NON-NLS-1$
                + (System.nanoTime() - t) / 1_000_000 + "ms: " + leakedPaths); //$NON-NLS-1$
        
        return leakedPaths.size();
    }
    
    /**
     * Use native {@code git diff --name-only --diff-filter=D} to find element IDs
     * truly deleted between merge base and each parent. An element is "truly deleted"
     * by a parent if the diff shows it removed at an old path AND it is NOT present
     * anywhere in that same parent's tree (i.e. not just moved to a new path).
     * 
     * @return true if native git was available, false to indicate JGit fallback needed
     */
    private static boolean collectDeletedIdsNative(File repoRoot, String mergeBaseSha,
            String oursSha, String theirsSha, Set<String> deletedIds) throws IOException {
        try {
            Set<String> deletedByOurs = new HashSet<>();
            Set<String> deletedByTheirs = new HashSet<>();
            collectDeletedIdsFromDiff(repoRoot, mergeBaseSha, oursSha, deletedByOurs);
            collectDeletedIdsFromDiff(repoRoot, mergeBaseSha, theirsSha, deletedByTheirs);
            
            // git diff --diff-filter=D reports path-based deletions. If a parent
            // moved a folder, all files at the old path show as "deleted" even
            // though they exist at the new path. We cross-check against the
            // same parent's full tree to distinguish true deletions from moves.
            Set<String> presentInOurs = new HashSet<>();
            Set<String> presentInTheirs = new HashSet<>();
            collectPresentIdsFromTree(repoRoot, oursSha, presentInOurs);
            collectPresentIdsFromTree(repoRoot, theirsSha, presentInTheirs);
            
            // Truly deleted by ours: diff says deleted AND not present anywhere in ours
            for(String id : deletedByOurs) {
                if(!presentInOurs.contains(id)) {
                    deletedIds.add(id);
                }
            }
            // Truly deleted by theirs: diff says deleted AND not present anywhere in theirs
            for(String id : deletedByTheirs) {
                if(!presentInTheirs.contains(id)) {
                    deletedIds.add(id);
                }
            }
            
            return true;
        }
        catch(IOException ex) {
            String msg = ex.getMessage();
            if(msg != null && (msg.contains("Cannot run program") || msg.contains("not found"))) { //$NON-NLS-1$ //$NON-NLS-2$
                return false; // Native git not available
            }
            throw ex;
        }
    }
    
    /**
     * Run {@code git diff --name-only --diff-filter=D base..commit} and extract
     * element IDs from deleted GRAFICO filenames.
     */
    private static void collectDeletedIdsFromDiff(File repoRoot, String baseSha,
            String commitSha, Set<String> deletedIds) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "git", "diff", "--name-only", "--diff-filter=D", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                baseSha, commitSha, "--", IGraficoConstants.MODEL_FOLDER); //$NON-NLS-1$
        pb.directory(repoRoot);
        pb.redirectErrorStream(true);
        
        Process process;
        try {
            process = pb.start();
        }
        catch(IOException ex) {
            throw ex; // "Cannot run program" — caller catches and falls back
        }
        
        try(BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while((line = reader.readLine()) != null) {
                String fileName = line.substring(line.lastIndexOf('/') + 1);
                String id = extractIdFromElementFileName(fileName);
                if(id != null) {
                    deletedIds.add(id);
                }
            }
        }
        
        try {
            int exitCode = process.waitFor();
            if(exitCode != 0) {
                throw new IOException("git diff failed with exit code " + exitCode); //$NON-NLS-1$
            }
        }
        catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git diff interrupted", e); //$NON-NLS-1$
        }
    }
    
    /**
     * Use native {@code git ls-tree -r --name-only} to collect element IDs
     * present in a commit's tree. Used to cross-check deletion candidates.
     */
    private static void collectPresentIdsFromTree(File repoRoot, String commitSha,
            Set<String> presentIds) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "git", "ls-tree", "-r", "--name-only", commitSha, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "--", IGraficoConstants.MODEL_FOLDER); //$NON-NLS-1$
        pb.directory(repoRoot);
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        
        try(BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while((line = reader.readLine()) != null) {
                String fileName = line.substring(line.lastIndexOf('/') + 1);
                String id = extractIdFromElementFileName(fileName);
                if(id != null) {
                    presentIds.add(id);
                }
            }
        }
        
        try {
            int exitCode = process.waitFor();
            if(exitCode != 0) {
                throw new IOException("git ls-tree failed with exit code " + exitCode); //$NON-NLS-1$
            }
        }
        catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git ls-tree interrupted", e); //$NON-NLS-1$
        }
    }
    
    /**
     * JGit fallback: walk both parent trees and the merge base tree to find
     * truly deleted element IDs. Since {@link #collectElementIdsFromTree} is
     * path-agnostic (collects IDs regardless of folder location), an element
     * missing from a parent's ID set means it was truly deleted, not just moved.
     */
    private static void collectDeletedIdsJGit(Repository repo, ObjectId oursId,
            ObjectId theirsId, String mergeBaseSha, Set<String> deletedIds) throws IOException {
        try(RevWalk rw = new RevWalk(repo)) {
            RevCommit baseCommit = rw.parseCommit(ObjectId.fromString(mergeBaseSha));
            RevCommit oursCommit = rw.parseCommit(oursId);
            RevCommit theirsCommit = rw.parseCommit(theirsId);
            
            Set<String> baseIds = collectElementIdsFromTree(repo, baseCommit);
            Set<String> oursIds = collectElementIdsFromTree(repo, oursCommit);
            Set<String> theirsIds = collectElementIdsFromTree(repo, theirsCommit);
            
            // Elements truly deleted by ours: in base but not in ours at any path
            for(String id : baseIds) {
                if(!oursIds.contains(id)) {
                    deletedIds.add(id);
                }
            }
            // Elements truly deleted by theirs: in base but not in theirs at any path
            for(String id : baseIds) {
                if(!theirsIds.contains(id)) {
                    deletedIds.add(id);
                }
            }
        }
    }
    
    /**
     * Collect all element IDs from a commit's tree by scanning GRAFICO element filenames.
     * Element files follow the pattern: {EClassName}_{id}.xml (e.g. BusinessActor_id-q.xml).
     * Excludes folder.xml files.
     */
    private static Set<String> collectElementIdsFromTree(Repository repo, RevCommit commit) throws IOException {
        Set<String> ids = new HashSet<>();
        try(TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(commit.getTree());
            tw.setRecursive(true);
            while(tw.next()) {
                String path = tw.getPathString();
                if(!path.startsWith(IGraficoConstants.MODEL_FOLDER + "/")) { //$NON-NLS-1$
                    continue;
                }
                String fileName = path.substring(path.lastIndexOf('/') + 1);
                String id = extractIdFromElementFileName(fileName);
                if(id != null) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }
    
    /**
     * Extract the element ID from a GRAFICO element filename.
     * Format: {EClassName}_{id}.xml → returns {id}
     * Returns null for folder.xml and non-element files.
     */
    static String extractIdFromElementFileName(String fileName) {
        if(IGraficoConstants.FOLDER_XML.equals(fileName) || !fileName.endsWith(".xml")) { //$NON-NLS-1$
            return null;
        }
        int underscoreIdx = fileName.indexOf('_');
        if(underscoreIdx < 0 || underscoreIdx >= fileName.length() - 5) {
            return null;
        }
        return fileName.substring(underscoreIdx + 1, fileName.length() - 4);
    }
    
    /**
     * Recursively find element files in the working tree whose IDs are in the leaked set.
     * Adds repo-relative paths to the leakedPaths list.
     */
    private static void findLeakedElementFiles(File dir, File repoRoot, Set<String> leakedIds,
            List<String> leakedPaths) {
        File[] files = dir.listFiles();
        if(files == null) {
            return;
        }
        for(File f : files) {
            if(f.isDirectory()) {
                findLeakedElementFiles(f, repoRoot, leakedIds, leakedPaths);
            }
            else {
                String id = extractIdFromElementFileName(f.getName());
                if(id != null && leakedIds.contains(id)) {
                    String repoRelativePath = repoRoot.toPath().relativize(f.toPath())
                            .toString().replace('\\', '/');
                    leakedPaths.add(repoRelativePath);
                }
            }
        }
    }
    
}
