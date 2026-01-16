# Review Changes Feature Requirements

## Overview

The Review Changes feature allows users to selectively revert changes before committing to the repository. It shows a comparison between the current model state and the HEAD (last committed) state, allowing users to choose which changes to keep and which to revert.

## Core Concepts

### Change Types
- **ADDED**: Object exists in current model but not in HEAD (new object)
- **DELETED**: Object exists in HEAD but not in current model (removed object)
- **MODIFIED**: Object exists in both but has different properties

### User Choices for ChangeInfo (Elements, Relationships, Diagrams)
- **Keep**: Keep the current state (do not revert)
- **Revert**: Restore to HEAD state

### User Choices for DiagramDependencyInfo (Missing concepts in diagrams)
- **Restore concept**: Restore the element/relationship from HEAD model AND keep it in diagram
- **Remove from diagram**: Remove the visual representation from the diagram (don't restore the concept)

## Key Insights & Lessons Learned

### Insight 1: Diagrams Have Two Levels of Representation

An ArchiMate concept (element or relationship) has:
1. **Model Level**: The IArchimateElement/IArchimateRelationship stored in a folder
2. **Diagram Level**: Visual objects (IDiagramModelArchimateObject/Connection) that REFERENCE the model-level concept

When reverting a diagram, both levels must be consistent:
- If concept is restored to model, diagram can reference it
- If concept is NOT restored (stays deleted), diagram visual objects must be removed

### Insight 2: Diagram Connections Have Complex References

An `IDiagramModelArchimateConnection` (diagram connection) has:
1. `archimateRelationship` - The model-level relationship it represents
2. `source` - The visual object (IDiagramModelObject) where the arrow starts
3. `target` - The visual object (IDiagramModelObject) where the arrow ends

**Archi's validation rule**: 
```
connection.source.archimateElement == connection.archimateRelationship.source
connection.target.archimateElement == connection.archimateRelationship.target
```

If these don't match, Archi shows: "Diagram connection relationship has wrong source/target end component"

### Insight 3: MODIFIED Diagrams Need Special Handling

Unlike elements/relationships, diagrams CANNOT be reverted by simply copying properties:

```java
// ❌ WRONG: Property copy gives references to HEAD model objects
for (EStructuralFeature feature : head.eClass().getEAllStructuralFeatures()) {
    current.eSet(feature, head.eGet(feature));  // Children point to HEAD!
}

// ✅ CORRECT: Deep copy children, restore IDs, resolve references
List<IDiagramModelObject> copies = headDiagram.getChildren().stream()
    .map(child -> EcoreUtil.copy(child))
    .collect(toList());
// Then: restore IDs, remove unwanted objects, resolve references
```

### Insight 4: Order of Operations Matters

When reverting diagrams with dependencies:

```
1. Pre-restore concepts (elements first, then relationships)
   - Elements must exist before relationships can reference them
   
2. Copy diagram children from HEAD

3. Restore original IDs on copied children

4. Remove visual objects for REMOVE_FROM_DIAGRAM dependencies
   - Must also remove connections whose source/target is removed
   - Remove connections BEFORE removing their source objects
   
5. Resolve cross-model references
   - diagram objects → current model elements
   - connections → current model relationships
   - **CRITICAL**: If a relationship is not found, the connection must be REMOVED
   
6. Remove any connections that couldn't be resolved
   - These reference relationships that don't exist in the current model
   - If left in place, validation will fail with "wrong source/target end component"
   
7. Add children to current diagram
```

### Insight 6: Unresolved Connections Must Be Removed

When resolving diagram connections to the current model, if the `archimateRelationship` is not found:
- The connection must be **removed from the diagram**
- If left with a reference to the HEAD model's relationship, validation will fail

**Error pattern:**
```
Diagram connection relationship has wrong target end component in '<diagram>' (id-xxx)
```

**Root cause:** Connection's visual target references current model element, but
connection's archimateRelationship still references HEAD model's relationship,
which has a different target object.

**Fix:** Track unresolved connections during reference resolution and remove them.

### Insight 5: Connections Are Contained by Their Source

In the EMF model:
- A connection's container is `source.sourceConnections` (containment)
- A connection also appears in `target.targetConnections` (non-containment reference)

When removing a connection:
1. Remove from `source.sourceConnections` (removes from model)
2. Also remove from `target.targetConnections` (clears dangling reference)

When removing a visual object:
1. First remove all its connections (they're contained by it)
2. Then remove the visual object itself

## Consistency Rules

### Rule 1: Model Deletion Forces Diagram Removal
When a user chooses **Keep** for a deleted element/relationship at the model level:
- Any diagram dependencies that reference this concept must be set to **Remove from diagram**
- Reason: The concept cannot be restored in a diagram if it doesn't exist in the model

### Rule 2: Diagram Restore Forces Model Restore
When a user chooses **Restore concept** for a diagram dependency:
- The corresponding ChangeInfo at the model level must be set to **Revert**
- Reason: A concept cannot appear in a diagram unless it exists in the model

### Rule 3: Model Revert Enables Diagram Restore
When a user chooses **Revert** for a deleted element/relationship at the model level:
- Diagram dependencies referencing this concept can now choose either option
- Default should be **Restore concept** since the concept will exist in the model

### Rule 4: Element Removal Cascades to Relationships
When a user chooses **Remove from diagram** for an element:
- All relationships in that diagram that connect to this element must also be set to **Remove from diagram**
- Reason: A relationship cannot exist in a diagram without both its source and target

### Rule 5: Relationship Restore Requires Endpoint Restore  
When a user chooses **Restore concept** for a relationship:
- If source or target element is set to **Remove from diagram**, change it to **Restore concept**
- Reason: A relationship needs both endpoints to exist

### Rule 6: Visual Connection Removal Follows Source/Target
When removing a visual object (element) from a diagram:
- All connections that have this object as source OR target must also be removed
- This is a structural requirement, not a user choice
- Reason: Orphaned connections cause validation errors

## UI Behavior Requirements

### Immediate UI Refresh (CRITICAL)
- **TreeViewer.refresh()** must be called after ANY change to choices
- This includes:
  - User changes a dropdown
  - Cascade changes other items
  - User expands/collapses tree nodes
- **asyncExec()** should NOT be used for refresh - it delays the update

### Hierarchical Tree Display
- Top-level items are ChangeInfo objects (elements, relationships, diagrams)
- Child items under diagrams are DiagramDependencyInfo objects (only shown when diagram is set to Revert)
- Dependencies are only analyzed when a diagram is first expanded

### Dropdown Text Consistency
- Dropdown must show current selection, not a label
- For ChangeInfo: Show "Keep" or "Revert"
- For DiagramDependencyInfo: Show "Restore concept" or "Remove from diagram"
- Text should update immediately when cascade changes the value

## Implementation Order

### Phase 1: Pre-Restore Concepts (preRestoreDiagramDependencies)
For each diagram being reverted (DELETED or MODIFIED):
1. Collect dependencies marked as RESTORE_CONCEPT
2. Separate into elements and relationships
3. Restore elements first (with folder lookup, cache update)
4. Restore relationships second (with source/target resolution)

### Phase 2: Apply Diagram Revert
For DELETED diagrams (revertDeletion):
1. Copy entire diagram from HEAD
2. Restore IDs
3. Remove visual objects for REMOVE_FROM_DIAGRAM
4. Resolve references
5. Add to folder

For MODIFIED diagrams (revertDiagramModification):
1. Copy scalar properties (name, doc, viewpoint)
2. Clear children
3. Copy children from HEAD
4. Restore IDs
5. Remove visual objects for REMOVE_FROM_DIAGRAM
6. Resolve references  
7. Add children

### Phase 3: Reference Resolution (resolveCrossModelReferences)
For each visual object:
- `IDiagramModelArchimateObject.archimateElement` → current model element
- `IDiagramModelArchimateConnection.archimateRelationship` → current model relationship

For each relationship:
- `IArchimateRelationship.source` → current model element
- `IArchimateRelationship.target` → current model element

## Data Structures

### ChangeInfo
- Represents a single changed object (element, relationship, or diagram)
- Tracks the XML path, change type, and user choice
- For diagrams, contains a list of DiagramDependencyInfo
- `isDiagram()` - true if HEAD object is IDiagramModel

### DiagramDependencyInfo
- Represents a missing concept referenced by a diagram
- Contains: conceptId, conceptType, conceptName, referenceCount
- `isElement()` - true for elements, false for relationships
- `getHeadConcept()` - the EObject from HEAD model
- `getParent()` - the ChangeInfo this dependency belongs to

### ID Caches
- `fHeadModelIdCache`: Map<String, IIdentifier> for HEAD model lookups
- `fCurrentModelIdCache`: Map<String, IIdentifier> for current model lookups  
- Both caches built at initialization for fast O(1) lookups
- Cache updated when concepts are restored (`addToCurrentModelCache`)

## Error Messages & Causes

### "Diagram connection relationship has wrong source/target end component"
**Cause**: The connection's source/target visual object references a different element than the relationship's source/target.
**Fix**: Ensure reference resolution is correct and happens after concept restoration.

### "Wrong number of diagram component instances"
**Cause**: An element has a different number of visual objects than expected.
**Fix**: Ensure visual objects are properly added/removed, not duplicated.

### General diagram validation errors
**Cause**: Usually orphaned connections or missing elements.
**Fix**: Remove connections before their source objects; ensure proper cleanup.

## Debug Logging

Enable with JVM arg: `-Dreview.debug.logging=true`

Key log points:
- `preRestoreDiagramDependencies: N concepts to restore`
- `restoreConceptFromDependency: restoring Type ID`
- `revertDiagramModification: DiagramName`
- `removeVisualObjectsFromList: removing concepts [ids]`
- `resolved diagram object element: ElementName`
- `resolved diagram connection relationship: RelName`
