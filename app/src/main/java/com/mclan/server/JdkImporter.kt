package com.mclan.server

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * 把用户选中的 JDK 文件夹整份复制进应用私有目录，并设置可执行权限。
 *
 * 必须复制进来：Android 上别的应用（例如 Termux）的私有目录是不可读的，
 * 只有放在本应用自己的目录里才既读得到又执行得到。
 */
object JdkImporter {

    const val TARGET_SUBDIR = "jdk"

    fun targetDir(): File = File(Paths.appDir(), TARGET_SUBDIR)

    interface Callback {
        fun progress(text: String)
        fun done(ok: Boolean, message: String)
    }

    fun importTree(ctx: Context, treeUri: Uri, cb: Callback) {
        Thread({
            try {
                val dest = targetDir()
                if (dest.exists()) dest.deleteRecursively()
                dest.mkdirs()
                val rootId = DocumentsContract.getTreeDocumentId(treeUri)
                val total = 0
                var copied = 0
                copyDir(ctx, treeUri, rootId, dest) { name ->
                    copied++
                    if (copied % 25 == 0) cb.progress("已复制 $copied 个文件…")
                }
                cb.progress("设置可执行权限…")
                chmodAll(dest)
                val java = File(dest, "bin/java")
                if (!java.isFile) {
                    cb.done(false, "没有找到 bin/java，请确认选的是 JDK 根目录（含 bin、lib、conf）")
                    return@Thread
                }
                val probe = Jdk.probe(java)
                if (!probe.contains("version", true)) {
                    cb.done(false, "复制完成但无法运行：\n$probe")
                    return@Thread
                }
                AppPrefs.setJdkPath(dest.absolutePath)
                cb.done(true, probe)
            } catch (t: Throwable) {
                cb.done(false, "导入失败：" + (t.message ?: t.javaClass.simpleName))
            }
        }, "jdk-import").start()
    }

    private fun copyDir(
        ctx: Context,
        treeUri: Uri,
        parentDocId: String,
        dest: File,
        onFile: (String) -> Unit
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        ctx.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0)
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                val child = File(dest, name)
                if (DocumentsContract.Document.MIME_TYPE_DIR == mime) {
                    child.mkdirs()
                    copyDir(ctx, treeUri, docId, child, onFile)
                } else {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    child.parentFile?.mkdirs()
                    ctx.contentResolver.openInputStream(fileUri)?.use { input ->
                        child.outputStream().use { input.copyTo(it, 1 shl 16) }
                    }
                    onFile(name)
                }
            }
        }
    }

    private fun chmodAll(dir: File) {
        dir.walkTopDown().forEach { f ->
            if (f.isFile) {
                val executable = f.parentFile?.name == "bin" ||
                    f.name.endsWith(".so") ||
                    f.name == "jspawnhelper"
                try {
                    f.setReadable(true, false)
                    if (executable) f.setExecutable(true, false)
                } catch (t: Throwable) {
                    // 个别文件权限设置失败不影响整体
                }
                if (executable) {
                    try {
                        android.system.Os.chmod(f.absolutePath, 493)
                    } catch (t: Throwable) {
                    }
                }
            }
        }
    }
}
