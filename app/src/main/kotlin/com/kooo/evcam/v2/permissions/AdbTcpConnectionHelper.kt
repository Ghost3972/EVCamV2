package com.kooo.evcam.v2.permissions

import android.util.Log
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

internal object AdbTcpConnectionHelper {
    fun resetLocalConnection(log: (String) -> Unit) {
        log("重置 ADB 连接...")
        var probe: Socket? = null
        try {
            probe = Socket().apply { connect(InetSocketAddress(ADB_HOST, ADB_PORT), ADB_CONNECT_TIMEOUT_MS) }
            probe.close()
            probe = null
            Thread.sleep(800)
            log("✓ ADB 连接已重置")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            Log.d(TAG, "ADB reset: port not occupied or not available")
        } finally {
            try {
                probe?.close()
            } catch (_: Exception) {
            }
        }
    }

    fun discoverCandidateHosts(): List<String> {
        val hosts = mutableListOf(ADB_HOST)
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress
                        if (ip != null && ip !in hosts) hosts.add(ip)
                    }
                }
            }
        } catch (error: IOException) {
            Log.w(TAG, "Failed to enumerate network interfaces", error)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Failed to enumerate network interfaces", error)
        }
        return hosts
    }

    fun waitForAdbd(maxWaitMs: Long) {
        val deadline = System.currentTimeMillis() + maxWaitMs
        var delay = 500L
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(delay)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            var probe: Socket? = null
            try {
                probe = Socket().apply { connect(InetSocketAddress(ADB_HOST, ADB_PORT), 1500) }
                probe.close()
                return
            } catch (_: Exception) {
                delay = minOf(delay * 2, 2000L)
            } finally {
                try {
                    probe?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private const val TAG = "AdbTcpClient"
}
