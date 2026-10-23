# Calls the running Aldhani servlet and checks the aldhanitest.nsf sandbox.
param(
    [string]$Url = "https://dantooine.oninfoware.local/mcp",
    [string]$Config = "C:\Domino\Data\aldhani.json"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not (Test-Path -LiteralPath $Config)) { throw "Config was not found: $Config" }

$servlet = "C:\Domino\osgi\rcp\eclipse\plugins\com.ibm.pvc.servlet_2.5.0.20260601-1307.jar"
$javac = "C:\Domino\jvm\bin\javac.exe"
$java = "C:\Domino\jvm\bin\java.exe"
if (-not (Test-Path $javac)) { $javac = "javac" }
if (-not (Test-Path $java)) { $java = "java" }
if (-not (Test-Path $servlet)) { throw "Domino servlet API was not found at $servlet" }

$classes = Join-Path $root "target\classes"
$testClasses = Join-Path $root "target\test-classes"
New-Item -ItemType Directory -Force -Path $classes, $testClasses | Out-Null

$main = @(Get-ChildItem (Join-Path $root "src\main\java") -Filter *.java -Recurse | ForEach-Object FullName)
& $javac --release 21 -encoding UTF-8 -cp $servlet -d $classes @main
if ($LASTEXITCODE -ne 0) { throw "compile failed" }
$tests = @(Get-ChildItem (Join-Path $root "src\test\java") -Filter *.java -Recurse | ForEach-Object FullName)
$testCp = "$classes;$servlet"
& $javac --release 21 -encoding UTF-8 -cp $testCp -d $testClasses @tests
if ($LASTEXITCODE -ne 0) { throw "test compile failed" }

$runCp = "$testClasses;$classes"
& $java -cp $runCp se.infoware.domino.aldhani.AldhaniSmoke $Url $Config
if ($LASTEXITCODE -ne 0) { throw "smoke failed" }
