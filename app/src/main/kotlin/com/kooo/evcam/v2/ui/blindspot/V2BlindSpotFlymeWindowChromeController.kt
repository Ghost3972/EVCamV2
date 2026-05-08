package com.kooo.evcam.v2.ui.blindspot

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.kooo.evcam.v2.log.V2AppLog

internal class V2BlindSpotFlymeWindowChromeController(
    private val activity: AppCompatActivity,
    private val onCloseRequested: (String) -> Unit,
) {
    private var actionListenerProxy: Any? = null
    private var closeRequested = false

    fun disable(reason: String) {
        hideFlymeWindowActions(reason)
        suppressFlymeCaptionTouchTargets(reason)
        activity.window.decorView.post { suppressFlymeCaptionTouchTargets("$reason/post") }
    }

    private fun hideFlymeWindowActions(reason: String) {
        runCatching {
            val clazz = Class.forName(FLYME_DECOR_CAPTION_MANAGER_CLASS)
            val hideState = clazz.getField(FLYME_MINI_WINDOW_HIDE_STATE_FIELD).getInt(null)
            val showState = runCatching { clazz.getField(FLYME_MINI_WINDOW_SHOW_STATE_FIELD).getInt(null) }.getOrDefault(0)
            val method = clazz.getMethod(
                "setMiniWindowActionState",
                Context::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            )
            val hiddenButtons = FLYME_MINI_WINDOW_HIDDEN_ACTION_FIELDS.mapNotNull { field ->
                runCatching { clazz.getField(field).getInt(null) }.getOrNull()
            }.distinct()
            val shownButtons = FLYME_MINI_WINDOW_VISIBLE_ACTION_FIELDS.mapNotNull { field ->
                runCatching { clazz.getField(field).getInt(null) }.getOrNull()
            }.distinct()
            hiddenButtons.forEach { buttonId ->
                method.invoke(null, activity, buttonId, hideState)
            }
            shownButtons.forEach { buttonId ->
                method.invoke(null, activity, buttonId, showState)
            }
            consumeFlymeWindowActions(clazz, reason)
            V2AppLog.i(TAG, "hide flyme mini-window actions reason=$reason hidden=$hiddenButtons shown=$shownButtons")
        }.onFailure {
            V2AppLog.w(TAG, "hide flyme mini-window actions failed reason=$reason", it)
        }
    }

    private fun consumeFlymeWindowActions(captionManagerClass: Class<*>, reason: String) {
        runCatching {
            val listenerClass = Class.forName(FLYME_MINI_WINDOW_ACTION_LISTENER_CLASS)
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, method, args ->
                when (method.name) {
                    "toString" -> "EVCamBlindSpotFlymeActionBlocker"
                    "hashCode" -> System.identityHashCode(actionListenerProxy ?: this)
                    "equals" -> false
                    else -> {
                        val view = args?.firstOrNull() as? View
                        if (isFlymeCloseActionView(view)) {
                            requestClose("action_listener:${method.name}")
                        }
                        when (method.returnType) {
                            Boolean::class.javaPrimitiveType -> true
                            Int::class.javaPrimitiveType -> 0
                            else -> null
                        }
                    }
                }
            }
            actionListenerProxy = proxy
            captionManagerClass.getMethod("setMiniWindowActionListener", Context::class.java, listenerClass)
                .invoke(null, activity, proxy)
            V2AppLog.i(TAG, "consume flyme mini-window actions reason=$reason")
        }.onFailure {
            V2AppLog.w(TAG, "consume flyme mini-window actions failed reason=$reason", it)
        }
    }

    private fun isFlymeCloseActionView(view: View?): Boolean {
        if (view == null) return false
        return flymeCloseActionIds().contains(view.id)
    }

    private fun flymeCloseActionIds(): Set<Int> {
        val ids = linkedSetOf<Int>()
        runCatching {
            val clazz = Class.forName(FLYME_DECOR_CAPTION_MANAGER_CLASS)
            FLYME_MINI_WINDOW_VISIBLE_ACTION_FIELDS.forEach { field ->
                runCatching { clazz.getField(field).getInt(null) }.getOrNull()?.let { ids += it }
            }
        }
        FLYME_CLOSE_VIEW_NAMES.forEach { name ->
            flymeInternalResId(name)?.let { ids += it }
        }
        return ids
    }

    private fun suppressFlymeCaptionTouchTargets(reason: String) {
        val root = activity.window.decorView ?: return
        val hiddenTargets = linkedMapOf<String, View>()
        val touchOnlyTargets = linkedMapOf<String, View>()
        val closeTargets = linkedMapOf<String, View>()
        FLYME_MINI_WINDOW_HIDDEN_VIEW_NAMES.forEach { name ->
            val id = flymeInternalResId(name) ?: return@forEach
            val view = root.findViewById<View>(id) ?: return@forEach
            hiddenTargets["id:$name"] = view
        }
        FLYME_MINI_WINDOW_TOUCH_ONLY_VIEW_NAMES.forEach { name ->
            val id = flymeInternalResId(name) ?: return@forEach
            val view = root.findViewById<View>(id) ?: return@forEach
            touchOnlyTargets["id:$name"] = view
        }
        FLYME_CLOSE_VIEW_NAMES.forEach { name ->
            val id = flymeInternalResId(name) ?: return@forEach
            val view = root.findViewById<View>(id) ?: return@forEach
            closeTargets["id:$name"] = view
        }
        flymeCaptionContainers(root).forEach { container ->
            FLYME_HIDDEN_CAPTION_FIELD_NAMES.forEach { fieldName ->
                val view = reflectFieldValue(container, fieldName) as? View ?: return@forEach
                hiddenTargets["field:$fieldName"] = view
            }
            FLYME_TOUCH_ONLY_CAPTION_FIELD_NAMES.forEach { fieldName ->
                val view = reflectFieldValue(container, fieldName) as? View ?: return@forEach
                touchOnlyTargets["field:$fieldName"] = view
            }
            FLYME_MINI_WINDOW_HIDDEN_VIEW_NAMES.forEach { name ->
                val id = flymeInternalResId(name) ?: return@forEach
                val view = container.findViewById<View>(id) ?: return@forEach
                hiddenTargets["captionId:$name"] = view
            }
            FLYME_MINI_WINDOW_TOUCH_ONLY_VIEW_NAMES.forEach { name ->
                val id = flymeInternalResId(name) ?: return@forEach
                val view = container.findViewById<View>(id) ?: return@forEach
                touchOnlyTargets["captionId:$name"] = view
            }
            FLYME_CLOSE_VIEW_NAMES.forEach { name ->
                val id = flymeInternalResId(name) ?: return@forEach
                val view = container.findViewById<View>(id) ?: return@forEach
                closeTargets["captionId:$name"] = view
            }
        }
        val hidden = hiddenTargets.mapNotNull { (name, view) ->
            if (view.javaClass.name == FLYME_DECOR_CAPTION_VIEW_CLASS) return@mapNotNull null
            view.setOnClickListener {}
            view.setOnTouchListener { _, _ -> true }
            view.isClickable = true
            view.isLongClickable = false
            view.visibility = View.GONE
            "$name/${view.javaClass.simpleName}"
        }
        val touchOnly = touchOnlyTargets.mapNotNull { (name, view) ->
            if (view.javaClass.name == FLYME_DECOR_CAPTION_VIEW_CLASS) return@mapNotNull null
            view.setOnTouchListener { _, event -> !isTouchInsideCloseTarget(event, closeTargets.values) }
            view.isLongClickable = false
            view.visibility = View.VISIBLE
            "$name/${view.javaClass.simpleName}"
        }
        val close = closeTargets.mapNotNull { (name, view) ->
            if (view.javaClass.name == FLYME_DECOR_CAPTION_VIEW_CLASS) return@mapNotNull null
            view.setOnClickListener { requestClose("caption_click:$name") }
            view.setOnTouchListener { touchView, event ->
                if (event.action == MotionEvent.ACTION_UP) touchView.performClick()
                true
            }
            view.isClickable = true
            view.isEnabled = true
            view.visibility = View.VISIBLE
            view.alpha = 1f
            view.bringToFront()
            "$name/${view.javaClass.simpleName}"
        }
        if (hidden.isNotEmpty() || touchOnly.isNotEmpty() || close.isNotEmpty()) {
            V2AppLog.i(TAG, "suppress flyme mini-window caption reason=$reason hidden=$hidden touchOnly=$touchOnly close=$close")
        }
    }

    private fun requestClose(reason: String) {
        if (closeRequested) return
        closeRequested = true
        V2AppLog.i(TAG, "flyme mini-window close requested reason=$reason")
        activity.window.decorView.post { onCloseRequested(reason) }
    }

    private fun isTouchInsideCloseTarget(event: MotionEvent, closeTargets: Collection<View>): Boolean {
        val location = IntArray(2)
        return closeTargets.any { target ->
            if (!target.isShown) return@any false
            runCatching {
                target.getLocationOnScreen(location)
                event.rawX >= location[0] &&
                    event.rawX <= location[0] + target.width &&
                    event.rawY >= location[1] &&
                    event.rawY <= location[1] + target.height
            }.getOrDefault(false)
        }
    }

    private fun flymeCaptionContainers(root: View): List<View> {
        val containers = linkedSetOf<View>()
        collectFlymeCaptionContainers(root, containers)
        (reflectFieldValue(root, FLYME_DECOR_CAPTION_VIEW_FIELD) as? View)?.let { containers += it }
        (reflectFieldValue(activity.window, FLYME_DECOR_CAPTION_VIEW_FIELD) as? View)?.let { containers += it }
        return containers.toList()
    }

    private fun collectFlymeCaptionContainers(view: View, containers: MutableSet<View>) {
        if (view.javaClass.name == FLYME_DECOR_CAPTION_VIEW_CLASS) containers += view
        if (view !is ViewGroup) return
        for (index in 0 until view.childCount) {
            collectFlymeCaptionContainers(view.getChildAt(index), containers)
        }
    }

    private fun reflectFieldValue(instance: Any, fieldName: String): Any? {
        var clazz: Class<*>? = instance.javaClass
        while (clazz != null) {
            val value = runCatching {
                val field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true
                field.get(instance)
            }.getOrNull()
            if (value != null) return value
            clazz = clazz.superclass
        }
        return null
    }

    private fun flymeInternalResId(name: String): Int? {
        return runCatching {
            val clazz = Class.forName(FLYME_INTERNAL_RESOURCE_UTILS_CLASS)
            val method = clazz.getMethod(
                "getInternalResId",
                Int::class.javaPrimitiveType!!,
                String::class.java,
            )
            method.invoke(null, FLYME_INTERNAL_RESOURCE_TYPE_ID, name) as? Int
        }.getOrNull()?.takeIf { it != 0 }
    }

    private companion object {
        private const val TAG = "V2BlindSpotSmallWindow"
        private const val FLYME_DECOR_CAPTION_MANAGER_CLASS = "flyme.view.minimenu.FlymeDecorCaptionManager"
        private const val FLYME_MINI_WINDOW_ACTION_LISTENER_CLASS = "flyme.view.minimenu.FlymeDecorCaptionManager\$MiniWindowActionListener"
        private const val FLYME_DECOR_CAPTION_VIEW_CLASS = "flyme.view.minimenu.FlymeDecorCaptionView"
        private const val FLYME_DECOR_CAPTION_VIEW_FIELD = "mDecorCaptionView"
        private const val FLYME_INTERNAL_RESOURCE_UTILS_CLASS = "flyme.utils.InternalResourceUtils"
        private const val FLYME_INTERNAL_RESOURCE_TYPE_ID = 0
        private const val FLYME_MINI_WINDOW_HIDE_STATE_FIELD = "MINI_WINDOW_ACTION_STATE_HIDE"
        private const val FLYME_MINI_WINDOW_SHOW_STATE_FIELD = "MINI_WINDOW_ACTION_STATE_SHOW"
        private val FLYME_MINI_WINDOW_HIDDEN_ACTION_FIELDS = listOf(
            "MINI_WINDOW_ACTION_BUTTON_FULL",
            "MINI_WINDOW_ACTION_BUTTON_PIN",
            "MINI_WINDOW_MENU_DRAG_VIEW",
            "MINI_WINDOW_MENU_CAPTION",
        )
        private val FLYME_MINI_WINDOW_VISIBLE_ACTION_FIELDS = listOf(
            "MINI_WINDOW_ACTION_BUTTON_CLOSE",
        )
        private val FLYME_MINI_WINDOW_HIDDEN_VIEW_NAMES = listOf(
            "mini_menu_drag_icon",
            "mini_menu_full_screen_icon",
            "mini_menu_topin_icon",
        )
        private val FLYME_MINI_WINDOW_TOUCH_ONLY_VIEW_NAMES = listOf(
            "menu_parent",
        )
        private val FLYME_CLOSE_VIEW_NAMES = listOf(
            "mini_menu_close_icon",
        )
        private val FLYME_HIDDEN_CAPTION_FIELD_NAMES = listOf(
            "mDragIcon",
            "mFullScreenIcon",
            "mToPinIcon",
        )
        private val FLYME_TOUCH_ONLY_CAPTION_FIELD_NAMES = listOf(
            "mMenuParent",
        )
    }
}
