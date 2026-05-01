package com.kooo.evcam.v2.storage

import android.content.Context
import android.os.Environment
import java.io.File

object V2StoragePathHelper {
    enum class StorageLocation { INTERNAL, USB }

    private const val OUTPUT_DIR_NAME = "EVCam_Video"

    fun preferredLocation(context: Context): StorageLocation =
        V2StorageLocationSettings.selectedLocation(context)

    fun availableUsbMount(context: Context): File? {
        val candidates = mutableSetOf<String>()
        runCatching {
            File("/proc/mounts").forEachLine { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2 && parts[1].matches(Regex("/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}"))) {
                    candidates += parts[1]
                }
            }
        }
        candidates += context.getExternalFilesDirs(null)
            .mapNotNull { it?.absolutePath }
            .mapNotNull { path -> File(path).absoluteFile.parentFile?.parentFile?.parentFile?.parentFile?.absolutePath }
            .filter { it.matches(Regex("/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) }
        return candidates.sorted().firstOrNull()?.let { File(it, "DCIM/$OUTPUT_DIR_NAME") }
    }

    fun outputDir(context: Context): File {
        val preferred = preferredLocation(context)
        val usb = availableUsbMount(context)
        return when (preferred) {
            StorageLocation.USB -> usb ?: internalDir()
            StorageLocation.INTERNAL -> internalDir()
        }.apply { mkdirs() }
    }

    fun playbackScanDirs(context: Context): List<File> {
        val dirs = mutableListOf(internalDir())
        availableUsbMount(context)?.let { dirs += it }
        return dirs
            .distinctBy { it.toPath().toAbsolutePath().normalize().toString() }
            .filter { it.isDirectory && it.canRead() }
    }

    fun selectedLocationLabel(context: Context): String = when (preferredLocation(context)) {
        StorageLocation.INTERNAL -> "内部存储"
        StorageLocation.USB -> if (availableUsbMount(context) != null) "U盘" else "U盘（未检测到，已回退内部存储）"
    }

    fun storageSummary(context: Context): String {
        val dir = outputDir(context)
        return "当前路径：${dir.absolutePath}\n可用/总空间：${V2StorageCleaner.formatBytes(dir.usableSpace)} / ${V2StorageCleaner.formatBytes(dir.totalSpace)}"
    }

    fun storageOptions(context: Context): List<String> = listOf(
        "内部存储",
        if (availableUsbMount(context) != null) "U盘" else "U盘（未检测到）"
    )

    fun saveLocation(context: Context, location: StorageLocation) {
        V2StorageLocationSettings.setSelectedLocation(context, location)
    }

    private fun internalDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        OUTPUT_DIR_NAME
    )
}
