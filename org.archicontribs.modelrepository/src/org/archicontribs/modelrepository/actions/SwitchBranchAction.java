/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.actions;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

import org.archicontribs.modelrepository.IModelRepositoryImages;
import org.archicontribs.modelrepository.grafico.BranchInfo;
import org.archicontribs.modelrepository.grafico.GraficoModelLoader;
import org.archicontribs.modelrepository.grafico.IGraficoConstants;
import org.archicontribs.modelrepository.grafico.IRepositoryListener;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.swt.SWT;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateModel;

/**
 * Switch and checkout Branch
 */
public class SwitchBranchAction extends AbstractModelAction {
    
    private BranchInfo fBranchInfo;
    
    public SwitchBranchAction(IWorkbenchWindow window) {
        super(window);
        setImageDescriptor(IModelRepositoryImages.ImageFactory.getImageDescriptor(IModelRepositoryImages.ICON_BRANCHES));
        setText(Messages.SwitchBranchAction_0);
        setToolTipText(Messages.SwitchBranchAction_0);
    }

    @Override
    public void run() {
        if(!shouldBeEnabled()) {
            return;
        }

        // Keep a local reference in case of a notification event changing the current branch selection in the UI
        BranchInfo branchInfo = fBranchInfo;
        
        // Offer to save the model if open and dirty
        // We need to do this to keep grafico and temp files in sync
        IArchimateModel model = getRepository().locateModel();
        if(model != null && IEditorModelManager.INSTANCE.isModelDirty(model)) {
            int response = MessageDialog.open(MessageDialog.CONFIRM,
                    fWindow.getShell(),
                    Messages.AbstractModelAction_1,
                    Messages.AbstractModelAction_2,
                    SWT.NONE,
                    Messages.SwitchBranchAction_2, Messages.SwitchBranchAction_3, Messages.SwitchBranchAction_4);
            
            // Cancel
            if(response == 2) {
                return;
            }
            
            // Save anyway
            try {
                IEditorModelManager.INSTANCE.saveModel(model);
            }
            catch(IOException ex) {
                displayErrorDialog(Messages.AbstractModelAction_1, ex);
                return;
            }

            // If "no" to save then don't commit
            if(response == 1) {
                try {
                    // Abort changes by resetting to HEAD
                    getRepository().resetToRef(IGraficoConstants.HEAD);
                    
                    // Switch branch
                    switchBranch(branchInfo, !isBranchRefSameAsCurrentBranchRef(branchInfo));
                    notifyChangeListeners(IRepositoryListener.BRANCHES_CHANGED);
                    
                    return;
                }
                catch(IOException | GitAPIException ex) {
                    displayErrorDialog(Messages.SwitchBranchAction_0, ex);
                }
            }
        }
        
        boolean notifyHistoryChanged = false;
        
        try {
            // Do the Grafico Export first
            getRepository().exportModelToGraficoFiles();
            
            // If there are changes to commit...
            if(getRepository().hasChangesToCommit()) {
                // Ask user
                boolean doCommit = MessageDialog.openQuestion(fWindow.getShell(),
                        Messages.SwitchBranchAction_0,
                        Messages.SwitchBranchAction_1);

                // Commit dialog
                if(doCommit && !offerToCommitChanges()) {
                    return;
                }

                // User chose "no" to commit so let's make sure we proceed
                boolean proceed = MessageDialog.openQuestion(fWindow.getShell(),
                        Messages.SwitchBranchAction_0,
                        Messages.SwitchBranchAction_5);
                
                if(!proceed) {
                    return;
                }
                
                // Abort changes by resetting to HEAD
                getRepository().resetToRef(IGraficoConstants.HEAD);
                
                notifyHistoryChanged = true;
            }
            
            // Switch branch
            switchBranch(branchInfo, !isBranchRefSameAsCurrentBranchRef(branchInfo));
        }
        catch(Exception ex) {
            displayErrorDialog(Messages.SwitchBranchAction_0, ex);
        }

        // Notify listeners last because a new UI selection will trigger an updated BranchInfo here
        if(notifyHistoryChanged) {
            notifyChangeListeners(IRepositoryListener.HISTORY_CHANGED);
        }
        notifyChangeListeners(IRepositoryListener.BRANCHES_CHANGED);
    }
    
    protected void switchBranch(BranchInfo branchInfo, boolean doReloadGrafico) throws IOException, GitAPIException {
        // First, perform Git operations with progress feedback
        // (checkout can take a long time if many files differ between branches)
        performGitCheckout(branchInfo);
        
        // Then reload the model - GraficoModelLoader has its own progress dialog
        if(doReloadGrafico) {
            new GraficoModelLoader(getRepository()).loadModel();
            
            // Save the checksum
            getRepository().saveChecksum();
        }
    }
    
    /**
     * Perform the Git checkout operation with progress feedback
     */
    private void performGitCheckout(BranchInfo branchInfo) throws IOException, GitAPIException {
        Exception[] exception = new Exception[1];
        
        try {
            PlatformUI.getWorkbench().getProgressService().busyCursorWhile(new IRunnableWithProgress() {
                @Override
                public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
                    monitor.beginTask(Messages.SwitchBranchAction_6, IProgressMonitor.UNKNOWN);
                    
                    try(Git git = Git.open(getRepository().getLocalRepositoryFolder())) {
                        // If the branch is local just checkout
                        if(branchInfo.isLocal()) {
                            monitor.subTask(Messages.SwitchBranchAction_7);
                            git.checkout().setName(branchInfo.getFullName()).call();
                        }
                        // If the branch is remote and has no local ref we need to create the local branch and switch to that
                        else if(branchInfo.isRemote() && !branchInfo.hasLocalRef()) {
                            String branchName = branchInfo.getShortName();
                            
                            // Create local branch at point of remote branch ref
                            monitor.subTask(Messages.SwitchBranchAction_8);
                            Ref ref = git.branchCreate()
                                    .setName(branchName)
                                    .setStartPoint(branchInfo.getFullName())
                                    .call();
                            
                            // checkout
                            monitor.subTask(Messages.SwitchBranchAction_7);
                            git.checkout().setName(ref.getName()).call();
                        }
                    }
                    catch(IOException | GitAPIException ex) {
                        exception[0] = ex;
                    }
                    finally {
                        monitor.done();
                    }
                }
            });
        }
        catch(InvocationTargetException | InterruptedException ex) {
            throw new IOException(ex);
        }
        
        // Re-throw any exception from the progress runnable
        if(exception[0] != null) {
            if(exception[0] instanceof IOException) {
                throw (IOException)exception[0];
            }
            if(exception[0] instanceof GitAPIException) {
                throw (GitAPIException)exception[0];
            }
            throw new IOException(exception[0]);
        }
    }
    
    private boolean isBranchRefSameAsCurrentBranchRef(BranchInfo branchInfo) {
        try {
            BranchInfo currentLocalBranch = getRepository().getBranchStatus().getCurrentLocalBranch();
            return currentLocalBranch != null && currentLocalBranch.getRef().getObjectId().equals(branchInfo.getRef().getObjectId());
        }
        catch(IOException | GitAPIException ex) {
            ex.printStackTrace();
        }
        
        return false;
    }
    
    public void setBranch(BranchInfo branchInfo) {
        fBranchInfo = branchInfo;
        setEnabled(shouldBeEnabled());
    }
    
    @Override
    protected boolean shouldBeEnabled() {
        return fBranchInfo != null && !fBranchInfo.isCurrentBranch() && super.shouldBeEnabled();
    }
}
