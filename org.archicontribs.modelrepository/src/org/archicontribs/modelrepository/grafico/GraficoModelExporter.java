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

import org.eclipse.core.runtime.IProgressMonitor;
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
     * @throws IOException
     */
    public void exportModel() throws IOException {
        exportModel(null);
    }
    
    /**
     * Export the IArchimateModel as Grafico files with progress monitoring
     * @param monitor Progress monitor for UI feedback, can be null
     * @throws IOException
     */
    public void exportModel(IProgressMonitor monitor) throws IOException {
        // Use SubMonitor for easier progress reporting
        SubMonitor progress = SubMonitor.convert(monitor, Messages.GraficoModelExporter_0, 100);
        
        // Define target folders for model and images
        File modelFolder = new File(fLocalRepoFolder, IGraficoConstants.MODEL_FOLDER);
        File imagesFolder = new File(fLocalRepoFolder, IGraficoConstants.IMAGES_FOLDER);
        
        // Ensure directories exist
        modelFolder.mkdirs();
        imagesFolder.mkdirs();
        
        // Clear tracking sets
        expectedFiles.clear();
        writtenFiles.clear();
        expectedDirectories.clear();
        
        // Check for cancellation
        if (progress.isCanceled()) {
            return;
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
        fProgressReporter = new ThrottledProgressReporter(progress.split(85), totalWork);
        
        try {
            // Save model images (if any): this has to be done on original model (not a copy)
            // Uses shared fProgressReporter for progress updates
            fProgressReporter.subTask(NLS.bind(Messages.GraficoModelExporter_1, 0, totalImages));
            saveImages(totalImages);
            
            // Check for cancellation
            if (fProgressReporter.isCanceled()) {
                return;
            }
            
            // Create ResourceSet
            fProgressReporter.subTask(Messages.GraficoModelExporter_2);
            fResourceSet = new ResourceSetImpl();
            fResourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("*", new XMLResourceFactoryImpl()); //$NON-NLS-1$
            // Add a URIConverter that will be used to map full filenames to logical names
            fResourceSet.setURIConverter(new ExtensibleURIConverterImpl());
            
            // Now work on a copy
            IArchimateModel copy = EcoreUtil.copy(fModel);
            
            // Check for cancellation
            if (fProgressReporter.isCanceled()) {
                return;
            }
            
            // Create directory structure and prepare all Resources
            fProgressReporter.subTask(NLS.bind(Messages.GraficoModelExporter_3, 0, totalModelFiles));
            createAndSaveResourceForFolder(copy, modelFolder, totalModelFiles);

            // MERGED PIPELINE: Read existing → Serialize → Hash both → Write if different
            // This is more efficient than separate hash and write phases because:
            // 1. Avoids storing all hashes in memory (ConcurrentHashMap overhead)
            // 2. Writes immediately while serialized content is still in memory
            // 3. Single pass through resources instead of two passes
            // 
            // ForkJoinPool is optimal for CPU-bound work (XML serialization, hashing) - matches CPU cores
            // Batching reduces CompletableFuture overhead (30,000 files -> ~300 futures)
            int cpuThreads = Runtime.getRuntime().availableProcessors();
            ForkJoinPool cpuExecutor = new ForkJoinPool(cpuThreads);
            
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
                return;
            }
            
            // MERGED BATCHING: One CompletableFuture per batch (30,000 files -> ~300 futures)
            // Each batch: read existing files → serialize → hash compare → write if changed
            // This keeps serialized content in memory only until write completes
            List<CompletableFuture<Void>> pipelineFutures = new ArrayList<>();
            
            // Announce the merged phase
            fProgressReporter.subTask(NLS.bind(Messages.GraficoModelExporter_5, 0, totalResources));
            
            for (int i = 0; i < writeTasks.size(); i += BATCH_SIZE) {
                final int start = i;
                final int end = Math.min(i + BATCH_SIZE, writeTasks.size());
                final List<ResourceWriteTask> batch = writeTasks.subList(start, end);
                
                // ONE future per batch - handles entire pipeline: read → serialize → hash → write
                CompletableFuture<Void> batchFuture = CompletableFuture.supplyAsync(() -> {
                    // STEP 1: Start async reads for all existing files in batch
                    // This fires off all I/O requests in parallel
                    List<CompletableFuture<byte[]>> existingContentFutures = new ArrayList<>();
                    for (ResourceWriteTask task : batch) {
                        if (task.file.exists()) {
                            existingContentFutures.add(readFileAsync(task.file));
                        } else {
                            existingContentFutures.add(CompletableFuture.completedFuture(null));
                        }
                    }
                    
                    // Wait for all reads to complete (disk I/O happens in parallel)
                    CompletableFuture.allOf(existingContentFutures.toArray(new CompletableFuture[0])).join();
                    
                    // STEP 2: Serialize and compare content directly (CPU-bound)
                    // Since we have both existing and new content in memory, direct comparison
                    // is faster than computing SHA-256 hashes (avoids ~2000 CPU cycles per file)
                    List<FileWriteRequest> writeRequests = new ArrayList<>();
                    
                    for (int j = 0; j < batch.size(); j++) {
                        ResourceWriteTask task = batch.get(j);
                        try {
                            // Get existing content (already read async)
                            byte[] existingContent = existingContentFutures.get(j).join();
                            
                            // CPU-bound: serialize to byte array
                            ByteArrayOutputStream os = new ByteArrayOutputStream(4096);
                            task.resource.save(os, null);
                            byte[] newContent = os.toByteArray();
                            
                            // Direct content comparison - faster than hashing when both are in memory
                            if (!Arrays.equals(existingContent, newContent)) {
                                // Content changed - queue for async write (content still in memory)
                                task.file.getParentFile().mkdirs();
                                addWrittenFile(task.file.toPath());
                                writeRequests.add(new FileWriteRequest(task.file, newContent));
                            }
                            // If unchanged, newContent is released immediately (no storage)
                        } catch (IOException ex) {
                            exceptions.add(ex);
                        }
                    }
                    
                    if (writeRequests.isEmpty()) {
                        return null; // No writes needed for this batch
                    }
                    
                    // STEP 3: Fire async writes directly using CountDownLatch
                    CountDownLatch writeLatch = new CountDownLatch(writeRequests.size());
                    
                    for (FileWriteRequest req : writeRequests) {
                        writeFileAsyncDirect(req.file, req.content, writeLatch, exceptions);
                    }
                    
                    // Wait for all writes in this batch to complete
                    try {
                        writeLatch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    
                    return null;
                }, cpuExecutor)
                .thenRun(() -> {
                    // Report progress after batch completes - reduces UI thread contention
                    fProgressReporter.incrementBy(batch.size());
                    fProgressReporter.maybeReport(
                        count -> NLS.bind(Messages.GraficoModelExporter_5, count, totalResources));
                });
                
                pipelineFutures.add(batchFuture);
            }
            
            // Wait for all pipeline operations to complete
            try {
                CompletableFuture.allOf(pipelineFutures.toArray(new CompletableFuture[0])).join();
            } catch (Exception e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException && cause.getCause() instanceof IOException) {
                    exceptions.add((IOException) cause.getCause());
                }
            }
            
            // Shutdown executor
            cpuExecutor.shutdown();
            
            // Clean up obsolete files after all resources are saved
            fProgressReporter.subTask(Messages.GraficoModelExporter_6);
            cleanupObsoleteFiles(new File(fLocalRepoFolder, IGraficoConstants.MODEL_FOLDER));
            cleanupObsoleteFiles(new File(fLocalRepoFolder, IGraficoConstants.IMAGES_FOLDER));
            
            // Throw on any exception
            if(!exceptions.isEmpty()) {
                throw exceptions.get(0);
            }
        } finally {
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
     * 
     * @param folderContainer Model or folder to work on 
     * @param folder Directory in which to generate files
     * @param totalModelFiles Total number of model files for progress reporting
     * @throws IOException
     */
    private void createAndSaveResourceForFolder(IFolderContainer folderContainer, File folder, int totalModelFiles) throws IOException {
        // Collect all work items first (quick, single-threaded traversal)
        List<ResourceCreationTask> tasks = new ArrayList<>();
        collectResourceCreationTasks(folderContainer, folder, tasks);
        
        if (tasks.isEmpty()) {
            return;
        }
        
        // PHASE 1: Create all directories in parallel using virtual threads (I/O-bound)
        // This is the only parallelizable part - directory creation has no synchronization needs
        ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
        List<CompletableFuture<Void>> dirFutures = new ArrayList<>();
        
        try {
            for (int i = 0; i < tasks.size(); i += BATCH_SIZE) {
                final int start = i;
                final int end = Math.min(i + BATCH_SIZE, tasks.size());
                final List<ResourceCreationTask> batch = tasks.subList(start, end);
                
                // Batch directory creation - only I/O, no shared state
                CompletableFuture<Void> batchFuture = CompletableFuture.runAsync(() -> {
                    for (ResourceCreationTask task : batch) {
                        task.file.getParentFile().mkdirs();
                    }
                }, ioExecutor);
                
                dirFutures.add(batchFuture);
            }
            
            // Wait for all directories to be created
            CompletableFuture.allOf(dirFutures.toArray(new CompletableFuture[0])).join();
        } finally {
            ioExecutor.shutdown();
        }
        
        // PHASE 2: Add all resources to ResourceSet (single-threaded, no synchronization needed)
        // ResourceSet is not thread-safe, so we do this sequentially
        // This is fast CPU work - no I/O blocking
        int processedCount = 0;
        for (ResourceCreationTask task : tasks) {
            createAndSaveResource(task.file, task.object);
            
            // Report progress periodically (every BATCH_SIZE items)
            processedCount++;
            if (processedCount % BATCH_SIZE == 0 || processedCount == tasks.size()) {
                final int count = processedCount;
                fProgressReporter.incrementBy(Math.min(BATCH_SIZE, count - ((count / BATCH_SIZE - 1) * BATCH_SIZE)));
                fProgressReporter.maybeReport(
                    c -> NLS.bind(Messages.GraficoModelExporter_3, count, totalModelFiles));
            }
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
    
    /**
     * Helper class for async file write requests
     */
    private static class FileWriteRequest {
        final File file;
        final byte[] content;
        
        FileWriteRequest(File file, byte[] content) {
            this.file = file;
            this.content = content;
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
    
    // Batch size for CompletableFuture operations (reduces overhead from 30,000 futures to ~300)
    private static final int BATCH_SIZE = 100;
    
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
     */
    private void deleteDirectoryRecursively(java.nio.file.Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<java.nio.file.Path>() {
            @Override
            public FileVisitResult visitFile(java.nio.file.Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
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
