package com.mclan.server.jdk

import com.mclan.server.AppPrefs
import com.mclan.server.Jdk
import com.mclan.server.Paths
import com.mclan.server.Storage
import com.mclan.server.download.Http
import java.io.File
import java.io.IOException

/**
 * 在手机上自动获取 Java 运行时（bionic / aarch64，Termux 官方仓库的 openjdk 包）。
 *
 * 为什么用 Termux 的包：Android 用的是 bionic libc，Adoptium 那种 glibc 版 JDK 根本链接不上；
 * Termux 的 openjdk-17/21/25 是唯一现成的、能在这颗 ARM 芯片上跑的 JDK。
 *
 * 服务的版本与所需 Java：
 *   ≤1.16.5 → 8（Termux 已不再提供，只能用 17 代跑或另找）
 *   1.17–1.20.4 → 17
 *   1.20.5–1.21.x → 21
 *   1.22 / 26.x（含最新 26.3）→ 25
 */
object JdkCatalog {

    data class Build(val major: Int, val version: String, val debPath: String)

    /** 官方源很慢，国内镜像快得多，按可用性依次尝试。 */
    /** 官方源很慢，国内镜像快得多，按可用性依次尝试。 */
    private val MIRRORS = listOf(
        "https://mirrors.nju.edu.cn/termux/apt/termux-main",
        "https://mirrors.ustc.edu.cn/termux/apt/termux-main",
        "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main",
        "https://packages.termux.dev/apt/termux-main"
    )

    // 依赖直接读 deb 的 Depends 字段（见 installDependencies）；
    // 下面这些只跟声音/图形有关，服务端用不上，跳过以免多下十几个包。
    private val SKIP_DEPS = setOf(
        "alsa-plugins", "cups", "libx11", "libxext", "libxrender", "libxcb",
        "libxau", "libxdmcp", "libglvnd", "ca-certificates-java"
    )
    private var indexText: String? = null

    /** 可执行的 JDK 在应用私有目录（/sdcard 是 noexec，不能从那里执行） */
    fun installDir(major: Int): File = Paths.execJdkDir(major)

    fun javaOf(major: Int): File = File(installDir(major), "bin/java")

    fun isInstalled(major: Int): Boolean {
        val j = javaOf(major)
        return j.isFile && j.canExecute()
    }

    fun installedMajors(): List<Int> = listOf(8, 17, 21, 25).filter { isInstalled(it) }

    /** 名称与用途，供界面展示。 */
    fun desc(major: Int): String = when (major) {
        8 -> "Java 8 · 1.16.5 及更早"
        17 -> "Java 17 · 1.17 – 1.20.4"
        21 -> "Java 21 · 1.20.5 – 1.21.x"
        25 -> "Java 25 · 1.22 / 26.x（含 26.3）"
        else -> "Java $major"
    }

    /** 根据服务端版本号推断需要的 Java 主版本。 */
    fun requiredMajor(serverVersion: String): Int {
        val v = serverVersion.trim().removePrefix("r")
        val parts = v.split('.', '-', '+')
        val a = parts.getOrNull(0)?.toIntOrNull() ?: return 21
        val b = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return when {
            a >= 22 -> 25
            a == 1 -> when {
                b >= 21 -> 21
                b >= 17 -> 17
                else -> 8
            }
            else -> 21
        }
    }

    fun resolve(major: Int): Build {
        val text = indexText ?: loadIndex().also { indexText = it }
        for (stanza in text.split("\n\n")) {
            if (!stanza.contains("Package: openjdk-$major\n") && !stanza.startsWith("Package: openjdk-$major")) continue
            val version = Regex("(?m)^Version: (.+)$").find(stanza)?.groupValues?.get(1)?.trim() ?: continue
            val path = Regex("(?m)^Filename: (.+)$").find(stanza)?.groupValues?.get(1)?.trim() ?: continue
            return Build(major, version, path)
        }
        throw IOException(
            "Termux 仓库没有 openjdk-$major（Android 上现成可用的只有 17 / 21 / 25）"
        )
    }

    private fun loadIndex(): String {
        var last: Throwable? = null
        for (m in MIRRORS) {
            try {
                return Http.getText("$m/dists/stable/main/binary-aarch64/Packages")
            } catch (t: Throwable) {
                last = t
            }
        }
        throw IOException("获取 JDK 索引失败：" + (last?.message ?: "网络不可用"))
    }

    /** 下载并安装；返回 bin/java。 */
    fun install(major: Int, onProgress: (Long, Long, String) -> Unit): File {
        val build = resolve(major)
        val deb = File(Storage.downloads(), "openjdk-$major.deb")

        onProgress(0, -1, "准备下载 openjdk-$major（约 110MB）…")
        downloadFromMirrors(build.debPath, deb) { done, total, label ->
            onProgress(done, total, label)
        }

        onProgress(0, -1, "解压 openjdk-$major…")
        val staging = File(Paths.appDir(), "jdk-$major.tmp")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()
        var extracted = 0
        DebExtractor.extract(deb, staging, true, object : DebExtractor.Progress {
            override fun onEntry(path: String) {
                extracted++
                if (extracted % 40 == 0) onProgress(0, -1, "解压中… 已写出 " + extracted + " 个文件")
            }
        })

        // deb 里的布局是 usr/lib/jvm/java-N-openjdk/，把它摊平到 jdk-N/
        val home = findJdkHome(staging) ?: throw IOException("包内没有找到 bin/java")
        val dest = installDir(major)
        if (dest.exists()) dest.deleteRecursively()
        if (!home.renameTo(dest)) {
            home.copyRecursively(dest, overwrite = true)
            home.deleteRecursively()
        }
        staging.deleteRecursively()

        onProgress(0, -1, "补齐运行依赖库…")
        // JDK 的 control 里写明了依赖：libandroid-shmem / libandroid-spawn / libiconv /
        // libjpeg-turbo / zlib / littlecms / alsa-plugins。libjli.so 必须要 zlib 提供的
        // libz.so.1（Android 系统只有 libz.so），缺了就会 "CANNOT LINK EXECUTABLE"。
        installDependencies(deb, major, dest) { name ->
            onProgress(0, -1, "安装依赖 $name…")
        }

        chmodAll(dest)
        deb.delete()

        val java = File(dest, "bin/java")
        if (!java.isFile) throw IOException("安装后没有 bin/java")
        val probe = Jdk.probe(java)
        if (!probe.contains("version", true)) {
            throw IOException("JDK 已解压但无法运行：\n$probe")
        }

        // 用户要求：在「内部存储/mcserver/java」里也留一份，文件管理器能直接看到
        onProgress(0, -1, "复制到 " + Storage.root().name + "/java …")
        try {
            val visible = Storage.jdkMirror(major)
            if (visible.exists()) visible.deleteRecursively()
            Storage.copyTree(dest, visible) { bytes ->
                onProgress(bytes, -1, "复制到可见目录")
            }
        } catch (t: Throwable) {
            android.util.Log.w("MCLanJdk", "copy to visible dir failed: ${t.message}")
        }

        AppPrefs.setRequiredJavaMajor(major)
        return java
    }

    private fun downloadFromMirrors(debPath: String, out: File, onProgress: (Long, Long, String) -> Unit) {
        var last: Throwable? = null
        for ((i, m) in MIRRORS.withIndex()) {
            val url = "$m/$debPath"
            try {
                onProgress(0, -1, "使用镜像 ${i + 1}/${MIRRORS.size}")
                Http.download(url, out, null) { done, total -> onProgress(done, total, "下载中") }
                return
            } catch (t: Throwable) {
                last = t
                out.delete()
            }
        }
        throw IOException("所有镜像都下载失败：" + (last?.message ?: "未知错误"))
    }

    /**
     * 递归安装 deb 的 Depends（两三层就够：Termux 的原生库之间依赖很浅）。
     * 装完把它们的共享库放进 JDK 的 lib 目录，运行时靠 LD_LIBRARY_PATH 找到。
     */
    private fun installDependencies(
        jdkDeb: File,
        major: Int,
        jdkHome: File,
        onStep: (String) -> Unit
    ) {
        val done = HashSet<String>()
        var level = DebExtractor.parseDepends(DebExtractor.readControl(jdkDeb))
            .filterNot { it in SKIP_DEPS }
        var depth = 0
        while (level.isNotEmpty() && depth < 4) {
            val next = ArrayList<String>()
            for (name in level) {
                if (!done.add(name)) continue
                onStep(name)
                try {
                    val path = findPackagePath(name) ?: continue
                    val deb = File(Storage.downloads(), "$name.deb")
                    downloadFromMirrors(path, deb) { _, _, _ -> }
                    DebExtractor.extract(deb, jdkHome)
                    // 这些依赖自己的 Depends 也装一层
                    next.addAll(DebExtractor.parseDepends(DebExtractor.readControl(deb)))
                    deb.delete()
                } catch (t: Throwable) {
                    android.util.Log.w("MCLanJdk", "依赖 $name 安装失败：${t.message}")
                }
            }
            level = next.filter { it !in done && it !in SKIP_DEPS }
            depth++
        }
        // 关键自检：libjli.so 依赖 libz.so.1，缺了就直接报清楚
        val libz = File(jdkHome, "lib/libz.so.1")
        if (!libz.isFile) {
            android.util.Log.w("MCLanJdk", "警告：没有拿到 libz.so.1，JDK 可能无法启动")
        }
    }

    private fun findPackagePath(name: String): String? {
        val text = indexText ?: loadIndex().also { indexText = it }
        for (stanza in text.split("\n\n")) {
            val pkg = Regex("(?m)^Package: (.+)$").find(stanza)?.groupValues?.get(1)?.trim() ?: continue
            if (pkg != name) continue
            return Regex("(?m)^Filename: (.+)$").find(stanza)?.groupValues?.get(1)?.trim()
        }
        return null
    }

    private fun findJdkHome(root: File): File? {
        val direct = File(root, "bin/java")
        if (direct.isFile) return root
        root.walkTopDown().forEach { f ->
            if (f.isFile && f.name == "java" && f.parentFile?.name == "bin") {
                return f.parentFile!!.parentFile!!
            }
        }
        return null
    }

    private fun chmodAll(dir: File) {
        dir.walkTopDown().forEach { f ->
            if (f.isFile) {
                val exe = f.parentFile?.name == "bin" || f.name.endsWith(".so") || f.name == "jspawnhelper"
                try {
                    f.setReadable(true, false)
                    if (exe) f.setExecutable(true, false)
                } catch (t: Throwable) {
                }
                if (exe) {
                    try {
                        android.system.Os.chmod(f.absolutePath, 493)
                    } catch (t: Throwable) {
                    }
                }
            }
        }
    }
}
