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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    
    /**
     * Shared CPU executor for parallel XML parsing across all folders.
     * Sized to CPU cores since XML parsing is CPU-bound.
     * Created once per import, not per folder.
     */
    private ForkJoinPool fCpuExecutor;
    
    // Batch size for CompletableFuture operations (reduces overhead from 30,000 futures to ~300)
    private static final int BATCH_SIZE = 100;
    
    /**
     * Producer/Consumer queue for decoupling file reading from model building.
     * Producers (parallel): read files async → parse XML → put into queue
     * Consumer (single thread): takes from queue → adds to EMF model
     */
    private BlockingQueue<ParsedElement> fElementQueue;
    
    /**
     * Signal that all producers have finished adding to the queue.
     */
    private AtomicBoolean fProducersFinished;
    
    /**
     * Consumer thread for model building.
     */
    private Thread fConsumerThread;
    
    /**
     * Exception caught by consumer thread, if any.
     */
    private volatile Throwable fConsumerException;
    
    /**
     * Holds a parsed element or subfolder with its target parent folder context.
     * Grouped by folder to optimize EMF operations (batch adds to same folder).
     */
    private record ParsedElement(IFolder targetFolder, IArchimateModel targetModel, EObject element, boolean isSubfolder, boolean isTopLevelFolder) {
        // Poison pill to signal end of queue
        static final ParsedElement END_OF_QUEUE = new ParsedElement(null, null, null, false, false);
        
        static ParsedElement forElement(IFolder target, EObject element) {
            return new ParsedElement(target, null, element, false, false);
        }
        
        static ParsedElement forSubfolder(IFolder parent, IFolder subfolder) {
            return new ParsedElement(parent, null, subfolder, true, false);
        }
        
        static ParsedElement forTopLevelFolder(IArchimateModel model, IFolder folder) {
            return new ParsedElement(null, model, folder, false, true);
        }
    }
    
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
    	// Uses optimized NIO2 file walking (much faster than File.listFiles())
    	int modelFileCount = GraficoUtils.countModelFilesRecursively(modelFolder.toPath());
    	int imageFileCount = GraficoUtils.countFilesInFolder(imagesFolder.toPath());
    	int totalFiles = modelFileCount + imageFileCount;
    	
    	// Create a SINGLE shared progress reporter for all phases
    	// This ensures only ONE background thread handles UI updates across all phases
    	fProgressReporter = new ThrottledProgressReporter(progress.split(80), totalFiles);
    	
    	// Create a SINGLE shared CPU executor for all parallel XML parsing
    	// This avoids creating a new ForkJoinPool for each folder in the hierarchy
    	fCpuExecutor = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
    	
    	try {
    	    // Reset the ID -> Object lookup table
    	    fIDLookup = new ConcurrentHashMap<String, IIdentifier>();
    	
            // Load the Model from files (it will contain unresolved proxies)
            // Uses shared fProgressReporter and fCpuExecutor
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
        
    	    // Load images - uses shared fProgressReporter and fCpuExecutor
    	    fProgressReporter.subTask(NLS.bind(Messages.GraficoModelImporter_4, 0, imageFileCount));
    	    loadImages(imagesFolder, archiveManager, imageFileCount);

    	    return fModel;
    	} finally {
    	    // Ensure the shared CPU executor is stopped
    	    if (fCpuExecutor != null) {
    	        fCpuExecutor.shutdown();
    	        fCpuExecutor = null;
    	    }
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
        
        // Store results in a concurrent map - keyed by filename for archive manager
        Map<String, byte[]> imageData = new ConcurrentHashMap<>();
        
        // TRUE BATCHING with direct async I/O - no per-file CompletableFutures
        // Uses CountDownLatch at batch level for async I/O coordination
        List<CompletableFuture<Void>> batchFutures = new ArrayList<>();
        
        for (int i = 0; i < filesToLoad.size(); i += BATCH_SIZE) {
            final int start = i;
            final int end = Math.min(i + BATCH_SIZE, filesToLoad.size());
            final List<Path> batch = filesToLoad.subList(start, end);
            
            // ONE future per batch - uses CountDownLatch internally for async I/O
            CompletableFuture<Void> batchFuture = CompletableFuture.supplyAsync(() -> {
                // Temporary map to hold bytes before copying to imageData with filename keys
                Map<Path, byte[]> batchBytes = new ConcurrentHashMap<>();
                CountDownLatch latch = new CountDownLatch(batch.size());
                
                // Start all async reads for this batch - no per-file futures!
                for (Path path : batch) {
                    readFileAsyncDirect(path, batchBytes, latch);
                }
                
                // Wait for all reads in this batch to complete
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                // Copy to imageData with filename keys
                for (Path path : batch) {
                    byte[] bytes = batchBytes.get(path);
                    if (bytes != null) {
                        imageData.put(path.getFileName().toString(), bytes);
                    }
                }
                
                // Report progress after batch completes
                if (fProgressReporter != null) {
                    fProgressReporter.incrementBy(batch.size());
                    fProgressReporter.maybeReport(
                        count -> NLS.bind(Messages.GraficoModelImporter_4, count, totalImages));
                }
                
                return null;
            }, fCpuExecutor);
            
            batchFutures.add(batchFuture);
        }
        
        // Wait for all batches to complete
        try {
            CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0])).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Image load interrupted", e); //$NON-NLS-1$
        } catch (ExecutionException e) {
            throw new IOException("Failed to load images", e); //$NON-NLS-1$
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

		// Initialize producer/consumer infrastructure
		fElementQueue = new LinkedBlockingQueue<>();
		fProducersFinished = new AtomicBoolean(false);
		fConsumerException = null;
		
		// Start consumer thread - single-threaded model building for EMF thread safety
		// Consumer handles elements, subfolders, and top-level folders
		// ALL EMF modifications happen on this single thread to avoid race conditions
		fConsumerThread = new Thread(() -> {
		    try {
		        while (true) {
		            ParsedElement item = fElementQueue.poll(50, TimeUnit.MILLISECONDS);
		            if (item == null) {
		                // Check if producers are done and queue is empty
		                if (fProducersFinished.get() && fElementQueue.isEmpty()) {
		                    break;
		                }
		                continue;
		            }
		            if (item == ParsedElement.END_OF_QUEUE) {
		                break;
		            }
		            
		            // Add to appropriate parent - all EMF modifications on this thread
		            if (item.isTopLevelFolder()) {
		                // Add top-level folder to model
		                item.targetModel().getFolders().add((IFolder) item.element());
		            } else if (item.isSubfolder()) {
		                // Add subfolder to parent folder
		                item.targetFolder().getFolders().add((IFolder) item.element());
		            } else {
		                // Add element to folder
		                item.targetFolder().getElements().add(item.element());
		            }
		        }
		    } catch (InterruptedException e) {
		        Thread.currentThread().interrupt();
		    } catch (Exception e) {
		        fConsumerException = e;
		    }
		}, "GraficoModelImporter-Consumer"); //$NON-NLS-1$
		fConsumerThread.start();

		try {
    		// Loop based on FolderType enumeration - producers add to queue
    		for(FolderType folderType : folderList) {
    		    // Check for cancellation via shared reporter
    		    if (fProgressReporter != null && fProgressReporter.isCanceled()) {
    		        break;
    		    }
    		    // Update phase message via shared reporter
    		    if (fProgressReporter != null) {
    		        fProgressReporter.maybeReport(
    		            count -> String.format(Messages.GraficoModelImporter_5, folderType.toString()));
    		    }
    		    IFolder tmpFolder = loadFolder(new File(folder, folderType.toString()), totalModelFiles);
    		    if(tmpFolder != null) {
    		        // Queue top-level folder for consumer - all EMF mods on consumer thread
    		        try {
    		            fElementQueue.put(ParsedElement.forTopLevelFolder(model, tmpFolder));
    		        } catch (InterruptedException e) {
    		            Thread.currentThread().interrupt();
    		            throw new IOException("Interrupted while queueing top-level folder", e); //$NON-NLS-1$
    		        }
    		    }
    		}
		} finally {
		    // Signal producers are done and wait for consumer to finish
		    fProducersFinished.set(true);
		    try {
		        // Put poison pill to ensure consumer wakes up
		        fElementQueue.put(ParsedElement.END_OF_QUEUE);
		        fConsumerThread.join(30000); // Wait up to 30 seconds
		    } catch (InterruptedException e) {
		        Thread.currentThread().interrupt();
		    }
		    
		    // Check for consumer exception
		    if (fConsumerException != null) {
		        if (fConsumerException instanceof IOException) {
		            throw (IOException) fConsumerException;
		        }
		        throw new IOException("Consumer thread failed", fConsumerException); //$NON-NLS-1$
		    }
		}
		
		return model;
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
        
        // Load files using PIPELINED async I/O → parse → queue pattern
        // As each file's async read completes, immediately parse and queue for consumer
        // This overlaps I/O, CPU parsing, and model building concurrently
        if (!filesToLoad.isEmpty()) {
            // Track completion and store parsed elements for ordered queueing
            Map<Path, EObject> loadedElements = new ConcurrentHashMap<>();
            CountDownLatch allFilesLatch = new CountDownLatch(filesToLoad.size());
            
            // Start all async reads - each one pipelines: read → parse → store
            for (Path path : filesToLoad) {
                readParseAndStoreDirect(path, loadedElements, allFilesLatch, totalModelFiles);
            }
            
            // Wait for all files to be read, parsed, and stored
            try {
                // Use timed waits to allow cancellation checks
                while (!allFilesLatch.await(100, TimeUnit.MILLISECONDS)) {
                    if (fProgressReporter != null && fProgressReporter.isCanceled()) {
                        return currentFolder;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Load interrupted", e); //$NON-NLS-1$
            }
                
            // Queue elements for consumer thread (maintains order within this folder)
            // Producer/consumer pattern: file reading done, now queue for model building
            // NOTE: Queueing happens on the MAIN THREAD (after allFutures.get()),
            // so order within a folder is preserved. If we ever parallelize folder 
            // traversal, order across folders would not be guaranteed, but that's OK
            // since EMF doesn't require a specific order for elements/subfolders.
            for (Path path : filesToLoad) {
                EObject element = loadedElements.get(path);
                if (element != null) {
                    try {
                        fElementQueue.put(ParsedElement.forElement(currentFolder, element));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while queueing element", e); //$NON-NLS-1$
                    }
                }
            }
        }
        
        // Load subfolders recursively - depth-first to maintain folder hierarchy
        for (Path subFolder : foldersToLoad) {
            // Check for cancellation via shared reporter
            if (fProgressReporter != null && fProgressReporter.isCanceled()) {
                return currentFolder;
            }
            
            IFolder loadedFolder = loadFolder(subFolder.toFile(), totalModelFiles);
            if (loadedFolder != null) {
                // Queue subfolder addition for consumer thread
                try {
                    fElementQueue.put(ParsedElement.forSubfolder(currentFolder, loadedFolder));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while queueing subfolder", e); //$NON-NLS-1$
                }
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
    
    /**
     * Read file bytes asynchronously using direct async I/O with CountDownLatch.
     * This avoids CompletableFuture overhead - uses direct callback to store result.
     * 
     * More efficient than readFileAsync() when processing many files in a batch,
     * as it avoids creating a CompletableFuture per file.
     * 
     * @param path The file path to read
     * @param results Map to store the result bytes (thread-safe)
     * @param latch CountDownLatch to signal completion
     */
    private void readFileAsyncDirect(Path path, Map<Path, byte[]> results, CountDownLatch latch) {
        try {
            long fileSize = Files.size(path);
            ByteBuffer buffer = ByteBuffer.allocate((int) fileSize);
            
            AsynchronousFileChannel channel = AsynchronousFileChannel.open(path, StandardOpenOption.READ);
            
            channel.read(buffer, 0, buffer, new CompletionHandler<Integer, ByteBuffer>() {
                @Override
                public void completed(Integer bytesRead, ByteBuffer buf) {
                    try {
                        channel.close();
                        buf.flip();
                        byte[] bytes = new byte[buf.remaining()];
                        buf.get(bytes);
                        results.put(path, bytes);
                    } catch (IOException e) {
                        // Ignore - file will be missing from results
                    } finally {
                        latch.countDown();
                    }
                }
                
                @Override
                public void failed(Throwable exc, ByteBuffer buf) {
                    try {
                        channel.close();
                    } catch (IOException e) {
                        // Ignore close error
                    }
                    latch.countDown();
                }
            });
        } catch (IOException e) {
            // Failed to open file - count down and continue
            latch.countDown();
        }
    }
    
    /**
     * PIPELINED async I/O: Read file → Parse XML → Store result.
     * 
     * When async I/O completes, immediately submits CPU parsing to executor,
     * then stores the parsed element. This allows I/O, parsing, and model building
     * to overlap concurrently instead of running in separate phases.
     * 
     * @param path The file path to read
     * @param results Map to store the parsed EObject (thread-safe)
     * @param latch CountDownLatch to signal completion
     * @param totalModelFiles Total files for progress reporting
     */
    private void readParseAndStoreDirect(Path path, Map<Path, EObject> results, 
            CountDownLatch latch, int totalModelFiles) {
        try {
            long fileSize = Files.size(path);
            ByteBuffer buffer = ByteBuffer.allocate((int) fileSize);
            
            AsynchronousFileChannel channel = AsynchronousFileChannel.open(path, StandardOpenOption.READ);
            
            channel.read(buffer, 0, buffer, new CompletionHandler<Integer, ByteBuffer>() {
                @Override
                public void completed(Integer bytesRead, ByteBuffer buf) {
                    try {
                        channel.close();
                        buf.flip();
                        byte[] bytes = new byte[buf.remaining()];
                        buf.get(bytes);
                        
                        // Submit CPU-bound parsing to executor (don't block I/O callback thread)
                        fCpuExecutor.execute(() -> {
                            try (InputStream inputStream = new java.io.ByteArrayInputStream(bytes)) {
                                IIdentifier eObject = GraficoResourceLoader.loadEObject(inputStream);
                                
                                // Update ID -> Object mapping table (thread-safe map)
                                fIDLookup.put(eObject.getId(), eObject);
                                if (eObject instanceof IArchimateModel) {
                                    for (IProfile profile : ((IArchimateModel) eObject).getProfiles()) {
                                        fIDLookup.put(profile.getId(), profile);
                                    }
                                }
                                
                                results.put(path, eObject);
                            } catch (IOException e) {
                                // Log but continue - element will be missing
                            } finally {
                                // Report progress and signal completion
                                if (fProgressReporter != null) {
                                    fProgressReporter.incrementAndMaybeReport(
                                        count -> NLS.bind(Messages.GraficoModelImporter_1, count, totalModelFiles));
                                }
                                latch.countDown();
                            }
                        });
                    } catch (IOException e) {
                        latch.countDown();
                    }
                }
                
                @Override
                public void failed(Throwable exc, ByteBuffer buf) {
                    try {
                        channel.close();
                    } catch (IOException e) {
                        // Ignore close error
                    }
                    latch.countDown();
                }
            });
        } catch (IOException e) {
            // Failed to open file - count down and continue
            latch.countDown();
        }
    }
}
