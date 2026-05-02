# Build & Testing Guide

## Quick Start

### Building the Plugin

```bash
# Full build (compile + tests + package)
.\build-archiplugin.ps1

# Compile only (skip tests, faster)
mvn compile -pl org.archicontribs.modelrepository -am

# Build with performance tests enabled
mvn verify -pl org.archicontribs.modelrepository.tests -am "-Dinclude.perf.tests=true"
```

### Running Tests

```bash
# Run all tests
mvn verify -pl org.archicontribs.modelrepository.tests -am

# Compile tests only (no execution)
mvn compile -pl org.archicontribs.modelrepository.tests -am
```

The build output (`.archiplugin` file) is available at:
```
C:\Users\RABRO14\dist\coArchi\coArchi_0.9.4.rb<timestamp>.archiplugin
```

---

## Build System

### Technologies
- **Build Tool**: Maven 3.9.9+ with Tycho 5.0.0 (for Eclipse/OSGi plugins)
- **Java Version**: Java 21+ (required for virtual threads in async I/O)
- **Platform**: Windows (uses PowerShell for build script)

### Build Stages

The Maven build automatically:
1. Resolves Eclipse/Archi runtime dependencies
2. Compiles plugin bundles
3. Runs test suite in OSGi container
4. Packages as P2 repository and `.archiplugin` bundle

The PowerShell build script (`build-archiplugin.ps1`) adds:
1. IDE classpath updates (for VS Code support)
2. Pre-build and post-build cleanup of OSGi runtime locks
3. Plugin JAR extraction and repackaging with version suffixes

### Build Configuration

Local configuration in `build-config.local.json` (git-ignored):
```json
{
    "DistDir": "C:\\path\\to\\output",
    "VersionSuffix": "rb",
    "MavenPath": "C:\\path\\to\\mvn.cmd"
}
```

### Build Artifacts

Located in `org.archicontribs.modelrepository.repository/target/repository/plugins/`:
- `org.archicontribs.modelrepository_*.jar` — main plugin
- `org.archicontribs.modelrepository.commandline_*.jar` — CLI plugin

---

## Testing

### Test Framework
- **Engine**: JUnit 5 (Jupiter)
- **Plugin Integration**: Tycho Surefire (OSGi/Eclipse test runner)
- **Test Bundles**: 
  - `org.archicontribs.modelrepository.tests` — main regression suite
  - Runs in full Eclipse/Archi environment (SWT, EMF, GEF APIs available)

### Test Suites

#### AllTests (default)
Covers core merge logic, conflict resolution, folder move detection:
- `MergeConflictHandlerTests` — merge scenarios, move groups, folder consolidation
- `RepositoryServiceTests` — service-layer merge workflows
- `ArchiRepositoryTests` — git operations, conflict index handling
- `GraficoModelLoaderTests` — model import/repair workflows

**Run with**: `mvn verify -pl org.archicontribs.modelrepository.tests -am`

#### AllTestsWithPerformance
Includes performance/stress tests that take longer:
- Large merge scenarios (30,000+ files)
- Nested folder move detection efficiency
- Model repair on corrupted merge states

**Run with**: `mvn verify -pl org.archicontribs.modelrepository.tests -am "-Dinclude.perf.tests=true"`

### Tycho Test Execution Limitations

**CRITICAL**: The `-Dtest` parameter does NOT filter Tycho test execution
```bash
# ❌ WRONG: This is ignored by Tycho Surefire
mvn verify -Dtest="ClassName#methodName"  # Runs ALL tests anyway

# ✅ CORRECT: Use <includes> in pom.xml or @Tag annotations
# Current config in org.archicontribs.modelrepository.tests/pom.xml:
# <include>**/AllTests.java</include>  OR  <include>**/AllTestsWithPerformance.java</include>
```

This is a known Tycho limitation — it uses its own test runner, not standard Maven Surefire.

### Test Execution Time
- **Default suite** (AllTests): ~3-5 minutes
- **With performance tests**: ~10-15 minutes
- **OSGi framework bootstrap**: ~10-15 seconds overhead (before first test)

Test reports appear in `target/surefire-reports/` after the test class completes.

### Test Patterns

#### GraficoTestHelper Pattern (recommended)
Build models programmatically and export to GRAFICO:
```java
var helper = new GraficoTestHelper();
IFolder folderX = helper.addFolder(helper.businessFolder(), "FolderX", "id-folderX");
IArchimateElement q = helper.addBusinessActor(folderX, "Q", "id-q");
helper.export(repoFolder);

// GRAFICO structure: model/business/id-folderX/BusinessActor_id-q.xml
```

Key notes:
- Folder paths use folder ID as directory name (e.g., `id-folderX`, not `FolderX`)
- Element files: `{SimpleClassName}_{id}.xml` (e.g., `BusinessActor_id-q.xml`)
- Branch modifications use file-level ops: `renameElement()`, `moveElementFile()`, `writeFolderXml()`

#### Model Import in Tests
- **DirCache-based importer** skips conflicted entries (only imports clean stage)
- **Import "ours"**: `new GraficoModelImporter(repoFolder).importAsModel()`
- **Import "theirs"** from commit: `new GraficoModelImporter(gitRepo, commit.getTree()).importFromCommit(null)`

---

## Common Issues & Fixes

### File Lock Errors
**Symptom**: "The process cannot access the file because it is being used by another process"

**Root Cause**: Tycho's OSGi runtime doesn't fully shut down after Maven exits, leaving Java processes with file handles on `target/work/plugins/`

**Fix** (automatic via `build-archiplugin.ps1`):
- Kills orphaned Maven/Java processes before and after build
- Force-deletes `target/work` directories holding extracted JAR files
- No manual intervention needed when using the build script

If manual cleanup is needed:
```powershell
Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force
Remove-Item "org.archicontribs.modelrepository\target" -Recurse -Force
Remove-Item "org.archicontribs.modelrepository.tests\target" -Recurse -Force
```

### Stale Build Artifacts
**Symptom**: Tests fail or behave unexpectedly after code changes

**Cause**: Cached classes in compiled JAR don't reflect source edits after incremental builds

**Fix**: Full rebuild
```bash
Remove-Item target -Recurse -Force
mvn verify -pl org.archicontribs.modelrepository.tests -am
```

---

## IDE Support (VS Code)

The build script automatically updates `.classpath` with:
- Bundled library JARs (JGit, SSH, SLF4J)
- Eclipse/Archi runtime plugin references

This enables Java language server support for code completion, navigation, and diagnostics in VS Code.

Classpath is regenerated:
1. **Before Maven build** (if Archi runtime already exists)
2. **After Maven build** (picks up newly downloaded Archi runtime)

---

## Architecture & Performance Notes

### Performance-Critical Code
High-performance async I/O is used in:
- `GraficoModelExporter.java` — exports GRAFICO files (30,000+ files)
- `GraficoModelImporter.java` — imports GRAFICO files with DirCache batching

See `.github/copilot-instructions.md` for detailed async I/O patterns and batching guidelines.

### Key Design Decisions

- **Service Layer**: All git/model workflow logic goes through `RepositoryService`
- **UI vs Headless**: Service layer methods must be headless-safe (no SWT imports)
- **Thread Safety**: 
  - Editor operations (`IEditorModelManager.saveModel()`) must run on SWT UI thread
  - Import/repair work can run on worker threads (CPU/I/O bound)
  - Model merge logic coordinates both with `Display.syncExec()`

### When Tests Change Behavior

1. **Test modifications don't automatically reload** — class files are cached
2. **Tycho runs ALL tests in the bundle** regardless of `-Dtest` parameter
3. **Profile/activation** in pom.xml controls which test suite runs (see `AllTests.java` vs `AllTestsWithPerformance.java`)

---

## Related Documentation

- [BUILDING.md](BUILDING.md) — Original build instructions
- [ARCHITECTURE.md](ARCHITECTURE.md) — System design and service layer
- [CONTRIBUTING.md](CONTRIBUTING.md) — Development workflow
- `.github/copilot-instructions.md` — Performance guidelines for async I/O and Grafico processing
