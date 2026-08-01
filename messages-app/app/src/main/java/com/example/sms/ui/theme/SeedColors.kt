package com.example.sms.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 手动主题色的候选种子色。
 *
 * 种子色只是「输入」：完整的 Material 3 配色方案（primary / secondary / tertiary /
 * surface 各档色调、以及对应的深色方案）由 material-kolor 按 M3 官方调色算法
 * 从种子色推导出来。因此 Android 12 以下的机器也能得到与动态取色同样规范的配色。
 */
data class SeedOption(val name: String, val color: Color) {
    val argb: Int get() = color.toArgb()
}

/** seedColor 存 0 表示「不使用手动主题色」 */
const val SEED_NONE: Int = 0

val seedOptions: List<SeedOption> = listOf(
    SeedOption("默认紫", Color(0xFF6750A4)),
    SeedOption("靛蓝", Color(0xFF3F51B5)),
    SeedOption("天青", Color(0xFF0061A4)),
    SeedOption("薄荷", Color(0xFF006A60)),
    SeedOption("森绿", Color(0xFF386A20)),
    SeedOption("琥珀", Color(0xFF8C4A00)),
    SeedOption("赤陶", Color(0xFFB3261E)),
    SeedOption("玫瑰", Color(0xFF8E4585)),
)
