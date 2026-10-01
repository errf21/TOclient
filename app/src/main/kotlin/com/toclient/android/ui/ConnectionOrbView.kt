package com.toclient.android.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.sin

enum class OrbState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

class ConnectionOrbView(context: Context) : View(context) {
    var palette: ToPalette = ToPalette.Dark
        set(value) { field = value; rebuildGlowShader(); invalidate() }
    var state: OrbState = OrbState.DISCONNECTED
        set(value) {
            field = value
            contentDescription = value.name.lowercase().replaceFirstChar(Char::uppercase)
            rebuildGlowShader()
            invalidate()
        }
    private var phase = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var glowShader: RadialGradient? = null
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 5_200
        repeatCount = ValueAnimator.INFINITE
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            phase = it.animatedFraction
            invalidate()
        }
    }

    init {
        isClickable = true
        isFocusable = true
        minimumHeight = context.dp(220)
        minimumWidth = context.dp(220)
        foreground = RippleDrawable(
            ColorStateList.valueOf(Color.argb(32, 255, 255, 255)),
            null,
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.WHITE)
            },
        )
        contentDescription = "Connect"
        if (android.os.Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()) {
            animator.start()
        }
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = context.dp(236)
        setMeasuredDimension(resolveSize(desired, widthMeasureSpec), resolveSize(desired, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGlowShader()
    }

    private fun rebuildGlowShader() {
        if (width == 0 || height == 0) return
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) * .31f
        val signal = when (state) {
            OrbState.ERROR -> palette.error
            OrbState.CONNECTED, OrbState.RECONNECTING -> palette.primary
            OrbState.CONNECTING -> palette.secondary
            OrbState.DISCONNECTED -> palette.muted
        }
        glowShader = RadialGradient(
            cx - radius * .2f,
            cy - radius * .25f,
            radius * 1.2f,
            intArrayOf(
                Color.argb(if (state == OrbState.CONNECTED) 185 else 105, Color.red(signal), Color.green(signal), Color.blue(signal)),
                Color.argb(80, Color.red(signal), Color.green(signal), Color.blue(signal)),
                Color.argb(15, Color.red(signal), Color.green(signal), Color.blue(signal)),
            ),
            floatArrayOf(0f, .42f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val pulse = when (state) {
            OrbState.CONNECTING, OrbState.RECONNECTING -> sin(phase * 6.283f) * context.dp(3)
            OrbState.CONNECTED -> sin(phase * 6.283f) * context.dp(2)
            else -> 0f
        }
        val radius = minOf(width, height) * .31f + pulse
        val signal = when (state) {
            OrbState.ERROR -> palette.error
            OrbState.CONNECTED, OrbState.RECONNECTING -> palette.primary
            OrbState.CONNECTING -> palette.secondary
            OrbState.DISCONNECTED -> palette.muted
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(1).toFloat()
        repeat(3) { index ->
            val offset = phase * 6.283f + index * 2.094f
            path.reset()
            for (step in 0..120) {
                val a = step / 120f * 6.283f
                val wobble = sin(a * (2 + index) + offset) * context.dp(7 + index * 2)
                val rx = radius * (1.28f + index * .13f) + wobble
                val ry = radius * (.72f + index * .11f) - wobble * .25f
                val x = cx + cos(a) * rx
                val y = cy + sin(a) * ry
                if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            paint.color = Color.argb(42 - index * 7, Color.red(signal), Color.green(signal), Color.blue(signal))
            canvas.drawPath(path, paint)
        }
        paint.color = Color.argb(65, Color.red(signal), Color.green(signal), Color.blue(signal))
        canvas.drawCircle(cx, cy, radius * 1.18f, paint)
        paint.color = Color.argb(110, Color.red(signal), Color.green(signal), Color.blue(signal))
        canvas.drawCircle(cx, cy, radius, paint)

        // A glass rim and highlight keep the control dimensional in both themes.
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            cx - radius, cy - radius, cx + radius, cy + radius,
            intArrayOf(
                Color.argb(if (palette === ToPalette.Light) 178 else 84, 255, 255, 255),
                Color.argb(18, 255, 255, 255),
                Color.argb(34, Color.red(signal), Color.green(signal), Color.blue(signal)),
            ),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius * .98f, paint)
        paint.style = Paint.Style.FILL
        paint.shader = glowShader
        canvas.drawCircle(cx, cy, radius * .88f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = context.dp(2).toFloat()
        paint.color = if (palette === ToPalette.Light) palette.textSecondary else palette.text
        val mark = radius * .28f
        repeat(3) { index ->
            canvas.save()
            canvas.rotate(index * 60f, cx, cy)
            canvas.drawOval(cx - mark, cy - mark * .42f, cx + mark, cy + mark * .42f, paint)
            canvas.restore()
        }
        paint.style = Paint.Style.FILL
        repeat(3) { index ->
            val a = phase * 6.283 + index * 2.094
            val nr = radius * 1.58f
            paint.color = signal
            canvas.drawCircle(
                cx + cos(a).toFloat() * nr,
                cy + sin(a).toFloat() * nr * .62f,
                context.dp(2).toFloat(),
                paint,
            )
        }
    }
}