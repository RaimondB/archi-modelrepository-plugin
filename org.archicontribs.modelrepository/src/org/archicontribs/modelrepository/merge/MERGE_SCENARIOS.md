# Merge Scenarios: Move + Modify + Add + Delete

This document defines the test scenarios for GRAFICO merge operations involving folder/element moves, modifications, additions, and deletions across branches. It is the persistent reference for TDD implementation.

## Design Principles

1. **Content vs Location are separate concerns**: Mine/Theirs on an element = content choice only. Folder Mine/Theirs = location choice for ALL child elements.
2. **One unified dialog**: No separate folder move dialog. Folder rows + element rows in a single conflict resolution view.
3. **Uniform statuses**: Both folders and elements can be Modified, Moved, Moved + Modified, Deleted by us, Deleted by them.
4. **No [MERGE FIX] folders**: Unique elements at old location follow the chosen location.
5. **Engine/presentation split**: All merge logic in `MergeConflictHandler` (headless engine). Dialog is presentation-only. Enables future CLI merge command for CI pipelines.
6. **Real git in tests**: All tests use real JGit repos — real branches, real merges, real disk I/O. No mocking. No dialog code.
7. **Git move at commit**: Future optimization; cannot be assumed due to mixed plugin versions.

## Primary Bug

Branch A moves 10 elements from folderX → folderY. Branch B modifies 2 (one property-add, one rename) at folderX. Merge A→B. User chooses A's location (folderY) but B's content for only 1 of the 2 modified elements.

**Expected**: 9 at folderY with A's content + 1 with B's chosen content + 1 with A's content (not overridden).  
**Actual**: Both modified elements get A's content — per-element content choice is ignored.

**Root cause**: `consolidateMoveGroups()` step 4 only overwrites files in `unchosenConflictElements` (git conflicts at old path). Auto-merged copies at the chosen path (new location) are never overwritten with the user's content choice.

---

## Category A: Element Move to Different Folder (different folder ID) + Modify

These go through `cleanupAutoMergedDuplicates()`, NOT MoveGroup.

| ID | Scenario | User Choice | Expected | Status |
|----|----------|-------------|----------|--------|
| A1 | 1 elem moved + renamed | OURS (old loc + B content) | Old loc, B's content; auto-merged copy deleted | COVERED |
| A2 | 1 elem moved + renamed | THEIRS loc + OURS content | New loc, B's content | COVERED |
| A3 | 1 elem moved + renamed | THEIRS entirely | New loc, A's content | COVERED |
| **A4** | **10 elems moved, 2 modified (prop-add + rename)** | **A's loc, B's content for 1 of 2** | **Per-element content honored** | **NEW (BUG)** |
| **A5** | Elem moved + new elem added at old loc | A's loc for moved elem | Moved at new loc; new stays at old | **NEW** |
| **A6** | Both branches move same elem to diff folders | User picks one | Elem at chosen; other deleted | **NEW** |

## Category B: Folder Move (same folder ID, different path) + Modify

These go through MoveGroup + `consolidateMoveGroups()`.

| ID | Scenario | User Choice | Expected | Status |
|----|----------|-------------|----------|--------|
| B1 | Folder move+rename + 1 elem rename | New loc + OURS content | Elem at new loc with B's content | COVERED |
| **B2** | **Folder (10 elems) moved, 2 modified** | **New loc, mixed content** | **Per-element content honored** | **NEW (BUG)** |
| **B3** | Folder moved + new elem added at old loc | New location | New elem follows to chosen loc | **NEW** |
| **B4** | Folder moved + elem deleted by B | New location | Deleted elem absent | **NEW** |
| **B5** | Nested folder move + deep elem modify | New loc + OURS content | Nested move, content preserved | **NEW** |
| **B6** | Both branches move same folder to diff locs | User picks one | Chosen loc kept, other cleaned up | **NEW** |

## Category C: GraficoModelLoader Repair Path (no git conflicts)

Runs after `handler.merge()` for every merge.

| ID | Scenario | Expected | Status |
|----|----------|----------|--------|
| **C1** | A moves folder, B no conflicts → MERGED | Repair detects orphan, elements at new loc | **NEW** |
| **C2** | Same + B added unique elem at old loc | Unique elem follows chosen loc (no MERGE FIX) | **NEW** |

## Category D: Interaction Between Systems

| ID | Scenario | Expected | Status |
|----|----------|----------|--------|
| **D1** | Conflicts AND orphaned dirs | Handler first, repair rest; no data loss | **NEW** |

## Category E: Add and Delete Scenarios

Key distinction: **delete** (ID gone) vs **move** (same ID at different path).

### E1–E4: Element-level add/delete

| ID | Scenario | Expected | Status |
|----|----------|----------|--------|
| **E1** | A adds new elem; B modifies folder metadata | Both present | **NEW** |
| **E2** | A deletes elem; B modifies same elem | Conflict: keep or discard | **NEW** |
| **E3** | A deletes elem; B deletes same elem | No conflict; gone | **NEW** |
| **E4** | A adds new elem; B adds different new elem | Both present | **NEW** |

### E5–E8: Folder-level add/delete

| ID | Scenario | Expected | Status |
|----|----------|----------|--------|
| **E5** | A deletes folder (not moved); B adds new elem in folder | Folder restored at original loc (repair) | **NEW** |
| **E6** | A deletes folder; B modifies existing elem | Conflict: keep folder or accept delete | **NEW** |
| **E7** | A adds new subfolder with elems; B no conflicts | Merged cleanly | **NEW** |
| **E8** | A deletes folder; B deletes same folder | No conflict; gone | **NEW** |

### E9–E10: Folder move + add interactions

| ID | Scenario | Expected | Status |
|----|----------|----------|--------|
| **E9** | A moves folder; B adds new elem at OLD loc | Elem follows chosen location | **NEW** |
| **E10** | A moves folder; B adds new subfolder at old loc | Subfolder follows chosen location | **NEW** |

**Note**: E5 is distinct from E9 — E5 is a true delete (ID gone), E9 is a move (ID at new path). Repair must distinguish: if folder ID exists elsewhere → move; if not → delete requiring restore.

---

## UI Requirements

### Unified dialog (replaces two-tab + separate dialog)

| Row Type | Status | Mine/Theirs Meaning | Target Location |
|----------|--------|---------------------|-----------------|
| Folder (moved) | Moved | Location choice | Old / New |
| Folder (moved + modified) | Moved + Modified | Location + metadata content | Old / New |
| Folder (modified) | Modified | Metadata content | Static |
| Element (moved + modified) | Moved + Modified | Content only (location from folder) | From folder choice |
| Element (modified) | Modified | Content | Static |
| Element (moved, no change) | Moved (auto-resolved) | None | From folder choice |
| Element (deleted) | Deleted by us/them | Keep or discard | — |

Target Location column updates dynamically when folder choice changes.

### Engine/Presentation split

- `MergeConflictHandler.init()` → detection
- User choices on MoveGroup/MergeObjectInfo objects
- `MergeConflictHandler.merge()` → execution
- Dialog reads/writes state only — no merge logic
- CLI can call engine directly

---

## Implementation Phases

| Phase | What |
|-------|------|
| 0 | This document |
| 1 | RED tests: A4, B2 (primary bug) |
| 2 | RED tests: A5, A6, B3–B6, C1, C2, D1, E1–E10 |
| 3 | Fix engine: A4/B2 GREEN |
| 4 | Fix remaining: all GREEN |
| 5 | UI consolidation |
