/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.merge;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.archicontribs.modelrepository.grafico.GraficoResourceLoader;
import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.util.ArchimateModelUtils;

/**
 * Information about a merge conflict object
 * 
 * @author Phillip Beauvoir
 */
class MergeObjectInfo {

    private MergeConflictHandler handler;

    private String xmlPath;
    
    // Referenced EObjects - ours and theirs
    private EObject[] objects = new EObject[2];
    
    public static int OURS = 0;
    public static int THEIRS = 1;
    
    // User's choice
    private int userChoice = OURS;
    
    /**
     * The folder path portion of the xmlPath, e.g. "model/business/shared"
     * for "model/business/shared/BusinessActor_id-abc.xml".
     * Used to show location context in the conflicts dialog.
     */
    private String folderPath;
    
    /**
     * If this conflict is part of a detected folder move, this ID links it
     * to all other conflicts in the same move group. When the user resolves
     * one item, all items sharing the same moveGroupId are auto-resolved.
     * null if this conflict is not part of a move.
     */
    private String moveGroupId;
    
    /**
     * Human-readable description of the detected move, e.g.
     * "Moved: business/shared → technology/shared"
     * null if not part of a move.
     */
    private String moveDescription;
    
    /**
     * Cached human-readable breadcrumb location, e.g. "Application / Shared / Subfolder".
     * Computed lazily from the EObject's containment hierarchy.
     */
    private String displayLocation;
    
    /**
     * True if resolveMovedObject() successfully resolved a null side,
     * meaning this element was moved (not deleted) and both sides are now populated.
     */
    private boolean resolvedAsMove;

    MergeObjectInfo(String xmlPath, MergeConflictHandler handler) throws IOException {
        this.handler = handler;
        this.xmlPath = xmlPath;
        
        // Extract folder path from xml path
        int lastSlash = xmlPath.lastIndexOf('/');
        this.folderPath = lastSlash > 0 ? xmlPath.substring(0, lastSlash) : ""; //$NON-NLS-1$
        
        objects[OURS] = loadEObject(handler.getLocalRef());
        objects[THEIRS] = loadEObject(handler.getTheirRef());
    }
    
    String getXMLPath() {
        return xmlPath;
    }
    
    /**
     * @return The folder path portion of the XML path (no filename)
     */
    String getFolderPath() {
        return folderPath;
    }
    
    /**
     * @return A human-readable breadcrumb location, e.g. "Application / Shared / Subfolder".
     * Computed from the EObject's containment hierarchy. Falls back to the raw folder path.
     */
    String getDisplayLocation() {
        if(displayLocation == null) {
            displayLocation = handler.buildBreadcrumbForElement(objects[OURS], objects[THEIRS]);
            if(displayLocation.isEmpty()) {
                // Fallback: strip "model/" prefix from raw path
                displayLocation = folderPath;
                if(displayLocation.startsWith("model/")) { //$NON-NLS-1$
                    displayLocation = displayLocation.substring(6);
                }
            }
        }
        return displayLocation;
    }
    
    /**
     * @return true if this conflict is a folder.xml file
     */
    boolean isFolderXml() {
        return xmlPath.endsWith("/folder.xml"); //$NON-NLS-1$
    }
    
    /**
     * @return The move group ID, or null if not part of a move
     */
    String getMoveGroupId() {
        return moveGroupId;
    }
    
    void setMoveGroupId(String id) {
        this.moveGroupId = id;
    }
    
    String getMoveDescription() {
        return moveDescription;
    }
    
    void setMoveDescription(String desc) {
        this.moveDescription = desc;
    }
    
    /**
     * @return true if this conflict is part of a detected folder move
     */
    boolean isPartOfMove() {
        return moveGroupId != null;
    }
    
    /**
     * For items where one side is null (git sees it as deleted), try to resolve
     * the element by ID in the other model. This handles moves: Git sees a move
     * as delete + create, so the file at the new path shows "Deleted by us"
     * even though the element exists in our model at a different location.
     * After resolving, both sides are populated and status becomes "Moved".
     */
    void resolveMovedObject() {
        if(objects[OURS] != null && objects[THEIRS] == null) {
            String id = ((IIdentifier)objects[OURS]).getId();
            EObject resolved = ArchimateModelUtils.getObjectByID(handler.getTheirModel(), id);
            if(resolved != null) {
                objects[THEIRS] = resolved;
                resolvedAsMove = true;
            }
        }
        else if(objects[THEIRS] != null && objects[OURS] == null) {
            String id = ((IIdentifier)objects[THEIRS]).getId();
            EObject resolved = ArchimateModelUtils.getObjectByID(handler.getOurModel(), id);
            if(resolved != null) {
                objects[OURS] = resolved;
                resolvedAsMove = true;
            }
        }
    }
    
    /**
     * Get the human-readable location (breadcrumb) for an element on the
     * specified side (OURS or THEIRS), e.g. "Application / Shared / Subfolder".
     * Returns empty string if the EObject is null or has no containment.
     */
    String getLocation(int choice) {
        EObject eObject = objects[choice];
        if(eObject == null) {
            return ""; //$NON-NLS-1$
        }
        return MergeConflictHandler.buildBreadcrumb(eObject);
    }
    
    EObject getEObject(int choice) {
        return objects[choice];
    }
    
    // Default is ours, or theirs if ours is null
    EObject getDefaultEObject() {
        return objects[OURS] != null ? objects[OURS] : objects[THEIRS];
    }
    
    String getStatus() {
        String baseStatus;
        if(objects[OURS] == null) {
            baseStatus = Messages.MergeObjectInfo_0;
        }
        else if(objects[THEIRS] == null) {
            baseStatus = Messages.MergeObjectInfo_1;
        }
        else if(resolvedAsMove) {
            baseStatus = Messages.MergeObjectInfo_3;
        }
        else {
            baseStatus = Messages.MergeObjectInfo_2;
        }
        
        if(moveDescription != null) {
            return baseStatus + " (" + moveDescription + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return baseStatus;
    }
    
    void setUserChoice(int choice) {
        userChoice = choice;
    }
    
    int getUserChoice() {
        return userChoice;
    }

    /**
     * Load the EObject from the ours or theirs XML file so we can get its ID
     * Once we have its ID we can load the real EObject from either "theirs" or "ours" full model.
     * We do this because some EObjects have proxy references to other EObjects that would need resolving
     * Returns null if the file contents does not exist (either we or they deleted the object)
     * ref is either ours or theirs
     */
    private EObject loadEObject(String ref) throws IOException {
        // Load the contents of the ref not the actual file because "theirs" is not an actual file
        byte[] contents = handler.getArchiRepository().getFileContents(xmlPath, ref);
        // Not found so was deleted by us or them
        if(contents == null) {
            return null;
        }
        
        ByteArrayInputStream bis = new ByteArrayInputStream(contents);
        
        IIdentifier eObject = GraficoResourceLoader.loadEObject(bis);
        
        // Get the ID
        String id = eObject.getId();
        
        // Now get the full object from the appropriate model
        IArchimateModel model = null;
        
        if(ref == handler.getLocalRef()) { // Ours
            model = handler.getOurModel();
        }
        else {
            model = handler.getTheirModel();
        }

        return ArchimateModelUtils.getObjectByID(model, id);
    }
}
