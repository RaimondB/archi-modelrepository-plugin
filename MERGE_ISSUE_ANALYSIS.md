# Merge Issue Analysis - "Missing Target End" Errors

## Problem Statement

After merging `master` into `merge-shadow` branch on the model repository (`D:\Repos\archi-tool`), the loaded model shows:
- ~100 "Diagram Connection has missing target end" errors
- ~50 "Relationship has missing referenced source/target element" errors

## Scenario Topology

- **Model repo**: `D:\Repos\archi-tool` (READ ONLY during analysis)
- **HEAD on merge-shadow**: `fa6f7364f`
- **Merge-base** (between merge-shadow and master): `d74d1dab8`
- **merge-shadow changes from base**: 13 files (12 `folder.xml` timestamp-only changes + 1 diagram)
- **master changes from base**: 12,191 files (8,437 adds, ~16 deletes, many modifies)
- **Merge conflicts**: Exactly **12 `folder.xml` content conflicts** (both sides modified timestamps)
- **master legitimately deletes** from merge-base: 6 diagrams + 10 relationships

## Key Findings

### 1. GRAFICO Reference Format (CONFIRMED)

Diagram connections use **filename-only** hrefs, NOT full paths:
```xml
<archimateRelationship
    xsi:type="archimate:AssociationRelationship"
    href="AssociationRelationship_id-6d2758deeef14c9aa8355943a36f4af4.xml#id-6d2758deeef14c9aa8355943a36f4af4"/>
```

Diagram connections reference other diagram objects by their **internal `id` attribute** within the same diagram file:
```xml
<sourceConnections
    xsi:type="archimate:DiagramModelArchimateConnection"
    id="id-810b447da6b14068808c0c21b4a6ff35"
    source="id-739e1e1620724ea8b94595ecd9f8e3e5"
    target="id-54762d11716145e2bbc0742e8b4fabc5">
```

### 2. Example Connection Traced (COMPLETE)

Connection `id-810b447da6b14068808c0c21b4a6ff35` in diagram "Conceptual Data Model - Current":
- **Diagram path**: `model/diagrams/id-3fa71b42eb074ecf8d0f1a2b3deca6a4/id-e5628bdb4daa402abbe135a988637a6b/id-8f7eebf6284a4b2a97397e7dc6e4b602/ArchimateDiagramModel_id-7b1025c878e84846b4bbe5e55a99b827.xml`
- **source** (diagram object): `id-739e1e1620724ea8b94595ecd9f8e3e5` — ✅ EXISTS on both merge-shadow and master
- **target** (diagram object): `id-54762d11716145e2bbc0742e8b4fabc5` — ✅ EXISTS on both merge-shadow and master
- **archimateRelationship**: `AssociationRelationship_id-6d2758deeef14c9aa8355943a36f4af4`
  - ✅ EXISTS on both branches at `model/relations/...`
  - Source: `DataObject_id-9ed3c074d1184fffadacab75ec30cf62` — ✅ EXISTS on both branches
  - Target: `DataObject_id-16a31c745e5640beaf6ec1ac2b453fad` ("Stock Demand") — ✅ EXISTS on both branches
- **Diagram path**: Same on both branches (no folder move)
- **All parent folder.xml files**: Valid and present

### 3. DISPROVEN Hypotheses

- ❌ "Diagram refs include folder paths" — they use filename-only format
- ❌ "Missing elements deleted by master" — for the traced example, ALL referenced elements exist on BOTH branches
- ❌ "Folder moves cause path mismatches" — diagram is at the same path on both branches

### 4. Open Hypotheses (To Investigate)

1. **Pre-existing errors**: The model may already have these integrity errors BEFORE the merge. Test: load model from merge-shadow alone (no merge) and run validation.

2. **GRAFICO import resolution bug**: The `GraficoModelImporter.resolve()` method uses `fIDLookup` map. If an element's file is in an unexpected subfolder, it might not get indexed properly despite existing on disk.

3. **`restoreProblemObjects()` side effects**: `GraficoModelLoader.restoreProblemObjects()` searches git history for missing objects and brings them back. This could restore deleted diagrams that reference elements that no longer exist (those elements WERE deleted legitimately by master).

4. **Relationship file references its targets by filename** (e.g., `DataObject_id-xxx.xml#id-xxx`). If the target element was moved to a different folder on master, the resolution depends on finding the file anywhere in the tree. The GRAFICO importer's path traversal must find it.

5. **Conflict resolution corrupts folder.xml**: The 12 `folder.xml` conflicts are the only actual conflicts. If their resolution loses element entries, elements could become orphaned and unloadable.

### 5. What "Missing Target End" Likely Means

In Archi's validator, "Diagram Connection has missing target end" means one of:
- The connection's `target` attribute references a diagram object ID that doesn't exist in the diagram
- OR the connection's `archimateRelationship` references a relationship whose target **concept** doesn't exist in the model

For the traced example, ALL referenced objects exist on both branches. This strongly suggests either:
- The errors are **pre-existing** (model broken before merge)
- The GRAFICO **import/resolution** step fails to assemble the model correctly despite all files being present
- Something in the **merge conflict handler** (specifically `consolidateMoveGroups()` or folder.xml resolution) causes data loss

## Relevant Code Paths

| Component | File | Role |
|-----------|------|------|
| Merge orchestrator | `RepositoryService.mergeBranch()` | Full merge flow |
| Conflict handler | `MergeConflictHandler.java` (merge/ package) | Categorizes & resolves conflicts |
| GRAFICO loader | `GraficoModelLoader.java` | Loads model, calls `restoreProblemObjects()` |
| GRAFICO importer | `GraficoModelImporter.java` | `resolve()` resolves ID-based proxies via `fIDLookup` |
| Move detection | `MergeObjectInfo.resolveMovedObject()` | Handles element moves between folders |

## Next Steps (When Resuming)

1. **Reproduce with logging**: Add instrumentation to `GraficoModelImporter.resolve()` to see which IDs fail to resolve and why
2. **Check pre-existing errors**: Load model from merge-shadow alone (no merge) and validate — do the same errors appear?
3. **Check folder.xml conflict resolution**: After the 12 conflicts are resolved, verify no element entries are lost from folder.xml files
4. **Check `restoreProblemObjects()`**: Does it bring back deleted diagrams/relationships that then reference missing concepts?
5. **Trace a NEW element**: Some "missing target" IDs (like `id-02cbbf9fc6444a0aa4751eb1a688bd13`) are files that only exist on master (new additions). After merge they SHOULD exist. Check if they do.

## Commands for Quick Reference

```powershell
# Check if an element exists on a branch
cd D:\Repos\archi-tool
git ls-tree -r --name-only merge-shadow | Select-String "id-XXXX"
git ls-tree -r --name-only master | Select-String "id-XXXX"

# Read an element file
git show merge-shadow:"path/to/file.xml"

# Find merge-base
git merge-base merge-shadow master  # = d74d1dab8

# Check what master deleted from merge-base
git diff --name-status d74d1dab8 master -- "model/" | Select-String "^D"

# Check conflicts (during merge)
git diff --name-only --diff-filter=U
```

---

## Code Analysis: Merge Pipeline

### RepositoryService.mergeBranch() — Line 599

```java
public MergeBranchResult mergeBranch(IArchiRepository repo, BranchInfo currentBranch,
        BranchInfo branchToMerge, MergeHandler mergeHandler,
        IProgressMonitor monitor) throws IOException, GitAPIException
```

**Complete flow (in order):**

1. **Git Merge** (Line 619): `ArchiRepository.mergeBranch()` — performs actual git merge, returns `MergeResult`
2. **Early exit** (Line 624): If `ALREADY_UP_TO_DATE` → return
3. **Conflict Detection** (Line 627): If `CONFLICTING` → create `MergeConflictHandler`
4. **Handler init** (Line 636-637): `handler.init(monitor)` — loads OURS/THEIRS models, creates MergeObjectInfo per conflict, resolves moves, detects folder moves
5. **Conflict Dialog** (Line 645): `mergeHandler.resolveConflicts(handler, msg)` — shows UI, user makes choices
6. **Apply Resolutions** (Line 648): `handler.merge()` — categorizes choices, checkouts stages, consolidates move groups
7. **Cross-Path Deletion** (Line 666): For **clean merges only** (not conflicting!): `detectAndRemoveCrossPathDeletions()` — removes elements deleted on one side but modified on other
8. **Folder Repair** (Line 675): `GraficoModelLoader`:
   - `repairMissingFolderXml()` — detects folder moves, repairs folder.xml files
   - `applyFolderMoveResolutions()` — applies user's folder location choices
9. **Model Reload** (Line 683): `mergeHandler.reloadModel(loader, monitor)` — full GRAFICO re-import (includes restoreProblemObjects, repairConnectionEndpoints, removeOrphanedElements)
10. **Commit** (Line 688-707): If changes exist or MERGE_HEAD present, commits with detailed message

**CRITICAL NOTE**: `shouldRunCrossPathDeletion(CONFLICTING)` returns **false** → cross-path deletion is SKIPPED for conflicting merges. This means elements deleted by one side but present on the other are NOT automatically removed during conflicting merges.

---

### MergeConflictHandler.java — Conflict Resolution Orchestrator

#### init() — Line 131

```java
public void init(IProgressMonitor pm) throws IOException, GitAPIException
```

1. Validate MergeResult is not null
2. Load OURS model from current repository state
3. Extract THEIRS model from the branch being merged (git ref extraction)
4. Create `MergeContentCache` — bulk-loads all conflicting file contents from DirCache (efficient: 1 read vs N TreeWalks)
5. For each conflicting XML path → create `MergeObjectInfo` (holds EObjects from both sides, user's choice)
6. For each `MergeObjectInfo` → call `resolveMovedObject()` to detect element moves
7. Call `detectFolderMoves()` to group related conflicts

#### categorizeConflictChoices() — Line 579

Builds 4 lists from all `MergeObjectInfo` objects:
- **ours**: paths where user chose OURS and file exists
- **theirs**: paths where user chose THEIRS and file exists
- **deletions**: paths where user chose a deleted side
- **moveResolvedInfos**: paths with detected-as-move elements (special handling)

Logic: Skips move group items (handled separately). For resolved-as-move items → `moveResolvedInfos`. For regular items: based on `getUserChoice()` + whether that side has content or was deleted.

#### merge() — Line 529

```java
public void merge() throws IOException, GitAPIException
```

1. Call `categorizeConflictChoices()` → builds the 4 lists
2. `checkout(Stage.OURS, ours)` — git checkout --stage=ours for each path
3. `checkout(Stage.THEIRS, theirs)` — git checkout --stage=theirs for each path
4. `resolveToDeleted(deletions)` — git rm for deleted paths
5. `resolveMoveResolvedElements()` — special handling for moves
6. `consolidateMoveGroups()` — resolves folder move conflicts

#### consolidateMoveGroups() — Line 804

For each MoveGroup:
1. Determines chosen path (OURS or THEIRS location) from user's `locationChoice`
2. Resolves conflict markers at unchosen path (checkout correct stage)
3. Resolves conflict markers at chosen path (per-element user choices)
4. **Migrates files** from unchosen → chosen path:
   - Elements only at unchosen → moved to chosen
   - Elements at both → handled per user's choice
5. Deletes unchosen directory if empty
6. Stages all changes via `stageMoveGroupPaths()`

---

### MergeObjectInfo.java — Single Conflict Representation

Holds:
- `EObject[] objects` — [OURS, THEIRS] loaded objects
- `boolean[] rawContentExists` — [OURS, THEIRS] whether file content exists in DirCache
- `boolean resolvedAsMove` — true if element was moved between folders
- `String moveGroupId` — non-null if part of a folder move group
- User's choice (OURS or THEIRS)

#### resolveMovedObject() — Line 185

- **OURS exists, THEIRS null**: Search THEIRS model for element with same ID → if found, mark `resolvedAsMove = true`
- **THEIRS exists, OURS null**: Search OURS model for element with same ID → if found, mark `resolvedAsMove = true`
- **Both exist or both null**: Do nothing (not a move)

Key predicates:
- `isDeletedBy(side)`: Returns `!rawContentExists[side] && !resolvedAsMove` — true deletion
- `isPartOfMove()`: Returns `moveGroupId != null`
- `isResolvedAsMove()`: Returns `resolvedAsMove`

---

### GraficoModelImporter.java — GRAFICO to EMF Model

#### fIDLookup — `ConcurrentHashMap<String, IIdentifier>`

- **Populated during import**: As each XML file is parsed → `fIDLookup.put(eObject.getId(), eObject)`
- **Used for proxy resolution**: After all files loaded → replaces proxy refs with real objects

#### resolve() — Line 1175

```java
private EObject resolve(IIdentifier object, IIdentifier parent)
```

1. Check if `object.eIsProxy()`
2. Extract object ID from URI fragment
3. Lookup: `newObject = fIDLookup.get(objectID)`
4. **If not found**: Add to `fUnresolvedObjects` list, log diagnostic
5. Return found object or keep proxy

#### resolveProxies flow (line 1127):

1. Iterate all model contents
2. For `IArchimateRelationship` → resolve source and target concept proxies
3. For `IDiagramModelArchimateObject` → resolve archimateElement proxy
4. For `IDiagramModelArchimateConnection` → resolve archimateRelationship proxy
5. For `IDiagramModelReference` → resolve referencedModel proxy

---

### GraficoModelLoader.java — Post-Merge Model Loading

#### restoreProblemObjects() — Line 603

```java
private IArchimateModel restoreProblemObjects(List<UnresolvedObject> unresolvedObjects) throws IOException
```

1. For each unresolved object:
   - Extract filename from URI
   - Walk git commits from HEAD backward
   - Use `TreeWalk` to find file in each commit's tree
   - When found: extract blob → write to working directory
2. Re-import the model (full `GraficoModelImporter.importAsModel()`)
3. Return refreshed model with restored objects

**When called**: After initial GRAFICO import reports unresolved proxies. Attempts to restore missing files from git history.

**⚠️ POTENTIAL ISSUE**: If an element was LEGITIMATELY deleted (by master), this will RESTORE it from history. The restored element may reference other elements that no longer exist, creating cascading orphan errors.

#### repairConnectionEndpoints() — Line 696

```java
int repairConnectionEndpoints(IArchimateModel model)
```

1. Collect all diagram connections
2. For each connection:
   - Get underlying `IArchimateRelationship`
   - Check if connection's source/target diagram objects represent same concepts as relationship's source/target
3. If mismatch: search diagram for correct diagram object → rewire via `connection.connect()`
4. Returns count of repaired connections

#### removeOrphanedElements() — Line 808

```java
int removeOrphanedElements(IArchimateModel model)
```

**Three-pass cleanup:**
1. **Orphaned Relationships**: source or target concept not in model → `EcoreUtil.delete()`
2. **Orphaned Diagram Connections**: underlying relationship null/orphaned, or relationship's source/target orphaned → disconnect + delete
3. **Orphaned Diagram Elements**: underlying ArchiMate element orphaned → disconnect connections + delete

---

### Critical Interaction: restoreProblemObjects + removeOrphanedElements

The model load pipeline is:
```
importAsModel() → resolveProxies() → fUnresolvedObjects populated
  → restoreProblemObjects() → restores files from git history → re-imports
    → repairConnectionEndpoints() → fixes diagram wiring
      → removeOrphanedElements() → 3-pass cleanup of broken refs
```

**The "missing target end" errors likely occur AFTER this pipeline completes**, meaning:
- Either `removeOrphanedElements()` is NOT being called
- Or the errors are in objects that pass validation but have semantic issues
- Or the errors are reported by Archi's OWN validator (separate from GRAFICO loader)

### Key Suspicion: restoreProblemObjects() Resurrection

If `restoreProblemObjects()` finds a relationship in git history that was legitimately deleted by master, it will:
1. Restore the relationship XML file to the working directory
2. Re-import the model with the restored file
3. The relationship's source/target elements may still not exist (if also deleted)
4. `removeOrphanedElements()` SHOULD catch this... unless the target element ALSO gets restored
5. But the diagram connection pointing to the restored relationship's target may not have its diagram object restored

This creates a scenario where:
- Relationship exists (restored from history)
- Relationship's target element exists (also restored)
- But diagram connection's `target` attribute references a diagram object ID that was in a DELETED diagram
- Result: "Diagram Connection has missing target end"
