package com.kooo.evcam.v2.storage

import android.content.Context
import android.os.Environment
import java.io.File

object V2StoragePathHelper {
    enum class StorageLocation { INTERNAL, USB, PUBLIC_DCIM }

    private const val OUTPUT_DIR_NAME = "EVCam_Video"
    private const val PHOTO_DIR_NAME = "EVCam_Photo"

    fun preferredLocation(context: Context): StorageLocation =
        V2StorageLocationSettings.selectedLocation(context)

    fun clearCache() = V2UsbStorageDetector.clearCache()

    fun hasUsbStorage(context: Context): Boolean = V2UsbStorageDetector.hasUsbStorage(context)

    fun isUsbFallback(context: Context): Boolean =
        preferredLocation(context) == StorageLocation.USB && !hasUsbStorage(context)

    fun availableUsbMount(context: Context): File? =
        V2UsbStorageDetector.availableUsbVideoDir(context, OUTPUT_DIR_NAME)

    fun availableUsbPhotoMount(context: Context): File? =
        V2UsbStorageDetector.availableUsbPhotoDir(context, PHOTO_DIR_NAME)

    fun storageDebugInfo(context: Context): List<String> =
        V2UsbStorageDetector.debugInfo(context, OUTPUT_DIR_NAME)

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
        return storageSummary(outputDir(context))
    }

    fun storageOptions(context: Context): List<String> = storageOptions(usbAvailable = availableUsbMount(context) != null)

    fun saveLocation(context: Context, location: StorageLocation) {
        V2StorageLocationSettings.setSelectedLocation(context, location)
    }

    private fun internalDir(context: Context): File = File(
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, Environment.DIRECTORY_MOVIES),
        OUTPUT_DIR_NAME
    )

    private fun storageSummary(dir: File): String = "当前路径：${dir.absolutePath}\n可用/总空间：${formatStorageBytes(dir.usableSpace)} / ${formatStorageBytes(dir.totalSpace)}"

    private fun storageOptions(usbAvailable: Boolean): List<String> = listOf(
        "App内部",
        if (usbAvailable) "App U盘" else "App U盘(未检测到)",
        "公共DCIM"
    )

    private fun formatStorageBytes(bytes: Long): String = V2StorageCleaner.formatBytes(bytes)

    private fun publicDcimDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        OUTPUT_DIR_NAME
    )

    private fun publicDcimPhotoDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        PHOTO_DIR_NAME
    )

}
