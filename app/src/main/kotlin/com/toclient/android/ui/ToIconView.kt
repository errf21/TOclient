package com.toclient.android.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

enum class ToIcon {
    HOME, PROFILES, ACTIVITY, SETTINGS, PLUS, BACK, GLOBE, ROUTE, DNS,
    TUNNEL, THEME, INFO, SUBSCRIPTION, BACKUP, CHECK, CLOSE, BOLT
}

class ToIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    var icon: ToIcon = ToIcon.HOME
    var tint: Int = Color.WHITE
    constructor(context: Context, icon: ToIcon, tint: Int) : this(context) {
        this.icon = icon
        this.tint = tint
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val globeOval = RectF(8f, 4f, 16f, 20f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = context.dp(24)
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val ox = (width - s) / 2f
        val oy = (height - s) / 2f
        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(s / 24f, s / 24f)
        paint.color = tint
        paint.strokeWidth = 1.8f
        path.reset()
        when (icon) {
            ToIcon.HOME -> {
                path.moveTo(3.5f, 11f); path.lineTo(12f, 4f); path.lineTo(20.5f, 11f)
                path.moveTo(5.5f, 10f); path.lineTo(5.5f, 20f); path.lineTo(18.5f, 20f)
                path.lineTo(18.5f, 10f)
                path.moveTo(9.5f, 20f); path.lineTo(9.5f, 14f); path.lineTo(14.5f, 14f)
                path.lineTo(14.5f, 20f)
            }
            ToIcon.PROFILES -> {
                canvas.drawCircle(9f, 8f, 3f, paint); canvas.drawCircle(17f, 9f, 2.4f, paint)
                path.moveTo(3.5f, 19f); path.cubicTo(4f, 14f, 14f, 14f, 14.5f, 19f)
                path.moveTo(14f, 14.5f); path.cubicTo(18f, 13f, 21f, 15.5f, 20.5f, 19f)
            }
            ToIcon.ACTIVITY -> {
                path.moveTo(2.5f, 13f); path.lineTo(7f, 13f); path.lineTo(9.5f, 6f)
                path.lineTo(13f, 18f); path.lineTo(16f, 10f); path.lineTo(18f, 13f)
                path.lineTo(21.5f, 13f)
            }
            ToIcon.SETTINGS -> {
                canvas.drawCircle(12f, 12f, 3f, paint); canvas.drawCircle(12f, 12f, 7f, paint)
                for (i in 0 until 8) {
                    val a = Math.toRadians((i * 45).toDouble())
                    canvas.drawLine(
                        (12 + kotlin.math.cos(a) * 7).toFloat(),
                        (12 + kotlin.math.sin(a) * 7).toFloat(),
                        (12 + kotlin.math.cos(a) * 9).toFloat(),
                        (12 + kotlin.math.sin(a) * 9).toFloat(),
                        paint,
                    )
                }
            }
            ToIcon.PLUS -> {
                canvas.drawLine(12f, 4f, 12f, 20f, paint)
                canvas.drawLine(4f, 12f, 20f, 12f, paint)
            }
            ToIcon.BACK -> {
                path.moveTo(15f, 5f); path.lineTo(8f, 12f); path.lineTo(15f, 19f)
            }
            ToIcon.GLOBE -> {
                canvas.drawCircle(12f, 12f, 8f, paint)
                canvas.drawOval(globeOval, paint)
                canvas.drawLine(4f, 12f, 20f, 12f, paint)
            }
            ToIcon.ROUTE -> {
                canvas.drawCircle(6f, 6f, 2f, paint); canvas.drawCircle(18f, 18f, 2f, paint)
                path.moveTo(6f, 8f); path.cubicTo(6f, 15f, 18f, 9f, 18f, 16f)
            }
            ToIcon.DNS -> {
                canvas.drawCircle(6f, 7f, 2f, paint); canvas.drawCircle(18f, 7f, 2f, paint)
                canvas.drawCircle(12f, 18f, 2f, paint)
                canvas.drawLine(8f, 8f, 16f, 8f, paint)
                canvas.drawLine(7f, 9f, 11f, 16f, paint)
                canvas.drawLine(17f, 9f, 13f, 16f, paint)
            }
            ToIcon.TUNNEL -> {
                path.moveTo(4f, 19f); path.lineTo(4f, 12f); path.cubicTo(4f, 4f, 20f, 4f, 20f, 12f)
                path.lineTo(20f, 19f); path.moveTo(8f, 19f); path.lineTo(8f, 13f)
                path.cubicTo(8f, 9f, 16f, 9f, 16f, 13f); path.lineTo(16f, 19f)
            }
            ToIcon.THEME -> {
                canvas.drawCircle(12f, 12f, 4f, paint)
                for (i in 0 until 8) {
                    val a = Math.toRadians((i * 45).toDouble())
                    canvas.drawLine(
                        (12 + kotlin.math.cos(a) * 7).toFloat(),
                        (12 + kotlin.math.sin(a) * 7).toFloat(),
                        (12 + kotlin.math.cos(a) * 9).toFloat(),
                        (12 + kotlin.math.sin(a) * 9).toFloat(),
                        paint,
                    )
                }
            }
            ToIcon.INFO -> {
                canvas.drawCircle(12f, 12f, 8f, paint)
                canvas.drawLine(12f, 11f, 12f, 17f, paint)
                paint.style = Paint.Style.FILL; canvas.drawCircle(12f, 7.5f, 1f, paint)
                paint.style = Paint.Style.STROKE
            }
            ToIcon.SUBSCRIPTION -> {
                path.moveTo(5f, 7f); path.cubicTo(12f, 7f, 17f, 12f, 17f, 19f)
                path.moveTo(5f, 12f); path.cubicTo(9f, 12f, 12f, 15f, 12f, 19f)
                paint.style = Paint.Style.FILL; canvas.drawCircle(5f, 18.5f, 1.5f, paint)
                paint.style = Paint.Style.STROKE
            }
            ToIcon.BACKUP -> {
                path.moveTo(5f, 8f); path.lineTo(5f, 20f); path.lineTo(19f, 20f)
                path.lineTo(19f, 8f); path.lineTo(16f, 5f); path.lineTo(8f, 5f); path.close()
                path.moveTo(9f, 5f); path.lineTo(9f, 10f); path.lineTo(15f, 10f); path.lineTo(15f, 5f)
                path.moveTo(9f, 20f); path.lineTo(9f, 14f); path.lineTo(15f, 14f); path.lineTo(15f, 20f)
            }
            ToIcon.CHECK -> {
                path.moveTo(4f, 12f); path.lineTo(9f, 17f); path.lineTo(20f, 6f)
            }
            ToIcon.CLOSE -> {
                canvas.drawLine(5f, 5f, 19f, 19f, paint)
                canvas.drawLine(19f, 5f, 5f, 19f, paint)
            }
            ToIcon.BOLT -> {
                path.moveTo(13.5f, 2.5f)
                path.lineTo(5.5f, 13f)
                path.lineTo(11f, 13f)
                path.lineTo(9.5f, 21.5f)
                path.lineTo(18.5f, 10.5f)
                path.lineTo(13f, 10.5f)
                path.close()
            }
        }
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}