/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;

import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.osgi.util.NLS;
import org.eclipse.emf.ecore.resource.impl.ExtensibleURIConverterImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.XMLResource;
import org.eclipse.emf.ecore.xmi.impl.XMLResourceFactoryImpl;

import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModelImageProvider;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IFolderContainer;
import com.archimatetool.model.IIdentifier;


/**
 * Based on the GRAFICO Model Exporter
 * GRAFICO (Git fRiendly Archi FIle COllection) is a way to persist an ArchiMate
 * model in a bunch of XML files (one file per ArchiMate element or view).
 * 
 * @author Jean-Baptiste Sarrodie
 * @author Quentin Varquet
 * @author Phillip Beauvoir
 */
public class GraficoModelExporter {
    
	/**
	 * ResourceSet
	 */
	private ResourceSet fResourceSet;
	
    /**
     * Model
     */
    private IArchimateModel fModel;
    
    /**
     * Local repo folder
     */
    private File fLocalRepoFolder;
    
    /**
     * Shared progress reporter across all phases (images, hash, write).
     * Uses dedicated background thread for UI updates to prevent worker thread blocking.
     */
    private ThrottledProgressReporter fProgressReporter;
    
    /**
     * Git repository, if exporting to a git-managed folder.
     * Null if the folder is not a git repository.
     * Must be closed after export completes.
     */
    private Repository fGitRepository;
    
    /**
     * DirCache (git index) for the repository.
     * Used for fast hash-based comparison instead of reading files from disk.
     * Null if the folder is not a git repository or if DirCache loading fails.
     */
    private DirCache fDirCache;
    
    /**
     * ObjectInserter for computing git blob hashes using the repository's hash algorithm.
     * Git may use SHA-1 or SHA-256 depending on repository configuration.
     * Null if the folder is not a git repository.
     */
    private ObjectInserter fObjectInserter;
    
    /**
     * Enable performance logging. Set to true to see detailed timing breakdown.
     * Logs go to Eclipse Error Log view (Window → Show View → Error Log).
     * 
     * This flag is evaluated once at class load time, so there's zero overhead
     * when disabled (the default). The check is a simple boolean comparison.
     */
    private static final boolean PERF_LOGGING = Boolean.getBoolean("grafico.perf.logging"); //$NON-NLS-1$
    
    /**
     * Log performance metrics if PERF_LOGGING is enabled.
     * Enable with JVM arg: -Dgrafico.perf.logging=true
     * 
     * Logs appear in:
     * - Eclipse Error Log view (Window → Show View → Error Log)
     * - .metadata/.log file in your workspace
     * 
     * Performance note: When PERF_LOGGING is false (default), the if-check
     * short-circuits immediately with zero allocations or method calls.
     * Logging is only called at phase boundaries (6 times per export),
     * never from within the parallel pipeline, so it doesn't affect throughput.
     */
    private void logPerf(String phase, long startNanos, int itemCount) {
        if (!PERF_LOGGING) return; // Fast path - zero overhead when disabled
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        double itemsPerSec = itemCount > 0 && elapsedMs > 0 ? (itemCount * 1000.0 / elapsedMs) : 0;
        String message = String.format("[GRAFICO PERF] %s: %dms (%d items, %.0f items/sec)", //$NON-NLS-1$
            phase, elapsedMs, itemCount, itemsPerSec);
        ModelRepositoryPlugin.getInstance().log(IStatus.INFO, message, null);
    }
    
    private void logPerf(String phase, long startNanos) {
        if (!PERF_LOGGING) return; // Fast path - zero overhead when disabled
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        String message = String.format("[GRAFICO PERF] %s: %dms", phase, elapsedMs); //$NON-NLS-1$
        ModelRepositoryPlugin.getInstance().log(IStatus.INFO, message, null);
    }
    
    private void logPerfMessage(String message) {
        if (!PERF_LOGGING) return; // Fast path - zero overhead when disabled
        ModelRepositoryPlugin.getInstance().log(IStatus.INFO, "[GRAFICO PERF] " + message, null); //$NON-NLS-1$
    }
    
	/**
	 * @param model The model to export
	 * @param folder The root folder in which to write the grafico XML files
	 */
	public GraficoModelExporter(IArchimateModel model, File folder) {
	    if(model == null) {
            throw new IllegalArgumentException("Model cannot be null"); //$NON-NLS-1$
        }
	    if(folder == null) {
            throw new IllegalArgumentException("Folder cannot be null"); //$NON-NLS-1$
        }
	    
	    fModel = model;
	    fLocalRepoFolder = folder;
	}
	
    /**
     * Export the IArchimateModel as Grafico files
     * @return true if any files were written or deleted (i.e., repository has changes)
     * @throws IOException
     */
    public boolean exportModel() throws IOException {
        return exportModel(null);
    }
    
    /**
     * Export the IArchimateModel as Grafico files with progress monitoring.
     * 
     * IMPORTANT: This method returns whether the export resulted in any file changes.
     * The return value should be used to determine if git operations (add, commit) are needed,
     * rather than calling hasChangesToCommit() which checks git status AFTER staging.
     * See REFACTORING_NOTES.md for details on this design decision.
     * 
     * @param monitor Progress monitor for UI feedback, can be null
     * @return true if any files were written or deleted (i.e., repository has changes)
     * @throws IOException
     */
    public boolean exportModel(IProgressMonitor monitor) throws IOException {
        long exportStart = System.nanoTime();
        
        // Use SubMonitor for easier progress reporting
        SubMonitor progress = SubMonitor.convert(monitor, Messages.GraficoModelExporter_0, 100);
        
        // Define target folders for model and images
        File modelFolder = new File(fLocalRepoFolder, IGraficoConstants.MODEL_FOLDER);
        File imagesFolder = new File(fLocalRepoFolder, IGraficoConstants.IMAGES_FOLDER);
        
        // Ensure directories exist
        modelFolder.mkdirs();
        imagesFolder.mkdirs();
        
        // Clear tracking sets and counters
        expectedFiles.clear();
        writtenFiles.clear();
        expectedDirectories.clear();
        deletedFilesCount.set(0);
        
        // Check for cancellation
        if (progress.isCanceled()) {
            return false;
        }
        
        // Count total work across ALL phases upfront: images + model files
        // This allows a SINGLE shared progress reporter across all phases
        int imageCount = countImages();
        int modelFileCount = countModelElements();
        // Total work = images + processing model files (merged read/serialize/hash/write pipeline)
        int totalWork = imageCount + modelFileCount;
        
        // Store counts as final for use in lambdas
        final int totalImages = imageCount;
        final int totalModelFiles = modelFileCount;
        
        // Create a SINGLE throttled progress reporter for ALL phases (images, hash, write)
        // This ensures only ONE background thread handles UI updates across the entire export
        // Use 100% of allocated progress - no reserved portion left idle at the end
        fProgressReporter = new ThrottledProgressReporter(progress.split(100), totalWork);
        
        try {
            // Save model images (if any): this has to be done on original model (not a copy)
            // Uses shared fProgressReporter for progress updates
            long phaseStart = System.nanoTime();
            fProgressReporter.subTask(NLS.bind(Messages.GraficoModelExporter_1, 0, totalImages));
            saveImages(totalImages);
            logPerf("Phase: Save Images", phaseStart, totalImages);
            
            // Check for cancellation
            if (fProgressReporter.isCanceled()) {
                return false;
            }
            
            // Create ResourceSet
            phaseStart = System.nanoTime();
            fProgressReporter.subTask(Messages.GraficoModelExporter_2);
            fResourceSet = new ResourceSetImpl();
            fResourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("*", new XMLResourceFactoryImpl()); //$NON-NLS-1$
            // Add a URIConverter that will be used to map full filenames to logical names
            fResourceSet.setURIConverter(new ExtensibleURIConverterImpl());
            
            // Now work on a copy
            IArchimateModel copy = EcoreUtil.copy(fModel);
            logPerf("Phase: Copy Model + Create ResourceSet", phaseStart);
            
            // Check for cancellation
            if (fProgressReporter.isCanceled()) {
                return false;
            }
            
            // Create directory structure and prepare all Resources
            // This is a fast phase (no I/O) - progress is reported during the merged pipeline phase
            phaseStart = System.nanoTime();
            fProgressReporter.subTask(Messages.GraficoModelExporter_3);
            createAndSaveResourceForFolder(copy, modelFolder);
            logPerf("Phase: Create Resources (no I/O)", phaseStart, totalModelFiles);
            
            // Initialize DirCache for fast hash-based comparison (git repos only)
            // If folder is not a git repo, we fall back to reading files from disk
            phaseStart = System.nanoTime();
            boolean useDirCache = initDirCache();
            logPerf("Phase: Init DirCache", phaseStart);
            if (useDirCache) {
                logPerfMessage(String.format("Using DirCache optimization (%d indexed files)", 
                    fDirCache != null ? fDirCache.getEntryCount() : 0)); //$NON-NLS-1$
            }

            // MERGED PIPELINE: Read existing → Serialize → Hash both → Write if different
            // This is more efficient than separate hash and write phases because:
            // 1. Avoids storing all hashes in memory (ConcurrentHashMap overhead)
            // 2. Writes immediately while serialized content is still in memory
            // 3. Single pass through resources instead of two passes
            // 
            // PARALLELISM STRATEGY for 30,000 small files (avg 8KB):
            // - Use more threads than CPU cores because:
            //   a) XML serialization has some I/O waits (EMF resource loading)
            //   b) Small files don't stress memory bandwidth
            //   c) We want to keep async I/O channels busy
            // - 4x CPU cores is a good balance for mixed CPU/IO workloads
            phaseStart = System.nanoTime();
            int cpuThreads = Runtime.getRuntime().availableProcessors();
            int parallelism = cpuThreads * 4; // More parallelism for small files with I/O waits
            ForkJoinPool cpuExecutor = new ForkJoinPool(parallelism);
            
            // Collect all resources with their target files first (quick, sequential)
            List<ResourceWriteTask> writeTasks = new ArrayList<>();
            for(Resource resource : fResourceSet.getResources()) {
                URI uri = fResourceSet.getURIConverter().normalize(resource.getURI());
                String filePath = uri.toFileString();
                File file = new File(filePath);
                writeTasks.add(new ResourceWriteTask(resource, file, null));
            }
            int totalResources = writeTasks.size();
            
            // Serialize resources using CPU executor, write files using virtual threads
            List<IOException> exceptions = Collections.synchronizedList(new ArrayList<>());
            
            // Check for cancellation before starting
            if (fProgressReporter.isCanceled()) {
                cpuExecutor.shutdown();
                return false;
            }
            
            // BATCH-WRAPPED PIPELINE: Auto-tuned batches run in parallel, sequential I/O within each batch
            // This limits concurrent file handles while allowing high parallelism for CPU work
            //
            // Why this is better than per-file futures:
            // 1. Fewer CompletableFutures = less GC pressure
            // 2. Limited concurrent file handles = OS-friendly (especially macOS!)
            // 3. Sequential I/O within batch allows SSD queue optimization
            // 4. Still fully parallel across batches for CPU work
            //
            // Auto-tuning considers:
            // - CPU cores: more cores = more useful parallelism
            // - File count: smaller models don't need many batches
            // - OS limits: macOS has 256 soft limit, Windows/Linux have higher limits
            final int targetBatches = calculateOptimalBatchCount(totalResources, cpuThreads);
            final int batchSize = Math.max(1, (totalResources + targetBatches - 1) / targetBatches);
            final int actualBatches = (totalResources + batchSize - 1) / batchSize;
            
            logPerfMessage(String.format("Starting pipeline: %d files, %d batches of ~%d files, parallelism=%d (CPU cores=%d)", //$NON-NLS-1$
                totalResources, actualBatches, batchSize, parallelism, cpuThreads));
            
            List<CompletableFuture<Void>> batchFutures = new ArrayList<>(actualBatches);
            
            // Announce the merged phase
            fProgressReporter.subTask(NLS.bind(Messages.GraficoModelExporter_5, 0, totalResources));
            
            // Create one CompletableFuture per batch
            // Within each batch: sequential read → serialize → compare → write for each file
            // Across batches: fully parallel execution via ForkJoinPool
            for (int i = 0; i < writeTasks.size(); i += batchSize) {
                final int start = i;
                final int end = Math.min(i + batchSize, writeTasks.size());
                final List<ResourceWriteTask> batch = writeTasks.subList(start, end);
                
                // ONE future per batch - processes files sequentially within batch
                CompletableFuture<Void> batchFuture = CompletableFuture.runAsync(() -> {
                    for (ResourceWriteTask task : batch) {
                        try {
                            // STEP 1: Serialize to byte array (CPU-bound)
                            ByteArrayOutputStream os = new ByteArrayOutputStream(4096);
                            task.resource.save(os, null);
                            byte[] newContent = os.toByteArray();
                            
                            // STEP 2: Check if content changed (uses DirCache if available, else file read)
                            // DirCache optimization: compares hashes without reading file from disk
                            if (hasContentChanged(task.file, newContent)) {
                                // Directories already created in createAndSaveResourceForFolder()
                                addWrittenFile(task.file.toPath());
                                // Blocking write within batch - keeps it simple and sequential
                                Files.write(task.file.toPath(), newContent);
                            }
                        } catch (IOException ex) {
                            exceptions.add(ex);
                        }
                        
                        // Report progress for each file (uses LongAdder - lock-free)
                        fProgressReporter.incrementBy(1);
                        fProgressReporter.maybeReport(
                            count -> NLS.bind(Messages.GraficoModelExporter_5, count, totalResources));
                    }
                }, cpuExecutor);
                
                batchFutures.add(batchFuture);
            }
            
            // Wait for all batch operations to complete
            try {
                CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0])).join();
            } catch (Exception e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException && cause.getCause() instanceof IOException) {
                    exceptions.add((IOException) cause.getCause());
                }
            }
            
            // Shutdown executor
            cpuExecutor.shutdown();
            logPerf("Phase: Pipeline (read+serialize+compare+write)", phaseStart, totalResources); //$NON-NLS-1$
            logPerfMessage(String.format("Files actually written: %d of %d (%.1f%% changed)", //$NON-NLS-1$
                writtenFiles.size(), totalResources, 
                totalResources > 0 ? (writtenFiles.size() * 100.0 / totalResources) : 0));
            
            // Clean up obsolete files after all resources are saved
            phaseStart = System.nanoTime();
            fProgressReporter.subTask(Messages.GraficoModelExporter_6);
            cleanupObsoleteFiles(new File(fLocalRepoFolder, IGraficoConstants.MODEL_FOLDER));
            cleanupObsoleteFiles(new File(fLocalRepoFolder, IGraficoConstants.IMAGES_FOLDER));
            logPerf("Phase: Cleanup obsolete files", phaseStart); //$NON-NLS-1$
            
            // Log total export time and throughput summary
            logPerf("TOTAL EXPORT", exportStart, totalWork); //$NON-NLS-1$
            long totalMs = (System.nanoTime() - exportStart) / 1_000_000;
            logPerfMessage(String.format("Throughput: %.0f files/sec, CPU cores: %d", //$NON-NLS-1$
                totalWork > 0 && totalMs > 0 ? (totalWork * 1000.0 / totalMs) : 0,
                Runtime.getRuntime().availableProcessors()));
            
            // Throw on any exception
            if(!exceptions.isEmpty()) {
                throw exceptions.get(0);
            }
            
            // Return true if any files were written or deleted
            return writtenFiles.size() > 0 || deletedFilesCount.get() > 0;
        } finally {
            // Ensure DirCache resources are cleaned up
            cleanupDirCache();
            
            // Ensure progress reporter is always stopped
            if (fProgressReporter != null) {
                fProgressReporter.finish(null);
            }
        }
    }
    
    /**
     * For each folder inside model, create a directory and a Resource to save it.
     * For each element, create a Resource to save it.
     * 
     * This method collects all work items first, then processes them in parallel batches.
     * Note: Progress is NOT reported here - it's reported in the merged pipeline phase.
     * 
     * @param folderContainer Model or folder to work on 
     * @param folder Directory in which to generate files
     * @throws IOException
     */
    private void createAndSaveResourceForFolder(IFolderContainer folderContainer, File folder) throws IOException {
        // Collect all work items first (quick, single-threaded traversal)
        List<ResourceCreationTask> tasks = new ArrayList<>();
        collectResourceCreationTasks(folderContainer, folder, tasks);
        
        if (tasks.isEmpty()) {
            return;
        }
        
        // Create all unique directories using NIO (fast, no parallelization needed)
        // Collect unique parent directories first to avoid redundant mkdir calls
        Set<java.nio.file.Path> uniqueDirs = new HashSet<>();
        for (ResourceCreationTask task : tasks) {
            java.nio.file.Path parent = task.file.toPath().getParent();
            if (parent != null) {
                uniqueDirs.add(parent);
            }
        }
        
        // Create directories using NIO - Files.createDirectories is idempotent and efficient
        // No parallelization needed: OS handles this efficiently, and the overhead of
        // thread management would exceed the I/O time for directory creation
        for (java.nio.file.Path dir : uniqueDirs) {
            Files.createDirectories(dir);
        }
        
        // PHASE 2: Add all resources to ResourceSet (single-threaded, no synchronization needed)
        // ResourceSet is not thread-safe, so we do this sequentially
        // This is fast CPU work - no I/O blocking
        // Note: Progress is NOT reported here - it's reported in the merged pipeline phase
        // where actual serialization and file I/O happens
        for (ResourceCreationTask task : tasks) {
            createAndSaveResource(task.file, task.object);
        }
    }
    
    /**
     * Recursively collect all resource creation tasks from the folder hierarchy.
     * This is a quick single-threaded traversal that builds the work list.
     */
    private void collectResourceCreationTasks(IFolderContainer folderContainer, File folder, List<ResourceCreationTask> tasks) {
        // Process children folders
        for (IFolder tmpFolder : folderContainer.getFolders()) {
            File tmpFolderFile = new File(folder, getNameFor(tmpFolder));
            // Add task for the folder.xml
            tasks.add(new ResourceCreationTask(new File(tmpFolderFile, IGraficoConstants.FOLDER_XML), tmpFolder));
            // Recurse into subfolder
            collectResourceCreationTasks(tmpFolder, tmpFolderFile, tasks);
        }
        
        // Process elements in folders
        if (folderContainer instanceof IFolder) {
            for (EObject tmpElement : ((IFolder) folderContainer).getElements()) {
                File elementFile = new File(folder, 
                    tmpElement.getClass().getSimpleName() + "_" + ((IIdentifier) tmpElement).getId() + ".xml"); //$NON-NLS-1$ //$NON-NLS-2$
                tasks.add(new ResourceCreationTask(elementFile, tmpElement));
            }
        }
        
        // Process model root
        if (folderContainer instanceof IArchimateModel) {
            tasks.add(new ResourceCreationTask(new File(folder, IGraficoConstants.FOLDER_XML), folderContainer));
        }
    }
    
    /**
     * Helper class for resource creation tasks
     */
    private static class ResourceCreationTask {
        final File file;
        final EObject object;
        
        ResourceCreationTask(File file, EObject object) {
            this.file = file;
            this.object = object;
        }
    }
    
    /**
     * Generate a proper name for directory creation
     *  
     * @param folder
     * @return
     */
    private String getNameFor(IFolder folder) {
    	return folder.getType() == FolderType.USER ? folder.getId().toString() : folder.getType().toString();
    }
    
    /**
     * Save the model to Resource.
     * This method is now called from a single thread, so no synchronization is needed.
     * The parallelization happens at the directory creation and file I/O levels,
     * while ResourceSet operations remain sequential for correctness.
     * 
     * @param file
     * @param object
     * @throws IOException
     */
    private void createAndSaveResource(File file, EObject object) throws IOException {
        // Update the URIConverter
        // Map the logical name (filename) to the physical name (path+filename)
        // Folders must be declared with absolute path or else the 'folder.xml' file is not created
        // The model object must be declared with relative path or else concepts reference profiles through absolute path (which are gonna be different for each users)
        URI key = (!(object instanceof IArchimateModel) && file.getName().equals(IGraficoConstants.FOLDER_XML)) ? URI.createFileURI(file.getAbsolutePath()) : URI.createFileURI(file.getName());
        URI value = URI.createFileURI(file.getAbsolutePath());
        fResourceSet.getURIConverter().getURIMap().put(key, value);

        // Create a new resource for selected file and add object to persist
        XMLResource resource = (XMLResource)fResourceSet.createResource(key);
        
        // Use UTF-8 and don't start with an XML declaration
        resource.getDefaultSaveOptions().put(XMLResource.OPTION_ENCODING, "UTF-8"); //$NON-NLS-1$
        resource.getDefaultSaveOptions().put(XMLResource.OPTION_DECLARE_XML, Boolean.FALSE);
        
        // Make the produced XML easy to read
        resource.getDefaultSaveOptions().put(XMLResource.OPTION_FORMATTED, Boolean.TRUE);
        resource.getDefaultSaveOptions().put(XMLResource.OPTION_LINE_WIDTH, Integer.valueOf(5));
        // Don't use encoded attribute. Needed to have proper references inside Diagrams
        resource.getDefaultSaveOptions().put(XMLResource.OPTION_USE_ENCODED_ATTRIBUTE_STYLE, Boolean.FALSE);
        
        // Use cache for efficient saves
        resource.getDefaultSaveOptions().put(XMLResource.OPTION_CONFIGURATION_CACHE, Boolean.TRUE);
        
        // Use UNIX line endings to avoid EOL diffs
        resource.getDefaultSaveOptions().put(Resource.OPTION_LINE_DELIMITER, "\n"); //$NON-NLS-1$

        // Add the object to the resource
        resource.getContents().add(object);
        
        // Track this file as expected (use Path for faster cleanup lookups)
        addExpectedFile(file.toPath());
    }
      /**
     * Extract and save images used inside a model as separate image files
     * Uses virtual threads for I/O (file reads/writes) and ForkJoinPool for CPU work (hashing)
     * Uses the shared fProgressReporter for progress updates.
     * 
     * @param totalImages Total number of images for progress reporting
     */
    private void saveImages(int totalImages) throws IOException {
        Set<String> processed = new HashSet<>();
        List<CompletableFuture<Void>> writeFutures = new ArrayList<>();
        List<IOException> exceptions = Collections.synchronizedList(new ArrayList<>());

        IArchiveManager archiveManager = (IArchiveManager)fModel.getAdapter(IArchiveManager.class);
        if(archiveManager == null) {
            archiveManager = IArchiveManager.FACTORY.createArchiveManager(fModel);
        }
        
        // Virtual threads for I/O, ForkJoinPool for CPU
        int cpuThreads = Runtime.getRuntime().availableProcessors();
        ForkJoinPool cpuExecutor = new ForkJoinPool(cpuThreads);
        ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
        
        try {
            for(Iterator<EObject> iter = fModel.eAllContents(); iter.hasNext();) {
                EObject eObject = iter.next();
                if(eObject instanceof IDiagramModelImageProvider) {
                    IDiagramModelImageProvider imageProvider = (IDiagramModelImageProvider)eObject;
                    String imagePath = imageProvider.getImagePath();
                    
                    if(imagePath != null && !processed.contains(imagePath)) {
                        processed.add(imagePath);
                        
                        byte[] newBytes = archiveManager.getBytesFromEntry(imagePath);
                        if(newBytes == null) {
                            throw new IOException("Could not get image bytes from image path: " + imagePath); //$NON-NLS-1$
                        }
                        
                        File file = new File(fLocalRepoFolder, imagePath);
                        addExpectedFile(file.toPath());
                        
                        final File targetFile = file;
                        final byte[] contentToWrite = newBytes;
                        
                        // Read existing file (I/O), compare content directly, write if needed (I/O)
                        CompletableFuture<Void> future = CompletableFuture
                            .supplyAsync(() -> {
                                // I/O: Read existing file if it exists
                                if (targetFile.exists()) {
                                    return readFileBytes(targetFile);
                                }
                                return null;
                            }, ioExecutor)
                            .thenApplyAsync(existingBytes -> {
                                // Direct content comparison - faster than hashing when both are in memory
                                if (Arrays.equals(existingBytes, contentToWrite)) {
                                    return null; // Content unchanged
                                }
                                return contentToWrite;
                            }, cpuExecutor)
                            .thenComposeAsync(dataToWrite -> {
                                // I/O: Write file if content changed using async channel
                                if (dataToWrite != null) {
                                    targetFile.getParentFile().mkdirs();
                                    addWrittenFile(targetFile.toPath());
                                    return writeFileAsync(targetFile, dataToWrite);
                                }
                                return CompletableFuture.completedFuture(null);
                            }, ioExecutor)
                            .thenRun(() -> {
                                // Report progress using shared reporter
                                fProgressReporter.incrementBy(1);
                                fProgressReporter.maybeReport(
                                    count -> NLS.bind(Messages.GraficoModelExporter_1, count, totalImages));
                            })
                            .exceptionally(e -> {
                                Throwable cause = e.getCause() != null ? e.getCause() : e;
                                if (cause instanceof IOException) {
                                    exceptions.add((IOException) cause);
                                } else {
                                    exceptions.add(new IOException(cause));
                                }
                                return null;
                            });
                        
                        writeFutures.add(future);
                    }
                }
            }
            
            // Wait for all async writes to complete
            if (!writeFutures.isEmpty()) {
                try {
                    CompletableFuture.allOf(writeFutures.toArray(new CompletableFuture[0])).join();
                } catch (Exception e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException && cause.getCause() instanceof IOException) {
                        exceptions.add((IOException) cause.getCause());
                    }
                }
            }
        } finally {
            cpuExecutor.shutdown();
            ioExecutor.shutdown();
        }
        
        if (!exceptions.isEmpty()) {
            throw exceptions.get(0);
        }
    }
    
    /**
     * Count the number of unique images in the model.
     * Used to estimate total work for progress reporting.
     * 
     * @return Number of unique images
     */
    private int countImages() {
        Set<String> uniquePaths = new HashSet<>();
        for(Iterator<EObject> iter = fModel.eAllContents(); iter.hasNext();) {
            EObject eObject = iter.next();
            if(eObject instanceof IDiagramModelImageProvider) {
                IDiagramModelImageProvider imageProvider = (IDiagramModelImageProvider)eObject;
                String imagePath = imageProvider.getImagePath();
                if(imagePath != null) {
                    uniquePaths.add(imagePath);
                }
            }
        }
        return uniquePaths.size();
    }
    
    /**
     * Count the total number of model elements that will be exported.
     * This includes folders, elements, and model metadata.
     * Used to estimate total work for progress reporting.
     * 
     * @return Estimated number of model files
     */
    private int countModelElements() {
        int count = 1; // For the model itself (folder.xml in root)
        count += countElementsInContainer(fModel);
        return count;
    }
    
    /**
     * Recursively count elements in a folder container.
     */
    private int countElementsInContainer(IFolderContainer container) {
        int count = 0;
        for (IFolder folder : container.getFolders()) {
            count++; // The folder itself (folder.xml)
            if (folder instanceof IFolder) {
                count += ((IFolder) folder).getElements().size(); // Elements in folder
            }
            count += countElementsInContainer(folder); // Recurse
        }
        return count;
    }
    
    /**
     * Helper class for batching resource write tasks
     */
    private static class ResourceWriteTask {
        final Resource resource;
        final File file;
        byte[] existingHash;  // Mutable - filled in after hash phase completes
        
        ResourceWriteTask(Resource resource, File file, byte[] existingHash) {
            this.resource = resource;
            this.file = file;
            this.existingHash = existingHash;
        }
    }
    
    // Use ConcurrentHashMap.newKeySet() for better concurrent scalability than Collections.synchronizedSet()
    // These sets are accessed from multiple threads during parallel I/O operations
    // Using Path instead of File for faster lookups (no object conversion needed in cleanup)
    // Paths are normalized before adding to ensure consistent hashCode/equals
    private Set<java.nio.file.Path> expectedFiles = ConcurrentHashMap.newKeySet();
    private Set<java.nio.file.Path> writtenFiles = ConcurrentHashMap.newKeySet();
    
    // Track directories that contain expected files - used to skip unchanged subtrees during cleanup
    private Set<java.nio.file.Path> expectedDirectories = ConcurrentHashMap.newKeySet();
    
    /**
     * Counter for deleted files during cleanup phase.
     * Used to determine if export made any changes (written OR deleted files).
     * Accessed atomically from cleanup methods.
     */
    private java.util.concurrent.atomic.AtomicInteger deletedFilesCount = new java.util.concurrent.atomic.AtomicInteger(0);
    
    /**
     * Add a path to expectedFiles, normalizing it first for consistent lookups.
     * Also tracks all parent directories for efficient subtree skipping during cleanup.
     */
    private void addExpectedFile(java.nio.file.Path path) {
        java.nio.file.Path normalized = path.normalize();
        expectedFiles.add(normalized);
        
        // Track all parent directories up to root for subtree skipping
        java.nio.file.Path parent = normalized.getParent();
        while (parent != null) {
            expectedDirectories.add(parent);
            parent = parent.getParent();
        }
    }
    
    /**
     * Add a path to writtenFiles, normalizing it first for consistent lookups.
     */
    private void addWrittenFile(java.nio.file.Path path) {
        writtenFiles.add(path.normalize());
    }
    
    // Buffer size for file operations (64KB for better disk throughput)
    private static final int BUFFER_SIZE = 64 * 1024;
    
    /**
     * Calculate optimal batch count based on system resources and file count.
     * 
     * <p>Auto-tuning considers:</p>
     * <ul>
     *   <li><b>OS file descriptor limits:</b>
     *     <ul>
     *       <li>macOS: 256 soft limit (notorious for "too many open files")</li>
     *       <li>Linux: 1024 soft limit (configurable)</li>
     *       <li>Windows: 16,384+ handles (generous)</li>
     *     </ul>
     *   </li>
     *   <li><b>CPU cores:</b> More cores = more useful parallelism</li>
     *   <li><b>File count:</b> Small models don't benefit from many batches</li>
     * </ul>
     * 
     * <p>The batch count determines max concurrent file handles since each batch
     * processes files sequentially (one file handle at a time per batch).</p>
     * 
     * @param fileCount Total number of files to process
     * @param cpuCores Number of available CPU cores
     * @return Optimal number of parallel batches
     */
    private static int calculateOptimalBatchCount(int fileCount, int cpuCores) {
        // Detect OS for file descriptor limits
        String osName = System.getProperty("os.name", "").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
        
        int maxBatches;
        if (osName.contains("mac")) { //$NON-NLS-1$
            // macOS: Conservative limit due to 256 soft file descriptor limit
            // Leave headroom for JVM, network, etc. (use ~60% of limit)
            maxBatches = 150;
        } else if (osName.contains("linux")) { //$NON-NLS-1$
            // Linux: Default 1024 soft limit, but often configurable higher
            // Use ~50% of typical limit
            maxBatches = 500;
        } else {
            // Windows: Testing showed ~1000 batches optimal
            // Beyond 1000, cold cache unchanged but warm cache degrades 10%
            // (1883 batches: 21.6s cold, 1.5s warm vs 974 batches: 21.6s cold, 1.3s warm)
            maxBatches = 1000;
        }
        
        // Scale by CPU cores: more cores = more useful parallelism
        // Use 100x CPU cores as baseline - OS-specific maxBatches caps the result
        int cpuBasedBatches = cpuCores * 100;
        
        // Scale by file count: no point having more batches than files / 10
        
        // Scale by file count: no point having more batches than files / 10
        // (each batch should have at least ~10 files for efficiency)
        int fileBasedBatches = Math.max(1, fileCount / 10);
        
        // Take minimum of all constraints
        int optimalBatches = Math.min(maxBatches, Math.min(cpuBasedBatches, fileBasedBatches));
        
        // Ensure at least cpuCores batches to utilize all cores
        optimalBatches = Math.max(cpuCores, optimalBatches);
        
        // Ensure at least 1 batch
        return Math.max(1, optimalBatches);
    }
    
    /*
     * ============================================================================
     * PERFORMANCE ARCHITECTURE
     * ============================================================================
     * 
     * See PERFORMANCE_RESULTS.md for detailed benchmarks and optimization history.
     * 
     * ARCHITECTURE: BATCH-WRAPPED PIPELINE with AUTO-TUNING
     * - Auto-tuned batch count based on OS and resources (see calculateOptimalBatchCount)
     * - Sequential I/O within each batch (read → serialize → compare → write)
     * - Limited concurrent file handles (one per batch at a time)
     * - Uses blocking I/O within batch (simpler, less OS overhead)
     * 
     * PLATFORM-SPECIFIC LIMITS (tested optimal, not just OS limits):
     * - macOS:   150 batches max (256 file descriptor soft limit)
     * - Linux:   500 batches max (1024 file descriptor soft limit)
     * - Windows: 1000 batches max (beyond 1000 adds overhead, no cold cache benefit)
     * 
     * BENCHMARK RESULTS (28,240 files, 20 CPU cores, Windows, ~1000 batches):
     * - Cold cache: ~21.6 seconds (1,306 files/sec)
     * - Warm cache: ~1.3 seconds (21,925 files/sec)
     * - Cold/Warm ratio: 17x
     * 
     * WHY ~1000 BATCHES IS OPTIMAL:
     * - Enough parallelism to saturate SSD I/O queue
     * - Low CompletableFuture overhead (1000 vs 30,000)
     * - Testing: 1883 batches same cold cache but 10% slower warm cache
     * ============================================================================
     */
    
    /**
     * Read file bytes asynchronously using AsynchronousFileChannel.
     * This provides true async I/O that doesn't block any thread while waiting for disk.
     * 
     * @param file The file to read
     * @return CompletableFuture with the file contents as byte array, or null if reading fails
     */
    private CompletableFuture<byte[]> readFileAsync(File file) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        
        try {
            long fileSize = file.length();
            ByteBuffer buffer = ByteBuffer.allocate((int) fileSize);
            
            AsynchronousFileChannel channel = AsynchronousFileChannel.open(
                file.toPath(), StandardOpenOption.READ);
            
            channel.read(buffer, 0, buffer, new CompletionHandler<Integer, ByteBuffer>() {
                @Override
                public void completed(Integer bytesRead, ByteBuffer buf) {
                    try {
                        channel.close();
                        buf.flip();
                        byte[] bytes = new byte[buf.remaining()];
                        buf.get(bytes);
                        result.complete(bytes);
                    } catch (IOException e) {
                        result.complete(null);
                    }
                }
                
                @Override
                public void failed(Throwable exc, ByteBuffer buf) {
                    try {
                        channel.close();
                    } catch (IOException e) {
                        // Ignore close error
                    }
                    result.complete(null);
                }
            });
        } catch (IOException e) {
            result.complete(null);
        }
        
        return result;
    }
    
    /**
     * Read file bytes - I/O bound, uses AsynchronousFileChannel for true async I/O
     * 
     * @param file The file to read
     * @return The file contents as byte array, or null if reading fails
     */
    private byte[] readFileBytes(File file) {
        try {
            return readFileAsync(file).join();
        } catch (Exception ex) {
            return null;
        }
    }
    
    /**
     * Write file bytes asynchronously using AsynchronousFileChannel.
     * This provides true async I/O that doesn't block any thread while waiting for disk.
     * 
     * @param file The file to write
     * @param data The data to write
     * @return CompletableFuture that completes when write is done
     */
    private CompletableFuture<Void> writeFileAsync(File file, byte[] data) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            
            AsynchronousFileChannel channel = AsynchronousFileChannel.open(
                file.toPath(), 
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
            
            channel.write(buffer, 0, buffer, new CompletionHandler<Integer, ByteBuffer>() {
                @Override
                public void completed(Integer bytesWritten, ByteBuffer buf) {
                    try {
                        channel.close();
                        result.complete(null);
                    } catch (IOException e) {
                        result.completeExceptionally(e);
                    }
                }
                
                @Override
                public void failed(Throwable exc, ByteBuffer buf) {
                    try {
                        channel.close();
                    } catch (IOException e) {
                        // Ignore close error
                    }
                    result.completeExceptionally(exc);
                }
            });
        } catch (IOException e) {
            result.completeExceptionally(e);
        }
        
        return result;
    }
    
    /**
     * Write file bytes asynchronously using AsynchronousFileChannel with direct callback.
     * Uses CountDownLatch for synchronization instead of CompletableFuture wrapper.
     * 
     * <p><b>Why this pattern is more efficient than CompletableFuture wrapping:</b></p>
     * <ul>
     *   <li><b>No object allocation per operation:</b> CompletableFuture allocates ~200 bytes per instance.
     *       With 30,000 files, that's 6MB+ of allocations causing GC pressure.</li>
     *   <li><b>Direct callback:</b> The OS async I/O completion handler directly decrements the latch,
     *       avoiding the CompletableFuture state machine overhead.</li>
     *   <li><b>Simpler synchronization:</b> CountDownLatch is a lightweight primitive with minimal
     *       memory footprint (single AtomicInteger internally).</li>
     *   <li><b>Same async behavior:</b> The calling thread is not blocked during actual disk I/O;
     *       it only waits at latch.await() for all operations to complete.</li>
     * </ul>
     * 
     * <p><b>Pattern usage:</b></p>
     * <pre>
     * CountDownLatch latch = new CountDownLatch(itemCount);
     * for (Item item : items) {
     *     asyncOperationDirect(item, latch, exceptions);
     * }
     * latch.await(); // Wait for all to complete
     * </pre>
     * 
     * @param file The file to write
     * @param data The data to write
     * @param latch CountDownLatch to decrement when write completes (success or failure)
     * @param exceptions Thread-safe list to collect any IOExceptions
     */
    private void writeFileAsyncDirect(File file, byte[] data, CountDownLatch latch, List<IOException> exceptions) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            
            AsynchronousFileChannel channel = AsynchronousFileChannel.open(
                file.toPath(), 
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
            
            channel.write(buffer, 0, null, new CompletionHandler<Integer, Void>() {
                @Override
                public void completed(Integer bytesWritten, Void attachment) {
                    try {
                        channel.close();
                    } catch (IOException e) {
                        exceptions.add(e);
                    } finally {
                        latch.countDown();
                    }
                }
                
                @Override
                public void failed(Throwable exc, Void attachment) {
                    try {
                        channel.close();
                    } catch (IOException e) {
                        // Ignore close error
                    }
                    if (exc instanceof IOException) {
                        exceptions.add((IOException) exc);
                    } else {
                        exceptions.add(new IOException(exc));
                    }
                    latch.countDown();
                }
            });
        } catch (IOException e) {
            exceptions.add(e);
            latch.countDown();
        }
    }
    
    // ================================================================================
    // DirCache Optimization Methods
    // ================================================================================
    // 
    // When exporting to a git repository, we can use the DirCache (git index) to
    // avoid reading file contents from disk. The DirCache contains SHA-1/SHA-256 
    // hashes of all tracked files, allowing fast comparison with newly serialized
    // content by computing the hash of the new content instead of reading the old.
    //
    // PERFORMANCE BENEFIT:
    // - Avoids disk I/O for unchanged files (most files on typical export)
    // - Hash computation is ~10x faster than file read on cold cache
    // - Leverages git's existing index data structure
    //
    // ALGORITHM COMPATIBILITY:
    // - Git repositories may use SHA-1 (legacy) or SHA-256 (modern)
    // - We use ObjectInserter.idFor() which auto-detects the correct algorithm
    // - This ensures hashes match what's stored in DirCache
    // ================================================================================
    
    /**
     * Initialize DirCache optimization for git repositories.
     * Call this at the start of export to enable fast hash-based comparison.
     * 
     * <p>This method:</p>
     * <ol>
     *   <li>Checks if the export folder is a git repository</li>
     *   <li>Opens the Repository and reads the DirCache (index)</li>
     *   <li>Creates an ObjectInserter for computing blob hashes</li>
     * </ol>
     * 
     * <p>If initialization fails (not a git repo, or any error), the exporter
     * falls back to reading file contents from disk for comparison.</p>
     * 
     * @return true if DirCache was successfully initialized, false otherwise
     */
    private boolean initDirCache() {
        if (!GraficoUtils.isGitRepository(fLocalRepoFolder)) {
            logPerfMessage("DirCache: Not a git repository, using file comparison"); //$NON-NLS-1$
            return false;
        }
        
        try {
            fGitRepository = Git.open(fLocalRepoFolder).getRepository();
            fDirCache = DirCache.read(fGitRepository);
            fObjectInserter = fGitRepository.newObjectInserter();
            
            logPerfMessage(String.format("DirCache: Initialized with %d entries", fDirCache.getEntryCount())); //$NON-NLS-1$
            return true;
        } catch (IOException e) {
            // Failed to open repository or read DirCache - fall back to file comparison
            logPerfMessage("DirCache: Failed to initialize (" + e.getMessage() + "), using file comparison"); //$NON-NLS-1$ //$NON-NLS-2$
            cleanupDirCache();
            return false;
        }
    }
    
    /**
     * Clean up DirCache resources (Repository, ObjectInserter).
     * Call this in the finally block of exportModel().
     */
    private void cleanupDirCache() {
        if (fObjectInserter != null) {
            fObjectInserter.close();
            fObjectInserter = null;
        }
        // DirCache doesn't need explicit close
        fDirCache = null;
        if (fGitRepository != null) {
            fGitRepository.close();
            fGitRepository = null;
        }
    }
    
    /**
     * Compute the git blob hash for the given content.
     * Uses the same hash algorithm as the repository (SHA-1 or SHA-256).
     * 
     * <p>Git stores blobs with a header: "blob {size}\0{content}"
     * The ObjectInserter.idFor() method handles this format automatically.</p>
     * 
     * @param content The file content to hash
     * @return The ObjectId representing the blob hash, or null if not using DirCache
     */
    private ObjectId computeGitBlobHash(byte[] content) {
        if (fObjectInserter == null) {
            return null;
        }
        return fObjectInserter.idFor(org.eclipse.jgit.lib.Constants.OBJ_BLOB, content);
    }
    
    /**
     * Get the ObjectId (hash) of a file from the DirCache.
     * 
     * @param file The file to look up
     * @return The ObjectId from the index, or null if the file is not in the index
     */
    private ObjectId getHashFromDirCache(File file) {
        if (fDirCache == null) {
            return null;
        }
        
        // Compute relative path from repo root
        java.nio.file.Path repoRoot = fLocalRepoFolder.toPath();
        java.nio.file.Path filePath = file.toPath();
        java.nio.file.Path relativePath;
        try {
            relativePath = repoRoot.relativize(filePath);
        } catch (IllegalArgumentException e) {
            // File is not under repo root
            return null;
        }
        
        // DirCache uses forward slashes for path separators
        String entryPath = relativePath.toString().replace('\\', '/');
        
        DirCacheEntry entry = fDirCache.getEntry(entryPath);
        if (entry != null) {
            return entry.getObjectId();
        }
        return null;
    }
    
    /**
     * Check if file content has changed using DirCache hash comparison.
     * Falls back to byte comparison if DirCache is not available.
     * 
     * @param file The file to check
     * @param newContent The new content to compare against
     * @return true if content has changed and file needs to be written
     */
    private boolean hasContentChanged(File file, byte[] newContent) {
        // If DirCache is available, use hash comparison (faster, no disk I/O)
        if (fDirCache != null && fObjectInserter != null) {
            ObjectId existingHash = getHashFromDirCache(file);
            if (existingHash != null) {
                ObjectId newHash = computeGitBlobHash(newContent);
                // If hashes match, content is unchanged
                return !existingHash.equals(newHash);
            }
            // File not in index (new file) - needs to be written
            return true;
        }
        
        // Fall back to reading file and comparing bytes
        if (!file.exists()) {
            return true; // New file
        }
        byte[] existingContent = readFileBytes(file);
        return !Arrays.equals(existingContent, newContent);
    }

    /**
     * Clean up any files that were not written in this export.
     * Uses NIO2 Files.walkFileTree() for efficient single-pass deletion.
     * Files are deleted during visitFile(), directories are deleted in postVisitDirectory()
     * (after their contents have been processed), which is safe and efficient.
     * 
     * Optimization: Skips entire subtrees that have no expected files (SKIP_SUBTREE),
     * avoiding traversal of unchanged directory structures.
     */
    private void cleanupObsoleteFiles(File folder) throws IOException {
        if (!folder.exists()) {
            return;
        }
        
        // Single-pass walk: delete obsolete files immediately, delete empty directories after contents processed
        // Uses normalized Path for O(1) set lookups (no File object conversion needed)
        java.nio.file.Path folderPath = folder.toPath().normalize();
        
        Files.walkFileTree(folderPath, new SimpleFileVisitor<java.nio.file.Path>() {
            @Override
            public FileVisitResult preVisitDirectory(java.nio.file.Path dir, BasicFileAttributes attrs) {
                // Skip subtrees that have no expected files - they can't contain anything we need to keep
                // But always visit the root folder itself
                java.nio.file.Path normalizedDir = dir.normalize();
                if (!normalizedDir.equals(folderPath) && !expectedDirectories.contains(normalizedDir)) {
                    // This directory has no expected files - delete entire subtree and skip
                    try {
                        deleteDirectoryRecursively(dir);
                    } catch (IOException e) {
                        // Ignore deletion errors for cleanup
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult visitFile(java.nio.file.Path path, BasicFileAttributes attrs) {
                // Normalize path for consistent lookup (matches how paths were added to sets)
                java.nio.file.Path normalizedPath = path.normalize();
                if (!writtenFiles.contains(normalizedPath) && !expectedFiles.contains(normalizedPath)) {
                    // File is obsolete - delete immediately
                    try {
                        Files.delete(path);
                        deletedFilesCount.incrementAndGet(); // Track for hasChanges detection
                    } catch (IOException e) {
                        // Ignore deletion errors for cleanup
                    }
                }
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult postVisitDirectory(java.nio.file.Path dir, IOException exc) throws IOException {
                // Don't delete the root folder itself
                if (!dir.equals(folderPath)) {
                    // Check if directory is now empty and delete if so
                    // This is safe because postVisitDirectory is called AFTER all contents are processed
                    if (isEmptyDirectory(dir)) {
                        Files.delete(dir);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        
        // Finally check if the root folder itself is empty
        if (folder.exists() && isEmptyDirectory(folder)) {
            Files.delete(folder.toPath());
        }
    }
    
    /**
     * Delete a directory and all its contents recursively.
     * Used for cleaning up entire subtrees that have no expected files.
     * Tracks deleted files count for hasChanges detection.
     */
    private void deleteDirectoryRecursively(java.nio.file.Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<java.nio.file.Path>() {
            @Override
            public FileVisitResult visitFile(java.nio.file.Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                deletedFilesCount.incrementAndGet(); // Track for hasChanges detection
                return FileVisitResult.CONTINUE;
            }
            
            @Override
            public FileVisitResult postVisitDirectory(java.nio.file.Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
    
    /**
     * Check if a directory is empty using NIO2 DirectoryStream.
     * More efficient than File.list() as it doesn't need to create a full array.
     * 
     * @param dir the directory path to check
     * @return true if directory is empty
     */
    private boolean isEmptyDirectory(java.nio.file.Path dir) {
        try (java.nio.file.DirectoryStream<java.nio.file.Path> stream = Files.newDirectoryStream(dir)) {
            return !stream.iterator().hasNext();
        } catch (IOException e) {
            // If we can't check, assume not empty (safer)
            return false;
        }
    }
    
    /**
     * Check if a directory is empty using NIO2 DirectoryStream.
     * Overload for File parameter.
     * 
     * @param dir the directory to check
     * @return true if directory is empty
     */
    private boolean isEmptyDirectory(File dir) {
        return isEmptyDirectory(dir.toPath());
    }
}
