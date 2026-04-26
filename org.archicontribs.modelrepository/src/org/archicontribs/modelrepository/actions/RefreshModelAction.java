/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.actions;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.security.GeneralSecurityException;

import org.archicontribs.modelrepository.IModelRepositoryImages;
import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.archicontribs.modelrepository.authentication.ProxyAuthenticator;
import org.archicontribs.modelrepository.authentication.UsernamePassword;
import org.archicontribs.modelrepository.authentication.internal.EncryptedCredentialsStorage;
import org.archicontribs.modelrepository.grafico.ArchiRepository;
import org.archicontribs.modelrepository.grafico.BranchStatus;
import org.archicontribs.modelrepository.grafico.GraficoModelLoader;
import org.archicontribs.modelrepository.grafico.GraficoUtils;
import org.archicontribs.modelrepository.grafico.IGraficoConstants;
import org.archicontribs.modelrepository.grafico.IRepositoryListener;
import org.archicontribs.modelrepository.merge.FolderMoveResolutionDialog;
import org.archicontribs.modelrepository.merge.MergeConflictHandler;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.MergeResult.MergeStatus;
import org.eclipse.jgit.api.errors.CanceledException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefNotAdvertisedException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchWindow;

import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.model.IArchimateModel;

/**
 * Refresh model action
 * 
 * 1. Offer to save the model
 * 2. If there are changes offer to Commit
 * 3. Get credentials for Pull
 * 4. Check Proxy
 * 5. Pull from Remote
 * 6. Handle Merge conflicts
 * 7. Reload temp file from Grafico files
 * 
 * @author Jean-Baptiste Sarrodie
 * @author Phillip Beauvoir
 */
public class RefreshModelAction extends AbstractModelAction {
    
    protected static final int PULL_STATUS_ERROR = -1;
    protected static final int PULL_STATUS_OK = 0;
    protected static final int PULL_STATUS_UP_TO_DATE = 1;
    protected static final int PULL_STATUS_MERGE_CANCEL = 2;
    
    protected static final int USER_OK = 0;
    protected static final int USER_CANCEL = 1;
    
    public RefreshModelAction(IWorkbenchWindow window) {
        super(window);
        setImageDescriptor(IModelRepositoryImages.ImageFactory.getImageDescriptor(IModelRepositoryImages.ICON_REFRESH));
        setText(Messages.RefreshModelAction_0);
        setToolTipText(Messages.RefreshModelAction_0);
    }
    
    public RefreshModelAction(IWorkbenchWindow window, IArchimateModel model) {
        this(window);
        if(model != null) {
            setRepository(new ArchiRepository(GraficoUtils.getLocalRepositoryFolderForModel(model)));
        }
    }
    
    @Override
    public void run() {
        try {
            int status = init();
            if(status != USER_OK) {
                return;
            }
            
            // Check primary key set
            if(!EncryptedCredentialsStorage.checkPrimaryKeySet()) {
                return;
            }

            // Get this before opening the progress dialog
            // UsernamePassword will be null if using SSH
            UsernamePassword npw = getUsernamePassword();
            // User cancelled on HTTP
            if(npw == null && GraficoUtils.isHTTP(getRepository().getOnlineRepositoryURL())) {
                return;
            }

            // Do main action with PM dialog — run on background thread (true)
            ProgressMonitorDialog pmDialog = new ProgressMonitorDialog(fWindow.getShell());
            
            pmDialog.run(true, true, new IRunnableWithProgress() {
                @Override
                public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
                    try {
                        // Update Proxy
                        ProxyAuthenticator.update(getRepository().getOnlineRepositoryURL());
                        
                        monitor.beginTask(Messages.RefreshModelAction_5, -1);
                        int status = pull(npw, monitor);
                        if(status == PULL_STATUS_UP_TO_DATE) {
                            Display.getDefault().syncExec(() -> {
                                MessageDialog.openInformation(fWindow.getShell(), Messages.RefreshModelAction_0, Messages.RefreshModelAction_2);
                            });
                        }
                    }
                    catch(Exception ex) {
                        Display.getDefault().syncExec(() -> {
                            displayErrorDialog(Messages.RefreshModelAction_0, ex);
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
        catch(GeneralSecurityException ex) {
            displayCredentialsErrorDialog(ex);
        }
        catch(Exception ex) {
            displayErrorDialog(Messages.RefreshModelAction_0, ex);
        }
    }
    
    protected int init() throws IOException, GitAPIException {
        long initStart = System.nanoTime();
        long phaseStart;
        
        // Offer to save the model if open and dirty
        // We need to do this to keep grafico and temp files in sync
        IArchimateModel model = getRepository().locateModel();
        if(model != null && IEditorModelManager.INSTANCE.isModelDirty(model)) {
            if(!offerToSaveModel(model)) {
                return USER_CANCEL;
            }
        }
        
        // Do the Grafico Export first
        phaseStart = System.nanoTime();
        getRepository().exportModelToGraficoFiles();
        logPerf("exportModelToGraficoFiles", phaseStart); //$NON-NLS-1$
        
        // Then offer to Commit
        phaseStart = System.nanoTime();
        boolean hasChanges = getRepository().hasChangesToCommit();
        logPerf("hasChangesToCommit (init)", phaseStart); //$NON-NLS-1$
        
        if(hasChanges) {
            if(!offerToCommitChanges()) {
                // User cancelled commit dialog - reset staged changes
                getRepository().resetToRef(IGraficoConstants.HEAD);
                return USER_CANCEL;
            }
            notifyChangeListeners(IRepositoryListener.HISTORY_CHANGED);
        }
        
        logPerf("=== TOTAL INIT ===", initStart); //$NON-NLS-1$
        
        return USER_OK;
    }
    
    protected int pull(UsernamePassword npw, IProgressMonitor monitor) throws IOException, GitAPIException  {
        long pullStart = System.nanoTime();
        long phaseStart;
        
        // Capture HEAD before pull for cross-path deletion detection
        ObjectId oursIdBeforePull = null;
        phaseStart = System.nanoTime();
        try(Git git = Git.open(getRepository().getLocalRepositoryFolder())) {
            oursIdBeforePull = git.getRepository().resolve(IGraficoConstants.HEAD);
        }
        logPerf("Resolve HEAD before pull", phaseStart); //$NON-NLS-1$
        
        monitor.subTask(Messages.RefreshModelAction_6);
        
        // Phase 1: JGit fetch (handles HTTPS/SSH credentials)
        FetchResult fetchResult;
        phaseStart = System.nanoTime();
        try {
            fetchResult = getRepository().fetchFromRemote(npw, new ProgressMonitorWrapper(monitor), false);
        }
        catch(Exception ex) {
            if(ex instanceof RefNotAdvertisedException) {
                return PULL_STATUS_OK;
            }
            throw ex;
        }
        logPerf("fetchFromRemote", phaseStart); //$NON-NLS-1$
        
        boolean newTrackingRefUpdates = fetchResult != null && !fetchResult.getTrackingRefUpdates().isEmpty();
        
        // Phase 2: Determine if merge is needed
        phaseStart = System.nanoTime();
        MergeResult mergeResult = null;
        
        try(Git git = Git.open(getRepository().getLocalRepositoryFolder())) {
            Repository repository = git.getRepository();
            String currentBranch = repository.getBranch();
            String remoteBranch = IGraficoConstants.ORIGIN + "/" + currentBranch; //$NON-NLS-1$
            ObjectId remoteId = repository.resolve(Constants.R_REMOTES + remoteBranch);
            ObjectId headId = repository.resolve(IGraficoConstants.HEAD);
            
            if(remoteId == null || (headId != null && headId.equals(remoteId))) {
                // Already up to date
                logPerf("merge (already up to date)", phaseStart); //$NON-NLS-1$
                if(newTrackingRefUpdates) {
                    return PULL_STATUS_OK;
                }
                return PULL_STATUS_UP_TO_DATE;
            }
            
            // Phase 2a: Try native git merge (dramatically faster for large repos)
            ArchiRepository archiRepo = (ArchiRepository) getRepository();
            Boolean nativeResult = ArchiRepository.isNativeGitEnabled()
                    ? archiRepo.tryNativeGitMerge(remoteBranch) : null;
            
            if(Boolean.TRUE.equals(nativeResult)) {
                // Native merge succeeded cleanly
                logPerf("merge (native git)", phaseStart); //$NON-NLS-1$
            }
            else if(Boolean.FALSE.equals(nativeResult)) {
                // Native merge had conflicts — abort and fall back to JGit
                archiRepo.abortNativeMerge();
                logPerf("merge (native git — conflicts, aborted)", phaseStart); //$NON-NLS-1$
                
                // Re-open git since abort may have changed state
                phaseStart = System.nanoTime();
                try(Git git2 = Git.open(getRepository().getLocalRepositoryFolder())) {
                    ObjectId freshRemoteId = git2.getRepository().resolve(Constants.R_REMOTES + remoteBranch);
                    MergeCommand mergeCommand = git2.merge();
                    mergeCommand.include(remoteBranch, freshRemoteId);
                    mergeResult = mergeCommand.call();
                }
                logPerf("merge (JGit fallback for conflicts)", phaseStart); //$NON-NLS-1$
            }
            else {
                // Native git not available — full JGit merge fallback
                MergeCommand mergeCommand = git.merge();
                mergeCommand.include(remoteBranch, remoteId);
                mergeResult = mergeCommand.call();
                logPerf("merge (JGit fallback)", phaseStart); //$NON-NLS-1$
            }
        }
        
        // Invalidate branch status cache since refs may have changed
        ((ArchiRepository) getRepository()).invalidateBranchStatusCache();
        
        monitor.subTask(Messages.RefreshModelAction_7);
        
        phaseStart = System.nanoTime();
        BranchStatus branchStatus = getRepository().getBranchStatus();
        logPerf("getBranchStatus", phaseStart); //$NON-NLS-1$
        
        // Setup the Graphico Model Loader
        GraficoModelLoader loader = new GraficoModelLoader(getRepository());

        // Merge failure — only possible if JGit merge was used (native merge was either clean or aborted+retried)
        if(mergeResult != null && mergeResult.getMergeStatus() == MergeStatus.CONFLICTING) {
            // Get the remote ref name
            String remoteRef = branchStatus.getCurrentRemoteBranch().getFullName();
            
            // Try to handle the merge conflict
            MergeConflictHandler handler = new MergeConflictHandler(mergeResult, remoteRef,
                    getRepository(), fWindow.getShell());
            
            try {
                handler.init(monitor);
            }
            catch(IOException | GitAPIException ex) {
                handler.resetToLocalState(); // Clean up

                if(ex instanceof CanceledException) {
                    return PULL_STATUS_MERGE_CANCEL;
                }

                throw ex;
            }
            
            String dialogMessage = NLS.bind(Messages.RefreshModelAction_4, branchStatus.getCurrentLocalBranch().getShortName());
            
            // Show conflicts dialog on UI thread
            final boolean[] dialogResult = new boolean[1];
            Display.getDefault().syncExec(() -> {
                dialogResult[0] = handler.openConflictsDialog(dialogMessage);
            });

            if(dialogResult[0]) {
                handler.merge();
                ModelRepositoryPlugin.getInstance().log(IStatus.INFO, "[RefreshModelAction] handler.merge() completed (pull)", null); //$NON-NLS-1$
            }
            // User cancelled - we assume they committed all changes so we can reset
            else {
                handler.resetToLocalState();
                return PULL_STATUS_MERGE_CANCEL;
            }
            
            // We now have to check if model can be reloaded
            monitor.subTask(Messages.RefreshModelAction_8);
            
            // Phase 1.5: detect and remove elements deleted by one parent but leaked via move
            if(oursIdBeforePull != null) {
                try(Git git = Git.open(getRepository().getLocalRepositoryFolder())) {
                    ObjectId theirsId = git.getRepository().resolve(
                            branchStatus.getCurrentRemoteBranch().getFullName());
                    if(theirsId != null) {
                        MergeConflictHandler.detectAndRemoveCrossPathDeletions(
                                git.getRepository(), oursIdBeforePull, theirsId);
                    }
                }
            }
            
            // Pre-repair: detect and resolve folder moves before loading the model
            loader.repairMissingFolderXml();
            loader.applyFolderMoveResolutions();
            
            // Reload the model from the Grafico XML files (must be on UI thread)
            try {
                final IOException[] loadEx = new IOException[1];
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
            }
            catch(IOException ex) {
            	handler.resetToLocalState(); // Clean up
            	throw ex;
            }
        } else { 
		    // Reload the model from the Grafico XML files
		    monitor.subTask(Messages.RefreshModelAction_8);
		    
		    // Phase 1.5: detect and remove elements deleted by one parent but leaked via move
		    if(oursIdBeforePull != null) {
		        phaseStart = System.nanoTime();
		        try(Git git = Git.open(getRepository().getLocalRepositoryFolder())) {
		            ObjectId theirsId = git.getRepository().resolve(
		                    branchStatus.getCurrentRemoteBranch().getFullName());
		            if(theirsId != null) {
		                MergeConflictHandler.detectAndRemoveCrossPathDeletions(
		                        git.getRepository(), oursIdBeforePull, theirsId);
		            }
		        }
		        logPerf("detectAndRemoveCrossPathDeletions", phaseStart); //$NON-NLS-1$
		    }
		    
		    // Pre-repair: detect folder moves before loading the model
		    phaseStart = System.nanoTime();
		    loader.repairMissingFolderXml();
		    logPerf("repairMissingFolderXml", phaseStart); //$NON-NLS-1$
		    
		    // Show folder move resolution dialog if moves were detected
		    if(loader.hasPendingFolderMoves()) {
		        Display.getDefault().syncExec(() -> {
		            FolderMoveResolutionDialog moveDialog = new FolderMoveResolutionDialog(
		                    fWindow.getShell(), loader.getFolderMoves());
		            moveDialog.open();
		        });
		    }
		    
		    // Apply the user's choices (or defaults if no dialog was needed)
		    phaseStart = System.nanoTime();
		    loader.applyFolderMoveResolutions();
		    logPerf("applyFolderMoveResolutions", phaseStart); //$NON-NLS-1$
		    
		    // Reload the model from the Grafico XML files (must be on UI thread)
		    phaseStart = System.nanoTime();
		    final IOException[] loadEx = new IOException[1];
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
		    logPerf("loadModel (import GRAFICO)", phaseStart); //$NON-NLS-1$
        }
        
        // Do a commit if needed
        phaseStart = System.nanoTime();
        boolean hasChanges = getRepository().hasChangesToCommit();
        logPerf("hasChangesToCommit", phaseStart); //$NON-NLS-1$
        ModelRepositoryPlugin.getInstance().log(IStatus.INFO, "[RefreshModelAction] hasChangesToCommit=" + hasChanges + " (pull path)", null); //$NON-NLS-1$ //$NON-NLS-2$
        java.io.File mergeHead = new java.io.File(getRepository().getLocalRepositoryFolder(), ".git/MERGE_HEAD"); //$NON-NLS-1$
        ModelRepositoryPlugin.getInstance().log(IStatus.INFO, "[RefreshModelAction] MERGE_HEAD exists=" + mergeHead.exists(), null); //$NON-NLS-1$
        if(hasChanges || mergeHead.exists()) {
            monitor.subTask(Messages.RefreshModelAction_9);
            
            String commitMessage = NLS.bind(Messages.RefreshModelAction_1, branchStatus.getCurrentLocalBranch().getShortName());
            
            // Did we restore any missing objects?
            String restoredObjects = loader.getRestoredObjectsAsString();
            
            // Add to commit message
            if(restoredObjects != null) {
                commitMessage += "\n\n" + Messages.RefreshModelAction_3 + "\n" + restoredObjects; //$NON-NLS-1$ //$NON-NLS-2$
            }
            
            // Did we repair any missing folder.xml files?
            String repairDetails = loader.getRepairDetailsAsString();
            if(repairDetails != null) {
                commitMessage += "\n" + repairDetails; //$NON-NLS-1$
            }

            phaseStart = System.nanoTime();
            // TODO - not sure if amend should be false or true here?
            getRepository().commitChanges(commitMessage, false);
            logPerf("commitChanges", phaseStart); //$NON-NLS-1$
        }
        
        logPerf("=== TOTAL PULL ===", pullStart); //$NON-NLS-1$
        
        return PULL_STATUS_OK;
    }
    
    private static void logPerf(String phase, long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        ModelRepositoryPlugin.getInstance().log(IStatus.INFO,
                "[RefreshModelAction] " + phase + ": " + ms + "ms", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
