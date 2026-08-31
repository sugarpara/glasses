package com.example.glasses.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val AppFont = FontFamily.SansSerif

val Typography = Typography(
    headlineLarge = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.sp),
    labelLarge = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
    labelMedium = TextStyle(fontFamily = AppFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
)
