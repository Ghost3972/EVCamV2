package com.kooo.evcam.v2.ui.blindspot

import android.view.MotionEvent
import android.view.View

internal class V2BlindSpotOverlayGestureController(
    private val windowLayout: V2BlindSpotWindowLayoutController,
    private val rootView: () -> View?,
    private val resizeButtonView: () -> View?,
    private val currentSide: () -> String,
    private val shouldHandleRootTouch: (Float, Float) -> Boolean,
    private val onPreviewTransformNeeded: () -> Unit,
) {
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartX = 0
    private var dragStartY = 0
    private var resizeStartRawX = 0f
    private var resizeStartRawY = 0f
    private var resizeStartWidth = 0
    private var resizeStartHeight = 0
    private var resizeStartX = 0
    private var resizeStartY = 0
    private var dragInProgress = false
    private var resizeInProgress = false

    val rootDragTouchListener: View.OnTouchListener = DragTouchListener(shouldHandleRootTouch)
    val dragHandleTouchListener: View.OnTouchListener = DragTouchListener()

    fun createResizeTouchListener(): View.OnTouchListener = ResizeTouchListener()

    fun reset() {
        dragInProgress = false
        resizeInProgress = false
    }

    private inner class DragTouchListener(
        private val touchFilter: ((Float, Float) -> Boolean)? = null,
    ) : View.OnTouchListener {
        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            val params = windowLayout.currentParams ?: return false
            val view = rootView() ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (touchFilter?.invoke(event.x, event.y) == false) return false
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    dragStartX = params.x
                    dragStartY = params.y
                    dragInProgress = true
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragInProgress) return false
                    val nextX = windowLayout.clampX(dragStartX + (event.rawX - dragStartRawX).toInt(), params.width)
                    val nextY = windowLayout.clampY(dragStartY + (event.rawY - dragStartRawY).toInt(), params.height)
                    if (kotlin.math.abs(nextX - params.x) >= MOVE_UPDATE_THRESHOLD_PX || kotlin.math.abs(nextY - params.y) >= MOVE_UPDATE_THRESHOLD_PX) {
                        params.x = nextX
                        params.y = nextY
                        windowLayout.requestUpdate(view)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragInProgress) return false
                    dragInProgress = false
                    windowLayout.updateNow(view)
                    windowLayout.saveBounds(currentSide())
                    return true
                }
            }
            return true
        }
    }

    private inner class ResizeTouchListener : View.OnTouchListener {
        private var active = false

        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            val params = windowLayout.currentParams ?: return false
            val view = rootView() ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resizeStartRawX = event.rawX
                    resizeStartRawY = event.rawY
                    resizeStartWidth = params.width
                    resizeStartHeight = params.height
                    resizeStartX = params.x
                    resizeStartY = params.y
                    resizeInProgress = true
                    active = true
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!active) return false
                    val fromTopRightHandle = v === resizeButtonView()
                    val deltaX = (event.rawX - resizeStartRawX).toInt()
                    val deltaY = (event.rawY - resizeStartRawY).toInt()
                    val nextWidth = windowLayout.clampWidth(resizeStartWidth + deltaX)
                    val nextHeight = if (fromTopRightHandle) {
                        windowLayout.clampHeight(resizeStartHeight - deltaY)
                    } else {
                        windowLayout.clampHeight(resizeStartHeight + deltaY)
                    }
                    if (kotlin.math.abs(nextWidth - params.width) >= RESIZE_UPDATE_THRESHOLD_PX || kotlin.math.abs(nextHeight - params.height) >= RESIZE_UPDATE_THRESHOLD_PX) {
                        params.width = nextWidth
                        params.height = nextHeight
                        params.x = windowLayout.clampX(resizeStartX, params.width)
                        params.y = if (fromTopRightHandle) {
                            windowLayout.clampY(resizeStartY + resizeStartHeight - nextHeight, params.height)
                        } else {
                            windowLayout.clampY(resizeStartY, params.height)
                        }
                        onPreviewTransformNeeded()
                        windowLayout.requestUpdate(view)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!active) return false
                    active = false
                    resizeInProgress = false
                    windowLayout.updateNow(view)
                    onPreviewTransformNeeded()
                    windowLayout.saveBounds(currentSide())
                    return true
                }
            }
            return true
        }
    }

    private companion object {
        private const val MOVE_UPDATE_THRESHOLD_PX = 3
        private const val RESIZE_UPDATE_THRESHOLD_PX = 6
    }
}
