package com.kooo.evcam.v2.permissions

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class AdbTcpClient(
    private val keyStore: AdbKeyStore,
    private val isCancelled: () -> Boolean,
) {
    private data class AdbMessage(
        var command: Int = 0,
        var arg0: Int = 0,
        var arg1: Int = 0,
        var data: ByteArray? = null,
    )

    private var socket: Socket? = null
    private var socketIn: InputStream? = null
    private var socketOut: OutputStream? = null
    private var serverMaxData = MAX_PAYLOAD
    private var localIdCounter = 1

    val isConnected: Boolean
        get() = socket?.isClosed == false

    fun resetSession() {
        localIdCounter = 1
        serverMaxData = MAX_PAYLOAD
        close()
    }

    fun resetConnection(log: (String) -> Unit) {
        close()
        log("重置 ADB 连接...")
        var probe: Socket? = null
        try {
            probe = Socket().apply { connect(InetSocketAddress(ADB_HOST, ADB_PORT), CONNECT_TIMEOUT_MS) }
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

    fun connectWithCandidates(log: (String) -> Unit): Boolean {
        val hosts = discoverCandidateHosts()
        log("尝试连接 ADB (端口 $ADB_PORT)...")
        var lastException: IOException? = null
        for (host in hosts) {
            if (isCancelled()) break
            try {
                log("  → $host:$ADB_PORT ...")
                connect(host = host, connectTimeoutMs = CONNECT_TIMEOUT_MS, readTimeoutMs = READ_TIMEOUT_MS)
                log("  ✓ 已连接 $host:$ADB_PORT")
                return true
            } catch (e: IOException) {
                val reason = e.message ?: e::class.java.simpleName
                log("  ✗ $host - $reason")
                lastException = e
            }
        }

        log("")
        log("✗ 无法连接到 ADB (所有地址均失败)")
        log("")
        log("请确认：")
        log("  1. 开发者选项中已开启 USB 调试")
        log("  2. 通过 PC 执行: adb tcpip 5555")
        log("  3. 设备已连接 WiFi（部分设备需要）")
        if (lastException != null) Log.e(TAG, "ADB connect failed (all hosts)", lastException)
        return false
    }

    fun connectLocal(readTimeoutMs: Int = READ_TIMEOUT_MS) {
        connect(host = ADB_HOST, connectTimeoutMs = CONNECT_TIMEOUT_MS, readTimeoutMs = readTimeoutMs)
    }

    fun performHandshake(log: (String) -> Unit): Boolean {
        sendMessage(A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray(Charsets.UTF_8))
        val message = readMessage()
        if (message.command == A_CNXN) {
            serverMaxData = message.arg1
            logDeviceInfo(message.data, log)
            return true
        }

        if (message.command == A_AUTH && message.arg0 == ADB_AUTH_TOKEN) {
            log("ADB 需要认证...")
            sendMessage(A_AUTH, ADB_AUTH_SIGNATURE, 0, keyStore.signToken(requireNotNull(message.data)))
            var next = readMessage()
            if (next.command == A_CNXN) {
                serverMaxData = next.arg1
                logDeviceInfo(next.data, log)
                log("✓ 认证成功（已知密钥）")
                return true
            }

            if (next.command == A_AUTH) {
                log("发送公钥，请在设备上确认 USB 调试授权...")
                sendMessage(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, keyStore.adbPublicKeyBytes())
                val previousTimeout = socket?.soTimeout ?: READ_TIMEOUT_MS
                setReadTimeout(AUTH_ACCEPT_TIMEOUT_MS)
                try {
                    next = readMessage()
                } finally {
                    setReadTimeout(previousTimeout)
                }
                if (next.command == A_CNXN) {
                    serverMaxData = next.arg1
                    logDeviceInfo(next.data, log)
                    log("✓ 认证成功（用户已授权）")
                    return true
                }
            }

            log("✗ 认证失败")
            log("  请在设备上允许 USB 调试，或检查 ADB 安全设置")
            return false
        }

        log("✗ 未知响应: 0x${message.command.toString(16)}")
        return false
    }

    fun executeShellCommand(command: String): String = executeStream("shell:$command")

    fun executeService(serviceName: String): String = executeStream(serviceName).trim()

    fun executeShellCommandStreaming(command: String, log: (String) -> Unit): Boolean {
        val lineBuffer = StringBuilder()
        var hasError = false
        executeStream("shell:$command") { chunk ->
            lineBuffer.append(chunk)
            var index = lineBuffer.indexOf("\n")
            while (index >= 0) {
                val line = lineBuffer.substring(0, index)
                lineBuffer.delete(0, index + 1)
                log(line)
                if (line.contains("[ERROR]")) hasError = true
                index = lineBuffer.indexOf("\n")
            }
        }
        if (lineBuffer.isNotEmpty()) {
            val remaining = lineBuffer.toString()
            log(remaining)
            if (remaining.contains("[ERROR]")) hasError = true
        }
        return !hasError
    }

    fun setReadTimeout(timeoutMs: Int) {
        socket?.soTimeout = timeoutMs
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        socketIn = null
        socketOut = null
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

    private fun executeStream(serviceName: String): String {
        val output = StringBuilder()
        executeStream(serviceName) { output.append(it) }
        return output.toString()
    }

    private fun executeStream(serviceName: String, onChunk: (String) -> Unit) {
        val localId = localIdCounter++
        sendMessage(A_OPEN, localId, 0, (serviceName + "\u0000").toByteArray(Charsets.UTF_8))
        var streamOpen = true
        while (streamOpen) {
            val message = readMessage()
            when (message.command) {
                A_OKAY -> Unit
                A_WRTE -> {
                    sendMessage(A_OKAY, localId, message.arg0, null)
                    message.data?.let { onChunk(String(it, Charsets.UTF_8)) }
                }
                A_CLSE -> {
                    sendMessage(A_CLSE, localId, message.arg0, null)
                    streamOpen = false
                }
                else -> streamOpen = false
            }
        }
    }

    private fun connect(host: String, connectTimeoutMs: Int, readTimeoutMs: Int) {
        close()
        socket = Socket().apply {
            connect(InetSocketAddress(host, ADB_PORT), connectTimeoutMs)
            soTimeout = readTimeoutMs
        }
        socketIn = socket!!.getInputStream()
        socketOut = socket!!.getOutputStream()
    }

    private fun discoverCandidateHosts(): List<String> {
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
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enumerate network interfaces", e)
        }
        return hosts
    }

    private fun sendMessage(command: Int, arg0: Int, arg1: Int, data: ByteArray?) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(data?.size ?: 0)
        header.putInt(data?.let { dataChecksum(it) } ?: 0)
        header.putInt(command xor -0x1)
        requireNotNull(socketOut).write(header.array())
        if (data != null && data.isNotEmpty()) requireNotNull(socketOut).write(data)
        requireNotNull(socketOut).flush()
    }

    private fun readMessage(): AdbMessage {
        val headerBytes = readFully(24)
        val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val message = AdbMessage()
        message.command = buffer.int
        message.arg0 = buffer.int
        message.arg1 = buffer.int
        val length = buffer.int
        buffer.int
        buffer.int
        if (length > 0) message.data = readFully(length)
        return message
    }

    private fun readFully(length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        val input = requireNotNull(socketIn)
        while (offset < length) {
            val read = input.read(data, offset, length - offset)
            if (read == -1) throw IOException("ADB 连接已断开")
            offset += read
        }
        return data
    }

    private fun logDeviceInfo(data: ByteArray?, log: (String) -> Unit) {
        if (data == null || data.isEmpty()) return
        val info = String(data).replace("\u0000", "").trim()
        if (info.isNotEmpty()) log("设备: $info")
    }

    private fun dataChecksum(data: ByteArray): Int {
        var sum = 0
        for (byte in data) sum += byte.toInt() and 0xFF
        return sum
    }

    companion object {
        private const val TAG = "AdbTcpClient"
        private const val A_CNXN = 0x4e584e43
        private const val A_AUTH = 0x48545541
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257
        private const val ADB_AUTH_TOKEN = 1
        private const val ADB_AUTH_SIGNATURE = 2
        private const val ADB_AUTH_RSAPUBLICKEY = 3
        private const val A_VERSION = 0x01000000
        private const val MAX_PAYLOAD = 4096
        private const val ADB_HOST = "127.0.0.1"
        private const val ADB_PORT = 5555
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 10000
        private const val AUTH_ACCEPT_TIMEOUT_MS = 30000
        const val INSTALL_TIMEOUT_MS = 120000
    }
}
