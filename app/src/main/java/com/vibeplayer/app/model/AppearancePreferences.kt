package com.vibeplayer.app.model

import kotlin.math.roundToInt

enum class HomeLayout { TRENDY, TRADITIONAL }
enum class PlayerLayout { TRENDY, TRADITIONAL }

data class AppearancePreferences(
    val embyHomeLayout: HomeLayout = HomeLayout.TRENDY,
    val jellyfinHomeLayout: HomeLayout = HomeLayout.TRENDY,
    val playerLayout: PlayerLayout = PlayerLayout.TRENDY,
    val serviceCardScalePercent: Int = 100
)

fun normalizeServiceCardScale(percent: Int): Int =
    ((percent.coerceIn(50, 150) / 5f).roundToInt() * 5).coerceIn(50, 150)
