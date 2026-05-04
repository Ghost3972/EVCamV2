package com.kooo.evcam.v2.service.vhal

import io.grpc.MethodDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal object V2ByteArrayMarshaller : MethodDescriptor.Marshaller<ByteArray> {
    override fun stream(value: ByteArray): InputStream = ByteArrayInputStream(value)

    override fun parse(stream: InputStream): ByteArray = try {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val bytesRead = stream.read(buffer)
            if (bytesRead == -1) break
            output.write(buffer, 0, bytesRead)
        }
        output.toByteArray()
    } catch (_: Exception) {
        ByteArray(0)
    }
}
