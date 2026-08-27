package com.example.beikeschedule.ui.theme

import androidx.compose.ui.graphics.Color

/** 课程块色板（参考 WakeUp：浅色底 + 同色深字）。按 colorIndex 取模循环。 */
object CourseColors {
    private val palette = listOf(
        Color(0xFFFCDFD6) to Color(0xFF8C3B2E),
        Color(0xFFD6E4FC) to Color(0xFF2E4E8C),
        Color(0xFFD9F0DC) to Color(0xFF2F6B3C),
        Color(0xFFFCEFD6) to Color(0xFF8C6A2E),
        Color(0xFFE8DFFC) to Color(0xFF5B3E8C),
        Color(0xFFD6F0F5) to Color(0xFF2E7B8C),
        Color(0xFFFCDDE8) to Color(0xFF8C2E56),
        Color(0xFFE3E8EF) to Color(0xFF45536B),
        Color(0xFFF0F5D6) to Color(0xFF6B7A2E),
        Color(0xFFDFE8FC) to Color(0xFF3E5B8C),
    )

    /** 返回 (底色, 文字色)。 */
    fun of(colorIndex: Int): Pair<Color, Color> =
        palette[kotlin.math.abs(colorIndex) % palette.size]
}
