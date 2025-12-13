/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.actions;

import java.io.IOException;

import org.archicontribs.modelrepository.IModelRepositoryImages;
import org.archicontribs.modelrepository.grafico.ArchiRepository;
import org.archicontribs.modelrepository.grafico.GraficoUtils;
import org.archicontribs.modelrepository.grafico.IRepositoryListener;
import org.archicontribs.modelrepository.review.ChangeReviewHandler;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateModel;

/**
 * Review Changes Action
 * 
 * 1. Offer to save the model if dirty
 * 2. Create Grafico files from the model
 * 3. Check if there are any changes
 * 4. Show Review Changes dialog
 * 5. Apply reverts if user selected any
 * 
 * @author Raimond Brookman
 */
public class ReviewChangesAction extends AbstractModelAction {
    
    private ChangeReviewHandler fHandler;
    
    public ReviewChangesAction(IWorkbenchWindow window) {
        super(window);
        setImageDescriptor(IModelRepositoryImages.ImageFactory.getImageDescriptor(IModelRepositoryImages.ICON_COMMIT));
        setText(Messages.ReviewChangesAction_0);
        setToolTipText(Messages.ReviewChangesAction_1);
    }

    public ReviewChangesAction(IWorkbenchWindow window, IArchimateModel model) {
        this(window);
        if(model != null) {
            setRepository(new ArchiRepository(GraficoUtils.getLocalRepositoryFolderForModel(model)));
        }
    }

    @Override
    public void run() {
        // Offer to save the model if open and dirty
        IArchimateModel model = getRepository().locateModel();
        if(model != null && IEditorModelManager.INSTANCE.isModelDirty(model)) {
            if(!offerToSaveModel(model)) {
                return;
            }
        }

        // Do the Grafico Export first
        try {
            getRepository().exportModelToGraficoFiles();
        }
        catch(Exception ex) {
            displayErrorDialog(Messages.ReviewChangesAction_0, ex);
            return;
        }
        
        // Check for changes and show review dialog
        try {
            if(getRepository().hasChangesToCommit()) {
                // Initialize handler and show dialog
                Exception[] exception = new Exception[1];
                
                PlatformUI.getWorkbench().getProgressService().busyCursorWhile(new IRunnableWithProgress() {
                    @Override
                    public void run(IProgressMonitor monitor) {
                        try {
                            monitor.beginTask(Messages.ReviewChangesAction_2, IProgressMonitor.UNKNOWN);
                            
                            fHandler = new ChangeReviewHandler(getRepository(), fWindow.getShell());
                            fHandler.init(monitor);
                            
                            monitor.done();
                        }
                        catch(Exception ex) {
                            exception[0] = ex;
                        }
                    }
                });
                
                if(exception[0] != null) {
                    throw exception[0];
                }
                
                // Show the review dialog
                if(fHandler.openReviewDialog()) {
                    // User clicked OK - apply any reverts
                    if(fHandler.hasReverts()) {
                        applyReverts();
                    }
                }
            }
            else {
                MessageDialog.openInformation(fWindow.getShell(),
                        Messages.ReviewChangesAction_0,
                        Messages.ReviewChangesAction_3);
            }
        }
        catch(Exception ex) {
            displayErrorDialog(Messages.ReviewChangesAction_0, ex);
        }
    }
    
    /**
     * Apply the selected reverts
     */
    private void applyReverts() {
        try {
            Exception[] exception = new Exception[1];
            
            // Load HEAD model with progress (this can run in background - no UI updates)
            PlatformUI.getWorkbench().getProgressService().busyCursorWhile(new IRunnableWithProgress() {
                @Override
                public void run(IProgressMonitor monitor) {
                    try {
                        monitor.beginTask(Messages.ReviewChangesAction_5, IProgressMonitor.UNKNOWN);
                        
                        // Load HEAD model (lazy - only loaded now when we need it)
                        fHandler.ensureHeadModelLoaded(monitor);
                        
                        monitor.done();
                    }
                    catch(Exception ex) {
                        exception[0] = ex;
                    }
                }
            });
            
            if(exception[0] != null) {
                throw new IOException(exception[0]);
            }
            
            // Apply reverts on UI thread - EMF modifications trigger UI updates
            // so they MUST run on the UI thread to avoid "Invalid thread access"
            fHandler.applyReverts();
            
            // Notify that model changed
            notifyChangeListeners(IRepositoryListener.REPOSITORY_CHANGED);
            
            // Mark model as dirty so user can save
            IArchimateModel model = getRepository().locateModel();
            if(model != null) {
                // The model is now modified but not saved
                // User will need to save it
                MessageDialog.openInformation(fWindow.getShell(),
                        Messages.ReviewChangesAction_0,
                        Messages.ReviewChangesAction_4);
            }
        }
        catch(Exception ex) {
            displayErrorDialog(Messages.ReviewChangesAction_0, ex);
        }
    }
}
