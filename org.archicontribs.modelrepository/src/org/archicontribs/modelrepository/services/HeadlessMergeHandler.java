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
 * Headless merge handler for CLI and automated pipelines.
 * <p>
 * Aborts on conflicts (returns false) and uses default choices for folder moves.
 * Model reload is done directly on the current thread.
 */
public class HeadlessMergeHandler implements MergeHandler {

    @Override
    public boolean resolveConflicts(MergeConflictHandler handler, String dialogMessage) {
        // Headless mode cannot show a conflict dialog — abort the merge
        return false;
    }

    @Override
    public void resolveFolderMoves(List<FolderMoveInfo> folderMoves) {
        // Use default choices (KEEP_NEW_LOCATION) — no user interaction needed
    }

    @Override
    public void reloadModel(GraficoModelLoader loader, IProgressMonitor monitor) throws IOException {
        loader.loadModel(monitor);
    }
}
