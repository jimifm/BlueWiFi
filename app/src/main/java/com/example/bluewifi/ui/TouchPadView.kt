package com.example.bluewifi.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.bluewifi.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TouchPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface TouchPadListener {
        fun onMouseMove(dx: Byte, dy: Byte)
        fun onLeftClick()
        fun onRightClick()
    }

    var listener: TouchPadListener? = null

    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var isMoved = false
    private var pointerCount = 1

    private val touchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary)
        alpha = 40
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.touchpad_stroke)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    init {
        setBackgroundResource(R.drawable.bg_touchpad)
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // 绘制底部左右按键提示虚线
        val splitY = h * 0.75f
        canvas.drawLine(0f, splitY, w, splitY, linePaint)
        canvas.drawLine(w / 2, splitY, w / 2, h, linePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val currentPointerCount = event.pointerCount

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                downTime = System.currentTimeMillis()
                isMoved = false
                pointerCount = 1
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                pointerCount = max(pointerCount, currentPointerCount)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY

                if (abs(dx) > 2 || abs(dy) > 2) {
                    isMoved = true

                    // 灵敏度缩放系数
                    val sensitivity = 1.3f
                    val reportDx = clampToByte(dx * sensitivity)
                    val reportDy = clampToByte(dy * sensitivity)

                    if (reportDx.toInt() != 0 || reportDy.toInt() != 0) {
                        listener?.onMouseMove(reportDx, reportDy)
                    }

                    lastX = event.x
                    lastY = event.y
                }
            }

            MotionEvent.ACTION_UP -> {
                val duration = System.currentTimeMillis() - downTime
                val isClick = !isMoved && duration < 350

                if (isClick) {
                    val splitY = height * 0.75f
                    val isBottomHalf = event.y > splitY
                    val isRightSide = event.x > width / 2

                    if (pointerCount >= 2 || (isBottomHalf && isRightSide)) {
                        // 双指或右下角区域为右键
                        listener?.onRightClick()
                    } else {
                        // 其余区域为左键
                        listener?.onLeftClick()
                    }
                }
                parent?.requestDisallowInterceptTouchEvent(false)
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun clampToByte(value: Float): Byte {
        val intVal = value.toInt()
        val clamped = max(-127, min(127, intVal))
        return clamped.toByte()
    }
}
