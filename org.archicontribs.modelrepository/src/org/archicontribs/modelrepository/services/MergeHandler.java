/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.services;

import java.io.IOException;
import java.util.List;

import org.archicontribs.modelrepository.grafico.FolderMoveInfo;
import org.archicontribs.modelrepository.grafico.GraficoModelLoader;
import org.archicontribs.modelrepository.merge.MergeConflictHandler;
import org.eclipse.core.runtime.IProgressMonitor;

/**
 * Strategy interface for merge-related user interactions.
 * <p>
 * This abstracts all UI decisions required during a refresh/pull operation,
 * allowing the same core workflow to run interactively (with dialogs) or
 * headlessly (CLI, automated pipelines).
 * <p>
 * Implementations:
 * <ul>
 *   <li>{@code InteractiveMergeHandler} (in actions package) — shows dialogs on the SWT UI thread</li>
 *   <li>{@code HeadlessMergeHandler} (in services package) — aborts on conflicts or auto-resolves</li>
 * </ul>
 * 
 * @see <a href="../../../docs/adr/0006-service-layer.md">ADR-0006: Service Layer</a>
 */
public interface MergeHandler {

    /**
     * Resolve merge conflicts interactively or automatically.
     * <p>
     * The handler has already been initialized via {@code handler.init(monitor)}.
     * This method should either:
     * <ul>
     *   <li>Show a conflict resolution dialog and return the user's choice, OR</li>
     *   <li>Automatically choose ours/theirs and return true, OR</li>
     *   <li>Return false to cancel the merge</li>
     * </ul>
     * 
     * @param handler the initialized merge conflict handler
     * @param dialogMessage a human-readable message describing the conflict
     * @return true if conflicts were resolved (call handler.merge()), false to cancel (call handler.resetToLocalState())
     */
    boolean resolveConflicts(MergeConflictHandler handler, String dialogMessage);

    /**
     * Resolve folder move conflicts detected during merge repair.
     * <p>
     * Called when the same folder exists at two locations after a merge
     * (one branch moved it, the other modified elements in it).
     * The implementation should set each {@link FolderMoveInfo#setUserChoice(int)}
     * before returning.
     * 
     * @param folderMoves the detected folder moves needing resolution
     */
    void resolveFolderMoves(List<FolderMoveInfo> folderMoves);

    /**
     * Reload the model from GRAFICO files.
     * <p>
     * In interactive mode, this MUST run on the SWT UI thread because EMF
     * model operations and editor updates require it.
     * In headless mode, this can run on any thread.
     * 
     * @param loader the model loader
     * @param monitor progress monitor
     * @throws IOException if the model cannot be loaded
     */
    void reloadModel(GraficoModelLoader loader, IProgressMonitor monitor) throws IOException;
}
