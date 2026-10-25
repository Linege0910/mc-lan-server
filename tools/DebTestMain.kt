import com.mclan.server.jdk.DebExtractor
import java.io.File

/** 本机验证：用真实的 openjdk deb 跑一遍解包算法。 */
fun main(args: Array<String>) {
    val deb = File(args[0])
    val out = File(args[1])
    if (out.exists()) out.deleteRecursively()
    out.mkdirs()
    val start = System.currentTimeMillis()
    var count = 0
    DebExtractor.extract(deb, out) { count++ }
    val secs = (System.currentTimeMillis() - start) / 1000.0
    println("entries=$count  time=${secs}s")

    val java = File(out, "bin/java")
    println("bin/java exists=${java.isFile} size=${if (java.isFile) java.length() else 0}")
    if (java.isFile) {
        val head = ByteArray(4)
        java.inputStream().use { it.read(head) }
        println("magic=" + head.joinToString(" ") { String.format("%02X", it) })
    }
    println("libjvm=" + File(out, "lib/server/libjvm.so").isFile)
    println("modules=" + File(out, "lib/modules").isFile + " size=" + File(out, "lib/modules").length())
    println("totalFiles=" + out.walkTopDown().count { it.isFile })
}
