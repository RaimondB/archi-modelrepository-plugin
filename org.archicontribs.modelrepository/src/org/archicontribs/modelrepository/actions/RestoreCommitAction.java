/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.actions;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

import org.archicontribs.modelrepository.IModelRepositoryImages;
import org.archicontribs.modelrepository.grafico.GraficoModelImporter;
import org.archicontribs.modelrepository.grafico.GraficoModelLoader;
import org.archicontribs.modelrepository.grafico.IGraficoConstants;
import org.archicontribs.modelrepository.grafico.IRepositoryListener;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchWindow;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.editor.utils.FileUtils;
import com.archimatetool.model.IArchimateModel;

/**
 * Restore to a particular commit
 */
public class RestoreCommitAction extends AbstractModelAction {
    
    private RevCommit fCommit;
	
    public RestoreCommitAction(IWorkbenchWindow window) {
        super(window);
        setImageDescriptor(IModelRepositoryImages.ImageFactory.getImageDescriptor(IModelRepositoryImages.ICON_SYNCED));
        setText(Messages.RestoreCommitAction_0);
        setToolTipText(Messages.RestoreCommitAction_0);
    }

    /**
     * Store the commit. Caller manages enabled state.
     */
    public void setCommit(RevCommit commit) {
        fCommit = commit;
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
        
        boolean response = MessageDialog.openConfirm(fWindow.getShell(),
                Messages.RestoreCommitAction_0,
                Messages.RestoreCommitAction_1);

        if(!response) {
            return;
        }
        
        String commitSha = fCommit.getName();
        File repoFolder = getRepository().getLocalRepositoryFolder();

        final Throwable[] failure = new Throwable[1];
        final boolean[] noModelFound = new boolean[1];
        final boolean[] success = new boolean[1];

        ProgressMonitorDialog pmDialog = new ProgressMonitorDialog(null);
        try {
            pmDialog.run(true, true, new IRunnableWithProgress() {
                @Override
                public void run(org.eclipse.core.runtime.IProgressMonitor monitor)
                        throws InvocationTargetException, InterruptedException {
                    SubMonitor progress = SubMonitor.convert(monitor, Messages.RestoreCommitAction_5, 100);

                    try {
                        progress.subTask(Messages.RestoreCommitAction_6);
                        File modelFolder = new File(repoFolder, IGraficoConstants.MODEL_FOLDER);
                        FileUtils.deleteFolder(modelFolder);
                        modelFolder.mkdirs();

                        File imagesFolder = new File(repoFolder, IGraficoConstants.IMAGES_FOLDER);
                        FileUtils.deleteFolder(imagesFolder);
                        imagesFolder.mkdirs();
                        progress.worked(10);

                        if(progress.isCanceled()) {
                            throw new InterruptedException();
                        }

                        progress.subTask(Messages.RestoreCommitAction_7);
                        getRepository().checkoutPathsFromCommit(commitSha,
                                IGraficoConstants.MODEL_FOLDER, IGraficoConstants.IMAGES_FOLDER);
                        progress.worked(10);

                        if(progress.isCanceled()) {
                            throw new InterruptedException();
                        }

                        progress.subTask(Messages.RestoreCommitAction_8);
                        try(Repository repository = Git.open(repoFolder).getRepository()) {
                            GraficoModelImporter importer = new GraficoModelImporter(repository, fCommit.getTree());
                            IArchimateModel graficoModel = importer.importFromCommit(progress.split(60));

                            if(graficoModel == null) {
                                getRepository().resetToRef(IGraficoConstants.HEAD);
                                noModelFound[0] = true;
                                return;
                            }

                            progress.subTask(Messages.RestoreCommitAction_9);
                            IOException[] openError = new IOException[1];
                            Display.getDefault().syncExec(() -> {
                                try {
                                    new GraficoModelLoader(getRepository()).openModel(graficoModel, importer);
                                }
                                catch(IOException ex) {
                                    openError[0] = ex;
                                }
                            });

                            if(openError[0] != null) {
                                throw openError[0];
                            }
                        }

                        if(progress.isCanceled()) {
                            throw new InterruptedException();
                        }

                        progress.subTask(Messages.RestoreCommitAction_10);
                        getRepository().commitChanges(Messages.RestoreCommitAction_3 + " '" + fCommit.getShortMessage() + "'", false); //$NON-NLS-1$ //$NON-NLS-2$
                        getRepository().saveChecksum();
                        progress.worked(20);

                        success[0] = true;
                    }
                    catch(InterruptedException ex) {
                        throw ex;
                    }
                    catch(Exception ex) {
                        throw new InvocationTargetException(ex);
                    }
                }
            });
        }
        catch(InvocationTargetException ex) {
            failure[0] = ex.getCause() != null ? ex.getCause() : ex;
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            return;
        }

        if(failure[0] != null) {
            displayErrorDialog(Messages.RestoreCommitAction_0, failure[0]);
            return;
        }

        if(noModelFound[0]) {
            MessageDialog.openError(fWindow.getShell(), Messages.RestoreCommitAction_0, Messages.RestoreCommitAction_2);
            return;
        }

        if(success[0]) {
            notifyChangeListeners(IRepositoryListener.HISTORY_CHANGED);
        }
    }
    
    
    @Override
    protected boolean shouldBeEnabled() {
        if(getRepository() == null) {
            return false;
        }
        
        boolean isHead = false;
        try {
            isHead = isCommitLocalHead();
        }
        catch(IOException ex) {
            ex.printStackTrace();
        }
        
        return fCommit != null && !isHead;
    }
    
    protected boolean isCommitLocalHead() throws IOException {
        if(fCommit == null) {
            return false;
        }
        
        try(Repository repo = Git.open(getRepository().getLocalRepositoryFolder()).getRepository()) {
            ObjectId headID = repo.resolve(IGraficoConstants.HEAD);
            ObjectId commitID = fCommit.getId();
            return commitID.equals(headID);
        }
    }
}
