# Native Git Optimization

## Overview

This document describes the architecture and measured performance of using native git
commands (via `ProcessBuilder`) alongside JGit in the coArchi plugin. Native git is
significantly faster than JGit for operations on repositories with tens of thousands
of files (GRAFICO models typically contain 30,000+ XML files).

All native git logic is encapsulated in `ArchiRepository`, controlled by the
`useNativeGit` preference, and transparent to the rest of the codebase.

---

## Architecture: Single Gateway in ArchiRepository

### Design Principle

**All git interactions flow through `ArchiRepository`.** Actions, handlers, and UI code
never call native git or JGit directly. This means:

1. Any future native git optimization benefits **every code path** automatically.
2. The native/JGit switching logic exists in **one place** — no duplication.
3. Callers get a clean API and don't need to handle fallback or state synchronization.

### Public API (git operations exposed to callers)

| Method | Description |
|--------|-------------|
| `merge(remoteBranch)` | Merge remote branch; returns `MergeResult` (JGit) or `null` (native clean merge) |
| `checkoutBranch(branchName)` | Checkout a branch |
| `gitAddPaths(Set<String>)` | Stage specific paths (used by MergeConflictHandler) |
| `resetToRef(ref, resetType)` | Reset working tree and index |
| `cloneModel(url, creds, monitor)` | Clone a repository (native for SSH, JGit for HTTPS) |
| `commitChanges(message, amend)` | Commit (uses `gitAdd()` internally) |
| `exportModelToGraficoFiles(monitor)` | Export + `gitAdd()` internally |
| `collectDeletedElementIds(repo, ours, theirs, ids)` | Diff element deletions (native or JGit tree walk) |
| `extractIdFromElementFileName(name)` | Parse GRAFICO element filename |

### Internal Pattern (inside ArchiRepository)

Every git operation follows the same pattern:

```java
public void someGitOperation(...) throws IOException, GitAPIException {
    if (isNativeGitEnabled()) {
        boolean success = tryNativeSomeOperation(...);
        if (success) {
            invalidateBranchStatusCache();  // Sync UI
            return;
        }
        // null/false = native git unavailable or failed, fall through
    }
    // JGit fallback
    try (Git git = Git.open(getLocalRepositoryFolder())) {
        git.someCommand().call();
    }
    invalidateBranchStatusCache();
}
```

Private `tryNativeGit*` methods return:
- `true` — native git succeeded
- `false` — git not installed (enables silent fallback)
- throw `IOException` — git command failed (real error)

### Configuration

The `useNativeGit` preference controls all native git behavior:

```ini
# In Archi.ini (system property — takes precedence):
-Dorg.archicontribs.modelrepository/useNativeGit=false

# In plugin preferences (UI toggle):
org.archicontribs.modelrepository/useNativeGit=true   (default)
```

`isNativeGitEnabled()` checks the system property first, then falls back to the
preference store. This allows disabling native git for debugging without changing
the saved preference.

---

## Measured Performance: Native Git vs JGit-Only

### Test Environment
- **Repository**: ~48,500 items (38,823 at initial load, grows to 48,555 after pull)
- **Hardware**: 20 CPU cores, NVMe SSD, Windows
- **JGit version**: 7.6.0
- **Scenario**: Full pull (fetch + merge + cross-path detection + model import)

### Pull Breakdown (April 2026)

| Phase | Native Git ON | JGit Only | Speedup |
|-------|-------------:|----------:|--------:|
| exportModelToGraficoFiles | 8,217 ms | 8,522 ms | — |
| hasChangesToCommit (init) | 484 ms | 498 ms | — |
| **TOTAL INIT** | **8,706 ms** | **9,026 ms** | — |
| fetchFromRemote | 1,550 ms | 1,589 ms | — |
| **merge** | **3,886 ms** | **17,346 ms** | **4.5x** |
| getBranchStatus | 244 ms | 261 ms | — |
| detectAndRemoveCrossPathDeletions | 2,304 ms | 3,661 ms | **1.6x** |
| repairMissingFolderXml | 2,271 ms | 5,231 ms | **2.3x** |
| loadModel (import GRAFICO) | 4,073 ms | 14,677 ms | **3.6x** |
| hasChangesToCommit (post) | 813 ms | 811 ms | — |
| **TOTAL PULL** | **20,553 ms** | **43,596 ms** | **2.1x** |

### Key Observations

1. **Merge is the biggest win**: 3.9s (native) vs 17.3s (JGit) — JGit's recursive merge
   is 4.5x slower on ~48k files. Native git's C implementation handles the tree diff
   and 3-way merge far more efficiently.

2. **Cross-path deletion detection benefits from native `git diff`**: 2.3s vs 3.7s.
   The native path uses `git diff --name-only` between commit trees, while JGit must
   open and walk `DiffFormatter` entries in Java.

3. **repairMissingFolderXml is 2.3x faster with native git**: This uses git history
   lookups that benefit from native git's faster tree traversal.

4. **Phases that don't use git are unaffected** (export, fetch, hasChangesToCommit) —
   confirms the native git toggle works correctly and there's no regression.

5. **Total: native git cuts pull time by 53%** (43.6s → 20.6s) on a large model.

### Historical Performance Progression

| Configuration | Total Pull | Merge | Notes |
|---------------|----------:|------:|-------|
| JGit 7.5.0, native git OFF | 93,186 ms | 70,960 ms | Baseline |
| JGit 7.5.0, native merge ON | 46,855 ms | 31,504 ms | Cold disk cache |
| JGit 7.6.0, native git ON | 20,553 ms | 3,886 ms | JGit 7.6 improved tree walk |
| JGit 7.6.0, native git OFF | 43,596 ms | 17,346 ms | JGit-only baseline for 7.6 |

JGit 7.6.0's upgrade alone improved JGit merge from 70.9s → 17.3s (4.1x), but
native git still adds another 4.5x on top of that.

---

## Operations Using Native Git

### 1. Merge (`tryNativeGitMerge`)

The single largest performance win. Uses `git merge <remoteBranch>`.

**Strategy**: Try native merge first. If it conflicts, abort (`git merge --abort`) and
fall back to JGit so the conflict resolution handler gets a standard `MergeResult`.

```
Native merge clean → return null (no MergeResult needed)
Native merge conflict → abort, JGit merge → return MergeResult with conflicts
Native git unavailable → JGit merge → return MergeResult
```

### 2. Checkout (`tryNativeGitCheckout`)

Uses `git checkout <branch>`. 5.6x faster than JGit for large working trees.

### 3. Add/Stage (`tryNativeGitAdd`, `tryNativeGitAddPaths`)

- `tryNativeGitAdd()` — `git add .` (stage everything)
- `tryNativeGitAddPaths()` — `git add <path1> <path2> ...` (stage specific paths)

### 4. Reset (`tryNativeGitReset`)

Uses `git reset --hard <ref>` (or `--mixed`, `--soft`).

### 5. Clone (`tryNativeGitClone`)

Uses native git for SSH repositories (SSH agent/config) and HTTPS repositories
when GCM (Git Credential Manager) is the selected HTTP auth method. JGit fallback
for HTTPS with PAT/username-password authentication.

### 6. Fetch (`tryNativeGitFetch`)

Uses `git fetch --prune` for SSH and GCM-authenticated HTTPS repositories.
Returns `null` `FetchResult` — callers assume refs may have changed.
JGit fallback for PAT-authenticated HTTPS or when native git is unavailable.

### 7. Push (`tryNativeGitPush`)

Uses `git push` for SSH and GCM-authenticated HTTPS repositories.
Returns `null` `Iterable<PushResult>` — callers must null-check before iterating.
JGit fallback for PAT-authenticated HTTPS or when native git is unavailable.

### 8. Diff for Deletion Detection (`collectDeletedIdsNative`)

Uses `git diff --name-only <base>..<commit>` to find deleted elements between
commits. 1.6x faster than JGit `DiffFormatter`.

### 9. Status (`tryNativeGitStatus`)

Uses `git status --porcelain` for quick dirty check.

---

## Error Handling

All `tryNativeGit*` methods use this pattern:

```java
try {
    ProcessBuilder pb = new ProcessBuilder("git", ...);
    pb.directory(repoFolder);
    Process p = pb.start();
    // ... read output, check exit code ...
    if (exitCode != 0) throw new IOException("Git failed: " + output);
    return true;
}
catch (IOException ex) {
    if (ex.getMessage().contains("Cannot run program")
            || ex.getMessage().contains("not found")) {
        return false;  // Git not installed → silent fallback
    }
    throw ex;  // Real error → propagate
}
```

---

## JGit State Synchronization

**After every native git operation**, JGit's in-memory state (refs, index, etc.) must
be refreshed. This is handled by `invalidateBranchStatusCache()` and by opening/closing
a `Git` instance when needed. Without this, the Eclipse UI won't reflect changes made
by native git.

---

## Testing

### Running Tests

```bash
# Fast build (excludes integration/performance tests):
mvn verify

# Full build including performance tests:
mvn verify -Dinclude.perf.tests=true
```

`RemoteIntegrationTests` (6 tests) are tagged `@Tag("performance")` and excluded
from the default build. These tests exercise the full push/fetch/merge workflow.

### Manual Testing

1. **Native git ON** (default): Verify operations are fast
2. **Native git OFF**: Set `-Dorg.archicontribs.modelrepository/useNativeGit=false`
   in Archi.ini, verify JGit fallback works correctly
3. **No git installed**: Remove git from PATH, verify silent fallback to JGit

---

## Git Credential Manager (GCM) Integration

### Overview

Git Credential Manager (GCM) is now supported as a third HTTP authentication method
alongside SSH and PAT (Personal Access Token). When GCM is selected:

- **All remote operations** (clone, fetch, push) use native git, which delegates
  authentication to GCM
- **No username/password** is stored in coArchi — GCM caches tokens in the OS
  credential store (Windows Credential Manager, macOS Keychain, etc.)
- **OAuth/SSO/browser-based login** works automatically for GitHub, Azure DevOps, etc.

### Auth Method Selection

Users choose their HTTP auth method in Preferences → Collaboration → Authentication → HTTP:

| Method | How it works | Credential storage |
|--------|-------------|-------------------|
| **Username/PAT** (default) | JGit with `UsernamePasswordCredentialsProvider` | Encrypted file in coArchi |
| **Git Credential Manager** | Native git delegates to GCM | OS credential store |

SSH authentication always uses SSH keys regardless of this setting.

### Detection

`GitCredentialManagerDetector.isGCMAvailable()` checks:
1. `git config --global credential.helper` → contains "manager"
2. `git config --system credential.helper` → contains "manager" (fallback)

Result is cached for the application lifetime. Call `clearCache()` after config changes.

### Onboarding

On first startup, if GCM is detected and the user hasn't been prompted:
1. A dialog explains GCM's benefits
2. User chooses to enable GCM or keep PAT
3. The dialog is never shown again (`gcmOnboardingShown` preference)

### Headless/CLI Usage

The `--modelrepository.authMethod` command-line option supports:
- `pat` (default) — requires `--modelrepository.userName` and `--modelrepository.passFile`
- `gcm` — no credentials needed; native git + GCM handles authentication

```bash
Archi -consoleLog -nosplash -application com.archimatetool.commandline.app \
  --modelrepository.cloneModel "https://github.com/org/repo.git" \
  --modelrepository.loadModel "/path/to/clone" \
  --modelrepository.authMethod "gcm"
```

### Key Implementation Details

- `CredentialsAuthenticator.isGCMAuthEnabled()` — checks if GCM is the selected HTTP auth method
- `CredentialsAuthenticator.requiresExplicitCredentials(url)` — true only for HTTP+PAT (SSH and GCM don't need user-provided credentials)
- `CredentialsAuthenticator.getNonInteractiveCredentials(url, repo)` — returns SSH→null, GCM→credential-fill, PAT→stored
- `ArchiRepository.shouldUseNativeGitForRemote(url)` — returns true for SSH (when native
  git enabled) or for HTTP when GCM is selected
- All null checks on `FetchResult` / `Iterable<PushResult>` handle native git returns

---

## Future Optimization Opportunities

1. **Progress monitoring** — Parse native git progress output for UI updates
2. **Native `git pull`** — Currently uses JGit `PullCommand` (fetch + merge separately)

---

## References

- `ArchiRepository.java` — All native git methods (private `tryNativeGit*` helpers)
- `PERFORMANCE_ARCHITECTURE.md` — Async I/O, batching, DirCache guidelines
- `REFACTORING_NOTES.md` — Design decisions and pitfalls
- `NATIVE_GIT_REFACTORING.md` — Historical refactoring from GraficoUtils → ArchiRepository
