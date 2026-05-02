package com.kooo.evcam.v2.permissions

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

internal class AdbKeyStore(context: Context) {
    private val filesDir = context.applicationContext.filesDir
    private var keyPair: KeyPair? = null

    fun loadOrGenerate() {
        val privateFile = File(filesDir, PRIVATE_KEY_FILE)
        val publicFile = File(filesDir, PUBLIC_KEY_FILE)
        keyPair = if (privateFile.exists() && publicFile.exists()) {
            runCatching { loadKeyPair(privateFile, publicFile) }
                .getOrElse { regenerateKeyPair(privateFile, publicFile) }
        } else {
            regenerateKeyPair(privateFile, publicFile)
        }
    }

    fun signToken(token: ByteArray): ByteArray {
        val pair = requireNotNull(keyPair) { "ADB key pair is not loaded" }
        val digestInfo = ByteArray(SHA1_DIGEST_INFO_PREFIX.size + token.size)
        System.arraycopy(SHA1_DIGEST_INFO_PREFIX, 0, digestInfo, 0, SHA1_DIGEST_INFO_PREFIX.size)
        System.arraycopy(token, 0, digestInfo, SHA1_DIGEST_INFO_PREFIX.size, token.size)
        return Signature.getInstance("NONEwithRSA").apply {
            initSign(pair.private)
            update(digestInfo)
        }.sign()
    }

    fun adbPublicKeyBytes(): ByteArray {
        val pair = requireNotNull(keyPair) { "ADB key pair is not loaded" }
        val publicKey = pair.public as RSAPublicKey
        val encoded = encodeAndroidRsaPublicKey(publicKey)
        return (Base64.encodeToString(encoded, Base64.NO_WRAP) + " adb@evcam\u0000")
            .toByteArray(Charsets.UTF_8)
    }

    private fun loadKeyPair(privateFile: File, publicFile: File): KeyPair {
        val keyFactory = KeyFactory.getInstance("RSA")
        return KeyPair(
            keyFactory.generatePublic(X509EncodedKeySpec(readFileBytes(publicFile))),
            keyFactory.generatePrivate(PKCS8EncodedKeySpec(readFileBytes(privateFile))),
        )
    }

    private fun regenerateKeyPair(privateFile: File, publicFile: File): KeyPair {
        if (privateFile.exists()) privateFile.delete()
        if (publicFile.exists()) publicFile.delete()
        return KeyPairGenerator.getInstance("RSA").apply {
            initialize(RSA_KEY_BITS)
        }.generateKeyPair().also {
            writeFileBytes(privateFile, it.private.encoded)
            writeFileBytes(publicFile, it.public.encoded)
        }
    }

    private fun encodeAndroidRsaPublicKey(publicKey: RSAPublicKey): ByteArray {
        val modulus = publicKey.modulus
        val exponent = publicKey.publicExponent
        val modulusBytes = RSA_KEY_BITS / 8
        val modulusWords = modulusBytes / 4
        val two32 = BigInteger.ONE.shiftLeft(32)
        val n0 = modulus.mod(two32)
        val n0inv = n0.modInverse(two32).negate().mod(two32)
        val rr = BigInteger.ONE.shiftLeft(RSA_KEY_BITS * 2).mod(modulus)

        return ByteBuffer.allocate(4 + 4 + modulusBytes + modulusBytes + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(modulusWords)
            .putInt(n0inv.toInt())
            .put(bigIntToLittleEndian(modulus, modulusBytes))
            .put(bigIntToLittleEndian(rr, modulusBytes))
            .putInt(exponent.toInt())
            .array()
    }

    private fun bigIntToLittleEndian(value: BigInteger, length: Int): ByteArray {
        val bigEndian = value.toByteArray()
        val result = ByteArray(length)
        var sourceLength = bigEndian.size
        if (sourceLength > length && bigEndian[0] == 0.toByte()) sourceLength--
        val copyLength = minOf(sourceLength, length)
        for (i in 0 until copyLength) {
            result[i] = bigEndian[bigEndian.size - 1 - i]
        }
        return result
    }

    private fun readFileBytes(file: File): ByteArray {
        val data = ByteArray(file.length().toInt())
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < data.size) {
                val read = input.read(data, offset, data.size - offset)
                if (read == -1) break
                offset += read
            }
        }
        return data
    }

    private fun writeFileBytes(file: File, data: ByteArray) {
        FileOutputStream(file).use { it.write(data) }
    }

    private companion object {
        const val RSA_KEY_BITS = 2048
        const val PRIVATE_KEY_FILE = "adb_private_key"
        const val PUBLIC_KEY_FILE = "adb_public_key"
        val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
    }
}
