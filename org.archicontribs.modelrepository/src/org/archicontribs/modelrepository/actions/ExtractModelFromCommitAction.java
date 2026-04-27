/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.actions;

import java.io.IOException;

import org.archicontribs.modelrepository.grafico.GraficoModelImporter;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.ui.IWorkbenchWindow;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.editor.ui.IArchiImages;
import com.archimatetool.model.IArchimateModel;

/**
 * Checkout a commit and extract the .archimate file from it
 */
public class ExtractModelFromCommitAction extends AbstractModelAction {
    
    private RevCommit fCommit;
	
    public ExtractModelFromCommitAction(IWorkbenchWindow window) {
        super(window);
        setImageDescriptor(IArchiImages.ImageFactory.getImageDescriptor(IArchiImages.ICON_MODELS));
        setText(Messages.ExtractModelFromCommitAction_0);
        setToolTipText(Messages.ExtractModelFromCommitAction_0);
    }

    /**
     * Store the commit. Caller manages enabled state.
     */
    public void setCommit(RevCommit commit) {
        fCommit = commit;
    }
    
    @Override
    public void run() {
        boolean confirm = MessageDialog.openConfirm(fWindow.getShell(), Messages.ExtractModelFromCommitAction_1, Messages.ExtractModelFromCommitAction_3);
        
        if(!confirm) {
            return;
        }
        
        // Load model directly from git commit objects (no temp folder I/O)
        try(Repository repository = Git.open(getRepository().getLocalRepositoryFolder()).getRepository()) {
            GraficoModelImporter importer = new GraficoModelImporter(repository, fCommit.getTree());
            IArchimateModel graficoModel = importer.importFromCommit(null);
            
            if(graficoModel != null) {
                // Open it, this will do the necessary checks and add a command stack and an archive manager
                IEditorModelManager.INSTANCE.openModel(graficoModel);
                
                // Set model name
                graficoModel.setName(graficoModel.getName() + " (" + fCommit.getName().substring(0, 8) + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else {
                MessageDialog.openError(fWindow.getShell(), Messages.ExtractModelFromCommitAction_1, Messages.ExtractModelFromCommitAction_2);
            }
        }
        catch(IOException ex) {
            displayErrorDialog(Messages.ExtractModelFromCommitAction_1, ex);
        }
    }
    
    @Override
    protected boolean shouldBeEnabled() {
        return fCommit != null && getRepository() != null;
    }
}
