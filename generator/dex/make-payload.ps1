# p5 = PowerShell 5.1 (file is read as ANSI, keep this script ASCII-only)
param(
    [Parameter(Mandatory = $true)][string]$McJar,
    [string]$OutDir = "."
)

$ErrorActionPreference = "Stop"

$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$androidJar = "$sdk\platforms\android-37.0\android.jar"
$javaHome = $env:JAVA_HOME
if (-not $javaHome) { $javaHome = "C:\Users\13806\Desktop\开我的世界服务器\.toolchain\jdk\jdk-21.0.12.1+1" }
$java = Join-Path $javaHome "bin\java.exe"
$javac = Join-Path $javaHome "bin\javac.exe"
# PowerShell 5.1 misreads the Chinese path in this file as ANSI, so the r8 jar path comes from an
# ASCII-only environment variable set by the caller (or a fixed location).
$r8 = $env:MCLAN_R8
if (-not $r8) { $r8 = "C:\mc-r8\r8.jar" }
$spliceDir = "C:\Users\13806\mc-server-app\generator\dex"

$work = Join-Path $env:TEMP "mcdexwork"
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Force $work | Out-Null

function Invoke-Dex([string]$classJar, [string]$outDir) {
    if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Force $outDir | Out-Null }
    & $java -Xmx6g -cp $r8 com.android.tools.r8.D8 --release --min-api 26 --lib $androidJar --output $outDir $classJar
    if ($LASTEXITCODE -ne 0) { throw "d8 failed for $classJar" }
}

$spl = $spliceDir

Write-Host "== 1/6 unpack =="
$unpack = Join-Path $work "jar"
New-Item -ItemType Directory -Force $unpack | Out-Null
& "$env:SystemRoot\System32\tar.exe" -xf $McJar -C $unpack
if ($LASTEXITCODE -ne 0) { throw "unpack failed" }

Write-Host "== 2/6 class sets (core + fastutil + stub) =="
$classesAll = Join-Path $work "classes"
Get-ChildItem $unpack -Filter "*.class" -Recurse | ForEach-Object {
    $rel = $_.FullName.Substring($unpack.Length + 1)
    $dst = Join-Path $classesAll $rel
    $dir = Split-Path $dst
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
    Copy-Item $_.FullName $dst
}
$stubSrcDir = Join-Path $work "stubsrc\stub"
New-Item -ItemType Directory -Force $stubSrcDir | Out-Null
$stubSrc = Join-Path $stubSrcDir "A.java"
Set-Content $stubSrc "package stub; public class A { public static int v() { return 1; } }" -Encoding ascii
New-Item -ItemType Directory -Force (Join-Path $work "stubclasses") | Out-Null
& $javac -source 17 -target 17 -nowarn -d (Join-Path $work "stubclasses") $stubSrc
if ($LASTEXITCODE -ne 0) { throw "javac stub failed" }

$coreStage = Join-Path $work "core"
$fastStage = Join-Path $work "fast"
Get-ChildItem $classesAll -Recurse -Filter "*.class" | ForEach-Object {
    $rel = $_.FullName.Substring($classesAll.Length + 1)
    $isFastutil = $rel.StartsWith("it\unimi\dsi\fastutil")
    $base = if ($isFastutil) { $fastStage } else { $coreStage }
    $dst = Join-Path $base $rel
    $dir = Split-Path $dst
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
    Copy-Item $_.FullName $dst
}
$stubDir = Join-Path $coreStage "stub"
if (-not (Test-Path $stubDir)) { New-Item -ItemType Directory -Force $stubDir | Out-Null }
Copy-Item (Join-Path $work "stubclasses\stub\A.class") (Join-Path $stubDir "A.class")

$coreJar = Join-Path $work "core.jar"
$fastJar = Join-Path $work "fast.jar"
$stubJar = Join-Path $work "stub.jar"
Push-Location $coreStage; & (Join-Path $javaHome "bin\jar.exe") cf $coreJar .; if ($LASTEXITCODE -ne 0) { throw "jar core" }; Pop-Location
Push-Location $fastStage; & (Join-Path $javaHome "bin\jar.exe") cf $fastJar .; if ($LASTEXITCODE -ne 0) { throw "jar fast" }; Pop-Location
Push-Location $stubDir; & (Join-Path $javaHome "bin\jar.exe") cf $stubJar .; if ($LASTEXITCODE -ne 0) { throw "jar stub" }; Pop-Location

Write-Host "== 3/6 d8 core =="
$dexCore = Join-Path $work "dexcore"
Invoke-Dex $coreJar $dexCore
Write-Host "== 4/6 d8 fastutil =="
$dexFast = Join-Path $work "dexfast"
Invoke-Dex $fastJar $dexFast
Write-Host "== 5/6 d8 stub =="
$dexStub = Join-Path $work "dexstub"
Invoke-Dex $stubJar $dexStub

Write-Host "== 6/6 splice =="
$parts = @()
$parts += (Get-ChildItem $dexStub -Filter "classes*.dex" | Sort-Object Name | Select-Object -First 1).FullName
$parts += (Get-ChildItem $dexCore -Filter "classes*.dex" | Sort-Object Name | ForEach-Object { $_.FullName })
$parts += (Get-ChildItem $dexFast -Filter "classes*.dex" | Sort-Object Name | ForEach-Object { $_.FullName })

$tmpDir = Join-Path $work "splice"
New-Item -ItemType Directory -Force $tmpDir | Out-Null
$acc = $parts[0]
$count = 1
for ($i = 1; $i -lt $parts.Count; $i++) {
    $next = Join-Path $tmpDir ("acc" + $count + ".dex")
    & $java -cp $spliceDir $spl $acc $parts[$i] $next
    if ($LASTEXITCODE -ne 0) { throw "splice step $i failed" }
    $acc = $next
    $count++
}

$target = Join-Path $OutDir "mc_payload.dex"
Copy-Item $acc $target -Force
Write-Host ("output: " + $target + "  (" + [math]::Round((Get-Item $target).Length / 1MB, 1) + " MB)")
