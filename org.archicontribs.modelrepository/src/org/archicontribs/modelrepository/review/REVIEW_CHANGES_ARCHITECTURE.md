# Review Changes Feature Architecture

## Overview

The Review Changes feature allows users to interactively review uncommitted changes before committing, with the ability to selectively revert individual changes. This document describes the architecture, design decisions, and implementation details.

## Feature Summary

- **Purpose**: Review and selectively revert changes before committing
- **Entry Point**: Collaboration menu → "Review Changes" (or right-click popup menu)
- **Comparison**: Current model (working directory) vs. HEAD (last commit)
- **Actions**: Keep change (default) or Revert to HEAD version

## Architecture

### Package Structure

```
org.archicontribs.modelrepository.review/
├── ChangeInfo.java           # Data class for individual changes
├── ChangeReviewHandler.java  # Core logic: comparison, revert operations
├── ReviewChangesDialog.java  # Interactive UI dialog
├── Messages.java             # NLS message constants
└── messages.properties       # Localized strings
```

### Class Responsibilities

#### ChangeInfo
Data class representing a single change between HEAD and current model.

- **Change Types**: `ADDED`, `DELETED`, `MODIFIED`
- **User Choices**: `KEEP` (default), `REVERT`
- **Object References**: Holds both CURRENT and HEAD versions of the object
- **Lazy Loading**: Objects are loaded on-demand via `loadFromCurrentModel()` and `loadFromHead()`

#### ChangeReviewHandler
Core business logic for the review process.

- **Model Loading**: Extracts HEAD model to temp folder, imports via `GraficoModelImporter`
- **Change Detection**: Uses git status API (added, removed, changed, modified, untracked, missing)
- **Revert Operations**: Applies user-selected reverts to the in-memory model
- **Resource Management**: Cleans up temp folder after use

#### ReviewChangesDialog
Interactive UI based on Eclipse JFace dialogs.

- **Layout**: Similar to `ConflictsDialog` from merge package
- **Tabs**: Main (core properties), Properties (key-value pairs), View (diagram preview)
- **Side-by-Side**: Shows Current vs HEAD values for comparison
- **Table**: Lists all changes with Type, Name, Status, and Action columns
- **Editing**: ComboBox cell editor for Keep/Revert selection

### Action and Handler Classes

```
org.archicontribs.modelrepository.actions/
├── ReviewChangesAction.java   # Orchestrates the workflow
└── ReviewChangesHandler.java  # Eclipse command handler
```

## Design Decisions

### 1. Comparison Direction

- **LEFT panel**: HEAD (previous/committed version)
- **RIGHT panel**: CURRENT (working directory version)

This follows the git convention where HEAD is the "base" and current changes are shown relative to it.

### 2. EMF Copy vs Direct Reference

**Problem**: When restoring a deleted object, why create a copy instead of using the HEAD object directly?

**Answer**: EMF containment rules require that every `EObject` has exactly one container.

```java
// ❌ WRONG: Would remove headObject from HEAD model
targetFolder.getElements().add(headObject);

// ✅ CORRECT: Copy is independent, can be added to current model
EObject copy = EcoreUtil.copy(headObject);
targetFolder.getElements().add(copy);
```

If we added the HEAD object directly:
1. It would be silently removed from the HEAD model (EMF containment is exclusive)
2. The HEAD model would be corrupted
3. Subsequent operations might fail unexpectedly

### 3. ID Preservation After Copy

**Problem**: `EcoreUtil.copy()` generates new IDs for all copied objects.

**Solution**: After copying, we recursively restore original IDs:

```java
EObject copy = EcoreUtil.copy(headObject);
restoreOriginalIds(headObject, copy);  // Restore original IDs
```

**Why this matters**:
- Relationships reference elements by ID
- Diagram object references use element IDs
- Cascade restore depends on matching IDs
- Without original IDs, the restored element would be orphaned

### 4. In-Memory Revert Only

Reverts are applied to the **in-memory model only**. The user must:
1. Save the model (File → Save)
2. Export to GRAFICO (automatic on next commit)
3. Commit the changes

This gives the user control and allows them to undo if they made a mistake.

### 5. Lazy Loading for Performance

With potentially 30,000+ files in a large model, we use lazy loading:

```java
public EObject getEObject(int version) {
    if(version == CURRENT && fCurrentObject == null) {
        loadFromCurrentModel();  // Load on first access
    }
    // ...
}
```

This avoids loading all objects upfront when building the change list.

## Revert Operations

### Revert Addition (Remove new element)

```java
private void revertAddition(ChangeInfo info) {
    EObject current = info.getEObject(ChangeInfo.CURRENT);
    EcoreUtil.remove(current);  // Remove from parent container
}
```

### Revert Deletion (Restore deleted element)

```java
private void revertDeletion(ChangeInfo info) {
    EObject headObject = info.getEObject(ChangeInfo.HEAD);
    EObject copy = EcoreUtil.copy(headObject);
    restoreOriginalIds(headObject, copy);  // Preserve IDs!
    
    IFolder targetFolder = findTargetFolder(copy);
    targetFolder.getElements().add((IIdentifier)copy);
}
```

### Revert Modification (Restore original values)

```java
private void revertModification(ChangeInfo info) {
    EObject current = info.getEObject(ChangeInfo.CURRENT);
    EObject head = info.getEObject(ChangeInfo.HEAD);
    
    // Copy all features from HEAD to current
    for(EStructuralFeature feature : head.eClass().getEAllStructuralFeatures()) {
        if(feature.isChangeable() && !feature.isDerived()) {
            current.eSet(feature, head.eGet(feature));
        }
    }
}
```

## Change Detection

Changes are detected using git status after exporting the current model:

| Git Status | Change Type | Description |
|------------|-------------|-------------|
| Added | ADDED | New file in working directory |
| Untracked | ADDED | New file not yet tracked |
| Removed | DELETED | File deleted from working directory |
| Missing | DELETED | File missing (deleted outside git) |
| Changed | MODIFIED | Staged changes |
| Modified | MODIFIED | Unstaged changes |

## Future Enhancements (Iteration 2)

### Cascade Restore for Deleted Objects

When restoring a deleted element, also restore:
- Relationships that reference this element (source or target)
- Diagram object references to this element

**Implementation approach**:
1. Build dependency graph from HEAD model
2. When user selects "Revert" on deleted element, show confirmation dialog
3. List all dependent objects that will also be restored
4. Apply cascade restore

### Cascade Restore for Diagrams

When reverting a diagram to HEAD version:
- Identify elements referenced in HEAD diagram but missing from current model
- Offer to restore those elements as well

### Property-Level Revert

Instead of reverting entire elements, allow reverting individual properties:
- Show diff at property level
- Checkbox per property for selective revert

## Testing Considerations

### Unit Tests

- `ChangeInfo`: Test lazy loading, ID extraction from path
- `ChangeReviewHandler`: Test revert operations on mock model
- ID preservation after copy

### Integration Tests

- Full workflow: make changes → review → revert → verify model state
- Large model performance (30,000+ elements)
- Edge cases: deleted folder with contents, renamed elements

### Manual Testing

1. Create element → Review → Revert (should remove)
2. Delete element → Review → Revert (should restore with same ID)
3. Modify element → Review → Revert (should restore original values)
4. Verify relationships still work after restore
5. Verify diagram references after restore

## Related Files

- `plugin.xml`: Command and menu contributions
- `plugin.properties`: `command.name.12 = Review Changes`
- `actions/Messages.java`: Action message constants
- `actions/messages.properties`: Action localized strings

## Icon

Uses `img/revert.png` - consistent with the revert/restore concept.

## Debug Logging Pattern

### Why Not System.out.println?

In Eclipse RCP plugins, `System.out.println` goes to the console of the **host Eclipse instance**, not the runtime workbench. During normal usage (not running from Eclipse IDE), these messages are lost entirely.

### Eclipse Plugin Logging Pattern

Use `ModelRepositoryPlugin.getInstance().log()` which writes to:
- **Eclipse Error Log view**: Window → Show View → Error Log
- **Workspace log file**: `.metadata/.log` in the workspace folder

### Implementation Pattern

```java
import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.eclipse.core.runtime.IStatus;

public class MyClass {
    
    /**
     * Enable debug logging with JVM arg: -Dmy.feature.debug.logging=true
     * 
     * Logs appear in:
     * - Eclipse Error Log view (Window → Show View → Error Log)
     * - .metadata/.log file in your workspace
     */
    private static final boolean DEBUG_LOGGING = Boolean.getBoolean("my.feature.debug.logging"); //$NON-NLS-1$
    
    private static void logDebug(String message) {
        if (!DEBUG_LOGGING) return;  // Zero overhead when disabled
        ModelRepositoryPlugin.getInstance().log(IStatus.INFO, "[MyFeature] " + message, null); //$NON-NLS-1$
    }
    
    public void someMethod() {
        logDebug("Starting operation with param: " + param); //$NON-NLS-1$
        // ... operation ...
        logDebug("Operation completed successfully"); //$NON-NLS-1$
    }
}
```

### Enabling Debug Logging

Add JVM argument to your Eclipse launch configuration:

```
-Dreview.debug.logging=true
```

For the Review Changes feature specifically:
- **JVM arg**: `-Dreview.debug.logging=true`
- **Log prefix**: `[ReviewChanges]` or `[ChangeInfo]`

### Log Levels

- `IStatus.INFO` - Debug/informational messages
- `IStatus.WARNING` - Warnings that don't prevent operation
- `IStatus.ERROR` - Errors (include exception as third parameter)

```java
// Error with exception
ModelRepositoryPlugin.getInstance().log(IStatus.ERROR, "Operation failed", exception);
```

### Performance Considerations

1. **Boolean flag evaluated once at class load** - zero runtime overhead when disabled
2. **String concatenation only happens if logging is enabled** (due to early return)
3. **Don't log in tight loops** - aggregate data and log summaries instead

