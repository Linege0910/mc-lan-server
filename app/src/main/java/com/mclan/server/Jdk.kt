package com.mclan.server

import com.mclan.server.jdk.JdkCatalog
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 查找并校验运行服务端要用的 Java。
 *
 * 顺序：手动指定 → 已安装的对应主版本 → 任意已安装版本 → 空。
 * Android 自带运行时不是标准 OpenJDK，必须用 bionic 版 JDK（见 JdkCatalog）。
 */
object Jdk {

    fun candidates(): List<File> {
        val list = ArrayList<File>()
        val custom = AppPrefs.jdkPath()
        if (custom.isNotBlank()) {
            val f = File(custom)
            list.add(if (f.isDirectory) File(f, "bin/java") else f)
        }
        val major = AppPrefs.requiredJavaMajor()
        if (major > 0) list.add(JdkCatalog.javaOf(major))
        for (m in JdkCatalog.installedMajors()) list.add(JdkCatalog.javaOf(m))
        return list
    }

    fun find(): File? = candidates().firstOrNull { it.isFile && it.canExecute() }

    fun probe(bin: File, timeoutMs: Long = 30_000): String {
        return try {
            val pb = ProcessBuilder(bin.absolutePath, "-version")
            pb.redirectErrorStream(true)
            val home = bin.parentFile?.parentFile
            if (home != null) {
                pb.environment()["JAVA_HOME"] = home.absolutePath
                pb.environment()["LD_LIBRARY_PATH"] =
                    home.absolutePath + "/lib:" + home.absolutePath + "/lib/server"
            }
            val p = pb.start()
            val text = p.inputStream.bufferedReader().readText().trim()
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                return "超时（通常是 CPU 架构不匹配，需要 aarch64 版 JDK）"
            }
            if (p.exitValue() != 0) return text.ifBlank { "退出码 " + p.exitValue() }
            text.lineSequence().firstOrNull { it.contains("version", true) }
                ?: text.lineSequence().firstOrNull().orEmpty()
        } catch (t: Throwable) {
            "无法执行：" + (t.message ?: t.javaClass.simpleName)
        }
    }

    fun isUsable(bin: File): Boolean {
        val info = probe(bin)
        if (info.startsWith("无法执行") || info.startsWith("超时")) return false
        val major = Regex("(\\d+)").find(info)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return major >= 8
    }
}
