/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.actions;

import org.archicontribs.modelrepository.IModelRepositoryImages;
import org.archicontribs.modelrepository.dialogs.CommitDialog;
import org.archicontribs.modelrepository.grafico.ArchiRepository;
import org.archicontribs.modelrepository.grafico.GraficoUtils;
import org.archicontribs.modelrepository.grafico.IGraficoConstants;
import org.archicontribs.modelrepository.grafico.IRepositoryListener;
import org.archicontribs.modelrepository.services.RepositoryService;
import org.archicontribs.modelrepository.services.RepositoryService.CommitResult;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.IWorkbenchWindow;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateModel;

/**
 * Commit Model Action
 * 
 * UI wrapper that delegates to {@link RepositoryService#commit}.
 * 
 * 1. Offer to save the model
 * 2. Show Commit dialog to get message
 * 3. Delegate to RepositoryService.commit() (export + stage + commit)
 * 
 * @author Jean-Baptiste Sarrodie
 * @author Phillip Beauvoir
 */
public class CommitModelAction extends AbstractModelAction {
    
    private final RepositoryService repositoryService = new RepositoryService();
    
    public CommitModelAction(IWorkbenchWindow window) {
        super(window);
        setImageDescriptor(IModelRepositoryImages.ImageFactory.getImageDescriptor(IModelRepositoryImages.ICON_COMMIT));
        setText(Messages.CommitModelAction_0);
        setToolTipText(Messages.CommitModelAction_0);
    }

    public CommitModelAction(IWorkbenchWindow window, IArchimateModel model) {
        this(window);
        if(model != null) {
            setRepository(new ArchiRepository(GraficoUtils.getLocalRepositoryFolderForModel(model)));
        }
    }

    @Override
    public void run() {
        // Offer to save the model if open and dirty
        // We need to do this to keep grafico and temp files in sync
        IArchimateModel model = getRepository().locateModel();
        if(model != null && IEditorModelManager.INSTANCE.isModelDirty(model)) {
            if(!offerToSaveModel(model)) {
                return;
            }
        }

        // Export to GRAFICO and stage changes (so the dialog can show a change summary)
        try {
            getRepository().exportModelToGraficoFiles();
        }
        catch(Exception ex) {
            displayErrorDialog(Messages.CommitModelAction_0, ex);
            return;
        }
        
        try {
            if(getRepository().hasChangesToCommit()) {
                // Show commit dialog to get message and amend flag
                CommitDialog commitDialog = new CommitDialog(fWindow.getShell(), getRepository());
                if(commitDialog.open() == Window.OK) {
                    String commitMessage = commitDialog.getCommitMessage();
                    boolean amend = commitDialog.getAmend();
                    
                    // Delegate commit to service (handles staging + commit + checksum)
                    CommitResult result = repositoryService.commitChanges(
                            getRepository(), commitMessage, amend);
                    
                    if(result.status() == CommitResult.Status.COMMITTED) {
                        notifyChangeListeners(IRepositoryListener.HISTORY_CHANGED);
                    }
                }
                else {
                    // User cancelled commit dialog - reset staged changes
                    getRepository().resetToRef(IGraficoConstants.HEAD);
                }
            }
            else {
                MessageDialog.openInformation(fWindow.getShell(),
                        Messages.CommitModelAction_0,
                        Messages.CommitModelAction_2);
            }
        }
        catch(Exception ex) {
            displayErrorDialog(Messages.CommitModelAction_0, ex);
        }
    }
}
