package com.flyme.plugin.annotations

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class ProvidesInterface(
    val action: String = "",
    val version: Int,
)
