/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.review;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.util.ArchimateModelUtils;

/**
 * Information about a changed object for review
 * 
 * Represents a single change between HEAD and current model.
 * Similar to MergeObjectInfo but for reviewing local changes.
 * 
 * PERFORMANCE: Display information is extracted from the file path, 
 * not from parsing XML. HEAD model is loaded lazily only when needed.
 * 
 * @author Raimond Brookman
 */
public class ChangeInfo {

    /**
     * Enable debug logging for review changes feature.
     * Enable with JVM arg: -Dreview.debug.logging=true
     */
    private static final boolean DEBUG_LOGGING = Boolean.getBoolean("review.debug.logging"); //$NON-NLS-1$
    
    // Pattern to extract type and ID from filename: ElementType_id.xml
    private static final Pattern ELEMENT_FILE_PATTERN = Pattern.compile("([A-Za-z]+)_([^/]+)\\.xml$"); //$NON-NLS-1$
    
    private static void logDebug(String message) {
        if (!DEBUG_LOGGING) return;
        ModelRepositoryPlugin.getInstance().log(IStatus.INFO, "[ChangeInfo] " + message, null); //$NON-NLS-1$
    }

    /**
     * Change types
     */
    public static final int ADDED = 0;
    public static final int DELETED = 1;
    public static final int MODIFIED = 2;
    
    /**
     * User choices
     */
    public static final int KEEP = 0;
    public static final int REVERT = 1;
    
    /**
     * Indices for object arrays
     */
    public static final int CURRENT = 0;
    public static final int HEAD = 1;

    private ChangeReviewHandler handler;
    private String xmlPath;
    private int changeType;
    
    // Display info extracted from path (no parsing needed)
    private String elementType;
    private String elementId;
    
    // Referenced EObjects - current and HEAD (loaded lazily)
    private EObject[] objects = new EObject[2];
    private boolean headObjectLoaded = false;
    
    // User's choice - default is to keep changes
    private int userChoice = KEEP;

    /**
     * Constructor
     * 
     * PERFORMANCE: Only loads from current model during construction.
     * HEAD object is loaded lazily when needed (for display or revert).
     * 
     * @param xmlPath The path to the XML file in the repository
     * @param changeType The type of change (ADDED, DELETED, MODIFIED)
     * @param handler The parent handler
     * @throws IOException If loading fails
     */
    ChangeInfo(String xmlPath, int changeType, ChangeReviewHandler handler) throws IOException {
        this.handler = handler;
        this.xmlPath = xmlPath;
        this.changeType = changeType;
        
        // Extract display info from path (fast, no parsing needed)
        extractDisplayInfoFromPath(xmlPath);
        
        // Only load from current model during construction (fast)
        // HEAD object is loaded lazily when getEObject(HEAD) is called
        if(changeType != DELETED) {
            objects[CURRENT] = loadFromCurrentModel();
        }
    }
    
    /**
     * Extract element type and ID from the file path.
     * Path format: folder/subfolder/ElementType_id-xxxx.xml
     * 
     * This allows display without loading models.
     */
    private void extractDisplayInfoFromPath(String path) {
        if(path == null) {
            return;
        }
        
        // Use regex to extract type and ID from filename
        Matcher matcher = ELEMENT_FILE_PATTERN.matcher(path);
        if(matcher.find()) {
            elementType = matcher.group(1);
            elementId = matcher.group(2);
            logDebug("Extracted from path: type=" + elementType + ", id=" + elementId); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
    
    /**
     * @return The XML path in the repository
     */
    public String getXMLPath() {
        return xmlPath;
    }
    
    /**
     * @return The change type (ADDED, DELETED, MODIFIED)
     */
    public int getChangeType() {
        return changeType;
    }
    
    /**
     * Get the EObject for display
     * 
     * PERFORMANCE: HEAD object is loaded lazily on first access.
     * This requires the HEAD model to be loaded first via handler.ensureHeadModelLoaded().
     * 
     * @param which CURRENT or HEAD
     * @return The EObject, or null if it doesn't exist for that version
     */
    public EObject getEObject(int which) {
        // Lazy load HEAD object on first access
        if(which == HEAD && !headObjectLoaded && changeType != ADDED) {
            headObjectLoaded = true;
            try {
                objects[HEAD] = loadFromHead();
            }
            catch(IOException e) {
                logDebug("Failed to load HEAD object: " + e.getMessage()); //$NON-NLS-1$
            }
        }
        return objects[which];
    }
    
    /**
     * Get the default EObject for display (current if exists, else HEAD)
     * 
     * For deleted items, this requires HEAD model to be loaded.
     * 
     * @return The default EObject
     */
    public EObject getDefaultEObject() {
        if(objects[CURRENT] != null) {
            return objects[CURRENT];
        }
        // For deleted items, load from HEAD
        return getEObject(HEAD);
    }
    
    /**
     * Get a human-readable status string
     * 
     * @return Status string
     */
    public String getStatus() {
        switch(changeType) {
            case ADDED:
                return Messages.ChangeInfo_0;
            case DELETED:
                return Messages.ChangeInfo_1;
            case MODIFIED:
                return Messages.ChangeInfo_2;
            default:
                return ""; //$NON-NLS-1$
        }
    }
    
    /**
     * Set the user's choice (KEEP or REVERT)
     * 
     * @param choice The choice
     */
    public void setUserChoice(int choice) {
        userChoice = choice;
    }
    
    /**
     * @return The user's choice (KEEP or REVERT)
     */
    public int getUserChoice() {
        return userChoice;
    }
    
    /**
     * @return true if user chose to revert this change
     */
    public boolean isRevert() {
        return userChoice == REVERT;
    }
    
    /**
     * Get the element type extracted from the file path.
     * Useful for display when EObject is not available (e.g., DELETED items before HEAD is loaded).
     * 
     * @return The element type (e.g., "BusinessProcess", "AssociationRelationship", "ArchimateDiagramModel")
     */
    public String getElementType() {
        return elementType;
    }
    
    /**
     * Get the element ID extracted from the file path.
     * 
     * @return The element ID (e.g., "id-abc123...")
     */
    public String getElementId() {
        return elementId;
    }

    /**
     * Load the EObject from the current in-memory model
     */
    private EObject loadFromCurrentModel() throws IOException {
        // Get ID from XML path
        String id = extractIdFromPath(xmlPath);
        if(id == null) {
            logDebug("loadFromCurrentModel: " + xmlPath + " - could not extract ID"); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
        
        // Find in current model
        IArchimateModel model = handler.getCurrentModel();
        EObject result = ArchimateModelUtils.getObjectByID(model, id);
        logDebug("loadFromCurrentModel: " + xmlPath + " - id=" + id + ", found=" + (result != null ? result.getClass().getSimpleName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        return result;
    }
    
    /**
     * Load the EObject from HEAD (last commit)
     * 
     * PERFORMANCE: We don't need to read the file from git or parse XML!
     * The ID is embedded in the filename, and we already have the full HEAD model loaded.
     * Just extract the ID from the path and look it up in the HEAD model.
     */
    private EObject loadFromHead() throws IOException {
        logDebug("loadFromHead: " + xmlPath); //$NON-NLS-1$
        
        // Extract ID directly from the filename - no need to parse XML!
        String id = extractIdFromPath(xmlPath);
        if(id == null) {
            logDebug("  could not extract ID from path"); //$NON-NLS-1$
            return null;
        }
        logDebug("  extracted ID: " + id); //$NON-NLS-1$
        
        // Look up in the already-loaded HEAD model
        IArchimateModel headModel = handler.getHeadModel();
        EObject result = ArchimateModelUtils.getObjectByID(headModel, id);
        logDebug("  found in HEAD model: " + (result != null ? result.getClass().getSimpleName() : "null")); //$NON-NLS-1$ //$NON-NLS-2$
        
        return result;
    }
    
    /**
     * Extract the element ID from the XML path
     * Path format: folder/subfolder/ElementType_id-xxxx.xml
     * 
     * @param path The XML path
     * @return The element ID, or null if not found
     */
    private String extractIdFromPath(String path) {
        if(path == null) {
            return null;
        }
        
        // Get filename
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String filename = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        
        // Remove .xml extension
        if(filename.endsWith(".xml")) { //$NON-NLS-1$
            filename = filename.substring(0, filename.length() - 4);
        }
        
        // Extract ID after underscore
        int underscorePos = filename.lastIndexOf('_');
        if(underscorePos > 0 && underscorePos < filename.length() - 1) {
            return filename.substring(underscorePos + 1);
        }
        
        return null;
    }
}
