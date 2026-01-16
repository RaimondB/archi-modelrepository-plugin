/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.review;

import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IIdentifier;

/**
 * Information about a missing concept that a diagram references.
 * 
 * When reverting a diagram, it may reference elements or relationships that
 * were deleted in the current model. This class tracks those dependencies
 * and the user's resolution choice.
 * 
 * @author Raimond Brookman
 */
public class DiagramDependencyInfo {
    
    /**
     * Resolution choices for missing dependencies
     */
    public static final int RESTORE_CONCEPT = 0;  // Restore the element/relationship from HEAD
    public static final int REMOVE_FROM_DIAGRAM = 1;  // Remove the visual object from the diagram
    
    private final String conceptId;
    private final String conceptName;
    private final String conceptType;
    private final boolean isElement;  // true for element, false for relationship
    private final EObject headConcept;  // The concept from HEAD model
    private final int visualObjectCount;  // How many visual objects reference this concept
    private ChangeInfo parentChangeInfo;  // The diagram ChangeInfo that owns this dependency
    
    private int userChoice = RESTORE_CONCEPT;  // Default to restoring
    
    /**
     * Constructor for element dependency
     */
    public DiagramDependencyInfo(IArchimateElement headElement, int visualObjectCount, ChangeInfo parent) {
        this.conceptId = headElement.getId();
        this.conceptName = headElement.getName();
        this.conceptType = headElement.eClass().getName();
        this.isElement = true;
        this.headConcept = headElement;
        this.visualObjectCount = visualObjectCount;
        this.parentChangeInfo = parent;
    }
    
    /**
     * Constructor for relationship dependency
     */
    public DiagramDependencyInfo(IArchimateRelationship headRelationship, int visualObjectCount, ChangeInfo parent) {
        this.conceptId = headRelationship.getId();
        this.conceptName = headRelationship.getName();
        this.conceptType = headRelationship.eClass().getName();
        this.isElement = false;
        this.headConcept = headRelationship;
        this.visualObjectCount = visualObjectCount;
        this.parentChangeInfo = parent;
    }
    
    /**
     * @return The parent ChangeInfo (the diagram)
     */
    public ChangeInfo getParent() {
        return parentChangeInfo;
    }
    
    /**
     * @return The concept ID
     */
    public String getConceptId() {
        return conceptId;
    }
    
    /**
     * @return The concept name (may be empty for relationships)
     */
    public String getConceptName() {
        return conceptName != null ? conceptName : ""; //$NON-NLS-1$
    }
    
    /**
     * @return The concept type (e.g., "BusinessActor", "AssociationRelationship")
     */
    public String getConceptType() {
        return conceptType;
    }
    
    /**
     * @return true if this is an element, false if it's a relationship
     */
    public boolean isElement() {
        return isElement;
    }
    
    /**
     * @return The concept from HEAD model
     */
    public EObject getHeadConcept() {
        return headConcept;
    }
    
    /**
     * @return How many visual objects in the diagram reference this concept
     */
    public int getVisualObjectCount() {
        return visualObjectCount;
    }
    
    /**
     * Set the user's resolution choice
     */
    public void setUserChoice(int choice) {
        this.userChoice = choice;
    }
    
    /**
     * @return The user's resolution choice
     */
    public int getUserChoice() {
        return userChoice;
    }
    
    /**
     * @return true if user chose to restore the concept
     */
    public boolean shouldRestore() {
        return userChoice == RESTORE_CONCEPT;
    }
    
    /**
     * @return true if user chose to remove from diagram
     */
    public boolean shouldRemoveFromDiagram() {
        return userChoice == REMOVE_FROM_DIAGRAM;
    }
    
    /**
     * @return A display string for the dependency
     */
    public String getDisplayText() {
        StringBuilder sb = new StringBuilder();
        sb.append(conceptType);
        if (conceptName != null && !conceptName.isEmpty()) {
            sb.append(": ").append(conceptName); //$NON-NLS-1$
        }
        sb.append(" (").append(visualObjectCount).append(" reference"); //$NON-NLS-1$ //$NON-NLS-2$
        if (visualObjectCount != 1) {
            sb.append("s"); //$NON-NLS-1$
        }
        sb.append(")"); //$NON-NLS-1$
        return sb.toString();
    }
    
    /**
     * Get a description of what action will be taken
     */
    public String getActionDescription() {
        if (shouldRestore()) {
            return isElement ? 
                Messages.DiagramDependencyInfo_0 :  // "Restore element"
                Messages.DiagramDependencyInfo_1;   // "Restore relationship"
        } else {
            return Messages.DiagramDependencyInfo_2;  // "Remove from diagram"
        }
    }
}
