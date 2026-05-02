package com.kooo.evcam.v2.service

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.kooo.evcam.v2.log.V2AppLog

class V2ForegroundAppMonitor(private val context: Context) {
    private var lastUsageEventsLogMs = 0L
    private var lastLookupMs = 0L
    private var lastTargetsKey = ""
    private var lastResult: String? = null

    fun findForegroundTarget(targets: List<String>): String? {
        if (targets.isEmpty()) return null
        val now = System.currentTimeMillis()
        val targetsKey = targets.joinToString("|")
        if (targetsKey == lastTargetsKey && now - lastLookupMs < LOOKUP_CACHE_MS) return lastResult
        val result = findByAccessibility(targets) ?: findByRunningTasks(targets) ?: findByUsageEvents(targets, now)
        lastTargetsKey = targetsKey
        lastLookupMs = now
        lastResult = result
        return result
    }

    private fun findByAccessibility(targets: List<String>): String? {
        val window = V2KeepAliveAccessibilityService.currentWindow() ?: return null
        return matchTarget(targets, window.packageName, window.className)?.also {
            V2AppLog.i("V2ForegroundAppMonitor", "accessibility foreground target=$it package=${window.packageName} class=${window.className}")
        }
    }

    private fun findByRunningTasks(targets: List<String>): String? = runCatching {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        activityManager.getRunningTasks(10).orEmpty().firstNotNullOfOrNull { task ->
            val top = task.topActivity ?: return@firstNotNullOfOrNull null
            matchTarget(targets, top.packageName, top.className)?.also {
                V2AppLog.i("V2ForegroundAppMonitor", "running task foreground target=$it package=${top.packageName} class=${top.className}")
            }
        }
    }.onFailure { V2AppLog.e("V2ForegroundAppMonitor", "running task foreground check failed", it) }.getOrNull()

    private fun findByUsageEvents(targets: List<String>, now: Long): String? = runCatching {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val lastStates = LinkedHashMap<String, Boolean>()
        val events = usageStatsManager.queryEvents(now - USAGE_EVENTS_WINDOW_MS, now) ?: return@runCatching null
        val event = UsageEvents.Event()
        val shouldCollectSamples = now - lastUsageEventsLogMs >= USAGE_EVENTS_LOG_INTERVAL_MS
        val samples = if (shouldCollectSamples) mutableListOf<String>() else null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (samples != null && shouldSampleUsageEvent(targets, event.packageName, event.className, event.eventType) && samples.size < MAX_USAGE_EVENT_SAMPLES) {
                samples += "type=${eventName(event.eventType)} package=${event.packageName} class=${event.className}"
            }
            val matched = matchTarget(targets, event.packageName, event.className) ?: continue
            @Suppress("DEPRECATION")
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND, UsageEvents.Event.ACTIVITY_RESUMED -> lastStates[matched] = true
                UsageEvents.Event.MOVE_TO_BACKGROUND, UsageEvents.Event.ACTIVITY_PAUSED -> lastStates[matched] = false
            }
        }
        if (samples != null) logUsageSamples(now, samples, lastStates)
        targets.firstOrNull { lastStates[it] == true }?.also {
            V2AppLog.i("V2ForegroundAppMonitor", "usage events foreground target=$it states=$lastStates")
        }
    }.onFailure { V2AppLog.e("V2ForegroundAppMonitor", "usage events foreground check failed", it) }.getOrNull()

    private fun matchTarget(targets: List<String>, packageName: String?, className: String?): String? {
        val pkg = packageName.orEmpty()
        val cls = className.orEmpty()
        val flattened = if (pkg.isNotBlank() && cls.isNotBlank()) "$pkg/$cls" else ""
        val shortFlattened = if (pkg.isNotBlank() && cls.startsWith(pkg)) "$pkg/.${cls.removePrefix(pkg).removePrefix(".")}" else ""
        return targets.firstOrNull { target ->
            target == pkg ||
                target == cls ||
                target == flattened ||
                target == shortFlattened ||
                (cls.isBlank() && targetPackageCandidates(target).contains(pkg))
        }
    }

    private fun shouldSampleUsageEvent(targets: List<String>, packageName: String?, className: String?, eventType: Int): Boolean {
        if (!isForegroundStateEvent(eventType)) return false
        val pkg = packageName.orEmpty()
        val cls = className.orEmpty()
        if (matchTarget(targets, pkg, cls) != null) return true
        return targets.any { target ->
            val candidates = targetPackageCandidates(target)
            candidates.any { it.isNotBlank() && (pkg == it || cls.startsWith(it)) }
        }
    }

    private fun targetPackageCandidates(target: String): Set<String> = buildSet {
        val componentPackage = target.substringBefore('/', missingDelimiterValue = "")
        if (componentPackage.isNotBlank()) add(componentPackage)
        if ('/' !in target && target.none { it.isUpperCase() }) add(target)
        if ('/' !in target && target.any { it.isUpperCase() } && '.' in target) add(target.substringBeforeLast('.'))
    }

    @Suppress("DEPRECATION")
    private fun isForegroundStateEvent(eventType: Int): Boolean = eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
        eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ||
        eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
        eventType == UsageEvents.Event.ACTIVITY_PAUSED

    @Suppress("DEPRECATION")
    private fun eventName(eventType: Int): String = when (eventType) {
        UsageEvents.Event.MOVE_TO_FOREGROUND -> "MOVE_TO_FOREGROUND"
        UsageEvents.Event.MOVE_TO_BACKGROUND -> "MOVE_TO_BACKGROUND"
        UsageEvents.Event.ACTIVITY_RESUMED -> "ACTIVITY_RESUMED"
        UsageEvents.Event.ACTIVITY_PAUSED -> "ACTIVITY_PAUSED"
        else -> eventType.toString()
    }

    private fun logUsageSamples(now: Long, samples: List<String>, lastStates: Map<String, Boolean>) {
        if (now - lastUsageEventsLogMs < USAGE_EVENTS_LOG_INTERVAL_MS) return
        lastUsageEventsLogMs = now
        V2AppLog.i(
            "V2ForegroundAppMonitor",
            "usage events samples=${samples.ifEmpty { listOf("none") }.joinToString(" | ")} states=$lastStates"
        )
    }

    private companion object {
        private const val LOOKUP_CACHE_MS = 2_000L
        private const val USAGE_EVENTS_WINDOW_MS = 60_000L
        private const val MAX_USAGE_EVENT_SAMPLES = 12
        private const val USAGE_EVENTS_LOG_INTERVAL_MS = 30_000L
    }
}
