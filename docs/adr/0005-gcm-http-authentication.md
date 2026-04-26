# ADR-0005: GCM as HTTP Authentication Option

## Status

Accepted

## Date

2026-04-01 (approximate)

## Context

The plugin originally supported only username+password (PAT) for HTTP authentication, stored in an encrypted local file. This requires users to manually manage tokens and store them in the plugin's custom encrypted format.

Git Credential Manager (GCM) is widely deployed and handles credential storage, multi-factor authentication, and token refresh automatically. Many organizations mandate GCM for all git operations.

## Decision

Add GCM as an HTTP authentication option alongside the existing PAT approach:

- `CredentialsAuthenticator` detects GCM availability via `git credential-manager --version`
- When GCM is configured, credentials are obtained via `git credential fill` (piping protocol/host)
- The obtained credentials are passed to JGit for the fallback path
- CLI supports `--modelrepository.authMethod gcm`
- Preference page allows selecting between PAT and GCM

Centralized in `CredentialsAuthenticator.getNonInteractiveCredentials()` which handles SSH (null), GCM (git credential fill), and PAT (stored encrypted) uniformly.

## Consequences

- **Positive**: Seamless integration with enterprise SSO / MFA via GCM
- **Positive**: No need to manually manage PATs
- **Positive**: Works in both UI and CLI modes
- **Negative**: Depends on GCM being installed and configured
- **Negative**: `git credential fill` spawns a process for each credential request
