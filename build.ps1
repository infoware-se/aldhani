$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$version = "0.1.11"
$servlet = $env:ALDHANI_SERVLET_JAR
if (-not $servlet) {
    $servlet = "C:\Domino\osgi\rcp\eclipse\plugins\com.ibm.pvc.servlet_2.5.0.20260601-1307.jar"
}
$javac = "C:\Domino\jvm\bin\javac.exe"
$jar = "C:\Domino\jvm\bin\jar.exe"
$java = "C:\Domino\jvm\bin\java.exe"
if (-not (Test-Path $javac)) { $javac = "javac"; $jar = "jar"; $java = "java" }
if (-not (Test-Path $servlet)) { throw "Servlet API was not found at $servlet (set ALDHANI_SERVLET_JAR for CI)" }

$classes = Join-Path $root "target\classes"
$testClasses = Join-Path $root "target\test-classes"
Remove-Item -Recurse -Force (Join-Path $root "target") -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes, $testClasses | Out-Null

$main = @(Get-ChildItem (Join-Path $root "src\main\java") -Filter *.java -Recurse | ForEach-Object FullName)
& $javac --release 21 -encoding UTF-8 -cp $servlet -d $classes @main
if ($LASTEXITCODE -ne 0) { throw "compile failed" }

$tests = @(Get-ChildItem (Join-Path $root "src\test\java") -Filter *.java -Recurse | ForEach-Object FullName)
$testCp = "$classes;$servlet"
& $javac --release 21 -encoding UTF-8 -cp $testCp -d $testClasses @tests
if ($LASTEXITCODE -ne 0) { throw "test compile failed" }

Copy-Item (Join-Path $root "src\main\resources\plugin.xml") $classes
Copy-Item (Join-Path $root "src\main\resources\feature.xml") $classes
$bundle = Join-Path $root "target\infoware-aldhani-$version.jar"
& $jar cfm $bundle (Join-Path $root "src\main\resources\META-INF\MANIFEST.MF") -C $classes .
if ($LASTEXITCODE -ne 0) { throw "bundle jar failed" }

$runCp = "$testClasses;$classes"
& $java -cp $runCp se.infoware.domino.aldhani.AldhaniSelfTest
if ($LASTEXITCODE -ne 0) { throw "self-test failed" }
Write-Host "built $bundle"
