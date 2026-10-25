package com.mclan.server

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.io.File

/**
 * 后台保活：Doze 白名单 + adb 方案 + root 方案（Magisk / KernelSU）。
 *
 * 风险提示（界面里会完整展示，用户确认后才执行）：
 *  · root 方案会执行 su 命令、往 /data/adb/service.d 写开机脚本；
 *  · 「关闭 Doze」是全局设置，会影响所有应用的后台与耗电；
 *  · 应用一旦被授予 root，任何被利用的漏洞都会变成整机权限；
 *  · 系统更新、刷机、更换 root 管理器后需要重新执行。
 */
object KeepAlive {

    // ---------------------------------------------------------------- 通用

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        } catch (t: Throwable) {
            false
        }
    }

    fun requestIgnoreBatteryOptimizations(activity: Activity) {
        val pkg = activity.packageName
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                i.data = Uri.parse("package:" + pkg)
                activity.startActivity(i)
                return
            } catch (t: Throwable) {
            }
        }
        openBatterySettings(activity)
    }

    fun openBatterySettings(activity: Activity) {
        try {
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (t: Throwable) {
            openAppSettings(activity)
        }
    }

    fun openAppSettings(activity: Activity) {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.parse("package:" + activity.packageName)
            activity.startActivity(i)
        } catch (t: Throwable) {
        }
    }

    // ---------------------------------------------------------------- root

    data class RootInfo(
        val available: Boolean,
        val flavor: String,
        val version: String,
        val raw: String
    )

    /** 检测 root 类型（Magisk / KernelSU），会实际执行 su，务必放后台线程。 */
    // su 调用很贵（要进 mount namespace，还可能弹授权框），结果缓存 60 秒
    @Volatile private var suCheckedAt = 0L
    @Volatile private var suAvailable = false

    /** su 是否可用（带超时 + 缓存，绝不能在主线程反复调用）。 */
    fun hasRoot(): Boolean {
        val now = System.currentTimeMillis()
        if (now - suCheckedAt in 1..60_000) return suAvailable
        suAvailable = runRoot("id", timeoutMs = 8000).contains("uid=0")
        suCheckedAt = now
        return suAvailable
    }

    fun invalidateRootCache() {
        suCheckedAt = 0L
    }

    fun detectRoot(): RootInfo {
        val idOut = runRoot("id", timeoutMs = 8000)
        if (!idOut.contains("uid=0")) {
            return RootInfo(false, "无", "", idOut.trim())
        }
        val info = runRoot(
            "echo MAGISK=\$(magisk -V 2>/dev/null); " +
                "echo KSU=\$(ksud -V 2>/dev/null || cat /data/adb/ksu/version 2>/dev/null); " +
                "echo ADB=\$(ls -d /data/adb 2>/dev/null)",
            timeoutMs = 10000
        )
        val magisk = Regex("MAGISK=([^\\s]+)").find(info)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val ksu = Regex("KSU=([^\\s]+)").find(info)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val flavor = when {
            ksu != null -> "KernelSU"
            magisk != null -> "Magisk"
            File("/data/adb/ksu").exists() -> "KernelSU"
            else -> "其他（SuperSU / 系统自带 su）"
        }
        val version = ksu ?: magisk ?: ""
        return RootInfo(true, flavor, version, info.trim())
    }

    fun runRoot(command: String, timeoutMs: Long = 20000): String {
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText()
            val done = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!done) {
                process.destroyForcibly()
                "命令超时"
            } else {
                text.trim()
            }
        } catch (t: Throwable) {
            "无法执行 su：" + (t.message ?: t.javaClass.simpleName)
        } finally {
            try {
                process?.destroy()
            } catch (t: Throwable) {
            }
        }
    }

    fun rootAddWhitelist(pkg: String): String =
        runRoot("dumpsys deviceidle whitelist +$pkg; cmd deviceidle whitelist +$pkg; echo DONE")

    fun rootRemoveWhitelist(pkg: String): String =
        runRoot("dumpsys deviceidle whitelist -$pkg; echo DONE")

    fun rootDisableDoze(): String = runRoot("dumpsys deviceidle disable; echo DONE")

    fun rootEnableDoze(): String = runRoot("dumpsys deviceidle enable; echo DONE")

    fun rootWifiAlwaysOn(): String = runRoot("settings put global wifi_sleep_policy 2; echo DONE")

    fun rootIsWhitelisted(pkg: String): Boolean =
        runRoot("dumpsys deviceidle whitelist | grep -q $pkg && echo YES || echo NO", 10000).contains("YES")

    /**
     * 写开机脚本，让白名单在重启后自动恢复。
     * Magisk 与 KernelSU 都支持 /data/adb/service.d 下的 shell 脚本（开机 late_start 阶段执行）。
     */
    fun installBootScript(ctx: Context, pkg: String, disableDoze: Boolean): String {
        val script = buildString {
            append("#!/system/bin/sh\n")
            append("# 由 MC 局域网开服 v").append(Constants.VERSION).append(" 生成\n")
            append("sleep 25\n")
            append("dumpsys deviceidle whitelist +").append(pkg).append(" >/dev/null 2>&1\n")
            append("cmd deviceidle whitelist +").append(pkg).append(" >/dev/null 2>&1\n")
            append("settings put global wifi_sleep_policy 2 >/dev/null 2>&1\n")
            if (disableDoze) append("dumpsys deviceidle disable >/dev/null 2>&1\n")
        }
        val tmp = File(ctx.cacheDir, "mclan_keepalive.sh")
        try {
            tmp.writeText(script, Charsets.UTF_8)
        } catch (t: Throwable) {
            return "无法写入临时脚本：" + t.message
        }
        val out = runRoot(
            "mkdir -p /data/adb/service.d; " +
                "cp ${tmp.absolutePath} /data/adb/service.d/mclan_keepalive.sh; " +
                "chmod 755 /data/adb/service.d/mclan_keepalive.sh; " +
                "ls -l /data/adb/service.d/mclan_keepalive.sh",
            timeoutMs = 20000
        )
        tmp.delete()
        return out
    }

    fun removeBootScript(): String =
        runRoot("rm -f /data/adb/service.d/mclan_keepalive.sh; echo DONE")

    // ---------------------------------------------------------------- SELinux

    /** 当前强制模式（enforcing / permissive）。 */
    fun selinuxState(): String = runRoot("getenforce", timeoutMs = 8000).trim()

    /**
     * 临时把 SELinux 设成宽容模式。只有这样才能让 root 执行 App 私有目录里的 java。
     * 风险极大：整机内核级访问控制失效，重启后多数机型会恢复。
     */
    fun setSelinuxPermissive(): String =
        runRoot("getenforce; setenforce 0 2>&1; getenforce", timeoutMs = 15000)

    /** 恢复强制模式。 */
    fun restoreSelinuxEnforcing(): String =
        runRoot("setenforce 1 2>&1; getenforce", timeoutMs = 15000)
    /** 打开 root 管理器（方便用户确认授权）。 */
    fun openRootManager(activity: Activity) {
        val pkgs = listOf(
            "com.topjohnwu.magisk",
            "me.weishu.kernelsu",
            "com.rifsxd.ksunext"
        )
        var ok = false
        for (p in pkgs) {
            try {
                activity.startActivity(activity.packageManager.getLaunchIntentForPackage(p))
                ok = true
                break
            } catch (t: Throwable) {
            }
        }
        if (!ok) openAppSettings(activity)
    }

    // ---------------------------------------------------------------- adb

    fun adbCommands(pkg: String): String = buildString {
        append("# 1) 加入 Doze 白名单（重启后仍有效）\n")
        append("adb shell dumpsys deviceidle whitelist +").append(pkg).append("\n\n")
        append("# 2) 设为活跃应用，不受后台限制\n")
        append("adb shell am set-standby-bucket ").append(pkg).append(" active\n\n")
        append("# 3) 可选：临时关闭 Doze（重启恢复）\n")
        append("adb shell dumpsys deviceidle disable\n\n")
        append("# 4) 检查结果\n")
        append("adb shell dumpsys deviceidle whitelist | findstr ").append(pkg)
    }

    fun copyToClipboard(ctx: Context, label: String, text: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        } catch (t: Throwable) {
        }
    }

    // ---------------------------------------------------------------- 文案

    fun statusText(ctx: Context): String {
        val ignoring = isIgnoringBatteryOptimizations(ctx)
        val running = ServerProcess.current?.isRunning() == true
        return buildString {
            append("服务端进程：").append(if (running) "运行中" else "未运行").append('\n')
            append("电池优化白名单：").append(if (ignoring) "已加入 ✅" else "未加入（熄屏会被 Doze 限网）")
            append('\n').append("root 保活：").append(if (AppPrefs.rootKeepAlive()) "已开启" else "未开启")
            append('\n').append("服务端以 root 运行：").append(if (AppPrefs.rootRunServer()) "是（高风险）" else "否")
        }
    }

    fun riskText(): String = buildString {
        append("开启 root 保活会做这些事：\n")
        append("• 执行 su 命令，把本应用加入 Doze 白名单\n")
        append("• 可选：全局关闭 Doze（影响所有应用）\n")
        append("• 写入开机脚本 /data/adb/service.d/mclan_keepalive.sh\n\n")
        append("可能的风险：\n")
        append("• Doze 被全局关闭后，其它应用的待机耗电会上升\n")
        append("• 授予 root 的应用一旦被利用漏洞，等于整机失守\n")
        append("• 系统更新、刷机、更换 root 管理器后需要重新执行\n")
        append("• 部分银行的 App、游戏反作弊会检测 root 环境\n\n")
        append("这些都可以随时在应用里关闭（删除开机脚本 + 恢复 Doze）。")
    }

    fun deviceNote(): String = buildString {
        append("厂商省电策略也会杀后台，需要手动放行：\n")
        append("· 小米/红米（MIUI、HyperOS）：应用管理 → MC局域网开服 → 省电策略「无限制」+ 自启动\n")
        append("· 华为/荣耀：应用启动管理 → 关闭自动管理，三项全开\n")
        append("· OPPO/vivo：电池 → 应用耗电管理 → 允许后台运行\n")
    }

    fun suPath(): String? {
        for (p in listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")) {
            if (File(p).exists()) return p
        }
        return null
    }
}
