package com.evelorion.phone.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/** M3 Expressive 动效常量：emphasized 曲线 + 弹性形态变化 */
object Motion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val DurationShort = 200
    const val DurationMedium = 350
    const val DurationLong = 500

    fun <T> emphasized(duration: Int = DurationMedium): FiniteAnimationSpec<T> =
        tween(durationMillis = duration, easing = Emphasized)

    /** 按下/选中时的圆角形变，带一点回弹 */
    fun <T> springy(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)
}
