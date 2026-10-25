package com.mclan.server

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Append-only console mirror + in-memory ring buffer for the UI.
 */
class Log private constructor(private val key: String) {

    private val ring = ArrayDeque<String>()
    private val lock = Any()

    fun append(line: String) {
        synchronized(lock) {
            ring.addLast(line)
            while (ring.size > MAX) ring.removeFirst()
        }
        androidLogI("MCLanLog", line)
        try {
            FileOutputStream(file(), true).use { out ->
                out.write((line + "\n").toByteArray(Charsets.UTF_8))
            }
        } catch (t: Throwable) {
            // disk full etc; keep going
        }
    }

    fun lines(): List<String> = synchronized(lock) { ring.toList() }

    fun file(): File = File(key, ".console.log")

    fun clear() {
        synchronized(lock) { ring.clear() }
        file().delete()
    }

    fun filter(query: String): List<String> = if (query.isBlank()) lines() else lines().filter { it.contains(query, true) }

    fun timestamp(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    companion object {
        private const val MAX = 4000
        private val instances = ConcurrentHashMap<String, Log>()

        fun get(key: String): Log = instances.getOrPut(key) { com.mclan.server.Log(key) }
    }
}
