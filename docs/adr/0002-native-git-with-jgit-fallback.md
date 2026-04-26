# ADR-0002: Native Git with JGit Fallback

## Status

Accepted

## Date

2025-06-01 (approximate)

## Context

JGit (pure Java Git implementation) is used for all git operations. However, JGit has significant performance limitations:
- SSH agent authentication is unreliable on Windows
- Clone/fetch/push are slower than native git
- JGit's SSH implementation has compatibility issues with some SSH configurations
- Git Credential Manager (GCM) integration is not available in JGit

## Decision

Use native `git` CLI as the primary backend for remote operations (clone, fetch, push, merge) when available, with JGit as the automatic fallback. The switching logic lives in `ArchiRepository`:

- `isNativeGitEnabled()` checks a preference/system property
- `shouldUseNativeGitForRemote(url)` returns `true` for SSH URLs or when GCM is configured
- Each operation tries native git first, catches failure, and falls back to JGit
- Commit always uses JGit (needed for author/amend support)

## Consequences

- **Positive**: SSH agent authentication works reliably (native git handles it)
- **Positive**: GCM integration for HTTPS authentication
- **Positive**: Better performance for large repositories
- **Positive**: `--progress` flag enables real-time progress streaming to UI
- **Negative**: Requires `git` on PATH — not always available in all environments
- **Negative**: Dual code paths increase maintenance and testing surface
- **Negative**: Native git output parsing is fragile (locale-dependent error messages)
