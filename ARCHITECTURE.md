# Architecture

> **Last Updated**: 2026-04-26

This document describes the target architecture for the coArchi plugin. All changes should follow these guidelines.

## Layer Overview

```
┌───────────────────────────────────────────────────────────┐
│ UI Layer (SWT/JFace) — org.archicontribs.modelrepository  │
│  actions/      Action classes (thin UI wrappers)           │
│  dialogs/      SWT dialogs                                 │
│  views/        Eclipse views                               │
│  handlers/     Eclipse command handlers                     │
│  preferences/  Preference pages                             │
│  merge/        Conflict resolution UI                       │
└──────────────────────────┬────────────────────────────────┘
                           │ delegates to
┌──────────────────────────▼────────────────────────────────┐
│ Service Layer — org.archicontribs.modelrepository.services │
│  RepositoryService   Workflow orchestration (headless-safe)│
│  ConflictStrategy    Interface for conflict resolution     │
│  ProgressCallback    Interface for progress reporting      │
└──────────────────────────┬────────────────────────────────┘
                           │ uses
┌──────────────────────────▼────────────────────────────────┐
│ Core Layer (no UI dependencies)                            │
│  grafico/         ArchiRepository, GRAFICO I/O, BranchInfo │
│  authentication/  Credentials, SSH, GCM, crypto            │
│  merge/           MergeConflictHandler (logic only)         │
└───────────────────────────────────────────────────────────┘
                           │
┌──────────────────────────▼────────────────────────────────┐
│ CLI Layer — org.archicontribs.modelrepository.commandline   │
│  Providers wire CLI args → RepositoryService methods        │
└───────────────────────────────────────────────────────────┘
```

## Key Rules

1. **Service Layer**: All git/model workflow logic goes through `RepositoryService`.
   Action classes are UI wrappers — they collect input, show progress, delegate to the service, and display results.

2. **No UI in Core**: Classes in `grafico/`, `services/`, and `authentication/` must NOT import SWT, JFace, or `PlatformUI`.

3. **Strategy Pattern for UI Decisions**: When an operation needs user input (conflict resolution, folder move choice), accept a strategy interface. Never call `Display.syncExec()` from the service layer.

4. **CLI Parity**: Every `RepositoryService` method should have a corresponding CLI command.
   When adding a new operation: service method → UI action → CLI command → test.

5. **Result Objects**: Service methods return typed result objects, not `int` status codes.

## Package Responsibilities

| Package | May import SWT? | Responsibility |
|---------|:-:|---|
| `services/` | No | Workflow orchestration, coordinates git ops + GRAFICO I/O |
| `grafico/` | No | Git operations, GRAFICO export/import, repository state |
| `authentication/` | No | Credential storage, SSH, GCM |
| `merge/` (handler) | No | Conflict detection and resolution logic |
| `actions/` | Yes | UI wrappers: dialogs → service → result display |
| `dialogs/` | Yes | SWT dialogs |
| `views/` | Yes | Eclipse views |
| `merge/` (dialogs) | Yes | Conflict resolution UI |

## Adding a New Operation — Checklist

1. Add method to `RepositoryService` (headless-safe)
2. Add NLS strings to `messages.properties`
3. Create or update UI action wrapper in `actions/`
4. Add CLI command provider in the commandline plugin
5. Add unit test for the service method
6. Update this document if the change affects architecture

## Related Documents

- [ARCHITECTURE_REVIEW.md](ARCHITECTURE_REVIEW.md) — Detailed review with migration plan
- [docs/adr/](docs/adr/) — Architecture Decision Records
- [BUILDING.md](BUILDING.md) — Build instructions
- [PERFORMANCE_ARCHITECTURE.md](org.archicontribs.modelrepository/src/org/archicontribs/modelrepository/grafico/PERFORMANCE_ARCHITECTURE.md) — GRAFICO I/O performance
- [REFACTORING_NOTES.md](org.archicontribs.modelrepository/src/org/archicontribs/modelrepository/grafico/REFACTORING_NOTES.md) — Design decisions and pitfalls
