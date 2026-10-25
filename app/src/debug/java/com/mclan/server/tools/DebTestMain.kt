package com.mclan.server.tools

import com.mclan.server.download.Http
import com.mclan.server.jdk.DebExtractor
import java.io.File

/**
 * 本机验证：用应用里同一份代码解 JDK deb，按 control 的 Depends 递归装依赖，
 * 最后检查 libjli.so 需要的 libz.so.1 是否落地。
 *
 * 运行：MCLAN_TEST_DEB=<openjdk deb> ./gradlew :app:verifyDeb
 */
object DebTestMain {

    private val MIRRORS = listOf(
        "https://mirrors.ustc.edu.cn/termux/apt/termux-main",
        "https://mirrors.nju.edu.cn/termux/apt/termux-main",
        "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"
    )

    private val SKIP = setOf(
        "alsa-plugins", "cups", "libx11", "libxext", "libxrender", "libxcb",
        "libxau", "libxdmcp", "libglvnd", "ca-certificates-java"
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val deb = File(args[0])
        val out = File(args[1])
        if (out.exists()) out.deleteRecursively()
        out.mkdirs()

        val t0 = System.currentTimeMillis()
        DebExtractor.extract(deb, out)
        println("解压 JDK 用时 " + (System.currentTimeMillis() - t0) + "ms, 文件数 " + out.walkTopDown().count { it.isFile })

        val home = findHome(out) ?: error("没有找到 bin/java")
        println("JDK 根目录 " + home.relativeTo(out).path)
        println("bin/java " + File(home, "bin/java").length() + " bytes")

        val cache = File(out.parentFile, "debcache").apply { mkdirs() }
        val deps = DebExtractor.parseDepends(DebExtractor.readControl(deb))
        println("control Depends = " + deps)

        val done = HashSet<String>()
        var level = deps.filterNot { it in SKIP }
        var depth = 0
        while (level.isNotEmpty() && depth < 3) {
            val next = ArrayList<String>()
            for (name in level) {
                if (!done.add(name)) continue
                val path = findPackagePath(name, cache)
                if (path == null) {
                    println("  跳过 " + name + "（索引里没有）")
                    continue
                }
                val depDeb = File(cache, name + ".deb")
                download(path, depDeb)
                DebExtractor.extract(depDeb, home)
                next.addAll(DebExtractor.parseDepends(DebExtractor.readControl(depDeb)))
                println("  已装 " + name)
                depDeb.delete()
            }
            level = next.filter { it !in done && it !in SKIP }
            depth++
        }

        val libz = File(home, "lib/libz.so.1")
        val jli = File(home, "lib/libjli.so")
        val jvm = File(home, "lib/server/libjvm.so")
        println("libz.so.1 = " + libz.isFile + " (" + libz.length() + " bytes)")
        println("libjli.so = " + jli.isFile + ", libjvm.so = " + jvm.isFile)
        println(if (libz.isFile) "结论: 依赖完整 OK" else "结论: 仍缺 libz.so.1 FAIL")
    }

    private fun download(path: String, out: File) {
        var last: Throwable? = null
        for (m in MIRRORS) {
            try {
                Http.download("$m/$path", out, null) { _, _ -> }
                return
            } catch (t: Throwable) {
                last = t
                out.delete()
            }
        }
        throw java.io.IOException("下载失败 " + path + " : " + (last?.message ?: ""))
    }

    private fun findHome(root: File): File? {
        if (File(root, "bin/java").isFile) return root
        var found: File? = null
        root.walkTopDown().forEach { f ->
            if (found == null && f.isFile && f.name == "java" && f.parentFile?.name == "bin") {
                found = f.parentFile?.parentFile
            }
        }
        return found
    }

    private fun findPackagePath(name: String, cache: File): String? {
        val idx = File(cache, "Packages")
        if (!idx.isFile) {
            download("dists/stable/main/binary-aarch64/Packages", idx)
        }
        for (stanza in idx.readText(Charsets.UTF_8).split("\n\n")) {
            val pkg = Regex("(?m)^Package: (.+)$").find(stanza)?.groupValues?.get(1)?.trim() ?: continue
            if (pkg != name) continue
            return Regex("(?m)^Filename: (.+)$").find(stanza)?.groupValues?.get(1)?.trim()
        }
        return null
    }
}
