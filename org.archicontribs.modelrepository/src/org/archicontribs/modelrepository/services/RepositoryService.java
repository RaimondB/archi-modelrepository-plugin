/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.services;

import java.io.IOException;

import org.archicontribs.modelrepository.grafico.GraficoModelExporter;
import org.archicontribs.modelrepository.grafico.IArchiRepository;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;

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
}
