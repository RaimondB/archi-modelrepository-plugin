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
import java.nio.channels.CompletionHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.impl.XMLResourceImpl;
import org.eclipse.gef.commands.CommandStack;
import org.eclipse.osgi.util.NLS;

import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.editor.model.compatibility.CompatibilityHandlerException;
import com.archimatetool.editor.model.compatibility.ModelCompatibility;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.IProfile;



/**
 * Based on the GRAFICO Model Importer
 * GRAFICO (Git fRiendly Archi FIle COllection) is a way to persist an ArchiMate
 * model in a bunch of XML files (one file per ArchiMate element or view).
 * 
 * @author Jean-Baptiste Sarrodie
 * @author Quentin Varquet
 * @author Phillip Beauvoir
 */
public class GraficoModelImporter {
    
    /**
     * Unresolved missing object class
     * 
     * @author Phillip Beauvoir
     */
    static class UnresolvedObject {
        URI missingObjectURI;
        IIdentifier parentObject;

        UnresolvedObject(URI missingObjectURI, IIdentifier parentObject) {
            this.missingObjectURI = missingObjectURI;
            this.parentObject = parentObject;
        }
    }
    
	// ID -> Object lookup table
    private Map<String, IIdentifier> fIDLookup;
    
    /**
     * Unresolved missing objects
     */
    private List<UnresolvedObject> fUnresolvedObjects;
    
    /**
     * Model
     */
    private IArchimateModel fModel;
    
    /**
     * Local repo folder
     */
    private File fLocalRepoFolder;
    
    /**
     * Shared progress reporter for all phases of import.
     * Uses a dedicated background thread for UI updates - worker threads never block.
     */
    private ThrottledProgressReporter fProgressReporter;
    
    // Batch size for CompletableFuture operations (reduces overhead from 30,000 futures to ~300)
    private static final int BATCH_SIZE = 100;
    
    /**
     * @param folder The folder containing the grafico XML files
     */
    public GraficoModelImporter(File folder) {
        if(folder == null) {
            throw new IllegalArgumentException("Folder cannot be null"); //$NON-NLS-1$
        }
        
        fLocalRepoFolder = folder;
    }
	
    /**
     * Import the grafico XML files as a IArchimateModel
     * @throws IOException
     */
    public IArchimateModel importAsModel() throws IOException {
        return importAsModel(null);
    }
    
    /**
     * Import the grafico XML files as a IArchimateModel with progress monitoring
     * @param monitor Progress monitor for UI feedback, can be null
     * @throws IOException
     */
    public IArchimateModel importAsModel(IProgressMonitor monitor) throws IOException {
        // Use SubMonitor for easier progress reporting
        SubMonitor progress = SubMonitor.convert(monitor, Messages.GraficoModelImporter_0, 100);
        
    	// Create folders for model and images
    	File modelFolder = new File(fLocalRepoFolder, IGraficoConstants.MODEL_FOLDER);
        modelFolder.mkdirs();

        File imagesFolder = new File(fLocalRepoFolder, IGraficoConstants.IMAGES_FOLDER);
    	imagesFolder.mkdirs();
    	
    	// If the top folder.xml does not exist then there is nothing to import, so return null
    	if(!(new File(modelFolder, IGraficoConstants.FOLDER_XML)).isFile()) {
    	    return null;
    	}
    	
    	// Check for cancellation
    	if (progress.isCanceled()) {
    	    return null;
    	}
    	
    	// Count total files for the shared progress reporter
    	// This includes model files + image files
    	int modelFileCount = countFilesRecursively(modelFolder);
    	int imageFileCount = countFilesInFolder(imagesFolder);
    	int totalFiles = modelFileCount + imageFileCount;
    	
    	// Create a SINGLE shared progress reporter for all phases
    	// This ensures only ONE background thread handles UI updates across all phases
    	fProgressReporter = new ThrottledProgressReporter(progress.split(80), totalFiles);
    	
    	try {
    	    // Reset the ID -> Object lookup table
    	    fIDLookup = new ConcurrentHashMap<String, IIdentifier>();
    	
            // Load the Model from files (it will contain unresolved proxies)
            // Uses shared fProgressReporter for progress updates
    	    fProgressReporter.subTask(NLS.bind(Messages.GraficoModelImporter_1, 0, modelFileCount));
    	    fModel = loadModel(modelFolder, modelFileCount);
    	
    	    // Check for cancellation
    	    if (fProgressReporter.isCanceled()) {
    	        return null;
    	    }
    	
    	    // Create a new Resource for the model object so we can work with it in the ModelCompatibility class
    	    Resource resource = new XMLResourceImpl();
    	    resource.getContents().add(fModel);
    	
            // Resolve proxies - quick operation, no per-file progress needed
    	    fProgressReporter.subTask(Messages.GraficoModelImporter_2);
            resolveProxies();

    	    // New model compatibility
            ModelCompatibility modelCompatibility = new ModelCompatibility(resource);
    	
            // Fix any backward compatibility issues
    	    // This has to be done here because GraficoModelLoader#loadModel() will save with latest metamodel version number
    	    // And then the ModelCompatibility won't be able to tell the version number
    	    fProgressReporter.subTask(Messages.GraficoModelImporter_3);
            try {
                modelCompatibility.fixCompatibility();
            }
            catch(CompatibilityHandlerException ex) {
                ModelRepositoryPlugin.getInstance().log(IStatus.ERROR, "Error loading model", ex); //$NON-NLS-1$
            }

    	    // We now have to remove the Eobject from its Resource so it can be saved in its proper *.archimate format
            resource.getContents().remove(fModel);
        
            // Add Archive Manager and CommandStack
            IArchiveManager archiveManager = IArchiveManager.FACTORY.createArchiveManager(fModel);
            fModel.setAdapter(IArchiveManager.class, archiveManager);
        
            // We do need a CommandStack for ACLI
            CommandStack cmdStack = new CommandStack();
            fModel.setAdapter(CommandStack.class, cmdStack);
        
    	    // Load images - uses shared fProgressReporter for progress updates
    	    fProgressReporter.subTask(NLS.bind(Messages.GraficoModelImporter_4, 0, imageFileCount));
    	    loadImages(imagesFolder, archiveManager, imageFileCount);

    	    return fModel;
    	} finally {
    	    // Ensure the shared progress reporter is stopped
    	    if (fProgressReporter != null) {
    	        fProgressReporter.finish(null);
    	        fProgressReporter = null;
    	    }
    	}
    }
    
    /**
     * @return A list of unresolved objects. Can be null if no unresolved objects
     */
    public List<UnresolvedObject> getUnresolvedObjects() {
        return fUnresolvedObjects;
    }
    
    /**
     * Read images from images subfolder and load them into the model.
     * Uses TRUE BATCHING with async I/O for parallel file operations.
     * Uses the shared fProgressReporter for progress updates.
     * 
     * @param folder The images folder
     * @param archiveManager The archive manager to add images to
     * @param totalImages Total number of images for progress reporting
     */
    private void loadImages(File folder, IArchiveManager archiveManager, int totalImages) throws IOException {
        Path folderPath = folder.toPath();
        
        if (!Files.isDirectory(folderPath)) {
            return;
        }
        
        // Use NIO2 Files.list() which is more efficient than File.listFiles()
        List<Path> filesToLoad;
        try (Stream<Path> pathStream = Files.list(folderPath)) {
            filesToLoad = pathStream
                .filter(Files::isRegularFile)
                .collect(Collectors.toList());
        }
        
        if (filesToLoad.isEmpty()) {
            return;
        }
        
        // Store results in a concurrent map
        Map<String, byte[]> imageData = new ConcurrentHashMap<>();
        final int totalFiles = filesToLoad.size();
        
        // Use ForkJoinPool for coordinating batches
        ForkJoinPool cpuExecutor = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
        
        // Use the shared progress reporter (created in importAsModel)
        // This ensures only ONE background thread handles UI updates
        
        try {
            // TRUE BATCHING: One CompletableFuture per batch (reduces futures overhead)
            // Within each batch: start async reads, store results as they complete
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            
            for (int i = 0; i < filesToLoad.size(); i += BATCH_SIZE) {
                final int start = i;
                final int end = Math.min(i + BATCH_SIZE, filesToLoad.size());
                final List<Path> batch = filesToLoad.subList(start, end);
                
                // ONE future per batch - starts async reads for all files in batch
                CompletableFuture<Void> batchFuture = CompletableFuture.supplyAsync(() -> {
                    List<CompletableFuture<Void>> batchReads = new ArrayList<>();
                    
                    for (Path path : batch) {
                        CompletableFuture<Void> readFuture = readFileAsync(path.toFile())
                            .thenAccept(bytes -> {
                                if (bytes != null) {
                                    imageData.put(path.getFileName().toString(), bytes);
                                }
                            });
                        
                        batchReads.add(readFuture);
                    }
                    
                    // Return future that completes when all batch reads are done
                    return CompletableFuture.allOf(batchReads.toArray(new CompletableFuture[0]));
                }, cpuExecutor).thenCompose(f -> f) // Flatten nested future
                .thenRun(() -> {
                    // Report progress via shared reporter - NON-BLOCKING
                    if (fProgressReporter != null) {
                        fProgressReporter.incrementBy(batch.size());
                        fProgressReporter.maybeReport(
                            count -> NLS.bind(Messages.GraficoModelImporter_4, count, totalImages));
                    }
                });
                
                futures.add(batchFuture);
            }
            
            // Wait for all batches to complete
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
            );
            
            try {
                // Wait with timeout to allow cancellation checks
                while (!allFutures.isDone()) {
                    if (fProgressReporter != null && fProgressReporter.isCanceled()) {
                        cpuExecutor.shutdownNow();
                        return;
                    }
                    try {
                        allFutures.get(100, TimeUnit.MILLISECONDS);
                    } catch (java.util.concurrent.TimeoutException e) {
                        // Continue checking for cancellation
                    }
                }
                // Final get to propagate any exceptions
                allFutures.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Image load interrupted", e); //$NON-NLS-1$
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException && cause.getCause() instanceof IOException) {
                    throw (IOException) cause.getCause();
                } else if (cause instanceof IOException) {
                    throw (IOException) cause;
                }
                throw new IOException("Failed to load images", e); //$NON-NLS-1$
            }
            
            // Progress is reported via shared reporter - no finish() here
        } finally {
            cpuExecutor.shutdown();
        }
        
        // Add all loaded images to the archive manager
        for (Map.Entry<String, byte[]> entry : imageData.entrySet()) {
            // This must match the prefix used in ArchiveManager.createArchiveImagePathname()
            archiveManager.addByteContentEntry("images/" + entry.getKey(), entry.getValue()); //$NON-NLS-1$
        }
    }    
   
    /**
     * Iterate through all model objects, and resolve proxies on known classes
     */
    private void resolveProxies() {
        fUnresolvedObjects = null;
        
        for(Iterator<EObject> iter = fModel.eAllContents(); iter.hasNext();) {
            EObject eObject = iter.next();

            if(eObject instanceof IArchimateConcept) {
                // Resolve proxies for profiles
            	IArchimateConcept concept = (IArchimateConcept)eObject;
            	EList<IProfile> profiles = concept.getProfiles();
            	// getProfiles() can't return null so no need to check
            	// Assumption: most concepts don't have profiles so checking for empty has a positive impact on performance
            	if(!profiles.isEmpty()) {
	            	ListIterator<IProfile> iterator = profiles.listIterator();
	            	while(iterator.hasNext()) {
	            		IProfile profile = iterator.next();
	            		iterator.set((IProfile)resolve(profile, concept));
	            	}
            	}
            }
            
            if(eObject instanceof IArchimateRelationship) {
                // Resolve proxies for Relations
                IArchimateRelationship relation = (IArchimateRelationship)eObject;
                relation.setSource((IArchimateConcept)resolve(relation.getSource(), relation));
                relation.setTarget((IArchimateConcept)resolve(relation.getTarget(), relation));
            }
            else if(eObject instanceof IDiagramModelArchimateObject) {
                // Resolve proxies for Elements
                IDiagramModelArchimateObject element = (IDiagramModelArchimateObject)eObject;
                element.setArchimateElement((IArchimateElement)resolve(element.getArchimateElement(), element));
            }
            else if(eObject instanceof IDiagramModelArchimateConnection) {
                // Resolve proxies for Connections
                IDiagramModelArchimateConnection archiConnection = (IDiagramModelArchimateConnection)eObject;
                archiConnection.setArchimateRelationship((IArchimateRelationship)resolve(archiConnection.getArchimateRelationship(), archiConnection));
            }
            else if(eObject instanceof IDiagramModelReference) {
                // Resolve proxies for Model References
                IDiagramModelReference element = (IDiagramModelReference)eObject;
                element.setReferencedModel((IDiagramModel)resolve(element.getReferencedModel(), element));
            }
        }
    }

    /**
     * Check if 'object' is a proxy. if yes, replace it with real object from mapping table.
     */
    private EObject resolve(IIdentifier object, IIdentifier parent) {
        if(object != null && object.eIsProxy()) {
            URI objectURI = EcoreUtil.getURI(object);
            String objectID = EcoreUtil.getURI(object).fragment();
            
            // Get proxy object
            IIdentifier newObject = fIDLookup.get(objectID);
            
            // If proxy has not been resolved
            if(newObject == null) {
                // Add to list
                if(fUnresolvedObjects == null) {
                    fUnresolvedObjects = new ArrayList<UnresolvedObject>();
                }
                fUnresolvedObjects.add(new UnresolvedObject(objectURI, parent));
            }
            
            return newObject == null ? object : newObject;
        }
        else {
            return object;
        }
    }
    
	private IArchimateModel loadModel(File folder, int totalModelFiles) throws IOException {
		IArchimateModel model = (IArchimateModel)loadElement(new File(folder, IGraficoConstants.FOLDER_XML));
		
		List<FolderType> folderList = new ArrayList<FolderType>();
		folderList.add(FolderType.STRATEGY);
		folderList.add(FolderType.BUSINESS);
		folderList.add(FolderType.APPLICATION);
		folderList.add(FolderType.TECHNOLOGY);
		folderList.add(FolderType.MOTIVATION);
		folderList.add(FolderType.IMPLEMENTATION_MIGRATION);
		folderList.add(FolderType.OTHER);
		folderList.add(FolderType.RELATIONS);
		folderList.add(FolderType.DIAGRAMS);

		// Loop based on FolderType enumeration
		for(FolderType folderType : folderList) {
		    // Check for cancellation via shared reporter
		    if (fProgressReporter != null && fProgressReporter.isCanceled()) {
		        return model;
		    }
		    // Update phase message via shared reporter
		    if (fProgressReporter != null) {
		        fProgressReporter.maybeReport(
		            count -> String.format(Messages.GraficoModelImporter_5, folderType.toString()));
		    }
		    IFolder tmpFolder = loadFolder(new File(folder, folderType.toString()), totalModelFiles);
		    if(tmpFolder != null) {
		        model.getFolders().add(tmpFolder);
		    }
		}
		
		return model;
	}
    
    /**
     * Count files recursively in a folder (excluding folder.xml files).
     * Used for calculating proportional progress.
     */
    private int countFilesRecursively(File folder) {
        if (!folder.isDirectory()) {
            return 0;
        }
        
        int count = 0;
        File[] contents = folder.listFiles();
        if (contents != null) {
            for (File file : contents) {
                if (file.isDirectory()) {
                    count += countFilesRecursively(file);
                } else if (!file.getName().equals(IGraficoConstants.FOLDER_XML)) {
                    count++;
                }
            }
        }
        return count;
    }
    
    /**
     * Count regular files in a single folder (non-recursive).
     * Used for counting images folder.
     */
    private int countFilesInFolder(File folder) {
        if (!folder.isDirectory()) {
            return 0;
        }
        
        int count = 0;
        File[] contents = folder.listFiles();
        if (contents != null) {
            for (File file : contents) {
                if (file.isFile()) {
                    count++;
                }
            }
        }
        return count;
    }
	
	/**
	 * Load each XML file to recreate original object
	 * Uses ForkJoinPool with proper pipelining for parallel I/O and CPU operations.
	 * Uses the shared fProgressReporter for progress updates.
	 * 
	 * @param folder
	 * @param totalModelFiles Total number of model files for progress reporting
	 * @return Model folder
	 * @throws IOException 
	 */
    private IFolder loadFolder(File folder, int totalModelFiles) throws IOException {
        Path folderPath = folder.toPath();
        Path folderXmlPath = folderPath.resolve(IGraficoConstants.FOLDER_XML);
        
        if(!Files.isDirectory(folderPath) || !Files.isRegularFile(folderXmlPath)) {
            throw new IOException("File is not directory or folder.xml does not exist."); //$NON-NLS-1$
        }

        // Load folder object itself
        IFolder currentFolder = (IFolder)loadElement(folderXmlPath);

        // Get list of files/folders to process using NIO2 DirectoryStream (faster than File.listFiles())
        List<Path> filesToLoad = new ArrayList<>();
        List<Path> foldersToLoad = new ArrayList<>();
        
        try (Stream<Path> pathStream = Files.list(folderPath)) {
            pathStream.forEach(path -> {
                if (!path.getFileName().toString().equals(IGraficoConstants.FOLDER_XML)) {
                    if (Files.isDirectory(path)) {
                        foldersToLoad.add(path);
                    } else if (Files.isRegularFile(path)) {
                        filesToLoad.add(path);
                    }
                }
            });
        }
        
        // Load files in parallel using TRUE BATCHING with async I/O + CPU parsing
        if (!filesToLoad.isEmpty()) {
            ForkJoinPool cpuExecutor = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
            
            // Use a concurrent map to store loaded elements
            Map<Path, EObject> loadedElements = new ConcurrentHashMap<>();
            final int totalFiles = filesToLoad.size();
            
            // Use the shared progress reporter (created in importAsModel)
            // This ensures only ONE background thread handles UI updates
            
            try {
                // TRUE BATCHING: One CompletableFuture per batch (reduces 30,000 futures to ~300)
                // Within each batch: start async reads, then process results as they complete
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                
                for (int i = 0; i < filesToLoad.size(); i += BATCH_SIZE) {
                    final int start = i;
                    final int end = Math.min(i + BATCH_SIZE, filesToLoad.size());
                    final List<Path> batch = filesToLoad.subList(start, end);
                    
                    // ONE future per batch - starts async reads, chains CPU parsing
                    CompletableFuture<Void> batchFuture = CompletableFuture.supplyAsync(() -> {
                        // Start all async reads for this batch
                        List<CompletableFuture<Void>> batchReads = new ArrayList<>();
                        
                        for (Path path : batch) {
                            CompletableFuture<Void> readFuture = readFileAsync(path.toFile())
                                .thenApplyAsync(bytes -> {
                                    // CPU-bound: parse XML from bytes
                                    if (bytes == null) {
                                        return null;
                                    }
                                    try (InputStream inputStream = new java.io.ByteArrayInputStream(bytes)) {
                                        IIdentifier eObject = GraficoResourceLoader.loadEObject(inputStream);
                                        
                                        // Update ID -> Object mapping table (thread-safe map)
                                        fIDLookup.put(eObject.getId(), eObject);
                                        if (eObject instanceof IArchimateModel) {
                                            for (IProfile profile : ((IArchimateModel) eObject).getProfiles()) {
                                                fIDLookup.put(profile.getId(), profile);
                                            }
                                        }
                                        
                                        return eObject;
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                }, cpuExecutor)
                                .thenAccept(element -> {
                                    if (element != null) {
                                        loadedElements.put(path, element);
                                    }
                                });
                            
                            batchReads.add(readFuture);
                        }
                        
                        // Return future that completes when all batch reads are done
                        return CompletableFuture.allOf(batchReads.toArray(new CompletableFuture[0]));
                    }, cpuExecutor).thenCompose(f -> f) // Flatten nested future
                    .thenRun(() -> {
                        // Report progress via shared reporter - NON-BLOCKING
                        if (fProgressReporter != null) {
                            fProgressReporter.incrementBy(batch.size());
                            fProgressReporter.maybeReport(
                                count -> NLS.bind(Messages.GraficoModelImporter_1, count, totalModelFiles));
                        }
                    });
                    
                    futures.add(batchFuture);
                }
                
                // Wait for all batches to complete
                CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0])
                );
                
                try {
                    // Wait with timeout to allow cancellation checks
                    while (!allFutures.isDone()) {
                        if (fProgressReporter != null && fProgressReporter.isCanceled()) {
                            cpuExecutor.shutdownNow();
                            return currentFolder;
                        }
                        try {
                            allFutures.get(100, TimeUnit.MILLISECONDS);
                        } catch (java.util.concurrent.TimeoutException e) {
                            // Continue checking for cancellation
                        }
                    }
                    // Final get to propagate any exceptions
                    allFutures.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Load interrupted", e); //$NON-NLS-1$
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException && cause.getCause() instanceof IOException) {
                        throw (IOException) cause.getCause();
                    } else if (cause instanceof IOException) {
                        throw (IOException) cause;
                    }
                    throw new IOException("Failed to load elements", e); //$NON-NLS-1$
                }
                
                // Add elements in original order to maintain consistency
                for (Path path : filesToLoad) {
                    EObject element = loadedElements.get(path);
                    if (element != null) {
                        currentFolder.getElements().add(element);
                    }
                }
                
                // Progress is reported via shared reporter - no finish() here
            } finally {
                cpuExecutor.shutdown();
            }
        }
        
        // Load subfolders recursively
        for (Path subFolder : foldersToLoad) {
            // Check for cancellation via shared reporter
            if (fProgressReporter != null && fProgressReporter.isCanceled()) {
                return currentFolder;
            }
            
            IFolder loadedFolder = loadFolder(subFolder.toFile(), totalModelFiles);
            if (loadedFolder != null) {
                currentFolder.getFolders().add(loadedFolder);
            }
        }

        return currentFolder;
    }
    
    /**
     * Create an eObject from a Path. Uses AsynchronousFileChannel for async I/O.
     * Note: This method blocks on the async result - used for single file loads like folder.xml
     * For batch loading, use the pipelined approach in loadFolder() and loadImages().
     * 
     * @param path
     * @return
     * @throws IOException 
     */
    private EObject loadElement(Path path) throws IOException {
        // Use async file read for true non-blocking I/O
        byte[] bytes = readFileAsync(path.toFile()).join();
        if (bytes == null) {
            throw new IOException("Failed to read file: " + path); //$NON-NLS-1$
        }
        
        // Parse the XML from bytes
        try (InputStream inputStream = new java.io.ByteArrayInputStream(bytes)) {
            IIdentifier eObject = GraficoResourceLoader.loadEObject(inputStream);
            
            // Update an ID -> Object mapping table (used as a cache to resolve proxies)
            fIDLookup.put(eObject.getId(), eObject);
            if(eObject instanceof IArchimateModel) {
                for(IProfile profile : ((IArchimateModel)eObject).getProfiles()) {
                    fIDLookup.put(profile.getId(), profile);
                }
            }

            return eObject;
        }
    }

    /**
     * Create an eObject from an XML file. Basically load a resource.
     * Delegates to Path-based version for async I/O performance.
     * 
     * @param file
     * @return
     * @throws IOException 
     */
    private EObject loadElement(File file) throws IOException {
        return loadElement(file.toPath());
    }
    
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
}
