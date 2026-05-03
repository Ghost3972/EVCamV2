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
    private var lastMatcher: TargetMatcher? = null
    private var lastResult: String? = null

    fun findForegroundTarget(targets: List<String>): String? {
        if (targets.isEmpty()) return null
        val now = System.currentTimeMillis()
        val targetsKey = targets.joinToString("|")
        if (targetsKey == lastTargetsKey && now - lastLookupMs < LOOKUP_CACHE_MS) return lastResult
        val matcher = matcherFor(targetsKey, targets)
        val result = findByAccessibility(matcher) ?: findByRunningTasks(matcher) ?: findByUsageEvents(matcher, now)
        lastTargetsKey = targetsKey
        lastLookupMs = now
        lastResult = result
        return result
    }

    private fun matcherFor(targetsKey: String, targets: List<String>): TargetMatcher {
        val cached = lastMatcher
        if (cached != null && cached.key == targetsKey) return cached
        return TargetMatcher(targetsKey, targets).also { lastMatcher = it }
    }

    private fun findByAccessibility(matcher: TargetMatcher): String? {
        val window = V2KeepAliveAccessibilityService.currentWindow() ?: return null
        return matcher.match(window.packageName, window.className)?.also {
            V2AppLog.i("V2ForegroundAppMonitor", "accessibility foreground target=$it package=${window.packageName} class=${window.className}")
        }
    }

    private fun findByRunningTasks(matcher: TargetMatcher): String? = runCatching {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        activityManager.getRunningTasks(10).orEmpty().firstNotNullOfOrNull { task ->
            val top = task.topActivity ?: return@firstNotNullOfOrNull null
            matcher.match(top.packageName, top.className)?.also {
                V2AppLog.i("V2ForegroundAppMonitor", "running task foreground target=$it package=${top.packageName} class=${top.className}")
            }
        }
    }.onFailure { V2AppLog.e("V2ForegroundAppMonitor", "running task foreground check failed", it) }.getOrNull()

    private fun findByUsageEvents(matcher: TargetMatcher, now: Long): String? = runCatching {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val lastStates = LinkedHashMap<String, Boolean>()
        val events = usageStatsManager.queryEvents(now - USAGE_EVENTS_WINDOW_MS, now) ?: return@runCatching null
        val event = UsageEvents.Event()
        val shouldCollectSamples = now - lastUsageEventsLogMs >= USAGE_EVENTS_LOG_INTERVAL_MS
        val samples = if (shouldCollectSamples) mutableListOf<String>() else null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (samples != null && matcher.shouldSample(event.packageName, event.className, event.eventType) && samples.size < MAX_USAGE_EVENT_SAMPLES) {
                samples += "type=${eventName(event.eventType)} package=${event.packageName} class=${event.className}"
            }
            val matched = matcher.match(event.packageName, event.className) ?: continue
            @Suppress("DEPRECATION")
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND, UsageEvents.Event.ACTIVITY_RESUMED -> lastStates[matched] = true
                UsageEvents.Event.MOVE_TO_BACKGROUND, UsageEvents.Event.ACTIVITY_PAUSED -> lastStates[matched] = false
            }
        }
        if (samples != null) logUsageSamples(now, samples, lastStates)
        matcher.targets.firstOrNull { lastStates[it] == true }?.also {
            V2AppLog.i("V2ForegroundAppMonitor", "usage events foreground target=$it states=$lastStates")
        }
    }.onFailure { V2AppLog.e("V2ForegroundAppMonitor", "usage events foreground check failed", it) }.getOrNull()

    private class TargetMatcher(val key: String, val targets: List<String>) {
        private val packageCandidatesByTarget: Map<String, Set<String>> = targets.associateWith(::targetPackageCandidates)

        fun match(packageName: String?, className: String?): String? {
            val pkg = packageName.orEmpty()
            val cls = className.orEmpty()
            val flattened = if (pkg.isNotBlank() && cls.isNotBlank()) "$pkg/$cls" else ""
            val shortFlattened = if (pkg.isNotBlank() && cls.startsWith(pkg)) "$pkg/.${cls.removePrefix(pkg).removePrefix(".")}" else ""
            return targets.firstOrNull { target ->
                target == pkg ||
                    target == cls ||
                    target == flattened ||
                    target == shortFlattened ||
                    (cls.isBlank() && packageCandidatesByTarget[target]?.contains(pkg) == true)
            }
        }

        fun shouldSample(packageName: String?, className: String?, eventType: Int): Boolean {
            if (!isForegroundStateEvent(eventType)) return false
            val pkg = packageName.orEmpty()
            val cls = className.orEmpty()
            if (match(pkg, cls) != null) return true
            return packageCandidatesByTarget.values.any { candidates ->
                candidates.any { it.isNotBlank() && (pkg == it || cls.startsWith(it)) }
            }
        }

        private companion object {
            private fun targetPackageCandidates(target: String): Set<String> = buildSet {
                val componentPackage = target.substringBefore('/', missingDelimiterValue = "")
                if (componentPackage.isNotBlank()) add(componentPackage)
                if ('/' !in target && target.none { it.isUpperCase() }) add(target)
                if ('/' !in target && target.any { it.isUpperCase() } && '.' in target) add(target.substringBeforeLast('.'))
            }
        }
    }

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
        private const val LOOKUP_CACHE_MS = 250L
        private const val USAGE_EVENTS_WINDOW_MS = 60_000L
        private const val MAX_USAGE_EVENT_SAMPLES = 12
        private const val USAGE_EVENTS_LOG_INTERVAL_MS = 30_000L

        @Suppress("DEPRECATION")
        private fun isForegroundStateEvent(eventType: Int): Boolean = eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ||
            eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
            eventType == UsageEvents.Event.ACTIVITY_PAUSED
    }
}
