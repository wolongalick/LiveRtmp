package com.alick.livertmp.widget

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class SimpleDrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {


    // 画笔设置
    private val paint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND // 圆角线条
        strokeJoin = Paint.Join.ROUND // 圆角连接
        isAntiAlias = true // 抗锯齿
    }

    // 绘制路径
    private val drawingPath = Path()
    
    // 临时触摸点记录
    private var currentX = 0f
    private var currentY = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(drawingPath, paint) // 绘制当前路径
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 手指按下时开始新路径
                drawingPath.moveTo(event.x, event.y)
                currentX = event.x
                currentY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                // 优化：使用贝塞尔曲线使线条更平滑
                drawingPath.quadTo(
                    currentX, currentY,
                    (event.x + currentX) / 2, (event.y + currentY) / 2
                )
                currentX = event.x
                currentY = event.y
            }
            MotionEvent.ACTION_UP -> {
                // 手指抬起时完成路径
                drawingPath.lineTo(currentX, currentY)
            }
        }
        invalidate() // 重绘视图
        return true
    }

    /** 清除画布 */
    fun clearCanvas() {
        drawingPath.reset()
        invalidate()
    }

    /** 设置画笔颜色 */
    fun setPaintColor(color: Int) {
        paint.color = color
    }

    /** 设置画笔宽度 */
    fun setStrokeWidth(width: Float) {
        paint.strokeWidth = width
    }
}