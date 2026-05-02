package com.flyme.plugin.annotations

import kotlin.reflect.KClass

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
@Repeatable
annotation class Requires(
    val target: KClass<*>,
    val version: Int,
)
