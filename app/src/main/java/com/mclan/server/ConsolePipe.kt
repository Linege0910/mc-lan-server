package com.mclan.server

import java.io.OutputStream

/** 服务端进程的标准输入（控制台命令通道）。 */
object ConsolePipe {
    @Volatile var stdin: OutputStream? = null
}
