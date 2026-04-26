/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.actions;

import java.io.IOException;
import java.util.List;

import org.archicontribs.modelrepository.grafico.FolderMoveInfo;
import org.archicontribs.modelrepository.grafico.GraficoModelLoader;
import org.archicontribs.modelrepository.merge.FolderMoveResolutionDialog;
import org.archicontribs.modelrepository.merge.MergeConflictHandler;
import org.archicontribs.modelrepository.services.MergeHandler;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

/**
 * Interactive merge handler that shows SWT dialogs for conflict resolution.
 * <p>
 * This implementation delegates to the UI thread via {@code Display.syncExec}
 * for all user interactions required during merge.
 */
public class InteractiveMergeHandler implements MergeHandler {

    private final Shell fShell;

    public InteractiveMergeHandler(Shell shell) {
        fShell = shell;
    }

    @Override
    public boolean resolveConflicts(MergeConflictHandler handler, String dialogMessage) {
        final boolean[] result = new boolean[1];
        Display.getDefault().syncExec(() -> {
            result[0] = handler.openConflictsDialog(dialogMessage);
        });
        return result[0];
    }

    @Override
    public void resolveFolderMoves(List<FolderMoveInfo> folderMoves) {
        Display.getDefault().syncExec(() -> {
            FolderMoveResolutionDialog moveDialog = new FolderMoveResolutionDialog(
                    fShell, folderMoves);
            moveDialog.open();
        });
    }

    @Override
    public void reloadModel(GraficoModelLoader loader, IProgressMonitor monitor) throws IOException {
        final IOException[] loadEx = new IOException[1];
        Display.getDefault().syncExec(() -> {
            try {
                loader.loadModel(monitor);
            }
            catch(IOException ex) {
                loadEx[0] = ex;
            }
        });
        if(loadEx[0] != null) {
            throw loadEx[0];
        }
    }
}
