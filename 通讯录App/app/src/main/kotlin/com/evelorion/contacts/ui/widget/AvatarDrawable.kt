package com.evelorion.contacts.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.min

/**
 * 首字母头像。
 *
 * ── 颜色怎么定的 ────────────────────────────────────────────
 *
 * 用**联系人的稳定标识**（id）算哈希取模，不是列表下标。
 * 用下标的话，删掉一个人会导致它后面所有人的头像颜色集体变一次 ——
 * 用户会觉得「我的通讯录怎么自己乱了」。
 *
 * 8 个底色抄自设计稿的 AV 数组，都是 M3 的 40 号色阶。
 *
 * ── 为什么自己画而不是 FrameLayout + TextView ───────────────
 *
 * 列表里每行一个。套两层 View 意味着每行多两次测量和布局；
 * 一个 Drawable 直接画在 ImageView 里，滚动明显更稳。
 */
class AvatarDrawable(
    context: Context,
    private val initial: String,
    key: Any,
    private val shape: Shape = Shape.CIRCLE,
) : Drawable() {

    enum class Shape {
        CIRCLE,

        /** 详情页和编辑页的大头像，设计稿写的是 34% 圆角。 */
        SQUIRCLE,
    }

    private val dark = (context.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    private val bgColor = (if (dark) PALETTE_DARK else PALETTE_LIGHT)[
        abs(stableHash(key)) % PALETTE_LIGHT.size
    ]

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 深色模式下底色是 80 号色阶（偏亮），白字压上去看不清，
        // 所以按亮度决定配白字还是深字，而不是写死白色
        color = if (isLight(bgColor)) Color.parseColor("#1D1B20") else Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = min(b.width(), b.height()).toFloat()
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())

        when (shape) {
            Shape.CIRCLE -> canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), size / 2f, bgPaint)
            Shape.SQUIRCLE -> canvas.drawRoundRect(rect, size * 0.34f, size * 0.34f, bgPaint)
        }

        if (initial.isEmpty()) return

        // 字号取容器的 39%：设计稿 44dp 头像配 17sp 文字就是这个比例
        textPaint.textSize = size * 0.39f
        // drawText 的 y 是基线不是中心，减去 (ascent+descent)/2 才是视觉居中
        val fm = textPaint.fontMetrics
        canvas.drawText(initial, b.exactCenterX(), b.exactCenterY() - (fm.ascent + fm.descent) / 2f, textPaint)
    }

    override fun setAlpha(alpha: Int) {
        bgPaint.alpha = alpha
        textPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bgPaint.colorFilter = colorFilter
    }

    @Deprecated("Drawable 的抽象方法，必须实现")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** 设计稿里的 AV 数组，原样搬过来。 */
        private val PALETTE_LIGHT = intArrayOf(
            0xFF6750A4.toInt(), 0xFF7D5260.toInt(), 0xFF386A20.toInt(), 0xFF00639B.toInt(),
            0xFF8F4C38.toInt(), 0xFF4F378B.toInt(), 0xFF006A6A.toInt(), 0xFF7B5800.toInt(),
        )

        /** 深色模式下提亮到 80 号色阶，配深色文字。 */
        private val PALETTE_DARK = intArrayOf(
            0xFFB69DF8.toInt(), 0xFFEFB8C8.toInt(), 0xFF9CCC65.toInt(), 0xFF7FCFFF.toInt(),
            0xFFFFB5A0.toInt(), 0xFFD0BCFF.toInt(), 0xFF4CDADA.toInt(), 0xFFE4C44C.toInt(),
        )

        /**
         * 稳定哈希。
         *
         * 不用 Object.hashCode()：String 的确实稳定，但 Long/Int 装箱后语义不同，
         * 混用会让同一个人在「按 id 取色」和「按姓名取色」两条路径下拿到不同颜色。
         * 统一转字符串再算，行为可预期。
         */
        private fun stableHash(key: Any): Int {
            var h = 0
            for (c in key.toString()) h = 31 * h + c.code
            return h
        }

        private fun isLight(color: Int): Boolean {
            val r = Color.red(color) / 255.0
            val g = Color.green(color) / 255.0
            val b = Color.blue(color) / 255.0
            return (0.299 * r + 0.587 * g + 0.114 * b) > 0.6
        }

        /** 姓名首字。中文取第一个汉字，英文取首字母大写，空名回退 "?"。 */
        fun initialOf(name: String?): String {
            val t = name?.trim().orEmpty()
            if (t.isEmpty()) return "?"
            val c = t.first()
            return if (c.isLetter() || c.code > 0x2E80) c.uppercase() else "#"
        }
    }
}
