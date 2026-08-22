package com.vibeplayer.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Material 3 type scale defaults with a clean, modern look.
private val Default = Typography()

val Typography = Typography(
    // Display / headline scale (used sparingly by surfaces and large titles).
    displaySmall = Default.displaySmall.copy(
        letterSpacing = 0.2.sp
    ),
    headlineMedium = Default.headlineMedium.copy(
        letterSpacing = 0.15.sp
    ),
    headlineSmall = Default.headlineSmall.copy(
        letterSpacing = 0.1.sp
    ),
    // Title scale.
    titleLarge = Default.titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.sp
    ),
    titleMedium = Default.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.15.sp
    ),
    titleSmall = Default.titleSmall.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp
    ),
    // Body scale.
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.4.sp
    ),
    bodyMedium = Default.bodyMedium.copy(
        letterSpacing = 0.35.sp
    ),
    bodySmall = Default.bodySmall.copy(
        letterSpacing = 0.3.sp
    ),
    // Label scale (buttons, chips, tab labels).
    labelLarge = Default.labelLarge.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.3.sp
    ),
    labelMedium = Default.labelMedium.copy(
        letterSpacing = 0.4.sp
    ),
    labelSmall = Default.labelSmall.copy(
        letterSpacing = 0.5.sp
    )
)
