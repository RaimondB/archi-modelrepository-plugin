/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.swt.widgets.Display;

/**
 * A throttled progress reporter that is completely thread-safe for UI updates.
 * 
 * <p>This class uses a dedicated background thread for polling and {@link Display#asyncExec}
 * for all UI operations. Worker threads can safely call any method from any thread without
 * risk of "Invalid thread access" SWT exceptions.</p>
 * 
 * <p>Key optimizations:</p>
 * <ul>
 *   <li>Uses {@link LongAdder} for lock-free counting with minimal cache-line bouncing</li>
 *   <li>Dedicated reporter thread - worker threads NEVER block on UI synchronization</li>
 *   <li>Time-based polling - checks for updates every N milliseconds</li>
 *   <li>Worker threads only increment counters and set message generator (non-blocking)</li>
 *   <li>Uses {@link Display#asyncExec} for all UI updates - safe from ANY thread</li>
 * </ul>
 * 
 * <p>Usage:</p>
 * <pre>
 * ThrottledProgressReporter reporter = new ThrottledProgressReporter(progress, totalFiles);
 * 
 * // In worker threads (non-blocking - never touches UI thread):
 * reporter.incrementBy(batch.size());
 * reporter.setMessageGenerator(count -> "Processing " + count + " files...");
 * 
 * // At end (blocks until final report is sent):
 * reporter.finish("Complete");
 * </pre>
 * 
 * @author Copilot
 */
public class ThrottledProgressReporter {
    
    // Default polling interval for the reporter thread (milliseconds)
    private static final long DEFAULT_POLL_INTERVAL_MS = 250;
    
    // Default minimum files between updates (to avoid too frequent updates even with polling)
    // Set to 1 to ensure updates even when processing is slow (e.g. cold cache)
    private static final int DEFAULT_FILE_INTERVAL = 1;
    
    private final SubMonitor progress;
    private final int totalItems;
    private final long pollIntervalMs;
    private final int fileInterval;
    
    // Use LongAdder for minimal contention across many threads
    private final LongAdder processedCount = new LongAdder();
    
    // Last reported count - only accessed by reporter thread
    private long lastReportedCount = 0;
    
    // Current message generator - set by worker threads, read by reporter thread
    private final AtomicReference<Function<Long, String>> messageGenerator = new AtomicReference<>();
    
    // Flag to signal completion
    private final AtomicBoolean finished = new AtomicBoolean(false);
    
    // Dedicated reporter thread
    private final ScheduledExecutorService reporterThread;
    
    /**
     * Create a throttled progress reporter with default intervals.
     * Starts a background thread that polls every 250ms for progress updates.
     * 
     * @param progress The SubMonitor to report to
     * @param totalItems Total number of items to process
     */
    public ThrottledProgressReporter(SubMonitor progress, int totalItems) {
        this(progress, totalItems, DEFAULT_POLL_INTERVAL_MS, DEFAULT_FILE_INTERVAL);
    }
    
    /**
     * Create a throttled progress reporter with custom intervals.
     * Starts a background thread that polls at the specified interval.
     * 
     * @param progress The SubMonitor to report to
     * @param totalItems Total number of items to process
     * @param pollIntervalMs Polling interval for the reporter thread (milliseconds)
     * @param fileInterval Minimum files between UI updates
     */
    public ThrottledProgressReporter(SubMonitor progress, int totalItems, long pollIntervalMs, int fileInterval) {
        this.progress = progress;
        this.totalItems = totalItems;
        this.pollIntervalMs = pollIntervalMs;
        this.fileInterval = fileInterval;
        
        // Initialize work remaining
        if (progress != null) {
            progress.setWorkRemaining(totalItems);
        }
        
        // Start the dedicated reporter thread
        this.reporterThread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ThrottledProgressReporter"); //$NON-NLS-1$
            t.setDaemon(true); // Don't prevent JVM shutdown
            return t;
        });
        
        // Schedule periodic polling
        reporterThread.scheduleAtFixedRate(this::pollAndReport, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Called by the reporter thread to check for and send progress updates.
     * Uses Display.asyncExec() to ensure UI operations run on the UI thread.
     */
    private void pollAndReport() {
        if (progress == null || finished.get()) {
            return;
        }
        
        long currentCount = processedCount.sum();
        
        // Check if enough files have been processed to warrant an update
        // Since we are polling at a fixed interval (e.g. 250ms), we should update if ANY progress
        // has been made, to ensure the UI doesn't appear frozen during slow operations.
        if (currentCount > lastReportedCount || currentCount >= totalItems) {
            final int workDelta = (int) (currentCount - lastReportedCount);
            final long reportedCount = currentCount;
            
            // Get message if set
            Function<Long, String> msgGen = messageGenerator.get();
            final String message = (msgGen != null) ? msgGen.apply(currentCount) : null;
            
            if (workDelta > 0 || message != null) {
                // Update last reported count now to avoid duplicate updates
                lastReportedCount = reportedCount;
                
                // Schedule UI update on the UI thread
                Display display = Display.getDefault();
                if (display != null && !display.isDisposed()) {
                    display.asyncExec(() -> {
                        if (progress == null || finished.get()) {
                            return;
                        }
                        if (workDelta > 0) {
                            progress.worked(workDelta);
                        }
                        if (message != null) {
                            progress.subTask(message);
                        }
                    });
                }
            }
        }
    }
    
    /**
     * Increment the processed count by one.
     * This is NON-BLOCKING - the reporter thread will pick up the update.
     */
    public void increment() {
        processedCount.increment();
    }
    
    /**
     * Increment the processed count by a specific amount.
     * This is NON-BLOCKING - the reporter thread will pick up the update.
     * 
     * @param delta The amount to increment by
     */
    public void incrementBy(int delta) {
        processedCount.add(delta);
    }
    
    /**
     * Set the message generator for progress messages.
     * This is NON-BLOCKING - the reporter thread will use it on next poll.
     * 
     * @param generator Function to generate the progress message from current count
     */
    public void setMessageGenerator(Function<Long, String> generator) {
        messageGenerator.set(generator);
    }
    
    /**
     * Increment and set message generator in one call (convenience method).
     * This is NON-BLOCKING - the reporter thread will pick up the update.
     * 
     * @param delta The amount to increment by
     * @param generator Function to generate the progress message from current count
     */
    public void incrementByAndSetMessage(int delta, Function<Long, String> generator) {
        processedCount.add(delta);
        messageGenerator.set(generator);
    }
    
    /**
     * Legacy method for compatibility - increment by 1 and set message.
     * This is NON-BLOCKING - the reporter thread will pick up the update.
     * 
     * @param generator Function to generate the progress message from current count
     */
    public void incrementAndMaybeReport(Function<Long, String> generator) {
        processedCount.increment();
        messageGenerator.set(generator);
    }
    
    /**
     * Legacy method for compatibility - set message without incrementing.
     * This is NON-BLOCKING - the reporter thread will pick up the update.
     * 
     * @param generator Function to generate the progress message from current count
     */
    public void maybeReport(Function<Long, String> generator) {
        messageGenerator.set(generator);
    }
    
    /**
     * Set a subtask message for the next poll.
     * This is NON-BLOCKING - the reporter thread will pick up and display the message.
     * Use this for major phase transitions (e.g., "Reading files...", "Writing files...").
     * 
     * <p>Note: Unlike direct IProgressMonitor.subTask() calls, this does NOT immediately
     * update the UI. The message will appear on the next poll (within 250ms by default).
     * This ensures worker threads never block on UI synchronization.</p>
     * 
     * @param message The subtask message to display
     */
    public void subTask(String message) {
        if (message != null) {
            // Set message generator for next poll - NON-BLOCKING
            messageGenerator.set(count -> message);
        }
    }
    
    /**
     * Record work done (throttled).
     * This is NON-BLOCKING - the reporter thread will pick up the update.
     * Use this instead of calling progress.worked() directly.
     * 
     * @param work The amount of work done
     */
    public void worked(int work) {
        processedCount.add(work);
    }
    
    /**
     * Report final progress and ensure all work is accounted for.
     * This method BLOCKS until the final report is sent and the reporter thread is stopped.
     * Call this at the end of processing.
     * 
     * <p>Uses Display.syncExec() to ensure the final UI update completes before returning.</p>
     * 
     * @param finalMessage The final status message, or null for none
     */
    public void finish(String finalMessage) {
        if (finished.getAndSet(true)) {
            // Already finished - avoid double finishing
            return;
        }
        
        // Shutdown the reporter thread first
        reporterThread.shutdown();
        try {
            reporterThread.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        if (progress == null) {
            return;
        }
        
        // Report any remaining work on the UI thread
        final String message = finalMessage;
        final long currentCount = processedCount.sum();
        final int remaining = totalItems - (int) lastReportedCount;
        
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed()) {
            display.syncExec(() -> {
                if (remaining > 0) {
                    progress.worked(remaining);
                }
                if (message != null) {
                    progress.subTask(message);
                }
            });
        }
    }
    
    /**
     * Get the current processed count.
     * 
     * @return The number of items processed so far
     */
    public long getProcessedCount() {
        return processedCount.sum();
    }
    
    /**
     * Check if the operation has been canceled.
     * 
     * @return true if canceled
     */
    public boolean isCanceled() {
        return progress != null && progress.isCanceled();
    }
}
