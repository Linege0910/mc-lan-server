package com.mclan.server

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.mclan.server.download.PistonApi
import com.mclan.server.jdk.JdkCatalog
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    /** 状态探测单独一条线程，避免把下载/安装任务堵在这个线程后面 */ 
    private val probeIo = Executors.newSingleThreadExecutor()
    /** 下载/安装专用线程，别和状态探测抢同一个线程 */
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var pageHost: FrameLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var badge: TextView
    private lateinit var consoleView: TextView
    private var consoleScroll: ScrollView? = null
    private var lastConsoleText: String = ""

    private var tab = TAB_STATUS
    // 后台探测缓存（每秒轮询会 fork su，太重，这里 3 秒一次且在 io 线程）
    @Volatile private var probeRunning = false
    @Volatile private var probeAttached = false
    @Volatile private var probePid = 0
    @Volatile private var probePortOpen = false
    @Volatile private var lastRenderedRunning = false
    private var type = Server.JAVA

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Paths.init(this)
        AppPrefs.init(this)
        setContentView(buildRoot())
        render()
        startTickers()
        startProbeLoop()
        askStorageIfNeeded()
        ui.postDelayed({ showWelcomeIfNeeded() }, 600)
        ui.postDelayed({ maybeAutoStart() }, 1200)
    }

    /** adb 调试触发：Intent 带 auto_start=true 时自动走一次启动流程。 */
    private fun maybeAutoStart() {
        val i = intent ?: return
        if (!i.getBooleanExtra("auto_start", false)) return
        i.removeExtra("auto_start")
        startOrStopServer()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == Storage.REQ_STORAGE) {
            Storage.reset()
            render()
            if (Storage.isWritable()) {
                toast("存储权限已授予：" + Storage.PUBLIC_ROOT)
            } else {
                // 系统直接拒了（或用户选了拒绝）：引导到「所有文件访问」/应用设置
                val denied = grantResults.isNotEmpty() &&
                    grantResults.all { it != android.content.pm.PackageManager.PERMISSION_GRANTED }
                AlertDialog.Builder(this)
                    .setTitle(if (denied) "权限被拒绝" else "仍无法写入")
                    .setMessage(
                        "文件需要放在：\n" + Storage.PUBLIC_ROOT +
                            "\n\n请到系统设置里允许本应用访问存储（Android 11+ 可打开「所有文件访问」）。"
                    )
                    .setPositiveButton("所有文件访问") { _, _ -> Storage.openAllFilesSettings(this) }
                    .setNeutralButton("应用设置") { _, _ -> Storage.openAppSettings(this) }
                    .setNegativeButton("稍后", null)
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Storage.reset()
        if (::pageHost.isInitialized) render()
    }

    // ------------------------------------------------------------ 界面骨架

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(10))
        }
        val title = TextView(this).apply {
            text = "MC 局域网开服"
            textSize = 20f
            setTextColor(getColor(R.color.text))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        badge = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(R.color.textDim))
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(badge)
        root.addView(header)

        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        val labels = listOf("状态", "控制台", "配置", "设置")
        for (i in labels.indices) {
            val b = Button(this).apply {
                text = labels[i]
                isAllCaps = false
                textSize = 14f
                setOnClickListener {
                    tab = i
                    render()
                }
            }
            tabBar.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabBar)

        pageHost = FrameLayout(this)
        root.addView(pageHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun render() {
        if (::pageHost.isInitialized) pageHost.removeAllViews()
        for (i in 0 until tabBar.childCount) {
            val b = tabBar.getChildAt(i) as Button
            val active = i == tab
            b.setBackgroundColor(getColor(if (active) R.color.cardActive else R.color.card))
            b.setTextColor(getColor(if (active) R.color.accent else R.color.textDim))
        }
        when (tab) {
            TAB_STATUS -> pageHost.addView(statusPage())
            TAB_CONSOLE -> pageHost.addView(consolePage())
            TAB_CONFIG -> pageHost.addView(configPage())
            else -> pageHost.addView(settingsPage())
        }
        refreshBadge()
    }

    private fun refreshBadge() {
        val running = probeRunning
        badge.text = when {
            running && probeAttached -> "● 运行中"
            running -> "● 后台运行中 (pid " + probePid + ")"
            else -> "● 未启动"
        }
        badge.setTextColor(getColor(if (running) R.color.ok else R.color.textDim))
    }

    /** 3 秒一次在后台探测，避免在主线程 fork su。 */
    private fun startProbeLoop() {
        ui.post(object : Runnable {
            override fun run() {
                probeIo.execute {
                    val st = try {
                        ServerProbe.stateCached(Server.byType(Server.JAVA))
                    } catch (t: Throwable) {
                        null
                    }
                    if (st != null) {
                        probeRunning = st.running
                        probeAttached = st.attached
                        probePid = st.pid
                        probePortOpen = st.portOpen
                    }
                    ui.post {
                        refreshBadge()
                        // 只在运行状态变化时重绘，避免每 3 秒重建界面导致点击丢失
                        if (::pageHost.isInitialized && lastRenderedRunning != probeRunning) {
                            lastRenderedRunning = probeRunning
                            render()
                        }
                    }
                }
                ui.postDelayed(this, 3000)
            }
        })
    }

    // ------------------------------------------------------------ 状态页

    private fun statusPage(): View {
        val scroll = ScrollView(this)
        val col = column()
        scroll.addView(col)

        val switcher = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((t, label) in listOf(Server.JAVA to "Java 版", Server.BEDROCK to "基岩版")) {
            val b = Button(this).apply {
                text = label
                isAllCaps = false
                setBackgroundColor(getColor(if (type == t) R.color.cardActive else R.color.card))
                setOnClickListener {
                    type = t
                    render()
                }
            }
            switcher.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        col.addView(switcher)

        val server = Server.byType(type)
        val running = probeRunning && type == Server.JAVA

        if (!Storage.isWritable()) {
            col.addView(primaryButton("⚠ 存储权限未授予，点这里授权（否则文件会存到应用私有目录）") {
                Storage.requestPermission(this)
            })
        }

        if (type == Server.BEDROCK) {
            col.addView(note("基岩版说明", bedrockHint()))
        }

        col.addView(row(
            (if (running) "停止服务器" else "启动服务器") to { startOrStopServer() },
            "下载官网服务端" to { downloadDialog(server) }
        ))
        col.addView(row(
            "发送命令" to { commandDialog() },
            "下载 Java" to { showJavaDialog() }
        ))
        if (running && !probeAttached) {
            col.addView(primaryButton("⚠ 结束后台遗留的服务端") { forceStopServer(server) })
            col.addView(row(
                "诊断" to { diagnoseServer(server) },
                "强制结束所有 java" to { forceKillAllJava(server) }
            ))
        }

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(getColor(R.color.card))
        }
        val addr = ProcessUtils.lanAddress() ?: "未连接局域网"
        info.addView(kv("局域网地址", addr + ":" + server.port()))
        info.addView(kv("服务端版本", AppPrefs.lastVersion()))
        info.addView(kv("运行内存", "-Xmx" + AppPrefs.xmxMb() + "M"))
        info.addView(kv("设备架构", ProcessUtils.abi()))
        info.addView(kv("服务端文件", if (server.hasJar()) "已就绪 (" + server.jar.length() / 1024 / 1024 + " MB)" else "未安装"))
        val java = Jdk.find()
        val majors = JdkCatalog.installedMajors()
        info.addView(
            kv(
                "Java 运行时",
                when {
                    java == null -> "未安装（点「下载 Java」）"
                    majors.isNotEmpty() -> "已安装 " + majors.joinToString(" / ") { "Java $it" }
                    else -> java.absolutePath
                }
            )
        )
        info.addView(kv("状态", when {
            running && probeAttached -> "运行中（由应用托管）"
            running -> "后台运行中" + (if (probePid > 0) "（pid " + probePid + "）" else "") +
                (if (probePortOpen) "，端口 " + server.port() + " 已监听" else "")
            else -> "未启动"
        }))
        val copy = Button(this).apply {
            text = "复制局域网地址"
            isAllCaps = false
            setOnClickListener {
                clipboard().setPrimaryClip(ClipData.newPlainText("addr", addr + ":" + server.port()))
                toast("已复制")
            }
        }
        info.addView(copy)
        col.addView(info)
        col.addView(note("最近日志", server.log.lines().takeLast(10).joinToString("\n").ifBlank { "暂无日志" }))
        return scroll
    }

    private fun bedrockHint(): String = buildString {
        append("Mojang 只发布 Windows / Ubuntu(x86_64) 版基岩版服务端，")
        append("Android 手机无法直接运行；且其许可不允许随应用分发。\n")
        append("这里提供：查询官方最新版本、给出官网下载直链、编辑 server.properties、")
        append("并列出局域网需要的端口（19132/udp）。\n")
        append("想真的在手机上开服，请使用「Java 版」。")
    }

    // ------------------------------------------------------------ 控制台

    private fun consolePage(): View {
        val server = Server.byType(Server.JAVA)
        val col = column(padBottom = 10)
        col.addView(row(
            "清空" to {
                server.log.clear()
                render()
            },
            "复制全部" to {
                clipboard().setPrimaryClip(ClipData.newPlainText("log", server.log.lines().joinToString("\n")))
                toast("已复制")
            }
        ))
        consoleView = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColor(R.color.text))
            setBackgroundColor(getColor(R.color.card))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setTextIsSelectable(true)
            text = server.log.lines().takeLast(400).joinToString("\n")
        }
        val sv = ScrollView(this)
        sv.addView(consoleView)
        sv.isFillViewport = false
        consoleScroll = sv
        lastConsoleText = ""
        col.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        ui.post { syncConsole() }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val input = EditText(this).apply {
            hint = "输入命令：list / op 名字 / time set day / stop"
            setTextColor(getColor(R.color.text))
            setHintTextColor(getColor(R.color.textDim))
            setTextSize(14f)
        }
        val send = Button(this).apply {
            text = "发送"
            isAllCaps = false
            setOnClickListener {
                val c = input.text.toString().trim()
                if (c.isEmpty()) return@setOnClickListener
                if (ServerProcess.current?.send(c) != true) {
                    toast("服务器未运行")
                }
                input.setText("")
            }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(send)
        col.addView(bar)
        return col
    }

    // ------------------------------------------------------------ 配置

    private fun configPage(): View {
        val server = Server.byType(type)
        val scroll = ScrollView(this)
        val col = column()
        scroll.addView(col)
        col.addView(row(
            "文本编辑" to { rawEditor(server) },
            "授予 OP" to { grantOpDialog(server) },
            "备份" to {
                ServerConfig.backup(server)
                toast("已备份为 server.properties.bak")
            }
        ))
        for ((k, v) in ServerConfig.read(server)) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            line.addView(TextView(this).apply {
                text = k
                textSize = 13f
                setTextColor(getColor(R.color.textDim))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val field = EditText(this).apply {
                setText(v)
                textSize = 13f
                setTextColor(getColor(R.color.text))
                setBackgroundColor(getColor(R.color.card))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setSingleLine(true)
            }
            field.setOnFocusChangeListener { _, focused ->
                if (!focused) {
                    val nv = field.text.toString()
                    if (nv != v) {
                        ServerConfig.put(server, k, nv)
                        toast("已保存 $k")
                    }
                }
            }
            line.addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f))
            col.addView(line)
        }
        return scroll
    }

    /** 授予 OP：写 ops.json。root 守护模式下这是唯一能正常关服的办法（游戏内 /stop）。 */
    private fun grantOpDialog(server: Server) {
        val input = EditText(this).apply {
            hint = "玩家名（Minecraft ID，区分大小写）"
            setSingleLine(true)
            setTextColor(getColor(R.color.text))
        }
        val box = column().apply {
            addView(Text("离线模式的 UUID 由名字推导，和原版一致；写入后玩家重新进服生效。").apply {
                setTextColor(getColor(R.color.textDim))
                textSize = 12f
            })
            addView(input)
            addView(Text("当前 OP：\n" + Ops.list(server)).apply {
                setTextColor(getColor(R.color.textDim))
                textSize = 12f
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("授予 OP（level 4）")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("写入") { _, _ ->
                toast(Ops.grant(server, input.text.toString()))
                render()
            }
            .setNeutralButton("从玩家列表选", null)
            .setNegativeButton("关闭", null)
            .create()
        dialog.setOnShowListener {
            val neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            neutral.setOnClickListener { showKnownPlayersForOp(server, input) }
        }
        dialog.show()
    }

    /** 从 usercache.json 里挑一个名字填进去。 */
    private fun showKnownPlayersForOp(server: Server, input: EditText) {
        val names = try {
            val f = File(server.dir, "usercache.json")
            if (!f.isFile) emptyList()
            else org.json.JSONArray(f.readText(Charsets.UTF_8)).let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }.filter { it.isNotBlank() }
            }
        } catch (t: Throwable) {
            emptyList()
        }
        if (names.isEmpty()) {
            toast("还没有玩家进过服，先让玩家进一次再选，或直接手输名字")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("选择玩家")
            .setItems(names.toTypedArray()) { _, which -> input.setText(names[which]) }
            .setNegativeButton("取消", null)
            .show()
    }


    private fun rawEditor(server: Server) {
        val edit = EditText(this).apply {
            setText(ServerConfig.raw(server))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColor(R.color.text))
            minLines = 14
        }
        AlertDialog.Builder(this)
            .setTitle(server.props.name)
            .setView(ScrollView(this).apply { addView(edit) })
            .setPositiveButton("保存") { _, _ ->
                ServerConfig.saveRaw(server, edit.text.toString())
                toast("已保存，重启服务端后生效")
                render()
            }
            .setNeutralButton("导入文件") { _, _ -> pickFile() }
            .setNegativeButton("取消", null)
            .show()
    }

    private var importing = false

    private fun pickFile() {
        importing = true
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            },
            REQ_IMPORT
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == REQ_JDK_TREE) {
            val tree = data?.data ?: return
            importJdk(tree)
            return
        }
        if (requestCode != REQ_IMPORT) return
        val uri = data?.data ?: return
        val name = displayName(uri)
        val server = Server.byType(type)
        io.execute {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    if (name.endsWith(".jar", true)) {
                        val tmp = File(server.dir, "server.jar.importing")
                        tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
                        if (server.jar.exists()) server.jar.delete()
                        tmp.renameTo(server.jar)
                        ui.post {
                            toast("已导入服务端 jar")
                            render()
                        }
                    } else {
                        val text = input.bufferedReader(Charsets.UTF_8).readText()
                        ServerConfig.saveRaw(server, text)
                        ui.post {
                            toast("已导入配置")
                            render()
                        }
                    }
                }
            } catch (t: Throwable) {
                ui.post { toast("导入失败：" + t.message) }
            }
        }
    }

    // ------------------------------------------------------------ 设置

    private fun settingsPage(): View {
        val scroll = ScrollView(this)
        val col = column()
        scroll.addView(col)

        val totalMb = DeviceInfo.nominalRamMb()
        val maxMb = DeviceInfo.halfRamMb()
        col.addView(label(
            "服务器内存上限：" + DeviceInfo.formatGb(AppPrefs.xmxMb()) +
                "（设备总内存 " + DeviceInfo.formatGb(totalMb) + "，上限取其一半 " + DeviceInfo.formatGb(maxMb) + "）"
        ))
        val mem = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // 预设：上限的一半 / 3/4 / 上限本身（16G 手机即 4G / 6G / 8G）
        for (mb in linkedSetOf(maxMb / 2, maxMb * 3 / 4, maxMb)) {
            val value = maxOf(512, (mb / 256) * 256)
            val b = Button(this).apply {
                text = DeviceInfo.formatGb(value)
                isAllCaps = false
                setBackgroundColor(getColor(if (AppPrefs.xmxMb() == value) R.color.cardActive else R.color.card))
                setOnClickListener {
                    AppPrefs.setXmxMb(value)
                    toast("已设为 " + DeviceInfo.formatGb(AppPrefs.xmxMb()))
                    render()
                }
            }
            mem.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        col.addView(mem)

        col.addView(label("附加 JVM 参数（可选）"))
        val args = EditText(this).apply {
            hint = "例如 -XX:+UseG1GC"
            setText(AppPrefs.javaArgs())
            setTextColor(getColor(R.color.text))
            setHintTextColor(getColor(R.color.textDim))
        }
        col.addView(args)
        col.addView(row(
            "保存参数" to {
                AppPrefs.setJavaArgs(args.text.toString())
                toast("已保存")
            },
            "自定义 JDK" to { showJdkDialog() }
        ))
        col.addView(primaryButton("下载 Java 运行时") { showJavaDialog() })
        col.addView(row(
            "导入 JDK 文件夹" to { pickJdkTree() },
            "说明" to { showJdkHelp() }
        ))

        val majors = JdkCatalog.installedMajors()
        col.addView(note("Java 运行时", buildString {
            append(if (majors.isEmpty()) "尚未安装任何 Java" else "已安装：" + majors.joinToString(" / ") { "Java $it" })
            append("\n\n服务端版本与需要的 Java：\n")
            append("· 1.16.5 及更早 → Java 8（Termux 仓库已不提供）\n")
            append("· 1.17 – 1.20.4 → Java 17\n")
            append("· 1.20.5 – 1.21.x → Java 21\n")
            append("· 1.22 / 26.x（含最新 26.3）→ Java 25\n\n")
            append("点「下载 Java 运行时」会自动从镜像获取对应版本并解压安装，")
            append("用的是 Termux 官方仓库的 bionic 构建（Android 唯一能跑的 JDK）。")
        }))

        col.addView(note("关于", "作者 B 站 UID：102392702（欢迎关注）\n项目已在 GitHub 开源"))
        col.addView(row(
            "打开 B 站主页" to { openUrl(Constants.AUTHOR_BILI) },
            "关于本应用" to { showAboutDialog() }
        ))
        col.addView(note("存储位置", Storage.stateText(this)))
        col.addView(row(
            "申请存储权限" to { Storage.requestPermission(this) },
            "系统设置" to { openAppSettings() }
        ))
        col.addView(row("重新检测" to { Storage.reset(); render() }, "打开目录说明" to { showDataDir() }))
        col.addView(note("后台保活", KeepAlive.statusText(this) + "\n\n" + KeepAlive.deviceNote()))
        col.addView(primaryButton("保活方案（标准 / ADB / Root）") { showKeepAliveDialog() })
        col.addView(row(
            "忽略电池优化" to {
                AlertDialog.Builder(this)
                    .setTitle("加入电池优化白名单")
                    .setMessage(
                        "不加白名单时，熄屏 10~30 分钟后系统进入 Doze：CPU 休眠、应用网络被掐，" +
                            "服务器会「卡住」并让玩家掉线。\n\n这是最安全、最推荐的方案。"
                    )
                    .setPositiveButton("去设置") { _, _ -> KeepAlive.requestIgnoreBatteryOptimizations(this) }
                    .setNegativeButton("取消", null)
                    .show()
            },
            "adb 命令" to { showAdbCommands() }
        ))
        col.addView(note("关于", "MC 局域网开服 v" + Constants.VERSION + "\n\n" +
            "· 服务端来自 minecraft.net / piston-data.mojang.com 官方源；\n" +
            "· 首次启动会自动写入 eula=true，代表你同意 Minecraft EULA；\n" +
            "· 基岩版仅提供官方下载与配置编辑。"))
        return scroll
    }

    /** 启动/停止的统一入口（按钮和调试触发都走这里）。 */
    private fun startOrStopServer() {
        val server = Server.byType(Server.JAVA)
        val running = probeRunning
        if (running) {
            if (probeAttached) {
                ServerService.stop(this)
                ui.postDelayed({ render() }, 1500)
            } else {
                AlertDialog.Builder(this)
                    .setTitle("结束后台服务端")
                    .setMessage("检测到服务端仍在后台运行（pid " + probePid + "），需要再结束一次。")
                    .setPositiveButton("结束它") { _, _ -> forceStopServer(server) }
                    .setNegativeButton("取消", null)
                    .show()
            }
            return
        }
        ensureNotificationPermission()
        val major = AppPrefs.requiredJavaMajor()
        val java = Jdk.find()
        if (java == null || (major > 0 && !JdkCatalog.isInstalled(major))) {
            val need = if (major > 0) major else JdkCatalog.requiredMajor(AppPrefs.lastVersion())
            ensureJava(need) {
                ServerService.start(this, Server.JAVA)
                ui.postDelayed({ render() }, 1500)
            }
            return
        }
        if (!KeepAlive.isIgnoringBatteryOptimizations(this)) {
            AlertDialog.Builder(this)
                .setTitle("建议先加入电池优化白名单")
                .setMessage("否则熄屏一段时间后系统会进入 Doze，服务器会掉线。")
                .setPositiveButton("去设置") { _, _ -> KeepAlive.requestIgnoreBatteryOptimizations(this) }
                .setNeutralButton("直接启动") { _, _ ->
                    ServerService.start(this, Server.JAVA)
                    ui.postDelayed({ render() }, 1500)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        ServerService.start(this, Server.JAVA)
        ui.postDelayed({ render() }, 1500)
    }
    private fun showJavaDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
        }
        for (major in listOf(17, 21, 25)) {
            val installed = JdkCatalog.isInstalled(major)
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            line.addView(TextView(this).apply {
                text = JdkCatalog.desc(major) + if (installed) "  ✔ 已装" else ""
                textSize = 13f
                setTextColor(getColor(R.color.text))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(Button(this).apply {
                text = if (installed) "重装" else "下载"
                isAllCaps = false
                textSize = 13f
                setOnClickListener { installJava(major) }
            })
            box.addView(line)
        }
        box.addView(TextView(this).apply {
            text = "Java 8 用于 1.16.5 及更早版本，Termux 仓库已不再提供。\n" +
                "每个包约 110MB，解压后约 250MB，建议连 Wi-Fi。"
            textSize = 12f
            setTextColor(getColor(R.color.textDim))
            setPadding(0, dp(10), 0, 0)
        })
        AlertDialog.Builder(this)
            .setTitle("下载 Java 运行时")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun installJava(major: Int, onReady: (() -> Unit)? = null) {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val status = TextView(this).apply {
            setTextColor(getColor(R.color.textDim))
            textSize = 12f
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), 0)
            addView(Text(("正在安装 " + JdkCatalog.desc(major))))
            addView(bar)
            addView(status)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("安装 Java " + major)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("后台继续", null)
            .create()
        dialog.show()
        io.execute {
            try {
                JdkCatalog.install(major) { done, total, label ->
                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                    ui.post {
                        if (pct >= 0) {
                            bar.isIndeterminate = false
                            bar.progress = pct
                        } else {
                            bar.isIndeterminate = true
                        }
                        status.text = label +
                            if (total > 0) "  " + (done / 1048576) + "MB / " + (total / 1048576) + "MB" else ""
                    }
                }
                ui.post {
                    dialog.dismiss()
                    toast("Java $major 安装完成")
                    render()
                    onReady?.invoke()
                }
            } catch (t: Throwable) {
                ui.post {
                    dialog.dismiss()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("安装失败")
                        .setMessage(
                            (t.message ?: t.javaClass.simpleName) +
                                "\n\n也可以点「导入 JDK 文件夹」手动安装。"
                        )
                        .setPositiveButton("好", null)
                        .show()
                }
            }
        }
    }

    private fun ensureJava(major: Int, onReady: () -> Unit) {
        if (JdkCatalog.isInstalled(major)) {
            AppPrefs.setRequiredJavaMajor(major)
            onReady()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("需要 " + JdkCatalog.desc(major))
            .setMessage("这个服务端版本需要 Java $major，本机还没装。现在从镜像下载安装吗？（约 110MB）")
            .setPositiveButton("下载") { _, _ -> installJava(major, onReady) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun primaryButton(text: String, action: () -> Unit): View {
        val b = Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 15f
            setBackgroundColor(getColor(R.color.cardActive))
            setTextColor(getColor(R.color.accent))
            setOnClickListener { action() }
        }
        b.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        return b
    }

    private fun Text(text: String): android.widget.TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text))
        textSize = 13f
    }

    private fun showJdkDialog() {
        val input = EditText(this).apply {
            hint = "本应用目录下的 JDK 路径，或任意可读的 java"
            setText(AppPrefs.jdkPath())
        }
        AlertDialog.Builder(this)
            .setTitle("Java 运行时")
            .setMessage("填写 JDK 根目录（含 bin/java）或 java 可执行文件路径。留空表示自动查找。\n" +
                "注意：Termux 的私有目录本应用读不到，请用「导入 JDK 文件夹」。")
            .setView(input)
            .setPositiveButton("保存并检测") { _, _ ->
                val path = input.text.toString().trim()
                AppPrefs.setJdkPath(path)
                probeIo.execute {
                    val found = Jdk.find()
                    val info = if (found == null) "未找到 java" else Jdk.probe(found)
                    ui.post {
                        AlertDialog.Builder(this)
                            .setTitle(if (found == null) "未检测到 Java" else "检测结果")
                            .setMessage(info)
                            .setPositiveButton("好", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showJdkHelp() {
        val text = buildString {
            append("Minecraft 服务端需要 aarch64 Linux（bionic）JDK 21，Windows/macOS 的 JDK 用不了。\n\n")
            append("推荐做法：\n")
            append("1. 手机装 Termux，执行 pkg install openjdk-21；\n")
            append("2. 或者从 Termux 官方仓库下载 openjdk-21 的 aarch64 包：\n")
            append("   packages.termux.dev/apt/termux-main/pool/main/o/openjdk-21/\n")
            append("3. 把它解压到手机存储（例如 /sdcard/Download/jdk），得到 bin/ lib/ conf/；\n")
            append("4. 回到本页点「导入 JDK 文件夹」，选中那个目录。\n\n")
            append("应用会把文件复制进自己的目录并设置执行权限，然后自动检测版本。")
        }
        AlertDialog.Builder(this).setTitle("怎么准备 JDK").setMessage(text)
            .setPositiveButton("好", null).show()
    }

    private fun pickJdkTree() {
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                REQ_JDK_TREE
            )
        } catch (t: Throwable) {
            toast("系统没有文件选择器，请在「设置」里手动填写路径")
        }
    }

    private fun importJdk(uri: Uri) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("导入 JDK")
            .setMessage("准备中…")
            .setCancelable(false)
            .create()
        dialog.show()
        JdkImporter.importTree(this, uri, object : JdkImporter.Callback {
            override fun progress(text: String) {
                ui.post { dialog.setMessage(text) }
            }

            override fun done(ok: Boolean, message: String) {
                ui.post {
                    dialog.dismiss()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(if (ok) "JDK 导入成功" else "导入失败")
                        .setMessage(message)
                        .setPositiveButton("好", null)
                        .show()
                    render()
                }
            }
        })
    }

    private fun showDataDir() {
        val text = buildString {
            append("数据目录（文件管理器可直接打开）：\n")
            append(Storage.PUBLIC_ROOT).append("\n\n")
            append("· server.jar —— Java 版服务端\n")
            append("· server.properties —— 配置\n")
            append("· world/ —— 存档\n")
            append("· logs/、console.log —— 日志\n")
            append("· java-17 / java-21 / java-25 —— Java 运行时副本\n")
            append("· bedrock/ —— 基岩版官方包\n")
            append("· downloads/ —— 下载缓存（装完自动删除）\n\n")
            append("注意：/sdcard 是 noexec 挂载，程序不能从那里运行，")
            append("所以真正执行的 Java 是应用私有目录里的副本；这份是给你看和备份的。")
        }
        AlertDialog.Builder(this).setTitle("数据目录").setMessage(text).setPositiveButton("好", null).show()
    }

    // ------------------------------------------------------------ 下载

    private fun downloadDialog(server: Server) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
        }
        val spinner = Spinner(this)
        val versions = arrayListOf("最新正式版")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, versions)
        val status = TextView(this).apply {
            setTextColor(getColor(R.color.textDim))
            setTextSize(12f)
            setPadding(0, dp(6), 0, 0)
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        box.addView(TextView(this).apply {
            text = "版本"
            setTextColor(getColor(R.color.textDim))
        })
        box.addView(spinner)
        box.addView(bar)
        box.addView(status)

        if (type == Server.JAVA) {
            io.execute {
                try {
                    val list = PistonApi.releases(30)
                    ui.post {
                        for (r in list) versions.add(r.id)
                        (spinner.adapter as ArrayAdapter<String>).notifyDataSetChanged()
                        status.text = "共 " + list.size + " 个正式版可选"
                    }
                } catch (t: Throwable) {
                    ui.post { status.text = "版本列表获取失败，仍可下载最新正式版" }
                }
            }
        } else {
            io.execute {
                try {
                    val (ver, _) = ServerDownloader.bedrockLatest { }
                    ui.post {
                        versions.clear()
                        versions.add(ver)
                        (spinner.adapter as ArrayAdapter<String>).notifyDataSetChanged()
                        status.text = "官方最新：$ver"
                    }
                } catch (t: Throwable) {
                    ui.post { status.text = "查询失败：" + t.message }
                }
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (type == Server.JAVA) "下载 Java 版服务端" else "下载基岩版服务端")
            .setView(box)
            .setPositiveButton("开始下载", null)
            .setNeutralButton("官网页面") { _, _ -> openUrl(Constants.BDS_HOME) }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                if (type == Server.JAVA) {
                    val pick = spinner.selectedItemPosition
                    val version = if (pick <= 0) null else versions[pick]
                    io.execute {
                        try {
                            val res = ServerDownloader.downloadJava(server, version) { done, total, text ->
                                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                                ui.post {
                                    bar.progress = pct
                                    status.text = "$text  ${done / 1048576}MB" + if (total > 0) "/${total / 1048576}MB" else ""
                                }
                            }
                            ui.post {
                                status.text = "完成：" + res.version + "（需要 " + JdkCatalog.desc(res.javaMajor) + "）"
                                toast("服务端已更新到 " + res.version)
                                render()
                                dialog.dismiss()
                                // 顺手检查运行环境；没有就提示装
                                ensureJava(res.javaMajor) { }
                            }
                        } catch (t: Throwable) {
                            ui.post {
                                status.text = "失败：" + t.message
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            }
                        }
                    }
                } else {
                    io.execute {
                        try {
                            val (ver, url) = ServerDownloader.bedrockLatest { msg -> ui.post { status.text = msg } }
                            val res = ServerDownloader.downloadBedrock(server, ver, url) { done, total, text ->
                                ui.post {
                                    bar.progress = if (total > 0) (done * 100 / total).toInt() else 0
                                    status.text = "$text ${done / 1048576}MB"
                                }
                            }
                            ui.post {
                                status.text = "已下载：" + res.file.name +
                                    "\n（x86_64 Linux 包，Android 无法直接运行）"
                            }
                        } catch (t: Throwable) {
                            ui.post {
                                status.text = "失败：" + t.message
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            }
                        }
                    }
                }
            }
        }
        dialog.show()
    }



    /** 启动时如果没有可用的存储权限就明确弹窗，而不是静默退回私有目录。 */
    /** 首次启动：强调必须给「写入手机存储」权限，否则存档无法导出。 */
    private fun showWelcomeIfNeeded() {
        if (AppPrefs.firstRunNoticeShown()) return
        AppPrefs.setFirstRunNoticeShown()
        AlertDialog.Builder(this)
            .setTitle("欢迎使用 MC 局域网开服 v" + Constants.VERSION)
            .setMessage(
                "必须先授予「写入手机存储」权限。\n" +
                    "否则服务器存档、配置、日志都写不到 /storage/emulated/0/mcserver，" +
                    "你就无法用文件管理器导出或备份存档，换手机、升级版本时会丢档。\n\n" +
                    "授予后目标目录固定为：/storage/emulated/0/mcserver\n\n" +
                    "作者 B 站 UID：102392702（欢迎关注）\n" +
                    "项目已在 GitHub 开源，源码见项目仓库。"
            )
            .setPositiveButton("去授予权限") { _, _ -> askStorageIfNeeded(force = true) }
            .setNeutralButton("打开 B 站主页") { _, _ -> openUrl(Constants.AUTHOR_BILI) }
            .setNegativeButton("稍后", null)
            .show()
    }

    /** 关于作者 / 开源信息。 */
    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("关于")
            .setMessage(
                "MC 局域网开服 v" + Constants.VERSION + "\n\n" +
                    "作者 B 站 UID：102392702（欢迎关注）\n" +
                    "项目已在 GitHub 开源\n\n" +
                    "本应用不含 Minecraft 服务端本体，启动时从官方地址下载，" +
                    "运行需遵守 Minecraft EULA。"
            )
            .setPositiveButton("打开 B 站主页") { _, _ -> openUrl(Constants.AUTHOR_BILI) }
            .setNegativeButton("关闭", null)
            .show()
    }


    private fun askStorageIfNeeded(force: Boolean = false) {
        if (Storage.isWritable() && !force) return
        val canAsk = Storage.canAskRuntime(this)
        AlertDialog.Builder(this)
            .setTitle("需要存储权限")
            .setMessage(
                "服务端、存档和 Java 都要放在：\n" + Storage.PUBLIC_ROOT + "\n\n" +
                    (if (canAsk) "点「去授权」后请选择「允许」。"
                    else "系统已不再弹出权限框，点「去授权」后请打开「所有文件访问」或本应用的「存储」开关。") +
                    "\n\n" + Storage.stateText(this)
            )
            .setPositiveButton("去授权") { _, _ -> Storage.requestPermission(this) }
            .setNeutralButton("打开系统设置") { _, _ -> Storage.openAppSettings(this) }
            .setNegativeButton("稍后", null)
            .show()
    }


    // ------------------------------------------------------------ 保活方案

    private fun showKeepAliveDialog() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(4))
        }

        // 1. 标准方案
        col.addView(Text(("① 标准方案（推荐，无风险）")).apply {
            setTextColor(getColor(R.color.accent))
            textSize = 15f
        })
        col.addView(Text(if (KeepAlive.isIgnoringBatteryOptimizations(this))
            "已在电池优化白名单里 ✅" else "未加入白名单，熄屏后会被 Doze 断网"))
        col.addView(Button(this).apply {
            text = "加入电池优化白名单"
            isAllCaps = false
            setOnClickListener { KeepAlive.requestIgnoreBatteryOptimizations(this@MainActivity) }
        })

        // 2. ADB
        col.addView(Text(("② ADB 方案（需要电脑，一次性）")).apply {
            setTextColor(getColor(R.color.accent))
            textSize = 15f
            setPadding(0, dp(14), 0, 0)
        })
        col.addView(Text("不需要 root，命令执行一次后重启仍然有效。"))
        col.addView(Button(this).apply {
            text = "复制 adb 命令"
            isAllCaps = false
            setOnClickListener { showAdbCommands() }
        })

        // 3. Root
        col.addView(Text(("③ Root 方案（Magisk / KernelSU）")).apply {
            setTextColor(getColor(R.color.accent))
            textSize = 15f
            setPadding(0, dp(14), 0, 0)
        })
        val rootStatus = Text("正在检测 root…")
        col.addView(rootStatus)
        col.addView(Text(KeepAlive.riskText()).apply {
            setTextColor(getColor(R.color.warn))
            textSize = 12f
        })
        val rootBtnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val enableBtn = Button(this).apply {
            text = "开启 root 保活"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { confirmEnableRootKeepAlive() }
        }
        val disableBtn = Button(this).apply {
            text = "关闭并恢复"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { disableRootKeepAlive() }
        }
        rootBtnRow.addView(enableBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        rootBtnRow.addView(disableBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(rootBtnRow)

        val rootRun = android.widget.CheckBox(this).apply {
            text = "以 root 后台方式运行服务端（开启后应用关不掉，只能重启手机）"
            setTextColor(getColor(R.color.text))
            isChecked = AppPrefs.rootRunServer()
            setOnCheckedChangeListener { box, checked ->
                if (!checked) {
                    AppPrefs.setRootRunServer(false)
                    return@setOnCheckedChangeListener
                }
                // 先撤销勾选，等用户读完风险再决定
                box.isChecked = false
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("⚠ 以 root 后台方式运行服务端")
                    .setMessage(
                        "开启后行为会变成这样，请确认你接受：\n\n" +
                            "1. 服务端会以 root 系统进程运行，不再是应用的一部分。\n" +
                            "2. 关闭应用、划掉后台，服务端仍然继续运行。\n" +
                            "3. 本应用无法停止它 —— 只有重启手机才能真正关掉。\n" +
                            "4. 想正常关服，请在配置页「授予 OP」里写好玩家名，\n" +
                            "   让玩家进服后执行 /stop（服务器会在存档里保存后退出）。\n\n" +
                            "root 跑出来的存档属主是 root，应用之后可能改不了配置。\n" +
                            "建议只在专门的旧手机上这么用。"
                    )
                    .setPositiveButton("我接受，开启") { _, _ ->
                        AppPrefs.setRootRunServer(true)
                        box.isChecked = true
                        toast("已开启：下次启动服务端将以 root 后台运行")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        col.addView(rootRun)

        col.addView(Text("说明：root 后台模式在部分机型上不可用（Magisk 的 su 不能执行 App 私有目录里的程序），" +
            "此时会自动退回普通模式。可用的情况下服务端会脱离应用运行，应用无法停止它，" +
            "只能让有 OP 的玩家在游戏里执行 /stop，或者重启手机。").apply {
            setTextColor(getColor(R.color.textDim))
            textSize = 12f
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("保活方案")
            .setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton("关闭", null)
            .create()
        dialog.show()

        io.execute {
            val info = KeepAlive.detectRoot()
            ui.post {
                if (info.available) {
                    rootStatus.text = "root 可用：${info.flavor}${if (info.version.isNotBlank()) " " + info.version else ""}"
                    enableBtn.isEnabled = true
                } else {
                    rootStatus.text = "未检测到 root（没有 su 或未授权）。可以使用①②方案。"
                }
                disableBtn.isEnabled = AppPrefs.rootKeepAlive() ||
                    AppPrefs.rootDisableDoze() || AppPrefs.rootRunServer()
            }
        }
    }

    private fun confirmEnableRootKeepAlive() {
        AlertDialog.Builder(this)
            .setTitle("确认开启 root 保活？")
            .setMessage(KeepAlive.riskText() + "\n\n决定权在你：如果不想承担这些风险，请用「标准方案」或「ADB 方案」。")
            .setPositiveButton("我已了解风险，开启") { _, _ -> enableRootKeepAlive() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun enableRootKeepAlive() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("正在开启…")
            .setMessage("请在 root 管理器（Magisk / KernelSU）里允许授权。")
            .setCancelable(false)
            .create()
        dialog.show()
        io.execute {
            val pkg = packageName
            val steps = StringBuilder()
            steps.append("白名单：").append(KeepAlive.rootAddWhitelist(pkg)).append('\n')
            steps.append("Wi-Fi 常开：").append(KeepAlive.rootWifiAlwaysOn()).append('\n')
            val disableDoze = AppPrefs.rootDisableDoze()
            steps.append("开机脚本：").append(KeepAlive.installBootScript(this, pkg, disableDoze))
            val ok = KeepAlive.rootIsWhitelisted(pkg)
            AppPrefs.setRootKeepAlive(true)
            ui.post {
                dialog.dismiss()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(if (ok) "root 保活已开启" else "已执行，请检查")
                    .setMessage(steps.toString())
                    .setPositiveButton("好", null)
                    .show()
                render()
            }
        }
    }

    private fun disableRootKeepAlive() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("正在恢复…")
            .setMessage("删除开机脚本并撤销白名单")
            .setCancelable(false)
            .create()
        dialog.show()
        io.execute {
            val pkg = packageName
            val out = StringBuilder()
            out.append("删除开机脚本：").append(KeepAlive.removeBootScript()).append('\n')
            out.append("移出白名单：").append(KeepAlive.rootRemoveWhitelist(pkg)).append('\n')
            if (AppPrefs.rootDisableDoze()) {
                out.append("恢复 Doze：").append(KeepAlive.rootEnableDoze())
            }
            AppPrefs.setRootKeepAlive(false)
            AppPrefs.setRootDisableDoze(false)
            AppPrefs.setRootRunServer(false)
            ui.post {
                dialog.dismiss()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("已恢复")
                    .setMessage(out.toString())
                    .setPositiveButton("好", null)
                    .show()
                render()
            }
        }
    }

    /** 结束后台遗留的服务端（root：pkill -TERM，超时 -KILL）。 */
    /** 收集诊断信息，方便定位「为什么结束不掉」。 */
    private fun diagnoseServer(server: Server) {
        val dlg = AlertDialog.Builder(this)
            .setTitle("诊断中…")
            .setMessage("正在执行 ps / /proc 扫描 / 端口检查")
            .setCancelable(false)
            .create()
        dlg.show()
        io.execute {
            val text = try {
                ServerProbe.diagnose(server)
            } catch (t: Throwable) {
                "诊断失败：" + (t.message ?: t.javaClass.simpleName)
            }
            ui.post {
                dlg.dismiss()
                AlertDialog.Builder(this)
                    .setTitle("诊断结果")
                    .setMessage(text)
                    .setPositiveButton("复制") { _, _ ->
                        KeepAlive.copyToClipboard(this, "diag", text)
                        toast("已复制，可发给我")
                    }
                    .setNegativeButton("关闭", null)
                    .show()
            }
        }
    }

    /** 最后手段：killall -9 java（会结束所有 java 进程）。 */
    private fun forceKillAllJava(server: Server) {
        AlertDialog.Builder(this)
            .setTitle("强制结束所有 java 进程？")
            .setMessage("会执行 killall -9 java，结束本机所有 java 进程（包括其它应用在用的）。" +
                "这种做法不会给服务端存盘的机会。\n\n仅在其它方法都无效时使用。")
            .setPositiveButton("确定执行") { _, _ ->
                probeIo.execute {
                    val out = KeepAlive.runRoot("killall -9 java; sleep 1; pidof java; echo DONE", 20000)
                    ui.post {
                        AlertDialog.Builder(this)
                            .setTitle("结果")
                            .setMessage(out.ifBlank { "无输出" })
                            .setPositiveButton("好", null)
                            .show()
                        server.pidFile.delete()
                        render()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    private fun forceStopServer(server: Server) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("正在结束后台服务端…")
            .setMessage("先发送 TERM 让服务端存盘，最多等 20 秒")
            .setCancelable(false)
            .create()
        dialog.show()
        io.execute {
            val result = try {
                ServerProbe.killDetached(server)
            } catch (t: Throwable) {
                ServerProbe.KillResult(false, "调用失败：" + (t.message ?: t.javaClass.simpleName))
            }
            ui.post {
                dialog.dismiss()
                AlertDialog.Builder(this)
                    .setTitle(if (result.ok) "已结束后台服务端" else "结束失败")
                    .setMessage(result.log.ifBlank { "没有输出" })
                    .setPositiveButton("好", null)
                    .setNeutralButton("复制日志") { _, _ ->
                        KeepAlive.copyToClipboard(this, "kill", result.log)
                        toast("已复制")
                    }
                    .show()
                render()
            }
        }
    }
    private fun showAdbCommands() {
        val cmds = KeepAlive.adbCommands(packageName)
        AlertDialog.Builder(this)
            .setTitle("用 adb 加白名单（需一次性连电脑）")
            .setMessage(cmds)
            .setPositiveButton("复制") { _, _ ->
                KeepAlive.copyToClipboard(this, "adb", cmds)
                toast("已复制")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun rootAction(title: String, action: () -> String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("正在执行…（如果弹出 root 授权请允许）")
            .setCancelable(false)
            .create()
        dialog.show()
        io.execute {
            val out = action()
            ui.post {
                dialog.dismiss()
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(title)
                    .setMessage(out.ifBlank { "无输出（通常表示成功）" })
                    .setPositiveButton("好", null)
                    .show()
            }
        }
    }
    private fun openAppSettings() {
        try {
            val i = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.parse("package:" + packageName)
            startActivity(i)
        } catch (t: Throwable) {
            toast("请手动到 系统设置 → 应用 → MC局域网开服 → 权限 里打开存储权限")
        }
    }
    private fun commandDialog() {
        val input = EditText(this).apply { hint = "list / stop / say 你好" }
        AlertDialog.Builder(this)
            .setTitle("发送控制台命令")
            .setView(input)
            .setPositiveButton("发送") { _, _ ->
                val c = input.text.toString().trim()
                if (c.isNotEmpty()) {
                    if (ServerProcess.current?.send(c) != true) toast("服务器未运行") else toast("已发送")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 小工具

    private fun startTickers() {
        ui.post(object : Runnable {
            override fun run() {
                if (tab == TAB_CONSOLE && ::consoleView.isInitialized) syncConsole()
                ui.postDelayed(this, 1000)
            }
        })
        ui.post(object : Runnable {
            override fun run() {
                refreshBadge()
                ui.postDelayed(this, 3000)
            }
        })
    }

    /**
     * 控制台刷新：只在内容变化时重设文本，且仅当用户原本就在底部时才自动滚到底，
     * 避免每秒 fullScroll 造成「一会在顶一会到底」的抖动。
     */
    private fun syncConsole() {
        val lines = Server.byType(Server.JAVA).log.lines().takeLast(400)
        val text = lines.joinToString("\n")
        if (text == lastConsoleText) return
        val sv = consoleScroll
        val stickToBottom = sv == null || !sv.canScrollVertically(1)
        val keepY = sv?.scrollY ?: 0
        lastConsoleText = text
        consoleView.text = text
        if (sv == null) return
        sv.post {
            if (stickToBottom) {
                sv.fullScroll(View.FOCUS_DOWN)
            } else {
                sv.scrollTo(0, keepY)
            }
        }
    }
    private fun column(padBottom: Int = 24): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(padBottom))
    }

    private fun row(vararg items: Pair<String, () -> Unit>): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        for ((text, action) in items) {
            val b = Button(this).apply {
                this.text = text
                isAllCaps = false
                textSize = 13f
                setBackgroundColor(getColor(R.color.card))
                setOnClickListener { action() }
            }
            r.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return r
    }

    private fun note(title: String, body: String): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(getColor(R.color.card))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        card.addView(TextView(this).apply {
            text = title
            setTextColor(getColor(R.color.accent))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        })
        card.addView(TextView(this).apply {
            text = body
            setTextColor(getColor(R.color.text))
            textSize = 12f
            setTextIsSelectable(true)
        })
        return card
    }

    private fun kv(k: String, v: String): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        r.addView(TextView(this).apply {
            text = k
            textSize = 13f
            setTextColor(getColor(R.color.textDim))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(TextView(this).apply {
            text = v
            textSize = 13f
            setTextColor(getColor(R.color.text))
            setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f))
        return r
    }

    private fun label(text: String): View = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.textDim))
        textSize = 13f
        setPadding(0, dp(14), 0, dp(4))
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return c.getString(idx) ?: "file"
            }
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 42)
        }
    }

    private fun clipboard(): ClipboardManager = getSystemService(ClipboardManager::class.java)

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            toast("无法打开浏览器")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val TAB_STATUS = 0
        const val TAB_CONSOLE = 1
        const val REQ_JDK_TREE = 11
        const val TAB_CONFIG = 2
        const val TAB_SETTINGS = 3
        const val REQ_IMPORT = 7
    }
}
