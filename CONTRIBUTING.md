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

## Design Decisions

Architecture Decision Records are in [docs/adr/](docs/adr/). When making a non-obvious design choice:

1. Create a new ADR using the template in [docs/adr/0000-template.md](docs/adr/0000-template.md)
2. Use the next sequential number
3. Record the context, decision, and consequences
