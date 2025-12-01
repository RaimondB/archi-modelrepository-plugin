/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
     * Uses parallel I/O for better performance
     * @param monitor Progress monitor for UI feedback, can be null
     */
    private void loadImages(File folder, IArchiveManager archiveManager, IProgressMonitor monitor) throws IOException {
        SubMonitor progress = SubMonitor.convert(monitor);
        
        File[] imageFiles = folder.listFiles();
        if (imageFiles == null || imageFiles.length == 0) {
            return;
        }
        
        // Filter to only include files
        List<File> filesToLoad = new ArrayList<>();
        for (File imageFile : imageFiles) {
            if (imageFile.isFile()) {
                filesToLoad.add(imageFile);
            }
        }
        
        if (filesToLoad.isEmpty()) {
            return;
        }
        
        progress.setWorkRemaining(filesToLoad.size());
        
        // Use thread pool for parallel reads
        int maxThreads = ModelRepositoryPlugin.getInstance().getPreferenceStore().getInt(IPreferenceConstants.PREFS_EXPORT_MAX_THREADS);
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(maxThreads, filesToLoad.size()));
        
        // Store results in a concurrent map
        Map<String, byte[]> imageData = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();
        IOException firstException = null;
        
        try {
            // Submit parallel read tasks
            for (File imageFile : filesToLoad) {
                futures.add(executor.submit(() -> {
                    try {
                        byte[] bytes = Files.readAllBytes(imageFile.toPath());
                        imageData.put(imageFile.getName(), bytes);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            
            // Wait for all reads to complete
            for (Future<?> future : futures) {
                try {
                    future.get();
                    progress.worked(1);
                } catch (InterruptedException | ExecutionException e) {
                    if (firstException == null && e.getCause() instanceof IOException) {
                        firstException = (IOException) e.getCause();
                    } else if (firstException == null && e.getCause() instanceof RuntimeException 
                               && e.getCause().getCause() instanceof IOException) {
                        firstException = (IOException) e.getCause().getCause();
                    }
                }
                
                // Check for cancellation
                if (progress.isCanceled()) {
                    executor.shutdownNow();
                    return;
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
		    
		    IFolder tmpFolder = loadFolder(new File(folder, folderType.toString()), progress.split(1));
		    if(tmpFolder != null) {
		        model.getFolders().add(tmpFolder);
		    }
		}
		
		return model;
	}
	
	/**
	 * Load each XML file to recreate original object
	 * Uses parallel I/O for better performance
	 * 
	 * @param folder
	 * @param monitor Progress monitor for UI feedback, can be null
	 * @return Model folder
	 * @throws IOException 
	 */
    private IFolder loadFolder(File folder, IProgressMonitor monitor) throws IOException {
        SubMonitor progress = SubMonitor.convert(monitor);
        
        if(!folder.isDirectory() || !(new File(folder, IGraficoConstants.FOLDER_XML)).isFile()) {
            throw new IOException("File is not directory or folder.xml does not exist."); //$NON-NLS-1$
        }

        // Load folder object itself
        IFolder currentFolder = (IFolder)loadElement(new File(folder, IGraficoConstants.FOLDER_XML));

        // Get list of files/folders to process (excluding folder.xml)
        File[] contents = folder.listFiles();
        List<File> filesToLoad = new ArrayList<>();
        List<File> foldersToLoad = new ArrayList<>();
        
        if (contents != null) {
            for (File fileOrFolder : contents) {
                if (!fileOrFolder.getName().equals(IGraficoConstants.FOLDER_XML)) {
                    if (fileOrFolder.isFile()) {
                        filesToLoad.add(fileOrFolder);
                    } else {
                        foldersToLoad.add(fileOrFolder);
                    }
                }
            }
        }
        
        int totalWork = filesToLoad.size() + foldersToLoad.size();
        progress.setWorkRemaining(totalWork > 0 ? totalWork : 1);
        
        // Load files in parallel using thread pool
        if (!filesToLoad.isEmpty()) {
            int maxThreads = ModelRepositoryPlugin.getInstance().getPreferenceStore().getInt(IPreferenceConstants.PREFS_EXPORT_MAX_THREADS);
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(maxThreads, filesToLoad.size()));
            
            // Use a concurrent map to store loaded elements with their original order
            Map<File, EObject> loadedElements = new ConcurrentHashMap<>();
            List<Future<?>> futures = new ArrayList<>();
            
            try {
                // Submit parallel load tasks
                for (File file : filesToLoad) {
                    futures.add(executor.submit(() -> {
                        try {
                            EObject element = loadElement(file);
                            loadedElements.put(file, element);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }));
                }
                
                // Wait for all loads to complete
                IOException firstException = null;
                for (Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (InterruptedException | ExecutionException e) {
                        if (firstException == null) {
                            if (e.getCause() instanceof IOException) {
                                firstException = (IOException) e.getCause();
                            } else if (e.getCause() instanceof RuntimeException 
                                       && e.getCause().getCause() instanceof IOException) {
                                firstException = (IOException) e.getCause().getCause();
                            }
                        }
                    }
                    
                    // Check for cancellation
                    if (progress.isCanceled()) {
                        executor.shutdownNow();
                        return currentFolder;
                    }
                }
                
                if (firstException != null) {
                    throw firstException;
                }
                
                // Add elements in original order to maintain consistency
                for (File file : filesToLoad) {
                    EObject element = loadedElements.get(file);
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
        for (File subFolder : foldersToLoad) {
            // Check for cancellation
            if (progress.isCanceled()) {
                return currentFolder;
            }
            
            IFolder loadedFolder = loadFolder(subFolder, progress.split(1));
            if (loadedFolder != null) {
                currentFolder.getFolders().add(loadedFolder);
            }
        }

        return currentFolder;
    }

    /**
     * Create an eObject from an XML file. Basically load a resource.
     * 
     * @param file
     * @return
     * @throws IOException 
     */
    private EObject loadElement(File file) throws IOException {
        IIdentifier eObject = GraficoResourceLoader.loadEObject(file);
        
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
