/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.archicontribs.modelrepository.UIPerfLogger;
import org.archicontribs.modelrepository.grafico.GraficoModelImporter.UnresolvedObject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Shell;

import com.archimatetool.editor.diagram.DiagramEditorInput;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.editor.model.ModelChecker;
import com.archimatetool.editor.ui.services.EditorManager;
import com.archimatetool.editor.utils.StringUtils;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IConnectable;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateComponent;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.INameable;
import com.archimatetool.model.util.ArchimateModelUtils;

/**
 * Import a model from Grafico files and handle conflicts, re-opening diagrams and status
 * 
 * @author Phillip Beauvoir
 */
public class GraficoModelLoader {
    
    private IArchiRepository fRepository;
    private boolean bHeadless;
    
    private List<IIdentifier> fRestoredObjects;
    
    /**
     * Descriptions of repaired folder.xml files for commit message traceability.
     * Populated by repairMissingFolderXml() and exposed via getRepairDetailsAsString().
     */
    private List<String> fRepairDetails;
    
    /**
     * Folder moves detected during repairMissingFolderXml().
     * These need interactive resolution (user chooses which location to keep).
     * Populated by repairMissingFolderXml(), consumed by applyFolderMoveResolutions().
     */
    private List<FolderMoveInfo> fFolderMoves;
    
    /** Flag to skip repair in loadModel() when caller has already handled it */
    private boolean fRepairAlreadyDone;
    
    public GraficoModelLoader(IArchiRepository repository) {
        fRepository = repository;
        bHeadless = false;
    }

    public GraficoModelLoader(IArchiRepository repository, boolean headless ) {
        fRepository = repository;
        bHeadless = headless;
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
    
    /**
     * Load the model
     * @return
     * @throws IOException
     */
    public IArchimateModel loadModel() throws IOException {
        return loadModel(null);
    }
    
    /**
     * Load the model with external progress monitor.
     * This is useful when combining loading with other operations in a single progress dialog.
     * @param monitor Progress monitor (can be null for headless/own dialog behavior)
     * @return The loaded model
     * @throws IOException
     */
    public IArchimateModel loadModel(IProgressMonitor monitor) throws IOException {
        fRestoredObjects = null;
        
        // Repair missing folder.xml files before import (unless already done by caller).
        // After git merges, directories may exist with element files but no folder.xml,
        // which causes the importer to throw IOException or silently drop elements.
        if(!fRepairAlreadyDone) {
            int repairedFolders = repairMissingFolderXml();
            if(repairedFolders > 0) {
                log(IStatus.INFO, "[GraficoModelLoader] Restored " + repairedFolders + " missing folder.xml file(s) from git history"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            
            // Auto-apply folder move resolutions with default choices (keep new location).
            // In interactive mode, the caller should call repairMissingFolderXml(),
            // show the resolution dialog, call applyFolderMoveResolutions(),
            // then call loadModel().
            if(hasPendingFolderMoves()) {
                int moveRepairs = applyFolderMoveResolutions();
                if(moveRepairs > 0) {
                    log(IStatus.INFO, "[GraficoModelLoader] Auto-resolved " + moveRepairs + " folder move(s) with default choices"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        
        // Import Grafico Model
        GraficoModelImporter importer = new GraficoModelImporter(fRepository.getLocalRepositoryFolder());
        
        IArchimateModel[] graficoModel = new IArchimateModel[1];
        IOException[] exception = new IOException[1];
        
        // If external monitor is provided, use it directly (no separate dialog)
        // Otherwise, support headless mode or create our own dialog
        if(monitor != null) {
            // Use the provided external monitor
            try {
                graficoModel[0] = importer.importAsModel(monitor);
            }
            catch(IOException ex) {
                exception[0] = ex;
            }
        } else if(!bHeadless) {
            // No external monitor, not headless - create our own dialog
            // Use ProgressMonitorDialog instead of busyCursorWhile() to ensure
            // the event loop is pumped and asyncExec runnables are processed
            try {
                org.eclipse.jface.dialogs.ProgressMonitorDialog dialog = 
                    new org.eclipse.jface.dialogs.ProgressMonitorDialog(
                        PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell());
                dialog.run(true, true, new IRunnableWithProgress() {
                    @Override
                    public void run(IProgressMonitor pm) throws InvocationTargetException, InterruptedException {
                        try {
                            graficoModel[0] = importer.importAsModel(pm);
                        }
                        catch(IOException ex) {
                            exception[0] = ex;
                        }
                    }
                });
            }
            catch(InvocationTargetException | InterruptedException ex) {
                throw new IOException(ex.getMessage(), ex);
            }
        } else {
            // Headless mode - no progress
            try {
                graficoModel[0] = importer.importAsModel();
            }
            catch(IOException ex) {
                exception[0] = ex;
            }        	
        }
        
        if(exception[0] != null) {
            throw exception[0];
        }
        
        if(graficoModel[0] == null) {
            return null;
        }
        
        // Set file name on the grafico model so we can locate it
        graficoModel[0].setFile(fRepository.getTempModelFile());
        
        // Resolve missing objects
        List<UnresolvedObject> unresolvedObjects = importer.getUnresolvedObjects();
        if(unresolvedObjects != null) {
            graficoModel[0] = restoreProblemObjects(unresolvedObjects);
        }
        
        if(!bHeadless)
        {
	        // Save it
	        IEditorModelManager.INSTANCE.saveModel(graficoModel[0]);
	        
	        // Close and re-open the corresponding model if it is already open
	        IArchimateModel model = fRepository.locateModel();
	        if(model != null) {
	            // Store ids of open diagrams
	            List<String> openModelIDs = getOpenDiagramModelIdentifiers(model); // Store ids of open diagrams
	            IEditorModelManager.INSTANCE.closeModel(model);
	            IEditorModelManager.INSTANCE.openModel(graficoModel[0]);
	            reopenEditors(graficoModel[0], openModelIDs);
	        }
        } else {
        	// Repair connection endpoint mismatches before validation.
        	// After GRAFICO import, some diagram connections may reference elements
        	// that don't match their underlying relationship's source/target
        	// (e.g. from merge conflicts or manual diagram edits).
        	int repaired = repairConnectionEndpoints(graficoModel[0]);
        	if(repaired > 0) {
        	    log(IStatus.INFO, "[GraficoModelLoader] Repaired " + repaired + " mismatched connection endpoint(s)"); //$NON-NLS-1$ //$NON-NLS-2$
        	}

        	// Validate that the model is correct after fixes so it can be exported again
            ModelChecker checker = new ModelChecker(graficoModel[0]);
            if(!checker.checkAll()) {
            	//String errorMessage = checker.buildMessageSummary(); Wait with this until PR accepted for Archi
                String errorMessage = Messages.GraficoModelLoader_1 + String.join("\n", checker.getErrorMessages());

            	throw new IOException(errorMessage);
            }
        }
        
        return graficoModel[0];
    }
    
    /**
     * Open an already-imported model in the editor.
     * This method performs the UI operations that must run on the UI thread:
     * save, close old model, open new model, reopen editors.
     * 
     * <p>Use this when the import was done in a background thread and you need
     * to complete the loading on the UI thread.</p>
     * 
     * @param graficoModel The model that was already imported
     * @param importer The importer used (to check for unresolved objects)
     * @throws IOException if there's an error saving or opening the model
     */
    public void openModel(IArchimateModel graficoModel, GraficoModelImporter importer) throws IOException {
        if(graficoModel == null) {
            return;
        }
        
        long tOpen = System.nanoTime();
        fRestoredObjects = null;
        
        // Set file name on the grafico model so we can locate it
        graficoModel.setFile(fRepository.getTempModelFile());
        
        // Resolve missing objects if any
        List<UnresolvedObject> unresolvedObjects = importer != null ? importer.getUnresolvedObjects() : null;
        if(unresolvedObjects != null) {
            graficoModel = restoreProblemObjects(unresolvedObjects);
        }
        
        // Save the model
        long t = System.nanoTime();
        IEditorModelManager.INSTANCE.saveModel(graficoModel);
        UIPerfLogger.log("[ModelLoader]", "saveModel", t); //$NON-NLS-1$ //$NON-NLS-2$
        
        // Close and re-open the corresponding model if it is already open
        IArchimateModel model = fRepository.locateModel();
        if(model != null) {
            // Store ids of open diagrams
            List<String> openModelIDs = getOpenDiagramModelIdentifiers(model);
            
            // Save shell state before close/open cycle.
            // closeModel() can cause the shell to lose its maximized state when
            // the editor area collapses, resulting in a visible window resize.
            Shell shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
            boolean wasMaximized = shell.getMaximized();
            Rectangle savedBounds = shell.getBounds();
            
            shell.setRedraw(false);
            try {
                t = System.nanoTime();
                IEditorModelManager.INSTANCE.closeModel(model);
                UIPerfLogger.log("[ModelLoader]", "closeModel", t); //$NON-NLS-1$ //$NON-NLS-2$
                
                t = System.nanoTime();
                IEditorModelManager.INSTANCE.openModel(graficoModel);
                UIPerfLogger.log("[ModelLoader]", "openModel", t); //$NON-NLS-1$ //$NON-NLS-2$
                
                t = System.nanoTime();
                reopenEditors(graficoModel, openModelIDs);
                UIPerfLogger.log("[ModelLoader]", "reopenEditors (" + openModelIDs.size() + " diagrams)", t); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            } finally {
                shell.setRedraw(true);
                
                // Defer shell state restoration to after the current event loop completes.
                // Setting maximized/bounds while layout is still recalculating causes a
                // visible minimize/maximize flash on Windows.
                shell.getDisplay().asyncExec(() -> {
                    if(!shell.isDisposed()) {
                        if(wasMaximized && !shell.getMaximized()) {
                            shell.setMaximized(true);
                        } else if(!wasMaximized) {
                            shell.setBounds(savedBounds);
                        }
                    }
                });
            }
        }
        UIPerfLogger.log("[ModelLoader]", "openModel() total", tOpen); //$NON-NLS-1$ //$NON-NLS-2$
    }
    
    /**
     * @return The list of resolved objects as a message string or null
     */
    public String getRestoredObjectsAsString() {
        if(fRestoredObjects == null) {
            return null;
        }
        
        String s = Messages.GraficoModelLoader_0;
        
        for(IIdentifier id : fRestoredObjects) {
            if(id instanceof INameable) {
                String name = ((INameable)id).getName();
                String className = id.eClass().getName();
                s += "\n" + (StringUtils.isSet(name) ? name + " (" + className + ")" : className); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        
        return s;
    }
    
    /**
     * @return The list of repair details as a message string or null
     */
    public String getRepairDetailsAsString() {
        if(fRepairDetails == null || fRepairDetails.isEmpty()) {
            return null;
        }
        
        String s = "\nFolder repairs:"; //$NON-NLS-1$
        
        for(String detail : fRepairDetails) {
            s += "\n- " + detail; //$NON-NLS-1$
        }
        
        return s;
    }
    
    /**
     * @return The list of detected folder moves that need user resolution, or null if none
     */
    public List<FolderMoveInfo> getFolderMoves() {
        return fFolderMoves;
    }
    
    /**
     * @return true if there are pending folder moves that need user resolution
     */
    public boolean hasPendingFolderMoves() {
        return fFolderMoves != null && !fFolderMoves.isEmpty();
    }
    
    /**
     * Apply the user's resolution choices for detected folder moves.
     * Must be called after the user has set choices on each {@link FolderMoveInfo}
     * via {@link FolderMoveInfo#setUserChoice(int)}.
     * 
     * <p>For each move:</p>
     * <ul>
     *   <li><b>Keep new location</b> (default): Remove duplicate elements from old location.
     *       If unique elements remain, create a [MERGE FIX] folder at the old location.
     *       Otherwise skip the old directory entirely.</li>
     *   <li><b>Keep old location</b>: Restore the original folder.xml at the old location.
     *       Create a [MERGE FIX] folder at the new location (so it gets a new ID).
     *       Remove duplicate elements from the new location.</li>
     * </ul>
     * 
     * @return The number of folders repaired
     * @throws IOException if files cannot be written
     */
    public int applyFolderMoveResolutions() throws IOException {
        if(fFolderMoves == null || fFolderMoves.isEmpty()) {
            return 0;
        }
        
        if(fRepairDetails == null) {
            fRepairDetails = new ArrayList<>();
        }
        
        int repaired = 0;
        List<String> repairedDirPatterns = new ArrayList<>();
        
        for(FolderMoveInfo move : fFolderMoves) {
            if(move.getUserChoice() == FolderMoveInfo.KEEP_NEW_LOCATION) {
                repaired += applyKeepNewLocation(move, repairedDirPatterns);
            } else {
                repaired += applyKeepOldLocation(move, repairedDirPatterns);
            }
        }
        
        // Stage repaired directories
        if(!repairedDirPatterns.isEmpty()) {
            stageDirectories(repairedDirPatterns);
        }
        
        // Mark repair as done so loadModel() skips it
        fFolderMoves.clear();
        fRepairAlreadyDone = true;
        
        return repaired;
    }
    
    /**
     * Apply "keep new location" resolution: remove duplicates from old dir,
     * move unique elements to new dir, clean up empty old dir.
     */
    private int applyKeepNewLocation(FolderMoveInfo move, List<String> repairedDirPatterns) throws IOException {
        File oldDir = move.getOldDir();
        File destDir = move.getNewDir();
        String dirPattern = move.getOldRelativePath();
        
        // Remove duplicate elements from old location
        removeDuplicateElements(oldDir, destDir);
        
        // Move unique elements to the new location
        File[] remainingXmls = oldDir.listFiles((d, name) ->
                name.endsWith(".xml") && !IGraficoConstants.FOLDER_XML.equals(name)); //$NON-NLS-1$
        int uniqueRemaining = remainingXmls != null ? remainingXmls.length : 0;
        
        if(uniqueRemaining > 0) {
            for(File xmlFile : remainingXmls) {
                File destFile = new File(destDir, xmlFile.getName());
                Files.move(xmlFile.toPath(), destFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            repairedDirPatterns.add(move.getNewRelativePath());
            fRepairDetails.add("Moved " + uniqueRemaining + " unique element(s) to new location: " //$NON-NLS-1$ //$NON-NLS-2$
                    + dirPattern + " → " + move.getNewRelativePath()); //$NON-NLS-1$
            log(IStatus.INFO, "[GraficoModelLoader] Moved " + uniqueRemaining //$NON-NLS-1$
                    + " unique element(s) from " + dirPattern //$NON-NLS-1$
                    + " to " + move.getNewRelativePath()); //$NON-NLS-1$
        }
        
        // Clean up old directory if empty (no XMLs, no subdirs)
        if(!hasSubdirectories(oldDir)) {
            // Delete folder.xml if it exists, then remove empty dir
            File oldFolderXml = new File(oldDir, IGraficoConstants.FOLDER_XML);
            if(oldFolderXml.exists()) {
                oldFolderXml.delete();
            }
            // Delete any remaining non-XML files and the directory itself
            File[] remaining = oldDir.listFiles();
            if(remaining == null || remaining.length == 0) {
                oldDir.delete();
                repairedDirPatterns.add(dirPattern);
            }
            fRepairDetails.add("Cleaned up empty moved folder: " + dirPattern //$NON-NLS-1$
                    + " (kept at " + move.getNewRelativePath() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            log(IStatus.INFO, "[GraficoModelLoader] Cleaned up moved folder " + dirPattern //$NON-NLS-1$
                    + " — elements moved to " + move.getNewRelativePath()); //$NON-NLS-1$
            return uniqueRemaining > 0 ? 1 : 0;
        }
        
        // Has subdirectories — keep the dir but create [MERGE FIX] folder.xml
        // so the directory has a valid parent folder  
        createMergeFixFolderXml(oldDir, move.getFolderName());
        repairedDirPatterns.add(dirPattern);
        fRepairDetails.add("Created [MERGE FIX] folder for remaining subdirectories: " + dirPattern //$NON-NLS-1$
                + " (folder kept at " + move.getNewRelativePath() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        log(IStatus.INFO, "[GraficoModelLoader] Created [MERGE FIX] at " + dirPattern //$NON-NLS-1$
                + " for subdirectories; " + uniqueRemaining + " unique element(s) moved"); //$NON-NLS-1$ //$NON-NLS-2$
        return 1;
    }
    
    /**
     * Apply "keep old location" resolution: restore original folder.xml at old location,
     * create [MERGE FIX] at new location, remove duplicates from new location.
     */
    private int applyKeepOldLocation(FolderMoveInfo move, List<String> repairedDirPatterns) throws IOException {
        File oldDir = move.getOldDir();
        File destDir = move.getNewDir();
        String oldDirPattern = move.getOldRelativePath();
        String newDirPattern = move.getNewRelativePath();
        
        // 1. Restore original folder.xml at old location (with original ID)
        File oldFolderXml = new File(oldDir, IGraficoConstants.FOLDER_XML);
        Files.write(oldFolderXml.toPath(), move.getHistoricalContent());
        repairedDirPatterns.add(oldDirPattern);
        
        // 2. Remove duplicate elements from old location (they exist at new location too)
        removeDuplicateElements(oldDir, destDir);
        
        // 3. Create [MERGE FIX] at new location (gets a new ID, since old location keeps original)
        String newFolderName = GraficoUtils.extractNameFromFolderXml(
                Files.readAllBytes(new File(destDir, IGraficoConstants.FOLDER_XML).toPath()));
        createMergeFixFolderXml(destDir, newFolderName != null ? newFolderName : destDir.getName());
        repairedDirPatterns.add(newDirPattern);
        
        // 4. Remove duplicate elements from new location (they exist at old location now)
        removeDuplicateElements(destDir, oldDir);
        
        fRepairDetails.add("Restored folder at original location: " + oldDirPattern //$NON-NLS-1$
                + " (moved " + newDirPattern + " to [MERGE FIX])"); //$NON-NLS-1$ //$NON-NLS-2$
        log(IStatus.INFO, "[GraficoModelLoader] Kept folder at " + oldDirPattern //$NON-NLS-1$
                + ", created [MERGE FIX] at " + newDirPattern); //$NON-NLS-1$
        return 2; // Both locations were repaired
    }
    
    /**
     * Classify element XML files in sourceDir into duplicates and unique,
     * based on whether they also exist at destDir (by filename).
     */
    private void classifyElements(File sourceDir, File destDir,
                                  List<String> duplicates, List<String> unique) {
        File[] sourceXmls = sourceDir.listFiles((d, name) ->
                name.endsWith(".xml") && !IGraficoConstants.FOLDER_XML.equals(name)); //$NON-NLS-1$
        if(sourceXmls == null) {
            return;
        }
        
        java.util.Set<String> destNames = new java.util.HashSet<>();
        File[] destXmls = destDir.listFiles((d, name) ->
                name.endsWith(".xml") && !IGraficoConstants.FOLDER_XML.equals(name)); //$NON-NLS-1$
        if(destXmls != null) {
            for(File f : destXmls) {
                destNames.add(f.getName());
            }
        }
        
        for(File f : sourceXmls) {
            if(destNames.contains(f.getName())) {
                duplicates.add(f.getName());
            } else {
                unique.add(f.getName());
            }
        }
    }
    
    /**
     * Stage directories via git add so the DirCache-based importer can see them.
     */
    private void stageDirectories(List<String> dirPatterns) {
        try(Git git = Git.open(fRepository.getLocalRepositoryFolder())) {
            var addCommand = git.add();
            for(String dirPattern : dirPatterns) {
                addCommand.addFilepattern(dirPattern);
            }
            addCommand.call();
        } catch(IOException | GitAPIException ex) {
            log(IStatus.WARNING, "[GraficoModelLoader] Failed to stage repaired directories: " + ex.getMessage()); //$NON-NLS-1$
        }
    }
    
    /**
     * Find the problem object xml files from the commit history and restore them
     * @param unresolvedObjects 
     * @return
     * @throws IOException
     */
    private IArchimateModel restoreProblemObjects(List<UnresolvedObject> unresolvedObjects) throws IOException {
        fRestoredObjects = new ArrayList<IIdentifier>();
        
        List<String> restoredIdentifiers = new ArrayList<String>();
        
        try(Repository repository = Git.open(fRepository.getLocalRepositoryFolder()).getRepository()) {
            try(RevWalk revWalk = new RevWalk(repository)) {
                for(UnresolvedObject unresolved : unresolvedObjects) {
                    String missingFileName = unresolved.missingObjectURI.lastSegment();
                    String missingObjectID = unresolved.missingObjectURI.fragment();
                    
                    // Already got this one
                    if(restoredIdentifiers.contains(missingObjectID)) {
                        continue;
                    }
                    
                    boolean found = false;
                    
                    // Reset RevWalk
                    revWalk.reset();
                    ObjectId id = repository.resolve(IGraficoConstants.HEAD);
                    if(id != null) {
                        revWalk.markStart(revWalk.parseCommit(id)); 
                    }
                    
                    // Iterate all commits
                    for(RevCommit commit : revWalk ) {
                        try(TreeWalk treeWalk = new TreeWalk(repository)) {
                            treeWalk.addTree(commit.getTree());
                            treeWalk.setRecursive(true);
                            
                            // Iterate through all files
                            // We can't use a PathFilter for the file name as its path is not correct
                            while(!found && treeWalk.next()) {
                                // File is found
                                if(treeWalk.getPathString().endsWith(missingFileName)) {
                                    // Save file
                                    ObjectId objectId = treeWalk.getObjectId(0);
                                    ObjectLoader loader = repository.open(objectId);

                                    File file = new File(fRepository.getLocalRepositoryFolder(), treeWalk.getPathString());
                                    file.getParentFile().mkdirs();
                                    
                                    try(FileOutputStream out = new FileOutputStream(file)) {
                                        loader.copyTo(out);
                                    }
                                    
                                    restoredIdentifiers.add(missingObjectID);
                                    found = true;
                                }
                            }
                        }
                        
                        if(found) {
                            break;
                        }
                    }
                }
                
                revWalk.dispose();
            }
        }
        
        // Then re-import
        GraficoModelImporter importer = new GraficoModelImporter(fRepository.getLocalRepositoryFolder());
        IArchimateModel graficoModel = importer.importAsModel();
        graficoModel.setFile(fRepository.getTempModelFile()); // do this again
        
        // Collect restored objects
        for(Iterator<EObject> iter = graficoModel.eAllContents(); iter.hasNext();) {
            EObject element = iter.next();
            for(String id : restoredIdentifiers) {
                if(element instanceof IIdentifier && id.equals(((IIdentifier)element).getId())) {
                    fRestoredObjects.add((IIdentifier)element);
                }
            }
        }
        
        return graficoModel;
    }

    /**
     * Repair diagram connections whose visual source/target endpoints don't match 
     * the underlying relationship's source/target concepts.
     * 
     * This happens when GRAFICO merge conflicts cause a connection's source or target
     * diagram object to reference a different concept than the relationship expects.
     * For each mismatched connection, we search the same diagram for a diagram object 
     * representing the correct concept and rewire the connection.
     * 
     * @param model The model to repair
     * @return The number of connections repaired
     */
    int repairConnectionEndpoints(IArchimateModel model) {
        int repaired = 0;
        
        // Collect all connections first to avoid ConcurrentModificationException
        // when connect() modifies the model tree during iteration
        List<IDiagramModelArchimateConnection> connections = new ArrayList<>();
        for(Iterator<EObject> iter = model.eAllContents(); iter.hasNext();) {
            EObject eObject = iter.next();
            if(eObject instanceof IDiagramModelArchimateConnection connection) {
                connections.add(connection);
            }
        }
        
        for(IDiagramModelArchimateConnection connection : connections) {
            IArchimateRelationship relation = connection.getArchimateRelationship();
            if(relation == null) {
                continue;
            }
            
            IConnectable currentSource = connection.getSource();
            IConnectable currentTarget = connection.getTarget();
            
            // Check source mismatch
            boolean sourceMismatch = currentSource instanceof IDiagramModelArchimateComponent dmc
                    && dmc.getArchimateConcept() != relation.getSource();
            
            // Check target mismatch
            boolean targetMismatch = currentTarget instanceof IDiagramModelArchimateComponent dmc
                    && dmc.getArchimateConcept() != relation.getTarget();
            
            if(!sourceMismatch && !targetMismatch) {
                continue;
            }
            
            IDiagramModel diagram = connection.getDiagramModel();
            if(diagram == null) {
                continue;
            }
            
            IConnectable newSource = currentSource;
            IConnectable newTarget = currentTarget;
            
            if(sourceMismatch) {
                IConnectable found = findDiagramComponentForConcept(diagram, relation.getSource());
                if(found != null) {
                    newSource = found;
                }
            }
            
            if(targetMismatch) {
                IConnectable found = findDiagramComponentForConcept(diagram, relation.getTarget());
                if(found != null) {
                    newTarget = found;
                }
            }
            
            // Only rewire if we actually found replacements
            if(newSource != currentSource || newTarget != currentTarget) {
                String diagramName = diagram.getName();
                String connId = connection.getId();
                log(IStatus.INFO, "[GraficoModelLoader] Rewiring connection " + connId //$NON-NLS-1$
                        + " in '" + diagramName + "'" //$NON-NLS-1$ //$NON-NLS-2$
                        + (sourceMismatch ? " (source)" : "") //$NON-NLS-1$ //$NON-NLS-2$
                        + (targetMismatch ? " (target)" : "")); //$NON-NLS-1$ //$NON-NLS-2$
                connection.connect(newSource, newTarget);
                repaired++;
            }
        }
        
        return repaired;
    }
    
    /**
     * Find a diagram component (object or connection) in the given diagram that references
     * the specified ArchiMate concept.
     * 
     * @param diagram The diagram to search
     * @param concept The concept to find a visual representation of
     * @return The first matching diagram component, or null if not found
     */
    private IConnectable findDiagramComponentForConcept(IDiagramModel diagram, IArchimateConcept concept) {
        if(concept == null) {
            return null;
        }
        
        for(Iterator<EObject> iter = diagram.eAllContents(); iter.hasNext();) {
            EObject child = iter.next();
            if(child instanceof IDiagramModelArchimateComponent dmc && dmc.getArchimateConcept() == concept) {
                return dmc;
            }
        }
        
        return null;
    }

    /**
     * Scan the GRAFICO model directory tree and repair any directories that contain
     * element XML files but are missing their folder.xml.
     * 
     * <p>This happens after git merges in two scenarios:</p>
     * <ol>
     *   <li><b>Simple merge conflict:</b> One side adds elements in a subfolder, the other
     *       side doesn't have that subfolder. Git creates the element files but may lose
     *       the folder.xml. In this case, restoring from git history is safe.</li>
     *   <li><b>Folder move:</b> Git sees a folder move as delete + create. After merge,
     *       the old location may still have element files but no folder.xml. Restoring
     *       the old folder.xml would undo the move (duplicate ID). Instead, we detect the
     *       move and let the user choose which location to keep.</li>
     * </ol>
     * 
     * <p>This method handles simple restores (no move) and unknown folders immediately.
     * Folder moves are collected in {@link #fFolderMoves} for interactive resolution
     * via {@link #getFolderMoves()} and {@link #applyFolderMoveResolutions()}.</p>
     * 
     * @return The number of folder.xml files restored or created (excluding pending moves)
     * @throws IOException if the model directory cannot be read
     */
    public int repairMissingFolderXml() throws IOException {
        File modelDir = new File(fRepository.getLocalRepositoryFolder(), IGraficoConstants.MODEL_FOLDER);
        if(!modelDir.isDirectory()) {
            return 0;
        }
        
        List<File> dirsWithMissingFolderXml = new ArrayList<>();
        findDirsWithMissingFolderXml(modelDir, dirsWithMissingFolderXml);
        
        // Build a map of folder ID → directories (for move and duplicate detection)
        java.util.Map<String, java.util.List<File>> folderIdMap = collectFolderIdMap(modelDir);
        java.util.Set<String> existingFolderIds = new java.util.HashSet<>(folderIdMap.keySet());
        
        fRepairDetails = new ArrayList<>();
        fFolderMoves = new ArrayList<>();
        
        // Detect duplicate folder IDs (B6: both branches moved same folder to different locations)
        detectDuplicateFolderIds(modelDir, folderIdMap);
        
        if(dirsWithMissingFolderXml.isEmpty() && fFolderMoves.isEmpty()) {
            return 0;
        }
        
        // Repair each directory that is missing its folder.xml
        List<String> repairedDirPatterns = new ArrayList<>();
        try(Repository repository = Git.open(fRepository.getLocalRepositoryFolder()).getRepository()) {
            for(File dir : dirsWithMissingFolderXml) {
                String dirPattern = fRepository.getLocalRepositoryFolder().toPath()
                        .relativize(dir.toPath())
                        .toString().replace('\\', '/');
                
                if(repairSingleMissingFolderXml(repository, modelDir, dir, dirPattern, existingFolderIds)) {
                    repairedDirPatterns.add(dirPattern);
                }
            }
        }
        
        // Stage ALL files in repaired directories
        if(!repairedDirPatterns.isEmpty()) {
            stageDirectories(repairedDirPatterns);
        }
        
        return repairedDirPatterns.size();
    }
    
    /**
     * Repair a single directory that is missing its folder.xml.
     * Tries to restore from git history; creates [MERGE FIX] if no history found;
     * detects folder moves when the historical ID already exists elsewhere.
     * 
     * @return true if the directory was repaired (restored or [MERGE FIX] created)
     */
    private boolean repairSingleMissingFolderXml(Repository repository, File modelDir, File dir,
            String dirPattern, java.util.Set<String> existingFolderIds) throws IOException {
        String relativePath = fRepository.getLocalRepositoryFolder().toPath()
                .relativize(dir.toPath().resolve(IGraficoConstants.FOLDER_XML))
                .toString().replace('\\', '/');
        
        byte[] historicalContent = loadFolderXmlFromHistory(repository, relativePath);
        
        if(historicalContent == null) {
            // Not found in history — create a merge-fix folder
            createMergeFixFolderXml(dir, dir.getName());
            fRepairDetails.add("Created [MERGE FIX] folder (no history found): " + dirPattern); //$NON-NLS-1$
            log(IStatus.INFO, "[GraficoModelLoader] Created [MERGE FIX] folder.xml for " + relativePath); //$NON-NLS-1$
            return true;
        }
        
        String historicalId = GraficoUtils.extractIdFromFolderXml(historicalContent);
        
        if(historicalId != null && existingFolderIds.contains(historicalId)) {
            // ID already exists elsewhere = folder move
            return handleFolderMoveDetection(modelDir, dir, dirPattern, relativePath,
                    historicalContent, historicalId);
        }
        
        // ID doesn't exist elsewhere — safe to restore from history
        restoreFolderXmlFromHistory(dir, dirPattern, relativePath, historicalContent,
                historicalId, existingFolderIds);
        return true;
    }
    
    /**
     * Handle case where a missing folder.xml has an ID that already exists elsewhere (= move).
     * Collects a FolderMoveInfo for interactive resolution, or creates [MERGE FIX] as fallback.
     * @return true if a [MERGE FIX] was created, false if move was deferred to user
     */
    private boolean handleFolderMoveDetection(File modelDir, File dir, String dirPattern,
            String relativePath, byte[] historicalContent, String historicalId) throws IOException {
        File destDir = GraficoUtils.findFolderDirById(modelDir, historicalId);
        String folderName = GraficoUtils.extractNameFromFolderXml(historicalContent);
        
        if(destDir != null) {
            List<String> duplicates = new ArrayList<>();
            List<String> unique = new ArrayList<>();
            classifyElements(dir, destDir, duplicates, unique);
            
            String destRelPath = fRepository.getLocalRepositoryFolder().toPath()
                    .relativize(destDir.toPath())
                    .toString().replace('\\', '/');
            
            FolderMoveInfo moveInfo = new FolderMoveInfo(
                    folderName != null ? folderName : dir.getName(),
                    historicalId, dir, destDir,
                    dirPattern, destRelPath,
                    duplicates, unique, historicalContent);
            fFolderMoves.add(moveInfo);
            
            log(IStatus.INFO, "[GraficoModelLoader] Folder move detected for " + relativePath //$NON-NLS-1$
                    + " (id " + historicalId + " exists at " + destRelPath //$NON-NLS-1$ //$NON-NLS-2$
                    + "). " + duplicates.size() + " duplicates, " + unique.size() + " unique."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return false; // Not repaired yet — deferred to user
        }
        
        // destDir not found (shouldn't happen) — fall through to [MERGE FIX]
        createMergeFixFolderXml(dir, folderName != null ? folderName : dir.getName());
        fRepairDetails.add("Created [MERGE FIX] folder for moved folder: " + dirPattern //$NON-NLS-1$
                + " (original id " + historicalId + " exists elsewhere)"); //$NON-NLS-1$ //$NON-NLS-2$
        log(IStatus.INFO, "[GraficoModelLoader] Folder move detected for " + relativePath //$NON-NLS-1$
                + " (id " + historicalId + " exists elsewhere). Created [MERGE FIX] folder."); //$NON-NLS-1$ //$NON-NLS-2$
        return true;
    }
    
    /**
     * Restore a folder.xml from historical content (no move conflict).
     */
    private void restoreFolderXmlFromHistory(File dir, String dirPattern, String relativePath,
            byte[] historicalContent, String historicalId, java.util.Set<String> existingFolderIds)
            throws IOException {
        File folderXmlFile = new File(dir, IGraficoConstants.FOLDER_XML);
        Files.write(folderXmlFile.toPath(), historicalContent);
        if(historicalId != null) {
            existingFolderIds.add(historicalId);
        }
        String folderName = GraficoUtils.extractNameFromFolderXml(historicalContent);
        fRepairDetails.add("Restored folder.xml from git history: " + dirPattern //$NON-NLS-1$
                + (folderName != null ? " (" + folderName + ")" : "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        log(IStatus.INFO, "[GraficoModelLoader] Restored folder.xml from git history: " + relativePath); //$NON-NLS-1$
    }
    
    /**
     * Recursively find directories under modelDir that contain .xml element files
     * but are missing folder.xml.
     */
    void findDirsWithMissingFolderXml(File dir, List<File> result) {
        File folderXml = new File(dir, IGraficoConstants.FOLDER_XML);
        
        if(!folderXml.exists()) {
            // Check if this directory contains any .xml element files or subdirectories
            // (a folder might only contain subfolders, not direct elements)
            File[] xmlFiles = dir.listFiles((d, name) -> name.endsWith(".xml")); //$NON-NLS-1$
            File[] subdirs = dir.listFiles(File::isDirectory);
            if((xmlFiles != null && xmlFiles.length > 0) || (subdirs != null && subdirs.length > 0)) {
                result.add(dir);
            }
        }
        
        // Recurse into subdirectories
        File[] subdirs = dir.listFiles(File::isDirectory);
        if(subdirs != null) {
            for(File subdir : subdirs) {
                findDirsWithMissingFolderXml(subdir, result);
            }
        }
    }
    
    /**
     * Collect all folder IDs from existing folder.xml files in the model tree.
     * Used to detect folder moves (where the same ID exists in a different location).
     */
    java.util.Set<String> collectExistingFolderIds(File modelDir) {
        java.util.Map<String, java.util.List<File>> idMap = collectFolderIdMap(modelDir);
        return idMap.keySet();
    }
    
    /**
     * Collect a map of folder ID → list of directories containing that ID.
     * Optimized: for user-created subfolders, the directory name IS the folder ID
     * (matching GraficoModelExporter.getNameFor()), so no folder.xml read is needed.
     * Only top-level typed folders (business, technology, etc.) and model root
     * require reading folder.xml.
     * 
     * <p>Duplicate entries (same ID mapping to multiple directories) indicate
     * the B6 scenario: both branches moved the same folder to different locations.</p>
     */
    java.util.Map<String, java.util.List<File>> collectFolderIdMap(File modelDir) {
        java.util.Map<String, java.util.List<File>> idMap = new java.util.HashMap<>();
        collectFolderIdMapRecursive(modelDir, idMap, 0);
        return idMap;
    }
    
    /**
     * @param depth 0=model root, 1=typed folders (business, technology, etc.), 2+=user folders
     */
    private void collectFolderIdMapRecursive(File dir, java.util.Map<String, java.util.List<File>> idMap, int depth) {
        File folderXml = new File(dir, IGraficoConstants.FOLDER_XML);
        
        if(folderXml.exists()) {
            try {
                byte[] content = Files.readAllBytes(folderXml.toPath());
                String id = GraficoUtils.extractIdFromFolderXml(content);
                if(id != null) {
                    idMap.computeIfAbsent(id, k -> new java.util.ArrayList<>()).add(dir);
                }
            } catch(IOException e) {
                // Skip unreadable files
            }
        }
        
        File[] subdirs = dir.listFiles(File::isDirectory);
        if(subdirs != null) {
            for(File subdir : subdirs) {
                collectFolderIdMapRecursive(subdir, idMap, depth + 1);
            }
        }
    }
    
    /**
     * Detect duplicate folder IDs in the model tree (B6 scenario: both branches
     * moved the same folder to different locations). Returns a list of FolderMoveInfo
     * for each duplicate pair.
     * 
     * <p>When both branches move a folder, git auto-merges both copies cleanly.
     * The result is two directories with valid folder.xml containing the same ID.
     * On next export, both map to the same directory (getNameFor returns folder ID),
     * causing silent data corruption.</p>
     */
    private void detectDuplicateFolderIds(File modelDir, java.util.Map<String, java.util.List<File>> idMap) {
        for(java.util.Map.Entry<String, java.util.List<File>> entry : idMap.entrySet()) {
            java.util.List<File> dirs = entry.getValue();
            if(dirs.size() < 2) {
                continue;
            }
            
            String folderId = entry.getKey();
            
            // Pick the first two as the duplicate pair
            // (more than 2 duplicates is theoretically possible but extremely unlikely)
            File dir1 = dirs.get(0);
            File dir2 = dirs.get(1);
            
            // Read folder names from folder.xml
            String name1 = null;
            String name2 = null;
            byte[] content1 = null;
            try {
                content1 = Files.readAllBytes(new File(dir1, IGraficoConstants.FOLDER_XML).toPath());
                name1 = GraficoUtils.extractNameFromFolderXml(content1);
            } catch(IOException e) { /* use dir name */ }
            try {
                byte[] content2 = Files.readAllBytes(new File(dir2, IGraficoConstants.FOLDER_XML).toPath());
                name2 = GraficoUtils.extractNameFromFolderXml(content2);
            } catch(IOException e) { /* use dir name */ }
            
            String folderName = name1 != null ? name1 : (name2 != null ? name2 : folderId);
            
            // Classify elements between the two directories
            java.util.List<String> duplicates = new java.util.ArrayList<>();
            java.util.List<String> unique = new java.util.ArrayList<>();
            classifyElements(dir1, dir2, duplicates, unique);
            
            String relPath1 = fRepository.getLocalRepositoryFolder().toPath()
                    .relativize(dir1.toPath()).toString().replace('\\', '/');
            String relPath2 = fRepository.getLocalRepositoryFolder().toPath()
                    .relativize(dir2.toPath()).toString().replace('\\', '/');
            
            FolderMoveInfo moveInfo = new FolderMoveInfo(
                    folderName, folderId, dir1, dir2,
                    relPath1, relPath2,
                    duplicates, unique, content1);
            fFolderMoves.add(moveInfo);
            
            log(IStatus.INFO, "[GraficoModelLoader] Duplicate folder ID detected: " + folderId //$NON-NLS-1$
                    + " at " + relPath1 + " and " + relPath2 //$NON-NLS-1$ //$NON-NLS-2$
                    + ". " + duplicates.size() + " duplicates, " + unique.size() + " unique."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }
    
    /**
     * Load folder.xml content from git commit history at the exact path.
     * 
     * @return The file content as bytes, or null if not found in history
     */
    private byte[] loadFolderXmlFromHistory(Repository repository, String relativePath) throws IOException {
        try(RevWalk revWalk = new RevWalk(repository)) {
            ObjectId headId = repository.resolve(IGraficoConstants.HEAD);
            if(headId == null) {
                return null;
            }
            revWalk.markStart(revWalk.parseCommit(headId));
            
            for(RevCommit commit : revWalk) {
                try(TreeWalk treeWalk = TreeWalk.forPath(repository, relativePath, commit.getTree())) {
                    if(treeWalk != null) {
                        ObjectId objectId = treeWalk.getObjectId(0);
                        ObjectLoader loader = repository.open(objectId);
                        return loader.getBytes();
                    }
                }
            }
            
            revWalk.dispose();
        }
        return null;
    }
    
    /**
     * Create a "[MERGE FIX]" folder.xml with a new UUID.
     * This is used when a folder was moved (so the original ID exists elsewhere)
     * or when the folder.xml was never found in history.
     * The "[MERGE FIX]" prefix signals to the user that manual resolution is needed.
     * 
     * @param dir The directory to create the folder.xml in
     * @param originalName The original folder name (will be prefixed with "[MERGE FIX] ")
     * @throws IOException if the file cannot be written
     */
    void createMergeFixFolderXml(File dir, String originalName) throws IOException {
        String newId = "id-" + java.util.UUID.randomUUID().toString().replace("-", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String displayName = "[MERGE FIX] " + originalName; //$NON-NLS-1$
        File folderXml = new File(dir, IGraficoConstants.FOLDER_XML);
        String xml = "<archimate:Folder\n" //$NON-NLS-1$
                + "    xmlns:archimate=\"http://www.archimatetool.com/archimate\"\n" //$NON-NLS-1$
                + "    name=\"" + GraficoUtils.escapeXml(displayName) + "\"\n" //$NON-NLS-1$ //$NON-NLS-2$
                + "    id=\"" + GraficoUtils.escapeXml(newId) + "\"/>\n"; //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(folderXml.toPath(), xml);
    }

    /**
     * Remove element XML files from sourceDir that also exist (by filename) in destDir.
     * Elements are identified by filename which contains the element type and ID,
     * e.g. "BusinessActor_id-abc.xml". If the same filename exists in both directories,
     * the element was moved along with the folder and the old-location copy is a duplicate.
     * 
     * @param sourceDir The directory to remove duplicates FROM (old location / [MERGE FIX])
     * @param destDir The directory to check for existing elements (move destination)
     * @return The number of unique element files remaining in sourceDir after deduplication
     */
    int removeDuplicateElements(File sourceDir, File destDir) {
        File[] sourceXmls = sourceDir.listFiles((d, name) ->
                name.endsWith(".xml") && !IGraficoConstants.FOLDER_XML.equals(name)); //$NON-NLS-1$
        if(sourceXmls == null || sourceXmls.length == 0) {
            return 0;
        }

        // Collect element filenames at the destination
        java.util.Set<String> destElementNames = new java.util.HashSet<>();
        File[] destXmls = destDir.listFiles((d, name) ->
                name.endsWith(".xml") && !IGraficoConstants.FOLDER_XML.equals(name)); //$NON-NLS-1$
        if(destXmls != null) {
            for(File f : destXmls) {
                destElementNames.add(f.getName());
            }
        }

        int remaining = 0;
        for(File sourceFile : sourceXmls) {
            if(destElementNames.contains(sourceFile.getName())) {
                // Duplicate: same element exists at destination — remove from source
                sourceFile.delete();
            } else {
                remaining++;
            }
        }
        return remaining;
    }

    /**
     * Check if a directory has any subdirectories.
     */
    private boolean hasSubdirectories(File dir) {
        File[] subdirs = dir.listFiles(File::isDirectory);
        return subdirs != null && subdirs.length > 0;
    }

    @SuppressWarnings("unused")  // Keep for potential future use
    private void deleteProblemObjects(List<UnresolvedObject> unresolvedObjects, IArchimateModel model) throws IOException {
        for(UnresolvedObject unresolved : unresolvedObjects) {
            String parentID = unresolved.parentObject.getId();
            
            EObject eObject = ArchimateModelUtils.getObjectByID(model, parentID);
            if(eObject != null) {
                EcoreUtil.remove(eObject);
            }
        }
        
        // And re-export to grafico xml files
        GraficoModelExporter exporter = new GraficoModelExporter(model, fRepository.getLocalRepositoryFolder());
        exporter.exportModel();
    }

    /**
     * @param model
     * @return All open diagram models' ids so we can restore them
     */
    private List<String> getOpenDiagramModelIdentifiers(IArchimateModel model) {
        List<String> list = new ArrayList<String>();
        
        for(IEditorReference ref : PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getEditorReferences()) {
            try {
                IEditorInput input = ref.getEditorInput();
                if(input instanceof DiagramEditorInput) {
                    IDiagramModel dm = ((DiagramEditorInput)input).getDiagramModel();
                    if(dm.getArchimateModel() == model) {
                        list.add(dm.getId());
                    }
                }
            }
            catch(PartInitException ex) {
                ex.printStackTrace();
            }
        }
        
        return list;
    }
    
    /**
     * Re-open any diagram editors
     * @param model
     * @param ids
     */
    private void reopenEditors(IArchimateModel model, List<String> ids) {
        if(ids != null) {
            for(String id : ids) {
                EObject eObject = ArchimateModelUtils.getObjectByID(model, id);
                if(eObject instanceof IDiagramModel) {
                    EditorManager.openDiagramEditor((IDiagramModel)eObject);
                }
            }
        }
    }

}
