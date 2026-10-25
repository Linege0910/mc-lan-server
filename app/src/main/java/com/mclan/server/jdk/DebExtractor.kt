package com.mclan.server.jdk

import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * 解 .deb（ar 归档 + data.tar.{xz,gz,plain}），只取 `data/data/com.termux/files/usr/` 之后的部分。
 *
 * Android 上没有 tar/xz 命令行，所以全部用纯 Java：ar 头固定 60 字节、tar 是 512 字节块、
 * xz 用 org.tukaani:xz（纯 Java，约 100 KB）。
 */
object DebExtractor {

    private const val TERMUX_PREFIX = "data/data/com.termux/files/usr/"

    interface Progress {
        fun onEntry(path: String)
    }

    /** 读控制段里的字段（Package、Depends、Version…）。 */
    fun readControl(deb: File): Map<String, String> {
        val tmp = File(deb.parentFile, ".control-" + System.nanoTime()).apply { mkdirs() }
        try {
            extract(deb, tmp, stripTermuxPrefix = false, progress = null, member = "control.tar")
            val control = File(tmp, "control")
            if (!control.isFile) return emptyMap()
            val map = HashMap<String, String>()
            for (line in control.readLines(Charsets.UTF_8)) {
                if (line.startsWith(" ") || !line.contains(":")) continue
                val k = line.substringBefore(':').trim()
                val v = line.substringAfter(':').trim()
                if (k.isNotEmpty()) map[k] = v
            }
            return map
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** "libandroid-shmem, libiconv (>= 1.0), foo | bar" → ["libandroid-shmem","libiconv"] */
    fun parseDepends(control: Map<String, String>): List<String> {
        val raw = control["Depends"] ?: control["Pre-Depends"] ?: return emptyList()
        return raw.split(',')
            .map { it.substringBefore('|').trim() }
            .map { it.substringBefore('(').trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    fun extract(
        deb: File,
        dest: File,
        stripTermuxPrefix: Boolean = true,
        progress: Progress? = null,
        member: String = "data.tar"
    ) {
        var dataOffset = -1L
        var dataSize = 0L
        RandomAccessFile(deb, "r").use { raf ->
            val magic = ByteArray(8)
            raf.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "!<arch>\n") {
                throw java.io.IOException("不是合法的 .deb（ar）文件")
            }
            var pos = 8L
            while (pos + 60 <= raf.length()) {
                raf.seek(pos)
                val header = ByteArray(60)
                raf.readFully(header)
                val name = String(header, 0, 16, Charsets.US_ASCII).trim()
                val size = String(header, 48, 10, Charsets.US_ASCII).trim().toLongOrNull() ?: 0L
                if (name.startsWith(member)) {
                    dataOffset = pos + 60
                    dataSize = size
                    break
                }
                pos += 60 + size + (size % 2)
            }
        }
        if (dataOffset < 0) throw java.io.IOException("deb 里没有 " + member)

        val slice = SliceStream(deb, dataOffset, dataSize)
        val buffered = BufferedInputStream(slice, 1 shl 16)
        buffered.mark(8)
        val head = ByteArray(6)
        val n = buffered.read(head)
        buffered.reset()
        val body: InputStream = when {
            n >= 6 && head[0] == 0xFD.toByte() && head[1] == 0x37.toByte() &&
                head[2] == 0x7A.toByte() && head[3] == 0x58.toByte() -> XZInputStream(buffered)
            n >= 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte() -> java.util.zip.GZIPInputStream(buffered)
            else -> buffered
        }
        body.use { tar -> readTar(tar, dest, stripTermuxPrefix, progress) }
    }

    private fun readTar(tar: InputStream, dest: File, strip: Boolean, progress: Progress?) {
        val block = ByteArray(512)
        var longName: String? = null
        var longLink: String? = null
        // 符号链接 / 硬链接要等目标落地后再建，先记下来
        val pendingLinks = ArrayList<Pair<File, String>>()
        while (true) {
            if (!readFully(tar, block)) break
            if (block.all { it == 0.toByte() }) break

            var name = String(block, 0, 100, Charsets.UTF_8).trimEnd('\u0000')
            val prefix = String(block, 345, 155, Charsets.UTF_8).trimEnd('\u0000')
            if (prefix.isNotEmpty()) name = prefix + "/" + name
            longName?.let {
                name = it
                longName = null
            }
            var link = String(block, 157, 100, Charsets.UTF_8).trimEnd('\u0000')
            longLink?.let {
                link = it
                longLink = null
            }
            val size = tarNumber(block, 124, 12)
            val type = block[156].toInt().toChar()
            val padded = ((size + 511) / 512) * 512

            when (type) {
                'L' -> {
                    val buf = ByteArray(size.toInt())
                    readFully(tar, buf)
                    longName = String(buf, Charsets.UTF_8).trimEnd('\u0000')
                    skipFully(tar, padded - size)
                }
                'K' -> {
                    val buf = ByteArray(size.toInt())
                    readFully(tar, buf)
                    longLink = String(buf, Charsets.UTF_8).trimEnd('\u0000')
                    skipFully(tar, padded - size)
                }
                'x', 'g' -> {
                    val buf = ByteArray(size.toInt())
                    readFully(tar, buf)
                    val pax = String(buf, Charsets.UTF_8)
                    if (type == 'x') {
                        Regex("(?m)^\\d+ path=(.+)$").find(pax)?.let {
                            longName = it.groupValues[1].trimEnd('\u0000')
                        }
                        Regex("(?m)^\\d+ linkpath=(.+)$").find(pax)?.let {
                            longLink = it.groupValues[1].trimEnd('\u0000')
                        }
                    }
                    skipFully(tar, padded - size)
                }
                '5' -> {
                    target(name, dest, strip)?.mkdirs()
                    skipFully(tar, padded)
                }
                '2', '1' -> {
                    // 符号链接（如 libz.so.1 -> libz.so.1.3.2）和硬链接
                    val out = target(name, dest, strip)
                    if (out != null && link.isNotEmpty()) pendingLinks.add(out to link)
                    skipFully(tar, padded)
                }
                '0', '\u0000', '7' -> {
                    val out = target(name, dest, strip)
                    if (out == null || name.endsWith("/")) {
                        skipFully(tar, padded)
                    } else {
                        out.parentFile?.mkdirs()
                        try {
                            out.outputStream().use { copyExactly(tar, it, size) }
                        } catch (e: Throwable) {
                            skipFully(tar, size)
                        }
                        skipFully(tar, padded - size)
                        progress?.onEntry(name)
                    }
                }
                else -> skipFully(tar, padded)
            }
        }
        materializeLinks(pendingLinks)
    }

    /**
     * 落地链接：先试 symlink（API 26+ 可用），失败就复制目标文件，
     * 这样 libz.so.1 这类名字也一定能解析到内容。
     */
    private fun materializeLinks(pending: List<Pair<File, String>>) {
        for ((linkFile, linkTarget) in pending) {
            try {
                if (linkFile.exists()) continue
                if (linkTarget.startsWith("/")) continue
                val resolved = File(linkFile.parentFile, linkTarget)
                if (!resolved.exists()) continue
                linkFile.parentFile?.mkdirs()
                try {
                    android.system.Os.symlink(linkTarget, linkFile.absolutePath)
                } catch (t: Throwable) {
                    resolved.copyTo(linkFile, overwrite = true)
                }
            } catch (t: Throwable) {
                android.util.Log.w("MCLanDeb", "link failed: " + linkFile.name)
            }
        }
    }

    /** tar 的数字字段：八进制文本（可能带 NUL/空格填充），或 GNU base-256 编码。 */
    private fun tarNumber(block: ByteArray, off: Int, len: Int): Long {
        val first = block[off].toInt() and 0xFF
        if (first and 0x80 != 0) {
            var value = (first and 0x7F).toLong()
            for (i in off + 1 until off + len) {
                value = (value shl 8) or (block[i].toLong() and 0xFF)
            }
            return value
        }
        val text = String(block, off, len, Charsets.US_ASCII)
            .trim { it == ' ' || it == '\u0000' || it == '\n' || it == '\r' }
        if (text.isEmpty()) return 0
        return text.toLongOrNull(8) ?: 0
    }

    private fun target(name: String, dest: File, strip: Boolean): File? {
        var rel = name.trimStart('.').trimStart('/')
        if (strip) {
            if (!rel.startsWith(TERMUX_PREFIX)) return null
            rel = rel.removePrefix(TERMUX_PREFIX)
        }
        rel = rel.trimEnd('/')
        if (rel.isEmpty() || rel.contains("..")) return null
        // demo/sample/man 对运行服务端没用，跳过以节省空间
        val lower = rel.lowercase()
        if (lower.startsWith("demo/") || lower.startsWith("sample/") || lower.startsWith("man/") ||
            lower.contains("/demo/") || lower.contains("/sample/")
        ) return null
        return File(dest, rel)
    }

    private fun copyExactly(input: InputStream, out: OutputStream, size: Long) {
        val buf = ByteArray(1 shl 16)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        val buf = ByteArray(1 shl 12)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) return
            left -= n
        }
    }

    /** 只暴露 deb 里某一段字节的输入流。 */
    private class SliceStream(file: File, offset: Long, length: Long) : InputStream() {
        private val raf = RandomAccessFile(file, "r")
        private var remaining = length

        init {
            raf.seek(offset)
        }

        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = raf.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val want = minOf(len.toLong(), remaining).toInt()
            val n = raf.read(b, off, want)
            if (n > 0) remaining -= n
            return n
        }

        override fun close() {
            raf.close()
        }
    }
}
