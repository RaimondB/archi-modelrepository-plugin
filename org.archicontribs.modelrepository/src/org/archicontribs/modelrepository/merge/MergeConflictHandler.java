/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.merge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.archicontribs.modelrepository.grafico.GraficoModelImporter;
import org.archicontribs.modelrepository.grafico.IArchiRepository;
import org.archicontribs.modelrepository.grafico.IGraficoConstants;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.window.Window;
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
        
        MoveGroup(String moveGroupId, String folderName, 
                  String oldLocationBreadcrumb, String newLocationBreadcrumb,
                  List<MergeObjectInfo> relatedInfos) {
            this.moveGroupId = moveGroupId;
            this.folderName = folderName;
            this.oldLocationBreadcrumb = oldLocationBreadcrumb;
            this.newLocationBreadcrumb = newLocationBreadcrumb;
            this.relatedInfos = relatedInfos;
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
        // This could be null if Rebase is the default behaviour on the repo rather than merge when a Pull is done
        if(fMergeResult == null) {
            throw new IOException("MergeResult was null"); //$NON-NLS-1$
        }
        
        fProgressMonitor = pm;

        // Our model is the current loaded one
        fOurModel = fArchiRepo.locateModel();
        if(fOurModel == null) {
            throw new IOException(Messages.MergeConflictHandler_0);
        }
        
        // Their model needs to be extracted
        fTheirModel = extractModel(getTheirRef());
        
        // Create Merge Infos
        fMergeObjectInfos = new ArrayList<MergeObjectInfo>();
        for(String xmlPath : fMergeResult.getConflicts().keySet()) {
            fMergeObjectInfos.add(new MergeObjectInfo(xmlPath, this));
        }
        
        // For items where one side appears "deleted" (null), try to resolve the
        // element by ID in the other model. This handles element/folder moves:
        // Git sees a move as delete + create, so the file at the new path shows
        // "Deleted by us" even though the element exists in our model (just at a
        // different location). After resolving, both sides are populated and the
        // conflict shows as "Modified" with different locations instead.
        for(MergeObjectInfo info : fMergeObjectInfos) {
            info.resolveMovedObject();
        }
        
        // Detect folder moves among the conflicts and link related items
        detectFolderMoves();
    }
    
    /**
     * Detect folder moves among conflicting files.
     * 
     * A folder move is detected when:
     * - A folder.xml exists in conflicts with one side deleted (delete = part of the move)
     * - The same folder ID appears in another folder.xml conflict at a different path
     * 
     * When detected, all conflicts under the same folder path are tagged with a
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
        
        // Pass 2: Find IDs that appear at multiple paths = moves
        for(Map.Entry<String, List<MergeObjectInfo>> entry : folderIdToInfos.entrySet()) {
            List<MergeObjectInfo> infos = entry.getValue();
            if(infos.size() < 2) {
                continue;
            }
            
            String folderId = entry.getKey();
            String moveGroupId = "move:" + folderId; //$NON-NLS-1$
            
            // Build breadcrumb descriptions using the loaded models
            StringBuilder pathList = new StringBuilder();
            String folderName = ""; //$NON-NLS-1$
            String oldBreadcrumb = ""; //$NON-NLS-1$
            String newBreadcrumb = ""; //$NON-NLS-1$
            
            for(int i = 0; i < infos.size(); i++) {
                MergeObjectInfo info = infos.get(i);
                String breadcrumb = buildBreadcrumbForFolder(info);
                
                if(pathList.length() > 0) pathList.append(" \u2194 "); //$NON-NLS-1$
                pathList.append(breadcrumb);
                
                // First entry = "ours" side (old location), second = "theirs" side (new location)
                if(i == 0) {
                    oldBreadcrumb = breadcrumb;
                } else if(i == 1) {
                    newBreadcrumb = breadcrumb;
                }
            }
            
            // Get the folder name from either model
            EObject folderObj = com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(fOurModel, folderId);
            if(folderObj == null) {
                folderObj = com.archimatetool.model.util.ArchimateModelUtils.getObjectByID(fTheirModel, folderId);
            }
            if(folderObj instanceof INameable) {
                folderName = ((INameable)folderObj).getName();
            }
            
            String moveDesc = Messages.MergeConflictHandler_3 + pathList;
            
            // Tag all folder.xml conflicts for this move
            for(MergeObjectInfo folderInfo : infos) {
                folderInfo.setMoveGroupId(moveGroupId);
                folderInfo.setMoveDescription(moveDesc);
            }
            
            // Collect all related infos (folder.xml + elements in those folders)
            List<MergeObjectInfo> allRelated = new ArrayList<>(infos);
            
            // Tag all element conflicts under the moved folder paths
            for(MergeObjectInfo folderInfo : infos) {
                String folderPath = folderInfo.getFolderPath();
                for(MergeObjectInfo elementInfo : fMergeObjectInfos) {
                    if(!elementInfo.isFolderXml() 
                            && elementInfo.getFolderPath().equals(folderPath)
                            && elementInfo.getMoveGroupId() == null) {
                        elementInfo.setMoveGroupId(moveGroupId);
                        elementInfo.setMoveDescription(moveDesc);
                        allRelated.add(elementInfo);
                    }
                }
            }
            
            // Create MoveGroup
            fMoveGroups.add(new MoveGroup(moveGroupId, folderName,
                    oldBreadcrumb, newBreadcrumb, allRelated));
        }
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
        Dialog dialog = new ConflictsDialog(fShell, this, message);
        return dialog.open() == Window.OK ? true : false;
    }
    
    /**
     * Merge ours and theirs but don't commit
     * @throws IOException
     * @throws GitAPIException
     */
    public void merge() throws IOException, GitAPIException {
        List<String> ours = new ArrayList<>();
        List<String> theirs = new ArrayList<>();
        
        for(MergeObjectInfo info : getMergeObjectInfos()) {
            // Ours
            if(info.getUserChoice() == MergeObjectInfo.OURS) {
                ours.add(info.getXMLPath());
            }
            // Theirs
            else {
                theirs.add(info.getXMLPath());
            }
        }
        
        try(Git git = Git.open(fArchiRepo.getLocalRepositoryFolder())) {
            if(!ours.isEmpty()) {
                checkout(git, Stage.OURS, ours);
            }
            if(!theirs.isEmpty()) {
                checkout(git, Stage.THEIRS, theirs);
            }
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
