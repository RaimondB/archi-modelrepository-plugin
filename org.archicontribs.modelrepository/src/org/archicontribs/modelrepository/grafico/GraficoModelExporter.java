/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.archicontribs.modelrepository.preferences.IPreferenceConstants;
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

        // Now save all Resources using async I/O for better performance on macOS
        // Use ForkJoinPool for work-stealing which is better for I/O-bound tasks with variable duration
        int maxThreads = ModelRepositoryPlugin.getInstance().getPreferenceStore().getInt(IPreferenceConstants.PREFS_EXPORT_MAX_THREADS);
        ForkJoinPool executor = new ForkJoinPool(maxThreads);
        
        // Cache for file content hashes (SHA-256) to avoid keeping full content in memory
        Map<File, byte[]> existingHashCache = new ConcurrentHashMap<>();
        
        // Pre-compute hashes of existing files in parallel for comparison
        progress.subTask(NLS.bind(Messages.GraficoModelExporter_4, maxThreads));
        
        // Collect all files that need hashing first
        List<File> filesToHash = new ArrayList<>();
        for(Resource resource : fResourceSet.getResources()) {
            URI uri = fResourceSet.getURIConverter().normalize(resource.getURI());
            String filePath = uri.toFileString();
            File file = new File(filePath);
            if (file.exists()) {
                filesToHash.add(file);
            }
        }
        
        // Batch files across threads to reduce scheduling overhead
        // Each thread processes multiple files instead of one task per file
        int batchSize = Math.max(1, (filesToHash.size() + maxThreads - 1) / maxThreads);
        List<CompletableFuture<Void>> hashFutures = new ArrayList<>();
        
        for (int i = 0; i < filesToHash.size(); i += batchSize) {
            final int start = i;
            final int end = Math.min(i + batchSize, filesToHash.size());
            final List<File> batch = filesToHash.subList(start, end);
            
            hashFutures.add(CompletableFuture.runAsync(() -> {
                for (File file : batch) {
                    try {
                        byte[] hash = computeFileHash(file);
                        if (hash != null) {
                            existingHashCache.put(file, hash);
                        }
                    } catch (IOException e) {
                        // File might not exist or be readable, will be written anyway
                    }
                }
            }, executor));
        }
        
        // Wait for all hash computations to complete using allOf (more efficient than checking one-by-one)
        try {
            CompletableFuture.allOf(hashFutures.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            // Continue with export even if some hashes failed
        }
        progress.worked(15);
        
        // Check for cancellation
        if (progress.isCanceled()) {
            executor.shutdownNow();
            return;
        }
        
        // Collect async write operations as CompletableFutures
        // Each future handles its own byte array, allowing GC once write completes
        progress.subTask(NLS.bind(Messages.GraficoModelExporter_5, maxThreads));
        List<IOException> exceptions = Collections.synchronizedList(new ArrayList<>());
        
        int totalResources = fResourceSet.getResources().size();
        SubMonitor writeProgress = progress.split(50);
        writeProgress.setWorkRemaining(totalResources);
        
        // Check for cancellation before starting
        if (writeProgress.isCanceled()) {
            executor.shutdownNow();
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
        
        // Batch resources across threads - serialize AND write in parallel
        int writeBatchSize = Math.max(1, (writeTasks.size() + maxThreads - 1) / maxThreads);
        List<CompletableFuture<Void>> writeFutures = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger(0);
        
        for (int i = 0; i < writeTasks.size(); i += writeBatchSize) {
            final int start = i;
            final int end = Math.min(i + writeBatchSize, writeTasks.size());
            final List<ResourceWriteTask> batch = writeTasks.subList(start, end);
            
            writeFutures.add(CompletableFuture.runAsync(() -> {
                for (ResourceWriteTask task : batch) {
                    try {
                        // Serialize resource to byte array (CPU-intensive, now parallel!)
                        java.io.ByteArrayOutputStream os = new java.io.ByteArrayOutputStream(4096);
                        task.resource.save(os, null);
                        byte[] newContent = os.toByteArray();
                        
                        // Compare hash of new content with cached hash of existing file
                        byte[] newHash = computeHash(newContent);
                        
                        // Only save if content has changed (hash mismatch or file doesn't exist)
                        if (task.existingHash == null || !Arrays.equals(newHash, task.existingHash)) {
                            task.file.getParentFile().mkdirs();
                            writtenFiles.add(task.file);
                            
                            // Write file synchronously within the batch (simpler, still parallel across batches)
                            Files.write(task.file.toPath(), newContent, 
                                StandardOpenOption.CREATE, 
                                StandardOpenOption.WRITE, 
                                StandardOpenOption.TRUNCATE_EXISTING);
                        }
                        // newContent goes out of scope here, eligible for GC
                    } catch(IOException ex) {
                        exceptions.add(ex);
                    }
                    
                    // Update progress periodically
                    int done = completed.incrementAndGet();
                    if (done % 100 == 0) {
                        writeProgress.worked(100);
                    }
                }
            }, executor));
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
        writeProgress.worked(totalResources % 100);
        
        // Shutdown executor
        executor.shutdown();
        try {
            executor.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
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
     * Uses async I/O for better performance on macOS
     * @param monitor Progress monitor for UI feedback, can be null
     */
    private void saveImages(IProgressMonitor monitor) throws IOException {
        SubMonitor progress = SubMonitor.convert(monitor);
        
        Set<String> processed = new HashSet<>();
        List<CompletableFuture<Void>> writeFutures = new ArrayList<>();
        List<IOException> exceptions = new ArrayList<>();

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
        
        // Use ForkJoinPool for work-stealing which is better for I/O-bound tasks with variable duration
        int maxThreads = ModelRepositoryPlugin.getInstance().getPreferenceStore().getInt(IPreferenceConstants.PREFS_EXPORT_MAX_THREADS);
        ForkJoinPool executor = new ForkJoinPool(Math.min(maxThreads, imageCount + 1));
        
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
                            executor.shutdownNow();
                            return;
                        }
                        
                        byte[] newBytes = archiveManager.getBytesFromEntry(imagePath);
                        if(newBytes == null) {
                            throw new IOException("Could not get image bytes from image path: " + imagePath); //$NON-NLS-1$
                        }
                        
                        File file = new File(fLocalRepoFolder, imagePath);
                        expectedFiles.add(file);
                        
                        // Compute hash of existing file if it exists (for comparison)
                        byte[] existingHash = null;
                        if (file.exists()) {
                            try {
                                existingHash = computeFileHash(file);
                            } catch (IOException e) {
                                // Will be overwritten anyway
                            }
                        }
                        
                        // Compare with hash of new content
                        byte[] newHash = computeHash(newBytes);
                        
                        // Only write if different (hash mismatch or file doesn't exist)
                        if (existingHash == null || !Arrays.equals(newHash, existingHash)) {
                            file.getParentFile().mkdirs();
                            writtenFiles.add(file);
                            
                            // Create a CompletableFuture that writes the file
                            // The newBytes reference is captured but will be eligible for GC once the future completes
                            final File targetFile = file;
                            final byte[] contentToWrite = newBytes;
                            
                            writeFutures.add(CompletableFuture.runAsync(() -> {
                                try (AsynchronousFileChannel channel = AsynchronousFileChannel.open(
                                        targetFile.toPath(),
                                        EnumSet.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
                                        executor)) {
                                    ByteBuffer buffer = ByteBuffer.wrap(contentToWrite);
                                    channel.write(buffer, 0).get();
                                } catch (IOException | InterruptedException | ExecutionException e) {
                                    throw new RuntimeException(e);
                                }
                            }, executor));
                        }
                        // If not writing, newBytes goes out of scope here and can be GC'd immediately
                        
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
            executor.shutdown();
            try {
                executor.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
     * Compute SHA-256 hash of a file using streaming to avoid loading entire file into memory.
     * 
     * @param file The file to hash
     * @return The SHA-256 hash as byte array, or null if hashing fails
     * @throws IOException if file cannot be read
     */
    private byte[] computeFileHash(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
            // Use larger buffer (64KB) for better disk I/O throughput
            try (InputStream is = new java.io.BufferedInputStream(Files.newInputStream(file.toPath()), 65536)) {
                byte[] buffer = new byte[65536];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in Java, this should never happen
            throw new RuntimeException("SHA-256 algorithm not available", e); //$NON-NLS-1$
        }
    }
    
    /**
     * Compute SHA-256 hash of a byte array.
     * 
     * @param data The data to hash
     * @return The SHA-256 hash as byte array
     */
    private byte[] computeHash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
            return digest.digest(data);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in Java, this should never happen
            throw new RuntimeException("SHA-256 algorithm not available", e); //$NON-NLS-1$
        }
    }
}
