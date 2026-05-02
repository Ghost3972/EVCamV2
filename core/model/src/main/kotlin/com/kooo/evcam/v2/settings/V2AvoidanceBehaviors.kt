package com.kooo.evcam.v2.settings

object V2AvoidanceBehaviors {
    const val EXIT_FOREGROUND = 1 shl 0
    const val STOP_RECORDING = 1 shl 1
    const val HIDE_BLIND_SPOT = 1 shl 3

    const val KNOWN_MASK = EXIT_FOREGROUND or STOP_RECORDING or HIDE_BLIND_SPOT

    fun labels(mask: Int): String = buildList {
        if (mask and EXIT_FOREGROUND != 0) add("退出前台")
        if (mask and STOP_RECORDING != 0) add("停止录制")
        if (mask and HIDE_BLIND_SPOT != 0) add("补盲避让")
    }.ifEmpty { listOf("不避让") }.joinToString("/")
}
