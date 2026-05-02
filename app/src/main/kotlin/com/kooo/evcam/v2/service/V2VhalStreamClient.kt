package com.kooo.evcam.v2.service

import android.util.Log
import com.kooo.evcam.VhalNative
import io.grpc.CallOptions
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.ClientCalls
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class V2VhalStreamClient(
    private val tag: String,
    private val clientId: String,
    private val connectedLog: String,
    private val streamTimeoutLog: String,
    private val sendAllSuccessLog: String,
    private val sendAllExhaustedLog: String,
) {
    data class Connection(val channel: ManagedChannel, val sessionId: String)

    interface Callback {
        fun onBatch(data: ByteArray)
        fun onStreamCompleted()
        fun onStreamError(t: Throwable)
    }

    fun connect(): Connection {
        val sessionId = UUID.randomUUID().toString()
        val headers = Metadata().apply {
            put(Metadata.Key.of("session_id", Metadata.ASCII_STRING_MARSHALLER), sessionId)
            put(Metadata.Key.of("client_id", Metadata.ASCII_STRING_MARSHALLER), clientId)
        }
        val channel = OkHttpChannelBuilder.forAddress(VhalNative.getGrpcHost(), VhalNative.getGrpcPort())
            .usePlaintext()
            .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))
            .build()
        Log.d(tag, connectedLog + sessionId)
        return Connection(channel, sessionId)
    }

    fun disconnect(channel: ManagedChannel?) {
        if (channel == null) return
        try {
            channel.shutdown()
            if (!channel.awaitTermination(2, TimeUnit.SECONDS)) channel.shutdownNow()
        } catch (_: Throwable) {
            try {
                channel.shutdownNow()
            } catch (_: Throwable) {
            }
        }
    }

    @Throws(InterruptedException::class)
    fun streamProperties(streamMethodName: String, sendAllMethodName: String, active: ManagedChannel?, callback: Callback) {
        if (active == null) return
        val latch = CountDownLatch(1)
        val streamMethod = MethodDescriptor.newBuilder<ByteArray, ByteArray>()
            .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
            .setFullMethodName(streamMethodName)
            .setRequestMarshaller(V2ByteArrayMarshaller)
            .setResponseMarshaller(V2ByteArrayMarshaller)
            .build()
        ClientCalls.asyncServerStreamingCall(active.newCall(streamMethod, CallOptions.DEFAULT), ByteArray(0), object : StreamObserver<ByteArray> {
            override fun onNext(value: ByteArray) = callback.onBatch(value)
            override fun onError(t: Throwable) {
                callback.onStreamError(t)
                latch.countDown()
            }
            override fun onCompleted() {
                callback.onStreamCompleted()
                latch.countDown()
            }
        })
        requestAllProperties(active, sendAllMethodName)
        if (!latch.await(STREAM_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(tag, streamTimeoutLog)
        }
    }

    private fun requestAllProperties(active: ManagedChannel, sendAllMethodName: String) {
        Thread({
            for (attempt in 1..MAX_RETRIES) {
                try {
                    Thread.sleep(if (attempt == 1) FIRST_SEND_ALL_DELAY_MS else FIRST_SEND_ALL_DELAY_MS * attempt)
                    val method = MethodDescriptor.newBuilder<ByteArray, ByteArray>()
                        .setType(MethodDescriptor.MethodType.UNARY)
                        .setFullMethodName(sendAllMethodName)
                        .setRequestMarshaller(V2ByteArrayMarshaller)
                        .setResponseMarshaller(V2ByteArrayMarshaller)
                        .build()
                    ClientCalls.blockingUnaryCall(
                        active.newCall(method, CallOptions.DEFAULT.withWaitForReady().withDeadlineAfter(SEND_ALL_DEADLINE_MS, TimeUnit.MILLISECONDS)),
                        ByteArray(0),
                    )
                    Log.d(tag, sendAllSuccessLog + attempt)
                    return@Thread
                } catch (error: Throwable) {
                    val status = Status.fromThrowable(error)
                    Log.w(tag, "SendAll attempt $attempt/$MAX_RETRIES failed status=${status.code} desc=${status.description} msg=${error.message}")
                }
            }
            Log.e(tag, sendAllExhaustedLog)
        }, tag + "SendAll").start()
    }

    companion object {
        private const val STREAM_TIMEOUT_MS = 120_000L
        private const val MAX_RETRIES = 3
        private const val FIRST_SEND_ALL_DELAY_MS = 500L
        private const val SEND_ALL_DEADLINE_MS = 3000L
    }
}
