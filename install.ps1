# Copies the Aldhani jar into the Domino applications site, unpacks feature.xml,
# and lists the feature in platform.xml. Run this while the HTTP task is stopped.
# tell http quit, then this script, then load http. Do not restart the Windows service.
param(
    [string]$Jar,
    [string]$DataDir = "C:\Domino\Data"
)

$ErrorActionPreference = "Stop"
$featureId = "se.infoware.domino.aldhani.feature"
$bundleName = "se.infoware.domino.aldhani"

function Update-AldhaniPlatformText {
    param([string]$Text, [string]$Id, [string]$Version)
    $pattern = '(?s)(<site\b[^>]*(?:apps\.link|applications/eclipse)[^>]*>)(.*?)(</site>)'
    $match = [regex]::Match($Text, $pattern)
    if (-not $match.Success) {
        throw "The applications site was not found in platform.xml."
    }
    $inner = [regex]::Replace(
        $match.Groups[2].Value,
        "(?s)\s*<feature\b[^>]*\bid=`"$([regex]::Escape($Id))`"[^>]*>.*?</feature>",
        "")
    $entry = "<feature id=`"$Id`" url=`"features/${Id}_$Version/`" version=`"$Version`">`r`n</feature>`r`n"
    $site = $match.Groups[1].Value + $inner.TrimEnd() + "`r`n" + $entry + $match.Groups[3].Value
    return $Text.Remove($match.Index, $match.Length).Insert($match.Index, $site)
}

function Get-ManifestValue {
    param([string]$Manifest, [string]$Name)
    $folded = [regex]::Replace($Manifest, "`r?`n ", "")
    $found = [regex]::Match($folded, "(?m)^$([regex]::Escape($Name)):\s*(.+)$")
    if (-not $found.Success) { throw "The jar manifest has no $Name." }
    return $found.Groups[1].Value.Trim()
}

function Install-Aldhani {
    param([string]$JarPath, [string]$DataPath)
    if (-not (Test-Path -LiteralPath $JarPath)) { throw "Jar was not found: $JarPath" }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $manifestEntry = $zip.GetEntry("META-INF/MANIFEST.MF")
        $featureEntry = $zip.GetEntry("feature.xml")
        if (-not $manifestEntry -or -not $featureEntry) {
            throw "The jar must contain META-INF/MANIFEST.MF and feature.xml."
        }
        $reader = New-Object System.IO.StreamReader($manifestEntry.Open())
        try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $featureReader = New-Object System.IO.StreamReader($featureEntry.Open())
        try { $featureText = $featureReader.ReadToEnd() } finally { $featureReader.Dispose() }
    } finally {
        $zip.Dispose()
    }

    $symbolic = (Get-ManifestValue $manifest "Bundle-SymbolicName").Split(";")[0].Trim()
    $version = Get-ManifestValue $manifest "Bundle-Version"
    if ($symbolic -ne $bundleName) { throw "Unexpected bundle name: $symbolic" }

    $featureDoc = New-Object System.Xml.XmlDocument
    $featureDoc.LoadXml($featureText)
    $fileFeatureId = $featureDoc.DocumentElement.GetAttribute("id")
    $fileFeatureVersion = $featureDoc.DocumentElement.GetAttribute("version")
    if ($fileFeatureId -ne $featureId -or $fileFeatureVersion -ne $version) {
        throw "feature.xml ($fileFeatureId $fileFeatureVersion) does not match the bundle ($version)."
    }

    $eclipse = Join-Path $DataPath "domino\workspace\applications\eclipse"
    $plugins = Join-Path $eclipse "plugins"
    $features = Join-Path $eclipse "features"
    $featureDir = Join-Path $features "${featureId}_$version"
    $platform = Join-Path $DataPath "domino\workspace\.config\org.eclipse.update\platform.xml"
    New-Item -ItemType Directory -Force -Path $plugins, $features | Out-Null

    Get-ChildItem -LiteralPath $plugins -Filter "${bundleName}_*.jar" -ErrorAction SilentlyContinue |
        Remove-Item -Force
    Get-ChildItem -LiteralPath $features -Directory -Filter "${featureId}_*" -ErrorAction SilentlyContinue |
        Remove-Item -Recurse -Force
    Get-ChildItem -LiteralPath $features -Filter "${featureId}_*.jar" -ErrorAction SilentlyContinue |
        Remove-Item -Force
    New-Item -ItemType Directory -Force -Path $featureDir | Out-Null

    $pluginJar = Join-Path $plugins "${bundleName}_$version.jar"
    Copy-Item -LiteralPath $JarPath -Destination $pluginJar -Force
    $utf8 = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText((Join-Path $featureDir "feature.xml"), $featureText, $utf8)

    if (-not (Test-Path -LiteralPath $platform)) { throw "platform.xml was not found: $platform" }
    $platformText = [System.IO.File]::ReadAllText($platform)
    $updated = Update-AldhaniPlatformText -Text $platformText -Id $featureId -Version $version
    [System.IO.File]::WriteAllText($platform, $updated, $utf8)
    return $pluginJar
}

function Assert-HttpStopped {
    param([string]$DataPath)
    $http = Get-Process nhttp -ErrorAction SilentlyContinue
    if (-not $http) { return }
    if (-not (Test-Path -LiteralPath (Join-Path $DataPath "notes.ini"))) { return }
    $pidList = ($http | ForEach-Object { $_.Id }) -join ", "
    throw "HTTP is running (nhttp $pidList). Run 'tell http quit', then this script, then 'load http'. Do not restart the Windows service."
}

if ($MyInvocation.InvocationName -eq ".") { return }

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $Jar) {
    $built = @(Get-ChildItem (Join-Path $root "target") -Filter "infoware-aldhani-*.jar" -ErrorAction SilentlyContinue)
    if ($built.Count -ne 1) { throw "Pass -Jar, or build exactly one target\infoware-aldhani-*.jar." }
    $Jar = $built[0].FullName
}
Assert-HttpStopped $DataDir
$installed = Install-Aldhani -JarPath $Jar -DataPath $DataDir
Write-Host "installed $installed"
Write-Host "Start HTTP with: load http"
