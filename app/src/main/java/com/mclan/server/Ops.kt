package com.mclan.server

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 给玩家授 OP。
 *
 * 后台守护模式下应用无法给服务端发命令（没有 stdin），
 * 所以关服只能靠玩家在游戏里执行 /stop —— 前提是他有 OP。
 * 这里直接写 ops.json（离线模式的 UUID 由用户名推出来，和原版一致）。
 */
object Ops {

    fun offlineUuid(name: String): String {
        val bytes = ("OfflinePlayer:" + name).toByteArray(Charsets.UTF_8)
        return UUID.nameUUIDFromBytes(bytes).toString()
    }

    /** 写入 ops.json，返回一行给界面看的结果。 */
    fun grant(server: Server, name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "玩家名不能为空"
        if (trimmed.length > 16) return "玩家名最长 16 个字符"
        val file = File(server.dir, "ops.json")
        val array: JSONArray = try {
            if (file.isFile && file.length() > 2) JSONArray(file.readText(Charsets.UTF_8)) else JSONArray()
        } catch (t: Throwable) {
            return "ops.json 不是合法 JSON，未改动：" + (t.message ?: "")
        }
        // 已存在就升级到 4 级
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            if (o.optString("name").equals(trimmed, ignoreCase = true)) {
                o.put("level", 4)
                o.put("bypassesPlayerLimit", false)
                return write(file, array, trimmed, "已存在，已把权限提到 4 级")
            }
        }
        val entry = JSONObject()
        entry.put("uuid", offlineUuid(trimmed))
        entry.put("name", trimmed)
        entry.put("level", 4)
        entry.put("bypassesPlayerLimit", false)
        array.put(entry)
        return write(file, array, trimmed, "已写入")
    }

    private fun write(file: File, array: JSONArray, name: String, what: String): String {
        return try {
            file.writeText(array.toString(2) + "\n", Charsets.UTF_8)
            "$what：$name（level 4）。玩家重新进服后生效；离线模式下 UUID 由名字推导，与原版一致。"
        } catch (t: Throwable) {
            "写入 ops.json 失败：" + (t.message ?: t.javaClass.simpleName)
        }
    }

    fun list(server: Server): String {
        val file = File(server.dir, "ops.json")
        return try {
            if (!file.isFile) return "还没有任何 OP"
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            if (arr.length() == 0) return "还没有任何 OP"
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                o.optString("name") + " (level " + o.optInt("level") + ")"
            }.joinToString("\n")
        } catch (t: Throwable) {
            "读取失败：" + (t.message ?: t.javaClass.simpleName)
        }
    }
}
