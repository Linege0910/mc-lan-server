package com.mclan.server

import android.content.Context
import java.io.File

/**
 * 两种目录：
 *  · appDir()   —— 应用私有目录，唯一能执行程序的地方（JDK 运行副本放这里）
 *  · Storage    —— 内部存储/mcserver，文件管理器可直接访问（服务端、存档、Java 备份）
 */
object Paths {
    private var ctx: Context? = null

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    fun context(): Context = ctx ?: error("Paths.init() 未调用")

    fun appDir(): File {
        val dir = File(context().applicationInfo.dataDir, "files")
        dir.mkdirs()
        return dir
    }

    /** 服务端目录（可见）：/storage/emulated/0/mcserver 本体 */
    fun serversDir(): File = Storage.javaServerDir()

    fun bedrockDir(): File = Storage.bedrockDir()

    /** 可执行的 JDK（私有目录） */
    fun execJdkDir(major: Int): File = File(appDir(), "jdk-$major")
}
