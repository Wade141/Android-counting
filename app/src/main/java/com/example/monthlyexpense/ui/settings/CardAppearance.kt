package com.example.monthlyexpense.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.monthlyexpense.categories.ColorCodec

internal enum class CardBorderStyle { SOLID }
internal data class CardBorder(val color: Long = 0L, val widthDp: Float = 1f, val style: CardBorderStyle = CardBorderStyle.SOLID)
internal data class CardAppearance(val border: CardBorder = CardBorder(), val transparency: Int = 0) {
    val fillAlpha: Float get() = 1f - transparency.coerceIn(0, 100) / 100f
}
internal enum class CardEditor { BORDER, TRANSPARENCY }
internal val LocalCardAppearance = staticCompositionLocalOf { CardAppearance() }
internal fun parseBorderColor(text: String): Long? = ColorCodec.parse(text.trim())

@Composable
internal fun AppearanceCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val appearance = LocalCardAppearance.current
    val border = when (appearance.border.style) {
        CardBorderStyle.SOLID -> if ((appearance.border.color ushr 24) and 0xFFL == 0L) null
            else BorderStroke(appearance.border.widthDp.dp, Color(appearance.border.color.toInt()))
    }
    Card(
        modifier = if (onClick == null) modifier else modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(cornerRadius), border = border,
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = appearance.fillAlpha),
            contentColor = MaterialTheme.colorScheme.onSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), content = content
    )
}
