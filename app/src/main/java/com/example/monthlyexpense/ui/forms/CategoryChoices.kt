package com.example.monthlyexpense.ui.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.example.monthlyexpense.ExpenseCategory

@Composable
internal fun CategoryChoices(
    categories: List<ExpenseCategory>,
    selectedKey: String,
    onSelect: (String) -> Unit
) {
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        categories.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { category ->
                    if (category.key == selectedKey) {
                        Button(
                            onClick = { onSelect(category.key) },
                            modifier = Modifier.weight(1f)
                                .testTag("category-${category.key}")
                                .semantics { selected = true }
                        ) { Text(category.name, maxLines = 1) }
                    } else {
                        OutlinedButton(
                            onClick = { onSelect(category.key) },
                            modifier = Modifier.weight(1f)
                                .testTag("category-${category.key}")
                                .semantics { selected = false }
                        ) { Text(category.name, maxLines = 1) }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
