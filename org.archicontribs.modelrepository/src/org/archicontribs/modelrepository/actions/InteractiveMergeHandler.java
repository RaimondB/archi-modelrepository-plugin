/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.actions;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.archicontribs.modelrepository.UIPerfLogger;
import org.archicontribs.modelrepository.grafico.FolderMoveInfo;
import org.archicontribs.modelrepository.grafico.GraficoModelLoader;
import org.archicontribs.modelrepository.grafico.GraficoModelLoader.ImportResult;
import org.archicontribs.modelrepository.merge.FolderMoveResolutionDialog;
import org.archicontribs.modelrepository.merge.MergeConflictHandler;
import org.archicontribs.modelrepository.services.MergeHandler;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.archimatetool.model.IArchimateModel;

/**
 * Interactive merge handler that shows SWT dialogs for conflict resolution.
 * <p>
 * This implementation delegates to the UI thread via {@code Display.syncExec}
 * for all user interactions required during merge.
 */
public class InteractiveMergeHandler implements MergeHandler {

    private static final long WATCHDOG_INTERVAL_MS = 15000;
    private static final int WATCHDOG_STACK_DEPTH = 20;
    private static final DateTimeFormatter WATCHDOG_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"); //$NON-NLS-1$
    private static final Path WATCHDOG_FILE = Path.of(System.getProperty("user.home"), "Archi", "interactive-merge-watchdog.log"); //$NON-NLS-1$ //$NON-NLS-2$

    private final Shell fShell;

    public InteractiveMergeHandler(Shell shell) {
        fShell = shell;
    }

    @Override
    public boolean resolveConflicts(MergeConflictHandler handler, String dialogMessage) {
        final boolean[] result = new boolean[1];
        try {
            runWithWatchdog("resolveConflicts(syncExec)", () -> Display.getDefault().syncExec(() -> { //$NON-NLS-1$
                result[0] = handler.openConflictsDialog(dialogMessage);
            }));
        }
        catch(IOException ex) {
            throw new RuntimeException(ex);
        }
        return result[0];
    }

    @Override
    public void resolveFolderMoves(List<FolderMoveInfo> folderMoves) {
        try {
            runWithWatchdog("resolveFolderMoves(syncExec)", () -> Display.getDefault().syncExec(() -> { //$NON-NLS-1$
                FolderMoveResolutionDialog moveDialog = new FolderMoveResolutionDialog(
                        fShell, folderMoves);
                moveDialog.open();
            }));
        }
        catch(IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void reloadModel(GraficoModelLoader loader, IProgressMonitor monitor) throws IOException {
        final IOException[] loadEx = new IOException[1];
        final ImportResult[] importResult = new ImportResult[1];
        final IArchimateModel[] preparedModel = new IArchimateModel[1];

        // Import in worker thread to keep UI responsive while outer progress dialog is active.
        if(monitor != null) {
            monitor.subTask(Messages.InteractiveMergeHandler_0);
        }
        long tImport = System.nanoTime();
        runWithWatchdog("importModelOnly", () -> importResult[0] = loader.importModelOnly(monitor)); //$NON-NLS-1$
        UIPerfLogger.log("[InteractiveMerge]", "importModelOnly", tImport); //$NON-NLS-1$ //$NON-NLS-2$

        // Prepare model content (repair endpoints, remove orphans) on worker thread.
        if(monitor != null) {
            monitor.subTask(Messages.InteractiveMergeHandler_1);
        }
        long tPrepare = System.nanoTime();
        runWithWatchdog("prepareModelForOpen", () -> preparedModel[0] = loader.prepareModelForOpen(importResult[0].model(), importResult[0].importer())); //$NON-NLS-1$
        UIPerfLogger.log("[InteractiveMerge]", "prepareModelForOpen", tPrepare); //$NON-NLS-1$ //$NON-NLS-2$

        // Saving still fires editor listeners, so it must run on the SWT UI thread.
        if(monitor != null) {
            monitor.subTask(Messages.InteractiveMergeHandler_2);
        }
        long tSave = System.nanoTime();
        runWithWatchdog("savePreparedModel(syncExec)", () -> Display.getDefault().syncExec(() -> { //$NON-NLS-1$
            try {
                loader.savePreparedModel(preparedModel[0]);
            }
            catch(IOException ex) {
                loadEx[0] = ex;
            }
        }));
        UIPerfLogger.log("[InteractiveMerge]", "savePreparedModel(syncExec)", tSave); //$NON-NLS-1$ //$NON-NLS-2$

        if(loadEx[0] != null) {
            throw loadEx[0];
        }

        // Apply editor/UI operations on SWT UI thread.
        if(monitor != null) {
            monitor.subTask(Messages.InteractiveMergeHandler_3);
        }
        long tOpen = System.nanoTime();
        runWithWatchdog("openModel(syncExec)", () -> Display.getDefault().syncExec(() -> { //$NON-NLS-1$
            try {
                loader.applyPreparedModelOnUIThread(preparedModel[0]);
            }
            catch(IOException ex) {
                loadEx[0] = ex;
            }
        }));
        UIPerfLogger.log("[InteractiveMerge]", "openModel(syncExec)", tOpen); //$NON-NLS-1$ //$NON-NLS-2$

        if(loadEx[0] != null) {
            throw loadEx[0];
        }
    }

    @FunctionalInterface
    private interface IoRunnable {
        void run() throws IOException;
    }

    /**
     * Logs worker/UI thread state and stack traces periodically while a phase runs,
     * helping identify lock contention and blocked sync hand-offs.
     */
    private void runWithWatchdog(String phase, IoRunnable runnable) throws IOException {
        log(IStatus.INFO, "[InteractiveMerge] WATCHDOG START phase=" + phase); //$NON-NLS-1$

        AtomicBoolean done = new AtomicBoolean(false);
        Thread workerThread = Thread.currentThread();
        Display display = Display.getDefault();
        Thread uiThread = display != null ? display.getThread() : null;

        Thread watchdog = new Thread(() -> {
            ThreadMXBean threadMxBean = ManagementFactory.getThreadMXBean();
            while(!done.get()) {
                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MS);
                }
                catch(InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }

                if(done.get()) {
                    return;
                }

                StringBuilder sb = new StringBuilder();
                sb.append("[InteractiveMerge] WATCHDOG phase=").append(phase); //$NON-NLS-1$
                sb.append(", workerState=").append(workerThread.getState()); //$NON-NLS-1$
                if(uiThread != null) {
                    sb.append(", uiState=").append(uiThread.getState()); //$NON-NLS-1$
                }

                long[] deadlocked = threadMxBean.findDeadlockedThreads();
                if(deadlocked != null && deadlocked.length > 0) {
                    sb.append(", deadlockedThreadIds="); //$NON-NLS-1$
                    for(int i = 0; i < deadlocked.length; i++) {
                        if(i > 0) {
                            sb.append(',');
                        }
                        sb.append(deadlocked[i]);
                    }
                }

                sb.append("\n  workerTop=").append(compactStack(workerThread.getStackTrace())); //$NON-NLS-1$
                if(uiThread != null) {
                    sb.append("\n  uiTop=").append(compactStack(uiThread.getStackTrace())); //$NON-NLS-1$
                }

                log(IStatus.WARNING, sb.toString());
            }
        }, "InteractiveMerge-Watchdog-" + phase); //$NON-NLS-1$

        watchdog.setDaemon(true);
        watchdog.start();

        try {
            runnable.run();
        }
        finally {
            done.set(true);
            watchdog.interrupt();
            log(IStatus.INFO, "[InteractiveMerge] WATCHDOG END phase=" + phase); //$NON-NLS-1$
        }
    }

    private String compactStack(StackTraceElement[] stack) {
        if(stack == null || stack.length == 0) {
            return "<empty>"; //$NON-NLS-1$
        }

        int limit = Math.min(WATCHDOG_STACK_DEPTH, stack.length);
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < limit; i++) {
            if(i > 0) {
                sb.append(" | "); //$NON-NLS-1$
            }
            StackTraceElement e = stack[i];
            sb.append(e.getClassName()).append('.').append(e.getMethodName())
              .append(':').append(e.getLineNumber());
        }
        return sb.toString();
    }

    private void log(int severity, String message) {
        writeWatchdogLogFile(message);

        ModelRepositoryPlugin plugin = ModelRepositoryPlugin.getInstance();
        if(plugin != null) {
            plugin.log(severity, message, null);
        }
    }

    private void writeWatchdogLogFile(String message) {
        try {
            Files.createDirectories(WATCHDOG_FILE.getParent());
            String line = WATCHDOG_TIMESTAMP.format(LocalDateTime.now()) + " " + message + System.lineSeparator(); //$NON-NLS-1$
            Files.writeString(WATCHDOG_FILE, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch(IOException ex) {
            // Intentionally ignore file logging failures; plugin log still records diagnostics.
        }
    }
}
