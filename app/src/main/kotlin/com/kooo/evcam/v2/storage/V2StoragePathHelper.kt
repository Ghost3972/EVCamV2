package com.kooo.evcam.v2.storage

import android.content.Context
import android.os.Environment
import com.kooo.evcam.v2.log.V2AppLog
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

object V2StoragePathHelper {
    enum class StorageLocation { INTERNAL, USB, PUBLIC_DCIM }

    private const val TAG = "V2StoragePathHelper"
    private const val OUTPUT_DIR_NAME = "EVCam_Video"
    private const val PHOTO_DIR_NAME = "EVCam_Photo"
    private const val CACHE_VALIDITY_MS = 5_000L
    private val usbRootPattern = Regex("/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")
    private val cacheLock = Any()
    @Volatile private var cachedUsbRoot: File? = null
    @Volatile private var cachedHasUsb: Boolean? = null
    @Volatile private var cacheTimestampMs: Long = 0L

    fun preferredLocation(context: Context): StorageLocation =
        V2StorageLocationSettings.selectedLocation(context)

    fun clearCache() {
        synchronized(cacheLock) {
            cachedUsbRoot = null
            cachedHasUsb = null
            cacheTimestampMs = 0L
        }
        V2AppLog.d(TAG, "USB storage detection cache cleared")
    }

    fun hasUsbStorage(context: Context): Boolean {
        synchronized(cacheLock) {
            cachedHasUsb?.let { if (isCacheValid()) return it }
        }
        val root = usbRoot(context)
        val result = root != null && isWritableUsbRoot(root)
        synchronized(cacheLock) {
            cachedHasUsb = result
            cacheTimestampMs = System.currentTimeMillis()
        }
        return result
    }

    fun isUsbFallback(context: Context): Boolean =
        preferredLocation(context) == StorageLocation.USB && !hasUsbStorage(context)

    fun availableUsbMount(context: Context): File? {
        val appDir = usbAppFilesDir(context, Environment.DIRECTORY_MOVIES) ?: usbRoot(context)?.let { root ->
            File(File(root, "Android/data/${context.packageName}/files"), Environment.DIRECTORY_MOVIES)
        }
        if (appDir != null && ensureWritableDirectory(appDir)) {
            return File(appDir, OUTPUT_DIR_NAME)
        }
        return null
    }

    fun availableUsbPhotoMount(context: Context): File? {
        val appDir = usbAppFilesDir(context, Environment.DIRECTORY_PICTURES) ?: usbRoot(context)?.let { root ->
            File(File(root, "Android/data/${context.packageName}/files"), Environment.DIRECTORY_PICTURES)
        }
        if (appDir != null && ensureWritableDirectory(appDir)) {
            return File(appDir, PHOTO_DIR_NAME)
        }
        return null
    }

    fun usbRoot(context: Context): File? {
        synchronized(cacheLock) {
            val cached = cachedUsbRoot
            if (isCacheValid() && cached != null && isReadableDirectory(cached)) return cached
            if (cached != null && !isReadableDirectory(cached)) {
                cachedUsbRoot = null
                cachedHasUsb = null
            }
        }

        val detected = detectUsbRoot(context)
        synchronized(cacheLock) {
            cachedUsbRoot = detected
            cachedHasUsb = detected?.let { root ->
                isWritableUsbRoot(root) || usbAppFilesDir(context, Environment.DIRECTORY_MOVIES)?.let(::ensureWritableDirectory) == true
            } ?: false
            cacheTimestampMs = System.currentTimeMillis()
        }
        return detected
    }

    fun storageDebugInfo(context: Context): List<String> = buildList {
        add("=== 内部存储 ===")
        add("路径: ${Environment.getExternalStorageDirectory().absolutePath}")
        add("")
        add("=== /proc/mounts ===")
        addAll(storageMountPoints().ifEmpty { listOf("未发现 /storage 挂载点") })
        add("")
        add("=== getExternalFilesDirs ===")
        runCatching { context.getExternalFilesDirs(null) }
            .onSuccess { dirs ->
                dirs?.forEachIndexed { index, dir ->
                    add("[$index] ${if (index == 0) "内部" else "外部"}: ${dir?.absolutePath ?: "null"}")
                } ?: add("返回 null")
            }
            .onFailure { add("错误: ${it.message}") }
        add("")
        add("=== 自定义路径 ===")
        val custom = V2StorageLocationSettings.customUsbPath(context)
        if (custom.isNullOrBlank()) {
            add("未设置")
        } else {
            val dir = File(custom)
            add("路径: $custom")
            add("存在: ${dir.exists()}, 可读: ${dir.canRead()}, 可写: ${dir.canWrite()}")
        }
        add("")
        add("=== 缓存路径 ===")
        add(V2StorageLocationSettings.lastDetectedUsbPath(context) ?: "未缓存")
        add("")
        add("=== 检测结果 ===")
        val root = usbRoot(context)
        if (root != null) {
            add("检测到U盘: ${root.absolutePath}")
            add("根目录可写: ${root.canWrite()}")
            add("App目录可用: ${availableUsbMount(context)?.absolutePath ?: "不可用"}")
        } else {
            add("未检测到U盘")
        }
    }

    fun outputDir(context: Context): File {
        val preferred = preferredLocation(context)
        val usb = availableUsbMount(context)
        return when (preferred) {
            StorageLocation.USB -> usb ?: internalDir(context)
            StorageLocation.INTERNAL -> internalDir(context)
            StorageLocation.PUBLIC_DCIM -> publicDcimDir()
        }.apply { mkdirs() }
    }

    fun playbackScanDirs(context: Context): List<File> {
        val dirs = mutableListOf(internalDir(context), publicDcimDir())
        availableUsbMount(context)?.let { dirs += it }
        return dirs
            .distinctBy { it.toPath().toAbsolutePath().normalize().toString() }
            .filter { it.isDirectory && it.canRead() }
    }

    fun photoDir(context: Context): File = File(
        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: File(context.filesDir, Environment.DIRECTORY_PICTURES),
        PHOTO_DIR_NAME
    ).apply { mkdirs() }

    fun photoScanDirs(context: Context): List<File> {
        val dirs = mutableListOf(photoDir(context), publicDcimPhotoDir())
        availableUsbPhotoMount(context)?.let { dirs += it }
        return dirs
            .distinctBy { it.toPath().toAbsolutePath().normalize().toString() }
            .filter { it.isDirectory && it.canRead() }
    }

    fun selectedLocationLabel(context: Context): String = when (preferredLocation(context)) {
        StorageLocation.INTERNAL -> "App内部存储"
        StorageLocation.USB -> if (availableUsbMount(context) != null) "App U盘目录" else "App U盘目录（未检测到，已回退App内部存储）"
        StorageLocation.PUBLIC_DCIM -> "公共DCIM目录"
    }

    fun storageSummary(context: Context): String {
        val dir = outputDir(context)
        return "当前路径：${dir.absolutePath}\n可用/总空间：${V2StorageCleaner.formatBytes(dir.usableSpace)} / ${V2StorageCleaner.formatBytes(dir.totalSpace)}"
    }

    fun storageOptions(context: Context): List<String> = listOf(
        "App内部",
        if (availableUsbMount(context) != null) "App U盘" else "App U盘(未检测到)",
        "公共DCIM"
    )

    fun saveLocation(context: Context, location: StorageLocation) {
        V2StorageLocationSettings.setSelectedLocation(context, location)
    }

    private fun internalDir(context: Context): File = File(
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, Environment.DIRECTORY_MOVIES),
        OUTPUT_DIR_NAME
    )

    private fun publicDcimDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        OUTPUT_DIR_NAME
    )

    private fun publicDcimPhotoDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        PHOTO_DIR_NAME
    )

    private fun detectUsbRoot(context: Context): File? {
        V2StorageLocationSettings.customUsbPath(context)?.takeIf { it.isNotBlank() }?.let { path ->
            File(path).takeIf(::isReadableDirectory)?.let { return it }
        }

        V2StorageLocationSettings.lastDetectedUsbPath(context)?.takeIf { it.isNotBlank() }?.let { path ->
            File(path).takeIf(::isReadableDirectory)?.let { return it }
        }

        sdCardFromMounts()?.let { root ->
            V2StorageLocationSettings.setLastDetectedUsbPath(context, root.absolutePath)
            return root
        }

        sdCardFromExternalFilesDirs(context)?.let { root ->
            V2StorageLocationSettings.setLastDetectedUsbPath(context, root.absolutePath)
            return root
        }

        V2StorageLocationSettings.setLastDetectedUsbPath(context, null)
        V2AppLog.d(TAG, "USB storage not detected")
        return null
    }

    private fun sdCardFromMounts(): File? = runCatching {
        BufferedReader(FileReader("/proc/mounts")).useLines { lines ->
            lines.mapNotNull { line ->
                val mountPoint = line.split(Regex("\\s+")).getOrNull(1) ?: return@mapNotNull null
                if (!usbRootPattern.matches(mountPoint)) return@mapNotNull null
                File(mountPoint).takeIf(::isReadableDirectory)
            }.firstOrNull()
        }
    }.onFailure { V2AppLog.d(TAG, "read /proc/mounts failed", it) }.getOrNull()

    private fun sdCardFromExternalFilesDirs(context: Context): File? = runCatching {
        context.getExternalFilesDirs(null)
            ?.drop(1)
            ?.asSequence()
            ?.filterNotNull()
            ?.mapNotNull { dir -> usbRootFromAppDir(dir.absolutePath) }
            ?.firstOrNull { isReadableDirectory(it) }
    }.onFailure { V2AppLog.d(TAG, "getExternalFilesDirs USB detection failed", it) }.getOrNull()

    private fun usbAppFilesDir(context: Context, type: String): File? = runCatching {
        context.getExternalFilesDirs(type)
            ?.drop(1)
            ?.asSequence()
            ?.filterNotNull()
            ?.firstOrNull { dir -> usbRootFromAppDir(dir.absolutePath)?.let(::isReadableDirectory) == true }
    }.getOrNull()

    private fun usbRootFromAppDir(path: String): File? {
        val index = path.indexOf("/Android/data/")
        if (index <= 0) return null
        val rootPath = path.substring(0, index)
        return if (usbRootPattern.matches(rootPath)) File(rootPath) else null
    }

    private fun storageMountPoints(): List<String> = runCatching {
        BufferedReader(FileReader("/proc/mounts")).useLines { lines ->
            lines.mapNotNull { line ->
                val mountPoint = line.split(Regex("\\s+")).getOrNull(1) ?: return@mapNotNull null
                if (!mountPoint.startsWith("/storage/")) return@mapNotNull null
                val marker = when {
                    mountPoint.contains("emulated") -> " [内部]"
                    usbRootPattern.matches(mountPoint) -> " [U盘]"
                    else -> ""
                }
                mountPoint + marker
            }.toList()
        }
    }.getOrDefault(emptyList())

    private fun isCacheValid(): Boolean =
        cacheTimestampMs > 0 && System.currentTimeMillis() - cacheTimestampMs < CACHE_VALIDITY_MS

    private fun isReadableDirectory(dir: File): Boolean = dir.exists() && dir.isDirectory && dir.canRead()

    private fun ensureWritableDirectory(dir: File): Boolean =
        (dir.exists() || dir.mkdirs()) && dir.isDirectory && dir.canWrite()

    private fun isWritableUsbRoot(root: File): Boolean {
        val dcim = File(root, Environment.DIRECTORY_DCIM)
        return ensureWritableDirectory(dcim) || root.canWrite()
    }
}
