package com.mclan.server

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * 用户可见的数据目录，固定为 `/storage/emulated/0/mcserver`。
 *
 * 目录结构：
 *   mcserver/server.jar、server.properties、eula.txt、world/、logs/、console.log
 *   mcserver/java-17 / java-21 / java-25      各版本 Java 副本
 *   mcserver/bedrock/                          基岩版官方包
 *   mcserver/downloads/                        下载缓存
 *
 * 注意：`/sdcard` 是 noexec 挂载，程序不能从这里执行；
 * 所以 Java 在这里留一份给用户看/备份，真正执行用应用私有目录里的副本。
 */
object Storage {

    const val PUBLIC_ROOT = "/storage/emulated/0/mcserver"
    const val REQ_STORAGE = 1001

    private var cachedRoot: File? = null

    fun reset() {
        cachedRoot = null
    }

    fun root(): File {
        cachedRoot?.let { return it }
        val candidates = ArrayList<File>()
        // 用户指定的固定路径优先
        candidates.add(File(PUBLIC_ROOT))
        try {
            Environment.getExternalStorageDirectory()?.let { candidates.add(File(it, "mcserver")) }
        } catch (t: Throwable) {
        }
        candidates.add(File("/sdcard/mcserver"))

        var chosen: File? = null
        for (c in candidates) {
            try {
                c.mkdirs()
                val probe = File(c, ".writable")
                probe.writeText("ok")
                probe.delete()
                chosen = c
                break
            } catch (t: Throwable) {
            }
        }
        val dir = chosen ?: File(Paths.appDir(), "mcserver").apply { mkdirs() }
        cachedRoot = dir
        return dir
    }

    /** 当前是否真的落在 /storage/emulated/0/mcserver。 */
    fun isPublicDir(): Boolean = root().absolutePath.startsWith("/storage/emulated/0/")

    fun javaServerDir(): File = root().apply { mkdirs() }
    fun bedrockDir(): File = File(root(), "bedrock").apply { mkdirs() }
    fun downloads(): File = File(root(), "downloads").apply { mkdirs() }
    fun logs(): File = File(root(), "logs").apply { mkdirs() }
    fun jdkMirror(major: Int): File = File(root(), "java-$major")

    /** 真正写一次，比查权限位可靠。 */
    fun isWritable(): Boolean {
        return try {
            val f = File(root(), ".w")
            f.writeText("1")
            f.delete()
            true
        } catch (t: Throwable) {
            false
        }
    }

    // ---------------------------------------------------------------- 权限

    fun hasLegacyPermission(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Android 11+ 的「所有文件访问权限」。 */
    fun hasAllFilesAccess(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return true
        return try {
            Environment.isExternalStorageManager()
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 是否能弹运行时权限框。
     * 不设上限：Android 13/14 对 targetSdk≤32 的应用仍会弹 WRITE_EXTERNAL_STORAGE；
     * 万一系统直接拒绝，onRequestPermissionsResult 里会再引导「所有文件访问」。
     */
    fun canAskRuntime(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < 23) return false
        return !hasLegacyPermission(activity)
    }

    /**
     * 申请权限。顺序：
     *   1. Android 6–12：弹 WRITE_EXTERNAL_STORAGE 运行时权限
     *   2. Android 11+：引导「所有文件访问权限」页
     *   3. 兜底：打开本应用的系统设置页
     */
    fun requestPermission(activity: Activity) {
        if (canAskRuntime(activity)) {
            activity.requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                REQ_STORAGE
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 30 && !hasAllFilesAccess()) {
            openAllFilesSettings(activity)
            return
        }
        openAppSettings(activity)
    }

    fun openAllFilesSettings(activity: Activity) {
        try {
            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            i.data = Uri.parse("package:" + activity.packageName)
            activity.startActivity(i)
        } catch (t: Throwable) {
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (t2: Throwable) {
                openAppSettings(activity)
            }
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

    fun stateText(ctx: Context): String = buildString {
        append("目标目录：").append(PUBLIC_ROOT).append('\n')
        append("实际目录：").append(root().absolutePath).append('\n')
        append("可写入：").append(if (isWritable()) "是" else "否").append('\n')
        append("存储权限：").append(if (hasLegacyPermission(ctx)) "已授予" else "未授予")
        if (Build.VERSION.SDK_INT >= 30) {
            append('\n').append("所有文件访问：").append(if (hasAllFilesAccess()) "已授予" else "未授予")
        }
    }

    fun copyTree(src: File, dst: File, onBytes: ((Long) -> Unit)? = null) {
        dst.mkdirs()
        var total = 0L
        src.walkTopDown().forEach { f ->
            if (f.isFile) {
                val target = File(dst, f.relativeTo(src).path)
                target.parentFile?.mkdirs()
                f.inputStream().use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            total += n
                            onBytes?.invoke(total)
                        }
                    }
                }
            }
        }
    }
}
