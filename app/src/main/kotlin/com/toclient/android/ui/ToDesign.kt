package com.toclient.android.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

data class ToPalette(
    val background: Int,
    val elevated: Int,
    val glass: Int,
    val glassStrong: Int,
    val primary: Int,
    val secondary: Int,
    val text: Int,
    val textSecondary: Int,
    val muted: Int,
    val line: Int,
    val lineStrong: Int,
    val success: Int,
    val warning: Int,
    val error: Int,
    val selected: Int,
) {
    companion object {
        val Dark = ToPalette(
            background = Color.rgb(8, 13, 18),
            elevated = Color.rgb(13, 21, 28),
            glass = Color.rgb(20, 33, 42),
            glassStrong = Color.rgb(26, 43, 52),
            primary = Color.rgb(57, 214, 181),
            secondary = Color.rgb(30, 159, 137),
            text = Color.rgb(242, 247, 245),
            textSecondary = Color.rgb(145, 165, 163),
            muted = Color.rgb(95, 113, 111),
            line = Color.argb(38, 221, 247, 241),
            lineStrong = Color.argb(70, 221, 247, 241),
            success = Color.rgb(66, 214, 165),
            warning = Color.rgb(231, 184, 92),
            error = Color.rgb(255, 107, 114),
            selected = Color.rgb(18, 57, 55),
        )
        val Light = ToPalette(
            background = Color.rgb(238, 242, 241),
            elevated = Color.rgb(248, 250, 249),
            glass = Color.rgb(243, 247, 245),
            glassStrong = Color.WHITE,
            primary = Color.rgb(8, 124, 108),
            secondary = Color.rgb(21, 155, 136),
            text = Color.rgb(16, 32, 30),
            textSecondary = Color.rgb(63, 87, 83),
            muted = Color.rgb(95, 113, 111),
            line = Color.argb(31, 16, 32, 30),
            lineStrong = Color.argb(61, 16, 32, 30),
            success = Color.rgb(8, 124, 108),
            warning = Color.rgb(150, 110, 17),
            error = Color.rgb(185, 67, 76),
            selected = Color.rgb(219, 244, 237),
        )
    }
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
fun Context.sp(value: Float): Float = value

fun roundedBackground(
    color: Int,
    radius: Float,
    strokeColor: Int? = null,
    strokeWidth: Int = 1,
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    setColor(color)
    cornerRadius = radius
    strokeColor?.let { setStroke(strokeWidth, it) }
}

fun Context.vertical(
    spacing: Int = 0,
    block: LinearLayout.() -> Unit = {},
): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (spacing > 0) showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    block()
}

fun Context.label(
    value: CharSequence,
    color: Int,
    size: Float = 14f,
    weight: Int = Typeface.NORMAL,
    gravityValue: Int = Gravity.START,
): TextView = TextView(this).apply {
    text = value
    setTextColor(color)
    textSize = size
    gravity = gravityValue
    includeFontPadding = false
    typeface = Typeface.create("sans", weight)
    setLineSpacing(0f, 1.08f)
}

fun View.pad(horizontal: Int, vertical: Int = horizontal): View = apply {
    setPadding(context.dp(horizontal), context.dp(vertical), context.dp(horizontal), context.dp(vertical))
}

fun ViewGroup.add(
    child: View,
    width: Int = if (this is LinearLayout && orientation == LinearLayout.HORIZONTAL) {
        ViewGroup.LayoutParams.WRAP_CONTENT
    } else {
        ViewGroup.LayoutParams.MATCH_PARENT
    },
    height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    weight: Float = 0f,
): View {
    val params = if (this is LinearLayout) {
        LinearLayout.LayoutParams(width, height, weight)
    } else {
        ViewGroup.LayoutParams(width, height)
    }
    addView(child, params)
    return child
}

fun View.margin(
    left: Int = 0,
    top: Int = 0,
    right: Int = 0,
    bottom: Int = 0,
) {
    (layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(
        context.dp(left),
        context.dp(top),
        context.dp(right),
        context.dp(bottom),
    )
}

fun View.marginRelative(
    start: Int = 0,
    top: Int = 0,
    end: Int = 0,
    bottom: Int = 0,
) {
    (layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
        marginStart = context.dp(start)
        topMargin = context.dp(top)
        marginEnd = context.dp(end)
        bottomMargin = context.dp(bottom)
    }
}