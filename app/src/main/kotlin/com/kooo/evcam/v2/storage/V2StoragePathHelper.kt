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

    fun availableUsbMount(context: Context): File? {
        return context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES)
            .filterNotNull()
            .firstOrNull { it.absolutePath.matches(Regex("/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}/Android/data/.+")) }
            ?.let { File(it, OUTPUT_DIR_NAME) }
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

    fun photoScanDirs(context: Context): List<File> = listOf(photoDir(context))
        .filter { it.isDirectory && it.canRead() }

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
}
