# build-archiplugin.ps1
# Build script for coArchi - Archi Model Repository plugin
# Uses Maven/Tycho to build, then packages as archiplugin
#
# Usage: .\build-archiplugin.ps1 [-VersionSuffix "rb"] [-SkipMaven] [-MavenPath "mvn"]
#
# Configuration:
#   Create a build-config.local.json file (gitignored) with your local paths:
#   {
#       "DistDir": "C:\\path\\to\\output",
#       "VersionSuffix": "rb",
#       "MavenPath": "C:\\path\\to\\mvn.cmd"
#   }

[CmdletBinding()]
param(
    [string]$OutputDir = "",
    [string]$DistDir = "",
    [string]$VersionSuffix = "",
    [switch]$SkipMaven,
    [string]$MavenPath = ""
)

$ErrorActionPreference = "Stop"

# Plugin identifiers
$mainPluginId = "org.archicontribs.modelrepository"
$cmdlinePluginId = "org.archicontribs.modelrepository.commandline"
$sourceDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Load local config if exists
$configFile = Join-Path $sourceDir "build-config.local.json"
if (Test-Path $configFile) {
    $config = Get-Content $configFile | ConvertFrom-Json
    if (-not $DistDir -and $config.DistDir) { $DistDir = $config.DistDir }
    if (-not $VersionSuffix -and $config.VersionSuffix) { $VersionSuffix = $config.VersionSuffix }
    if (-not $MavenPath -and $config.MavenPath) { $MavenPath = $config.MavenPath }
    Write-Host "Loaded config from: build-config.local.json" -ForegroundColor DarkGray
}

# Apply defaults
if (-not $OutputDir) { $OutputDir = "$env:TEMP\archiplugin-build" }
if (-not $DistDir) { $DistDir = "$env:USERPROFILE\dist\coArchi" }

# Find Maven
if (-not $MavenPath) {
    $mavenLocations = @(
        "$env:USERPROFILE\Maven\apache-maven-3.9.9\bin\mvn.cmd",
        "$env:MAVEN_HOME\bin\mvn.cmd",
        "mvn"  # Rely on PATH
    )
    foreach ($loc in $mavenLocations) {
        if (Get-Command $loc -ErrorAction SilentlyContinue) {
            $MavenPath = $loc
            break
        }
    }
}

if (-not $MavenPath) {
    Write-Error "Maven not found. Install Maven 3.9.9+ or set MavenPath parameter."
    exit 1
}

Write-Host "=== coArchi Plugin Builder ===" -ForegroundColor Cyan
Write-Host "Source Dir: $sourceDir"
Write-Host "Maven: $MavenPath"

# --- Update .classpath for VS Code IDE support ---
function Update-Classpath {
    param([string]$SourceDir)

    $archiPluginsDir = Join-Path $SourceDir "target\archi-runtime\Archi\plugins"
    if (-not (Test-Path $archiPluginsDir)) {
        Write-Host "Archi runtime not yet downloaded, skipping .classpath update" -ForegroundColor DarkGray
        return
    }

    $classpathFile = Join-Path $SourceDir "$mainPluginId\.classpath"
    Write-Host "Updating .classpath with Archi runtime references..." -ForegroundColor Yellow

    # Resolve local lib/ JARs dynamically (these are bundled with the plugin)
    $libDir = Join-Path $SourceDir "$mainPluginId\lib"
    $libEntries = @()
    if (Test-Path $libDir) {
        Get-ChildItem $libDir -Filter "*.jar" | Sort-Object Name | ForEach-Object {
            $libEntries += "	<classpathentry exported=""true"" kind=""lib"" path=""lib/$($_.Name)""/>"
        }
    }

    # Jar patterns to resolve from the Archi runtime plugins directory
    $jarSpecs = @(
        # Archi bundles (directory-based: plugins/<name>_<version>/<name>.jar)
        @{ Pattern = "com.archimatetool.model_*";    Dir = $true;  JarName = "com.archimatetool.model.jar";   Comment = "Archi model API" },
        @{ Pattern = "com.archimatetool.editor_*";   Dir = $true;  JarName = "com.archimatetool.editor.jar";  Comment = "Archi editor" },
        @{ Pattern = "com.archimatetool.widgets_*";  Dir = $true;  JarName = "com.archimatetool.widgets.jar"; Comment = "Archi widgets" },
        # Eclipse SWT
        @{ Pattern = "org.eclipse.swt.win32.win32.x86_64_*.jar"; Dir = $false; Comment = "SWT (Windows)" },
        # Eclipse Platform
        @{ Pattern = "org.eclipse.ui.workbench_*.jar";          Dir = $false; Comment = "Eclipse Workbench" },
        @{ Pattern = "org.eclipse.core.runtime_*.jar";          Dir = $false; Comment = "Eclipse Core Runtime" },
        @{ Pattern = "org.eclipse.core.commands_*.jar";         Dir = $false; Comment = "Eclipse Commands" },
        @{ Pattern = "org.eclipse.core.jobs_*.jar";             Dir = $false; Comment = "Eclipse Jobs" },
        @{ Pattern = "org.eclipse.jface_3*.jar";                Dir = $false; Comment = "JFace" },
        @{ Pattern = "org.eclipse.help.ui_*.jar";               Dir = $false; Comment = "Eclipse Help UI" },
        # Eclipse UI
        @{ Pattern = "org.eclipse.ui_3*.jar";                  Dir = $false; Comment = "Eclipse UI" },
        # Eclipse Equinox
        @{ Pattern = "org.eclipse.equinox.app_*.jar";          Dir = $false; Comment = "Equinox App" },
        @{ Pattern = "org.eclipse.equinox.common_*.jar";       Dir = $false; Comment = "Equinox Common" },
        @{ Pattern = "org.eclipse.equinox.preferences_*.jar";  Dir = $false; Comment = "Equinox Preferences" },
        @{ Pattern = "org.eclipse.equinox.registry_*.jar";     Dir = $false; Comment = "Equinox Registry" },
        @{ Pattern = "org.eclipse.equinox.security_*.jar";     Dir = $false; Comment = "Equinox Security" },
        @{ Pattern = "org.eclipse.osgi_3*.jar";                Dir = $false; Comment = "OSGi Framework" },
        # EMF
        @{ Pattern = "org.eclipse.emf.ecore_2*.jar";           Dir = $false; Comment = "EMF Ecore" },
        @{ Pattern = "org.eclipse.emf.ecore.xmi_*.jar";        Dir = $false; Comment = "EMF XMI" },
        @{ Pattern = "org.eclipse.emf.common_*.jar";           Dir = $false; Comment = "EMF Common" }
    )

    $runtimeEntries = @()
    foreach ($spec in $jarSpecs) {
        if ($spec.Dir) {
            $dir = Get-ChildItem $archiPluginsDir -Directory -Filter $spec.Pattern | Select-Object -First 1
            if ($dir) {
                $jarPath = "$($dir.Name)/$($spec.JarName)"
                $runtimeEntries += "	<!-- $($spec.Comment) -->"
                $runtimeEntries += "	<classpathentry kind=""lib"" path=""../target/archi-runtime/Archi/plugins/$jarPath""/>"
            }
        } else {
            $jar = Get-ChildItem $archiPluginsDir -Filter $spec.Pattern -File | Select-Object -First 1
            if ($jar) {
                $runtimeEntries += "	<!-- $($spec.Comment) -->"
                $runtimeEntries += "	<classpathentry kind=""lib"" path=""../target/archi-runtime/Archi/plugins/$($jar.Name)""/>"
            }
        }
    }

    if ($runtimeEntries.Count -eq 0) {
        Write-Host "  No jars found in Archi runtime, skipping" -ForegroundColor DarkYellow
        return
    }

    $libBlock = $libEntries -join "`n"
    $runtimeBlock = $runtimeEntries -join "`n"

    $classpathContent = @"
<?xml version="1.0" encoding="UTF-8"?>
<classpath>
	<classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-21"/>
	<classpathentry kind="con" path="org.eclipse.pde.core.requiredPlugins"/>
	<classpathentry kind="src" path="src"/>
	<!-- Bundled library JARs (JGit, SSH, SLF4J) -->
$libBlock
	<!--
		VS Code IDE support: explicit jar references for the Java Language Server.
		Eclipse PDE ignores these (resolved via requiredPlugins container above).
		Auto-generated by build-archiplugin.ps1 from target/archi-runtime/.
	-->
$runtimeBlock
	<classpathentry kind="output" path="bin"/>
</classpath>
"@

    Set-Content -Path $classpathFile -Value $classpathContent -Encoding UTF8
    Write-Host "  Updated $classpathFile with $($libEntries.Count) lib + $($jarSpecs.Count) runtime jar references" -ForegroundColor Green
}

# Update classpath before build (if runtime already exists from previous build)
Update-Classpath -SourceDir $sourceDir

# Run Maven build unless skipped
if (-not $SkipMaven) {
    Write-Host ""
    Write-Host "Running Maven build..." -ForegroundColor Yellow
    Push-Location $sourceDir
    try {
        & $MavenPath clean package -DskipTests
        if ($LASTEXITCODE -ne 0) {
            Write-Error "Maven build failed with exit code $LASTEXITCODE"
            exit 1
        }
    } finally {
        Pop-Location
    }
}

# Update classpath after build (picks up newly downloaded Archi runtime)
Update-Classpath -SourceDir $sourceDir

# Find built JARs from Maven repository output
$repositoryPluginsDir = Join-Path $sourceDir "org.archicontribs.modelrepository.repository\target\repository\plugins"

if (-not (Test-Path $repositoryPluginsDir)) {
    Write-Error "Maven build output not found at: $repositoryPluginsDir"
    exit 1
}

# Find main plugin JAR (exclude commandline)
$mainJar = Get-ChildItem "$repositoryPluginsDir" -Filter "$mainPluginId`_*.jar" -ErrorAction SilentlyContinue | 
    Where-Object { $_.Name -notmatch "commandline" } | 
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

# Find commandline plugin JAR
$cmdlineJar = Get-ChildItem "$repositoryPluginsDir" -Filter "$cmdlinePluginId`_*.jar" -ErrorAction SilentlyContinue | 
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $mainJar) {
    Write-Error "Main plugin JAR not found in $repositoryPluginsDir"
    exit 1
}

Write-Host "Found main JAR: $($mainJar.Name)" -ForegroundColor Green
if ($cmdlineJar) {
    Write-Host "Found cmdline JAR: $($cmdlineJar.Name)" -ForegroundColor Green
}

# Extract version and qualifier from JAR name
$jarBaseName = [System.IO.Path]::GetFileNameWithoutExtension($mainJar.Name)
if ($jarBaseName -match "_(\d+\.\d+\.\d+)\.?(.*)$") {
    $version = $matches[1]
    $qualifier = $matches[2]
} else {
    $version = "0.9.4"
    $qualifier = Get-Date -Format "yyyyMMddHHmm"
}

# Apply version suffix as prefix to qualifier (OSGi requires major.minor.micro.qualifier format)
# e.g., "rb" suffix becomes "0.9.4.rb202601161718" not "0.9.4-rb.202601161718"
$fullQualifier = if ($VersionSuffix) { "${VersionSuffix}${qualifier}" } else { $qualifier }
$pluginFolderName = "${mainPluginId}_${version}.${fullQualifier}"
Write-Host "Plugin folder: $pluginFolderName" -ForegroundColor Yellow

# Clean and create output directory
if (Test-Path $OutputDir) {
    Remove-Item $OutputDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path "$OutputDir\$pluginFolderName" | Out-Null

# Create marker file
"Magic file to signify this is an Archi plug-in bundle." | Out-File "$OutputDir\archi-plugin" -Encoding ASCII -NoNewline

# Extract main plugin JAR (it's a dir-based bundle with inner JAR + lib/)
$tempExtractDir = "$env:TEMP\coarchi-jar-extract"
if (Test-Path $tempExtractDir) {
    Remove-Item $tempExtractDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $tempExtractDir | Out-Null

$tempZipPath = "$env:TEMP\coarchi-main.zip"
Copy-Item $mainJar.FullName $tempZipPath -Force
Expand-Archive -Path $tempZipPath -DestinationPath $tempExtractDir -Force
Remove-Item $tempZipPath -Force

# Copy extracted contents to plugin folder
Get-ChildItem $tempExtractDir | ForEach-Object {
    Copy-Item $_.FullName "$OutputDir\$pluginFolderName\" -Recurse -Force
}
Write-Host "Extracted main plugin JAR contents" -ForegroundColor Green

# Update the version in MANIFEST.MF if VersionSuffix is set
if ($VersionSuffix) {
    $manifestPath = "$OutputDir\$pluginFolderName\META-INF\MANIFEST.MF"
    if (Test-Path $manifestPath) {
        $manifestContent = Get-Content $manifestPath -Raw
        $manifestContent = $manifestContent -replace "Bundle-Version: .*", "Bundle-Version: $version.$fullQualifier"
        Set-Content $manifestPath $manifestContent -NoNewline
        Write-Host "Updated main MANIFEST.MF version to $version.$fullQualifier"
    }
}

# Clean up
Remove-Item $tempExtractDir -Recurse -Force

# Find jar command for ZIP/JAR operations
$jarCmd = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\jar" } else { "jar" }

# Copy and rename commandline plugin JAR with suffix in qualifier
if ($cmdlineJar) {
    $cmdlineBaseName = [System.IO.Path]::GetFileNameWithoutExtension($cmdlineJar.Name)
    if ($VersionSuffix -and $cmdlineBaseName -match "^(.+)_(\d+\.\d+\.\d+)\.(.+)$") {
        $cmdlineId = $matches[1]
        $cmdlineVersion = $matches[2]
        $cmdlineQualifier = $matches[3]
        $cmdlineFullQualifier = "${VersionSuffix}${cmdlineQualifier}"
        $newCmdlineName = "${cmdlineId}_${cmdlineVersion}.${cmdlineFullQualifier}.jar"
        
        # Update MANIFEST.MF inside the JAR
        $cmdlineTempDir = "$env:TEMP\coarchi-cmdline-extract"
        if (Test-Path $cmdlineTempDir) { Remove-Item $cmdlineTempDir -Recurse -Force }
        New-Item -ItemType Directory -Force -Path $cmdlineTempDir | Out-Null
        
        Push-Location $cmdlineTempDir
        & $jarCmd xf $cmdlineJar.FullName
        Pop-Location
        
        $cmdlineManifest = "$cmdlineTempDir\META-INF\MANIFEST.MF"
        if (Test-Path $cmdlineManifest) {
            $manifestContent = Get-Content $cmdlineManifest -Raw
            $manifestContent = $manifestContent -replace "Bundle-Version: .*", "Bundle-Version: $cmdlineVersion.$cmdlineFullQualifier"
            Set-Content $cmdlineManifest $manifestContent -NoNewline
        }
        
        $newCmdlinePath = "$OutputDir\$newCmdlineName"
        Push-Location $cmdlineTempDir
        & $jarCmd cfm $newCmdlinePath "META-INF\MANIFEST.MF" .
        Pop-Location
        
        Remove-Item $cmdlineTempDir -Recurse -Force
        Write-Host "Copied and updated commandline JAR: $newCmdlineName" -ForegroundColor Green
    } else {
        # No suffix - just copy as-is
        Copy-Item $cmdlineJar.FullName "$OutputDir\"
        Write-Host "Copied commandline JAR"
    }
}

# Create .archiplugin file
$archipluginName = "coArchi_${version}.${fullQualifier}.archiplugin"
$archipluginPath = Join-Path $DistDir $archipluginName

New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

if (Test-Path $archipluginPath) {
    Remove-Item $archipluginPath -Force
}

# Use jar to create ZIP with forward-slash paths (Compress-Archive uses backslashes
# which breaks extraction on macOS/Linux where \ is a valid filename character)
Push-Location $OutputDir
try {
    $entries = Get-ChildItem -Name
    & $jarCmd cfM $archipluginPath @entries
    if ($LASTEXITCODE -ne 0) {
        Write-Error "jar cf failed with exit code $LASTEXITCODE"
        exit 1
    }
} finally {
    Pop-Location
}

Write-Host ""
Write-Host "=== Build Complete ===" -ForegroundColor Green
Write-Host "Output: $archipluginPath"
Write-Host ""
Write-Host "To install:" -ForegroundColor Yellow
Write-Host "1. Open Archi"
Write-Host "2. Help > Manage Plug-ins > Install New..."
Write-Host "3. Select: $archipluginPath"
Write-Host "4. Restart Archi"

# Show contents
Write-Host ""
Write-Host "Archive contents:" -ForegroundColor Yellow
Get-ChildItem $OutputDir -Recurse | ForEach-Object {
    $relativePath = $_.FullName.Replace($OutputDir, "").TrimStart("\")
    if ($_.PSIsContainer) {
        Write-Host "  $relativePath/" -ForegroundColor Blue
    } else {
        Write-Host "  $relativePath"
    }
}
