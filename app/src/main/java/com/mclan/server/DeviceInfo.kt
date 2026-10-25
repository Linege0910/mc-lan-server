package com.mclan.server

import android.app.ActivityManager
import android.content.Context

/** 设备内存信息，用来给出合理的服务端 -Xmx 上限。 */
object DeviceInfo {

    private var cachedTotalMb = 0

    fun totalRamMb(ctx: Context = Paths.context()): Int {
        if (cachedTotalMb > 0) return cachedTotalMb
        var mb = 0
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            mb = (info.totalMem / (1024 * 1024)).toInt()
        } catch (t: Throwable) {
        }
        if (mb <= 0) {
            // 退化方案：读 /proc/meminfo
            try {
                val line = java.io.File("/proc/meminfo").readLines()
                    .firstOrNull { it.startsWith("MemTotal:") }
                val kb = line?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: 0L
                if (kb > 0) mb = (kb / 1024).toInt()
            } catch (t: Throwable) {
            }
        }
        cachedTotalMb = if (mb > 0) mb else 2048
        return cachedTotalMb
    }

    /**
     * 标称内存。系统报告的 totalMem 会少一点（内核/GPU 占用），
     * 这里向上取到常见的标称容量，这样 16G 手机算出来正好是 8G。
     */
    fun nominalRamMb(): Int {
        val total = totalRamMb()
        val steps = intArrayOf(512, 1024, 1536, 2048, 3072, 4096, 6144, 8192, 12288, 16384, 24576, 32768)
        for (s in steps) if (s >= total) return s
        return ((total + 1023) / 1024) * 1024
    }

    fun totalRamGb(): Double = nominalRamMb() / 1024.0

    /** 服务端内存上限 = 标称总内存的一半（16G → 8G），并给系统留出余量。 */
    fun halfRamMb(): Int {
        val half = (nominalRamMb() / 2 + 255) / 256 * 256
        val safe = minOf(half, maxOf(512, totalRamMb() - 1024))
        return maxOf(512, safe)
    }

    fun formatGb(mb: Int): String {
        val gb = mb / 1024.0
        return if (gb >= 1.0) String.format("%.1fG", gb) else mb.toString() + "M"
    }
}
