package com.toclient.android.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.sin

class SubscriptionUsageView(context: Context) : View(context) {
    var palette: ToPalette = ToPalette.Dark
        set(value) { field = value; invalidate() }
    var usedBytes: Long = 0
        set(value) { field = value.coerceAtLeast(0); invalidate() }
    var totalBytes: Long = 0
        set(value) { field = value.coerceAtLeast(0); invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wave = Path()
    private var reveal = 0f
    private var animated = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animated) {
            animated = true
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 520
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    reveal = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(context.dp(260), widthMeasureSpec),
            resolveSize(context.dp(34), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padding = context.dp(7).toFloat()
        val start = padding
        val end = width - padding
        val centerY = height / 2f
        val ratio = if (totalBytes <= 0) 0f
        else (usedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()
        val visibleRatio = ratio * reveal
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = context.dp(6).toFloat()
        paint.color = palette.lineStrong
        canvas.drawLine(start, centerY, end, centerY, paint)

        val available = end - start
        val progress = available * visibleRatio
        val from = if (rtl) end else start
        val direction = if (rtl) -1f else 1f
        wave.reset()
        val steps = maxOf(1, (progress / context.dp(4)).toInt())
        for (index in 0..steps) {
            val distance = progress * index / steps
            val x = from + direction * distance
            val y = centerY + sin(index * .9f) * context.dp(2)
            if (index == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
        }
        paint.strokeWidth = context.dp(3).toFloat()
        paint.color = when {
            ratio >= .95f -> palette.error
            ratio >= .8f -> palette.warning
            else -> palette.primary
        }
        paint.setShadowLayer(context.dp(5).toFloat(), 0f, 0f,
            Color.argb(85, Color.red(paint.color), Color.green(paint.color), Color.blue(paint.color)))
        setLayerType(LAYER_TYPE_SOFTWARE, paint)
        canvas.drawPath(wave, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
        canvas.drawCircle(from + direction * progress, centerY, context.dp(4).toFloat(), paint)
    }
}