# Contributing to coArchi

## Development Setup

See [BUILDING.md](BUILDING.md) for prerequisites, build instructions, and IDE setup.

## Architecture

Read [ARCHITECTURE.md](ARCHITECTURE.md) before making changes. Key rules:

- Business logic belongs in `RepositoryService` (service layer), not in action classes
- Action classes are thin UI wrappers: collect input → delegate to service → show result
- Core packages (`grafico/`, `services/`, `authentication/`) must not import SWT/JFace
- Every service method should have a CLI counterpart

## Code Conventions

- **Java 21** minimum (virtual threads are used)
- **NLS**: All user-visible strings go in `messages.properties`. Use `NLS.bind()` for parameterized messages.
- **Commit messages**: Keep the subject line concise (<72 chars). Use imperative mood.
- **No `@author` tags** on new code — use git blame instead.

## Pull Request Process

1. Create a feature branch from `master`
2. Follow the architecture guidelines
3. Add/update tests for service-layer changes
4. Ensure `mvn clean compile -T1C` passes
5. Build the plugin with `.\build-archiplugin.ps1` and test in Archi

## Test-First Development (TFD)

When fixing bugs, **always write a failing test first** before implementing the fix:

1. **Reproduce**: Write a test that exercises the exact scenario that triggers the bug
2. **Verify it fails**: Run the test and confirm it fails for the expected reason
3. **Fix**: Implement the minimal fix
4. **Verify it passes**: Run the test again and confirm it passes
5. **Regression guard**: The test stays in the suite permanently to prevent regressions

This applies especially to:
- Merge conflict resolution bugs (test in `MergeConflictHandlerTests`)
- Data loss scenarios (test in `RepositoryServiceTests`)
- GRAFICO import/export correctness (test in `GraficoModelLoaderTests`)

If you discover the behavior is actually **correct** (not a bug), convert the failing test into a **characterization test** that documents the expected behavior with an explanatory comment.

## Design Decisions

Architecture Decision Records are in [docs/adr/](docs/adr/). When making a non-obvious design choice:

1. Create a new ADR using the template in [docs/adr/0000-template.md](docs/adr/0000-template.md)
2. Use the next sequential number
3. Record the context, decision, and consequences
