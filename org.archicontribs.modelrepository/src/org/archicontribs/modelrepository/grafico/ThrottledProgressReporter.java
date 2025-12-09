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

import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.eclipse.core.runtime.IStatus;
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
 *   <li>Uses {@link LongAdder} for produced count (many producers - contention matters)</li>
 *   <li>Uses {@link AtomicLong} for consumed count (accurate reads needed for UI updates)</li>
 *   <li>Dedicated reporter thread - worker threads NEVER block on UI synchronization</li>
 *   <li>Time-based polling - checks for updates every N milliseconds</li>
 *   <li>Worker threads only increment counters and set message generator (non-blocking)</li>
 *   <li>Uses {@link Display#asyncExec} for all UI updates - safe from ANY thread</li>
 * </ul>
 * 
 * <p>Note on counter types: We use {@link AtomicLong} for the consumed count because
 * {@link LongAdder#sum()} can return stale values during high-concurrency updates,
 * which caused the progress UI to freeze when it thought no progress was being made.
 * The consumed counter has a single writer (consumer thread) so contention is not an issue.</p>
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
    
    // Performance logging flag - matches GraficoModelImporter
    private static final boolean PERF_LOGGING = Boolean.getBoolean("grafico.perf.logging"); //$NON-NLS-1$
    
    // Default polling interval for the reporter thread (milliseconds)
    private static final long DEFAULT_POLL_INTERVAL_MS = 250;
    
    // Default minimum files between updates (to avoid too frequent updates even with polling)
    // Set to 1 to ensure updates even when processing is slow (e.g. cold cache)
    private static final int DEFAULT_FILE_INTERVAL = 1;
    
    private final SubMonitor progress;
    private final int totalItems;
    private final long pollIntervalMs;
    private final int fileInterval;
    
    // Use LongAdder for produced count (many concurrent producers - contention matters)
    private final LongAdder producedCount = new LongAdder();
    
    // Use volatile for consumed count (single consumer thread writes, reporter thread reads)
    // AtomicLong.get() was returning stale values in ScheduledExecutorService threads.
    // Volatile provides a memory barrier that ensures visibility across threads.
    private volatile long consumedCount = 0;
    
    // Last reported counts - only accessed by reporter thread
    private long lastReportedConsumed = 0;
    private long lastReportedProduced = 0;
    
    // Current message generator - set by worker threads, read by reporter thread
    private final AtomicReference<Function<Long, String>> messageGenerator = new AtomicReference<>();
    
    // Flag to signal completion
    private final AtomicBoolean finished = new AtomicBoolean(false);
    
    // Dedicated reporter thread
    private final ScheduledExecutorService reporterThread;
    
    // Optional queue size supplier for diagnostics
    private volatile java.util.function.IntSupplier queueSizeSupplier;
    
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
    
    // Track poll invocations for diagnostics
    private final LongAdder pollCount = new LongAdder();
    private final LongAdder asyncExecCount = new LongAdder();
    
    /**
     * Called by the reporter thread to check for and send progress updates.
     * Uses Display.asyncExec() to ensure UI operations run on the UI thread.
     */
    private void pollAndReport() {
        pollCount.increment();
        
        if (progress == null || finished.get()) {
            return;
        }
        
        long currentConsumed = consumedCount;  // volatile read
        long currentProduced = producedCount.sum();
        
        // Log every 10 polls (every ~2.5 seconds) to diagnose stalls (only if perf logging enabled)
        if (PERF_LOGGING && pollCount.sum() % 10 == 0) {
            int queueSize = queueSizeSupplier != null ? queueSizeSupplier.getAsInt() : -1;
            ModelRepositoryPlugin.getInstance().log(IStatus.INFO,
                "[PROGRESS REPORTER] poll #" + pollCount.sum() +  //$NON-NLS-1$
                ": produced=" + currentProduced + ", consumed=" + currentConsumed +  //$NON-NLS-1$
                ", queueSize=" + queueSize + //$NON-NLS-1$
                ", asyncExecs=" + asyncExecCount.sum() + ", finished=" + finished.get(), null); //$NON-NLS-1$ //$NON-NLS-2$
        }
        
    boolean producedChanged = currentProduced != lastReportedProduced;
    boolean consumedChanged = currentConsumed != lastReportedConsumed;
    boolean reachedTotal = currentConsumed >= totalItems;
        
        // Check if enough files have been processed to warrant an update
        // Since we are polling at a fixed interval (e.g. 250ms), we should update if ANY progress
        // has been made, to ensure the UI doesn't appear frozen during slow operations.
        if (producedChanged || consumedChanged || reachedTotal) {
            final int workDelta = consumedChanged ? (int) (currentConsumed - lastReportedConsumed) : 0;
            final long reportedConsumed = currentConsumed;
            final long reportedProduced = currentProduced;
            
            // Get message if set; fallback always includes produced/consumed diagnostics
            Function<Long, String> msgGen = messageGenerator.get();
            final String baseMessage = (msgGen != null) ? msgGen.apply(currentConsumed) : null;
            final String message = buildStatusMessage(baseMessage, reportedProduced, reportedConsumed);
            
            // Update last reported counts now to avoid duplicate updates
            lastReportedConsumed = reportedConsumed;
            lastReportedProduced = reportedProduced;
            
            // Schedule UI update on the UI thread
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed()) {
                asyncExecCount.increment();
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
        } else {
            // No count changes - still update message if a generator is set and UI thread is alive
            Function<Long, String> msgGen = messageGenerator.get();
            if (msgGen != null) {
                final String message = buildStatusMessage(msgGen.apply(currentConsumed), currentProduced, currentConsumed);
                Display display = Display.getDefault();
                if (display != null && !display.isDisposed()) {
                    asyncExecCount.increment();
                    display.asyncExec(() -> {
                        if (progress == null || finished.get()) {
                            return;
                        }
                        progress.subTask(message);
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
        consumedCount++;  // volatile write
    }
    
    /**
     * Increment the processed count by a specific amount.
     * This is NON-BLOCKING - the reporter thread will pick up the update.
     * 
     * @param delta The amount to increment by
     */
    public void incrementBy(int delta) {
        consumedCount += delta;  // volatile write
    }

    /**
     * Increment the produced count by one.
     * Use this when a producer thread has finished preparing an item for consumption.
     */
    public void incrementProduced() {
        producedCount.increment();
    }
    
    /**
     * Increment the produced count by a specific amount.
     * 
     * @param delta Amount to increment by
     */
    public void incrementProducedBy(int delta) {
        producedCount.add(delta);
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
     * Set a supplier for queue size diagnostics.
     * Used to log actual queue size in diagnostic polls.
     * 
     * @param supplier Supplier that returns current queue size, or null to disable
     */
    public void setQueueSizeSupplier(java.util.function.IntSupplier supplier) {
        this.queueSizeSupplier = supplier;
    }
    
    /**
     * Increment and set message generator in one call (convenience method).
     * This is NON-BLOCKING - the reporter thread will pick up the update.
     * 
     * @param delta The amount to increment by
     * @param generator Function to generate the progress message from current count
     */
    public void incrementByAndSetMessage(int delta, Function<Long, String> generator) {
        consumedCount += delta;  // volatile write
        messageGenerator.set(generator);
    }
    
    /**
     * Legacy method for compatibility - increment by 1 and set message.
     * This is NON-BLOCKING - the reporter thread will pick up the update.
     * 
     * @param generator Function to generate the progress message from current count
     */
    public void incrementAndMaybeReport(Function<Long, String> generator) {
        consumedCount++;  // volatile write
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
        consumedCount += work;  // volatile write
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
    final long currentConsumed = consumedCount;  // volatile read
    final long currentProduced = producedCount.sum();
    final int remaining = totalItems - (int) lastReportedConsumed;
        
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed()) {
            display.syncExec(() -> {
                if (remaining > 0) {
                    progress.worked(remaining);
                }
                final String finalStatus = buildStatusMessage(message, currentProduced, currentConsumed);
                if (finalStatus != null) {
                    progress.subTask(finalStatus);
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
        return consumedCount;  // volatile read
    }

    /**
     * Get the current produced count.
     * 
     * @return The number of items produced so far
     */
    public long getProducedCount() {
        return producedCount.sum();
    }
    
    /**
     * Check if the operation has been canceled.
     * 
     * @return true if canceled
     */
    public boolean isCanceled() {
        return progress != null && progress.isCanceled();
    }
    
    private String buildStatusMessage(String baseMessage, long produced, long consumed) {
        String diagnostics = String.format("Produced %d | Consumed %d / %d", produced, consumed, totalItems);
        if (baseMessage == null || baseMessage.isBlank()) {
            return diagnostics;
        }
        return baseMessage + " [" + diagnostics + "]"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
