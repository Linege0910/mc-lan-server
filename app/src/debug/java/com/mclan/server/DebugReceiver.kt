package com.mclan.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 仅 debug 变体：用 adb broadcast 触发启动/停止/状态，便于手机实测。 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Paths.init(context)
        AppPrefs.init(context)
        android.util.Log.i("MCLanDebug", "action=" + intent.action)
        val root = intent.getBooleanExtra("root", true)
        when (intent.action) {
            "com.mclan.server.DEBUG_START" -> {
                // 让 adb 触发的启动走 root 后台模式，并把 JDK 指到已恢复的副本
                AppPrefs.setRootRunServer(root)
                if (AppPrefs.requiredJavaMajor() <= 0) AppPrefs.setRequiredJavaMajor(25)
                if (AppPrefs.jdkPath().isBlank()) {
                    val jdk = java.io.File(Paths.appDir(), "jdk-25")
                    if (jdk.isDirectory) AppPrefs.setJdkPath(jdk.absolutePath)
                }
                android.util.Log.i(
                    "MCLanDebug",
                    "prefs root=" + AppPrefs.rootRunServer() +
                        " major=" + AppPrefs.requiredJavaMajor() +
                        " jdk=" + AppPrefs.jdkPath()
                )
                Thread({
                    val s = Server.byType(Server.JAVA)
                    android.util.Log.i("MCLanDebug", "直接调用 ServerProcess.start()")
                    val proc = ServerProcess(s) { state ->
                        android.util.Log.i("MCLanDebug", "state=" + state)
                    }
                    ServerProcess.current = proc
                    val ok = proc.start()
                    android.util.Log.i("MCLanDebug", "start returned " + ok)
                }, "dbg-start").start()
                val i = Intent(context, MainActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                i.putExtra("auto_start", true)
                context.startActivity(i)
            }
            "com.mclan.server.DEBUG_IMPORT_JDK" -> {
                // 调试用：把 /data/local/tmp/jdk25 拷进 App 私有目录（App 自己写，避开 SELinux 限制）
                Thread({
                    try {
                        val src = java.io.File("/data/local/tmp/jdk25")
                        val dst = java.io.File(Paths.appDir(), "jdk-25")
                        if (dst.exists()) dst.deleteRecursively()
                        Storage.copyTree(src, dst)
                        AppPrefs.setJdkPath(dst.absolutePath)
                        AppPrefs.setRequiredJavaMajor(25)
                        val probe = Jdk.probe(java.io.File(dst, "bin/java"))
                        android.util.Log.i("MCLanDebug", "import jdk ok: " + probe)
                    } catch (t: Throwable) {
                        android.util.Log.e("MCLanDebug", "import jdk failed", t)
                    }
                }, "dbg-jdk").start()
            }
            "com.mclan.server.DEBUG_STOP" -> {
                val s = Server.byType(Server.JAVA)
                Thread({
                    val r = ServerProbe.killDetached(s)
                    android.util.Log.i("MCLanDebug", "kill ok=" + r.ok + "\n" + r.log)
                }, "dbg-kill").start()
            }
            "com.mclan.server.DEBUG_STATE" -> {
                Thread({
                    val s = Server.byType(Server.JAVA)
                    val st = ServerProbe.state(s)
                    android.util.Log.i(
                        "MCLanDebug",
                        "running=" + st.running + " attached=" + st.attached + " pid=" + st.pid +
                            " port=" + st.portOpen + "\n" + ServerProbe.diagnose(s)
                    )
                }, "dbg-state").start()
            }
        }
    }
}
