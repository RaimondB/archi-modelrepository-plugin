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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.archicontribs.modelrepository.grafico.GraficoModelImporter.UnresolvedObject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jgit.api.Git;
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
    
    public GraficoModelLoader(IArchiRepository repository) {
        fRepository = repository;
        bHeadless = false;
    }

    public GraficoModelLoader(IArchiRepository repository, boolean headless ) {
        fRepository = repository;
        bHeadless = headless;
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
        	    System.out.println("[GraficoModelLoader] Repaired " + repaired + " mismatched connection endpoint(s)"); //$NON-NLS-1$ //$NON-NLS-2$
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
        
        fRestoredObjects = null;
        
        // Set file name on the grafico model so we can locate it
        graficoModel.setFile(fRepository.getTempModelFile());
        
        // Resolve missing objects if any
        List<UnresolvedObject> unresolvedObjects = importer != null ? importer.getUnresolvedObjects() : null;
        if(unresolvedObjects != null) {
            graficoModel = restoreProblemObjects(unresolvedObjects);
        }
        
        // Save the model
        IEditorModelManager.INSTANCE.saveModel(graficoModel);
        
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
                IEditorModelManager.INSTANCE.closeModel(model);
                IEditorModelManager.INSTANCE.openModel(graficoModel);
                reopenEditors(graficoModel, openModelIDs);
            } finally {
                // Restore shell state if it changed during close/open
                if(wasMaximized && !shell.getMaximized()) {
                    shell.setMaximized(true);
                } else if(!wasMaximized) {
                    shell.setBounds(savedBounds);
                }
                shell.setRedraw(true);
            }
        }
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
    private int repairConnectionEndpoints(IArchimateModel model) {
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
                System.out.println("[GraficoModelLoader] Rewiring connection " + connId //$NON-NLS-1$
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
