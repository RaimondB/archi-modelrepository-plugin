# ADR-0006: Service Layer for Headless Operation Support

## Status

Accepted

## Date

2026-04-26

## Context

All workflow logic (commit, refresh, publish, merge, branch switch) lives in `Action` subclasses that require `IWorkbenchWindow` and SWT. This makes it impossible to:

1. Run operations from the CLI (only clone/load/save are supported)
2. Unit test workflow logic without a running Eclipse workbench
3. Avoid code duplication between UI actions and CLI providers

The `AbstractModelAction` base class forces UI coupling on all subclasses — its constructor requires `IWorkbenchWindow` and all helper methods assume a `Shell` is available.

See [ARCHITECTURE_REVIEW.md](../../ARCHITECTURE_REVIEW.md) for the full analysis.

## Decision

Introduce a **service layer** (`org.archicontribs.modelrepository.services.RepositoryService`) that contains all workflow logic, headless-safe:

- `commit(repo, message, amend, monitor)` — export + stage + commit
- `refresh(repo, creds, monitor, conflictStrategy)` — fetch + merge + reload (future)
- `publish(repo, creds, monitor, conflictStrategy)` — refresh + push (future)
- Additional methods added incrementally per the migration plan

The service layer uses **interfaces** for UI-dependent decisions:
- `ConflictStrategy` — resolve merge conflicts (interactive UI or auto-resolve)
- Standard `IProgressMonitor` — progress reporting

Action classes become thin wrappers that:
1. Collect user input (credentials, commit message) via dialogs
2. Create a progress dialog
3. Delegate to `RepositoryService`
4. Display results/errors

CLI providers delegate to the same `RepositoryService` methods with headless implementations of the strategy interfaces.

### Migration Strategy

Incremental, starting with the simplest operation:
1. **Phase 1**: `commit()` — simplest, validates the pattern
2. **Phase 2**: `refresh()`, `publish()` — complex, requires `ConflictStrategy`
3. **Phase 3**: Branch operations
4. **Phase 4**: Cleanup (extract `NativeGitExecutor`, result objects)

## Consequences

- **Positive**: CLI gains full parity with UI operations
- **Positive**: Workflow logic becomes unit-testable without SWT
- **Positive**: Single source of truth for each operation (no duplication)
- **Positive**: Strategy pattern enables future automation (CI/CD merge bots)
- **Negative**: Additional indirection layer
- **Negative**: Requires careful incremental migration to avoid breaking existing functionality
- **Negative**: `IEditorModelManager` calls in `ArchiRepository.exportModelToGraficoFiles()` need to be pushed up to callers
