package com.mclan.server

import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

object ProcessUtils {
    fun isAlive(pid: Int): Boolean {
        if (pid <= 0) return false
        return try {
            File("/proc/$pid").exists()
        } catch (t: Throwable) {
            false
        }
    }

    fun abi(): String {
        val abis = android.os.Build.SUPPORTED_ABIS
        return abis.joinToString("/")
    }

    /** First non-loopback IPv4 address, i.e. what friends on the LAN should connect to. */
    fun lanAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (t: Throwable) {
            null
        }
    }
}
