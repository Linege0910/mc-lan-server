package com.mclan.server

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * 启动/停止服务端。
 *
 * 两种模式：
 *  · 普通模式：JDK 由本应用进程直接拉起，控制台走管道，能发命令；应用被杀就一起没了。
 *  · root 后台模式：通过 su -c 把 java 放到后台，输出重定向到 console.log，
 *    pid 写进 .server.pid。应用被杀也不影响，重开后读 pid 就能结束它。
 */
class ServerProcess(
    private val server: Server,
    private val onState: (String) -> Unit
) {
    @Volatile private var process: Process? = null
    @Volatile private var running = false
    private var stdin: OutputStream? = null

    fun isRunning(): Boolean = running && (process?.isAlive == true)

    fun belongsTo(s: Server): Boolean = s.dir.absolutePath == server.dir.absolutePath

    fun start(): Boolean {
        if (isRunning()) return false
        val java = Jdk.find()
        if (java == null) {
            server.log.append("[错误] 没有找到可用的 Java，请到「设置」下载或指定 JDK。")
            onState("stopped")
            return false
        }
        val dir = server.dir
        dir.mkdirs()
        server.ensureEula()
        val cmd = command(java.absolutePath, dir)

        if (AppPrefs.rootRunServer()) {
            val rootJava = RootJdk.ensure(java) { server.log.append(it) }
            if (rootJava == null) {
                // 偏好设置保持不动：这次用普通模式跑起来，下次启动仍会再试一次 root
                server.log.append("[错误] 无法准备 root 可执行的 Java，本次改用普通模式（root 选项保留）")
                return startAttached(java.absolutePath, dir, command(java.absolutePath, dir))
            }
            return startDaemon(rootJava.absolutePath, dir, command(rootJava.absolutePath, dir))
        }
        return startAttached(java.absolutePath, dir, cmd)
    }

    // ------------------------------------------------------------ 普通模式

    private fun startAttached(java: String, dir: File, cmd: List<String>): Boolean {
        server.log.append("[启动] " + cmd.joinToString(" "))
        return try {
            val pb = ProcessBuilder(cmd)
            pb.directory(dir)
            pb.redirectErrorStream(true)
            val env = pb.environment()
            val home = File(java).parentFile?.parentFile
            env["HOME"] = dir.absolutePath
            env["TMPDIR"] = File(dir, ".tmp").apply { mkdirs() }.absolutePath
            env["TERM"] = "dumb"
            if (home != null) {
                env["JAVA_HOME"] = home.absolutePath
                env["LD_LIBRARY_PATH"] = home.absolutePath + "/lib:" + home.absolutePath + "/lib/server"
            }
            val p = pb.start()
            process = p
            running = true
            stdin = p.outputStream
            ConsolePipe.stdin = stdin
            server.pidFile.writeText(pidOf(p).toString())
            onState("starting")
            readLoop(p.inputStream)
            watchdog(p)
            true
        } catch (t: Throwable) {
            server.log.append("[错误] 启动失败：" + (t.message ?: t.javaClass.simpleName))
            onState("stopped")
            false
        }
    }

    // ------------------------------------------------------------ root 后台模式

    /**
     * su -c 会清掉环境变量，所以 JAVA_HOME / LD_LIBRARY_PATH 必须内联进命令，
     * 否则 java 找不到 libjvm.so —— 表现就是「root 模式起不来、普通模式正常」。
     */
    private fun startDaemon(java: String, dir: File, cmd: List<String>): Boolean {
        if (!KeepAlive.hasRoot()) {
            server.log.append("[错误] root 模式需要 root 权限：su 不可用或被拒绝")
            onState("stopped")
            return false
        }
        val logFile = File(dir, "console.log")
        val pidFile = File(dir, ".server.pid")
        try {
            logFile.writeText("")
            pidFile.writeText("")
        } catch (t: Throwable) {
        }

        val javaHome = File(java).parentFile?.parentFile?.absolutePath ?: ""
        val libPath = javaHome + "/lib:" + javaHome + "/lib/server"
        val tmpDir = File(dir, ".tmp").apply { mkdirs() }.absolutePath

        val script = buildString {
            append("cd ").append(q(dir.absolutePath)).append(" || exit 1; ")
            append("JAVA_HOME=").append(q(javaHome)).append(' ')
            append("LD_LIBRARY_PATH=").append(q(libPath)).append(' ')
            append("HOME=").append(q(dir.absolutePath)).append(' ')
            append("TMPDIR=").append(q(tmpDir)).append(' ')
            append("TERM=dumb ")
            append(cmd.joinToString(" ") { q(it) })
            append(" < /dev/null >> ").append(q(logFile.absolutePath)).append(" 2>&1 & ")
            append("echo $! > ").append(q(pidFile.absolutePath)).append("; ")
            append("cat ").append(q(pidFile.absolutePath))
        }
        server.log.append("[启动] root 后台模式")
        server.log.append("[命令] " + script)

        return try {
            val pb = ProcessBuilder("su", "-c", script)
            pb.directory(dir)
            pb.redirectErrorStream(true)
            val p = pb.start()

            // 读取要有上限：su 可能卡在授权框上，绝不能让线程无限等
            val collected = StringBuilder()
            val reader = Thread {
                try {
                    collected.append(p.inputStream.bufferedReader().readText())
                } catch (t: Throwable) {
                }
            }
            reader.isDaemon = true
            reader.start()
            reader.join(12_000)
            p.waitFor(3, TimeUnit.SECONDS)

            var pid = collected.toString().lineSequence()
                .mapNotNull { it.trim().toIntOrNull() }
                .lastOrNull() ?: -1

            // 拿不到就把 pid 文件当唯一真相（它就是 root shell 写的）
            if (pid <= 0) {
                for (i in 0 until 15) {
                    val v = try {
                        pidFile.readText().trim().toIntOrNull()
                    } catch (t: Throwable) {
                        null
                    }
                    if (v != null && v > 0) {
                        pid = v
                        break
                    }
                    Thread.sleep(400)
                }
            }
            server.log.append("[启动] su 返回：" + collected.toString().trim().take(200))

            if (pid <= 0) {
                server.log.append("[错误] 没拿到 pid：可能 root 授权被拒绝，或命令立即失败")
                val err = try {
                    logFile.readText().take(400)
                } catch (t: Throwable) {
                    ""
                }
                if (err.isNotBlank()) server.log.append("[console.log] " + err)
                onState("stopped")
                return false
            }
            pidFile.writeText(pid.toString())
            server.log.append("[提示] 服务端已作为 root 后台进程启动：关闭本应用后它仍然运行")
            server.log.append("[警告] 本应用无法停止它。关服只有两种办法：")
            server.log.append("[警告]   ① 有 OP 的玩家在游戏里执行 /stop")
            server.log.append("[警告]   ② 重启手机（最彻底）")
            server.log.append("[提示] 授予 OP：配置页 → 授予 OP，写入玩家名后让玩家重新进服")
            // root 进程会把存档/配置写成 root 属主，应用随后读不了会崩；启动后改回应用 uid
            KeepAlive.runRoot(
                "chown -R " + android.os.Process.myUid() + " " + q(dir.absolutePath) + "; " + "chmod -R a+rwX " + q(dir.absolutePath),
                20_000
            )
            running = true
            onState("starting")
            startTailer(logFile)

            Thread({
                for (i in 0 until 60) {
                    Thread.sleep(2000)
                    val st = ServerProbe.stateCached(server)
                    if (!st.running) {
                        running = false
                        server.pidFile.delete()
                        server.log.append("[控制台] 后台服务端已退出（启动后 " + (i + 1) * 2 + " 秒内）")
                        onState("stopped")
                        return@Thread
                    }
                    if (i == 2) onState("running")
                }
            }, "mc-daemon-watch").start()
            true
        } catch (t: Throwable) {
            server.log.append("[错误] 后台启动失败：" + (t.message ?: t.javaClass.simpleName))
            onState("stopped")
            false
        }
    }


    /** 跟随 console.log，把新行喂给界面。 */
    private fun startTailer(logFile: File) {
        Thread({
            var pos = 0L
            val buf = StringBuilder()
            var idle = 0
            while (running || ServerProbe.stateCached(server).running) {
                try {
                    if (logFile.isFile) {
                        val len = logFile.length()
                        if (len < pos) pos = 0
                        if (len > pos) {
                            java.io.RandomAccessFile(logFile, "r").use { raf ->
                                raf.seek(pos)
                                val bytes = ByteArray((len - pos).toInt().coerceAtMost(1 shl 20))
                                val n = raf.read(bytes)
                                if (n > 0) {
                                    pos += n
                                    buf.append(String(bytes, 0, n, Charsets.UTF_8))
                                }
                            }
                            var idx = buf.indexOf("\n")
                            while (idx >= 0) {
                                val line = buf.substring(0, idx).trimEnd('\r')
                                buf.delete(0, idx + 1)
                                if (line.isNotEmpty()) server.log.append(line)
                                idx = buf.indexOf("\n")
                            }
                        }
                    }
                } catch (t: Throwable) {
                }
                Thread.sleep(700)
                idle++
                if (idle > 60 * 60 * 12) break
            }
        }, "mc-tail").start()
    }

    // ------------------------------------------------------------ 公共

    /** 单引号包裹，避免 su -c 里的空格被 shell 拆开。 */
    private fun q(s: String): String = "\u0027" + s.replace("\u0027", "\u0027\\\u0027\u0027") + "\u0027"

    private fun command(java: String, dir: File): List<String> {
        val xmx = AppPrefs.xmxMb()
        val extra = AppPrefs.javaArgs().split(' ', '\t').filter { it.isNotBlank() }
        val cmd = mutableListOf<String>()
        cmd.add(java)
        cmd.add("-Xms" + minOf(1024, maxOf(256, xmx / 4)) + "M")
        cmd.add("-Xmx" + xmx + "M")
        cmd.add("-XX:+UseSerialGC")
        cmd.add("-XX:+ExitOnOutOfMemoryError")
        cmd.add("-Duser.home=" + dir.absolutePath)
        cmd.add("-Djava.io.tmpdir=" + dir.absolutePath + "/.tmp")
        cmd.add("-Djava.awt.headless=true")
        cmd.addAll(extra)
        cmd.add("-jar")
        cmd.add(server.jar.absolutePath)
        cmd.add("nogui")
        return cmd
    }

    private fun pidOf(p: Process): Int = try {
        (p.javaClass.getMethod("pid").invoke(p) as Long).toInt()
    } catch (t: Throwable) {
        -1
    }

    private fun readLoop(input: InputStream) {
        Thread({
            try {
                input.bufferedReader(Charsets.UTF_8).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        server.log.append(line)
                        line = reader.readLine()
                    }
                }
            } catch (t: Throwable) {
                // 进程结束时会关闭流，属正常
            }
        }, "mc-console").start()
    }

    private fun watchdog(p: Process) {
        Thread({
            val code = try {
                p.waitFor()
            } catch (t: Throwable) {
                -1
            }
            running = false
            server.pidFile.delete()
            server.log.append("[控制台] 服务端已退出（退出码 " + code + "）")
            onState("stopped")
        }, "mc-watchdog").start()
    }

    fun send(command: String): Boolean {
        val out = stdin ?: return false
        return try {
            out.write((command + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
            server.log.append("> " + command)
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun stop() {
        if (AppPrefs.rootRunServer()) {
            server.log.append("[控制台] root 后台模式：交给 ServerProbe 结束")
            return
        }
        val p = process ?: return
        if (!send("stop")) {
            p.destroy()
            return
        }
        Thread({
            try {
                p.waitFor()
            } catch (t: Throwable) {
            }
            if (p.isAlive) {
                p.destroy()
                Thread.sleep(2500)
                if (p.isAlive) p.destroyForcibly()
            }
        }, "mc-stopper").start()
    }

    companion object {
        @Volatile var current: ServerProcess? = null
    }
}
