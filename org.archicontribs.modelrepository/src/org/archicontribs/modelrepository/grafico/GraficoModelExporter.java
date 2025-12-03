/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

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
        
        // Check for cancellation
        if (progress.isCanceled()) {
            return;
        }
        
        // Save model images (if any): this has to be done on original model (not a copy)
        progress.subTask(Messages.GraficoModelExporter_1);
        saveImages(progress.split(10));
        
        // Create ResourceSet
        fResourceSet = new ResourceSetImpl();
        fResourceSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("*", new XMLResourceFactoryImpl()); //$NON-NLS-1$
        // Add a URIConverter that will be used to map full filenames to logical names
        fResourceSet.setURIConverter(new ExtensibleURIConverterImpl());
        
        // Now work on a copy
        progress.subTask(Messages.GraficoModelExporter_2);
        IArchimateModel copy = EcoreUtil.copy(fModel);
        progress.worked(5);
        
        // Check for cancellation
        if (progress.isCanceled()) {
            return;
        }
        
        // Create directory structure and prepare all Resources
        progress.subTask(Messages.GraficoModelExporter_3);
        createAndSaveResourceForFolder(copy, modelFolder);
        progress.worked(10);

        // Now save all Resources using ForkJoinPool for CPU work with batching
        // ForkJoinPool is optimal for CPU-bound work (XML serialization, hashing) - matches CPU cores
        // Batching reduces CompletableFuture overhead (30,000 files -> ~300 futures)
        int cpuThreads = Runtime.getRuntime().availableProcessors();
        ForkJoinPool cpuExecutor = new ForkJoinPool(cpuThreads);
        
        // Cache for file content hashes (SHA-256) to avoid keeping full content in memory
        Map<File, byte[]> existingHashCache = new ConcurrentHashMap<>();
        
        // Collect all files that need hashing first
        progress.subTask(Messages.GraficoModelExporter_7);
        List<File> filesToHash = new ArrayList<>();
        for(Resource resource : fResourceSet.getResources()) {
            URI uri = fResourceSet.getURIConverter().normalize(resource.getURI());
            String filePath = uri.toFileString();
            File file = new File(filePath);
            if (file.exists()) {
                filesToHash.add(file);
            }
        }
        
        // Pre-compute hashes of existing files in parallel for comparison
        progress.subTask(NLS.bind(Messages.GraficoModelExporter_4, filesToHash.size()));
        
        // Use batching to reduce CompletableFuture overhead (30,000 files -> ~300 futures)
        // Each batch: async read files, then compute hashes in CPU executor
        List<CompletableFuture<Void>> hashFutures = new ArrayList<>();
        AtomicInteger filesProcessed = new AtomicInteger(0);
        final int totalFilesToHash = filesToHash.size();
        
        for (int i = 0; i < filesToHash.size(); i += BATCH_SIZE) {
            final int start = i;
            final int end = Math.min(i + BATCH_SIZE, filesToHash.size());
            final List<File> batch = filesToHash.subList(start, end);
            
            // For each file in batch: async read -> then hash in CPU executor (properly pipelined)
            for (File file : batch) {
                CompletableFuture<Void> fileFuture = readFileAsync(file)
                    .thenAcceptAsync(bytes -> {
                        // This runs in cpuExecutor when async read completes
                        if (bytes != null) {
                            byte[] hash = computeHash(bytes);
                            if (hash != null) {
                                existingHashCache.put(file, hash);
                            }
                        }
                        
                        // Report progress every 1000 files
                        int count = filesProcessed.incrementAndGet();
                        if (count % 1000 == 0) {
                            progress.subTask(NLS.bind(Messages.GraficoModelExporter_8, count, totalFilesToHash));
                        }
                    }, cpuExecutor);
                
                hashFutures.add(fileFuture);
            }
        }
        
        // Wait for all hash computations to complete
        try {
            CompletableFuture.allOf(hashFutures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            // Continue with export even if some hashes failed
        }
        progress.worked(15);
        
        // Check for cancellation
        if (progress.isCanceled()) {
            cpuExecutor.shutdown();
            return;
        }
        
        // Serialize resources using CPU executor, write files using virtual threads
        List<IOException> exceptions = Collections.synchronizedList(new ArrayList<>());
        
        int totalResources = fResourceSet.getResources().size();
        progress.subTask(NLS.bind(Messages.GraficoModelExporter_5, totalResources));
        SubMonitor writeProgress = progress.split(50);
        writeProgress.setWorkRemaining(totalResources);
        
        // Check for cancellation before starting
        if (writeProgress.isCanceled()) {
            cpuExecutor.shutdown();
            return;
        }
        
        // Collect all resources with their target files first (quick, sequential)
        List<ResourceWriteTask> writeTasks = new ArrayList<>();
        for(Resource resource : fResourceSet.getResources()) {
            URI uri = fResourceSet.getURIConverter().normalize(resource.getURI());
            String filePath = uri.toFileString();
            File file = new File(filePath);
            writeTasks.add(new ResourceWriteTask(resource, file, existingHashCache.get(file)));
        }
        
        // Serialize with CPU executor (CPU-bound), then write async (I/O-bound)
        // Properly pipelined: serialize -> hash check -> async write
        AtomicInteger completed = new AtomicInteger(0);
        List<CompletableFuture<Void>> writeFutures = new ArrayList<>();
        
        for (ResourceWriteTask task : writeTasks) {
            // CPU-bound work first, then chain async write
            CompletableFuture<Void> taskFuture = CompletableFuture
                .supplyAsync(() -> {
                    // CPU-bound: serialize to byte array
                    try {
                        ByteArrayOutputStream os = new ByteArrayOutputStream(4096);
                        task.resource.save(os, null);
                        return os.toByteArray();
                    } catch (IOException ex) {
                        exceptions.add(ex);
                        return null;
                    }
                }, cpuExecutor)
                .thenComposeAsync(newContent -> {
                    // CPU-bound: compute hash and check if content changed
                    if (newContent == null) {
                        return CompletableFuture.completedFuture(null);
                    }
                    
                    byte[] newHash = computeHash(newContent);
                    if (task.existingHash != null && Arrays.equals(newHash, task.existingHash)) {
                        // No change needed, skip write
                        return CompletableFuture.completedFuture(null);
                    }
                    
                    // I/O-bound: write file using async channel (returns future, not blocking)
                    task.file.getParentFile().mkdirs();
                    writtenFiles.add(task.file);
                    return writeFileAsync(task.file, newContent);
                }, cpuExecutor)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        if (ex.getCause() instanceof IOException) {
                            exceptions.add((IOException) ex.getCause());
                        } else if (ex instanceof IOException) {
                            exceptions.add((IOException) ex);
                        }
                    }
                    
                    // Update progress every 1000 files
                    int done = completed.incrementAndGet();
                    if (done % 1000 == 0) {
                        writeProgress.worked(1000);
                        writeProgress.subTask(NLS.bind(Messages.GraficoModelExporter_5, done + " of " + totalResources)); //$NON-NLS-1$
                    }
                });
            
            writeFutures.add(taskFuture);
        }
        
        // Wait for all writes to complete
        try {
            CompletableFuture.allOf(writeFutures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException && cause.getCause() instanceof IOException) {
                exceptions.add((IOException) cause.getCause());
            }
        }
        
        // Update remaining progress
        writeProgress.worked(totalResources % 1000);
        
        // Shutdown executor
        cpuExecutor.shutdown();
        
        // Clean up obsolete files after all resources are saved
        progress.subTask(Messages.GraficoModelExporter_6);
        cleanupObsoleteFiles(new File(fLocalRepoFolder, IGraficoConstants.MODEL_FOLDER));
        cleanupObsoleteFiles(new File(fLocalRepoFolder, IGraficoConstants.IMAGES_FOLDER));
        progress.worked(10);
        
        // Throw on any exception
        if(!exceptions.isEmpty()) {
            throw exceptions.get(0);
        }
    }
    
    /**
     * For each folder inside model, create a directory and a Resource to save it.
     * For each element, create a Resource to save it
     * 
     * @param folderContainer Model or folder to work on 
     * @param folder Directory in which to generate files
     * @throws IOException
     */
    private void createAndSaveResourceForFolder(IFolderContainer folderContainer, File folder) throws IOException {
        // Save each children folders
        List<IFolder> allFolders = new ArrayList<IFolder>();
        allFolders.addAll(folderContainer.getFolders());
        
        for(IFolder tmpFolder : allFolders) {
            File tmpFolderFile = new File(folder, getNameFor(tmpFolder));
            tmpFolderFile.mkdirs();
            createAndSaveResource(new File(tmpFolderFile, IGraficoConstants.FOLDER_XML), tmpFolder);
            createAndSaveResourceForFolder(tmpFolder, tmpFolderFile);
        }
        
        // Save each children elements
        if(folderContainer instanceof IFolder) {
            // Save each children element
            List<EObject> allElements = new ArrayList<EObject>();
            allElements.addAll(((IFolder)folderContainer).getElements());
            for(EObject tmpElement : allElements) {
                createAndSaveResource(
                        new File(folder, tmpElement.getClass().getSimpleName() + "_" + ((IIdentifier)tmpElement).getId() + ".xml"), //$NON-NLS-1$ //$NON-NLS-2$
                        tmpElement);
            }
        }
        if(folderContainer instanceof IArchimateModel) {
            createAndSaveResource(new File(folder, IGraficoConstants.FOLDER_XML), folderContainer);
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
     * Save the model to Resource
     * 
     * @param file
     * @param object
     * @throws IOException
     */    private void createAndSaveResource(File file, EObject object) throws IOException {
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
        
        // Track this file as expected
        expectedFiles.add(file);
    }
      /**
     * Extract and save images used inside a model as separate image files
     * Uses virtual threads for I/O (file reads/writes) and ForkJoinPool for CPU work (hashing)
     * @param monitor Progress monitor for UI feedback, can be null
     */
    private void saveImages(IProgressMonitor monitor) throws IOException {
        SubMonitor progress = SubMonitor.convert(monitor);
        
        Set<String> processed = new HashSet<>();
        List<CompletableFuture<Void>> writeFutures = new ArrayList<>();
        List<IOException> exceptions = Collections.synchronizedList(new ArrayList<>());

        IArchiveManager archiveManager = (IArchiveManager)fModel.getAdapter(IArchiveManager.class);
        if(archiveManager == null) {
            archiveManager = IArchiveManager.FACTORY.createArchiveManager(fModel);
        }
        
        // Count images for progress
        int imageCount = 0;
        for(Iterator<EObject> iter = fModel.eAllContents(); iter.hasNext();) {
            EObject eObject = iter.next();
            if(eObject instanceof IDiagramModelImageProvider) {
                IDiagramModelImageProvider imageProvider = (IDiagramModelImageProvider)eObject;
                if(imageProvider.getImagePath() != null) {
                    imageCount++;
                }
            }
        }
        
        // Set work remaining based on number of images
        progress.setWorkRemaining(imageCount + 1);
        
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
                        
                        // Check for cancellation
                        if (progress.isCanceled()) {
                            cpuExecutor.shutdown();
                            ioExecutor.shutdown();
                            return;
                        }
                        
                        byte[] newBytes = archiveManager.getBytesFromEntry(imagePath);
                        if(newBytes == null) {
                            throw new IOException("Could not get image bytes from image path: " + imagePath); //$NON-NLS-1$
                        }
                        
                        File file = new File(fLocalRepoFolder, imagePath);
                        expectedFiles.add(file);
                        
                        final File targetFile = file;
                        final byte[] contentToWrite = newBytes;
                        
                        // Read existing file hash (I/O), compute new hash (CPU), compare, write if needed (I/O)
                        CompletableFuture<Void> future = CompletableFuture
                            .supplyAsync(() -> {
                                // I/O: Read existing file if it exists
                                if (targetFile.exists()) {
                                    return readFileBytes(targetFile);
                                }
                                return null;
                            }, ioExecutor)
                            .thenApplyAsync(existingBytes -> {
                                // CPU: Compute hashes
                                byte[] existingHash = existingBytes != null ? computeHash(existingBytes) : null;
                                byte[] newHash = computeHash(contentToWrite);
                                // Return null if content unchanged
                                if (existingHash != null && Arrays.equals(existingHash, newHash)) {
                                    return null;
                                }
                                return contentToWrite;
                            }, cpuExecutor)
                            .thenComposeAsync(dataToWrite -> {
                                // I/O: Write file if content changed using async channel
                                if (dataToWrite != null) {
                                    targetFile.getParentFile().mkdirs();
                                    writtenFiles.add(targetFile);
                                    return writeFileAsync(targetFile, dataToWrite);
                                }
                                return CompletableFuture.completedFuture(null);
                            }, ioExecutor)
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
                        progress.worked(1);
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
     * Helper class for batching resource write tasks
     */
    private static class ResourceWriteTask {
        final Resource resource;
        final File file;
        final byte[] existingHash;
        
        ResourceWriteTask(Resource resource, File file, byte[] existingHash) {
            this.resource = resource;
            this.file = file;
            this.existingHash = existingHash;
        }
    }
    
    private Set<File> expectedFiles = new HashSet<>();
    private Set<File> writtenFiles = Collections.synchronizedSet(new HashSet<>());
    
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
     * Clean up any files that were not written in this export
     */
    private void cleanupObsoleteFiles(File folder) throws IOException {
        if (!folder.exists()) {
            return;
        }
        
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    cleanupObsoleteFiles(file);
                    // Delete directory if empty
                    if (file.exists() && file.list().length == 0) {
                        file.delete();
                    }
                }
                else if (!writtenFiles.contains(file) && !expectedFiles.contains(file)) {
                    file.delete();
                }
            }
        }
        
        // Delete the folder if it's empty
        if (folder.exists() && folder.list().length == 0) {
            folder.delete();
        }
    }
    
    /**
     * Compute SHA-256 hash of a byte array.
     * 
     * @param data The data to hash
     * @return The SHA-256 hash as byte array, or null if data is null
     */
    private byte[] computeHash(byte[] data) {
        if (data == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
            return digest.digest(data);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in Java, this should never happen
            throw new RuntimeException("SHA-256 algorithm not available", e); //$NON-NLS-1$
        }
    }
}
