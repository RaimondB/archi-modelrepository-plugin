/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
import org.archicontribs.modelrepository.preferences.IPreferenceConstants;
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
    	
    	// Reset the ID -> Object lookup table
    	fIDLookup = new ConcurrentHashMap<String, IIdentifier>();
    	
        // Load the Model from files (it will contain unresolved proxies)
        progress.subTask(Messages.GraficoModelImporter_1);
    	fModel = loadModel(modelFolder, progress.split(60));
    	
    	// Check for cancellation
    	if (progress.isCanceled()) {
    	    return null;
    	}
    	
    	// Create a new Resource for the model object so we can work with it in the ModelCompatibility class
    	Resource resource = new XMLResourceImpl();
    	resource.getContents().add(fModel);
    	
        // Resolve proxies
        progress.subTask(Messages.GraficoModelImporter_2);
        resolveProxies();
        progress.worked(15);

    	// New model compatibility
        ModelCompatibility modelCompatibility = new ModelCompatibility(resource);
    	
        // Fix any backward compatibility issues
    	// This has to be done here because GraficoModelLoader#loadModel() will save with latest metamodel version number
    	// And then the ModelCompatibility won't be able to tell the version number
        progress.subTask(Messages.GraficoModelImporter_3);
        try {
            modelCompatibility.fixCompatibility();
        }
        catch(CompatibilityHandlerException ex) {
            ModelRepositoryPlugin.getInstance().log(IStatus.ERROR, "Error loading model", ex); //$NON-NLS-1$
        }
        progress.worked(5);

    	// We now have to remove the Eobject from its Resource so it can be saved in its proper *.archimate format
        resource.getContents().remove(fModel);
        
        // Add Archive Manager and CommandStack
        IArchiveManager archiveManager = IArchiveManager.FACTORY.createArchiveManager(fModel);
        fModel.setAdapter(IArchiveManager.class, archiveManager);
        
        // We do need a CommandStack for ACLI
        CommandStack cmdStack = new CommandStack();
        fModel.setAdapter(CommandStack.class, cmdStack);
        
    	// Load images
    	progress.subTask(Messages.GraficoModelImporter_4);
    	loadImages(imagesFolder, archiveManager, progress.split(20));

    	return fModel;
    }
    
    /**
     * @return A list of unresolved objects. Can be null if no unresolved objects
     */
    public List<UnresolvedObject> getUnresolvedObjects() {
        return fUnresolvedObjects;
    }
    
    /**
     * Read images from images subfolder and load them into the model
     * Uses NIO2 Path APIs and CompletableFuture for parallel async I/O
     * @param monitor Progress monitor for UI feedback, can be null
     */
    private void loadImages(File folder, IArchiveManager archiveManager, IProgressMonitor monitor) throws IOException {
        SubMonitor progress = SubMonitor.convert(monitor);
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
        
        progress.setWorkRemaining(filesToLoad.size());
        
        // Use ForkJoinPool for work-stealing parallel reads
        int maxThreads = ModelRepositoryPlugin.getInstance().getPreferenceStore().getInt(IPreferenceConstants.PREFS_EXPORT_MAX_THREADS);
        ForkJoinPool executor = new ForkJoinPool(Math.min(maxThreads, filesToLoad.size()));
        
        // Store results in a concurrent map
        Map<String, byte[]> imageData = new ConcurrentHashMap<>();
        
        try {
            // Create CompletableFutures for all file reads
            List<CompletableFuture<Void>> futures = filesToLoad.stream()
                .map(path -> CompletableFuture.runAsync(() -> {
                    try {
                        byte[] bytes = Files.readAllBytes(path);
                        imageData.put(path.getFileName().toString(), bytes);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, executor))
                .collect(Collectors.toList());
            
            // Wait for all reads to complete using allOf
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
            );
            
            try {
                // Wait with timeout to allow cancellation checks
                while (!allFutures.isDone()) {
                    if (progress.isCanceled()) {
                        executor.shutdownNow();
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
            
            progress.worked(filesToLoad.size());
        } finally {
            executor.shutdown();
            try {
                executor.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
    
	private IArchimateModel loadModel(File folder, IProgressMonitor monitor) throws IOException {
        SubMonitor progress = SubMonitor.convert(monitor);
        
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

        progress.setWorkRemaining(folderList.size());

		// Loop based on FolderType enumeration
		for(FolderType folderType : folderList) {
		    // Check for cancellation
		    if (progress.isCanceled()) {
		        return model;
		    }
            progress.subTask(String.format(Messages.GraficoModelImporter_5, folderType.toString()));
		    IFolder tmpFolder = loadFolder(new File(folder, folderType.toString()), progress.split(1));
		    if(tmpFolder != null) {
		        model.getFolders().add(tmpFolder);
		    }
		}
		
		return model;
	}
	
	/**
	 * Load each XML file to recreate original object
	 * Uses NIO2 async I/O and CompletableFuture for better performance
	 * 
	 * @param folder
	 * @param monitor Progress monitor for UI feedback, can be null
	 * @return Model folder
	 * @throws IOException 
	 */
    private IFolder loadFolder(File folder, IProgressMonitor monitor) throws IOException {
        SubMonitor progress = SubMonitor.convert(monitor);
        
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
        
        int totalWork = filesToLoad.size() + foldersToLoad.size();
        progress.setWorkRemaining(totalWork > 0 ? totalWork : 1);
        
        // Load files in parallel using ForkJoinPool (work-stealing, better for I/O-bound tasks)
        if (!filesToLoad.isEmpty()) {
            int maxThreads = ModelRepositoryPlugin.getInstance().getPreferenceStore().getInt(IPreferenceConstants.PREFS_EXPORT_MAX_THREADS);
            ForkJoinPool executor = new ForkJoinPool(Math.min(maxThreads, filesToLoad.size()));
            
            // Use a concurrent map to store loaded elements
            Map<Path, EObject> loadedElements = new ConcurrentHashMap<>();
            
            try {
                // Create CompletableFutures for all file loads
                List<CompletableFuture<Void>> futures = filesToLoad.stream()
                    .map(path -> CompletableFuture.runAsync(() -> {
                        try {
                            EObject element = loadElementAsync(path);
                            loadedElements.put(path, element);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }, executor))
                    .collect(Collectors.toList());
                
                // Wait for all loads to complete using allOf for better composition
                CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0])
                );
                
                try {
                    // Wait with timeout to allow cancellation checks
                    while (!allFutures.isDone()) {
                        if (progress.isCanceled()) {
                            executor.shutdownNow();
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
                    progress.worked(1);
                }
            } finally {
                executor.shutdown();
                try {
                    executor.awaitTermination(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        
        // Load subfolders (recursively, with progress)
        for (Path subFolder : foldersToLoad) {
            // Check for cancellation
            if (progress.isCanceled()) {
                return currentFolder;
            }
            
            IFolder loadedFolder = loadFolder(subFolder.toFile(), progress.split(1));
            if (loadedFolder != null) {
                currentFolder.getFolders().add(loadedFolder);
            }
        }

        return currentFolder;
    }
    
    /**
     * Load an element using NIO2 async file reading for better I/O performance.
     * Reads file content asynchronously then parses it.
     * 
     * @param path Path to the XML file
     * @return The loaded EObject
     * @throws IOException
     */
    private EObject loadElementAsync(Path path) throws IOException {
        // Use buffered NIO2 InputStream which is more efficient than File-based access
        try (InputStream inputStream = Files.newInputStream(path, StandardOpenOption.READ)) {
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
     * Create an eObject from a Path. Uses NIO2 for better performance.
     * 
     * @param path
     * @return
     * @throws IOException 
     */
    private EObject loadElement(Path path) throws IOException {
        try (InputStream inputStream = Files.newInputStream(path, StandardOpenOption.READ)) {
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
     * Delegates to Path-based version for NIO2 performance.
     * 
     * @param file
     * @return
     * @throws IOException 
     */
    private EObject loadElement(File file) throws IOException {
        return loadElement(file.toPath());
    }
}
