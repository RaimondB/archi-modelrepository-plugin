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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
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
     * ThreadLocal batch buffer for producers to reduce queue contention.
     * Each virtual thread accumulates up to PRODUCER_BATCH_SIZE items before
     * flushing to the queue. This reduces queue.put() operations from 26,679
     * to ~1,300 (20x reduction), dramatically reducing lock contention.
     * 
     * CRITICAL: Must flush remaining items when thread completes or on last file.
     */
    private static final int PRODUCER_BATCH_SIZE = 20;
    private static final ThreadLocal<List<ElementWithFolder>> PRODUCER_BATCH_BUFFER = 
        ThreadLocal.withInitial(() -> new ArrayList<>(PRODUCER_BATCH_SIZE));
    
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
     * MUST be volatile to ensure visibility across virtual threads on different CPU cores.
     */
    private volatile ThrottledProgressReporter fProgressReporter;
    
    /**
     * Shared CPU executor for parallel XML parsing across all folders.
     * Sized to CPU cores since XML parsing is CPU-bound.
     * Created once per import, not per folder.
     */
    private ForkJoinPool fCpuExecutor;

    /**
     * Virtual thread executor for I/O operations.
     */
    private ExecutorService fIoExecutor;
    
    // NOTE: We use Virtual Threads for I/O as requested
    
    // Batch size for CompletableFuture operations (reduces overhead from 30,000 futures to ~300)
    private static final int BATCH_SIZE = 100;
    
    // Maximum concurrent I/O operations (matches exporter's maxBatches approach)
    private static final int MAX_CONCURRENT_IO = 1000;
    
    // Bounded queue capacity - should be >= MAX_CONCURRENT_IO to avoid producer blocking
    // 2x buffer allows for burst handling
    private static final int QUEUE_CAPACITY = 2000;
    
    // Producer timing accumulators (for performance diagnostics)
    private java.util.concurrent.atomic.LongAdder fProducerReadTime;
    private java.util.concurrent.atomic.LongAdder fProducerParseTime;
    private java.util.concurrent.atomic.LongAdder fProducerQueuePutTime;
    
    /**
     * Producer/Consumer queue for decoupling file reading from model building.
     * Uses BOUNDED capacity to create backpressure when consumer is slow.
     * Producers (parallel): read files async → parse XML → put into queue (blocks if full)
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
    
    // ================================================================================
    // DirCache Optimization Fields
    // ================================================================================
    // When importing from a git repository, we can use the DirCache (git index) to:
    // 1. Discover all files without hitting the disk (fast directory traversal)
    // 2. Pre-build the folder hierarchy before reading any files
    // 3. Start ALL async file reads at once (not folder-by-folder)
    // ================================================================================
    
    /**
     * Git repository, if importing from a git-managed folder.
     * Null if the folder is not a git repository.
     */
    private Repository fGitRepository;
    
    /**
     * DirCache (git index) for discovering file structure without disk I/O.
     * Null if the folder is not a git repository.
     */
    private DirCache fDirCache;
    
    /**
     * Pre-built folder hierarchy from DirCache.
     * Maps relative folder path (e.g., "model/strategy") to IFolder object.
     * Used to look up parent folders when adding elements.
     */
    private Map<String, IFolder> fFolderPathLookup;
    
    /**
     * Enable performance logging. Set to true to see detailed timing breakdown.
     * Logs go to Eclipse Error Log view (Window → Show View → Error Log).
     * Enable with JVM arg: -Dgrafico.perf.logging=true
     * 
     * This flag is evaluated once at class load time, so there's zero overhead
     * when disabled (the default). The check is a simple boolean comparison.
     */
    private static final boolean PERF_LOGGING = Boolean.getBoolean("grafico.perf.logging"); //$NON-NLS-1$
    
    /**
     * Log performance metrics if PERF_LOGGING is enabled.
     */
    private void logPerf(String phase, long startNanos, int itemCount) {
        if (!PERF_LOGGING) return; // Fast path - zero overhead when disabled
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        double itemsPerSec = itemCount > 0 && elapsedMs > 0 ? (itemCount * 1000.0 / elapsedMs) : 0;
        String message = String.format("[GRAFICO IMPORT PERF] %s: %dms (%d items, %.0f items/sec)", //$NON-NLS-1$
            phase, elapsedMs, itemCount, itemsPerSec);
        ModelRepositoryPlugin.getInstance().log(IStatus.INFO, message, null);
    }
    
    private void logPerf(String phase, long startNanos) {
        if (!PERF_LOGGING) return; // Fast path - zero overhead when disabled
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        String message = String.format("[GRAFICO IMPORT PERF] %s: %dms", phase, elapsedMs); //$NON-NLS-1$
        ModelRepositoryPlugin.getInstance().log(IStatus.INFO, message, null);
    }
    
    /**
     * Log START/COMPLETE messages that are ALWAYS visible (even when PERF_LOGGING disabled).
     * Use this for high-level phase timing that users should always see.
     */
    private void logTiming(String phase, long startNanos, int itemCount) {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        double itemsPerSec = itemCount > 0 && elapsedMs > 0 ? (itemCount * 1000.0 / elapsedMs) : 0;
        String message = String.format("[GRAFICO IMPORT] %s: %dms (%d items, %.0f items/sec)", //$NON-NLS-1$
            phase, elapsedMs, itemCount, itemsPerSec);
        ModelRepositoryPlugin.getInstance().log(IStatus.INFO, message, null);
    }
    
    private void logPerfMessage(String message) {
        if (!PERF_LOGGING) return; // Fast path - zero overhead when disabled
        ModelRepositoryPlugin.getInstance().log(IStatus.INFO, "[GRAFICO IMPORT PERF] " + message, null); //$NON-NLS-1$
    }

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
        long importStart = System.nanoTime();
        logPerfMessage("=== IMPORT START ==="); //$NON-NLS-1$
        
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
    	long phaseStart = System.nanoTime();
    	int modelFileCount = GraficoUtils.countModelFilesRecursively(modelFolder.toPath());
    	int imageFileCount = GraficoUtils.countFilesInFolder(imagesFolder.toPath());
    	int totalFiles = modelFileCount + imageFileCount;
    	logPerf("File counting", phaseStart, totalFiles); //$NON-NLS-1$
    	
    	// Create a SINGLE shared progress reporter for all phases
    	// This ensures only ONE background thread handles UI updates across all phases
    	// Use 100% of allocated progress - no reserved portion left idle at the end
    	fProgressReporter = new ThrottledProgressReporter(progress.split(100), totalFiles);
    	
    	// OPTIMIZED PIPELINE ARCHITECTURE (December 2025):
    	// Use ForkJoinPool for CPU work and Virtual Threads for I/O
    	fCpuExecutor = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
        fIoExecutor = Executors.newVirtualThreadPerTaskExecutor();
    	logPerfMessage("Executors created: ForkJoinPool(" + Runtime.getRuntime().availableProcessors() + ") + Virtual Threads"); //$NON-NLS-1$ //$NON-NLS-2$
    	
    	try {
    	    // Reset the ID -> Object lookup table
            // Pre-size to avoid resizing overhead (default load factor 0.75)
    	    fIDLookup = new ConcurrentHashMap<String, IIdentifier>((int)(totalFiles / 0.75) + 1);
    	    
    	    // Try DirCache-based loading for git repositories (faster on cold cache)
    	    // Falls back to traditional folder-by-folder loading if not a git repo
    	    phaseStart = System.nanoTime();
    	    boolean useDirCache = initDirCacheForImport();
    	    List<DirCacheFileEntry> allEntries = null;
    	    
    	    if (useDirCache) {
    	        allEntries = collectFilesFromDirCache();
    	        // Recalculate counts from DirCache for accuracy
    	        modelFileCount = (int) allEntries.stream().filter(DirCacheFileEntry::isModelFile).count();
    	        imageFileCount = (int) allEntries.stream().filter(DirCacheFileEntry::isImageFile).count();
    	        totalFiles = modelFileCount + imageFileCount;
    	        logPerf("DirCache init + collect", phaseStart, allEntries.size()); //$NON-NLS-1$
    	    } else {
    	        logPerf("DirCache init (fallback to traditional)", phaseStart); //$NON-NLS-1$
    	    }
    	
            // Load the Model from files (it will contain unresolved proxies)
            // Uses shared fProgressReporter and fCpuExecutor
    	    fProgressReporter.subTask(NLS.bind(Messages.GraficoModelImporter_1, 0, modelFileCount));
    	    
    	    phaseStart = System.nanoTime();
    	    if (useDirCache && allEntries != null) {
    	        // DirCache-optimized loading: bulk parallel reads of ALL files
    	        logPerfMessage("Using DirCache-optimized loading for " + modelFileCount + " model files"); //$NON-NLS-1$ //$NON-NLS-2$
    	        fModel = loadModelWithDirCache(modelFolder, allEntries, modelFileCount);
    	    } else {
    	        // Traditional folder-by-folder loading
    	        logPerfMessage("Using traditional folder-by-folder loading"); //$NON-NLS-1$
    	        fModel = loadModel(modelFolder, modelFileCount);
    	    }
    	    logPerf("Model loading (total)", phaseStart, modelFileCount); //$NON-NLS-1$
    	
    	    // Check for cancellation
    	    if (fProgressReporter.isCanceled()) {
    	        return null;
    	    }
    	
    	    // Create a new Resource for the model object so we can work with it in the ModelCompatibility class
    	    Resource resource = new XMLResourceImpl();
    	    resource.getContents().add(fModel);
    	
            // Resolve proxies - quick operation, no per-file progress needed
    	    fProgressReporter.subTask(Messages.GraficoModelImporter_2);
    	    phaseStart = System.nanoTime();
            resolveProxies();
            logPerf("Resolve proxies", phaseStart); //$NON-NLS-1$

    	    // New model compatibility
            ModelCompatibility modelCompatibility = new ModelCompatibility(resource);
    	
            // Fix any backward compatibility issues
    	    // This has to be done here because GraficoModelLoader#loadModel() will save with latest metamodel version number
    	    // And then the ModelCompatibility won't be able to tell the version number
    	    fProgressReporter.subTask(Messages.GraficoModelImporter_3);
    	    phaseStart = System.nanoTime();
            try {
                modelCompatibility.fixCompatibility();
            }
            catch(CompatibilityHandlerException ex) {
                ModelRepositoryPlugin.getInstance().log(IStatus.ERROR, "Error loading model", ex); //$NON-NLS-1$
            }
            logPerf("Fix compatibility", phaseStart); //$NON-NLS-1$

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
    	    phaseStart = System.nanoTime();
    	    loadImages(imagesFolder, archiveManager, imageFileCount);
    	    logPerf("Load images", phaseStart, imageFileCount); //$NON-NLS-1$
    	    
    	    logPerf("=== IMPORT COMPLETE ===", importStart, totalFiles); //$NON-NLS-1$

    	    return fModel;
    	} finally {
    	    // Ensure DirCache resources are cleaned up
    	    cleanupDirCacheForImport();
    	    
    	    // Ensure the CPU executor is stopped
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
                    fProgressReporter.incrementProducedBy(batch.size());
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
                    if (fProgressReporter != null) {
                        fProgressReporter.increment();
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
                        if (fProgressReporter != null) {
                            fProgressReporter.incrementProduced();
                        }
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
                        if (fProgressReporter != null) {
                            fProgressReporter.incrementProduced();
                        }
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
                    if (fProgressReporter != null) {
                        fProgressReporter.incrementProduced();
                    }
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
                                // Signal completion for this file
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
    
    // ================================================================================
    // DirCache Optimization Methods
    // ================================================================================
    // 
    // When importing from a git repository, we can use the DirCache (git index) to
    // discover all files without hitting the disk for directory listing. This is
    // especially important after the export optimization, which no longer warms
    // the disk cache by reading files.
    //
    // STRATEGY:
    // 1. Read DirCache once to get list of all files under model/ and images/
    // 2. Categorize files: folder.xml files (define structure) vs element files (content)
    // 3. Process folder.xml files first (in hierarchy order) to create IFolder objects
    // 4. Start ALL element file reads in parallel
    // 5. Use producer/consumer to add parsed elements to pre-created folders
    //
    // PERFORMANCE BENEFIT:
    // - Avoids disk I/O for directory listing (~30 folder scans → 0)
    // - Enables bulk parallel reads of ALL files at once
    // - Overlaps I/O completely across the entire file set
    // ================================================================================
    
    /**
     * Initialize DirCache for optimized file discovery.
     * 
     * @return true if DirCache was successfully initialized
     */
    private boolean initDirCacheForImport() {
        if (!GraficoUtils.isGitRepository(fLocalRepoFolder)) {
            return false;
        }
        
        try {
            fGitRepository = Git.open(fLocalRepoFolder).getRepository();
            fDirCache = DirCache.read(fGitRepository);
            fFolderPathLookup = new ConcurrentHashMap<>();
            return true;
        } catch (IOException e) {
            cleanupDirCacheForImport();
            return false;
        }
    }
    
    /**
     * Clean up DirCache resources.
     */
    private void cleanupDirCacheForImport() {
        fDirCache = null;
        if (fGitRepository != null) {
            fGitRepository.close();
            fGitRepository = null;
        }
        fFolderPathLookup = null;
    }
    
    /**
     * Represents a file entry from DirCache with its categorization.
     */
    private record DirCacheFileEntry(
        String relativePath,    // e.g., "model/strategy/folder.xml" or "model/strategy/Element_abc123.xml"
        Path absolutePath,      // Full filesystem path
        String folderPath,      // Parent folder path e.g., "model/strategy"
        boolean isFolderXml,    // true if this is a folder.xml file
        boolean isModelFile,    // true if under model/ directory
        boolean isImageFile,    // true if under images/ directory
        ObjectId objectId       // Git object ID for reading from object database
    ) {
        /**
         * Get the depth of this file in the folder hierarchy.
         * Used to sort folder.xml files for hierarchical processing.
         */
        int getDepth() {
            return (int) relativePath.chars().filter(c -> c == '/').count();
        }
    }
    
    /**
     * Collect all model and image files from DirCache.
     * This avoids disk I/O for directory traversal.
     * 
     * @return List of file entries categorized by type
     */
    private List<DirCacheFileEntry> collectFilesFromDirCache() {
        List<DirCacheFileEntry> entries = new ArrayList<>();
        
        if (fDirCache == null) {
            return entries;
        }
        
        Path repoRoot = fLocalRepoFolder.toPath();
        String modelPrefix = IGraficoConstants.MODEL_FOLDER + "/"; //$NON-NLS-1$
        String imagesPrefix = IGraficoConstants.IMAGES_FOLDER + "/"; //$NON-NLS-1$
        
        for (int i = 0; i < fDirCache.getEntryCount(); i++) {
            DirCacheEntry entry = fDirCache.getEntry(i);
            String path = entry.getPathString();
            
            boolean isModelFile = path.startsWith(modelPrefix);
            boolean isImageFile = path.startsWith(imagesPrefix);
            
            if (!isModelFile && !isImageFile) {
                continue; // Skip files outside model/ and images/
            }
            
            Path absolutePath = repoRoot.resolve(path.replace('/', File.separatorChar));
            
            // Compute parent folder path
            int lastSlash = path.lastIndexOf('/');
            String folderPath = lastSlash > 0 ? path.substring(0, lastSlash) : ""; //$NON-NLS-1$
            
            boolean isFolderXml = path.endsWith("/" + IGraficoConstants.FOLDER_XML) || //$NON-NLS-1$
                                  path.equals(IGraficoConstants.MODEL_FOLDER + "/" + IGraficoConstants.FOLDER_XML); //$NON-NLS-1$
            
            ObjectId objectId = entry.getObjectId();
            
            entries.add(new DirCacheFileEntry(path, absolutePath, folderPath, isFolderXml, isModelFile, isImageFile, objectId));
        }
        
        return entries;
    }
    
    /**
     * Load model using DirCache-optimized bulk parallel reading.
     * 
     * Strategy:
     * 1. Get all files from DirCache (no disk I/O for discovery)
     * 2. Sort and process folder.xml files first (creates folder hierarchy)
     * 3. Start ALL element file reads in parallel
     * 4. Use producer/consumer for single-threaded model building
     * 
     * @param modelFolder The model folder
     * @param allEntries Pre-collected file entries from DirCache
     * @param totalModelFiles Total file count for progress
     * @return The loaded model
     */
    private IArchimateModel loadModelWithDirCache(File modelFolder, List<DirCacheFileEntry> allEntries, int totalModelFiles) throws IOException {
        long methodStart = System.nanoTime();
        long phaseStart = System.nanoTime();
        
        // Separate folder.xml files from element files
        List<DirCacheFileEntry> folderXmlFiles = allEntries.stream()
            .filter(e -> e.isModelFile() && e.isFolderXml())
            .sorted((a, b) -> Integer.compare(a.getDepth(), b.getDepth())) // Process parents before children
            .collect(Collectors.toList());
        
        List<DirCacheFileEntry> elementFiles = allEntries.stream()
            .filter(e -> e.isModelFile() && !e.isFolderXml())
            .collect(Collectors.toList());
        
        logPerf("  Categorize files", phaseStart, allEntries.size()); //$NON-NLS-1$
        logPerfMessage("  folder.xml files: " + folderXmlFiles.size() + ", element files: " + elementFiles.size()); //$NON-NLS-1$ //$NON-NLS-2$
        
        // Calculate optimal batch count using exporter's proven approach
        int cpuCores = Runtime.getRuntime().availableProcessors();
        int targetBatches = calculateOptimalBatchCount(folderXmlFiles.size() + elementFiles.size(), cpuCores);
        logPerfMessage("  Target batches: " + targetBatches + " (cpuCores=" + cpuCores + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        
        // Phase 1: Load and parse all folder.xml files to build folder hierarchy
        // These must be processed in order (parents before children)
        // We read them in parallel using batched ForkJoinPool approach
        Map<String, EObject> folderXmlContents = new ConcurrentHashMap<>();
        
        phaseStart = System.nanoTime();
        if (!folderXmlFiles.isEmpty()) {
            // Calculate batch size for folder.xml files
            int folderBatchCount = Math.min(targetBatches, folderXmlFiles.size());
            int folderBatchSize = Math.max(1, (folderXmlFiles.size() + folderBatchCount - 1) / folderBatchCount);
            logPerfMessage("  Phase1 batch config: " + folderBatchCount + " batches, ~" + folderBatchSize + " files/batch"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            
            List<CompletableFuture<Void>> batchFutures = new ArrayList<>();
            
            // ONE future per batch - each batch processes files sequentially (one file handle at a time)
            for (int i = 0; i < folderXmlFiles.size(); i += folderBatchSize) {
                final List<DirCacheFileEntry> batch = folderXmlFiles.subList(i, Math.min(i + folderBatchSize, folderXmlFiles.size()));
                
                CompletableFuture<Void> batchFuture = CompletableFuture.runAsync(() -> {
                    for (DirCacheFileEntry entry : batch) {
                        readAndParseBatched(entry.absolutePath(), folderXmlContents);
                    }
                }, fCpuExecutor);
                
                batchFutures.add(batchFuture);
            }
            
            // Wait for all batches to complete
            try {
                CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0])).join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof IOException) {
                    throw (IOException) e.getCause();
                }
                throw new IOException("Folder loading failed", e.getCause()); //$NON-NLS-1$
            }
        }
        logPerf("  Phase1: Read+parse folder.xml (batched ForkJoinPool)", phaseStart, folderXmlFiles.size()); //$NON-NLS-1$
        
        // Build folder hierarchy (must be done sequentially in hierarchy order)
        phaseStart = System.nanoTime();
        IArchimateModel model = null;
        for (DirCacheFileEntry entry : folderXmlFiles) {
            EObject folderObj = folderXmlContents.get(entry.absolutePath().toString());
            if (folderObj == null) {
                continue;
            }
            
            if (folderObj instanceof IArchimateModel) {
                // Root folder.xml is the model itself
                model = (IArchimateModel) folderObj;
                // Note: Don't put in fFolderPathLookup - ConcurrentHashMap doesn't allow null values
                // and model folder has no IFolder. Children of model are top-level folders.
            } else if (folderObj instanceof IFolder) {
                IFolder folder = (IFolder) folderObj;
                
                // Find parent folder
                String parentPath = entry.folderPath();
                int lastSlash = parentPath.lastIndexOf('/');
                String grandParentPath = lastSlash > 0 ? parentPath.substring(0, lastSlash) : IGraficoConstants.MODEL_FOLDER;
                
                // Add to parent
                if (grandParentPath.equals(IGraficoConstants.MODEL_FOLDER)) {
                    // Top-level folder - add to model
                    if (model != null) {
                        model.getFolders().add(folder);
                    }
                } else {
                    // Nested folder - add to parent folder
                    IFolder parentFolder = fFolderPathLookup.get(grandParentPath);
                    if (parentFolder != null) {
                        parentFolder.getFolders().add(folder);
                    }
                }
                
                // Register this folder for its children
                fFolderPathLookup.put(entry.folderPath(), folder);
            }
            
            // Update progress message (counts handled when files were read)
            if (fProgressReporter != null) {
                fProgressReporter.maybeReport(
                    count -> NLS.bind(Messages.GraficoModelImporter_1, count, totalModelFiles));
            }
        }
        logPerf("  Build folder hierarchy (sequential)", phaseStart, folderXmlFiles.size()); //$NON-NLS-1$
        
        if (model == null) {
            throw new IOException("Model folder.xml not found"); //$NON-NLS-1$
        }
        
        // Phase 2 & 3 OVERLAPPED: 3-Stage Pipeline with Backpressure
        // 
        // Stage 1: I/O (Virtual Threads)
        //   - Virtual threads read files from disk - thousands can block concurrently
        //   - Natural disk I/O parallelism without consuming OS threads
        //   - Submits raw bytes to Stage 2
        //
        // Stage 2: CPU (ForkJoinPool)
        //   - Parse XML into EObjects (CPU-bound)
        //   - Batch-local ID collection to reduce ConcurrentHashMap contention
        //   - Submits parsed elements to bounded queue
        //
        // Stage 3: Model (Single Thread - this thread)
        //   - Add elements to EMF model (must be single-threaded)
        //   - BOUNDED queue creates backpressure - if consumer slow, producers block
        //
        phaseStart = System.nanoTime();
        if (!elementFiles.isEmpty()) {
            // UNBOUNDED LinkedBlockingQueue - no capacity limit
            // With ThreadLocal batching, producers accumulate 20 items before adding to queue.
            // This reduces queue operations from 26,679 to ~1,334.
            // Making queue unbounded eliminates the last contention point: producers waiting for space.
            // Memory impact: 26,679 items × ~2KB = ~50MB peak (acceptable for faster throughput)
            BlockingQueue<ElementWithFolder> elementQueue = new LinkedBlockingQueue<>();
            AtomicInteger remainingElements = new AtomicInteger(elementFiles.size());
            AtomicBoolean producerError = new AtomicBoolean(false);
            
            // Wire up queue size diagnostics for progress reporter (only if perf logging enabled)
            if (PERF_LOGGING && fProgressReporter != null) {
                fProgressReporter.setQueueSizeSupplier(() -> elementQueue.size());
            }
            
            // Initialize producer timing accumulators (only if perf logging enabled)
            if (PERF_LOGGING) {
                fProducerReadTime = new java.util.concurrent.atomic.LongAdder();
                fProducerParseTime = new java.util.concurrent.atomic.LongAdder();
                fProducerQueuePutTime = new java.util.concurrent.atomic.LongAdder();
            }
            
            // Track concurrent batch execution to diagnose parallelism limits (only if perf logging enabled)
            final AtomicInteger activeBatches = PERF_LOGGING ? new AtomicInteger(0) : null;
            final AtomicInteger peakActiveBatches = PERF_LOGGING ? new AtomicInteger(0) : null;
            
            logPerfMessage("  Phase2 config: Virtual Threads (one per file), queue=UNBOUNDED"); //$NON-NLS-1$
            
            // CRITICAL: Start consumer thread FIRST, then fire producers
            // Consumer must run concurrently with producers to drain queue as items arrive
            final int totalElements = elementFiles.size();
            AtomicInteger elementsProcessed = new AtomicInteger(0);
            AtomicReference<Throwable> consumerException = new AtomicReference<>();
            
            // Consumer tracking for diagnostics (only if perf logging enabled)
            final AtomicInteger drainOperations = PERF_LOGGING ? new AtomicInteger(0) : null;
            final AtomicInteger takeOperations = PERF_LOGGING ? new AtomicInteger(0) : null;
            final AtomicLong totalAddTime = PERF_LOGGING ? new AtomicLong(0) : null;
            final AtomicLong totalTakeTime = PERF_LOGGING ? new AtomicLong(0) : null;
            final AtomicLong totalDrainTime = PERF_LOGGING ? new AtomicLong(0) : null;
            final AtomicLong totalLookupTime = PERF_LOGGING ? new AtomicLong(0) : null;
            final AtomicInteger maxBatchSize = PERF_LOGGING ? new AtomicInteger(0) : null;
            
            long consumerStart = System.nanoTime();
            
            // Start consumer thread BEFORE firing producers
            Thread consumerThread = new Thread(() -> {
                List<ElementWithFolder> drainBuffer = new ArrayList<>(500);
                
                try {
                    while (elementsProcessed.get() < totalElements) {
                        long takeStart = PERF_LOGGING ? System.nanoTime() : 0;
                        ElementWithFolder firstItem = elementQueue.poll(2, TimeUnit.SECONDS);
                        if (PERF_LOGGING && totalTakeTime != null) {
                            totalTakeTime.addAndGet(System.nanoTime() - takeStart);
                        }
                        
                        if (firstItem == null) {
                            // Timeout - check if producers are done
                            if (remainingElements.get() == 0 && elementQueue.isEmpty()) {
                                break;  // All done
                            }
                            if (PERF_LOGGING) {
                                logPerfMessage("  CONSUMER POLL TIMEOUT: processed=" + elementsProcessed.get() + //$NON-NLS-1$
                                    ", queueSize=" + elementQueue.size() + ", remaining=" + remainingElements.get()); //$NON-NLS-1$ //$NON-NLS-2$
                            }
                            continue;
                        }
                        
                        if (PERF_LOGGING && takeOperations != null) {
                            takeOperations.incrementAndGet();
                        }
                        
                        // Drain all available items
                        drainBuffer.add(firstItem);
                        if (PERF_LOGGING) {
                            long drainStart = System.nanoTime();
                            int drained = elementQueue.drainTo(drainBuffer);
                            if (totalDrainTime != null) {
                                totalDrainTime.addAndGet(System.nanoTime() - drainStart);
                            }
                            if (drained > 0 && drainOperations != null) {
                                drainOperations.incrementAndGet();
                            }
                            int currentBatchSize = drainBuffer.size();
                            if (maxBatchSize != null) {
                                maxBatchSize.updateAndGet(max -> Math.max(max, currentBatchSize));
                            }
                        } else {
                            elementQueue.drainTo(drainBuffer);
                        }
                        
                        // Process batch
                        for (ElementWithFolder item : drainBuffer) {
                            if (item.element() == null) {
                                elementsProcessed.incrementAndGet();
                                if (fProgressReporter != null) {
                                    fProgressReporter.increment();
                                }
                                continue;
                            }
                            
                            IFolder parentFolder;
                            if (PERF_LOGGING) {
                                long lookupStart = System.nanoTime();
                                parentFolder = fFolderPathLookup.get(item.folderPath());
                                if (totalLookupTime != null) {
                                    totalLookupTime.addAndGet(System.nanoTime() - lookupStart);
                                }
                            } else {
                                parentFolder = fFolderPathLookup.get(item.folderPath());
                            }
                            
                            if (PERF_LOGGING) {
                                long addStart = System.nanoTime();
                                if (parentFolder != null) {
                                    parentFolder.getElements().add(item.element());
                                }
                                if (totalAddTime != null) {
                                    totalAddTime.addAndGet(System.nanoTime() - addStart);
                                }
                            } else {
                                if (parentFolder != null) {
                                    parentFolder.getElements().add(item.element());
                                }
                            }
                            
                            elementsProcessed.incrementAndGet();
                            if (fProgressReporter != null) {
                                fProgressReporter.increment();
                            }
                            
                            // Log progress every 5000 elements (only if perf logging enabled)
                            if (PERF_LOGGING) {
                                int processed = elementsProcessed.get();
                                if (processed % 5000 == 0) {
                                    logPerfMessage("  CONSUMER PROGRESS: processed=" + processed + //$NON-NLS-1$
                                        ", queueSize=" + elementQueue.size() + ", remaining=" + remainingElements.get()); //$NON-NLS-1$ //$NON-NLS-2$
                                }
                            }
                            
                            // Report progress
                            if (fProgressReporter != null) {
                                fProgressReporter.maybeReport(
                                    count -> NLS.bind(Messages.GraficoModelImporter_1, count, totalModelFiles));
                            }
                        }
                        drainBuffer.clear();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    consumerException.set(new IOException("Consumer interrupted", e)); //$NON-NLS-1$
                } catch (Exception e) {
                    consumerException.set(e);
                }
            }, "GraficoModelImporter-DirCache-Consumer"); //$NON-NLS-1$
            
            consumerThread.start();
            
            // NOW fire producers - consumer is already running and ready to drain
            long producerStart = System.nanoTime();
            
            // Fire ALL file reads using virtual threads
            // Each virtual thread: read file (I/O) → parse → queue
            for (DirCacheFileEntry entry : elementFiles) {
                fIoExecutor.execute(() -> {
                    if (PERF_LOGGING && activeBatches != null && peakActiveBatches != null) {
                        activeBatches.incrementAndGet();
                        peakActiveBatches.updateAndGet(peak -> Math.max(peak, activeBatches.get()));
                    }
                    try {
                        readParseAndQueueStreaming(entry, elementQueue, remainingElements, producerError, totalModelFiles);
                    } finally {
                        if (PERF_LOGGING && activeBatches != null) {
                            activeBatches.decrementAndGet();
                        }
                    }
                });
            }
            
            // Calculate how long it took to fire all producer tasks
            long producerFireTime = System.nanoTime() - producerStart;
            
            // Wait for consumer thread to finish
            try {
                consumerThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Consumer thread interrupted", e); //$NON-NLS-1$
            }
            
            // Check for consumer exception
            if (consumerException.get() != null) {
                Throwable ex = consumerException.get();
                if (ex instanceof IOException) {
                    throw (IOException) ex;
                }
                throw new IOException("Consumer thread failed", ex); //$NON-NLS-1$
            }
            
            // Log diagnostics (only if PERF_LOGGING enabled)
            if (PERF_LOGGING) {
                // Log producer startup time (how long to fire all 26,680 tasks)
                logPerfMessage("  Phase2 Stage1: Fire async reads (Virtual Threads): " + (producerFireTime / 1_000_000) + //$NON-NLS-1$
                    "ms (" + elementFiles.size() + " items, " + (elementFiles.size() * 1000000000L / producerFireTime) + " items/sec)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                
                long totalConsumerTime = System.nanoTime() - consumerStart;
                logPerf("  Phase2+3: Consumer (take+drain)", consumerStart, elementsProcessed.get()); //$NON-NLS-1$
                
                if (takeOperations != null && drainOperations != null && maxBatchSize != null) {
                    int avgBatchSize = takeOperations.get() > 0 ? elementsProcessed.get() / takeOperations.get() : 0;
                    logPerfMessage("  Consumer: " + takeOperations.get() + " take ops, " + drainOperations.get() + //$NON-NLS-1$ //$NON-NLS-2$
                        " drain ops, avg batch=" + avgBatchSize + ", max batch=" + maxBatchSize.get()); //$NON-NLS-1$ //$NON-NLS-2$
                }
                
                // Log breakdown of consumer time
                if (totalAddTime != null && totalDrainTime != null && totalTakeTime != null && totalLookupTime != null) {
                    long addTimeMs = totalAddTime.get() / 1_000_000;
                    long drainMs = totalDrainTime.get() / 1_000_000;
                    long takeMs = totalTakeTime.get() / 1_000_000;
                    long lookupMs = totalLookupTime.get() / 1_000_000;
                    long otherTimeMs = (totalConsumerTime / 1_000_000) - addTimeMs - drainMs - takeMs - lookupMs;
                    int theoreticalMaxRate = addTimeMs > 0 ? (int)(elementsProcessed.get() * 1000L / addTimeMs) : 0;
                    logPerfMessage("  Consumer breakdown: add=" + addTimeMs + "ms, lookup=" + lookupMs + //$NON-NLS-1$ //$NON-NLS-2$
                        "ms, drain=" + drainMs + "ms, take=" + takeMs + //$NON-NLS-1$ //$NON-NLS-2$
                        "ms, other=" + otherTimeMs + "ms"); //$NON-NLS-1$ //$NON-NLS-2$
                    logPerfMessage("  Theoretical max consumer rate (add-only): " + theoreticalMaxRate + " items/sec"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                
                // Log producer breakdown (cumulative across all virtual threads)
                // NOTE: With streaming, "read" includes I/O + parse (overlapped in BufferedInputStream)
                if (fProducerReadTime != null && fProducerParseTime != null && fProducerQueuePutTime != null && peakActiveBatches != null) {
                    long producerReadMs = fProducerReadTime.sum() / 1_000_000;
                    long producerParseMs = fProducerParseTime.sum() / 1_000_000;
                    long producerQueueMs = fProducerQueuePutTime.sum() / 1_000_000;
                    int numFiles = elementFiles.size();
                    logPerfMessage("  Producer: peakConcurrentThreads=" + peakActiveBatches.get() + " (of " + numFiles + " files)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    logPerfMessage("  Producer breakdown (cumulative): readParse=" + producerReadMs + //$NON-NLS-1$
                        "ms, parseSeparate=" + producerParseMs + "ms, queuePut=" + producerQueueMs + "ms"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    // Per-file average (more meaningful for concurrent threads)
                    long readPerFileUs = numFiles > 0 ? (fProducerReadTime.sum() / numFiles) / 1000 : 0;
                    long parsePerFileUs = numFiles > 0 ? (fProducerParseTime.sum() / numFiles) / 1000 : 0;
                    // Calculate effective parallelism = cumulative time / wall clock time
                    long wallClockMs = (System.nanoTime() - consumerStart) / 1_000_000;
                    int effectiveParallelism = wallClockMs > 0 ? (int)(producerReadMs / wallClockMs) : 0;
                    logPerfMessage("  Producer per-file avg: readParse=" + readPerFileUs + "us, effectiveParallelism=" + effectiveParallelism); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        logTiming("  Phase2+3 TOTAL (overlapped I/O + model build)", phaseStart, elementFiles.size()); //$NON-NLS-1$
        logTiming("  loadModelWithDirCache TOTAL", methodStart, totalModelFiles); //$NON-NLS-1$
        
        return model;
    }
    
    /**
     * Holds a parsed element with its target folder path.
     * Used for the producer/consumer queue in DirCache-based loading.
     */
    private record ElementWithFolder(String folderPath, EObject element) {}
    
    /**
     * Intermediate record holding raw bytes read from disk, waiting for XML parsing.
     */
    private record RawFileData(DirCacheFileEntry entry, byte[] bytes) {}
    
    /**
     * Read, parse, and queue an element using STREAMING I/O.
     * Parses XML directly from FileInputStream - no intermediate byte buffer.
     * This allows overlapped I/O and parsing within each file.
     * 
     * Combined with semaphore control (max 1000 concurrent), this provides:
     * - Bounded parallelism (no queue contention from 26,680 threads)
     * - Streaming parsing (I/O and XML parsing overlap)
     * - Backpressure via bounded queue
     * 
     * @param entry The DirCache file entry
     * @param queue The queue to put parsed elements into
     * @param remaining Counter for remaining elements (decremented on completion)
     * @param errorFlag Set to true if any producer encounters an error
     * @param totalModelFiles Total files for progress reporting
     */
    private void readParseAndQueueStreaming(DirCacheFileEntry entry, BlockingQueue<ElementWithFolder> queue,
            AtomicInteger remaining, AtomicBoolean errorFlag, int totalModelFiles) {
        // Read from git object database instead of filesystem to reduce syscalls:
        // - No stat() to get metadata
        // - No open() to get file descriptor  
        // - Single read from pack file instead of multiple filesystem reads
        //
        // SAFETY: This method is only called from loadModelWithDirCache(), which is only
        // called when initDirCacheForImport() returns true (meaning fGitRepository is initialized).
        try {
            if (fGitRepository == null) {
                throw new IllegalStateException("readParseAndQueueStreaming called without git repository initialized"); //$NON-NLS-1$
            }
            
            // PROFILING: Break down the per-file average
            long ioStart = PERF_LOGGING ? System.nanoTime() : 0;
            
            // Read from git object database using ObjectId from DirCache
            long readStart = PERF_LOGGING ? System.nanoTime() : 0;
            ObjectLoader loader = fGitRepository.open(entry.objectId());
            byte[] bytes = loader.getCachedBytes();
            
            try (InputStream inputStream = new java.io.ByteArrayInputStream(bytes)) {
                long parseStart = PERF_LOGGING ? System.nanoTime() : 0;
                long ioTime = PERF_LOGGING ? (parseStart - ioStart) : 0;  // Time to read from object DB
                
                IIdentifier eObject = GraficoResourceLoader.loadEObject(inputStream);
                long parseEnd = PERF_LOGGING ? System.nanoTime() : 0;
                long parseTime = PERF_LOGGING ? (parseEnd - parseStart) : 0;
                
                fIDLookup.put(eObject.getId(), eObject);
                if (PERF_LOGGING && fProducerReadTime != null) {
                    fProducerReadTime.add(System.nanoTime() - readStart);
                }
                
                // Increment produced BEFORE batching to ensure count reflects items about to enter queue
                if (fProgressReporter != null) {
                    fProgressReporter.incrementProduced();
                }
                
                // BATCHING: Add to thread-local buffer instead of directly to queue
                // This reduces queue.put() operations from 26,679 to ~1,300 (20x reduction)
                long putStart = PERF_LOGGING ? System.nanoTime() : 0;
                List<ElementWithFolder> localBatch = PRODUCER_BATCH_BUFFER.get();
                localBatch.add(new ElementWithFolder(entry.folderPath(), eObject));
                
                int remainingCount = remaining.get();
                
                // Flush when batch is full OR this is one of the last files
                if (localBatch.size() >= PRODUCER_BATCH_SIZE || remainingCount <= PRODUCER_BATCH_SIZE) {
                    queue.addAll(localBatch);  // ONE lock for entire batch!
                    localBatch.clear();
                }
                
                long putEnd = PERF_LOGGING ? System.nanoTime() : 0;
                long putTime = PERF_LOGGING ? (putEnd - putStart) : 0;
                
                if (PERF_LOGGING && fProducerQueuePutTime != null) {
                    fProducerQueuePutTime.add(putTime);
                }
                
                // Every 1000 files, log breakdown to identify bottleneck
                if (PERF_LOGGING && remainingCount % 1000 == 0) {
                    logPerfMessage(String.format("  PRODUCER BREAKDOWN: I/O=%dms, Parse=%dms, QueuePut=%dms, BatchSize=%d (remaining=%d)", //$NON-NLS-1$
                        ioTime / 1_000_000, parseTime / 1_000_000, putTime / 1_000_000, localBatch.size(), remainingCount));
                }
            }
        } catch (IOException e) {
            errorFlag.set(true);
            try {
                // Flush any pending batch on error
                List<ElementWithFolder> localBatch = PRODUCER_BATCH_BUFFER.get();
                if (!localBatch.isEmpty()) {
                    queue.addAll(localBatch);
                    localBatch.clear();
                }
                queue.put(new ElementWithFolder(entry.folderPath(), null));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        } finally {
            remaining.decrementAndGet();
            
            // CRITICAL: Flush any remaining items in thread-local batch when thread completes
            // This ensures the last 0-19 items don't get stuck in the buffer
            try {
                List<ElementWithFolder> localBatch = PRODUCER_BATCH_BUFFER.get();
                if (!localBatch.isEmpty()) {
                    queue.addAll(localBatch);
                    localBatch.clear();
                }
            } catch (Exception e) {
                // Ignore - queue might be closed or thread interrupted
            }
            
            // Clean up thread-local to avoid memory leak
            PRODUCER_BATCH_BUFFER.remove();
            
            // Progress reporting moved to consumer to avoid contention
        }
    }
    
    /**
     * Read, parse, and queue an element DIRECTLY on virtual thread.
     * No CPU executor handoff - parsing is only 3% of total time, so keep it simple.
     * Maximum I/O parallelism: each of 26,680 files gets its own virtual thread.
     * 
     * @param entry The DirCache file entry
     * @param queue The queue to put parsed elements into
     * @param remaining Counter for remaining elements (decremented on completion)
     * @param errorFlag Set to true if any producer encounters an error
     * @param totalModelFiles Total files for progress reporting
     */
    private void readParseAndQueueDirect(DirCacheFileEntry entry, BlockingQueue<ElementWithFolder> queue,
            AtomicInteger remaining, AtomicBoolean errorFlag, int totalModelFiles) {
        try {
            // I/O: Read file (this is 97% of time on cold cache)
            long readStart = PERF_LOGGING ? System.nanoTime() : 0;
            byte[] bytes = Files.readAllBytes(entry.absolutePath());
            if (PERF_LOGGING && fProducerReadTime != null) {
                fProducerReadTime.add(System.nanoTime() - readStart);
            }
            
            // CPU: Parse XML (only 3% of time, OK to do on virtual thread)
            long parseStart = PERF_LOGGING ? System.nanoTime() : 0;
            try (InputStream inputStream = new java.io.ByteArrayInputStream(bytes)) {
                IIdentifier eObject = GraficoResourceLoader.loadEObject(inputStream);
                fIDLookup.put(eObject.getId(), eObject);
                if (PERF_LOGGING && fProducerParseTime != null) {
                    fProducerParseTime.add(System.nanoTime() - parseStart);
                }
                
                // Put into queue for consumer (may block if queue is full)
                long putStart = PERF_LOGGING ? System.nanoTime() : 0;
                queue.put(new ElementWithFolder(entry.folderPath(), eObject));
                if (PERF_LOGGING && fProducerQueuePutTime != null) {
                    fProducerQueuePutTime.add(System.nanoTime() - putStart);
                }
                if (fProgressReporter != null) {
                    fProgressReporter.incrementProduced();
                }
            }
        } catch (IOException | InterruptedException e) {
            errorFlag.set(true);
            try {
                queue.put(new ElementWithFolder(entry.folderPath(), null));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        } finally {
            remaining.decrementAndGet();
            // Progress reporting moved to consumer to avoid contention
        }
    }
    
    /**
     * Read, parse, and queue an element for the consumer thread.
     * 
     * CRITICAL ARCHITECTURE: Split I/O from CPU work!
     * - Virtual threads: ONLY for blocking I/O (Files.readAllBytes)
     * - ForkJoinPool: For CPU-bound XML parsing
     * 
     * Why this matters:
     * - Virtual threads are efficient for I/O because they "park" when blocked
     * - But XML parsing is CPU-bound and "pins" the carrier thread
     * - Pinned carrier threads limit concurrency to ~20 (number of carriers)
     * - By handing off to ForkJoinPool, we get 20 parallel parsers + unlimited I/O
     * 
     * @param entry The DirCache file entry
     * @param queue The queue to put parsed elements into
     * @param remaining Counter for remaining elements (decremented on completion)
     * @param errorFlag Set to true if any producer encounters an error
     * @param totalModelFiles Total files for progress reporting
     */
    private void readParseAndQueueElement(DirCacheFileEntry entry, BlockingQueue<ElementWithFolder> queue,
            AtomicInteger remaining, AtomicBoolean errorFlag, int totalModelFiles) {
        try {
            // STEP 1: I/O on virtual thread - this parks, doesn't pin
            byte[] bytes = Files.readAllBytes(entry.absolutePath());
            
            // STEP 2: Hand off to CPU executor for XML parsing
            // This keeps virtual threads free for more I/O
            fCpuExecutor.execute(() -> {
                try (InputStream inputStream = new java.io.ByteArrayInputStream(bytes)) {
                    IIdentifier eObject = GraficoResourceLoader.loadEObject(inputStream);
                    fIDLookup.put(eObject.getId(), eObject);
                    
                    // Put into queue for consumer
                    queue.put(new ElementWithFolder(entry.folderPath(), eObject));
                    if (fProgressReporter != null) {
                        fProgressReporter.incrementProduced();
                    }
                } catch (IOException | InterruptedException e) {
                    errorFlag.set(true);
                    try {
                        queue.put(new ElementWithFolder(entry.folderPath(), null));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                } finally {
                    remaining.decrementAndGet();
                    // Progress reporting moved to consumer to avoid contention
                }
            });
        } catch (IOException e) {
            errorFlag.set(true);
            remaining.decrementAndGet();
            try {
                queue.put(new ElementWithFolder(entry.folderPath(), null));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Read, parse, and queue an element for the consumer thread (batched version).
     * This is called sequentially within a batch on ForkJoinPool, so only one file 
     * handle is open at a time per batch.
     * 
     * @param entry The DirCache file entry
     * @param queue The queue to put parsed elements into
     * @param remaining Counter for remaining elements (decremented on completion)
     * @param errorFlag Set to true if any producer encounters an error
     * @param totalModelFiles Total files for progress reporting
     */
    private void readParseAndQueueBatched(DirCacheFileEntry entry, BlockingQueue<ElementWithFolder> queue,
            AtomicInteger remaining, AtomicBoolean errorFlag, int totalModelFiles) {
        try {
            // Synchronous I/O - OK because we're in a ForkJoinPool batch
            // Only one file open per batch (sequential within batch)
            long readStart = PERF_LOGGING ? System.nanoTime() : 0;
            byte[] bytes = Files.readAllBytes(entry.absolutePath());
            if (PERF_LOGGING && fProducerReadTime != null) {
                fProducerReadTime.add(System.nanoTime() - readStart);
            }
            
            long parseStart = PERF_LOGGING ? System.nanoTime() : 0;
            try (InputStream inputStream = new java.io.ByteArrayInputStream(bytes)) {
                IIdentifier eObject = GraficoResourceLoader.loadEObject(inputStream);
                fIDLookup.put(eObject.getId(), eObject);
                if (PERF_LOGGING && fProducerParseTime != null) {
                    fProducerParseTime.add(System.nanoTime() - parseStart);
                }
                
                // Put into queue for consumer (may block if queue is full = backpressure)
                long putStart = PERF_LOGGING ? System.nanoTime() : 0;
                queue.put(new ElementWithFolder(entry.folderPath(), eObject));
                if (PERF_LOGGING && fProducerQueuePutTime != null) {
                    fProducerQueuePutTime.add(System.nanoTime() - putStart);
                }
                if (fProgressReporter != null) {
                    fProgressReporter.incrementProduced();
                }
            }
        } catch (IOException | InterruptedException e) {
            errorFlag.set(true);
            try {
                queue.put(new ElementWithFolder(entry.folderPath(), null));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        } finally {
            remaining.decrementAndGet();
            // Progress reporting moved to consumer to avoid contention
        }
    }
    
    /**
     * Read and parse a file directly.
     * Called from virtual threads for I/O, hands off to CPU executor for parsing.
     * Used for folder.xml files where we need the content for hierarchy building.
     */
    private void readAndParseDirect(Path path, Map<String, EObject> results, CountDownLatch latch) {
        try {
            // STEP 1: I/O on virtual thread - parks, doesn't pin carrier
            byte[] bytes = Files.readAllBytes(path);
            
            // STEP 2: Parse on CPU executor to avoid pinning virtual thread carrier
            fCpuExecutor.execute(() -> {
                try (InputStream inputStream = new java.io.ByteArrayInputStream(bytes)) {
                    IIdentifier eObject = GraficoResourceLoader.loadEObject(inputStream);
                    fIDLookup.put(eObject.getId(), eObject);
                    if (eObject instanceof IArchimateModel) {
                        for (IProfile profile : ((IArchimateModel) eObject).getProfiles()) {
                            fIDLookup.put(profile.getId(), profile);
                        }
                    }
                    results.put(path.toString(), eObject);
                } catch (IOException e) {
                    // Ignore - folder will be missing
                } finally {
                    latch.countDown();
                }
            });
        } catch (IOException e) {
            // I/O failed - still need to count down
            latch.countDown();
        }
    }
    
    /**
     * Read and parse a folder.xml file synchronously (for batched ForkJoinPool processing).
     * This is called sequentially within a batch, so only one file handle is open at a time per batch.
     * 
     * @param path The path to read
     * @param results Map to store parsed results
     */
    private void readAndParseBatched(Path path, Map<String, EObject> results) {
        try {
            // Synchronous I/O - OK because we're in a ForkJoinPool batch
            // Only one file open per batch (sequential within batch)
            byte[] bytes = Files.readAllBytes(path);
            
            try (InputStream inputStream = new java.io.ByteArrayInputStream(bytes)) {
                IIdentifier eObject = GraficoResourceLoader.loadEObject(inputStream);
                fIDLookup.put(eObject.getId(), eObject);
                if (eObject instanceof IArchimateModel) {
                    for (IProfile profile : ((IArchimateModel) eObject).getProfiles()) {
                        fIDLookup.put(profile.getId(), profile);
                    }
                }
                results.put(path.toString(), eObject);
                if (fProgressReporter != null) {
                    fProgressReporter.incrementBy(1);
                }
            }
        } catch (IOException e) {
            // Ignore - folder will be missing from results
        }
    }
    
    /**
     * Read, parse, and store an element file directly (for DirCache-based loading).
     * Similar to readParseAndStoreDirect but uses Path as key.
     */
    private void readParseAndStoreDirectForDirCache(Path path, Map<Path, EObject> results, 
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
                        
                        // Parse on CPU executor
                        fCpuExecutor.execute(() -> {
                            try (InputStream inputStream = new java.io.ByteArrayInputStream(bytes)) {
                                IIdentifier eObject = GraficoResourceLoader.loadEObject(inputStream);
                                fIDLookup.put(eObject.getId(), eObject);
                                results.put(path, eObject);
                            } catch (IOException e) {
                                // Ignore - element will be missing
                            } finally {
                                if (fProgressReporter != null) {
                                    fProgressReporter.incrementBy(1);
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
                    try { channel.close(); } catch (IOException e) { }
                    latch.countDown();
                }
            });
        } catch (IOException e) {
            latch.countDown();
        }
    }
    
    /**
     * Calculate optimal batch count based on OS file descriptor limits and resource count.
     * 
     * <p>For importing ~30,000 files, we need enough parallel batches to saturate disk I/O
     * while avoiding OS file descriptor exhaustion. Each batch opens files sequentially,
     * so concurrent open file handles = number of active batches.</p>
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
            maxBatches = 1000;
        }
        
        // Scale by CPU cores: more cores = more useful parallelism
        // Use 100x CPU cores as baseline - OS-specific maxBatches caps the result
        int cpuBasedBatches = cpuCores * 100;
        
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
    
    // ================================================================================
    // ISOLATION TESTS - To identify where time is being spent
    // ================================================================================
    
    /**
     * Run isolation tests to identify performance bottlenecks.
     * Each test isolates a different component of the import pipeline.
     * 
     * Results are logged to help identify:
     * - Is disk I/O the bottleneck?
     * - Is XML parsing the bottleneck?
     * - Is EMF model building the bottleneck?
     * - Is there hidden synchronization limiting parallelism?
     */
    private void runIsolationTests(List<DirCacheFileEntry> elementFiles) {
        logPerfMessage("=== RUNNING ISOLATION TESTS ==="); //$NON-NLS-1$
        logPerfMessage("Files to test: " + elementFiles.size()); //$NON-NLS-1$
        
        // Log JVM and system info that might affect disk I/O
        logPerfMessage("=== JVM/System Diagnostics ==="); //$NON-NLS-1$
        logPerfMessage("  Java version: " + System.getProperty("java.version")); //$NON-NLS-1$ //$NON-NLS-2$
        logPerfMessage("  Java VM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        logPerfMessage("  OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        logPerfMessage("  Available processors: " + Runtime.getRuntime().availableProcessors()); //$NON-NLS-1$
        logPerfMessage("  Max memory: " + (Runtime.getRuntime().maxMemory() / 1024 / 1024) + " MB"); //$NON-NLS-1$ //$NON-NLS-2$
        logPerfMessage("  Free memory: " + (Runtime.getRuntime().freeMemory() / 1024 / 1024) + " MB"); //$NON-NLS-1$ //$NON-NLS-2$
        
        // Check for relevant JVM properties that affect I/O
        String directBuffers = System.getProperty("jdk.nio.maxCachedBufferSize"); //$NON-NLS-1$
        logPerfMessage("  jdk.nio.maxCachedBufferSize: " + (directBuffers != null ? directBuffers : "(default)")); //$NON-NLS-1$ //$NON-NLS-2$
        
        String fileEncoding = System.getProperty("sun.jnu.encoding"); //$NON-NLS-1$
        logPerfMessage("  File encoding: " + fileEncoding); //$NON-NLS-1$
        
        // ForkJoinPool common pool parallelism
        logPerfMessage("  ForkJoinPool.commonPool parallelism: " + java.util.concurrent.ForkJoinPool.commonPool().getParallelism()); //$NON-NLS-1$
        
        // Check if running with certain JVM flags
        java.lang.management.RuntimeMXBean runtimeMxBean = java.lang.management.ManagementFactory.getRuntimeMXBean();
        List<String> inputArguments = runtimeMxBean.getInputArguments();
        for (String arg : inputArguments) {
            if (arg.contains("MaxDirectMemory") || arg.contains("UseNUMA") ||  //$NON-NLS-1$ //$NON-NLS-2$
                arg.contains("ParallelGC") || arg.contains("G1GC") || arg.contains("ZGC") || //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                arg.contains("Xmx") || arg.contains("Xms")) { //$NON-NLS-1$ //$NON-NLS-2$
                logPerfMessage("  JVM arg: " + arg); //$NON-NLS-1$
            }
        }
        
        logPerfMessage("=== End Diagnostics ==="); //$NON-NLS-1$
        
        logPerfMessage("NOTE: Run CompletableFuture test FIRST to measure cold-cache performance!"); //$NON-NLS-1$
        
        // IMPORTANT: Run CompletableFuture test FIRST while cache is still cold!
        // Test 1e: Pure Disk I/O - CompletableFuture with ForkJoinPool (like real import) - COLD CACHE
        logPerfMessage("--- Test 1e: Pure Disk I/O (CompletableFuture + ForkJoinPool, COLD CACHE) ---"); //$NON-NLS-1$
        runTest1e_CompletableFuture_ForkJoinPool(elementFiles);
        
        // Test 1b: Pure Disk I/O - Parallel with ForkJoinPool parallelStream (20 threads) - WARM CACHE now
        logPerfMessage("--- Test 1b: Pure Disk I/O (ForkJoinPool parallelStream, WARM CACHE) ---"); //$NON-NLS-1$
        runTest1b_ParallelDiskIO_ForkJoin(elementFiles);
        
        // Test 1c: Pure Disk I/O - Parallel with Virtual Threads (1000 concurrent) - WARM CACHE
        logPerfMessage("--- Test 1c: Pure Disk I/O (Virtual Threads, 1000 batches, WARM CACHE) ---"); //$NON-NLS-1$
        runTest1c_ParallelDiskIO_VirtualThreads(elementFiles);
        
        // Test 1a: Pure Disk I/O - Sequential (WARM cache)
        // We know from previous runs that sequential cold cache = ~95s
        logPerfMessage("--- Test 1a: Pure Disk I/O (sequential, WARM CACHE - baseline was 95s cold) ---"); //$NON-NLS-1$
        runTest1a_SequentialDiskIO(elementFiles);
        
        // Test 1d is skipped - AsyncFileChannel was slower (62s vs 44s ForkJoinPool)
        
        // Test 2: Pure Parsing (single-threaded, no disk I/O)
        // This isolates EMF/SAX parsing cost
        Map<Path, byte[]> preloadedData = runTest2_PureParsingSingleThreaded(elementFiles);
        
        // Test 3: Parallel Parsing (no disk I/O)
        // This tests if parsing can scale with threads
        if (preloadedData != null) {
            runTest3_ParallelParsing(preloadedData);
        }
        
        // Test 4: Batched Parallel I/O + Parsing (current approach)
        // For comparison with the isolation tests
        runTest4_BatchedParallelIOAndParsing(elementFiles);
        
        logPerfMessage("=== ISOLATION TESTS COMPLETE ==="); //$NON-NLS-1$
    }
    
    /**
     * Test 1e: Pure Disk I/O with CompletableFuture + ForkJoinPool.
     * This matches the pattern used in the real import.
     */
    private void runTest1e_CompletableFuture_ForkJoinPool(List<DirCacheFileEntry> elementFiles) {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        ForkJoinPool pool = new ForkJoinPool(cpuCores);
        java.util.concurrent.atomic.LongAdder totalBytes = new java.util.concurrent.atomic.LongAdder();
        
        // Use batching like the real import (1000 batches)
        int batchCount = 1000;
        int batchSize = Math.max(1, (elementFiles.size() + batchCount - 1) / batchCount);
        
        logPerfMessage(String.format("  Using %d CPU cores, %d batches of ~%d files", cpuCores, batchCount, batchSize)); //$NON-NLS-1$
        
        long start = System.nanoTime();
        
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            
            for (int i = 0; i < elementFiles.size(); i += batchSize) {
                final int startIdx = i;
                final int endIdx = Math.min(i + batchSize, elementFiles.size());
                
                futures.add(CompletableFuture.runAsync(() -> {
                    for (int j = startIdx; j < endIdx; j++) {
                        try {
                            byte[] data = Files.readAllBytes(elementFiles.get(j).absolutePath());
                            totalBytes.add(data.length);
                        } catch (IOException e) {
                            // Ignore errors
                        }
                    }
                }, pool));
            }
            
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            pool.shutdown();
        }
        
        long elapsed = System.nanoTime() - start;
        double elapsedSec = elapsed / 1_000_000_000.0;
        double mbPerSec = (totalBytes.sum() / 1_000_000.0) / elapsedSec;
        double filesPerSec = elementFiles.size() / elapsedSec;
        
        logPerfMessage(String.format("  Test 1e Result: %.2fs, %d files, %.1f MB, %.1f MB/s, %.0f files/s", //$NON-NLS-1$
            elapsedSec, elementFiles.size(), totalBytes.sum() / 1_000_000.0, mbPerSec, filesPerSec));
    }
    
    /**
     * Test 1a: Pure Disk I/O - Sequential reads without parsing.
     * This is the WORST case - each file opened, read, closed sequentially.
     * NOTE: Caller logs the test header with cache state info.
     */
    private void runTest1a_SequentialDiskIO(List<DirCacheFileEntry> elementFiles) {
        long totalBytes = 0;
        long start = System.nanoTime();
        
        for (DirCacheFileEntry entry : elementFiles) {
            try {
                byte[] data = Files.readAllBytes(entry.absolutePath());
                totalBytes += data.length;
            } catch (IOException e) {
                // Ignore errors in test
            }
        }
        
        long elapsed = System.nanoTime() - start;
        double elapsedSec = elapsed / 1_000_000_000.0;
        double mbPerSec = (totalBytes / 1_000_000.0) / elapsedSec;
        double filesPerSec = elementFiles.size() / elapsedSec;
        
        logPerfMessage(String.format("  Test 1a Result: %.2fs, %d files, %.1f MB, %.1f MB/s, %.0f files/s", //$NON-NLS-1$
            elapsedSec, elementFiles.size(), totalBytes / 1_000_000.0, mbPerSec, filesPerSec));
    }
    
    /**
     * Test 1b: Parallel Disk I/O with ForkJoinPool (limited to CPU cores).
     * This tests if we can parallelize file opens/reads with platform threads.
     * NOTE: Caller logs the test header with cache state info.
     */
    private void runTest1b_ParallelDiskIO_ForkJoin(List<DirCacheFileEntry> elementFiles) {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        ForkJoinPool pool = new ForkJoinPool(cpuCores);
        java.util.concurrent.atomic.LongAdder totalBytes = new java.util.concurrent.atomic.LongAdder();
        
        long start = System.nanoTime();
        
        try {
            pool.submit(() -> 
                elementFiles.parallelStream().forEach(entry -> {
                    try {
                        byte[] data = Files.readAllBytes(entry.absolutePath());
                        totalBytes.add(data.length);
                    } catch (IOException e) {
                        // Ignore errors
                    }
                })
            ).get();
        } catch (InterruptedException | ExecutionException e) {
            logPerfMessage("  Test 1b failed: " + e.getMessage()); //$NON-NLS-1$
            return;
        } finally {
            pool.shutdown();
        }
        
        long elapsed = System.nanoTime() - start;
        double elapsedSec = elapsed / 1_000_000_000.0;
        double mbPerSec = (totalBytes.sum() / 1_000_000.0) / elapsedSec;
        double filesPerSec = elementFiles.size() / elapsedSec;
        
        logPerfMessage(String.format("  Test 1b Result: %.2fs, %d files, %.1f MB, %.1f MB/s, %.0f files/s", //$NON-NLS-1$
            elapsedSec, elementFiles.size(), totalBytes.sum() / 1_000_000.0, mbPerSec, filesPerSec));
    }
    
    /**
     * Test 1c: Parallel Disk I/O with Virtual Threads (1000 concurrent).
     * This tests if virtual threads can achieve more parallelism than platform threads.
     * NOTE: Caller logs the test header with cache state info.
     */
    private void runTest1c_ParallelDiskIO_VirtualThreads(List<DirCacheFileEntry> elementFiles) {
        int batchCount = 1000;
        int batchSize = Math.max(1, (elementFiles.size() + batchCount - 1) / batchCount);
        
        java.util.concurrent.atomic.LongAdder totalBytes = new java.util.concurrent.atomic.LongAdder();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        
        long start = System.nanoTime();
        
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < elementFiles.size(); i += batchSize) {
            final int startIdx = i;
            final int endIdx = Math.min(i + batchSize, elementFiles.size());
            
            futures.add(CompletableFuture.runAsync(() -> {
                for (int j = startIdx; j < endIdx; j++) {
                    try {
                        byte[] data = Files.readAllBytes(elementFiles.get(j).absolutePath());
                        totalBytes.add(data.length);
                    } catch (IOException e) {
                        // Ignore errors
                    }
                }
            }, executor));
        }
        
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        executor.shutdown();
        
        long elapsed = System.nanoTime() - start;
        double elapsedSec = elapsed / 1_000_000_000.0;
        double mbPerSec = (totalBytes.sum() / 1_000_000.0) / elapsedSec;
        double filesPerSec = elementFiles.size() / elapsedSec;
        
        logPerfMessage(String.format("  Test 1c Result: %.2fs, %d files, %.1f MB, %.1f MB/s, %.0f files/s", //$NON-NLS-1$
            elapsedSec, elementFiles.size(), totalBytes.sum() / 1_000_000.0, mbPerSec, filesPerSec));
    }
    
    /**
     * Test 1d: TRUE Async Disk I/O with AsynchronousFileChannel.
     * This fires ALL reads simultaneously and lets the OS handle scheduling.
     * Uses a semaphore to limit concurrent file handles to avoid exhaustion.
     * NOTE: Caller logs the test header with cache state info.
     */
    private void runTest1d_TrueAsyncDiskIO(List<DirCacheFileEntry> elementFiles) {
        // Limit concurrent file handles to avoid exhaustion (Windows limit ~500)
        final int maxConcurrent = 200;
        final java.util.concurrent.Semaphore semaphore = new java.util.concurrent.Semaphore(maxConcurrent);
        
        java.util.concurrent.atomic.LongAdder totalBytes = new java.util.concurrent.atomic.LongAdder();
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger peakConcurrent = new AtomicInteger(0);
        AtomicInteger currentConcurrent = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(elementFiles.size());
        
        logPerfMessage("  Max concurrent file handles: " + maxConcurrent); //$NON-NLS-1$
        
        long start = System.nanoTime();
        
        // Fire all reads - semaphore limits concurrent file handles
        for (DirCacheFileEntry entry : elementFiles) {
            try {
                semaphore.acquire();
                
                // Track peak concurrency
                int current = currentConcurrent.incrementAndGet();
                peakConcurrent.updateAndGet(peak -> Math.max(peak, current));
                
                Path path = entry.absolutePath();
                long fileSize;
                try {
                    fileSize = Files.size(path);
                } catch (IOException e) {
                    semaphore.release();
                    currentConcurrent.decrementAndGet();
                    latch.countDown();
                    continue;
                }
                
                ByteBuffer buffer = ByteBuffer.allocate((int) fileSize);
                
                AsynchronousFileChannel channel;
                try {
                    channel = AsynchronousFileChannel.open(path, StandardOpenOption.READ);
                } catch (IOException e) {
                    semaphore.release();
                    currentConcurrent.decrementAndGet();
                    latch.countDown();
                    continue;
                }
                
                channel.read(buffer, 0, buffer, new CompletionHandler<Integer, ByteBuffer>() {
                    @Override
                    public void completed(Integer bytesRead, ByteBuffer buf) {
                        try {
                            channel.close();
                            totalBytes.add(bytesRead);
                            completed.incrementAndGet();
                        } catch (IOException e) {
                            // Ignore
                        } finally {
                            currentConcurrent.decrementAndGet();
                            semaphore.release();
                            latch.countDown();
                        }
                    }
                    
                    @Override
                    public void failed(Throwable exc, ByteBuffer buf) {
                        try {
                            channel.close();
                        } catch (IOException e) {
                            // Ignore
                        }
                        currentConcurrent.decrementAndGet();
                        semaphore.release();
                        latch.countDown();
                    }
                });
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        // Wait for all reads to complete
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        long elapsed = System.nanoTime() - start;
        double elapsedSec = elapsed / 1_000_000_000.0;
        double mbPerSec = (totalBytes.sum() / 1_000_000.0) / elapsedSec;
        double filesPerSec = completed.get() / elapsedSec;
        
        logPerfMessage(String.format("  Test 1d Result: %.2fs, %d files, %.1f MB, %.1f MB/s, %.0f files/s", //$NON-NLS-1$
            elapsedSec, completed.get(), totalBytes.sum() / 1_000_000.0, mbPerSec, filesPerSec));
        logPerfMessage("  Peak concurrent file handles: " + peakConcurrent.get()); //$NON-NLS-1$
    }
    
    /**
     * Test 2: Pure Parsing (single-threaded) - Pre-load all files, then parse.
     * 
     * This isolates parsing cost from disk I/O.
     * Returns the preloaded data for use in Test 3.
     */
    private Map<Path, byte[]> runTest2_PureParsingSingleThreaded(List<DirCacheFileEntry> elementFiles) {
        logPerfMessage("--- Test 2: Pure Parsing (single-threaded, pre-loaded) ---"); //$NON-NLS-1$
        
        // First, pre-load all files into memory
        logPerfMessage("  Pre-loading all files into memory..."); //$NON-NLS-1$
        Map<Path, byte[]> preloaded = new HashMap<>();
        long preloadStart = System.nanoTime();
        
        for (DirCacheFileEntry entry : elementFiles) {
            try {
                preloaded.put(entry.absolutePath(), Files.readAllBytes(entry.absolutePath()));
            } catch (IOException e) {
                // Skip files that fail
            }
        }
        
        long preloadElapsed = (System.nanoTime() - preloadStart) / 1_000_000;
        logPerfMessage("  Pre-load complete: " + preloadElapsed + "ms for " + preloaded.size() + " files"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        
        // Force GC to clear allocation pressure
        System.gc();
        
        // Now measure parsing only (single-threaded)
        logPerfMessage("  Parsing (single-threaded)..."); //$NON-NLS-1$
        long parseStart = System.nanoTime();
        int parsed = 0;
        
        for (Map.Entry<Path, byte[]> entry : preloaded.entrySet()) {
            try (InputStream is = new java.io.ByteArrayInputStream(entry.getValue())) {
                GraficoResourceLoader.loadEObject(is);
                parsed++;
            } catch (IOException e) {
                // Skip files that fail
            }
        }
        
        long parseElapsed = System.nanoTime() - parseStart;
        double parseElapsedSec = parseElapsed / 1_000_000_000.0;
        double filesPerSec = parsed / parseElapsedSec;
        
        logPerfMessage(String.format("  Test 2 Result: %.2fs, %d files parsed, %.0f files/s", //$NON-NLS-1$
            parseElapsedSec, parsed, filesPerSec));
        
        return preloaded;
    }
    
    /**
     * Test 3: Parallel Parsing - Parse from pre-loaded data using ForkJoinPool.
     * 
     * This tests if parsing can scale with threads.
     * If single-threaded = parallel, there's synchronization limiting parallelism.
     */
    private void runTest3_ParallelParsing(Map<Path, byte[]> preloaded) {
        logPerfMessage("--- Test 3: Parallel Parsing (ForkJoinPool, pre-loaded) ---"); //$NON-NLS-1$
        
        int cpuCores = Runtime.getRuntime().availableProcessors();
        logPerfMessage("  Using ForkJoinPool with " + cpuCores + " threads"); //$NON-NLS-1$ //$NON-NLS-2$
        
        // Force GC before test
        System.gc();
        
        ForkJoinPool pool = new ForkJoinPool(cpuCores);
        AtomicInteger parsed = new AtomicInteger(0);
        
        long parseStart = System.nanoTime();
        
        try {
            pool.submit(() -> 
                preloaded.entrySet().parallelStream().forEach(entry -> {
                    try (InputStream is = new java.io.ByteArrayInputStream(entry.getValue())) {
                        GraficoResourceLoader.loadEObject(is);
                        parsed.incrementAndGet();
                    } catch (IOException e) {
                        // Skip files that fail
                    }
                })
            ).get();
        } catch (InterruptedException | ExecutionException e) {
            logPerfMessage("  Test 3 failed: " + e.getMessage()); //$NON-NLS-1$
            return;
        } finally {
            pool.shutdown();
        }
        
        long parseElapsed = System.nanoTime() - parseStart;
        double parseElapsedSec = parseElapsed / 1_000_000_000.0;
        double filesPerSec = parsed.get() / parseElapsedSec;
        double speedup = (preloaded.size() / parseElapsedSec) / (preloaded.size() / parseElapsedSec);
        
        logPerfMessage(String.format("  Test 3 Result: %.2fs, %d files parsed, %.0f files/s", //$NON-NLS-1$
            parseElapsedSec, parsed.get(), filesPerSec));
        logPerfMessage("  Expected speedup: " + cpuCores + "x, Actual: compare with Test 2"); //$NON-NLS-1$ //$NON-NLS-2$
    }
    
    /**
     * Test 4: Batched Parallel I/O + Parsing (current production approach).
     * 
     * This measures the combined I/O + parsing with batched virtual threads.
     * Useful for comparing against the isolation tests.
     */
    private void runTest4_BatchedParallelIOAndParsing(List<DirCacheFileEntry> elementFiles) {
        logPerfMessage("--- Test 4: Batched Parallel I/O + Parsing (1000 batches) ---"); //$NON-NLS-1$
        
        int batchCount = 1000;
        int batchSize = Math.max(1, (elementFiles.size() + batchCount - 1) / batchCount);
        
        AtomicInteger parsed = new AtomicInteger(0);
        ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();
        
        long start = System.nanoTime();
        
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < elementFiles.size(); i += batchSize) {
            final int startIdx = i;
            final int endIdx = Math.min(i + batchSize, elementFiles.size());
            
            futures.add(CompletableFuture.runAsync(() -> {
                for (int j = startIdx; j < endIdx; j++) {
                    try {
                        byte[] data = Files.readAllBytes(elementFiles.get(j).absolutePath());
                        try (InputStream is = new java.io.ByteArrayInputStream(data)) {
                            GraficoResourceLoader.loadEObject(is);
                            parsed.incrementAndGet();
                        }
                    } catch (IOException e) {
                        // Skip files that fail
                    }
                }
            }, ioExecutor));
        }
        
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        ioExecutor.shutdown();
        
        long elapsed = System.nanoTime() - start;
        double elapsedSec = elapsed / 1_000_000_000.0;
        double filesPerSec = parsed.get() / elapsedSec;
        
        logPerfMessage(String.format("  Test 4 Result: %.2fs, %d files, %.0f files/s", //$NON-NLS-1$
            elapsedSec, parsed.get(), filesPerSec));
    }
}
