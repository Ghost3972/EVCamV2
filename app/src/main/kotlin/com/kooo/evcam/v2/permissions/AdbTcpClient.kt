package com.kooo.evcam.v2.permissions

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import com.kooo.evcam.v2.permissions.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import com.kooo.evcam.v2.permissions.AdbProtocol.ADB_AUTH_SIGNATURE
import com.kooo.evcam.v2.permissions.AdbProtocol.ADB_AUTH_TOKEN
import com.kooo.evcam.v2.permissions.AdbProtocol.A_AUTH
import com.kooo.evcam.v2.permissions.AdbProtocol.A_CLSE
import com.kooo.evcam.v2.permissions.AdbProtocol.A_CNXN
import com.kooo.evcam.v2.permissions.AdbProtocol.A_OKAY
import com.kooo.evcam.v2.permissions.AdbProtocol.A_OPEN
import com.kooo.evcam.v2.permissions.AdbProtocol.A_VERSION
import com.kooo.evcam.v2.permissions.AdbProtocol.A_WRTE
import com.kooo.evcam.v2.permissions.AdbProtocol.MAX_PAYLOAD

internal class AdbTcpClient(
    private val keyStore: AdbKeyStore,
    private val isCancelled: () -> Boolean,
) {
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
        AdbTcpConnectionHelper.resetLocalConnection(log)
    }

    fun connectWithCandidates(log: (String) -> Unit): Boolean {
        val hosts = AdbTcpConnectionHelper.discoverCandidateHosts()
        log("尝试连接 ADB (端口 $ADB_PORT)...")
        var lastException: IOException? = null
        for (host in hosts) {
            if (isCancelled()) break
            try {
                log("  → $host:$ADB_PORT ...")
                connect(host = host, connectTimeoutMs = ADB_CONNECT_TIMEOUT_MS, readTimeoutMs = ADB_READ_TIMEOUT_MS)
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

    fun connectLocal(readTimeoutMs: Int = ADB_READ_TIMEOUT_MS) {
        connect(host = ADB_HOST, connectTimeoutMs = ADB_CONNECT_TIMEOUT_MS, readTimeoutMs = readTimeoutMs)
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
                val previousTimeout = socket?.soTimeout ?: ADB_READ_TIMEOUT_MS
                setReadTimeout(ADB_AUTH_ACCEPT_TIMEOUT_MS)
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
        AdbTcpConnectionHelper.waitForAdbd(maxWaitMs)
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

    private fun sendMessage(command: Int, arg0: Int, arg1: Int, data: ByteArray?) {
        AdbProtocol.write(requireNotNull(socketOut), command, arg0, arg1, data)
    }

    private fun readMessage(): AdbMessage {
        return AdbProtocol.read(requireNotNull(socketIn))
    }

    private fun logDeviceInfo(data: ByteArray?, log: (String) -> Unit) {
        if (data == null || data.isEmpty()) return
        val info = String(data).replace("\u0000", "").trim()
        if (info.isNotEmpty()) log("设备: $info")
    }

    companion object {
        private const val TAG = "AdbTcpClient"
        const val INSTALL_TIMEOUT_MS = 120000
    }
}
