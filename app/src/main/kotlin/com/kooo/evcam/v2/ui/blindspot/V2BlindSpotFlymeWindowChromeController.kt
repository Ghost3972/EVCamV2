package com.kooo.evcam.v2.ui.blindspot

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.kooo.evcam.R
import com.kooo.evcam.v2.log.V2AppLog

internal class V2BlindSpotFlymeWindowChromeController(
    private val activity: AppCompatActivity,
) {
    private var actionListenerProxy: Any? = null

    fun disable(reason: String) {
        applyAvmCaptionStyle(reason)
        hideSideActionsWithFlyme(reason)
        consumeHiddenActionClicks(reason)
        suppressHiddenActionViews(reason)
        activity.window.decorView.post {
            applyAvmCaptionStyle("$reason/post")
            hideSideActionsWithFlyme("$reason/post")
            suppressHiddenActionViews("$reason/post")
        }
    }

    private fun applyAvmCaptionStyle(reason: String) {
        runCatching {
            val clazz = Class.forName(FLYME_DECOR_CAPTION_MANAGER_CLASS)
            clazz.getMethod(
                "setDecorCaptionViewBackground",
                Context::class.java,
                Int::class.javaPrimitiveType!!,
            ).invoke(null, activity, R.drawable.flyme_avm_small_window_caption_bg)

            val dragAction = clazz.getField(FLYME_MINI_WINDOW_DRAG_ACTION_FIELD).getInt(null)
            clazz.getMethod(
                "setMiniWindowActionIcon",
                Context::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            ).invoke(
                null,
                activity,
                dragAction,
                R.drawable.flyme_avm_small_window_drag,
                R.drawable.flyme_avm_small_window_drag,
            )
            setActionState(clazz, dragAction, MINI_WINDOW_ACTION_STATE_NORMAL)
            V2AppLog.i(TAG, "apply avm flyme caption style reason=$reason dragAction=$dragAction")
        }.onFailure {
            V2AppLog.w(TAG, "apply avm flyme caption style failed reason=$reason", it)
        }
    }

    private fun hideSideActionsWithFlyme(reason: String) {
        runCatching {
            val clazz = Class.forName(FLYME_DECOR_CAPTION_MANAGER_CLASS)
            val hiddenActions = FLYME_MINI_WINDOW_HIDDEN_ACTION_FIELDS.mapNotNull { field ->
                runCatching { clazz.getField(field).getInt(null) }.getOrNull()
            }.distinct()
            hiddenActions.forEach { action ->
                setActionState(clazz, action, MINI_WINDOW_ACTION_STATE_HIDE)
            }
            V2AppLog.i(TAG, "hide avm flyme side actions reason=$reason hidden=$hiddenActions")
        }.onFailure {
            V2AppLog.w(TAG, "hide avm flyme side actions failed reason=$reason", it)
        }
    }

    private fun setActionState(captionManagerClass: Class<*>, action: Int, state: Int) {
        captionManagerClass.getMethod(
            "setMiniWindowActionState",
            Context::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        ).invoke(null, activity, action, state)
    }

    private fun consumeHiddenActionClicks(reason: String) {
        runCatching {
            val captionManagerClass = Class.forName(FLYME_DECOR_CAPTION_MANAGER_CLASS)
            val listenerClass = Class.forName(FLYME_MINI_WINDOW_ACTION_LISTENER_CLASS)
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, method, args ->
                when (method.name) {
                    "toString" -> "EVCamBlindSpotAvmActionBlocker"
                    "hashCode" -> System.identityHashCode(actionListenerProxy ?: this)
                    "equals" -> args?.firstOrNull() === actionListenerProxy
                    else -> {
                        val view = args?.firstOrNull() as? View
                        val hiddenAction = isHiddenActionView(view)
                        when (method.returnType) {
                            Boolean::class.javaPrimitiveType -> hiddenAction
                            Int::class.javaPrimitiveType -> if (hiddenAction) 1 else 0
                            else -> null
                        }
                    }
                }
            }
            actionListenerProxy = proxy
            captionManagerClass.getMethod("setMiniWindowActionListener", Context::class.java, listenerClass)
                .invoke(null, activity, proxy)
            V2AppLog.i(TAG, "consume avm flyme hidden action clicks reason=$reason")
        }.onFailure {
            V2AppLog.w(TAG, "consume avm flyme hidden action clicks failed reason=$reason", it)
        }
    }

    private fun isHiddenActionView(view: View?): Boolean {
        if (view == null) return false
        return hiddenActionIds().contains(view.id)
    }

    private fun suppressHiddenActionViews(reason: String) {
        val root = activity.window.decorView ?: return
        val hiddenTargets = linkedMapOf<String, View>()
        FLYME_MINI_WINDOW_HIDDEN_VIEW_NAMES.forEach { name ->
            val id = flymeInternalResId(name) ?: return@forEach
            root.findViewById<View>(id)?.let { hiddenTargets["id:$name"] = it }
        }
        collectFlymeCaptionContainers(root).forEach { container ->
            FLYME_MINI_WINDOW_HIDDEN_VIEW_NAMES.forEach { name ->
                val id = flymeInternalResId(name) ?: return@forEach
                container.findViewById<View>(id)?.let { hiddenTargets["captionId:$name"] = it }
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
        if (hidden.isNotEmpty()) {
            V2AppLog.i(TAG, "suppress avm flyme hidden actions reason=$reason hidden=$hidden")
        }
    }

    private fun hiddenActionIds(): Set<Int> {
        val ids = linkedSetOf<Int>()
        runCatching {
            val clazz = Class.forName(FLYME_DECOR_CAPTION_MANAGER_CLASS)
            FLYME_MINI_WINDOW_HIDDEN_ACTION_FIELDS.forEach { field ->
                runCatching { clazz.getField(field).getInt(null) }.getOrNull()?.let { ids += it }
            }
        }
        FLYME_MINI_WINDOW_HIDDEN_VIEW_NAMES.forEach { name ->
            flymeInternalResId(name)?.let { ids += it }
        }
        return ids
    }

    private fun collectFlymeCaptionContainers(root: View): List<View> {
        val containers = linkedSetOf<View>()
        collectViews(root) { view ->
            if (view.javaClass.name == FLYME_DECOR_CAPTION_VIEW_CLASS) containers += view
        }
        return containers.toList()
    }

    private fun collectViews(view: View, visitor: (View) -> Unit) {
        visitor(view)
        if (view !is ViewGroup) return
        for (index in 0 until view.childCount) {
            collectViews(view.getChildAt(index), visitor)
        }
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
        private const val FLYME_MINI_WINDOW_ACTION_LISTENER_CLASS =
            "flyme.view.minimenu.FlymeDecorCaptionManager\$MiniWindowActionListener"
        private const val FLYME_DECOR_CAPTION_VIEW_CLASS = "flyme.view.minimenu.FlymeDecorCaptionView"
        private const val FLYME_INTERNAL_RESOURCE_UTILS_CLASS = "flyme.utils.InternalResourceUtils"
        private const val FLYME_INTERNAL_RESOURCE_TYPE_ID = 0
        private const val FLYME_MINI_WINDOW_DRAG_ACTION_FIELD = "MINI_WINDOW_MENU_DRAG_VIEW"
        private const val MINI_WINDOW_ACTION_STATE_NORMAL = 0
        private const val MINI_WINDOW_ACTION_STATE_HIDE = 1
        private val FLYME_MINI_WINDOW_HIDDEN_ACTION_FIELDS = listOf(
            "MINI_WINDOW_ACTION_BUTTON_BACK",
            "MINI_WINDOW_ACTION_BUTTON_CLOSE",
            "MINI_WINDOW_ACTION_BUTTON_FULL",
            "MINI_WINDOW_ACTION_BUTTON_PIN",
        )
        private val FLYME_MINI_WINDOW_HIDDEN_VIEW_NAMES = listOf(
            "mini_menu_close_icon",
            "mini_menu_full_screen_icon",
            "mini_menu_full_icon",
            "mini_menu_topin_icon",
            "mini_menu_pin_icon",
        )
    }
}
