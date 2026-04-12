# Building coArchi - Archi Model Repository Plugin

## Prerequisites

1. **Java 21** or later (JDK, not just JRE)
2. **Maven 3.9.9+** ([download](https://maven.apache.org/download.cgi))
3. **Archi source code** checked out separately (used as target platform)

The build automatically downloads the Archi 5.7.0 runtime to `target/archi-runtime/` on first run.

## Quick Start

```powershell
# 1. Clone the repository
git clone https://github.com/RaimondB/archi-modelrepository-plugin.git
cd archi-modelrepository-plugin

# 2. Create local build config
cp build-config.local.json.template build-config.local.json
# Edit build-config.local.json with your paths

# 3. Build and package
.\build-archiplugin.ps1
```

The `.archiplugin` file will be created in the configured `DistDir` (default: `%USERPROFILE%\dist\coArchi`).

## Build Configuration

Copy `build-config.local.json.template` to `build-config.local.json` (gitignored) and adjust:

```json
{
    "DistDir": "C:\\Users\\<you>\\dist\\coArchi",
    "VersionSuffix": "rb",
    "MavenPath": "C:\\path\\to\\mvn.cmd"
}
```

| Setting | Description | Default |
|---------|-------------|---------|
| `DistDir` | Output directory for `.archiplugin` | `%USERPROFILE%\dist\coArchi` |
| `VersionSuffix` | Prefix added to qualifier (e.g., `rb` → `0.9.4.rb202603111729`) | _(none)_ |
| `MavenPath` | Full path to `mvn.cmd` if not on PATH | Auto-detected |

## Build Script Options

```powershell
# Full build (Maven + package)
.\build-archiplugin.ps1

# Skip Maven if already built (just re-package)
.\build-archiplugin.ps1 -SkipMaven

# Override settings via parameters
.\build-archiplugin.ps1 -VersionSuffix "test" -DistDir "C:\temp\output"
```

## What the Build Does

1. **Downloads Archi runtime** (first run only) to `target/archi-runtime/`
2. **Runs Maven/Tycho build** — compiles both plugins against Eclipse 2024-06 + Archi target platform
3. **Updates `.classpath`** — resolves Archi runtime JARs for VS Code Java Language Server support
4. **Packages `.archiplugin`** — creates a ZIP with forward-slash paths (macOS compatible) containing:
   - `archi-plugin` marker file
   - `org.archicontribs.modelrepository_<version>/` directory (dir-based bundle with inner JAR, lib/, img/)
   - `org.archicontribs.modelrepository.commandline_<version>.jar`

## Installing in Archi

1. Open Archi
2. **Help > Manage Plug-ins > Install New...**
3. Select the `.archiplugin` file
4. Restart Archi

## Development in VS Code

After the first build, the `.classpath` file is updated with resolved Archi runtime JAR references. This enables Java Language Server features (autocomplete, error checking) in VS Code without Eclipse PDE.

To refresh after Archi version changes:

```powershell
.\build-archiplugin.ps1 -SkipMaven  # Just updates .classpath from existing runtime
```

## Development in Eclipse

### Import Projects

1. File > Import > General > Existing Projects into Workspace
2. Browse to the repository root
3. Select all projects:
   - `org.archicontribs.modelrepository`
   - `org.archicontribs.modelrepository.commandline`
   - `org.archicontribs.modelrepository.feature`

### Run/Debug

1. Right-click `org.archicontribs.modelrepository`
2. Run As > Eclipse Application (or Debug As)
3. This launches Archi with the plugin loaded

## Project Structure

```
archi-modelrepository-plugin/
├── .mvn/                          # Maven Tycho build extension
├── pom.xml                        # Parent POM (Tycho 5.0.0, Archi download)
├── coarchi.target                 # Target platform definition
├── build-archiplugin.ps1          # Build + package script
├── org.archicontribs.modelrepository/        # Main plugin (dir-based bundle)
│   ├── META-INF/MANIFEST.MF
│   ├── src/                       # Java source code
│   ├── lib/                       # Bundled JARs (JGit, SSH, SLF4J)
│   └── pom.xml
├── org.archicontribs.modelrepository.commandline/  # CLI plugin
│   └── pom.xml
├── org.archicontribs.modelrepository.feature/      # Eclipse feature
│   ├── feature.xml
│   └── pom.xml
├── org.archicontribs.modelrepository.repository/   # P2 repository (build output)
│   ├── category.xml
│   └── pom.xml
└── org.archicontribs.modelrepository.tests/        # Tests
```
