/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.actions;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.security.GeneralSecurityException;
import java.util.concurrent.CancellationException;

import org.archicontribs.modelrepository.IModelRepositoryImages;
import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.archicontribs.modelrepository.authentication.CredentialsAuthenticator;
import org.archicontribs.modelrepository.authentication.ProxyAuthenticator;
import org.archicontribs.modelrepository.authentication.UsernamePassword;
import org.archicontribs.modelrepository.grafico.BranchInfo;
import org.archicontribs.modelrepository.grafico.IGraficoConstants;
import org.archicontribs.modelrepository.grafico.ProgressMonitorWrapper;
import org.archicontribs.modelrepository.services.MergeHandler;
import org.archicontribs.modelrepository.services.RepositoryService;
import org.archicontribs.modelrepository.services.RepositoryService.RefreshResult;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchWindow;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateModel;

/**
 * Merge a Branch
 */
public class MergeBranchAction extends AbstractModelAction {
    
    protected static final int MERGE_STATUS_ERROR = -1;
    protected static final int MERGE_STATUS_OK = 0;
    protected static final int MERGE_STATUS_UP_TO_DATE = 1;
    protected static final int MERGE_STATUS_MERGE_CANCEL = 2;

    private BranchInfo fBranchInfo;
	
    public MergeBranchAction(IWorkbenchWindow window) {
        super(window);
        setImageDescriptor(IModelRepositoryImages.ImageFactory.getImageDescriptor(IModelRepositoryImages.ICON_MERGE));
        setText(Messages.MergeBranchAction_0);
        setToolTipText(Messages.MergeBranchAction_1);
    }

    @Override
    public void run() {
        if(!shouldBeEnabled()) {
            return;
        }
        
        int response = MessageDialog.open(MessageDialog.QUESTION,
                fWindow.getShell(),
                Messages.MergeBranchAction_1,
                Messages.MergeBranchAction_5,
                SWT.NONE,
                Messages.MergeBranchAction_6,
                Messages.MergeBranchAction_7,
                Messages.MergeBranchAction_8);
        
        // Cancel
        if(response == -1 || response == 2) {
            return;
        }

        try {
            if(response == 0) {
                doOnlineMerge(fBranchInfo);
            }
            
            if(response == 1) {
                doLocalMerge(fBranchInfo);
            }
        }
        catch(CancellationException ex) {
            // User cancelled the credentials dialog
        }
        catch(GeneralSecurityException ex) {
            displayCredentialsErrorDialog(ex);
        }
        catch(Exception ex) {
            displayErrorDialog(Messages.MergeBranchAction_1, ex);
        }
    }
    
    private void doLocalMerge(BranchInfo branchToMerge) throws IOException, GitAPIException {
        // Offer to save the model if open and dirty
        // We need to do this to keep grafico and temp files in sync
        IArchimateModel model = getRepository().locateModel();
        if(model != null && IEditorModelManager.INSTANCE.isModelDirty(model)) {
            if(!offerToSaveModel(model)) {
                return;
            }
        }
        
        // Do the Grafico Export first
        getRepository().exportModelToGraficoFiles();

        // Then offer to Commit
        if(getRepository().hasChangesToCommit()) {
            if(!offerToCommitChanges()) {
                // User cancelled commit dialog - reset staged changes
                getRepository().resetToRef(IGraficoConstants.HEAD);
                return;
            }
        }

        // Do main action with PM dialog — run on background thread (true)
        ProgressMonitorDialog pmDialog = new ProgressMonitorDialog(fWindow.getShell());
        try {
            pmDialog.run(true, true, new IRunnableWithProgress() {
                @Override
                public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
                    try {
                        monitor.beginTask(Messages.MergeBranchAction_11, -1);
                        
                        // Store currentBranch first
                        BranchInfo currentBranch = getRepository().getBranchStatus().getCurrentLocalBranch();
                        merge(currentBranch, branchToMerge, monitor);
                    }
                    catch(Exception ex) {
                        Display.getDefault().syncExec(() -> {
                            displayErrorDialog(Messages.MergeBranchAction_1, ex);
                        });
                    }
                    finally {
                        Display.getDefault().syncExec(() -> {
                            try {
                                saveChecksumAndNotifyListeners();
                            }
                            catch(IOException ex) {
                                ex.printStackTrace();
                            }
                        });
                    }
                }
            });
        }
        catch(InvocationTargetException | InterruptedException ex) {
            ex.printStackTrace();
        }
    }
    
    private void doOnlineMerge(BranchInfo branchToMerge) throws IOException, GitAPIException, GeneralSecurityException {
        // Store currentBranch first
        BranchInfo currentBranch = getRepository().getBranchStatus().getCurrentLocalBranch();
        
        PushModelAction pushAction = new PushModelAction(fWindow, getRepository().locateModel());

        // Init
        int status = pushAction.init();
        if(status == RefreshModelAction.USER_CANCEL) {
            return;
        }
        
        // Check primary key set (only needed for PAT auth, not GCM)
        if(!CredentialsAuthenticator.checkPrimaryKeyIfNeeded()) {
            return;
        }
        
        // Get credentials before opening the progress dialog
        UsernamePassword npw = getUsernamePassword();
        
        // Service and merge handler for pull/push operations
        RepositoryService repositoryService = new RepositoryService();
        MergeHandler mergeHandler = new InteractiveMergeHandler(fWindow.getShell());
        
        // Do main action with PM dialog — run on background thread (true)
        ProgressMonitorDialog pmDialog = new ProgressMonitorDialog(fWindow.getShell());
        
        try {
            pmDialog.run(true, true, new IRunnableWithProgress() {
                @Override
                public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
                    try {
                        // Update Proxy
                        ProxyAuthenticator.update(getRepository().getOnlineRepositoryURL());
                        
                        monitor.beginTask(Messages.MergeBranchAction_11, -1);
                        
                        // Pull
                        monitor.subTask(NLS.bind(Messages.MergeBranchAction_17, currentBranch.getShortName()));
                        RefreshResult pullResult = repositoryService.refresh(
                                getRepository(), npw, mergeHandler, monitor);
                        
                        // Push
                        if(pullResult.status() == RefreshResult.Status.OK || pullResult.status() == RefreshResult.Status.UP_TO_DATE) {
                            monitor.subTask(NLS.bind(Messages.MergeBranchAction_18, currentBranch.getShortName()));
                            getRepository().pushToRemote(npw, new ProgressMonitorWrapper(monitor));
                        }
                        else {
                            return;
                        }
                        
                        // Switch to other branch
                        monitor.subTask(NLS.bind(Messages.MergeBranchAction_14, branchToMerge.getShortName()));
                        SwitchBranchAction switchBranchAction = new SwitchBranchAction(fWindow);
                        switchBranchAction.setRepository(getRepository());
                        switchBranchAction.switchBranch(branchToMerge, true);
                        
                        // Pull again
                        monitor.subTask(NLS.bind(Messages.MergeBranchAction_17, branchToMerge.getShortName()));
                        pullResult = repositoryService.refresh(
                                getRepository(), npw, mergeHandler, monitor);
                        
                        // Push
                        if(pullResult.status() == RefreshResult.Status.OK || pullResult.status() == RefreshResult.Status.UP_TO_DATE) {
                            monitor.subTask(NLS.bind(Messages.MergeBranchAction_18, branchToMerge.getShortName()));
                            getRepository().pushToRemote(npw, new ProgressMonitorWrapper(monitor));
                        }
                        else {
                            return;
                        }
                        
                        // Switch back
                        monitor.subTask(NLS.bind(Messages.MergeBranchAction_14, currentBranch.getShortName()));
                        switchBranchAction.switchBranch(currentBranch, true);
                        
                        // Merge
                        merge(currentBranch, branchToMerge, monitor);
                        
                        // Final Push on this branch
                        monitor.subTask(NLS.bind(Messages.MergeBranchAction_18, currentBranch.getShortName()));
                        getRepository().pushToRemote(npw, new ProgressMonitorWrapper(monitor));
                        
                        // Ask user to delete branch (if not master)
                        DeleteBranchAction deleteBranchAction = new DeleteBranchAction(fWindow);
                        deleteBranchAction.setRepository(getRepository());
                        deleteBranchAction.setBranch(branchToMerge);
                        
                        if(deleteBranchAction.shouldBeEnabled()) {
                            final boolean[] doDeleteBranch = new boolean[1];
                            Display.getDefault().syncExec(() -> {
                                doDeleteBranch[0] = MessageDialog.openQuestion(fWindow.getShell(),
                                        Messages.MergeBranchAction_1,
                                        NLS.bind(Messages.MergeBranchAction_9, branchToMerge.getShortName()));
                            });

                            if(doDeleteBranch[0]) {
                                monitor.subTask(Messages.MergeBranchAction_12);
                                // Branch will have been pushed at this point so BranchInfo is no longer valid to determine if it's just a local branch
                                deleteBranchAction.deleteBranchAndPush(branchToMerge, npw);
                            }
                        }
                    }
                    catch(Exception ex) {
                        Display.getDefault().syncExec(() -> {
                            displayErrorDialog(Messages.MergeBranchAction_1, ex);
                        });
                    }
                    finally {
                        try {
                            Display.getDefault().syncExec(() -> {
                                try {
                                    saveChecksumAndNotifyListeners();
                                }
                                catch(IOException ex) {
                                    ex.printStackTrace();
                                }
                            });
                        }
                        finally {
                            // Clear Proxy
                            ProxyAuthenticator.clear();
                            
                            // Clear credentials
                            if(npw != null) {
                                npw.clear();
                            }
                        }
                    }
                }
            });
        }
        catch(InvocationTargetException | InterruptedException ex) {
            ex.printStackTrace();
        }
    }
    
    private int merge(BranchInfo currentBranch, BranchInfo branchToMerge, IProgressMonitor monitor) throws GitAPIException, IOException {
        RepositoryService repositoryService = new RepositoryService();
        MergeHandler mergeHandler = new InteractiveMergeHandler(fWindow.getShell());
        
        RepositoryService.MergeBranchResult result = repositoryService.mergeBranch(
                getRepository(), currentBranch, branchToMerge, mergeHandler, monitor);
        
        switch(result.status()) {
            case OK:
                return MERGE_STATUS_OK;
            case UP_TO_DATE:
                return MERGE_STATUS_UP_TO_DATE;
            case MERGE_CANCELLED:
                return MERGE_STATUS_MERGE_CANCEL;
            case ERROR:
            default:
                return MERGE_STATUS_ERROR;
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
        return fBranchInfo != null
                && !fBranchInfo.isCurrentBranch() // Not current branch
                && fBranchInfo.isLocal() // Has to be local
                && !isBranchRefSameAsCurrentBranchRef(fBranchInfo) // Not same ref
                && super.shouldBeEnabled();
    }

}
