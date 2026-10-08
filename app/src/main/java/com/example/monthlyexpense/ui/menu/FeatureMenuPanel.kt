package com.example.monthlyexpense.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PageColor = Color(0xFFFFF9F2)

internal data class FeatureMenuItem(
    val title: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

@Composable
internal fun FeatureMenuPanel(items: List<FeatureMenuItem>, onClose: () -> Unit) {
    Surface(Modifier.fillMaxHeight().fillMaxWidth(.78f), color = PageColor, shadowElevation = 18.dp) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("功能菜单", Modifier.weight(1f), fontSize = 25.sp, fontWeight = FontWeight.Black)
                TextButton(onClick = onClose) { Text("关闭") }
            }
            items.forEach { item ->
                OutlinedButton(
                    onClick = item.onClick,
                    enabled = item.enabled,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    Text(item.title, Modifier.fillMaxWidth(), fontSize = 17.sp)
                }
            }
        }
    }
}
