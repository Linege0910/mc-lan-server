package com.mclan.server

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * server.properties 的读写。Java 版是 key=value 文本，基岩版是 JSON 对象。
 */
object ServerConfig {

    private val JAVA_TEMPLATE = linkedMapOf(
        "motd" to "Minecraft 局域网服务器",
        "server-port" to "25565",
        "server-ip" to "",
        "max-players" to "20",
        "gamemode" to "survival",
        "difficulty" to "easy",
        "level-name" to "world",
        "level-seed" to "",
        "level-type" to "minecraft:normal",
        "online-mode" to "false",
        "white-list" to "false",
        "enforce-whitelist" to "false",
        "pvp" to "true",
        "allow-nether" to "true",
        "allow-flight" to "false",
        "spawn-protection" to "16",
        "view-distance" to "10",
        "simulation-distance" to "10",
        "max-world-size" to "29999984",
        "enable-command-block" to "false",
        "enable-status" to "true",
        "hide-online-players" to "false",
        "player-idle-timeout" to "0",
        "spawn-monsters" to "true",
        "spawn-animals" to "true",
        "spawn-npcs" to "true",
        "generate-structures" to "true",
        "force-gamemode" to "false",
        "broadcast-console-to-ops" to "true",
        "enable-rcon" to "false",
        "rcon.port" to "25575",
        "rcon.password" to "",
        "enable-query" to "false",
        "query.port" to "25565",
        "network-compression-threshold" to "256",
        "entity-broadcast-range-percentage" to "100",
        "max-tick-time" to "-1",
        "use-native-transport" to "true",
        "prevent-proxy-connections" to "false",
        "sync-chunk-writes" to "true",
        "debug" to "false"
    )

    private val BEDROCK_TEMPLATE = linkedMapOf(
        "server-name" to "Minecraft 基岩版局域网服务器",
        "gamemode" to "survival",
        "force-gamemode" to "false",
        "difficulty" to "normal",
        "allow-cheats" to "false",
        "max-players" to "10",
        "online-mode" to "true",
        "allow-list" to "false",
        "server-port" to "19132",
        "server-portv6" to "19133",
        "enable-lan-visibility" to "true",
        "view-distance" to "32",
        "tick-distance" to "4",
        "player-idle-timeout" to "30",
        "max-threads" to "8",
        "level-name" to "Bedrock level",
        "level-seed" to "",
        "default-player-permission-level" to "member",
        "texturepack-required" to "false",
        "compression-threshold" to "1",
        "client-side-chunk-generation-enabled" to "true"
    )

    fun template(server: Server) = if (server.type == Server.BEDROCK) BEDROCK_TEMPLATE else JAVA_TEMPLATE

    fun ensurePropertiesFile(server: Server) {
        if (server.props.isFile) return
        server.dir.mkdirs()
        val tpl = template(server)
        if (server.type == Server.BEDROCK) {
            val obj = JSONObject()
            // 基岩版要求类型正确，布尔/整数不能写成字符串
            for ((k, v) in tpl) {
                when {
                    v == "true" || v == "false" -> obj.put(k, v.toBoolean())
                    k == "max-players" || k == "server-port" || k == "server-portv6" ||
                        k == "view-distance" || k == "tick-distance" || k == "player-idle-timeout" ||
                        k == "max-threads" || k == "compression-threshold" -> obj.put(k, v.toIntOrNull() ?: 0)
                    else -> obj.put(k, v)
                }
            }
            server.props.writeText(obj.toString(2) + "\n", Charsets.UTF_8)
        } else {
            val sb = StringBuilder()
            sb.append("#Minecraft server properties\n")
            sb.append("#由 MC局域网开服 于 ")
                .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                .append(" 生成\n")
            for ((k, v) in tpl) sb.append(k).append('=').append(v).append('\n')
            server.props.writeText(sb.toString(), Charsets.UTF_8)
        }
    }

    /** key → value 列表；基岩版从 JSON 展开。 */
    fun read(server: Server): List<Pair<String, String>> = try {
        readUnsafe(server)
    } catch (t: Throwable) {
        emptyList()
    }

    private fun readUnsafe(server: Server): List<Pair<String, String>> {
        ensurePropertiesFile(server)
        return if (server.type == Server.BEDROCK) {
            try {
                val obj = JSONObject(server.props.readText(Charsets.UTF_8))
                val out = ArrayList<Pair<String, String>>()
                for (k in obj.keys()) out.add(k to obj.get(k).toString())
                out
            } catch (t: Throwable) {
                emptyList()
            }
        } else {
            server.props.readLines(Charsets.UTF_8)
                .filter { it.isNotBlank() && !it.trimStart().startsWith("#") && it.contains('=') }
                .map { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        }
    }

    /** 只改一个键，保留文件其余内容（含注释）。 */
    fun put(server: Server, key: String, value: String) {
        ensurePropertiesFile(server)
        if (server.type == Server.BEDROCK) {
            val obj = try {
                JSONObject(server.props.readText(Charsets.UTF_8))
            } catch (t: Throwable) {
                JSONObject()
            }
            val typed: Any = when {
                value == "true" || value == "false" -> value.toBoolean()
                value.toIntOrNull() != null && looksNumeric(key) -> value.toInt()
                else -> value
            }
            obj.put(key, typed)
            server.props.writeText(obj.toString(2) + "\n", Charsets.UTF_8)
        } else {
            val lines = server.props.readLines(Charsets.UTF_8).toMutableList()
            var done = false
            for (i in lines.indices) {
                if (lines[i].trimStart().startsWith("$key=")) {
                    lines[i] = "$key=$value"
                    done = true
                    break
                }
            }
            if (!done) lines.add("$key=$value")
            server.props.writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        }
    }

    private fun looksNumeric(key: String): Boolean = key in setOf(
        "server-port", "server-portv6", "max-players", "view-distance", "tick-distance",
        "player-idle-timeout", "max-threads", "compression-threshold", "spawn-protection",
        "max-world-size", "network-compression-threshold", "entity-broadcast-range-percentage",
        "max-tick-time", "rcon.port", "query.port"
    )

    fun raw(server: Server): String {
        ensurePropertiesFile(server)
        return try {
            server.props.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            ""
        }
    }

    fun saveRaw(server: Server, text: String) {
        server.dir.mkdirs()
        server.props.writeText(text, Charsets.UTF_8)
    }

    fun backup(server: Server): File {
        val dst = File(server.dir, "server.properties.bak")
        if (server.props.isFile) server.props.copyTo(dst, overwrite = true)
        return dst
    }
}
