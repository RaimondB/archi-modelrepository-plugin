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
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.archicontribs.modelrepository.preferences.IPreferenceConstants;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
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
     * Thread-safe holder for async write results
     */
    private static class AsyncWriteResult {
        final File file;
        final Future<Integer> future;
        final AsynchronousFileChannel channel;
        
        AsyncWriteResult(File file, Future<Integer> future, AsynchronousFileChannel channel) {
            this.file = file;
            this.future = future;
            this.channel = channel;
        }
    }
    
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
        int maxThreads = ModelRepositoryPlugin.getInstance().getPreferenceStore().getInt(IPreferenceConstants.PREFS_EXPORT_MAX_THREADS);
        ExecutorService executor = Executors.newFixedThreadPool(maxThreads);
        
        // Cache for file content hashes (SHA-256) to avoid keeping full content in memory
        Map<File, byte[]> existingHashCache = new ConcurrentHashMap<>();
        
        // Pre-compute hashes of existing files in parallel for comparison
        progress.subTask(Messages.GraficoModelExporter_4);
        List<Future<?>> readFutures = new ArrayList<>();
        for(Resource resource : fResourceSet.getResources()) {
            URI uri = fResourceSet.getURIConverter().normalize(resource.getURI());
            String filePath = uri.toFileString();
            File file = new File(filePath);
            
            if (file.exists()) {
                readFutures.add(executor.submit(() -> {
                    try {
                        byte[] hash = computeFileHash(file);
                        if (hash != null) {
                            existingHashCache.put(file, hash);
                        }
                    } catch (IOException e) {
                        // File might not exist or be readable, will be written anyway
                    }
                }));
            }
        }
        
        // Wait for all hash computations to complete
        for (Future<?> future : readFutures) {
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                // Continue with export
            }
        }
        progress.worked(15);
        
        // Check for cancellation
        if (progress.isCanceled()) {
            executor.shutdownNow();
            return;
        }
        
        // Collect async write operations
        progress.subTask(Messages.GraficoModelExporter_5);
        List<AsyncWriteResult> asyncWrites = new ArrayList<>();
        IOException firstException = null;
        
        int totalResources = fResourceSet.getResources().size();
        SubMonitor writeProgress = progress.split(50);
        writeProgress.setWorkRemaining(totalResources);
        
        for(Resource resource : fResourceSet.getResources()) {
            // Check for cancellation periodically
            if (writeProgress.isCanceled()) {
                executor.shutdownNow();
                return;
            }
            
            try {
                // Get the file for this resource
                URI uri = fResourceSet.getURIConverter().normalize(resource.getURI());
                String filePath = uri.toFileString();
                File file = new File(filePath);
                
                // Serialize resource to byte array
                java.io.ByteArrayOutputStream os = new java.io.ByteArrayOutputStream(4096);
                resource.save(os, null);
                byte[] newContent = os.toByteArray();
                
                // Compare hash of new content with cached hash of existing file
                byte[] existingHash = existingHashCache.get(file);
                byte[] newHash = computeHash(newContent);
                
                // Only save if content has changed (hash mismatch or file doesn't exist)
                if (existingHash == null || !Arrays.equals(newHash, existingHash)) {
                    file.getParentFile().mkdirs();
                    
                    // Use async file channel for non-blocking write
                    Path path = file.toPath();
                    AsynchronousFileChannel channel = AsynchronousFileChannel.open(
                        path,
                        EnumSet.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
                        executor
                    );
                    
                    ByteBuffer buffer = ByteBuffer.wrap(newContent);
                    Future<Integer> writeFuture = channel.write(buffer, 0);
                    asyncWrites.add(new AsyncWriteResult(file, writeFuture, channel));
                    writtenFiles.add(file);
                }
            } catch(IOException ex) {
                if (firstException == null) {
                    firstException = ex;
                }
            }
            
            writeProgress.worked(1);
        }
        
        // Wait for all async writes to complete and close channels
        for (AsyncWriteResult writeResult : asyncWrites) {
            try {
                writeResult.future.get(); // Wait for write to complete
            } catch (InterruptedException | ExecutionException e) {
                if (firstException == null && e.getCause() instanceof IOException) {
                    firstException = (IOException) e.getCause();
                }
            } finally {
                try {
                    writeResult.channel.close();
                } catch (IOException e) {
                    // Ignore close errors
                }
            }
        }
        
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
        if(firstException != null) {
            throw firstException;
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
        List<AsyncWriteResult> asyncWrites = new ArrayList<>();

        IArchiveManager archiveManager = (IArchiveManager)fModel.getAdapter(IArchiveManager.class);
        if(archiveManager == null) {
            archiveManager = IArchiveManager.FACTORY.createArchiveManager(fModel);
        }
        
        // Collect all image data first
        List<ImageWriteTask> imageTasks = new ArrayList<>();
        
        for(Iterator<EObject> iter = fModel.eAllContents(); iter.hasNext();) {
            EObject eObject = iter.next();
            if(eObject instanceof IDiagramModelImageProvider) {
                IDiagramModelImageProvider imageProvider = (IDiagramModelImageProvider)eObject;
                String imagePath = imageProvider.getImagePath();
                
                if(imagePath != null && !processed.contains(imagePath)) {
                    byte[] newBytes = archiveManager.getBytesFromEntry(imagePath);
                    if(newBytes == null) {
                        throw new IOException("Could not get image bytes from image path: " + imagePath); //$NON-NLS-1$
                    }
                    
                    File file = new File(fLocalRepoFolder, imagePath);
                    expectedFiles.add(file);
                    imageTasks.add(new ImageWriteTask(file, newBytes));
                    processed.add(imagePath);
                }
            }
        }
        
        // Set work remaining based on number of images
        progress.setWorkRemaining(imageTasks.size() + 1);
        
        // Use thread pool for async writes
        int maxThreads = ModelRepositoryPlugin.getInstance().getPreferenceStore().getInt(IPreferenceConstants.PREFS_EXPORT_MAX_THREADS);
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(maxThreads, imageTasks.size() + 1));
        IOException firstException = null;
        
        try {
            for (ImageWriteTask task : imageTasks) {
                // Check for cancellation
                if (progress.isCanceled()) {
                    executor.shutdownNow();
                    return;
                }
                
                // Compute hash of existing file if it exists (for comparison)
                byte[] existingHash = null;
                if (task.file.exists()) {
                    try {
                        existingHash = computeFileHash(task.file);
                    } catch (IOException e) {
                        // Will be overwritten anyway
                    }
                }
                
                // Compare with hash of new content
                byte[] newHash = computeHash(task.newBytes);
                
                // Only write if different (hash mismatch or file doesn't exist)
                if (existingHash == null || !Arrays.equals(newHash, existingHash)) {
                    task.file.getParentFile().mkdirs();
                    
                    // Use async file channel for non-blocking write
                    Path path = task.file.toPath();
                    AsynchronousFileChannel channel = AsynchronousFileChannel.open(
                        path,
                        EnumSet.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
                        executor
                    );
                    
                    ByteBuffer buffer = ByteBuffer.wrap(task.newBytes);
                    Future<Integer> writeFuture = channel.write(buffer, 0);
                    asyncWrites.add(new AsyncWriteResult(task.file, writeFuture, channel));
                    writtenFiles.add(task.file);
                }
                
                progress.worked(1);
            }
            
            // Wait for all async writes to complete
            for (AsyncWriteResult writeResult : asyncWrites) {
                try {
                    writeResult.future.get();
                } catch (InterruptedException | ExecutionException e) {
                    if (firstException == null && e.getCause() instanceof IOException) {
                        firstException = (IOException) e.getCause();
                    }
                } finally {
                    try {
                        writeResult.channel.close();
                    } catch (IOException e) {
                        // Ignore close errors
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
        
        if (firstException != null) {
            throw firstException;
        }
    }
    
    /**
     * Helper class for image write tasks
     */
    private static class ImageWriteTask {
        final File file;
        final byte[] newBytes;
        
        ImageWriteTask(File file, byte[] newBytes) {
            this.file = file;
            this.newBytes = newBytes;
        }
    }
    
    private Set<File> expectedFiles = new HashSet<>();
    private Set<File> writtenFiles = new HashSet<>();
    
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
            try (InputStream is = Files.newInputStream(file.toPath())) {
                byte[] buffer = new byte[8192];
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
