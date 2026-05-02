# UI Layout Flash Fix Plan

## Symptom

When background processing is running (fetch, refresh, push, merge, switch branch) and the user
clicks in the UI with no modal dialog visible, the application:
- Shows a busy-wait cursor
- Flickers from maximized to normal window state and back

## Root Causes Identified

### Cause A — `ProgressMonitorDialog(fWindow.getShell())` [PRIMARY]

On Windows, when a child modal dialog is created with the workbench shell as its parent, the OS
sends `WM_ACTIVATE`/`WM_DEACTIVATE` messages to the parent shell during dialog open AND close.
SWT responds by restoring the window from its maximized state to produce the temporary normalize
flash visible to the user.

**Fix**: Pass `null` as the parent shell. The dialog becomes a top-level window without triggering
the parent's activation cycle.

**Sites (6)**:

| File | Line(s) |
|------|---------|
| `actions/RefreshModelAction.java` | ~95 |
| `actions/PushModelAction.java` | ~69 |
| `actions/MergeBranchAction.java` | ~119 (local merge), ~179 (online merge) |
| `actions/SwitchBranchAction.java` | ~247 |
| `grafico/GraficoModelLoader.java` | ~165 |
| `views/repositories/FetchJob.java` | ~64 (**already uses `null`** ✓) |

### Cause B — Untargeted `parent.layout()` fired from `asyncExec` [SECONDARY]

Four "kludge" layout calls in viewer `doSetInput()` methods fire from `asyncExec` callbacks
(e.g. at the end of a background commit load). Because `ProgressMonitorDialog.run()` pumps the
event loop, these callbacks can execute during an unrelated dialog, making the layout recalculation
appear at unexpected moments.

**Fix**: Replace broad `parent.layout()` with targeted `parent.layout(new Control[]{widget})`
so only the column widths for the changed control are recalculated without propagating up the
widget tree. For `BranchesViewer` (a `ComboViewer`) the call is unnecessary and can be removed.

**Sites (4)**:

| File | Line | Current | Fix |
|------|------|---------|-----|
| `views/history/HistoryTableViewer.java` | ~199 | `getTable().getParent().layout()` | `getTable().getParent().layout(new Control[]{getTable()})` |
| `views/history/BranchesViewer.java` | ~108 | `getControl().getParent().layout()` | remove |
| `views/branches/BranchesTableViewer.java` | ~97 | `getTable().getParent().layout()` | `getTable().getParent().layout(new Control[]{getTable()})` |
| `views/branches/BranchesTableViewer.java` | ~116 | `getTable().getParent().layout()` | `getTable().getParent().layout(new Control[]{getTable()})` |

### Cause C — `busyCursorWhile` pumping the event loop [CONTRIBUTING]

Nine sites use `busyCursorWhile` which also pumps the event loop, draining any pending
`asyncExec` layout callbacks from concurrent background threads. This can make Cause B
visible at additional entry points.

**Fix (deferred)**: Only pursue if Causes A and B do not fully resolve the symptom. Would
require migrating these actions to background `Job`s, which is a larger refactor.

---

## Diagnostic Logging

`UIPerfLogger` calls have been added (guarded by `UIPerfLogger.ENABLED`) to:

1. Log `shell.getMaximized()` **before** every `ProgressMonitorDialog.run()` call — confirms
   whether the shell was maximized at the point the dialog opened (Cause A diagnostic).
2. Log after `SwitchBranchAction` `dialog.run()` — shows whether the shell is still maximized
   after the dialog closes.
3. Log every `parent.layout()` call in the viewers — confirms when and how often Cause B fires.

**Enable logging**:

```
-Dcoarchi.ui.perf.logging=true
```

in `Archi.ini` (or Eclipse launch config VM arguments). Results appear in the Eclipse Error Log
view (**Window → Show View → Error Log**) and in `.metadata/.log`.

---

## Phases

### Phase 1 — `ProgressMonitorDialog` parent (this commit)

Change all `new ProgressMonitorDialog(fWindow.getShell())` → `new ProgressMonitorDialog(null)` at
the 5 affected sites. Add `UIPerfLogger` diagnostics at each site.

Expected outcome: The window maximize/normalize flash disappears for all affected actions.

### Phase 2 — Targeted `layout()` calls (follow-up)

Replace the 3 remaining untargeted `layout()` calls with `layout(new Control[]{widget})` and
remove the unnecessary `BranchesViewer.parent.layout()` call entirely.

Expected outcome: Layout thrashing during event loop pumps is eliminated, preventing Cause B
from reintroducing the flash if new actions are added in future.

---

## Verification

1. Build and launch Archi with `-Dcoarchi.ui.perf.logging=true` in a maximized window.
2. Trigger **Refresh**, **Push**, **Merge**, and **Switch Branch** — confirm no maximize→normalize flash.
3. Check the Error Log — confirm `shell.maximized=true` is logged before each dialog, and `true`
   after, with no `parent.layout() called` entries appearing during dialog execution.
4. Run `mvn verify` — all tests must pass.

---

*Last updated: April 29, 2026*
