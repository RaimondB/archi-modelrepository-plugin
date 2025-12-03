/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;

/**
 * A throttled progress reporter that minimizes UI thread synchronization.
 * 
 * <p>Key optimizations:</p>
 * <ul>
 *   <li>Uses {@link LongAdder} for lock-free counting with minimal cache-line bouncing</li>
 *   <li>Time-based throttling - updates UI at most every N milliseconds</li>
 *   <li>File count-based throttling - updates only after N files processed</li>
 *   <li>Thread-safe increments from multiple worker threads</li>
 * </ul>
 * 
 * <p>Usage:</p>
 * <pre>
 * ThrottledProgressReporter reporter = new ThrottledProgressReporter(progress, totalFiles);
 * 
 * // In worker threads:
 * reporter.incrementAndMaybeReport(count -> "Processing " + count + " files...");
 * 
 * // At end:
 * reporter.finish("Complete");
 * </pre>
 * 
 * @author Copilot
 */
public class ThrottledProgressReporter {
    
    // Default minimum interval between UI updates (milliseconds)
    private static final long DEFAULT_UPDATE_INTERVAL_MS = 250;
    
    // Default minimum files between updates
    private static final int DEFAULT_FILE_INTERVAL = 2000;
    
    private final SubMonitor progress;
    private final int totalItems;
    private final long updateIntervalMs;
    private final int fileInterval;
    
    // Use LongAdder for minimal contention across many threads
    private final LongAdder processedCount = new LongAdder();
    
    // Last update time - volatile for visibility across threads
    private volatile long lastUpdateTimeMs = 0;
    
    // Last reported count - to calculate worked() delta
    private volatile long lastReportedCount = 0;
    
    /**
     * Create a throttled progress reporter with default intervals.
     * Updates UI at most every 250ms or every 2000 files, whichever comes first.
     * 
     * @param progress The SubMonitor to report to
     * @param totalItems Total number of items to process
     */
    public ThrottledProgressReporter(SubMonitor progress, int totalItems) {
        this(progress, totalItems, DEFAULT_UPDATE_INTERVAL_MS, DEFAULT_FILE_INTERVAL);
    }
    
    /**
     * Create a throttled progress reporter with custom intervals.
     * 
     * @param progress The SubMonitor to report to
     * @param totalItems Total number of items to process
     * @param updateIntervalMs Minimum milliseconds between UI updates
     * @param fileInterval Minimum files between UI updates
     */
    public ThrottledProgressReporter(SubMonitor progress, int totalItems, long updateIntervalMs, int fileInterval) {
        this.progress = progress;
        this.totalItems = totalItems;
        this.updateIntervalMs = updateIntervalMs;
        this.fileInterval = fileInterval;
        
        // Initialize work remaining
        if (progress != null) {
            progress.setWorkRemaining(totalItems);
        }
    }
    
    /**
     * Increment the processed count and maybe report progress.
     * This method is thread-safe and optimized for high-frequency calls.
     * 
     * <p>Progress is only reported if:</p>
     * <ul>
     *   <li>At least {@code updateIntervalMs} has passed since last update, OR</li>
     *   <li>At least {@code fileInterval} files have been processed since last update</li>
     * </ul>
     * 
     * @param messageGenerator Function to generate the progress message from current count.
     *                         Only called when an update will actually be sent.
     */
    public void incrementAndMaybeReport(Function<Long, String> messageGenerator) {
        processedCount.increment();
        
        if (progress == null || progress.isCanceled()) {
            return;
        }
        
        long currentCount = processedCount.sum();
        long currentTimeMs = System.currentTimeMillis();
        
        // Check if we should report (time-based or count-based)
        boolean shouldReport = false;
        
        // Time-based check: has enough time passed?
        if (currentTimeMs - lastUpdateTimeMs >= updateIntervalMs) {
            shouldReport = true;
        }
        
        // Count-based check: have enough files been processed?
        if (currentCount - lastReportedCount >= fileInterval) {
            shouldReport = true;
        }
        
        // Always report on completion
        if (currentCount >= totalItems) {
            shouldReport = true;
        }
        
        if (shouldReport) {
            // Use synchronized block only for the actual update
            // This ensures only one thread updates at a time, preventing duplicate reports
            synchronized (this) {
                // Double-check inside synchronized block
                long recheckCount = processedCount.sum();
                long recheckTime = System.currentTimeMillis();
                
                if (recheckTime - lastUpdateTimeMs >= updateIntervalMs || 
                    recheckCount - lastReportedCount >= fileInterval ||
                    recheckCount >= totalItems) {
                    
                    // Calculate work delta since last report
                    int workDelta = (int) (recheckCount - lastReportedCount);
                    
                    // Update progress (this syncs with UI thread)
                    if (workDelta > 0) {
                        progress.worked(workDelta);
                    }
                    
                    // Generate and set message
                    String message = messageGenerator.apply(recheckCount);
                    if (message != null) {
                        progress.subTask(message);
                    }
                    
                    // Update tracking variables
                    lastUpdateTimeMs = recheckTime;
                    lastReportedCount = recheckCount;
                }
            }
        }
    }
    
    /**
     * Increment the processed count without reporting progress.
     * Useful when you want to batch increments and report separately.
     */
    public void increment() {
        processedCount.increment();
    }
    
    /**
     * Increment by a specific amount without reporting.
     * 
     * @param delta The amount to increment by
     */
    public void incrementBy(int delta) {
        processedCount.add(delta);
    }
    
    /**
     * Force a progress report with the current count.
     * 
     * @param message The message to display
     */
    public void forceReport(String message) {
        if (progress == null) {
            return;
        }
        
        synchronized (this) {
            long currentCount = processedCount.sum();
            int workDelta = (int) (currentCount - lastReportedCount);
            
            if (workDelta > 0) {
                progress.worked(workDelta);
            }
            
            if (message != null) {
                progress.subTask(message);
            }
            
            lastUpdateTimeMs = System.currentTimeMillis();
            lastReportedCount = currentCount;
        }
    }
    
    /**
     * Report final progress and ensure all work is accounted for.
     * Call this at the end of processing.
     * 
     * @param finalMessage The final status message, or null for none
     */
    public void finish(String finalMessage) {
        if (progress == null) {
            return;
        }
        
        synchronized (this) {
            // Report any remaining work
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
