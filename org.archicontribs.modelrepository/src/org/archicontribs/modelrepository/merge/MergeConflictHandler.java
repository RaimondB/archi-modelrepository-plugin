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
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.swt.widgets.Shell;

import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IFolder;
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
        
        // Pass 3: Detect moves for element-only conflicts (no folder.xml in conflict list).
        // This happens when branch A moves a folder (git sees delete+create of folder.xml)
        // and branch B only modifies an element inside the folder. Git auto-merges folder.xml
        // (delete wins) but the element file conflicts. We detect this by checking if the
        // element was resolved as moved (resolvedAsMove = true) and its containing folders
        // differ between ours and theirs models.
        for(MergeObjectInfo info : fMergeObjectInfos) {
            if(info.isFolderXml() || info.isPartOfMove()) {
                continue;
            }
            if(!info.isResolvedAsMove()) {
                continue; // Not a moved element
            }
            String folderPath = info.getFolderPath();
            if(processedFolderPaths.contains(folderPath)) {
                continue;
            }
            
            // The element exists in both models (after resolveMovedObject).
            // Find the folder ID from the ours model (the element's parent folder at the old path).
            EObject oursElement = info.getEObject(MergeObjectInfo.OURS);
            EObject theirsElement = info.getEObject(MergeObjectInfo.THEIRS);
            if(oursElement == null || theirsElement == null) {
                continue;
            }
            
            // Get containing folders from both models
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
            // Find the theirs folder path on disk.
            Set<String> exclude = new HashSet<>();
            exclude.add(folderPath);
            exclude.addAll(processedFolderPaths);
            String otherPath = findFolderOnDisk(folderId, exclude);
            if(otherPath == null) {
                continue;
            }
            
            // Determine which side is the mover.
            // oursFolderPath = old location (conflict path), theirsFolderPath = new location (disk).
            // Check if OURS has content at the conflict path - if not, OURS is the mover.
            byte[] headContent = null;
            try {
                headContent = fArchiRepo.getFileContents(info.getXMLPath(), getLocalRef());
            } catch(IOException e) { /* Fall through */ }
            boolean oursIsTheMover = (headContent == null);
            
            String oursFP = folderPath;
            String theirsFP = otherPath;
            
            processedFolderPaths.add(oursFP);
            processedFolderPaths.add(theirsFP);
            
            buildMoveGroup(folderId, new ArrayList<>(), oursFP, theirsFP, oursIsTheMover);
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
        return findFolderByIdRecursive(folderId, excludePaths, modelDir);
    }
    
    private String findFolderByIdRecursive(String folderId, Set<String> excludePaths, File dir) {
        String relativePath = fArchiRepo.getLocalRepositoryFolder().toPath()
                .relativize(dir.toPath()).toString().replace('\\', '/');
        
        if(!excludePaths.contains(relativePath)) {
            File folderXml = new File(dir, IGraficoConstants.FOLDER_XML);
            if(folderXml.exists()) {
                try {
                    byte[] content = Files.readAllBytes(folderXml.toPath());
                    String id = extractFolderIdFromXml(content);
                    if(folderId.equals(id)) {
                        return relativePath;
                    }
                } catch(IOException e) { /* skip */ }
            }
        }
        
        File[] subdirs = dir.listFiles(File::isDirectory);
        if(subdirs != null) {
            for(File subdir : subdirs) {
                String found = findFolderByIdRecursive(folderId, excludePaths, subdir);
                if(found != null) return found;
            }
        }
        return null;
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
                return extractFolderIdFromXml(content);
            }
        } catch(IOException e) {
            // Fall through
        }
        
        // Try theirs
        try {
            byte[] content = fArchiRepo.getFileContents(xmlPath, getTheirRef());
            if(content != null) {
                return extractFolderIdFromXml(content);
            }
        } catch(IOException e) {
            // Fall through
        }
        
        return null;
    }
    
    /**
     * Extract the id attribute from folder.xml content using simple string matching.
     */
    private static String extractFolderIdFromXml(byte[] content) {
        String xml = new String(content, java.nio.charset.StandardCharsets.UTF_8);
        int idStart = xml.indexOf("id=\""); //$NON-NLS-1$
        if(idStart < 0) return null;
        idStart += 4;
        int idEnd = xml.indexOf('"', idStart);
        if(idEnd < 0) return null;
        return xml.substring(idStart, idEnd);
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
        List<String> ours = new ArrayList<>();
        List<String> theirs = new ArrayList<>();
        // resolvedAsMove elements need special handling: git stage THEIRS
        // doesn't exist at the conflict path (theirs deleted/moved it), so
        // normal checkout would silently fail.  Instead, we always checkout
        // OURS to resolve the git conflict. Content + location is then
        // handled by consolidateMoveGroups() (all moves route through MoveGroups).
        List<String> moveResolved = new ArrayList<>();
        
        for(MergeObjectInfo info : getMergeObjectInfos()) {
            // Move group items are handled by consolidateMoveGroups()
            if(info.isPartOfMove()) {
                continue;
            }
            
            if(info.isResolvedAsMove()) {
                // Always checkout OURS to resolve the git conflict at the old path.
                // Content/location choice is handled by cleanupAutoMergedDuplicates().
                moveResolved.add(info.getXMLPath());
                log(IStatus.INFO, "[MergeConflictHandler] merge() resolvedAsMove: " + info.getXMLPath() //$NON-NLS-1$
                        + ", userChoice=" + (info.getUserChoice() == MergeObjectInfo.OURS ? "OURS" : "THEIRS")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                continue;
            }
            
            // Ours
            if(info.getUserChoice() == MergeObjectInfo.OURS) {
                ours.add(info.getXMLPath());
            }
            // Theirs
            else {
                theirs.add(info.getXMLPath());
            }
        }
        
        log(IStatus.INFO, "[MergeConflictHandler] merge() checkout: ours=" + ours.size() + ", theirs=" + theirs.size() //$NON-NLS-1$ //$NON-NLS-2$
                + ", moveResolved=" + moveResolved.size() //$NON-NLS-1$
                + ", moveGroupItems=" + getMergeObjectInfos().stream().filter(MergeObjectInfo::isPartOfMove).count()); //$NON-NLS-1$
        
        long t = System.nanoTime();
        try(Git git = Git.open(fArchiRepo.getLocalRepositoryFolder())) {
            if(!ours.isEmpty()) {
                checkout(git, Stage.OURS, ours);
            }
            if(!theirs.isEmpty()) {
                checkout(git, Stage.THEIRS, theirs);
            }
            // Resolve git conflicts for moved elements by checking out OURS
            // (the only stage that exists at the conflict path)
            if(!moveResolved.isEmpty()) {
                checkout(git, Stage.OURS, moveResolved);
            }
        }
        log(IStatus.INFO, "[MergeConflictHandler] merge() checkout done: " + (System.nanoTime() - t) / 1_000_000 + "ms"); //$NON-NLS-1$ //$NON-NLS-2$
        
        // Consolidate move groups: resolve chosen-path conflicts, move unique
        // elements from unchosen to chosen path, delete unchosen path, stage all.
        t = System.nanoTime();
        consolidateMoveGroups();
        log(IStatus.INFO, "[MergeConflictHandler] merge() consolidateMoveGroups done: " + (System.nanoTime() - t) / 1_000_000 + "ms"); //$NON-NLS-1$ //$NON-NLS-2$
        
        // Log git status after merge
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
        
        log(IStatus.INFO, "[MergeConflictHandler] merge() total: " + (System.nanoTime() - mergeStart) / 1_000_000 + "ms"); //$NON-NLS-1$ //$NON-NLS-2$
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
            
            // Build set of conflicting element filenames at the unchosen path for
            // targeted handling (these need content resolution, not just move/delete).
            Map<String, MergeObjectInfo> unchosenConflictElements = new HashMap<>();
            for(MergeObjectInfo info : group.relatedInfos) {
                if(info.isFolderXml()) continue;
                if(info.getFolderPath().equals(unchosenPath)) {
                    String filename = info.getXMLPath()
                            .substring(info.getXMLPath().lastIndexOf('/') + 1);
                    unchosenConflictElements.put(filename, info);
                }
            }
            
            // 1. Resolve conflict markers on folder.xml at unchosen path so we can delete cleanly
            //    Checkout OURS stage (which has content at the old path).
            List<String> folderConflictsToResolve = new ArrayList<>();
            for(MergeObjectInfo info : group.folderInfos) {
                if(info.getFolderPath().equals(unchosenPath)) {
                    folderConflictsToResolve.add(info.getXMLPath());
                }
            }
            
            // 2. For each conflicting element at the unchosen path, resolve based on
            //    the user's per-element content choice.
            List<String> oursCheckoutPaths = new ArrayList<>(folderConflictsToResolve);
            for(Map.Entry<String, MergeObjectInfo> entry : unchosenConflictElements.entrySet()) {
                MergeObjectInfo info = entry.getValue();
                // Always checkout OURS stage to get clean content at the unchosen path.
                // The unchosen path is where OURS has real content (theirs deleted it there).
                oursCheckoutPaths.add(info.getXMLPath());
            }
            if(!oursCheckoutPaths.isEmpty()) {
                try(Git git = Git.open(repoRoot)) {
                    // Determine which git stage has content at the unchosen path.
                    // Normally OURS stage has content at oursFolderPath, but when
                    // OURS is the mover, THEIRS has content there instead.
                    boolean oursStageAtUnchosen = unchosenPath.equals(group.oursFolderPath);
                    if(group.oursIsTheMover) {
                        oursStageAtUnchosen = !oursStageAtUnchosen;
                    }
                    Stage stage = oursStageAtUnchosen ? Stage.OURS : Stage.THEIRS;
                    checkout(git, stage, oursCheckoutPaths);
                }
            }
            
            // 3. Resolve conflict markers on chosen-path conflict files
            List<String> chosenConflicts = new ArrayList<>();
            for(MergeObjectInfo info : group.relatedInfos) {
                if(info.getFolderPath().equals(chosenPath)) {
                    Stage stage = (info.getUserChoice() == MergeObjectInfo.OURS)
                            ? Stage.OURS : Stage.THEIRS;
                    chosenConflicts.add(info.getXMLPath());
                }
            }
            if(!chosenConflicts.isEmpty()) {
                try(Git git = Git.open(repoRoot)) {
                    // Resolve per user choice - but all at once per stage
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
                    if(!oursAtChosen.isEmpty()) {
                        checkout(git, Stage.OURS, oursAtChosen);
                    }
                    if(!theirsAtChosen.isEmpty()) {
                        checkout(git, Stage.THEIRS, theirsAtChosen);
                    }
                }
            }
            
            // 4. Handle files at the unchosen path
            if(unchosenDir.isDirectory()) {
                File[] xmlFiles = unchosenDir.listFiles(
                        (d, name) -> name.endsWith(".xml")); //$NON-NLS-1$
                if(xmlFiles != null) {
                    for(File f : xmlFiles) {
                        if(IGraficoConstants.FOLDER_XML.equals(f.getName())) {
                            f.delete(); // Remove unchosen folder.xml
                            continue;
                        }
                        
                        File dest = new File(chosenDir, f.getName());
                        MergeObjectInfo conflictInfo = unchosenConflictElements.get(f.getName());
                        
                        if(conflictInfo != null) {
                            // Determine which side's content is at the unchosen path (resolved in step 2).
                            // Normally the unchosen path has OURS content; when OURS moved, it has THEIRS.
                            int contentAtUnchosen = group.oursIsTheMover
                                    ? MergeObjectInfo.THEIRS : MergeObjectInfo.OURS;
                            if(conflictInfo.getUserChoice() == contentAtUnchosen) {
                                // User wants the content version that's at the unchosen path.
                                // Overwrite the auto-merged copy at the chosen path.
                                Files.copy(f.toPath(), dest.toPath(),
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                            // Otherwise: dest already has the correct content.
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
                
                // 5. Delete unchosen directory if now empty
                deleteDirectoryIfEmpty(unchosenDir);
            }
        }
        
        // 6. Stage all changes (new, modified, deleted) in affected directories only
        Set<String> affectedPaths = new LinkedHashSet<>();
        for(MoveGroup group : fMoveGroups) {
            int locChoice2 = group.locationChoice;
            affectedPaths.add((locChoice2 == MergeObjectInfo.OURS)
                    ? group.oursFolderPath : group.theirsFolderPath);
            affectedPaths.add((locChoice2 == MergeObjectInfo.OURS)
                    ? group.theirsFolderPath : group.oursFolderPath);
        }
        
        log(IStatus.INFO, "[MergeConflictHandler] staging " + affectedPaths.size() //$NON-NLS-1$
                + " affected path(s): " + affectedPaths); //$NON-NLS-1$
        
        if(!tryNativeGitAdd(repoRoot, affectedPaths)) {
            // Fall back to JGit with targeted paths
            try(Git git = Git.open(repoRoot)) {
                AddCommand addNew = git.add();
                AddCommand addUpdated = git.add().setUpdate(true);
                for(String path : affectedPaths) {
                    addNew.addFilepattern(path);
                    addUpdated.addFilepattern(path);
                }
                addNew.call();
                addUpdated.call();
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
    
}
