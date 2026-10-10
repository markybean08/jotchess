package com.jotchess.recorder.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val NeubrutalistBorderWidth = 3.dp
val NeubrutalistShadowOffset = 4.dp

/**
 * Solid hard-offset shadow with no blur — the neubrutalist drop shadow.
 * Reserve layout room for the offset so siblings don't clip the shadow.
 */
fun Modifier.hardShadow(
    offset: Dp = NeubrutalistShadowOffset,
    color: Color = HardBlack,
    shape: Shape = RectangleShape,
): Modifier = this
    .padding(end = offset, bottom = offset)
    .drawBehind {
        val ox = offset.toPx()
        val oy = offset.toPx()
        drawRect(
            color = color,
            topLeft = Offset(ox, oy),
            size = size,
        )
    }

@Composable
fun NeubrutalistButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = CarbonMint,
    contentColor: Color = HardBlack,
    disabledContainerColor: Color = SurfaceContainer,
    disabledContentColor: Color = TextSecondary,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val fill = if (enabled) containerColor else disabledContainerColor
    val labelColor = if (enabled) contentColor else disabledContentColor
    val pressOffset = if (enabled && pressed) NeubrutalistShadowOffset else 0.dp

    Box(
        modifier = modifier
            .hardShadow()
            .offset(x = pressOffset, y = pressOffset)
            .border(NeubrutalistBorderWidth, HardBlack, RectangleShape)
            .background(fill, RectangleShape)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            color = labelColor,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 0.5.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
fun NeubrutalistCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    containerColor: Color = SurfaceContainer,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .hardShadow()
            .border(NeubrutalistBorderWidth, HardBlack, RectangleShape)
            .background(containerColor, RectangleShape)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun NeubrutalistChip(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = HotSynthPink,
    contentColor: Color = HardBlack,
) {
    Box(
        modifier = modifier
            .border(NeubrutalistBorderWidth, HardBlack, RectangleShape)
            .background(containerColor, RectangleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            color = contentColor,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            letterSpacing = 0.5.sp,
            maxLines = 1,
        )
    }
}
