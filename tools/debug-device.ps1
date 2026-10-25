# 手机连上后一键调试：装包、授权、启动、抓 logcat、看崩溃栈
# 用法: powershell -ExecutionPolicy Bypass -File tools\debug-device.ps1 [-Apk <path>]
param(
    [string]$Apk = "C:\Users\13806\Desktop\开我的世界服务器\MC局域网开服-v2.0.2.apk"
)

$ErrorActionPreference = "Continue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$pkg = "com.mclan.server"

function Adb([string]$args) { & $adb $args.Split(' ') 2>&1 }

Write-Host "== 1. 设备 =="
& $adb devices -l
$dev = (& $adb devices) -split "`n" | Where-Object { $_ -match "\s+device\s*$" } | Select-Object -First 1
if (-not $dev) {
    Write-Host "没有可用设备（未开 USB 调试或未授权）"
    exit 1
}

Write-Host "`n== 2. 设备信息 =="
& $adb shell "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.product.cpu.abi"

Write-Host "`n== 3. root 状态 =="
& $adb shell "su -c id" 2>&1

Write-Host "`n== 4. 安装 =="
& $adb install -r "$Apk" 2>&1 | Select-Object -Last 3

Write-Host "`n== 5. 授权存储 =="
& $adb shell "pm grant $pkg android.permission.WRITE_EXTERNAL_STORAGE" 2>&1
& $adb shell "pm grant $pkg android.permission.READ_EXTERNAL_STORAGE" 2>&1
& $adb shell "appops set $pkg MANAGE_EXTERNAL_STORAGE allow" 2>&1

Write-Host "`n== 6. 启动并抓日志 =="
& $adb logcat -c
& $adb shell "am force-stop $pkg"
& $adb shell "am start -n $pkg/.MainActivity"
Start-Sleep -Seconds 4
Write-Host "--- AndroidRuntime / 我们的 tag ---"
& $adb logcat -d -v time AndroidRuntime:E "*:S" 2>&1 | Select-Object -Last 60
& $adb logcat -d -v time | Select-String -Pattern "MCLan|mclan" | Select-Object -Last 40

Write-Host "`n== 7. 进程与数据目录 =="
& $adb shell "pidof $pkg" 2>&1
& $adb shell "su -c 'ls -l /storage/emulated/0/mcserver 2>/dev/null | head -20'" 2>&1
& $adb shell "su -c 'ls -l /storage/emulated/0/mcserver/console.log 2>/dev/null; tail -20 /storage/emulated/0/mcserver/console.log 2>/dev/null'" 2>&1
& $adb shell "su -c 'cat /storage/emulated/0/mcserver/.server.pid 2>/dev/null; echo ---; ps -A -ww -o PID,ARGS | grep server.jar | grep -v grep'" 2>&1
