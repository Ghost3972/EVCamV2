package com.kooo.evcam.v2.update

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import com.kooo.evcam.v2.log.V2AppLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class V2VersionUpdateManager(context: Context) {
    interface UpdateCheckCallback {
        fun onUpdateAvailable(info: UpdateInfo)
        fun onNoUpdate(remoteVersion: String)
        fun onError(error: String)
    }

    interface DownloadCallback {
        fun onProgress(progress: Int)
        fun onComplete(apkFile: File)
        fun onError(error: String)
    }

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Long,
        val apkUrl: String,
        val notes: String
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var cancelled = false

    fun currentVersionName(): String = runCatching {
        packageInfo().versionName ?: "unknown"
    }.getOrDefault("unknown")

    fun currentVersionCode(): Long = runCatching {
        packageInfo().longVersionCode
    }.getOrDefault(0L)

    fun checkUpdate(callback: UpdateCheckCallback) {
        cancelled = false
        executor.execute {
            runCatching {
                val info = fetchUpdateInfo()
                V2AppLog.i(TAG, "update check current=${currentVersionName()}(${currentVersionCode()}) remote=${info.versionName}(${info.versionCode})")
                if (info.versionCode > currentVersionCode() || isNewerVersion(info.versionName, currentVersionName())) {
                    post { callback.onUpdateAvailable(info) }
                } else {
                    post { callback.onNoUpdate(info.versionName) }
                }
            }.getOrElse { error ->
                V2AppLog.w(TAG, "update check failed: ${error.message}", error)
                post { callback.onError(error.message ?: "未知错误") }
            }
        }
    }

    fun downloadApk(info: UpdateInfo, callback: DownloadCallback) {
        cancelled = false
        executor.execute {
            runCatching {
                val connection = URL(info.apkUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.connect()
                if (connection.responseCode !in 200..299) error("服务器错误: ${connection.responseCode}")

                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
                val file = File(dir, "EVCam_${info.versionName}.apk")
                if (file.exists()) file.delete()

                val total = connection.contentLengthLong
                var downloaded = 0L
                var lastProgress = -1
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            if (cancelled) error("下载已取消")
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0L) {
                                val progress = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    post { callback.onProgress(progress) }
                                }
                            }
                        }
                    }
                }
                connection.disconnect()
                V2AppLog.i(TAG, "update apk downloaded: ${file.absolutePath}")
                post { callback.onComplete(file) }
            }.getOrElse { error ->
                V2AppLog.w(TAG, "update apk download failed: ${error.message}", error)
                post { callback.onError(error.message ?: "下载失败") }
            }
        }
    }

    fun cancelDownload() {
        cancelled = true
    }

    private fun fetchUpdateInfo(): UpdateInfo {
        val text = fetchText(UPDATE_URL)
        val map = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
            }
            .toMap()
        val versionName = map["versionName"] ?: error("缺少 versionName")
        val versionCode = map["versionCode"]?.toLongOrNull() ?: 0L
        val apk = map["apkUrl"] ?: "EVCam-v$versionName-release.apk"
        val apkUrl = if (apk.startsWith("http://") || apk.startsWith("https://")) apk else UPDATE_BASE_URL + apk
        return UpdateInfo(
            versionName = versionName,
            versionCode = versionCode,
            apkUrl = apkUrl,
            notes = map["notes"].orEmpty().replace("\\n", "\n")
        )
    }

    private fun fetchText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = CONNECT_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.connect()
        return try {
            if (connection.responseCode !in 200..299) error("服务器错误: ${connection.responseCode}")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
        } finally {
            connection.disconnect()
        }
    }

    private fun packageInfo() = appContext.packageManager.getPackageInfo(appContext.packageName, 0)

    private fun isNewerVersion(remote: String, current: String): Boolean {
        val remoteMain = remote.substringBefore("-test-").substringBefore('-')
        val currentMain = current.substringBefore("-test-").substringBefore('-')
        val remoteParts = remoteMain.split('.')
        val currentParts = currentMain.split('.')
        repeat(maxOf(remoteParts.size, currentParts.size)) { index ->
            val r = remoteParts.getOrNull(index)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            val c = currentParts.getOrNull(index)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            if (r != c) return r > c
        }
        val remoteTest = remote.substringAfter("-test-", "")
        val currentTest = current.substringAfter("-test-", "")
        return remoteTest.isNotEmpty() && currentTest.isNotEmpty() && remoteTest > currentTest
    }

    private fun post(action: () -> Unit) = mainHandler.post(action)

    private companion object {
        const val TAG = "V2VersionUpdate"
        const val UPDATE_BASE_URL = "https://evcam.a-z.xin/update/"
        const val UPDATE_URL = UPDATE_BASE_URL + "version.txt"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
    }
}
