# Runs the uploaded Minecraft jar on Android - by packaging its own classes as DEX.
# PowerShell 5.1 reads this file as ANSI, so the script body must stay ASCII-only.
param(
    [Parameter(Mandatory = $true)][string]$McJar,
    [string]$OutDir = "."
)

$ErrorActionPreference = "Stop"

$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$d8 = "$sdk\build-tools\36.0.0\d8.bat"
$androidJar = "$sdk\platforms\android-37.0\android.jar"
$javaHome = $env:JAVA_HOME
if (-not $javaHome) { $javaHome = "C:\Users\13806\Desktop\开我的世界服务器\.toolchain\jdk\jdk-21.0.12.1+1" }
$javac = Join-Path $javaHome "bin\javac.exe"

$work = Join-Path $env:TEMP "mcdexwork"
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Force $work | Out-Null

Write-Host "== 1/4 unpack =="
$unpack = Join-Path $work "jar"
New-Item -ItemType Directory -Force $unpack | Out-Null
& "$env:SystemRoot\System32\tar.exe" -xf $McJar -C $unpack
if ($LASTEXITCODE -ne 0) { throw "unpack failed" }

Write-Host "== 2/4 stub class =="
$srcDir = Join-Path $work "src"
New-Item -ItemType Directory -Force (Join-Path $srcDir "stub") | Out-Null
Set-Content (Join-Path $srcDir "stub\A.java") "package stub; public class A { public static int v() { return 1; } }" -Encoding ascii
& $javac -source 17 -target 17 -nowarn -d (Join-Path $work "stubclasses") (Join-Path $srcDir "stub\A.java")
if ($LASTEXITCODE -ne 0) { throw "javac stub failed" }

Write-Host "== 3/4 class jar =="
$classesRoot = Join-Path $work "classes"
New-Item -ItemType Directory -Force $classesRoot | Out-Null
Get-ChildItem $unpack -Filter "*.class" -Recurse | ForEach-Object {
    $rel = $_.FullName.Substring($unpack.Length + 1)
    $dst = Join-Path $classesRoot $rel
    $dir = Split-Path $dst
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
    Copy-Item $_.FullName $dst
}
$stubDir = Join-Path $classesRoot "stub"
if (-not (Test-Path $stubDir)) { New-Item -ItemType Directory -Force $stubDir | Out-Null }
Copy-Item (Join-Path $work "stubclasses\stub\A.class") (Join-Path $stubDir "A.class")

# d8 cannot take tens of thousands of file names on a Windows command line: feed it a jar instead
$classJar = Join-Path $work "classes.jar"
Push-Location $classesRoot
& (Join-Path $javaHome "bin\jar.exe") cf $classJar .
$jarExit = $LASTEXITCODE
Pop-Location
if ($jarExit -ne 0) { throw "jar create failed" }

Write-Host "== 4/4 d8 =="
& $d8 --release --min-api 26 --lib $androidJar --output (Join-Path $work "dex") $classJar
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

$dex = Join-Path $work "dex\classes.dex"
$target = Join-Path $OutDir "mc_payload.dex"
Copy-Item $dex $target -Force
Write-Host ("output: " + $target + "  (" + [math]::Round((Get-Item $target).Length / 1MB, 1) + " MB)")
