package com.mclan.server

import com.mclan.server.download.Http
import com.mclan.server.download.PistonApi
import com.mclan.server.jdk.JdkCatalog
import java.io.File

/**
 * 从官网获取服务端：
 *  · Java 版：piston-meta 版本清单 → piston-data 的 server.jar（SHA1 校验），并记下所需 Java 版本
 *  · 基岩版：官方 bedrock-server-<版本>.zip 直链（Mojang 只有 Windows / Linux x86_64）
 */
object ServerDownloader {

    data class Result(val version: String, val file: File, val javaMajor: Int)

    fun downloadJava(
        server: Server,
        version: String?,
        onProgress: (Long, Long, String) -> Unit
    ): Result {
        onProgress(0, -1, "读取版本清单…")
        val info = PistonApi.resolveServer(version)
        onProgress(0, -1, "准备下载 " + info.version)
        val tmp = File(server.dir, "server.jar.download")
        Http.download(info.url, tmp, info.sha1) { done, total -> onProgress(done, total, "下载 " + info.version) }
        if (server.jar.exists()) server.jar.delete()
        if (!tmp.renameTo(server.jar)) {
            tmp.copyTo(server.jar, overwrite = true)
            tmp.delete()
        }
        AppPrefs.setLastVersion(info.version)
        // 元数据优先；拿不到就按版本号推断
        val major = if (info.javaMajor > 0) info.javaMajor else JdkCatalog.requiredMajor(info.version)
        AppPrefs.setRequiredJavaMajor(major)
        return Result(info.version, server.jar, major)
    }

    fun bedrockLatest(onProgress: (String) -> Unit): Pair<String, String> {
        onProgress("查询版本列表…")
        val text = Http.getGithubFile("EndstoneMC/bedrock-server-data", "versions.json", "v2")
        val latest = org.json.JSONObject(text)
            .optJSONObject("release")
            ?.optString("latest")
            .orEmpty()
        if (latest.isBlank()) throw java.io.IOException("无法解析基岩版版本")
        onProgress("读取 " + latest + " 的下载地址…")
        val metaText = Http.getGithubFile(
            "EndstoneMC/bedrock-server-data",
            "release/" + latest + "/metadata.json",
            "v2"
        )
        val binary = org.json.JSONObject(metaText).getJSONObject("binary")
        val url = binary.optJSONObject("linux")?.optString("url").orEmpty()
        if (url.isBlank()) throw java.io.IOException("该版本没有 Linux 包")
        return latest to url
    }

    fun downloadBedrock(
        server: Server,
        version: String,
        url: String,
        onProgress: (Long, Long, String) -> Unit
    ): Result {
        val zip = File(server.dir, "bedrock-server.zip")
        Http.download(url, zip, null) { done, total -> onProgress(done, total, "下载基岩版 " + version) }
        try {
            unzip(zip, File(server.dir, "bedrock"))
        } catch (t: Throwable) {
        }
        return Result(version, zip, 0)
    }

    fun unzip(zip: File, dest: File) {
        dest.mkdirs()
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(dest, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it, 1 shl 16) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
