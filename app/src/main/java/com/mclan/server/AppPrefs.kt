package com.mclan.server

import android.content.Context
import android.content.SharedPreferences

/** 设置存储。 */
object AppPrefs {
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("mc_server_prefs", Context.MODE_PRIVATE)
    }

    private fun p(): SharedPreferences = prefs ?: error("AppPrefs.init() 未调用")

    /**
     * 服务端内存上限。默认值 = 设备总内存的一半（16G 手机 → 8G），
     * 并且永远不会超过这个上限。
     */
    fun xmxMb(): Int {
        val max = DeviceInfo.halfRamMb()
        val stored = p().getInt("xmx", 0)
        val value = if (stored <= 0) max else stored
        return value.coerceIn(256, max)
    }

    fun setXmxMb(v: Int) {
        val max = DeviceInfo.halfRamMb()
        p().edit().putInt("xmx", v.coerceIn(256, max)).apply()
    }

    fun javaArgs(): String = p().getString("java_args", "") ?: ""
    fun setJavaArgs(v: String) = p().edit().putString("java_args", v).apply()

    fun jdkPath(): String = p().getString("jdk_path", "") ?: ""
    fun setJdkPath(v: String) = p().edit().putString("jdk_path", v).apply()

    fun requiredJavaMajor(): Int = p().getInt("java_major", 0)
    fun setRequiredJavaMajor(v: Int) = p().edit().putInt("java_major", v).apply()

    fun lastVersion(): String = p().getString("last_version", "-") ?: "-"
    fun setLastVersion(v: String) = p().edit().putString("last_version", v).apply()

    // ---------------------------------------------------------------- 保活

    fun rootKeepAlive(): Boolean = p().getBoolean("root_keepalive", false)
    fun setRootKeepAlive(v: Boolean) = p().edit().putBoolean("root_keepalive", v).apply()

    fun rootDisableDoze(): Boolean = p().getBoolean("root_disable_doze", false)
    fun setRootDisableDoze(v: Boolean) = p().edit().putBoolean("root_disable_doze", v).apply()

    /** 鏄惁鍏佽涓存椂鍏抽棴 SELinux 寮哄埗妯″紡锛堝嵄闄╋紝鐢辩敤鎴锋樉寮忛€夋嫨锛夈€?*/
    fun selinuxPermissive(): Boolean = p().getBoolean("selinux_permissive", false)
    fun setSelinuxPermissive(v: Boolean) = p().edit().putBoolean("selinux_permissive", v).apply()

    /** 首次启动的存储权限提示是否已经展示过。 */
    fun firstRunNoticeShown(): Boolean = p().getBoolean("first_run_notice", false)
    fun setFirstRunNoticeShown() = p().edit().putBoolean("first_run_notice", true).apply()

    fun rootRunServer(): Boolean = p().getBoolean("root_run_server", false)
    fun setRootRunServer(v: Boolean) = p().edit().putBoolean("root_run_server", v).apply()
}
