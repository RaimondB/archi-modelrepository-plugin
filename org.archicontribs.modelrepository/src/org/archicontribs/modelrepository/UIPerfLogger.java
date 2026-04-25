/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository;

import org.eclipse.core.runtime.IStatus;

/**
 * Lock-free performance logger for UI timing diagnostics.
 * 
 * Enable with JVM arg: -Dcoarchi.ui.perf.logging=true
 * 
 * Logs go to Eclipse Error Log view (Window → Show View → Error Log)
 * and .metadata/.log file in your workspace.
 * 
 * When disabled (default), the static final boolean is evaluated once at
 * class load time and the JIT compiler eliminates dead code entirely —
 * zero overhead, zero allocations, no contention.
 */
public final class UIPerfLogger {

    /** Enable with -Dcoarchi.ui.perf.logging=true */
    public static final boolean ENABLED = Boolean.getBoolean("coarchi.ui.perf.logging"); //$NON-NLS-1$

    private UIPerfLogger() {} // utility class

    /**
     * Log a timed phase.
     * @param tag Short prefix, e.g. "[SwitchBranch]"
     * @param phase Description of the phase
     * @param startNanos value from {@code System.nanoTime()} at phase start
     */
    public static void log(String tag, String phase, long startNanos) {
        if (!ENABLED) return;
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        ModelRepositoryPlugin.getInstance().log(IStatus.INFO,
                tag + " " + phase + ": " + ms + "ms", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Log a message (no timing).
     * @param tag Short prefix
     * @param message The message
     */
    public static void log(String tag, String message) {
        if (!ENABLED) return;
        ModelRepositoryPlugin.getInstance().log(IStatus.INFO,
                tag + " " + message, null); //$NON-NLS-1$
    }
}
