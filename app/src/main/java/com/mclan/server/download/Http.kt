package com.mclan.server.download

import org.json.JSONObject
import java.io.File
import java.util.Base64

/** 极简 HTTP：跟随重定向、可断点续传、带 SHA1 校验。 */
object Http {

    private const val UA = "Mozilla/5.0 (Linux; Android 14) MCLanServer/1.0"

    fun open(url: String, rangeFrom: Long = -1, timeoutMs: Int = 30_000): java.net.HttpURLConnection {
        var current = url
        var hops = 0
        while (true) {
            val conn = java.net.URL(current).openConnection() as java.net.HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = timeoutMs
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", UA)
            if (rangeFrom > 0) conn.setRequestProperty("Range", "bytes=" + rangeFrom + "-")
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw java.io.IOException("重定向缺少 Location")
                conn.disconnect()
                if (++hops > 8) throw java.io.IOException("重定向次数过多")
                current = if (loc.startsWith("http")) loc else java.net.URL(java.net.URL(current), loc).toString()
                continue
            }
            if (code >= 400) {
                conn.disconnect()
                throw java.io.IOException("HTTP " + code + "：" + url)
            }
            return conn
        }
    }

    fun getText(url: String): String {
        val conn = open(url)
        try {
            return conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
            conn.disconnect()
        }
    }

    fun getJson(url: String): JSONObject = JSONObject(getText(url))

    /** GitHub raw 偶尔不可用，这里回退到 API 的 base64 内容接口。 */
    fun getGithubFile(ownerRepo: String, path: String, ref: String): String {
        return try {
            getText("https://raw.githubusercontent.com/" + ownerRepo + "/" + ref + "/" + path)
        } catch (t: Throwable) {
            val api = "https://api.github.com/repos/" + ownerRepo + "/contents/" + path + "?ref=" + ref
            val obj = getJson(api)
            val b64 = obj.optString("content").replace("\n", "")
            if (b64.isBlank()) throw java.io.IOException("无法读取 " + path)
            String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
        }
    }

    fun download(url: String, target: File, sha1: String?, onProgress: (Long, Long) -> Unit) {
        val tmp = File(target.absolutePath + ".part")
        var have = if (tmp.isFile) tmp.length() else 0L
        if (tmp.isFile && have == 0L) have = 0L
        val conn = open(url, if (have > 0) have + 1 else -1)
        val total = if (conn.responseCode == 206) have + conn.contentLengthLong else conn.contentLengthLong
        conn.inputStream.use { input ->
            java.io.FileOutputStream(tmp, have > 0).use { out ->
                val buf = ByteArray(1 shl 16)
                var pos = have
                var last = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    pos += n
                    val now = System.currentTimeMillis()
                    if (now - last > 250) {
                        last = now
                        onProgress(pos, total)
                    }
                }
                out.flush()
            }
        }
        conn.disconnect()
        if (sha1 != null) {
            val actual = sha1Of(tmp)
            if (!actual.equals(sha1, true)) {
                tmp.delete()
                throw java.io.IOException("SHA1 校验失败（期望 " + sha1 + "，实际 " + actual + "）")
            }
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        onProgress(target.length(), maxOf(total, target.length()))
    }

    fun sha1Of(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val sb = StringBuilder()
        for (b in md.digest()) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
