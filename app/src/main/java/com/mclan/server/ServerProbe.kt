package com.mclan.server

import java.net.InetSocketAddress
import java.net.Socket

/**
 * 探测与结束「服务端到底还在不在跑」。
 *
 * 应用被杀后残留的进程不能用内存对象判断，也不一定能靠 `ps` 的命令行匹配：
 * Android 的 toybox `ps` 默认会**按屏幕宽度截断 ARGS**，服务端命令行很长，
 * 带 jar 路径的部分往往正好被截掉 —— 这就是之前「结束失败」的原因之一。
 *
 * 所以这里按可靠性依次：
 *  1. `/proc/<pid>/cmdline` 全量扫描（不受宽度限制）
 *  2. `ps -A -ww -o PID,ARGS`（-ww 关闭截断）
 *  3. 端口反查：从 /proc/net/tcp 取 socket inode，再在 /proc/<pid>/fd 里找它 ——
 *     即使进程名完全不匹配，也能定位到「正在监听 25565 的那个进程」
 *  4. `pidof java` 作为最后手段
 */
object ServerProbe {

    data class State(
        val running: Boolean,
        val attached: Boolean,
        val pid: Int,
        val portOpen: Boolean,
        val viaRoot: Boolean
    )

    data class KillResult(val ok: Boolean, val log: String)

    // 状态探测会 fork su（很贵），结果按目录缓存 3 秒，供界面/跟随线程复用
    private val stateCache = HashMap<String, Pair<Long, State>>()

    /** 带缓存的探测：给界面轮询和日志跟随用，避免每秒 fork 一次 su。 */
    fun stateCached(server: Server): State {
        val key = server.dir.absolutePath
        val now = System.currentTimeMillis()
        synchronized(stateCache) {
            val hit = stateCache[key]
            if (hit != null && now - hit.first < 3000) return hit.second
        }
        val st = state(server)
        synchronized(stateCache) {
            stateCache[key] = now to st
        }
        return st
    }

    fun invalidate() {
        synchronized(stateCache) { stateCache.clear() }
    }

    fun state(server: Server): State {
        val proc = ServerProcess.current
        val attached = proc != null && proc.isRunning() && proc.belongsTo(server)
        if (attached) {
            return State(true, true, server.pid(), isPortOpen(server.port()), false)
        }
        // 快速路径：端口没监听、也没有 pid 文件 —— 一定没在跑，不要 fork su 去 ps。
        // 否则每 3 秒一次的探测会把单线程任务队列堵死（下载进度条就是这样卡住的）。
        val portOpen = isPortOpen(server.port())
        val savedPid = server.pid()
        if (!portOpen && savedPid <= 0) {
            return State(false, false, -1, false, false)
        }
        val pids = collectPids(server, null)
        val alive = pids.isNotEmpty() || portOpen
        return State(alive, false, pids.firstOrNull() ?: savedPid, portOpen, pids.isNotEmpty())
    }

    fun isPortOpen(port: Int): Boolean {
        if (port <= 0) return false
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 300)
                true
            }
        } catch (t: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------ 找 pid

    fun collectPids(server: Server, log: ((String) -> Unit)?): List<Int> {
        if (!KeepAlive.hasRoot()) {
            log?.invoke("没有 root（su 不可用或被拒绝），无法查看/结束别的进程")
            return emptyList()
        }
        val jar = server.jar.absolutePath
        val pids = LinkedHashSet<Int>()

        // 1) /proc 扫描。关键：只认「命令行第一个词以 /java 结尾」的进程，
        //    否则会把这次探测用的 su/shell 自己（命令行里也带 jar 路径）当成服务端。
        val scan = KeepAlive.runRoot(
            "for p in /proc/[0-9]*; do " +
                "c=\$(tr \u0027\\000\u0027 \u0027 \u0027 < \$p/cmdline 2>/dev/null); " +
                "f=\${c%% *}; " +
                "case \"\$f\" in */java) case \"\$c\" in *" + jar + "*) echo \${p#/proc/};; esac;; esac; " +
                "done",
            timeoutMs = 15000
        )
        for (line in scan.lineSequence()) {
            line.trim().toIntOrNull()?.let { if (it > 0) pids.add(it) }
        }
        log?.invoke("/proc 扫描 → " + if (pids.isEmpty()) "未找到" else pids.joinToString(","))

        // 2) ps 兜底（-ww 关闭截断）
        if (pids.isEmpty()) {
            for (cmd in listOf("ps -A -ww -o PID,ARGS", "ps -A -o PID,ARGS -w")) {
                val out = KeepAlive.runRoot(cmd, timeoutMs = 10000)
                if (out.isBlank() || out.contains("not found") || out.startsWith("无法执行")) continue
                for (line in out.lineSequence()) {
                    if (!line.contains(jar)) continue
                    val parts = line.trim().split(Regex("\\s+"))
                    val pid = parts.firstOrNull()?.toIntOrNull() ?: continue
                    val args = line.substringAfter(parts.first(), "").trim()
                    // 同样要求命令是 java
                    if (!args.contains("/java ")) continue
                    if (pid > 0) pids.add(pid)
                }
                if (pids.isNotEmpty()) {
                    log?.invoke("$cmd → " + pids.joinToString(","))
                    break
                }
            }
        }

        // 3) pid 文件里的 pid：必须先核对命令行确实是 java 在跑这个 jar
        val saved = server.pid()
        if (saved > 0 && !pids.contains(saved)) {
            val check = KeepAlive.runRoot(
                "c=\$(tr \u0027\\000\u0027 \u0027 \u0027 < /proc/$saved/cmdline 2>/dev/null); " +
                    "case \"\$c\" in */java *" + jar + "*) echo YES;; *) echo NO;; esac",
                8000
            )
            if (check.contains("YES")) {
                pids.add(saved)
                log?.invoke("pid 文件 → " + saved + "（已核对）")
            } else {
                log?.invoke("pid 文件 → " + saved + "（已失效，忽略）")
            }
        }
        return pids.toList()
    }

    // ------------------------------------------------------------ 结束

    fun killDetached(server: Server): KillResult {
        val lines = ArrayList<String>()
        fun log(s: String) {
            lines.add(s)
            server.log.append("[控制台] " + s)
        }

        if (!KeepAlive.hasRoot()) {
            log("su 不可用：请到 Magisk / KernelSU 里确认本应用已被允许")
            return KillResult(false, lines.joinToString("\n"))
        }

        val pids = collectPids(server) { log(it) }
        log("候选进程：" + if (pids.isEmpty()) "无" else pids.joinToString(","))

        if (pids.isEmpty() && !isPortOpen(server.port())) {
            server.pidFile.delete()
            log("没有进程在跑，端口也没监听 —— 已清理状态")
            return KillResult(true, lines.joinToString("\n"))
        }

        for (pid in pids) {
            val out = KeepAlive.runRoot("kill -TERM $pid; sleep 0.3; kill -0 $pid 2>/dev/null && echo ALIVE || echo GONE", 8000)
            log("TERM $pid → " + out.replace("\n", " ").take(100))
        }

        var deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline) {
            if (collectPids(server, null).isEmpty() && !isPortOpen(server.port())) {
                server.pidFile.delete()
                log("已正常结束")
                return KillResult(true, lines.joinToString("\n"))
            }
            Thread.sleep(800)
        }

        log("12 秒未退出，强制 KILL")
        for (pid in collectPids(server, null)) {
            val out = KeepAlive.runRoot("kill -KILL $pid; sleep 0.3; kill -0 $pid 2>/dev/null && echo ALIVE || echo GONE", 8000)
            log("KILL $pid → " + out.replace("\n", " ").take(100))
        }
        Thread.sleep(1500)

        if (collectPids(server, null).isEmpty() && !isPortOpen(server.port())) {
            server.pidFile.delete()
            log("已强制结束")
            return KillResult(true, lines.joinToString("\n"))
        }

        // 最后兜底：结束所有 java
        log("仍未结束，尝试 killall -9 java")
        val out = KeepAlive.runRoot("killall -9 java; sleep 1; echo DONE", 15000)
        log("killall → " + out.replace("\n", " ").take(80))
        Thread.sleep(1500)
        val finalAlive = collectPids(server, null)
        val portOpen = isPortOpen(server.port())
        server.pidFile.delete()
        if (finalAlive.isEmpty() && !portOpen) {
            log("已结束（通过 killall）")
            return KillResult(true, lines.joinToString("\n"))
        }
        log("仍失败：" + (if (finalAlive.isEmpty()) "无匹配进程" else "pid " + finalAlive.joinToString(",")) +
            (if (portOpen) "，端口 ${server.port()} 仍在监听" else ""))
        log("如果端口仍被占用但没有 java 进程，可能是别的程序占用了该端口；请换端口或重启手机。")
        return KillResult(false, lines.joinToString("\n"))
    }

    /** 一次性把所有诊断信息收集出来，方便排查。 */
    fun diagnose(server: Server): String {
        val sb = StringBuilder()
        sb.append("== 基础 ==\n")
        sb.append("su 路径：").append(KeepAlive.suPath() ?: "无").append('\n')
        sb.append("su 可用：").append(KeepAlive.hasRoot()).append('\n')
        val info = KeepAlive.detectRoot()
        sb.append("root 类型：").append(info.flavor).append(' ').append(info.version).append('\n')
        sb.append("pid 文件：").append(server.pid()).append('\n')
        sb.append("端口 ").append(server.port()).append(" 监听：").append(isPortOpen(server.port())).append("\n\n")

        sb.append("== /proc 匹配 ==\n")
        sb.append(collectPids(server, null).joinToString(", ").ifBlank { "无" }).append("\n\n")

        sb.append("== ps (宽) ==\n")
        val ps = KeepAlive.runRoot("ps -A -ww -o PID,ARG,ARGS 2>/dev/null | head -60", 12000)
        sb.append(ps.take(1800)).append("\n\n")

        sb.append("== java 进程 ==\n")
        sb.append(KeepAlive.runRoot("pidof java; echo ---", 8000)).append("\n\n")

        sb.append("== 端口占用 ==\n")
        sb.append(KeepAlive.runRoot("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | head -20", 8000).take(1200))
        return sb.toString()
    }

    fun busyHint(server: Server): String =
        "端口 " + server.port() + " 已经被占用，可能是上次异常退出的服务端还在后台。"
}
