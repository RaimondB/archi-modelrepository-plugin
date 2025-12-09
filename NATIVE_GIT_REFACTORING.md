# Native Git Utility Methods Refactoring

## Overview

This refactoring implements the "try native git, fallback to JGit" pattern as private methods in `ArchiRepository`. This ensures consistent behavior across all git operations and includes critical JGit state synchronization.

**IMPORTANT:** Git utility methods are now part of `ArchiRepository`, not `GraficoUtils`. This provides better cohesion since all Git operations belong in the repository class.

## Key Principle: JGit State Synchronization

**CRITICAL:** After every native Git operation, you must call `Git.open(repoFolder).close()` to:
1. Refresh JGit's in-memory state (refs, index, etc.)
2. Trigger UI updates in Eclipse
3. Notify repository listeners

Without this, the UI won't reflect changes made by native git commands.

## Git Methods in ArchiRepository

### 1. `checkoutBranch(String branchName)` - Public Method

High-level checkout that tries native git, falls back to JGit, and syncs state.

```java
public void checkoutBranch(String branchName) throws IOException, GitAPIException
```

**Usage:**
```java
// Simple - handles everything automatically
getRepository().checkoutBranch(branchName);
```

**Replaces this pattern:**
```java
// OLD: Manual try/fallback with Git.open() to sync state
boolean nativeSuccess = tryNativeGitCheckout(repoFolder, branchName);
if(!nativeSuccess) {
    try(Git git = Git.open(repoFolder)) {
        git.checkout().setName(branchName).call();
    }
}
else {
    Git.open(repoFolder).close(); // Sync JGit state after native operation
}
```

### 2. `gitAdd()` - Private Method

### 2. `gitAdd()` - Private Method

High-level add that tries native git, falls back to JGit, and syncs state. Called internally by `commitChanges()` and `exportModelToGraficoFiles()`.

```java
private void gitAdd() throws IOException, GitAPIException
```

**Usage (internal to ArchiRepository):**

```java
// Within ArchiRepository methods - stages all files
gitAdd();
```

**Replaces this pattern:**

```java
// OLD: Manual try/fallback
boolean nativeSuccess = tryNativeGitAdd(repoFolder);
if(!nativeSuccess) {
    try(Git git = Git.open(repoFolder)) {
        AddCommand addCommand = git.add();
        addCommand.addFilepattern(".");
        addCommand.setUpdate(false);
        addCommand.call();
    }
}
else {
    Git.open(repoFolder).close(); // Sync JGit state
}
```

### 3. `gitReset(String ref, ResetType resetType)` - Private Method

High-level reset that tries native git, falls back to JGit, and syncs state. Called internally by `resetToRef()`.

```java
private void gitReset(String ref, ResetType resetType) 
        throws IOException, GitAPIException
```

**Usage (internal to ArchiRepository):**

```java
// Within resetToRef() - reset to specific commit
gitReset(ref, resetType);
```

### 4. `nativeGitClone(String repoURL, File targetFolder, ConfigCallback configCallback)` - Private Method

High-level clone that tries native git for SSH repositories and syncs state. Called internally by `cloneModel()`.

```java
private void nativeGitClone(String repoURL, File targetFolder, 
        ConfigCallback configCallback) throws IOException
```

**Usage (internal to ArchiRepository):**

```java
// For SSH repositories (tries native git)
if(GraficoUtils.isSSH(repoURL)) {
    try {
        nativeGitClone(repoURL, getLocalRepositoryFolder(), 
            repository -> setDefaultConfigSettings(repository));
        return; // Success
    }
    catch(IOException ex) {
        // Fall through to JGit
    }
}

// For HTTPS or if native failed - use JGit
CloneCommand cloneCommand = Git.cloneRepository();
// ... JGit clone with credentials ...
```

**Note:** Clone is more complex because:
- SSH repos can use native git (faster, uses SSH keys)
- HTTPS repos need JGit for credential handling
- After native clone, we still need to apply config settings

## Implementation Details

### Private Helper Methods

The utility class has private low-level methods that do the actual native git execution:

- `tryNativeGitCheckout(File, String)` - Returns `boolean`, throws on git failure
- `tryNativeGitAdd(File)` - Returns `boolean`, throws on git failure  
- `tryNativeGitClone(String, File)` - Returns `boolean`, throws on git failure

These methods:
1. Return `false` if git is not found (enables fallback)
2. Return `true` if git command succeeded
3. Throw `IOException` if git command failed (non-zero exit)

### Error Handling Pattern

All native git methods use this pattern:

```java
try {
    ProcessBuilder pb = new ProcessBuilder("git", "command", ...);
    // ... execute and read output ...
    
    if(exitCode != 0) {
        throw new IOException("Git command failed: " + output);
    }
    return true;
}
catch(IOException ex) {
    String message = ex.getMessage();
    if(message != null && (message.contains("Cannot run program") 
            || message.contains("not found"))) {
        // Git not installed - return false for fallback
        return false;
    }
    // Real error - propagate
    throw ex;
}
catch(InterruptedException ex) {
    Thread.currentThread().interrupt();
    throw new IOException("Git command interrupted", ex);
}
```

## Files Updated

### 1. GraficoUtils.java

**Added:**
- `gitCheckout(File, String)` - Public high-level method
- `gitAdd(File)` - Public high-level method
- `gitClone(String, File, Object, Object, ConfigCallback)` - Public high-level method
- `ConfigCallback` interface - For post-clone configuration
- `tryNativeGitCheckout(File, String)` - Private helper
- `tryNativeGitAdd(File)` - Private helper (was public)
- `tryNativeGitClone(String, File)` - Private helper (was public)

**Imports added:**
```java
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
```

### 2. SwitchBranchAction.java

**Simplified from 60+ lines to ~10 lines:**

```java
// OLD: performGitCheckoutWithProgress()
String branchForJGit = branchInfo.isLocal() ? 
        branchInfo.getFullName() : branchInfo.getShortName();
String branchForNativeGit = branchInfo.getShortName();

progress.subTask("Trying native Git checkout...");
boolean nativeSuccess = tryNativeGitCheckout(repoFolder, branchForNativeGit);

if(!nativeSuccess) {
    progress.subTask("Using JGit checkout...");
    try(Git git = Git.open(repoFolder)) {
        git.checkout().setName(branchForJGit).call();
    }
    progress.subTask("JGit checkout completed");
}
else {
    progress.subTask("Native Git checkout completed, refreshing JGit state...");
    Git.open(repoFolder); // Sync state
    progress.subTask("JGit state refresh completed");
}

// NEW: performGitCheckoutWithProgress()
String branchName = branchInfo.isLocal() ? 
        branchInfo.getFullName() : branchInfo.getShortName();

progress.subTask("Checking out branch...");
getRepository().checkoutBranch(branchName);
progress.subTask("Checkout completed");
```

**Removed:**
- `tryNativeGitCheckout()` method (moved to ArchiRepository)
- Manual Git.open() calls (handled by repository method)
- Separate progress messages for native vs JGit (implementation detail)
- GraficoUtils import (no longer needed)

### 3. ArchiRepository.java

**Simplified git add calls using private method:**

```java
// OLD: commitChanges()
boolean nativeSuccess = tryNativeGitAdd();
if(!nativeSuccess) {
    AddCommand addCommand = git.add();
    addCommand.addFilepattern(".");
    addCommand.setUpdate(false);
    addCommand.call();
}

// NEW: commitChanges()
gitAdd();  // Private method handles try/fallback/sync
```

```java
// OLD: exportModelToGraficoFiles()
boolean nativeSuccess = tryNativeGitAdd();
if(!nativeSuccess) {
    try(Git git = Git.open(getLocalRepositoryFolder())) {
        AddCommand addCommand = git.add();
        addCommand.addFilepattern(".");
        addCommand.setUpdate(false);
        addCommand.call();
    }
}

// NEW: exportModelToGraficoFiles()
gitAdd();  // Private method handles try/fallback/sync
```

**Simplified clone call:**

```java
// OLD: cloneModel()
boolean useNativeGit = GraficoUtils.isSSH(repoURL);
boolean nativeSuccess = false;

if(useNativeGit) {
    try {
        nativeSuccess = tryNativeGitClone(repoURL, getLocalRepositoryFolder());
    }
    catch(IOException ex) {
        nativeSuccess = false;
    }
}

if(!nativeSuccess) {
    // JGit clone...
}
else {
    try(Git git = Git.open(getLocalRepositoryFolder())) {
        setDefaultConfigSettings(git.getRepository());
    }
}

// NEW: cloneModel()
boolean useNativeGit = GraficoUtils.isSSH(repoURL);

if(useNativeGit) {
    try {
        nativeGitClone(repoURL, getLocalRepositoryFolder(), 
            repository -> setDefaultConfigSettings(repository));
        return; // Success
    }
    catch(IOException ex) {
        // Fall through to JGit
    }
}

// JGit clone (HTTPS or native failed)...
```

## Benefits

### 1. **Consistency**
All git operations use the same pattern - no risk of forgetting Git.open() sync step.

### 2. **Maintainability**
Changes to the native/fallback pattern only need to be made in one place.

### 3. **Simplicity**
Calling code is much simpler - just call one method instead of 20+ lines.

### 4. **Correctness**
The critical `Git.open()` sync step is guaranteed to happen after native operations.

### 5. **Testability**
The utility methods can be tested independently.

## Performance Impact

No change - same native git execution, same JGit fallback. The refactoring only reorganizes code.

## Migration Guide

### For git checkout:

**Before:**
```java
boolean nativeSuccess = tryNativeGitCheckout(branchName);
if(!nativeSuccess) {
    try(Git git = Git.open(getLocalRepositoryFolder())) {
        git.checkout().setName(branchName).call();
    }
}
else {
    Git.open(getLocalRepositoryFolder()).close(); // Don't forget this!
}
```

**After:**
```java
getRepository().checkoutBranch(branchName);  // From action classes
// OR
checkoutBranch(branchName);  // Within ArchiRepository
```

### For git add:

**Before:**
```java
boolean nativeSuccess = tryNativeGitAdd();
if(!nativeSuccess) {
    try(Git git = Git.open(getLocalRepositoryFolder())) {
        git.add().addFilepattern(".").setUpdate(false).call();
    }
}
// Missing Git.open() sync - BUG!
```

**After:**
```java
gitAdd();  // Within ArchiRepository - private method
```

### For git clone (SSH only):

**Before:**
```java
boolean nativeSuccess = tryNativeGitClone(repoURL, targetFolder);
if(nativeSuccess) {
    try(Git git = Git.open(targetFolder)) {
        setDefaultConfigSettings(git.getRepository());
    }
}
else {
    // JGit clone...
}
```

**After:**
```java
try {
    nativeGitClone(repoURL, targetFolder,
        repo -> setDefaultConfigSettings(repo));
}
catch(IOException ex) {
    // JGit clone fallback...
}
```

## Testing Checklist

- [ ] Checkout branch with native git available
- [ ] Checkout branch with native git not available (rename git.exe)
- [ ] Stage files with native git available
- [ ] Stage files with native git not available
- [ ] Clone SSH repo with native git available
- [ ] Clone SSH repo with native git not available
- [ ] Clone HTTPS repo (should always use JGit)
- [ ] Verify UI updates after native operations
- [ ] Verify repository listener notifications work

## Future Enhancements

Consider adding similar utilities for:
- `gitFetch()` - Fetch from remote
- `gitPush()` - Push to remote (SSH only, credential issues for HTTPS)
- `gitPull()` - Pull from remote
- `gitRm()` - Remove files from index

Each would follow the same pattern:
1. Try native git
2. Fall back to JGit if not available
3. Sync JGit state with `Git.open().close()`
