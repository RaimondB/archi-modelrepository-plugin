/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.services;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.archicontribs.modelrepository.authentication.UsernamePassword;
import org.archicontribs.modelrepository.grafico.ArchiRepository;
import org.archicontribs.modelrepository.grafico.BranchInfo;
import org.archicontribs.modelrepository.grafico.BranchStatus;
import org.archicontribs.modelrepository.grafico.GraficoModelExporter;
import org.archicontribs.modelrepository.grafico.GraficoModelLoader;
import org.archicontribs.modelrepository.grafico.IArchiRepository;
import org.archicontribs.modelrepository.grafico.IGraficoConstants;
import org.archicontribs.modelrepository.grafico.ProgressMonitorWrapper;
import org.archicontribs.modelrepository.merge.MergeConflictHandler;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.MergeResult.MergeStatus;
import org.eclipse.jgit.api.errors.CanceledException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefNotAdvertisedException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.osgi.util.NLS;

import com.archimatetool.editor.utils.StringUtils;
import com.archimatetool.model.IArchimateModel;

/**
 * Headless-safe service for repository operations.
 * <p>
 * This class contains workflow logic extracted from the UI action classes.
 * It must NOT import SWT, JFace, or PlatformUI — all UI decisions are
 * delegated to callers via strategy interfaces or parameters.
 * <p>
 * See ADR-0006 for the rationale behind this layer.
 * 
 * @see <a href="../../../docs/adr/0006-service-layer.md">ADR-0006: Service Layer</a>
 */
public class RepositoryService {

    /**
     * Result of a commit operation.
     * 
     * @param status the outcome
     * @param commitId the full commit SHA-1 (40 hex chars), or null if nothing committed
     */
    public record CommitResult(Status status, String commitId) {
        public enum Status {
            /** Commit was created successfully */
            COMMITTED,
            /** No changes to commit */
            NOTHING_TO_COMMIT
        }
    }

    /**
     * Result of a refresh (pull) operation.
     * 
     * @param status the outcome
     */
    public record RefreshResult(Status status) {
        public enum Status {
            /** Pull and merge succeeded */
            OK,
            /** Already up to date — nothing fetched */
            UP_TO_DATE,
            /** User cancelled during conflict resolution */
            MERGE_CANCELLED,
            /** An error occurred */
            ERROR
        }
    }

    /**
     * Result of a publish (pull + push) operation.
     * 
     * @param status the outcome
     * @param pushErrors push error messages, or null if no errors
     */
    public record PublishResult(Status status, String pushErrors) {
        public enum Status {
            /** Publish succeeded */
            OK,
            /** Already up to date (no pull needed, push may or may not have run) */
            UP_TO_DATE,
            /** User cancelled during conflict resolution */
            MERGE_CANCELLED,
            /** Push had errors */
            PUSH_ERROR
        }
    }

    /**
     * Result of a switch branch operation.
     * 
     * @param status the outcome
     */
    public record SwitchResult(Status status) {
        public enum Status {
            /** Branch switch succeeded */
            OK,
            /** Already on the requested branch */
            ALREADY_ON_BRANCH
        }
    }

    /**
     * Result of a merge branch operation.
     * 
     * @param status the outcome
     * @param conflictCount number of conflicts resolved (0 if clean merge or fast-forward)
     */
    public record MergeBranchResult(Status status, int conflictCount) {
        public enum Status {
            /** Merge succeeded */
            OK,
            /** Already up to date — nothing to merge */
            UP_TO_DATE,
            /** User cancelled during conflict resolution */
            MERGE_CANCELLED,
            /** An error occurred */
            ERROR
        }
    }
    
    /**
     * Commit staged changes with the given message.
     * <p>
     * In the UI flow, call {@code repo.exportModelToGraficoFiles()} first 
     * to export and stage changes, then show the commit dialog, then call this.
     * <p>
     * For headless use, prefer {@link #commit(IArchiRepository, IArchimateModel, String, boolean, IProgressMonitor)}
     * which handles export + stage + commit in one step.
     * 
     * @param repo the repository
     * @param commitMessage the commit message
     * @param amend if true, amend the previous commit
     * @return the commit result
     * @throws IOException on I/O errors
     * @throws GitAPIException on git errors
     */
    public CommitResult commitChanges(IArchiRepository repo, String commitMessage,
            boolean amend) throws IOException, GitAPIException {
        
        RevCommit commit = repo.commitChanges(commitMessage, amend);
        
        if(commit == null) {
            return new CommitResult(CommitResult.Status.NOTHING_TO_COMMIT, null);
        }
        
        // Save checksum
        repo.saveChecksum();
        
        return new CommitResult(CommitResult.Status.COMMITTED, commit.getName());
    }
    
    /**
     * Export the model to GRAFICO files, stage changes, and commit in one step.
     * <p>
     * This is the headless-safe equivalent of CommitModelAction — suitable for
     * CLI use where no interactive dialog is needed.
     * The caller is responsible for:
     * <ul>
     *   <li>Saving the model if dirty (UI concern)</li>
     *   <li>Providing the commit message (via CLI arg)</li>
     *   <li>Displaying the result to the user</li>
     * </ul>
     * 
     * @param repo the repository to commit to
     * @param model the model to export (must already be saved)
     * @param commitMessage the commit message
     * @param amend if true, amend the previous commit
     * @param monitor progress monitor (may be null)
     * @return the commit result indicating what happened
     * @throws IOException on I/O errors
     * @throws GitAPIException on git errors
     */
    public CommitResult commit(IArchiRepository repo, IArchimateModel model,
            String commitMessage, boolean amend, IProgressMonitor monitor) throws IOException, GitAPIException {
        
        SubMonitor progress = SubMonitor.convert(monitor, 100);
        
        // Export model to GRAFICO format (80% of progress)
        // See ADR-0003: Use export return value for change detection.
        GraficoModelExporter exporter = new GraficoModelExporter(model, repo.getLocalRepositoryFolder());
        boolean hasChanges = exporter.exportModel(progress.split(80));
        
        if(!hasChanges) {
            // Also check for pre-existing unstaged changes (e.g., manual file edits)
            hasChanges = repo.hasChangesToCommit();
        }
        
        if(!hasChanges) {
            return new CommitResult(CommitResult.Status.NOTHING_TO_COMMIT, null);
        }
        
        // commitChanges() handles staging (gitAdd) and commit internally
        RevCommit commit = repo.commitChanges(commitMessage, amend);
        
        // Save checksum
        repo.saveChecksum();
        
        progress.worked(20);
        
        return new CommitResult(CommitResult.Status.COMMITTED, commit.getName());
    }

    /**
     * Fetch from remote, merge, handle conflicts, and reload the model.
     * <p>
     * This is the core workflow extracted from {@code RefreshModelAction.pull()}.
     * All UI interactions are delegated to the {@link MergeHandler} strategy.
     * <p>
     * The caller is responsible for:
     * <ul>
     *   <li>Saving the model if dirty</li>
     *   <li>Exporting to GRAFICO and committing staged changes before calling this</li>
     *   <li>Providing credentials</li>
     *   <li>Updating proxy settings</li>
     *   <li>Saving checksum and notifying listeners after this completes</li>
     * </ul>
     * 
     * @param repo the repository to refresh
     * @param npw credentials for remote operations (may be null for native git/GCM)
     * @param mergeHandler strategy for resolving conflicts and reloading the model
     * @param monitor progress monitor
     * @return the refresh result
     * @throws IOException on I/O errors
     * @throws GitAPIException on git errors
     */
    public RefreshResult refresh(IArchiRepository repo, UsernamePassword npw,
            MergeHandler mergeHandler, IProgressMonitor monitor) throws IOException, GitAPIException {
        long pullStart = System.nanoTime();
        long phaseStart;

        // Capture HEAD before pull for cross-path deletion detection
        ObjectId oursIdBeforePull = null;
        phaseStart = System.nanoTime();
        try(Git git = Git.open(repo.getLocalRepositoryFolder())) {
            oursIdBeforePull = git.getRepository().resolve(IGraficoConstants.HEAD);
        }
        logPerf("Resolve HEAD before pull", phaseStart); //$NON-NLS-1$

        // Phase 1: Fetch from remote
        monitor.subTask(Messages.RepositoryService_0);
        FetchResult fetchResult;
        phaseStart = System.nanoTime();
        try {
            fetchResult = repo.fetchFromRemote(npw, new ProgressMonitorWrapper(monitor), false);
        }
        catch(Exception ex) {
            if(ex instanceof RefNotAdvertisedException) {
                return new RefreshResult(RefreshResult.Status.OK);
            }
            throw ex;
        }
        logPerf("fetchFromRemote", phaseStart); //$NON-NLS-1$

        // fetchResult is null when native git handled the fetch — assume refs may have changed
        boolean newTrackingRefUpdates = fetchResult == null || !fetchResult.getTrackingRefUpdates().isEmpty();

        // Phase 2: Determine if merge is needed
        phaseStart = System.nanoTime();
        MergeResult mergeResult = null;
        String remoteBranch;

        try(Git git = Git.open(repo.getLocalRepositoryFolder())) {
            Repository repository = git.getRepository();
            String currentBranch = repository.getBranch();
            remoteBranch = IGraficoConstants.ORIGIN + "/" + currentBranch; //$NON-NLS-1$
            ObjectId remoteId = repository.resolve(Constants.R_REMOTES + remoteBranch);
            ObjectId headId = repository.resolve(IGraficoConstants.HEAD);

            if(remoteId == null || (headId != null && headId.equals(remoteId))) {
                logPerf("merge (already up to date)", phaseStart); //$NON-NLS-1$
                if(newTrackingRefUpdates) {
                    return new RefreshResult(RefreshResult.Status.OK);
                }
                return new RefreshResult(RefreshResult.Status.UP_TO_DATE);
            }
        }

        // Merge — ArchiRepository handles native git / JGit switching internally
        monitor.subTask(Messages.RepositoryService_1);
        mergeResult = ((ArchiRepository) repo).merge(remoteBranch, monitor);
        logPerf("merge", phaseStart); //$NON-NLS-1$

        phaseStart = System.nanoTime();
        BranchStatus branchStatus = repo.getBranchStatus();
        logPerf("getBranchStatus", phaseStart); //$NON-NLS-1$

        // Setup the Grafico Model Loader
        GraficoModelLoader loader = new GraficoModelLoader(repo);

        // Track conflict count for commit message
        int conflictCount = 0;

        // Merge failure — only possible if JGit merge was used (native merge was either clean or aborted+retried)
        if(mergeResult != null && mergeResult.getMergeStatus() == MergeStatus.CONFLICTING) {
            conflictCount = mergeResult.getConflicts() != null ? mergeResult.getConflicts().size() : 0;
            monitor.subTask(NLS.bind(Messages.RepositoryService_2, conflictCount));

            // Get the remote ref name
            String remoteRef = branchStatus.getCurrentRemoteBranch().getFullName();

            // Try to handle the merge conflict
            MergeConflictHandler handler = new MergeConflictHandler(mergeResult, remoteRef,
                    repo, null); // Shell is null — UI interaction via MergeHandler strategy

            try {
                handler.init(monitor);
            }
            catch(IOException | GitAPIException ex) {
                handler.resetToLocalState();

                if(ex instanceof CanceledException) {
                    return new RefreshResult(RefreshResult.Status.MERGE_CANCELLED);
                }

                throw ex;
            }

            String dialogMessage = NLS.bind(Messages.RepositoryService_2,
                    branchStatus.getCurrentLocalBranch().getShortName());

            // Delegate to MergeHandler strategy for conflict resolution
            boolean resolved = mergeHandler.resolveConflicts(handler, dialogMessage);

            if(resolved) {
                handler.merge();
                log(IStatus.INFO, "[RepositoryService] handler.merge() completed (refresh)"); //$NON-NLS-1$
            }
            else {
                handler.resetToLocalState();
                return new RefreshResult(RefreshResult.Status.MERGE_CANCELLED);
            }

            // Conflicting merges are resolved by explicit user choices in handler.merge().
            // Skip cross-path deletion cleanup here so we don't override chosen outcomes.
            Set<String> impactedModelDirs = collectMergeImpactedModelDirs(repo, oursIdBeforePull, branchStatus);

            // Pre-repair: detect and resolve folder moves before loading the model
            loader.repairMissingFolderXml(impactedModelDirs);
            loader.applyFolderMoveResolutions();

            // Reload the model (delegated to MergeHandler — interactive mode needs UI thread)
            monitor.subTask(Messages.RepositoryService_4);
            try {
                mergeHandler.reloadModel(loader, monitor);
            }
            catch(IOException ex) {
                handler.resetToLocalState();
                throw ex;
            }
        }
        else {
            // Clean merge path
            monitor.subTask(Messages.RepositoryService_3);
            Set<String> impactedModelDirs = collectMergeImpactedModelDirs(repo, oursIdBeforePull, branchStatus);
            detectAndRemoveCrossPathDeletions(repo, oursIdBeforePull, branchStatus, impactedModelDirs);

            // Pre-repair: detect folder moves before loading the model
            phaseStart = System.nanoTime();
            loader.repairMissingFolderXml(impactedModelDirs);
            logPerf("repairMissingFolderXml", phaseStart); //$NON-NLS-1$

            // Delegate folder move resolution to MergeHandler
            if(loader.hasPendingFolderMoves()) {
                mergeHandler.resolveFolderMoves(loader.getFolderMoves());
            }

            // Apply the user's choices (or defaults if no dialog was needed)
            phaseStart = System.nanoTime();
            loader.applyFolderMoveResolutions();
            logPerf("applyFolderMoveResolutions", phaseStart); //$NON-NLS-1$

            // Reload the model (delegated to MergeHandler — interactive mode needs UI thread)
            monitor.subTask(Messages.RepositoryService_4);
            phaseStart = System.nanoTime();
            mergeHandler.reloadModel(loader, monitor);
            logPerf("loadModel (import GRAFICO)", phaseStart); //$NON-NLS-1$
        }

        // Do a commit if needed
        phaseStart = System.nanoTime();
        boolean hasChanges = repo.hasChangesToCommit();
        logPerf("hasChangesToCommit", phaseStart); //$NON-NLS-1$
        log(IStatus.INFO, "[RepositoryService] hasChangesToCommit=" + hasChanges + " (refresh)"); //$NON-NLS-1$ //$NON-NLS-2$

        File mergeHead = new File(repo.getLocalRepositoryFolder(), ".git/MERGE_HEAD"); //$NON-NLS-1$
        log(IStatus.INFO, "[RepositoryService] MERGE_HEAD exists=" + mergeHead.exists()); //$NON-NLS-1$

        if(hasChanges || mergeHead.exists()) {
            monitor.subTask(Messages.RepositoryService_5);

            String commitMessage;
            if(conflictCount > 0) {
                commitMessage = NLS.bind(Messages.RepositoryService_6,
                        branchStatus.getCurrentLocalBranch().getShortName(), conflictCount);
            }
            else {
                commitMessage = NLS.bind(Messages.RepositoryService_7,
                        branchStatus.getCurrentLocalBranch().getShortName());
            }

            // Append restored objects info
            String restoredObjects = loader.getRestoredObjectsAsString();
            if(restoredObjects != null) {
                commitMessage += "\n\n" + restoredObjects; //$NON-NLS-1$
            }

            // Append repair details
            String repairDetails = loader.getRepairDetailsAsString();
            if(repairDetails != null) {
                commitMessage += "\n" + repairDetails; //$NON-NLS-1$
            }

            phaseStart = System.nanoTime();
            repo.commitChanges(commitMessage, false);
            logPerf("commitChanges", phaseStart); //$NON-NLS-1$
        }

        logPerf("=== TOTAL REFRESH ===", pullStart); //$NON-NLS-1$

        return new RefreshResult(RefreshResult.Status.OK);
    }

    /**
     * Refresh (pull) then push to remote.
     * <p>
     * This is the core workflow extracted from {@code PushModelAction}.
     * The caller is responsible for the same prerequisites as {@link #refresh}.
     * 
     * @param repo the repository to publish
     * @param npw credentials for remote operations (may be null for native git/GCM)
     * @param mergeHandler strategy for resolving conflicts and reloading the model
     * @param monitor progress monitor
     * @return the publish result
     * @throws IOException on I/O errors
     * @throws GitAPIException on git errors
     */
    public PublishResult publish(IArchiRepository repo, UsernamePassword npw,
            MergeHandler mergeHandler, IProgressMonitor monitor) throws IOException, GitAPIException {

        // Pull first
        RefreshResult refreshResult = refresh(repo, npw, mergeHandler, monitor);

        // Only push if pull succeeded
        if(refreshResult.status() != RefreshResult.Status.OK
                && refreshResult.status() != RefreshResult.Status.UP_TO_DATE) {
            return new PublishResult(
                    refreshResult.status() == RefreshResult.Status.MERGE_CANCELLED
                            ? PublishResult.Status.MERGE_CANCELLED
                            : PublishResult.Status.PUSH_ERROR,
                    null);
        }

        // Push
        monitor.subTask(Messages.RepositoryService_8);
        Iterable<PushResult> pushResult = repo.pushToRemote(npw, new ProgressMonitorWrapper(monitor));

        // Check for push errors (null when native git handled the push)
        StringBuilder sb = new StringBuilder();
        if(pushResult != null) {
            pushResult.forEach(result -> {
                result.getRemoteUpdates().stream()
                        .filter(update -> update.getStatus() != RemoteRefUpdate.Status.OK)
                        .filter(update -> update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE)
                        .forEach(update -> {
                            sb.append(update.getStatus().name()).append("\n"); //$NON-NLS-1$
                            sb.append(update.getRemoteName()).append("\n"); //$NON-NLS-1$

                            String msgs = result.getMessages();
                            if(StringUtils.isSet(msgs)) {
                                if(msgs.charAt(0) == 0) {
                                    msgs = msgs.substring(1);
                                }
                                sb.append(msgs).append("\n"); //$NON-NLS-1$
                            }
                        });
            });
        }

        if(sb.length() != 0) {
            return new PublishResult(PublishResult.Status.PUSH_ERROR, sb.toString());
        }

        return new PublishResult(PublishResult.Status.OK, null);
    }

    /**
     * Switch to a different branch.
     * <p>
     * This is the headless-safe workflow extracted from {@code SwitchBranchAction.switchBranch()}.
     * Creates a local tracking branch if the target is remote-only, performs git checkout,
     * optionally reloads the model via the {@link MergeHandler}, and saves the checksum.
     * <p>
     * The caller is responsible for:
     * <ul>
     *   <li>Saving the model if dirty</li>
     *   <li>Exporting to GRAFICO and committing (or resetting) before calling this</li>
     *   <li>Notifying UI listeners after this completes</li>
     * </ul>
     * 
     * @param repo the repository
     * @param branchInfo the branch to switch to
     * @param doReloadModel whether to reload the model after checkout
     * @param mergeHandler strategy for reloading the model (needed when doReloadModel is true)
     * @param monitor progress monitor (may be null)
     * @return the switch result
     * @throws IOException on I/O errors
     * @throws GitAPIException on git errors
     */
    public SwitchResult switchBranch(IArchiRepository repo, BranchInfo branchInfo,
            boolean doReloadModel, MergeHandler mergeHandler,
            IProgressMonitor monitor) throws IOException, GitAPIException {
        
        SubMonitor progress = SubMonitor.convert(monitor, 100);
        File repoFolder = repo.getLocalRepositoryFolder();

        // If the branch is remote and has no local ref, create local tracking branch
        if(branchInfo.isRemote() && !branchInfo.hasLocalRef()) {
            try(Git git = Git.open(repoFolder)) {
                git.branchCreate()
                        .setName(branchInfo.getShortName())
                        .setStartPoint(branchInfo.getFullName())
                        .call();
            }
        }

        // Determine the branch name for checkout
        String branchName = branchInfo.isLocal()
                ? branchInfo.getFullName() : branchInfo.getShortName();

        // Git checkout (tries native git, falls back to JGit)
        long phaseStart = System.nanoTime();
        progress.subTask(Messages.RepositoryService_9);
        repo.checkoutBranch(branchName);
        logPerf("checkoutBranch", phaseStart); //$NON-NLS-1$
        progress.worked(30);

        // Reload the model if requested
        if(doReloadModel && mergeHandler != null) {
            phaseStart = System.nanoTime();
            progress.subTask(Messages.RepositoryService_4);
            GraficoModelLoader loader = new GraficoModelLoader(repo);
            mergeHandler.reloadModel(loader, progress.split(60));
            logPerf("reloadModel (switch)", phaseStart); //$NON-NLS-1$
        }

        // Save the checksum
        repo.saveChecksum();
        progress.worked(10);

        return new SwitchResult(SwitchResult.Status.OK);
    }

    /**
     * Merge a local branch into the current branch.
     * <p>
     * This is the core merge workflow extracted from {@code MergeBranchAction.merge()}.
     * Handles conflict resolution via the {@link MergeHandler} strategy, cross-path
     * deletion detection, folder repair, model reload, and final commit.
     * <p>
     * The caller is responsible for:
     * <ul>
     *   <li>Saving the model if dirty</li>
     *   <li>Exporting to GRAFICO and committing before calling this</li>
     *   <li>Notifying UI listeners after this completes</li>
     * </ul>
     * 
     * @param repo the repository
     * @param currentBranch the current branch (merge target)
     * @param branchToMerge the branch to merge into the current branch
     * @param mergeHandler strategy for resolving conflicts and reloading the model
     * @param monitor progress monitor
     * @return the merge result
     * @throws IOException on I/O errors
     * @throws GitAPIException on git errors
     */
    @SuppressWarnings("nls")
    public MergeBranchResult mergeBranch(IArchiRepository repo, BranchInfo currentBranch,
            BranchInfo branchToMerge, MergeHandler mergeHandler,
            IProgressMonitor monitor) throws IOException, GitAPIException {
        
        long mergeStart = System.nanoTime();
        long phaseStart;
        int conflictCount = 0;

        monitor.subTask(NLS.bind(Messages.RepositoryService_10,
                branchToMerge.getShortName(), currentBranch.getShortName()));

        try(Git git = Git.open(repo.getLocalRepositoryFolder())) {
            ObjectId oursId = git.getRepository().resolve(IGraficoConstants.HEAD);
            ObjectId theirsId = git.getRepository().resolve(branchToMerge.getShortName());

            String mergeMessage = NLS.bind(Messages.RepositoryService_11,
                    branchToMerge.getShortName(), currentBranch.getShortName());

            // Merge — ArchiRepository handles native git / JGit switching
            phaseStart = System.nanoTime();
            MergeResult mergeResult = ((ArchiRepository) repo).mergeBranch(
                    branchToMerge.getShortName(), mergeMessage, monitor);
            logPerf("mergeBranch", phaseStart);

            // null = native git performed a clean merge
            MergeStatus status = mergeResult != null ? mergeResult.getMergeStatus() : null;

            if(status == MergeStatus.ALREADY_UP_TO_DATE) {
                return new MergeBranchResult(MergeBranchResult.Status.UP_TO_DATE, 0);
            }

            // Handle conflicts
            if(status == MergeStatus.CONFLICTING) {
                conflictCount = mergeResult.getConflicts() != null ? mergeResult.getConflicts().size() : 0;
                monitor.subTask(NLS.bind(Messages.RepositoryService_2, conflictCount));

                MergeConflictHandler handler = new MergeConflictHandler(mergeResult,
                        branchToMerge.getShortName(), repo, null);

                try {
                    handler.init(monitor);
                }
                catch(IOException | GitAPIException ex) {
                    handler.resetToLocalState();
                    if(ex instanceof CanceledException) {
                        return new MergeBranchResult(MergeBranchResult.Status.MERGE_CANCELLED, 0);
                    }
                    throw ex;
                }

                String dialogMessage = NLS.bind(Messages.RepositoryService_12,
                        branchToMerge.getShortName(), currentBranch.getShortName());

                boolean resolved = mergeHandler.resolveConflicts(handler, dialogMessage);

                if(resolved) {
                    handler.merge();
                    log(IStatus.INFO, "[RepositoryService] handler.merge() completed (mergeBranch)");
                }
                else {
                    handler.resetToLocalState();
                    return new MergeBranchResult(MergeBranchResult.Status.MERGE_CANCELLED, 0);
                }
            }

            Set<String> impactedModelDirs = collectMergeImpactedModelDirs(git.getRepository(), oursId, theirsId);

            // Cross-path deletion detection is safe for clean merges. For conflicting
            // merges, user conflict choices are authoritative, so skip this cleanup.
            if(shouldRunCrossPathDeletion(status)) {
                monitor.subTask(Messages.RepositoryService_3);
                phaseStart = System.nanoTime();
                if(oursId != null && theirsId != null) {
                    int removed = MergeConflictHandler.detectAndRemoveCrossPathDeletions(
                            git.getRepository(), oursId, theirsId, impactedModelDirs);
                    log(IStatus.INFO, "[RepositoryService] detectAndRemoveCrossPathDeletions: removed=" + removed);
                }
                logPerf("detectAndRemoveCrossPathDeletions (mergeBranch)", phaseStart);
            }

            // Folder repair
            GraficoModelLoader loader = new GraficoModelLoader(repo);
            phaseStart = System.nanoTime();
            loader.repairMissingFolderXml(impactedModelDirs);
            logPerf("repairMissingFolderXml", phaseStart);

            if(loader.hasPendingFolderMoves()) {
                mergeHandler.resolveFolderMoves(loader.getFolderMoves());
            }

            phaseStart = System.nanoTime();
            loader.applyFolderMoveResolutions();
            logPerf("applyFolderMoveResolutions", phaseStart);

            // Reload the model
            monitor.subTask(Messages.RepositoryService_4);
            phaseStart = System.nanoTime();
            mergeHandler.reloadModel(loader, monitor);
            logPerf("reloadModel (mergeBranch)", phaseStart);

            // Commit if needed
            phaseStart = System.nanoTime();
            boolean hasChanges = repo.hasChangesToCommit();
            logPerf("hasChangesToCommit", phaseStart);

            File mergeHead = new File(repo.getLocalRepositoryFolder(), ".git/MERGE_HEAD");

            if(hasChanges || mergeHead.exists()) {
                monitor.subTask(Messages.RepositoryService_5);

                if(conflictCount > 0) {
                    mergeMessage = NLS.bind(Messages.RepositoryService_13,
                            new Object[] { branchToMerge.getShortName(),
                                           currentBranch.getShortName(), conflictCount });
                }

                String restoredObjects = loader.getRestoredObjectsAsString();
                if(restoredObjects != null) {
                    mergeMessage += "\n\n" + restoredObjects;
                }

                String repairDetails = loader.getRepairDetailsAsString();
                if(repairDetails != null) {
                    mergeMessage += "\n" + repairDetails;
                }

                phaseStart = System.nanoTime();
                repo.commitChanges(mergeMessage, false);
                logPerf("commitChanges (mergeBranch)", phaseStart);
            }
        }

        logPerf("=== TOTAL MERGE BRANCH ===", mergeStart);

        return new MergeBranchResult(MergeBranchResult.Status.OK, conflictCount);
    }

    // ---- Private helpers ----

    private void detectAndRemoveCrossPathDeletions(IArchiRepository repo,
            ObjectId oursIdBeforePull, BranchStatus branchStatus,
            Set<String> impactedModelDirs) throws IOException, GitAPIException {
        if(oursIdBeforePull != null) {
            long phaseStart = System.nanoTime();
            try(Git git = Git.open(repo.getLocalRepositoryFolder())) {
                ObjectId theirsId = git.getRepository().resolve(
                        branchStatus.getCurrentRemoteBranch().getFullName());
                if(theirsId != null) {
                    MergeConflictHandler.detectAndRemoveCrossPathDeletions(
                            git.getRepository(), oursIdBeforePull, theirsId, impactedModelDirs);
                }
            }
            logPerf("detectAndRemoveCrossPathDeletions", phaseStart); //$NON-NLS-1$
        }
    }

    private Set<String> collectMergeImpactedModelDirs(IArchiRepository repo,
            ObjectId oursIdBeforePull, BranchStatus branchStatus) throws IOException {
        if(oursIdBeforePull == null || branchStatus == null || branchStatus.getCurrentRemoteBranch() == null) {
            return null;
        }

        try(Git git = Git.open(repo.getLocalRepositoryFolder())) {
            ObjectId theirsId = git.getRepository().resolve(
                    branchStatus.getCurrentRemoteBranch().getFullName());
            return collectMergeImpactedModelDirs(git.getRepository(), oursIdBeforePull, theirsId);
        }
    }

    private Set<String> collectMergeImpactedModelDirs(Repository repository,
            ObjectId oursCommitId, ObjectId theirsCommitId) throws IOException {
        if(repository == null || oursCommitId == null || theirsCommitId == null) {
            return null;
        }

        Set<String> impactedModelDirs = new HashSet<>();
        ArchiRepository.collectMergeImpactedModelDirs(
                repository, oursCommitId, theirsCommitId, impactedModelDirs);

        log(IStatus.INFO, "[RepositoryService] impacted model dirs=" + impactedModelDirs.size()); //$NON-NLS-1$
        return impactedModelDirs;
    }

    /**
     * Cross-path deletion cleanup is safe for clean merges, but must be skipped
     * for conflicting merges where explicit user conflict choices are authoritative.
     */
    static boolean shouldRunCrossPathDeletion(MergeStatus status) {
        return status != MergeStatus.CONFLICTING;
    }

    private static void logPerf(String phase, long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        log(IStatus.INFO, "[RepositoryService] " + phase + ": " + ms + "ms"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static void log(int severity, String message) {
        ModelRepositoryPlugin plugin = ModelRepositoryPlugin.getInstance();
        if(plugin != null) {
            plugin.log(severity, message, null);
        }
    }
}
