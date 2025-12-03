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

/**
 * A throttled progress reporter that uses a dedicated background thread for UI updates.
 * 
 * <p>Key optimizations:</p>
 * <ul>
 *   <li>Uses {@link LongAdder} for lock-free counting with minimal cache-line bouncing</li>
 *   <li>Dedicated reporter thread - worker threads NEVER block on UI synchronization</li>
 *   <li>Time-based polling - checks for updates every N milliseconds</li>
 *   <li>Worker threads only increment counters and set message generator (non-blocking)</li>
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
    private static final int DEFAULT_FILE_INTERVAL = 500;
    
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
     * This is the ONLY method that touches the UI thread.
     */
    private void pollAndReport() {
        if (progress == null || finished.get()) {
            return;
        }
        
        long currentCount = processedCount.sum();
        
        // Check if enough files have been processed to warrant an update
        if (currentCount - lastReportedCount >= fileInterval || currentCount >= totalItems) {
            int workDelta = (int) (currentCount - lastReportedCount);
            
            if (workDelta > 0) {
                progress.worked(workDelta);
                lastReportedCount = currentCount;
            }
            
            // Get and apply message if set
            Function<Long, String> msgGen = messageGenerator.get();
            if (msgGen != null) {
                String message = msgGen.apply(currentCount);
                if (message != null) {
                    progress.subTask(message);
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
     * Set a subtask message with IMMEDIATE update.
     * Unlike other methods, this updates the UI synchronously for phase announcements.
     * Use this for major phase transitions (e.g., "Reading files...", "Writing files...").
     * 
     * @param message The subtask message to display
     */
    public void subTask(String message) {
        if (message != null && progress != null) {
            // Update message generator for subsequent polls
            messageGenerator.set(count -> message);
            // IMMEDIATE update for phase announcements
            progress.subTask(message);
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
     * @param finalMessage The final status message, or null for none
     */
    public void finish(String finalMessage) {
        // Signal completion
        finished.set(true);
        
        // Stop the reporter thread
        reporterThread.shutdown();
        try {
            reporterThread.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        if (progress == null) {
            return;
        }
        
        // Report any remaining work on the current thread
        long currentCount = processedCount.sum();
        int remaining = totalItems - (int) lastReportedCount;
        
        if (remaining > 0) {
            progress.worked(remaining);
        }
        
        if (finalMessage != null) {
            progress.subTask(finalMessage);
        }
        
        lastReportedCount = totalItems;
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
