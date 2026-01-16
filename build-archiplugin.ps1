# build-archiplugin.ps1
# Run this after exporting plugins from Eclipse to create an .archiplugin file
#
# Usage: .\build-archiplugin.ps1 [-ExportDir <path>] [-Version <version>]
#
# Configuration:
#   Create a build-config.local.json file (gitignored) with your local paths:
#   {
#       "ExportDir": "C:\\path\\to\\eclipse\\export\\plugins",
#       "DistDir": "C:\\path\\to\\output"
#   }
#
# Or pass parameters directly: .\build-archiplugin.ps1 -ExportDir "C:\path" -DistDir "C:\dist"

param(
    [string]$ExportDir = "",
    [string]$OutputDir = "",
    [string]$Version = "",
    [string]$VersionSuffix = "",
    [string]$OutputFile = "",
    [string]$DistDir = ""
)

$ErrorActionPreference = "Stop"

# Paths
$mainPluginId = "org.archicontribs.modelrepository"
$cmdlinePluginId = "org.archicontribs.modelrepository.commandline"
$sourceDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Load local config if exists
$configFile = Join-Path $sourceDir "build-config.local.json"
if (Test-Path $configFile) {
    $config = Get-Content $configFile | ConvertFrom-Json
    if (-not $ExportDir -and $config.ExportDir) { $ExportDir = $config.ExportDir }
    if (-not $DistDir -and $config.DistDir) { $DistDir = $config.DistDir }
    if (-not $VersionSuffix -and $config.VersionSuffix) { $VersionSuffix = $config.VersionSuffix }
    Write-Host "Loaded config from: build-config.local.json" -ForegroundColor DarkGray
}

# Apply defaults for anything still not set
if (-not $ExportDir) { $ExportDir = "C:\temp\coarchi-export\plugins" }
if (-not $OutputDir) { $OutputDir = "$env:TEMP\archiplugin-build" }
if (-not $DistDir) { $DistDir = "$env:USERPROFILE\dist\coArchi" }

Write-Host "=== Archi Plugin Builder ===" -ForegroundColor Cyan
Write-Host "Export Dir: $ExportDir"
Write-Host "Source Dir: $sourceDir"

# Validate export directory
if (-not (Test-Path $ExportDir)) {
    Write-Error "Export directory not found: $ExportDir`nExport plugins from Eclipse first!"
    exit 1
}

# Find exported JARs (use exact pattern to avoid matching commandline as main)
# Sort by LastWriteTime descending to get the latest export when multiple exist
$mainJar = Get-ChildItem "$ExportDir" -Filter "$mainPluginId`_*.jar" -ErrorAction SilentlyContinue | 
    Where-Object { $_.Name -notmatch "commandline" } | 
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
$cmdlineJar = Get-ChildItem "$ExportDir" -Filter "$cmdlinePluginId`_*.jar" -ErrorAction SilentlyContinue | 
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $mainJar) {
    Write-Error "Main plugin JAR not found in $ExportDir`nLooking for: $mainPluginId`_*.jar (not commandline)"
    Write-Host "Files in directory:" -ForegroundColor Yellow
    Get-ChildItem $ExportDir | ForEach-Object { Write-Host "  $($_.Name)" }
    exit 1
}

Write-Host "Found main JAR: $($mainJar.Name)" -ForegroundColor Green
if ($cmdlineJar) {
    Write-Host "Found cmdline JAR: $($cmdlineJar.Name)" -ForegroundColor Green
}

# Extract version and qualifier from JAR name (e.g., org.archicontribs.modelrepository_0.9.4.202501161234.jar)
$jarBaseName = [System.IO.Path]::GetFileNameWithoutExtension($mainJar.Name)
if ($jarBaseName -match "_(\d+\.\d+\.\d+)\.?(.*)$") {
    $jarVersion = $matches[1]
    $qualifier = $matches[2]
    if (-not $Version) { $Version = $jarVersion }
} else {
    if (-not $Version) { $Version = "0.9.4" }
    $qualifier = Get-Date -Format "yyyyMMddHHmm"
}

# Apply version suffix as prefix to qualifier (OSGi requires major.minor.micro.qualifier format)
# e.g., "rb" suffix becomes "0.9.4.rb202601161718" not "0.9.4-rb.202601161718"
$fullQualifier = if ($VersionSuffix) { "${VersionSuffix}${qualifier}" } else { $qualifier }
$pluginFolderName = "${mainPluginId}_${Version}.${fullQualifier}"
Write-Host "Plugin folder: $pluginFolderName" -ForegroundColor Yellow

# Clean and create output directory
if (Test-Path $OutputDir) {
    Remove-Item $OutputDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path "$OutputDir\$pluginFolderName" | Out-Null

# Create magic marker file
"Magic file to signify this is an Archi plug-in bundle." | Out-File "$OutputDir\archi-plugin" -Encoding ASCII -NoNewline
Write-Host "Created archi-plugin marker"

# The exported JAR is already in "exploded" format - it contains:
# - org.archicontribs.modelrepository.jar (inner JAR with compiled classes)
# - img/, lib/, META-INF/, LICENSE.txt, plugin.xml, plugin.properties
# So we just need to EXTRACT it, not copy resources from source

$tempExtractDir = "$env:TEMP\coarchi-jar-extract"
if (Test-Path $tempExtractDir) {
    Remove-Item $tempExtractDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $tempExtractDir | Out-Null

# Copy JAR to .zip for extraction (PowerShell only supports .zip)
$tempZipPath = "$env:TEMP\coarchi-main.zip"
Copy-Item $mainJar.FullName $tempZipPath -Force
Expand-Archive -Path $tempZipPath -DestinationPath $tempExtractDir -Force
Remove-Item $tempZipPath -Force

# Copy extracted contents to plugin folder
Get-ChildItem $tempExtractDir | ForEach-Object {
    Copy-Item $_.FullName "$OutputDir\$pluginFolderName\" -Recurse -Force
}
Write-Host "Extracted and copied exported JAR contents" -ForegroundColor Green

# Update the version in MANIFEST.MF
$manifestPath = "$OutputDir\$pluginFolderName\META-INF\MANIFEST.MF"
if (Test-Path $manifestPath) {
    $manifestContent = Get-Content $manifestPath -Raw
    $manifestContent = $manifestContent -replace "Bundle-Version: .*", "Bundle-Version: $Version.$fullQualifier"
    Set-Content $manifestPath $manifestContent -NoNewline
    Write-Host "Updated MANIFEST.MF version to $Version.$fullQualifier"
}

# Clean up temp extraction dir
Remove-Item $tempExtractDir -Recurse -Force

# Copy and rename commandline plugin JAR with suffix in qualifier
if ($cmdlineJar) {
    # Extract version from cmdline JAR name and apply suffix
    $cmdlineBaseName = [System.IO.Path]::GetFileNameWithoutExtension($cmdlineJar.Name)
    if ($VersionSuffix -and $cmdlineBaseName -match "^(.+)_(\d+\.\d+\.\d+)\.(.+)$") {
        $cmdlineId = $matches[1]
        $cmdlineVersion = $matches[2]
        $cmdlineQualifier = $matches[3]
        $cmdlineFullQualifier = "${VersionSuffix}${cmdlineQualifier}"
        $newCmdlineName = "${cmdlineId}_${cmdlineVersion}.${cmdlineFullQualifier}.jar"
        
        # Find jar command
        $jarCmd = if ($env:JAVA_HOME) { "$env:JAVA_HOME\bin\jar.exe" } else { "jar" }
        
        # Update MANIFEST.MF inside the JAR using jar command
        $cmdlineTempDir = "$env:TEMP\coarchi-cmdline-extract"
        if (Test-Path $cmdlineTempDir) { Remove-Item $cmdlineTempDir -Recurse -Force }
        New-Item -ItemType Directory -Force -Path $cmdlineTempDir | Out-Null
        
        # Extract using jar command (preserves JAR structure)
        Push-Location $cmdlineTempDir
        & $jarCmd xf $cmdlineJar.FullName
        Pop-Location
        
        $cmdlineManifest = "$cmdlineTempDir\META-INF\MANIFEST.MF"
        if (Test-Path $cmdlineManifest) {
            $manifestContent = Get-Content $cmdlineManifest -Raw
            $manifestContent = $manifestContent -replace "Bundle-Version: .*", "Bundle-Version: $cmdlineVersion.$cmdlineFullQualifier"
            Set-Content $cmdlineManifest $manifestContent -NoNewline
        }
        
        # Repackage using jar command (proper JAR format with MANIFEST first)
        $newCmdlinePath = "$OutputDir\$newCmdlineName"
        Push-Location $cmdlineTempDir
        & $jarCmd cfm $newCmdlinePath "META-INF\MANIFEST.MF" .
        Pop-Location
        
        Remove-Item $cmdlineTempDir -Recurse -Force
        
        Write-Host "Copied and updated commandline JAR: $newCmdlineName" -ForegroundColor Green
    } else {
        # No suffix or pattern didn't match - just copy as-is
        Copy-Item $cmdlineJar.FullName "$OutputDir\"
        Write-Host "Copied commandline JAR"
    }
}

# Create the .archiplugin file
# Note: Create as .zip first, then rename (PowerShell only supports .zip extension)
if (-not $OutputFile) {
    # Create dist directory if needed
    if (-not (Test-Path $DistDir)) {
        New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
    }
    # Use suffix in filename for easy identification
    $filenameSuffix = if ($VersionSuffix) { "-$VersionSuffix" } else { "" }
    $OutputFile = "$DistDir\coArchi_$Version$filenameSuffix.archiplugin"
}

$tempZipOutput = "$env:TEMP\coarchi-output.zip"

if (Test-Path $OutputFile) {
    Remove-Item $OutputFile -Force -Recurse
}
if (Test-Path $tempZipOutput) {
    Remove-Item $tempZipOutput -Force
}

Compress-Archive -Path "$OutputDir\*" -DestinationPath $tempZipOutput -Force
Move-Item $tempZipOutput $OutputFile -Force

Write-Host ""
Write-Host "=== Build Complete ===" -ForegroundColor Green
Write-Host "Created: $OutputFile" -ForegroundColor Green
Write-Host ""
Write-Host "To install: Drag onto Archi or use Help -> Manage Plug-ins -> Install New..."

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
