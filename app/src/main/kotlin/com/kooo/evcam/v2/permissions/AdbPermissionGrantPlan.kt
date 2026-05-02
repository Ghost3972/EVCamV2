package com.kooo.evcam.v2.permissions

internal data class AdbPermissionCommand(
    val description: String,
    val shellCommand: String,
)

internal object AdbPermissionGrantPlan {
    fun build(packageName: String, sdk: Int): List<AdbPermissionCommand> = buildList {
        add(AdbPermissionCommand("相机权限", "pm grant $packageName android.permission.CAMERA"))
        if (sdk >= 33) {
            add(AdbPermissionCommand("媒体视频权限", "pm grant $packageName android.permission.READ_MEDIA_VIDEO"))
            add(AdbPermissionCommand("媒体图片权限", "pm grant $packageName android.permission.READ_MEDIA_IMAGES"))
        }
        if (sdk <= 32) {
            add(AdbPermissionCommand("读取存储权限", "pm grant $packageName android.permission.READ_EXTERNAL_STORAGE"))
            add(AdbPermissionCommand("写入存储权限", "pm grant $packageName android.permission.WRITE_EXTERNAL_STORAGE"))
        }
        add(AdbPermissionCommand("日志读取权限", "pm grant $packageName android.permission.READ_LOGS"))
        if (sdk >= 33) add(AdbPermissionCommand("通知权限", "pm grant $packageName android.permission.POST_NOTIFICATIONS"))
        if (sdk >= 31) add(AdbPermissionCommand("蓝牙连接权限", "pm grant $packageName android.permission.BLUETOOTH_CONNECT"))
        add(AdbPermissionCommand("悬浮窗权限", "appops set $packageName SYSTEM_ALERT_WINDOW allow"))
        if (sdk >= 30) add(AdbPermissionCommand("所有文件访问权限", "appops set $packageName MANAGE_EXTERNAL_STORAGE allow"))
        add(AdbPermissionCommand("使用情况访问权限", "appops set $packageName android:get_usage_stats allow"))
    }
}
