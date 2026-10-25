package com.mclan.server.download

import org.json.JSONObject

/** Mojang 官方接口（与启动器使用的是同一套）。 */
object PistonApi {

    data class Release(val id: String, val type: String, val url: String, val releaseTime: String)

    data class ServerFile(
        val version: String,
        val url: String,
        val sha1: String,
        val size: Long,
        val javaMajor: Int
    )

    private var cachedManifest: JSONObject? = null

    fun manifest(): JSONObject {
        cachedManifest?.let { return it }
        val obj = Http.getJson("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
        cachedManifest = obj
        return obj
    }

    fun latestRelease(): String = manifest().getJSONObject("latest").getString("release")

    fun releases(limit: Int = 40): List<Release> {
        val versions = manifest().getJSONArray("versions")
        val out = ArrayList<Release>()
        for (i in 0 until versions.length()) {
            val v = versions.getJSONObject(i)
            if (v.getString("type") == "release") {
                out.add(
                    Release(
                        v.getString("id"),
                        v.getString("type"),
                        v.getString("url"),
                        v.optString("releaseTime")
                    )
                )
                if (out.size >= limit) break
            }
        }
        return out
    }

    private fun metaUrlOf(version: String?): Pair<String, String> {
        val versions = manifest().getJSONArray("versions")
        val wanted = if (version.isNullOrBlank() || version.equals("latest", true)) latestRelease() else version
        for (i in 0 until versions.length()) {
            val v = versions.getJSONObject(i)
            if (v.getString("id") == wanted) return wanted to v.getString("url")
        }
        throw java.io.IOException("找不到版本 $version")
    }

    fun serverFor(version: String?): ServerFile {
        val (id, metaUrl) = metaUrlOf(version)
        val meta = Http.getJson(metaUrl)
        val dl = meta.getJSONObject("downloads")
        // 老版本只有 server；1.21 之后同样有 server（bundler 也在这里）
        val server = dl.optJSONObject("server") ?: dl.optJSONObject("server_bundler")
            ?: throw java.io.IOException("该版本没有服务端下载")
        val javaMajor = meta.optJSONObject("javaVersion")?.optInt("majorVersion") ?: 0
        return ServerFile(
            id,
            server.getString("url"),
            server.getString("sha1"),
            server.optLong("size"),
            javaMajor
        )
    }

    fun resolveServer(version: String?): ServerFile = serverFor(version)
}
