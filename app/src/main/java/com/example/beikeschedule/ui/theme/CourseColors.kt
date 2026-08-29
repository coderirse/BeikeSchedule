package com.example.beikeschedule.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** 课程块色板（参考 WakeUp：浅色底 + 同色深字）。按 colorIndex 取模循环。 */
object CourseColors {
    // (底色, 文字色) 对
    private val basePalette = listOf(
        Color(0xFFFCDFD6) to Color(0xFF8C3B2E), // 0 珊瑚粉
        Color(0xFFD6E4FC) to Color(0xFF2E4E8C), // 1 淡蓝
        Color(0xFFD9F0DC) to Color(0xFF2F6B3C), // 2 淡绿
        Color(0xFFFCEFD6) to Color(0xFF8C6A2E), // 3 杏黄
        Color(0xFFE8DFFC) to Color(0xFF5B3E8C), // 4 淡紫
        Color(0xFFD6F0F5) to Color(0xFF2E7B8C), // 5 青
        Color(0xFFFCDDE8) to Color(0xFF8C2E56), // 6 玫粉
        Color(0xFFE3E8EF) to Color(0xFF45536B), // 7 灰蓝
        Color(0xFFF0F5D6) to Color(0xFF6B7A2E), // 8 草绿
        Color(0xFFDFE8FC) to Color(0xFF3E5B8C), // 9 靛蓝
    )

    private val userPalette = basePalette

    val defaultColorIndex: Int get() = 0

    /**
     * 教务课程颜色：教务 XB 色值如果在本色板范围内直接用（保证导入课与原版一致），
     * 超出范围或与已有冲突时退化为取模，保证无固定时间课程等不撞色由分配逻辑处理。
     */
    fun importedOf(colorIndex: Int): Int =
        if (colorIndex in basePalette.indices) colorIndex else kotlin.math.abs(colorIndex) % basePalette.size

    /** 返回 (底色, 文字色)。 */
    fun of(colorIndex: Int): Pair<Color, Color> {
        val c = userPalette[kotlin.math.abs(colorIndex) % userPalette.size]
        // 渐变背景上，课程块用不透明浅色底以保证清晰；文字色不变
        return c
    }

    /** 渐变背景的主色（半透明，供卡片叠加时显得通透）。 */
    val scheduleGradient = Brush.verticalGradient(
        0f to Color(0xFFE8E4F8),   // 顶部淡蓝紫
        0.45f to Color(0xFFF3ECF7), // 中部淡紫
        0.75f to Color(0xFFFBEBEE), // 下部暖粉
        1f to Color(0xFFFCE8E2),   // 底部暖橙
    )
}
