/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.actions;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;

import org.archicontribs.modelrepository.IModelRepositoryImages;
import org.archicontribs.modelrepository.UIPerfLogger;
import org.archicontribs.modelrepository.grafico.BranchInfo;
import org.archicontribs.modelrepository.grafico.GraficoModelImporter;
import org.archicontribs.modelrepository.grafico.GraficoModelLoader;
import org.archicontribs.modelrepository.grafico.IGraficoConstants;
import org.archicontribs.modelrepository.grafico.IRepositoryListener;
import org.archicontribs.modelrepository.services.HeadlessMergeHandler;
import org.archicontribs.modelrepository.services.RepositoryService;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.swt.SWT;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateModel;

/**
 * Switch and checkout Branch
 */
public class SwitchBranchAction extends AbstractModelAction {
    
    private static final String TAG = "[SwitchBranch]"; //$NON-NLS-1$
    
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

        long t0 = System.nanoTime();
        UIPerfLogger.log(TAG, "run() START"); //$NON-NLS-1$

        // Keep a local reference in case of a notification event changing the current branch selection in the UI
        BranchInfo branchInfo = fBranchInfo;
        
        // Get current branch name for progress reporting
        String currentBranchName;
        try {
            BranchInfo currentBranch = getRepository().getBranchStatus().getCurrentLocalBranch();
            currentBranchName = currentBranch != null ? currentBranch.getShortName() : "current"; //$NON-NLS-1$
        }
        catch(IOException | GitAPIException ex) {
            currentBranchName = "current"; //$NON-NLS-1$
        }
        final String currentBranchNameFinal = currentBranchName;
        
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
                    
                    // Switch branch with combined progress dialog
                    switchBranchWithProgress(branchInfo, !isBranchRefSameAsCurrentBranchRef(branchInfo));
                    notifyChangeListeners(IRepositoryListener.BRANCHES_CHANGED);
                    
                    return;
                }
                catch(IOException | GitAPIException ex) {
                    displayErrorDialog(Messages.SwitchBranchAction_0, ex);
                    return;
                }
            }
        }
        
        boolean notifyHistoryChanged = false;
        boolean[] hasChanges = new boolean[1];
        boolean[] wasCancelled = new boolean[1];
        
        try {
            // Phase 1: Export model to check for changes AND stage them with git add
            // We need to stage files so that:
            // 1. hasChangesToCommit() works correctly (it checks git status)
            // 2. offerToCommitChanges() can commit the staged changes
            Exception[] exception = new Exception[1];
            
            try {
                PlatformUI.getWorkbench().getProgressService().busyCursorWhile(new IRunnableWithProgress() {
                    @Override
                    public void run(IProgressMonitor pm) throws InvocationTargetException, InterruptedException {
                        SubMonitor progress = SubMonitor.convert(pm, 
                                MessageFormat.format(Messages.SwitchBranchAction_18, currentBranchNameFinal), 100);
                        try {
                            // Export AND stage with git add - we need files staged for commit to work
                            hasChanges[0] = getRepository().exportModelToGraficoFiles(progress.split(100));
                            
                            // Check for cancellation after export
                            if(progress.isCanceled()) {
                                throw new InterruptedException("Export cancelled by user");
                            }
                        }
                        catch(IOException | GitAPIException ex) {
                            exception[0] = ex;
                        }
                    }
                });
            }
            catch(InvocationTargetException ex) {
                if(ex.getCause() != null) {
                    exception[0] = (Exception)ex.getCause();
                } else {
                    throw new IOException(ex);
                }
            }
            catch(InterruptedException ex) {
                // User cancelled during export - reset to HEAD to discard partial export
                wasCancelled[0] = true;
                try {
                    getRepository().resetToRef(IGraficoConstants.HEAD);
                } catch(Exception resetEx) {
                    // Log but don't throw - we want to inform user about cancellation
                    resetEx.printStackTrace();
                }
                return; // Exit gracefully
            }
            
            if(exception[0] != null) {
                throw exception[0];
            }
            
            // If there are changes to commit...
            if(hasChanges[0]) {
                // Ask user if they want to commit before switching
                boolean doCommit = MessageDialog.openQuestion(fWindow.getShell(),
                        Messages.SwitchBranchAction_0,
                        Messages.SwitchBranchAction_1);

                // Commit dialog - changes are already staged, so commit will work
                if(doCommit) {
                    if(!offerToCommitChanges()) {
                        // User cancelled commit dialog - reset staged changes and abort
                        getRepository().resetToRef(IGraficoConstants.HEAD);
                        return;
                    }
                    // Commit succeeded - proceed to switch branch
                } else {
                    // User chose "no" to commit - ask if they want to lose changes
                    boolean proceed = MessageDialog.openQuestion(fWindow.getShell(),
                            Messages.SwitchBranchAction_0,
                            Messages.SwitchBranchAction_5);
                    
                    if(!proceed) {
                        // User cancelled - reset staged changes and abort
                        getRepository().resetToRef(IGraficoConstants.HEAD);
                        return;
                    }
                    
                    // User chose to discard changes - reset to HEAD
                    getRepository().resetToRef(IGraficoConstants.HEAD);
                    notifyHistoryChanged = true;
                }
            }
            
            // Phase 2 & 3: Switch branch and load model with combined progress
            UIPerfLogger.log(TAG, "switchBranchWithProgress() START", t0); //$NON-NLS-1$
            switchBranchWithProgress(branchInfo, !isBranchRefSameAsCurrentBranchRef(branchInfo));
            UIPerfLogger.log(TAG, "switchBranchWithProgress() END", t0); //$NON-NLS-1$
        }
        catch(Exception ex) {
            displayErrorDialog(Messages.SwitchBranchAction_0, ex);
        }

        // Notify listeners last because a new UI selection will trigger an updated BranchInfo here
        UIPerfLogger.log(TAG, "notifyChangeListeners START", t0); //$NON-NLS-1$
        if(notifyHistoryChanged) {
            notifyChangeListeners(IRepositoryListener.HISTORY_CHANGED);
        }
        notifyChangeListeners(IRepositoryListener.BRANCHES_CHANGED);
        UIPerfLogger.log(TAG, "run() END total", t0); //$NON-NLS-1$
    }
    
    /**
     * Switch branch with a single combined progress dialog for Git checkout and model import.
     * 
     * <p>Cancellation handling:</p>
     * <ul>
     *   <li>Cancel button is enabled during git checkout phase</li>
     *   <li>Cancel button is DISABLED during import phase (after checkout completes)</li>
     *   <li>This prevents users from cancelling when it would leave the model inconsistent</li>
     * </ul>
     * 
     * <p>Note: Git checkout and file import run in a background thread, but model UI operations
     * (save, close, open, reopen editors) MUST run on the UI thread because they trigger 
     * property change events that update UI components (e.g., SaveAction).</p>
     */
    private void switchBranchWithProgress(BranchInfo branchInfo, boolean doReloadGrafico) throws IOException, GitAPIException {
        Exception[] exception = new Exception[1];
        IArchimateModel[] importedModel = new IArchimateModel[1];
        GraficoModelImporter[] importerRef = new GraficoModelImporter[1];
        boolean[] checkoutCompleted = new boolean[1];
        long tSwitch = System.nanoTime();
        
        // Use ProgressMonitorDialog directly so we can control the cancel button
        ProgressMonitorDialog dialog = new ProgressMonitorDialog(fWindow.getShell());
        dialog.setCancelable(true);  // Start with cancel enabled (for checkout phase)
        
        try {
            dialog.run(true, true, new IRunnableWithProgress() {
                @Override
                public void run(IProgressMonitor pm) throws InvocationTargetException, InterruptedException {
                    // Allocate: 30% for git checkout, 70% for import
                    int totalWork = doReloadGrafico ? 100 : 30;
                    SubMonitor progress = SubMonitor.convert(pm, 
                            MessageFormat.format(Messages.SwitchBranchAction_19, branchInfo.getShortName()), totalWork);
                    
                    try {
                        // Phase 1: Git checkout (30%) - CANCELLABLE
                        if(progress.isCanceled()) {
                            throw new InterruptedException("Cancelled before checkout");
                        }
                        
                        long tCheckout = System.nanoTime();
                        performGitCheckoutWithMonitor(branchInfo, progress.split(30));
                        checkoutCompleted[0] = true;
                        UIPerfLogger.log(TAG, "git checkout", tCheckout); //$NON-NLS-1$
                        
                        // Phase 2: Import model files (70%) - NOT CANCELLABLE
                        // After checkout, we MUST complete import to keep model consistent
                        if(doReloadGrafico) {
                            long tImport = System.nanoTime();
                            // Disable cancellation for import phase
                            // Note: SubMonitor doesn't directly support this, but we ignore cancel
                            progress.subTask(MessageFormat.format(Messages.SwitchBranchAction_20, branchInfo.getShortName()));
                            importerRef[0] = new GraficoModelImporter(getRepository().getLocalRepositoryFolder());
                            
                            // Create a non-cancellable wrapper for the import
                            IProgressMonitor nonCancellableMonitor = new NonCancellableProgressMonitor(progress.split(70));
                            importedModel[0] = importerRef[0].importAsModel(nonCancellableMonitor);
                            UIPerfLogger.log(TAG, "grafico import", tImport); //$NON-NLS-1$
                        }
                        
                        // Pre-warm the BranchStatus cache while the progress dialog is still open.
                        // This avoids a 1-2s delay between dialog close and branch highlight update,
                        // since the BRANCHES_CHANGED handlers use async threads that call getBranchStatus().
                        try {
                            long tCache = System.nanoTime();
                            getRepository().getBranchStatus();
                            UIPerfLogger.log(TAG, "pre-warm BranchStatus cache", tCache); //$NON-NLS-1$
                        }
                        catch(Exception cacheEx) {
                            // Non-critical — handlers will recompute if cache miss
                        }
                    }
                    catch(IOException | GitAPIException ex) {
                        exception[0] = ex;
                    }
                }
            });
        }
        catch(InvocationTargetException ex) {
            if(ex.getCause() instanceof Exception) {
                exception[0] = (Exception)ex.getCause();
            } else {
                throw new IOException(ex);
            }
        }
        catch(InterruptedException ex) {
            // Cancelled before checkout completed - safe to abort, nothing changed
            if(!checkoutCompleted[0]) {
                return;
            }
            // Should not happen - import phase ignores cancellation
        }
        
        UIPerfLogger.log(TAG, "dialog.run() returned", tSwitch); //$NON-NLS-1$
        
        // Re-throw any exception from the background phase
        if(exception[0] != null) {
            // If checkout completed but import failed, try to recover
            if(checkoutCompleted[0] && doReloadGrafico) {
                try {
                    new GraficoModelLoader(getRepository()).loadModel();
                    getRepository().saveChecksum();
                } catch(IOException reloadEx) {
                    reloadEx.printStackTrace();
                }
            }
            
            if(exception[0] instanceof IOException) {
                throw (IOException)exception[0];
            }
            if(exception[0] instanceof GitAPIException) {
                throw (GitAPIException)exception[0];
            }
            throw new IOException(exception[0]);
        }
        
        // Phase 3: UI operations on UI thread (required because they trigger UI property changes)
        if(doReloadGrafico && importedModel[0] != null) {
            long tOpen = System.nanoTime();
            new GraficoModelLoader(getRepository()).openModel(importedModel[0], importerRef[0]);
            getRepository().saveChecksum();
            UIPerfLogger.log(TAG, "openModel + saveChecksum", tOpen); //$NON-NLS-1$
        } else if(doReloadGrafico && checkoutCompleted[0]) {
            // Checkout completed but import returned null - try to recover
            try {
                long tLoad = System.nanoTime();
                new GraficoModelLoader(getRepository()).loadModel();
                getRepository().saveChecksum();
                UIPerfLogger.log(TAG, "loadModel (recovery) + saveChecksum", tLoad); //$NON-NLS-1$
            } catch(IOException loadEx) {
                throw loadEx;
            }
        }
        UIPerfLogger.log(TAG, "switchBranchWithProgress total", tSwitch); //$NON-NLS-1$
    }
    
    /**
     * A progress monitor wrapper that ignores cancellation requests.
     * Used during the import phase when cancellation would leave the model inconsistent.
     */
    private static class NonCancellableProgressMonitor implements IProgressMonitor {
        private final IProgressMonitor delegate;
        
        public NonCancellableProgressMonitor(IProgressMonitor delegate) {
            this.delegate = delegate;
        }
        
        @Override
        public void beginTask(String name, int totalWork) {
            delegate.beginTask(name, totalWork);
        }
        
        @Override
        public void done() {
            delegate.done();
        }
        
        @Override
        public void internalWorked(double work) {
            delegate.internalWorked(work);
        }
        
        @Override
        public boolean isCanceled() {
            return false;  // Always return false - ignore cancellation
        }
        
        @Override
        public void setCanceled(boolean value) {
            // Ignore - don't allow setting cancelled
        }
        
        @Override
        public void setTaskName(String name) {
            delegate.setTaskName(name);
        }
        
        @Override
        public void subTask(String name) {
            delegate.subTask(name);
        }
        
        @Override
        public void worked(int work) {
            delegate.worked(work);
        }
    }
    
    /**
     * Switch branch for headless/command-line usage (no progress dialog).
     * Also used by MergeBranchAction.
     * Delegates to {@link RepositoryService#switchBranch}.
     */
    protected void switchBranch(BranchInfo branchInfo, boolean doReloadGrafico) throws IOException, GitAPIException {
        switchBranch(branchInfo, doReloadGrafico, null);
    }
    
    /**
     * Switch branch with optional external progress monitor.
     * Delegates to {@link RepositoryService#switchBranch}.
     * @param branchInfo The branch to switch to
     * @param doReloadGrafico Whether to reload the model after checkout
     * @param monitor External progress monitor (null for headless mode)
     */
    protected void switchBranch(BranchInfo branchInfo, boolean doReloadGrafico, IProgressMonitor monitor) throws IOException, GitAPIException {
        RepositoryService repositoryService = new RepositoryService();
        HeadlessMergeHandler mergeHandler = new HeadlessMergeHandler();
        repositoryService.switchBranch(getRepository(), branchInfo, doReloadGrafico, mergeHandler, monitor);
    }
    
    /**
     * Perform the Git checkout operation with external progress monitor.
     * This is used when combining checkout with other operations in a single progress dialog.
     */
    private void performGitCheckoutWithMonitor(BranchInfo branchInfo, IProgressMonitor monitor) throws IOException, GitAPIException {
        SubMonitor progress = SubMonitor.convert(monitor, Messages.SwitchBranchAction_6, 100);
        
        File repoFolder = getRepository().getLocalRepositoryFolder();
        
        // If the branch is remote and has no local ref, we need to create it first using JGit
        if(branchInfo.isRemote() && !branchInfo.hasLocalRef()) {
            progress.subTask(Messages.SwitchBranchAction_8);
            try(Git git = Git.open(repoFolder)) {
                git.branchCreate()
                        .setName(branchInfo.getShortName())
                        .setStartPoint(branchInfo.getFullName())
                        .call();
            }
        }
        progress.worked(10);
        
        // Determine the branch name to checkout
        // For JGit, we can use the full name (refs/heads/...) for local branches
        // For native git, we must use the short name (just the branch name)
        String branchName = branchInfo.isLocal() ? 
                branchInfo.getFullName() : branchInfo.getShortName();
        
        // Perform checkout using repository method (tries native git, falls back to JGit automatically)
        progress.subTask(Messages.SwitchBranchAction_11); // "Checking out branch..."
        getRepository().checkoutBranch(branchName);
        progress.subTask(Messages.SwitchBranchAction_17); // "Checkout completed"
        
        progress.worked(90);
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
