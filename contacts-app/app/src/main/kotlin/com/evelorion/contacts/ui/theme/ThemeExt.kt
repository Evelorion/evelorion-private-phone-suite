package com.evelorion.contacts.ui.theme

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat

/**
 * 从当前主题取一个颜色属性的实际值。
 *
 * 布局里能写 ?attr/colorPrimary，代码里不行 —— 代码拿到的是属性 id，
 * 要经过主题解析才变成颜色。
 *
 * context 必须是 **Activity**：applicationContext 上没叠加过 ThemeOverlay，
 * 用它取到的永远是默认紫。
 */
@ColorInt
fun Context.themeColor(@AttrRes attr: Int): Int {
    val v = TypedValue()
    theme.resolveAttribute(attr, v, true)
    return if (v.resourceId != 0) ContextCompat.getColor(this, v.resourceId) else v.data
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
