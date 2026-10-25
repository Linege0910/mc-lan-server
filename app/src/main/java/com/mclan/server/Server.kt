package com.mclan.server

import java.io.File

/**
 * A server instance on disk.  Java 版与基岩版各占一个目录。
 */
class Server(val type: String, val dir: File) {

    val jar: File get() = File(dir, "server.jar")
    val props: File get() = File(dir, "server.properties")
    val logFile: File get() = File(dir, "console.log")
    val pidFile: File get() = File(dir, ".server.pid")
    val log: Log get() = Log.get(dir.absolutePath)

    fun hasJar(): Boolean = jar.isFile && jar.length() > 100_000

    /** 是否在运行（含应用被杀后残留的后台进程）。 */
    fun isRunning(): Boolean = ServerProbe.stateCached(this).running

    /** 是否由当前应用进程直接托管（否则只能强制结束）。 */
    fun isAttached(): Boolean = ServerProbe.stateCached(this).attached

    fun pid(): Int = pidFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: -1

    fun clearPid() {
        try {
            pidFile.delete()
        } catch (t: Throwable) {
        }
    }

    fun port(): Int = configGet("server-port")?.trim()?.toIntOrNull()
        ?: if (type == BEDROCK) 19132 else 25565

    /**
     * 读配置。必须容错：root 模式跑过之后 server.properties 可能属于 root，
     * 应用读它会抛 EACCES —— 以前这里直接把整个界面崩掉。
     */
    fun configGet(key: String): String? = try {
        props.takeIf { it.isFile }
            ?.readLines(Charsets.UTF_8)
            ?.firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
    } catch (t: Throwable) {
        null
    }

    /** Write eula.txt; Minecraft requires an explicit agreement. */
    fun ensureEula() {
        dir.mkdirs()
        try {
            File(dir, "eula.txt").writeText(
                "# 由 MC局域网开服 写入：用户点击启动即表示同意 Minecraft EULA\n" +
                "# https://aka.ms/MinecraftEULA\neula=true\n",
                Charsets.UTF_8
            )
        } catch (t: Throwable) {
        }
    }

    companion object {
        const val JAVA = "java"
        const val BEDROCK = "bedrock"

        fun byType(type: String): Server = Server(
            type,
            if (type == BEDROCK) Paths.bedrockDir() else Paths.serversDir()
        )
    }
}
