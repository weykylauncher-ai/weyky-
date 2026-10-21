package com.example.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun Modifier.jarvisTacticalBackground(
  backgroundColor: Color,
  accentColor: Color = JarvisHudColors.HologramCyan
): Modifier = this
  .background(backgroundColor)
  .drawBehind {
    val strokeWidth = 1f
    val gridSpacing = 44.dp.toPx()
    val gridColor = accentColor.copy(alpha = 0.035f)
    var x = 0f
    while (x < size.width) {
      drawLine(
        color = gridColor,
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = strokeWidth
      )
      x += gridSpacing
    }
    var y = 0f
    while (y < size.height) {
      drawLine(
        color = gridColor,
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = strokeWidth
      )
      y += gridSpacing
    }
  }

enum class HudTabPosition {
  NONE,
  TOP_START,
  TOP_END,
  BOTTOM_START,
  BOTTOM_END
}

object JarvisHudColors {
  val HologramCyan = Color(0xFF00E5FF)
  val PanelBg = Color(0xDD0D1117)
  val TextPrimary = Color(0xFFF0F6FC)
  val LightTextPrimary = Color(0xFF1E293B)
  val CrimsonAlert = Color(0xFFFF3366)
  val TacticalAmber = Color(0xFFFFB800)
  val NeonGreen = Color(0xFF00FF9D)
  val MagentaPulse = Color(0xFFFF007F)
  val PitchBlack = Color(0xFF0A0E14)

  fun panelBg(isLight: Boolean): Color = if (isLight) Color(0xEEF0F4F8) else Color(0xDD0D1117)
  fun panelBgElevated(isLight: Boolean): Color = if (isLight) Color(0xFFFFFFFF) else Color(0xEE161B22)
  fun textPrimary(isLight: Boolean): Color = if (isLight) Color(0xFF1E293B) else Color(0xFFF0F6FC)
  fun textSecondary(isLight: Boolean): Color = if (isLight) Color(0xFF64748B) else Color(0xFF8B949E)
  fun textMuted(isLight: Boolean): Color = if (isLight) Color(0xFF94A3B8) else Color(0xFF484F58)

  val BackgroundPresets: List<Pair<String, Long>> = listOf(
    "افتراضي" to 0xFF0D1117L,
    "أسود نقي" to 0xFF000000L,
    "كحلي عميق" to 0xFF0B192CL,
    "سايبر رمادي" to 0xFF1E1E2EL,
    "أبيض نقي" to 0xFFF8FAFCL
  )

  val BorderPresets: List<Pair<String, Long>> = listOf(
    "سيان ساطع" to 0xFF00F0FFL,
    "أخضر نيون" to 0xFF00FF9DL,
    "كهرماني تكتيكي" to 0xFFFFB800L,
    "قرمزي إنذار" to 0xFFFF3366L,
    "ماجنتا نبضي" to 0xFFFF007FL,
    "رمادي تيتانيوم" to 0xFF8B949EL
  )
}

@Composable
fun JarvisHudBadge(
  accentColor: Color,
  containerColor: Color,
  cutSize: Dp = 8.dp,
  onClick: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit
) {
  val shape = CutCornerShape(
    topStart = cutSize,
    bottomEnd = cutSize,
    topEnd = (cutSize / 3).coerceAtLeast(2.dp),
    bottomStart = (cutSize / 3).coerceAtLeast(2.dp)
  )

  var boxModifier = modifier
    .clip(shape)
    .background(containerColor)
    .border(width = 1.dp, color = accentColor.copy(alpha = 0.65f), shape = shape)

  if (onClick != null) {
    boxModifier = boxModifier.clickable { onClick() }
  }

  Box(
    modifier = boxModifier,
    contentAlignment = Alignment.Center
  ) {
    content()
  }
}

@Composable
fun JarvisHudCard(
  accentColor: Color,
  containerColor: Color,
  cutCornerSize: Dp = 12.dp,
  bracketLength: Dp = 12.dp,
  tabTitle: String? = null,
  tabPosition: HudTabPosition = HudTabPosition.NONE,
  onClick: (() -> Unit)? = null,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit
) {
  val cardShape = CutCornerShape(
    topStart = cutCornerSize,
    bottomEnd = cutCornerSize,
    topEnd = (cutCornerSize / 2).coerceAtLeast(4.dp),
    bottomStart = (cutCornerSize / 2).coerceAtLeast(4.dp)
  )

  var boxModifier = modifier
    .clip(cardShape)
    .background(containerColor)
    .border(width = 1.dp, color = accentColor.copy(alpha = 0.5f), shape = cardShape)

  if (onClick != null) {
    boxModifier = boxModifier.clickable { onClick() }
  }

  Box(modifier = boxModifier) {
    Column {
      if (tabTitle != null && tabPosition != HudTabPosition.NONE) {
        val tabAlignment = when (tabPosition) {
          HudTabPosition.TOP_START, HudTabPosition.BOTTOM_START -> Alignment.Start
          HudTabPosition.TOP_END, HudTabPosition.BOTTOM_END -> Alignment.End
          HudTabPosition.NONE -> Alignment.Start
        }
        Row(
          modifier = Modifier.padding(horizontal = cutCornerSize + 4.dp, vertical = 2.dp)
        ) {
          Box(
            modifier = Modifier
              .clip(CutCornerShape(bottomEnd = 4.dp, topStart = 4.dp))
              .background(accentColor.copy(alpha = 0.16f))
              .border(0.5.dp, accentColor.copy(alpha = 0.5f), CutCornerShape(bottomEnd = 4.dp, topStart = 4.dp))
              .padding(horizontal = 6.dp, vertical = 2.dp)
          ) {
            Text(
              text = tabTitle,
              fontSize = 9.sp,
              fontWeight = FontWeight.Bold,
              fontFamily = FontFamily.Monospace,
              color = accentColor,
              letterSpacing = 1.sp
            )
          }
        }
      }
      content()
    }
  }
}
