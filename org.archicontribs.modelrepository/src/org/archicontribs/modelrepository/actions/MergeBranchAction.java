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
import org.archicontribs.modelrepository.grafico.ArchiRepository;
import org.archicontribs.modelrepository.grafico.BranchInfo;
import org.archicontribs.modelrepository.grafico.GraficoModelLoader;
import org.archicontribs.modelrepository.grafico.IGraficoConstants;
import org.archicontribs.modelrepository.merge.MergeConflictHandler;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.MergeResult.MergeStatus;
import org.eclipse.jgit.api.errors.CanceledException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
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
                        int pullStatus = pushAction.pull(npw, monitor);
                        
                        // Push
                        if(pullStatus == RefreshModelAction.PULL_STATUS_OK || pullStatus == RefreshModelAction.PULL_STATUS_UP_TO_DATE) {
                            monitor.subTask(NLS.bind(Messages.MergeBranchAction_18, currentBranch.getShortName()));
                            pushAction.push(npw, monitor);
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
                        pullStatus = pushAction.pull(npw, monitor);
                        
                        // Push
                        if(pullStatus == RefreshModelAction.PULL_STATUS_OK || pullStatus == RefreshModelAction.PULL_STATUS_UP_TO_DATE) {
                            monitor.subTask(NLS.bind(Messages.MergeBranchAction_18, branchToMerge.getShortName()));
                            pushAction.push(npw, monitor);
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
                        pushAction.push(npw, monitor);
                        
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
        monitor.subTask(NLS.bind(Messages.MergeBranchAction_13, branchToMerge.getShortName(), currentBranch.getShortName()));

        int conflictCount = 0;
        
        try(Git git = Git.open(getRepository().getLocalRepositoryFolder())) {
            ObjectId oursId = git.getRepository().resolve(IGraficoConstants.HEAD);
            ObjectId theirsId = git.getRepository().resolve(branchToMerge.getShortName());
            
            String mergeMessage = NLS.bind(Messages.MergeBranchAction_2, branchToMerge.getShortName(), currentBranch.getShortName());
            
            // Use ArchiRepository.mergeBranch() — tries native git first for progress streaming
            MergeResult mergeResult = ((ArchiRepository) getRepository()).mergeBranch(
                    branchToMerge.getShortName(), mergeMessage, monitor);
            
            // null = native git performed a clean merge
            MergeStatus status = mergeResult != null ? mergeResult.getMergeStatus() : null;
            
            // Conflict
            if(status == MergeStatus.CONFLICTING) {
                conflictCount = mergeResult.getConflicts() != null ? mergeResult.getConflicts().size() : 0;
                monitor.subTask(NLS.bind(Messages.MergeBranchAction_16, conflictCount));
                
                // Try to handle the merge conflict
                MergeConflictHandler handler = new MergeConflictHandler(mergeResult, branchToMerge.getShortName(),
                        getRepository(), fWindow.getShell());
                
                try {
                    handler.init(monitor);
                }
                catch(IOException | GitAPIException ex) {
                    handler.resetToLocalState(); // Clean up

                    if(ex instanceof CanceledException) {
                        return MERGE_STATUS_MERGE_CANCEL;
                    }

                    throw ex;
                }
                
                String dialogMessage = NLS.bind(Messages.MergeBranchAction_10,
                        branchToMerge.getShortName(), currentBranch.getShortName());
                
                // Show conflicts dialog on UI thread
                final boolean[] dialogResult = new boolean[1];
                Display.getDefault().syncExec(() -> {
                    dialogResult[0] = handler.openConflictsDialog(dialogMessage);
                });
                
                if(dialogResult[0]) {
                    handler.merge();
                    ModelRepositoryPlugin.getInstance().log(IStatus.INFO, "[MergeBranchAction] handler.merge() completed", null); //$NON-NLS-1$
                }
                // User cancelled - so we reset
                else {
                    handler.resetToLocalState();
                    return MERGE_STATUS_MERGE_CANCEL;
                }
            }
            
            if(conflictCount == 0) {
                monitor.subTask(Messages.MergeBranchAction_15);
            }
            
            // Reload the model from the Grafico XML files
            GraficoModelLoader loader = new GraficoModelLoader(getRepository());
            
            // Phase 1.5: detect and remove elements deleted by one parent but leaked via move by the other
            monitor.subTask(Messages.RefreshModelAction_12);
            long t = System.nanoTime();
            if(oursId != null && theirsId != null) {
                int removed = MergeConflictHandler.detectAndRemoveCrossPathDeletions(
                        git.getRepository(), oursId, theirsId);
                ModelRepositoryPlugin.getInstance().log(IStatus.INFO,
                        "[MergeBranchAction] detectAndRemoveCrossPathDeletions: removed=" + removed //$NON-NLS-1$
                        + " (" + (System.nanoTime() - t) / 1_000_000 + "ms)", null); //$NON-NLS-1$ //$NON-NLS-2$
            }
            
            // Pre-repair: detect and resolve folder moves before loading the model
            t = System.nanoTime();
            loader.repairMissingFolderXml();
            ModelRepositoryPlugin.getInstance().log(IStatus.INFO, "[MergeBranchAction] repairMissingFolderXml: " + (System.nanoTime() - t) / 1_000_000 + "ms", null); //$NON-NLS-1$ //$NON-NLS-2$
            t = System.nanoTime();
            loader.applyFolderMoveResolutions();
            ModelRepositoryPlugin.getInstance().log(IStatus.INFO, "[MergeBranchAction] applyFolderMoveResolutions: " + (System.nanoTime() - t) / 1_000_000 + "ms", null); //$NON-NLS-1$ //$NON-NLS-2$
            
            t = System.nanoTime();
            // Reload the model from the Grafico XML files (must be on UI thread)
            monitor.subTask(Messages.RefreshModelAction_8);
            final IOException[] loadEx = new IOException[1];
            final long loadStart = t;
            Display.getDefault().syncExec(() -> {
                try {
                    loader.loadModel();
                }
                catch(IOException ex) {
                    loadEx[0] = ex;
                }
            });
            if(loadEx[0] != null) {
                throw loadEx[0];
            }
            ModelRepositoryPlugin.getInstance().log(IStatus.INFO, "[MergeBranchAction] loadModel: " + (System.nanoTime() - loadStart) / 1_000_000 + "ms", null); //$NON-NLS-1$ //$NON-NLS-2$
            
            // Do a commit if needed
            t = System.nanoTime();
            boolean hasChanges = getRepository().hasChangesToCommit();
            ModelRepositoryPlugin.getInstance().log(IStatus.INFO, "[MergeBranchAction] hasChangesToCommit=" + hasChanges + " (" + (System.nanoTime() - t) / 1_000_000 + "ms)", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            
            // Also check for MERGE_HEAD presence (indicates we're in a merge)
            java.io.File mergeHead = new java.io.File(getRepository().getLocalRepositoryFolder(), ".git/MERGE_HEAD"); //$NON-NLS-1$
            ModelRepositoryPlugin.getInstance().log(IStatus.INFO, "[MergeBranchAction] MERGE_HEAD exists=" + mergeHead.exists(), null); //$NON-NLS-1$
            
            if(hasChanges || mergeHead.exists()) {
                monitor.subTask(Messages.RefreshModelAction_9);
                if(conflictCount > 0) {
                    mergeMessage = NLS.bind(Messages.MergeBranchAction_3, new Object[] { branchToMerge.getShortName(), currentBranch.getShortName(), conflictCount });
                }
                else {
                    mergeMessage = NLS.bind(Messages.MergeBranchAction_2, branchToMerge.getShortName(), currentBranch.getShortName());
                }
                
                // Did we restore any missing objects?
                String restoredObjects = loader.getRestoredObjectsAsString();
                
                // Add to commit message
                if(restoredObjects != null) {
                    mergeMessage += "\n\n" + Messages.RefreshModelAction_3 + "\n" + restoredObjects; //$NON-NLS-1$ //$NON-NLS-2$
                }
                
                // Did we repair any missing folder.xml files?
                String repairDetails = loader.getRepairDetailsAsString();
                if(repairDetails != null) {
                    mergeMessage += "\n" + repairDetails; //$NON-NLS-1$
                }

                // IMPORTANT!!! "amend" has to be false after a merge conflict or else the commit will be orphaned
                getRepository().commitChanges(mergeMessage, false);
            }
        }
        
        return MERGE_STATUS_OK;
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
