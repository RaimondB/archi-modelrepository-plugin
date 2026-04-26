# Architecture Review — coArchi Plugin

> **Date**: 2026-04-26  
> **Scope**: Full codebase review of the coArchi model repository plugin  
> **Goal**: Identify structural improvements, deduplicate logic, enable headless CLI parity, and improve SDLC for consistent agent/developer collaboration

---

## 1. Executive Summary

The coArchi plugin works well as a UI-driven collaboration tool, but its architecture makes it difficult to:

1. **Run operations headlessly** — only 2 of 12+ operations are available via CLI
2. **Test core logic** — every action class requires `IWorkbenchWindow` and SWT
3. **Avoid duplication** — the CLI re-implements clone logic instead of sharing with the UI
4. **Maintain consistency** — action classes directly couple workflow orchestration, git operations, conflict resolution, and UI dialogs in single methods

The root cause is **no separation between core operations and UI presentation**. All business logic lives inside `Action` subclasses that require SWT.

---

## 2. Current Architecture

```
┌────────────────────────────────────────────────────────────────┐
│ Eclipse UI Layer                                                │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────────────┐ │
│  │ Views         │  │ Handlers     │  │ Dialogs               │ │
│  │ (Repository,  │  │ (wired via   │  │ (Clone, Commit,       │ │
│  │  History,     │──│  plugin.xml  │──│  Credentials,         │ │
│  │  Branches)    │  │  commands)   │  │  Conflicts, ...)      │ │
│  └──────────────┘  └──────┬───────┘  └───────────────────────┘ │
│                           │                                     │
│  ┌────────────────────────▼───────────────────────────────────┐ │
│  │ Action Classes (RefreshModelAction, PushModelAction, ...)  │ │
│  │  ⚠ ALL business logic lives here                          │ │
│  │  ⚠ Mixed: git ops + UI dialogs + progress + model reload  │ │
│  └────────────────────────┬───────────────────────────────────┘ │
└───────────────────────────┼─────────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────────┐
│ Core Layer                                                       │
│  ┌─────────────────┐  ┌──────────────────┐  ┌────────────────┐  │
│  │ ArchiRepository  │  │ GraficoExporter  │  │ Authentication │  │
│  │  (~1900 lines)   │  │ GraficoImporter  │  │ CredentialsAuth│  │
│  │  ⚠ Also mixed:  │  │ GraficoLoader    │  │ CryptoUtils    │  │
│  │  PlatformUI,     │  └──────────────────┘  └────────────────┘  │
│  │  IEditorModel    │                                            │
│  └─────────────────┘  ┌──────────────────┐  ┌────────────────┐  │
│                        │ MergeConflict    │  │ BranchInfo     │  │
│                        │  Handler         │  │ BranchStatus   │  │
│                        └──────────────────┘  └────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────────┐
│ CLI Plugin (separate bundle)                                     │
│  ┌──────────────────┐  ┌──────────────────────────┐             │
│  │ LoadModelProvider │  │ SaveModelProvider         │             │
│  │  ⚠ Duplicates    │  │  ⚠ Bypasses staging      │             │
│  │  clone logic      │  │                           │             │
│  └──────────────────┘  └──────────────────────────┘             │
│  Only supports: clone, load, save (2 of 12+ operations)         │
└──────────────────────────────────────────────────────────────────┘
```

### Key Problems

| # | Problem | Impact |
|---|---------|--------|
| 1 | **No service layer** — business logic lives in `Action` subclasses that require `IWorkbenchWindow` | Cannot reuse for CLI, cannot unit test |
| 2 | **`ArchiRepository` mixes 5 responsibilities** — git ops, native process management, GRAFICO export, model location, caching | 1900-line god class, hard to reason about |
| 3 | **`AbstractModelAction` forces UI coupling** — constructor requires `IWorkbenchWindow`, all helpers assume `Shell` | Every subclass inherits untestable UI dependencies |
| 4 | **Action classes instantiate each other** — `MergeBranchAction.doOnlineMerge()` creates `PushModelAction` and `SwitchBranchAction` inline | Tight coupling, can't substitute implementations |
| 5 | **`RefreshModelAction.pull()` has 7 `Display.syncExec` calls** mixed with core git/merge logic | Core workflow is not extractable |
| 6 | **CLI duplicates clone logic** and bypasses git staging on export | Inconsistent behavior between UI and CLI |
| 7 | **`exportModelToGraficoFiles()` calls `PlatformUI.getWorkbench()`** inside `ArchiRepository` | Core layer depends on UI platform |

---

## 3. Recommended Target Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│ UI Layer (SWT/JFace)                                             │
│  ┌───────────┐  ┌───────────┐  ┌──────────────────────────────┐ │
│  │ Views      │  │ Handlers  │  │ Dialogs                      │ │
│  └─────┬─────┘  └─────┬─────┘  └──────────────────────────────┘ │
│        │              │                                          │
│  ┌─────▼──────────────▼──────────────────────────────────────┐   │
│  │ UI Actions (thin wrappers)                                │   │
│  │  - Show progress dialogs                                  │   │
│  │  - Prompt for credentials / conflict resolution           │   │
│  │  - Delegate to Service layer                              │   │
│  │  - Display results / errors                               │   │
│  └─────────────────────────┬─────────────────────────────────┘   │
└────────────────────────────┼─────────────────────────────────────┘
                             │ calls
┌────────────────────────────▼─────────────────────────────────────┐
│ Service Layer (NEW — headless-safe)                               │
│                                                                   │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │ RepositoryService                                         │    │
│  │  refresh(repo, creds, monitor, ConflictStrategy)          │    │
│  │  publish(repo, creds, monitor, ConflictStrategy)          │    │
│  │  commit(repo, message, amend, monitor)                    │    │
│  │  clone(url, targetDir, creds, monitor)                    │    │
│  │  mergeBranch(repo, branch, monitor, ConflictStrategy)     │    │
│  │  switchBranch(repo, branch, monitor)                      │    │
│  │  createBranch(repo, name)                                 │    │
│  │  deleteBranch(repo, branch, creds)                        │    │
│  │  getBranches(repo) → List<BranchInfo>                     │    │
│  │  getHistory(repo, branch) → List<RevCommit>               │    │
│  └──────────────────────────────────────────────────────────┘    │
│                                                                   │
│  ┌────────────────────┐  ┌─────────────────────────────┐        │
│  │ ConflictStrategy    │  │ ProgressCallback             │        │
│  │  (interface)        │  │  (interface)                  │        │
│  │  resolveConflicts() │  │  onPhase(name)                │        │
│  │  resolveFolderMove()│  │  onProgress(current, total)   │        │
│  └────────────────────┘  └─────────────────────────────┘        │
│                                                                   │
│  Implementations:                                                 │
│  - InteractiveConflictStrategy (shows SWT dialogs)               │
│  - AutoConflictStrategy (takes "ours"/"theirs", for CLI/CI)      │
│  - EclipseProgressCallback (wraps IProgressMonitor)              │
│  - ConsoleProgressCallback (System.out for CLI)                  │
└────────────────────────────┬─────────────────────────────────────┘
                             │ uses
┌────────────────────────────▼─────────────────────────────────────┐
│ Core Layer (no UI dependencies)                                   │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ GitOperations (extracted from ArchiRepository)               │ │
│  │  fetch, push, merge, commit, checkout, reset, status,       │ │
│  │  branch create/delete, clone                                 │ │
│  │  Internal: native git / JGit switching                       │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  ┌──────────────────────┐  ┌──────────────────────────────────┐ │
│  │ GraficoExporter      │  │ GraficoImporter                  │ │
│  │ GraficoLoader        │  │ GraficoResourceLoader            │ │
│  └──────────────────────┘  └──────────────────────────────────┘ │
│                                                                   │
│  ┌──────────────────────┐  ┌──────────────────────────────────┐ │
│  │ MergeConflictHandler  │  │ Authentication                   │ │
│  │ (logic only, no UI)   │  │ CredentialsAuthenticator         │ │
│  └──────────────────────┘  └──────────────────────────────────┘ │
│                                                                   │
│  ┌──────────────────────┐  ┌──────────────────────────────────┐ │
│  │ BranchInfo            │  │ ChangeSummary                    │ │
│  │ BranchStatus          │  │ FolderMoveInfo                   │ │
│  └──────────────────────┘  └──────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

### Key Principle: **Strategy Pattern for UI/Headless Split**

Instead of hardcoding `Display.syncExec(() -> handler.openConflictsDialog())` inside business logic, inject a `ConflictStrategy` that the caller provides:

```java
// Service method — no UI dependencies
public RefreshResult refresh(IArchiRepository repo, UsernamePassword creds,
        IProgressMonitor monitor, ConflictStrategy conflictStrategy) {
    monitor.subTask("Fetching from remote...");
    FetchResult fetch = repo.fetchFromRemote(creds, monitor);
    
    MergeResult merge = repo.merge(remoteBranch, monitor);
    
    if (merge != null && merge.getMergeStatus() == CONFLICTING) {
        // Delegate conflict resolution to the strategy
        boolean resolved = conflictStrategy.resolveConflicts(merge, repo);
        if (!resolved) return RefreshResult.CANCELLED;
    }
    
    reloadModel(repo, monitor);
    commitIfNeeded(repo, merge, monitor);
    return RefreshResult.OK;
}

// UI implementation — shows SWT dialogs
class InteractiveConflictStrategy implements ConflictStrategy {
    boolean resolveConflicts(MergeResult merge, IArchiRepository repo) {
        MergeConflictHandler handler = new MergeConflictHandler(merge, ...);
        handler.init(monitor);
        Display.syncExec(() -> handler.openConflictsDialog(message));
        handler.merge();
        return true;
    }
}

// CLI implementation — auto-resolve or fail
class AutoConflictStrategy implements ConflictStrategy {
    boolean resolveConflicts(MergeResult merge, IArchiRepository repo) {
        // Take "ours" for all conflicts, or abort
        return false; // CLI can't resolve interactively
    }
}
```

---

## 4. Specific Recommendations

### 4.1 Extract a `RepositoryService` (High Priority)

**What**: Create a new class `org.archicontribs.modelrepository.services.RepositoryService` that contains ALL workflow logic currently scattered across action classes.

**Why**: Every operation (refresh, publish, commit, merge, clone, switch branch) follows the same pattern:
1. Pre-checks (save model, commit pending changes)
2. Git operation(s)
3. Post-processing (conflict resolution, model reload, commit cleanup)
4. Status reporting

This pattern is currently copy-pasted across 6+ action classes with UI code mixed in.

**How** (incremental):
1. Start with `commit()` — simplest operation, ~5 lines of core logic
2. Then `refresh()` — extract the `pull()` method from `RefreshModelAction`
3. Then `publish()` — `refresh()` + push
4. Then `mergeBranch()` — extract from `MergeBranchAction.merge()`
5. Then `clone()`, `switchBranch()`, `createBranch()`, `deleteBranch()`

Each action class becomes a thin UI wrapper:
```java
// Before: 150 lines of mixed logic
class RefreshModelAction extends AbstractModelAction {
    void run() { /* 150 lines: dialogs + git + progress + error handling */ }
}

// After: 30 lines of UI orchestration
class RefreshModelAction extends AbstractModelAction {
    void run() {
        UsernamePassword creds = getUsernamePassword();
        ProgressMonitorDialog dialog = new ProgressMonitorDialog(shell);
        dialog.run(true, true, monitor -> {
            RefreshResult result = repositoryService.refresh(
                getRepository(), creds, monitor, new InteractiveConflictStrategy(shell));
            if (result == RefreshResult.UP_TO_DATE) {
                Display.syncExec(() -> showInfoDialog("Up to date."));
            }
        });
    }
}
```

### 4.2 Split `ArchiRepository` (Medium Priority)

**What**: Extract the native git process management into a separate `NativeGitExecutor` class, and move model-location logic out.

| Current `ArchiRepository` responsibility | Target class |
|---|---|
| Git operations (fetch, push, merge, commit, checkout, reset, clone, status, branch) | `ArchiRepository` (keep) |
| Native git process management (`tryNativeGit*`, `runNativeGit`, `abortNativeMerge`) | `NativeGitExecutor` (new) |
| Model location (`locateModel`, `getName` with editor fallback) | `ModelLocator` or keep in UI layer |
| GRAFICO export with `PlatformUI` dependency | Remove zero-arg `exportModelToGraficoFiles()`, always require explicit `IProgressMonitor` |
| Cross-path deletion detection (static methods) | `CrossPathDeletionDetector` (new) or keep as static |
| Branch status caching | Keep in `ArchiRepository` |

### 4.3 Expand CLI to Full Parity (High Priority)

**What**: Once `RepositoryService` exists, wire ALL operations into the CLI plugin.

New CLI commands:
```
--modelrepository.commit <folder> --message "msg"     # Commit
--modelrepository.push <folder>                        # Push to remote
--modelrepository.pull <folder>                        # Pull/refresh
--modelrepository.mergeBranch <folder> --branch name   # Merge branch
--modelrepository.switchBranch <folder> --branch name  # Switch branch
--modelrepository.createBranch <folder> --branch name  # Create branch
--modelrepository.deleteBranch <folder> --branch name  # Delete branch
--modelrepository.status <folder>                      # Show repo status
```

All would delegate to `RepositoryService` with `AutoConflictStrategy` and `ConsoleProgressCallback`.

### 4.4 Decouple Action Classes from Each Other (Medium Priority)

**What**: `MergeBranchAction.doOnlineMerge()` currently instantiates `PushModelAction` and `SwitchBranchAction` directly. With `RepositoryService`, it would call service methods instead.

**Before**:
```java
// MergeBranchAction creates other action instances
PushModelAction pushAction = new PushModelAction(fWindow, model);
pushAction.init();
pushAction.pull(npw, monitor);
pushAction.push(npw, monitor);
SwitchBranchAction switchAction = new SwitchBranchAction(fWindow);
switchAction.switchBranch(branch, true);
```

**After**:
```java
// MergeBranchAction calls service methods
repositoryService.refresh(repo, creds, monitor, conflictStrategy);
repositoryService.publish(repo, creds, monitor);
repositoryService.switchBranch(repo, branch, monitor);
```

### 4.5 Remove `PlatformUI` from `ArchiRepository` (High Priority)

**What**: `exportModelToGraficoFiles()` (zero-arg) calls `PlatformUI.getWorkbench().getProgressService().busyCursorWhile()`. This prevents headless use.

**Fix**: Remove the zero-arg overload. All callers should provide their own `IProgressMonitor`. The UI layer can wrap it in `busyCursorWhile` if needed.

Similarly, `locateModel()` depends on `IEditorModelManager.INSTANCE.getModels()`. For headless use, the model reference should be passed in explicitly rather than searched for in the editor.

### 4.6 Introduce Result Objects (Low Priority)

**What**: Instead of action methods returning `int` status codes (`PULL_STATUS_OK`, `PULL_STATUS_UP_TO_DATE`, `MERGE_STATUS_MERGE_CANCEL`), return typed result objects.

```java
// Instead of: int status = pull(npw, monitor);
// Use:
record RefreshResult(Status status, int conflictCount, String commitMessage) {
    enum Status { OK, UP_TO_DATE, CANCELLED, ERROR }
}
```

This makes the API self-documenting and extensible without breaking callers.

---

## 5. Duplication Map

| Logic | Location 1 | Location 2 | Resolution |
|-------|-----------|-----------|------------|
| Clone workflow | `CloneModelAction.run()` | `LoadModelFromRepositoryProvider.run()` | Extract to `RepositoryService.clone()` |
| Pull workflow | `RefreshModelAction.pull()` | — (missing from CLI) | Extract to `RepositoryService.refresh()` |
| Export + stage | `ArchiRepository.exportModelToGraficoFiles()` | `SaveModelToRepositoryProvider.saveModel()` (no staging!) | Unify: service always stages |
| Credential gathering | `AbstractModelAction.getUsernamePassword()` | `LoadModelFromRepositoryProvider` (passFile) | `CredentialsAuthenticator` already centralizes — CLI should use it too |
| Model reload after git op | `RefreshModelAction.pull()` | `MergeBranchAction.merge()` | Extract to `RepositoryService` |
| Commit after merge | `RefreshModelAction.pull()` (commit block) | `MergeBranchAction.merge()` (commit block) | Nearly identical ~20 lines — extract to shared method |
| Conflict resolution flow | `RefreshModelAction.pull()` | `MergeBranchAction.merge()` | Both create `MergeConflictHandler`, init, show dialog, merge — extract to `ConflictStrategy` |
| Cross-path deletion check | `RefreshModelAction.pull()` | `MergeBranchAction.merge()` | Both call `detectAndRemoveCrossPathDeletions` with same pattern |
| Folder move repair | `RefreshModelAction.pull()` | `MergeBranchAction.merge()` | Both call `repairMissingFolderXml()` + `applyFolderMoveResolutions()` |

---

## 6. Migration Strategy

### Phase 1: Foundation (Low Risk)
1. Create `RepositoryService` class with `commit()` method
2. Wire `CommitModelAction` to use it (simplest action)
3. Add `--modelrepository.commit` to CLI
4. Remove `PlatformUI` from `ArchiRepository.exportModelToGraficoFiles()`

### Phase 2: Core Operations (Medium Risk)  
5. Extract `refresh()` from `RefreshModelAction.pull()`
6. Extract `publish()` (refresh + push)
7. Introduce `ConflictStrategy` interface
8. Wire `RefreshModelAction` and `PushModelAction` as thin wrappers
9. Add `--modelrepository.pull` and `--modelrepository.push` to CLI

### Phase 3: Branch Operations (Medium Risk)
10. Extract `mergeBranch()` from `MergeBranchAction`
11. Extract `switchBranch()` from `SwitchBranchAction`
12. Extract `createBranch()`, `deleteBranch()`
13. Add all branch commands to CLI

### Phase 4: Cleanup (Low Risk)
14. Extract `NativeGitExecutor` from `ArchiRepository`
15. Introduce result objects (`RefreshResult`, `MergeResult`, etc.)
16. Add unit tests for `RepositoryService` (now testable without SWT)

---

## 7. SDLC Improvements

### 7.1 Documentation Gaps

| Document | Status | Recommendation |
|----------|--------|----------------|
| **ARCHITECTURE.md** | Missing | Create with the target architecture diagram and layer descriptions (this review is a starting point) |
| **ADR folder** | Missing | Start recording design decisions (e.g., "Why native git + JGit dual path", "Why GRAFICO format", "Why encrypted credential storage") |
| **CONTRIBUTING.md** | Missing | Add: coding conventions, PR process, test requirements, how to add new operations |
| **CI pipeline** | Missing | Add GitHub Actions: build + test on push, automated `.archiplugin` artifact |

### 7.2 Agent Consistency

The existing `.github/copilot-instructions.md` is excellent for performance-critical code but missing guidance on:

| Topic | Current Coverage | Recommendation |
|-------|-----------------|----------------|
| **Architecture layers** | None | Add section: "All new operations must go through `RepositoryService`. Action classes are UI-only thin wrappers." |
| **Adding a new operation** | None | Add checklist: 1. Add to `RepositoryService`, 2. Add UI action wrapper, 3. Add CLI command, 4. Add tests for service method |
| **Where code belongs** | None | Add package responsibility guide: `services/` = workflows, `actions/` = UI only, `grafico/` = git + GRAFICO, `merge/` = conflict logic |
| **Testing expectations** | None | Add: "Service methods must have unit tests. Action classes are integration-tested via the plugin." |
| **Conflict strategy pattern** | None | Document the `ConflictStrategy` interface and how to add new strategies |
| **CLI parity** | None | Add: "Every `RepositoryService` method must have a corresponding CLI command" |
| **NLS/i18n** | None | Add: "All user-visible strings go in `messages.properties`. Use `NLS.bind()` for parameterized messages. Keep commit messages concise." |

### 7.3 Recommended Copilot Instructions Additions

Add to `.github/copilot-instructions.md`:

```markdown
## Architecture Rules

1. **Service Layer**: All git/model operations go through `RepositoryService`.
   Action classes must NOT contain business logic — they are UI wrappers only.

2. **No UI in Core**: Classes in `grafico/`, `merge/`, `services/`, and 
   `authentication/` must NEVER import SWT, JFace, or PlatformUI.

3. **Strategy Pattern for UI Decisions**: When an operation needs user input
   (conflict resolution, folder move choice), accept a strategy interface.
   Never call `Display.syncExec()` from the service layer.

4. **CLI Parity**: Every RepositoryService method must have a CLI command.
   When adding a new operation: service method → UI action → CLI command → test.

5. **Result Objects**: Service methods return typed result objects, not int codes.
```

### 7.4 Test Strategy

| Layer | Test Type | Framework | Coverage Target |
|-------|-----------|-----------|-----------------|
| `RepositoryService` | Unit tests | JUnit 5 + temp git repos | All operations, happy + error paths |
| `ArchiRepository` | Integration tests | JUnit 5 + bare repos | Git operations with native/JGit paths |
| `GraficoExporter/Importer` | Performance tests | JUnit 5 + `@Tag("performance")` | Throughput regression detection |
| `MergeConflictHandler` | Scenario tests | JUnit 5 (existing — excellent) | Keep current coverage |
| UI Actions | Manual/smoke | Plugin testing in Archi | Key workflows |
| CLI Commands | Integration tests | JUnit 5 + process execution | All commands with real repos |

---

## 8. Quick Wins (Can Do Now)

These improvements don't require the full architectural refactor:

1. **Extract the post-merge commit block** from both `RefreshModelAction.pull()` and `MergeBranchAction.merge()` into a shared method in `AbstractModelAction` — they're nearly identical (~20 lines each)

2. **Extract the conflict resolution flow** (create handler → init → show dialog → merge) into a shared method — duplicated between refresh and merge

3. **Extract the cross-path-deletion + folder-move-repair + model-reload sequence** — this exact 3-step sequence appears in both `RefreshModelAction.pull()` (twice: conflict and non-conflict paths) and `MergeBranchAction.merge()`

4. **Fix `SaveModelToRepositoryProvider`** to call `ArchiRepository.exportModelToGraficoFiles(monitor)` instead of creating its own exporter — this would automatically stage files too

5. **Add `--modelrepository.commit` to CLI** — the simplest missing operation, ~15 lines of code

---

## 9. File-by-File Impact Assessment

| File | Lines | Complexity | Change in Refactor |
|------|-------|------------|-------------------|
| `RefreshModelAction.java` | ~430 | **High** — 7 syncExec, conflict handling, folder moves | Becomes ~50 lines: credential prompt + progress dialog + service call |
| `PushModelAction.java` | ~160 | Medium | Becomes ~30 lines: credential prompt + service.publish() |
| `MergeBranchAction.java` | ~450 | **High** — online/local paths, creates other actions | Becomes ~80 lines: dialog choice + service call |
| `CommitModelAction.java` | ~80 | Low | Becomes ~30 lines: dialog + service.commit() |
| `CloneModelAction.java` | ~120 | Medium | Becomes ~40 lines: dialog + service.clone() |
| `SwitchBranchAction.java` | ~200 | Medium | Becomes ~40 lines: dialog + service.switchBranch() |
| `ArchiRepository.java` | ~1900 | **High** — 5 responsibility domains | Loses ~300 lines (native git → `NativeGitExecutor`) |
| `AbstractModelAction.java` | ~200 | Medium | Keep as UI base class, add service reference |
| **New: `RepositoryService.java`** | ~400 est. | Medium | All extracted business logic |
| **New: `ConflictStrategy.java`** | ~30 est. | Low | Interface + 2 implementations |
| **New: `NativeGitExecutor.java`** | ~300 est. | Medium | Extracted from ArchiRepository |

---

## 10. Conclusion

The plugin's functionality is solid, but its architecture conflates UI orchestration with core business logic. The main refactoring axis is introducing a **service layer** that all consumers (UI actions, CLI commands, tests, future API integrations) can share. This can be done incrementally, starting with the simplest operations and building confidence before tackling the complex merge/refresh flows.

The existing test suite for merge conflict handling is excellent and shouldn't need to change — it already tests the core logic directly. The main testing gap is in workflow orchestration (the refresh/publish/merge sequences), which becomes testable once extracted into `RepositoryService`.
