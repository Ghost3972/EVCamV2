package com.kooo.evcam.v2.permissions

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class AdbMessage(
    var command: Int = 0,
    var arg0: Int = 0,
    var arg1: Int = 0,
    var data: ByteArray? = null,
)

internal object AdbProtocol {
    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val ADB_AUTH_TOKEN = 1
    const val ADB_AUTH_SIGNATURE = 2
    const val ADB_AUTH_RSAPUBLICKEY = 3
    const val A_VERSION = 0x01000000
    const val MAX_PAYLOAD = 4096

    fun write(output: OutputStream, command: Int, arg0: Int, arg1: Int, data: ByteArray?) {
        val header = ByteBuffer.allocate(HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(data?.size ?: 0)
        header.putInt(data?.let { checksum(it) } ?: 0)
        header.putInt(command xor -0x1)
        output.write(header.array())
        if (data != null && data.isNotEmpty()) output.write(data)
        output.flush()
    }

    fun read(input: InputStream): AdbMessage {
        val headerBytes = readFully(input, HEADER_LENGTH)
        val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val message = AdbMessage()
        message.command = buffer.int
        message.arg0 = buffer.int
        message.arg1 = buffer.int
        val length = buffer.int
        buffer.int
        buffer.int
        if (length > 0) message.data = readFully(input, length)
        return message
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(data, offset, length - offset)
            if (read == -1) throw IOException("ADB 连接已断开")
            offset += read
        }
        return data
    }

    private fun checksum(data: ByteArray): Int {
        var sum = 0
        for (byte in data) sum += byte.toInt() and 0xFF
        return sum
    }

    private const val HEADER_LENGTH = 24
}
