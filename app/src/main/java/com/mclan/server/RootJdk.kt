package com.mclan.server

import java.io.File

/**
 * root 后台模式专用 JDK。
 *
 * 来源必须是 /storage 里那份「可见镜像」java-N：
 *  · root 能读它（App 私有目录 root 读不了，列目录会被 Magisk 拒绝）
 *  · 复制到 /data/local/tmp/mclan 之后，root 才能执行
 * 反过来（从 App 私有目录复制）实测被 Magisk 的能力集拦死，怎么改都不行。
 */
object RootJdk {

    const val BASE = "/data/local/tmp/mclan"

    private fun q(s: String): String = "\u0027" + s.replace("\u0027", "\u0027\\\u0027\u0027") + "\u0027"

    /** 从 bin/java 路径推断主版本，例如 .../jdk-25/bin/java -> 25 */
    private fun majorOf(sourceJava: File): Int {
        val home = sourceJava.parentFile?.parentFile?.name ?: return 0
        return home.removePrefix("jdk-").toIntOrNull() ?: 0
    }

    fun destFor(major: Int): File = File(BASE, "jdk-$major")

    /** 准备一份 root 能执行的 JDK，返回它的 bin/java；失败返回 null。 */
    fun ensure(sourceJava: File, log: (String) -> Unit): File? {
        if (!KeepAlive.hasRoot()) {
            log("[root] 没有 root 权限")
            return null
        }
        val major = majorOf(sourceJava)
        if (major <= 0) {
            log("[root] 无法识别 JDK 版本目录：" + sourceJava.absolutePath)
            return null
        }
        val mirror = Storage.jdkMirror(major)
        val dest = destFor(major)
        val destJava = File(dest, "bin/java")
        val mirrorJava = File(mirror, "bin/java")

        if (destJava.isFile && mirrorJava.isFile && destJava.length() == mirrorJava.length() && destJava.length() > 0) {
            log("[root] 复用已同步的运行时：" + dest.absolutePath)
            return destJava
        }
        if (!mirrorJava.isFile) {
            log("[root] 找不到可见的 JDK 镜像：" + mirror.absolutePath + "（请先安装 Java " + major + "）")
            return null
        }

        log("[root] 同步 Java " + major + " 到 " + dest.absolutePath + "（约 250MB，首次十几秒）")
        val out = KeepAlive.runRoot(
            "rm -rf " + q(dest.absolutePath) + "; " +
                "mkdir -p " + q(BASE) + "; " +
                "cp -r " + q(mirror.absolutePath) + " " + q(dest.absolutePath) + "; " +
                "chmod -R 755 " + q(dest.absolutePath) + "; " +
                "ls " + q(destJava.absolutePath) + " 2>&1",
            timeoutMs = 300_000
        )
        val ok = destJava.isFile
        log("[root] 同步结果：" + if (ok) "成功" else out.lineSequence().lastOrNull().orEmpty().take(200))
        return if (ok) destJava else null
    }

    fun remove(): String = KeepAlive.runRoot("rm -rf " + q(BASE) + "; echo DONE", timeoutMs = 60_000)
}
