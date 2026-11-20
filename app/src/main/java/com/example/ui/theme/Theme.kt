package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LiquidOmniDarkColorScheme = darkColorScheme(
  primary = LiquidCyan,
  onPrimary = SpaceBlack,
  primaryContainer = SpaceCard,
  onPrimaryContainer = LiquidCyanLight,
  secondary = LiquidViolet,
  onSecondary = Color.White,
  secondaryContainer = SpaceCardBorder,
  onSecondaryContainer = LiquidVioletLight,
  tertiary = LiquidTeal,
  onTertiary = SpaceBlack,
  background = SpaceBlack,
  onBackground = TextPrimary,
  surface = SpaceDark,
  onSurface = TextPrimary,
  surfaceVariant = SpaceCard,
  onSurfaceVariant = TextSecondary,
  error = LiquidCoral,
  onError = Color.White
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false, // Keep cohesive Liquid AI aesthetic
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = LiquidOmniDarkColorScheme,
    typography = Typography,
    content = content
  )
}
