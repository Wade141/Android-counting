package com.example.monthlyexpense.ui.settings

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.monthlyexpense.categories.ColorCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val LocalCustomFontColor = staticCompositionLocalOf<Color?> { null }
@Composable internal fun secondaryTextColor(): Color = LocalCustomFontColor.current ?: Color.Gray

internal fun parseFontColor(text: String): Long? =
    if (Regex("#[0-9a-fA-F]{6}").matches(text.trim())) ColorCodec.parse(text.trim()) else null

internal fun fontColorHex(color: Long): String = "#%06X".format(java.util.Locale.ROOT, color and 0xFFFFFFL)

internal class AppearancePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    fun load(): Long? = preferences.getString("font_color", null)?.let(::parseFontColor)
    fun save(color: Long?) {
        val edit = preferences.edit()
        if (color == null) edit.remove("font_color") else edit.putString("font_color", fontColorHex(color))
        check(edit.commit())
    }
    fun loadCards(): CardAppearance = CardAppearance(
        border = CardBorder(preferences.getString("card_border", null)?.let(::parseBorderColor) ?: 0L),
        transparency = preferences.getInt("card_transparency", 0).coerceIn(0, 100) / 10 * 10
    )
    fun saveCards(cards: CardAppearance) {
        check(preferences.edit().putString("card_border", ColorCodec.format(cards.border.color))
            .putInt("card_transparency", cards.transparency.coerceIn(0, 100) / 10 * 10).commit())
    }
}

internal data class AppearanceState(
    val cards: CardAppearance = CardAppearance(),
    val cardEditor: CardEditor? = null,
    val color: Long? = null,
    val busy: Boolean = true,
    val editorOpen: Boolean = false,
    val error: String? = null
)

internal class AppearanceViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppearancePreferences(application)
    private val mutableState = MutableStateFlow(AppearanceState())
    val state = mutableState.asStateFlow()
    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { preferences.load() to preferences.loadCards() }
            mutableState.update { it.copy(color = loaded.first, cards = loaded.second, busy = false) }
        }
    }
    fun openEditor() { if (!state.value.busy) mutableState.update { it.copy(editorOpen = true, error = null) } }
    fun cancel() { if (!state.value.busy) mutableState.update { it.copy(editorOpen = false, cardEditor = null, error = null) } }
    fun openCardEditor(editor: CardEditor) {
        if (!state.value.busy) mutableState.update { it.copy(cardEditor = editor, error = null) }
    }
    fun saveCards(cards: CardAppearance) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO + NonCancellable) { runCatching { preferences.saveCards(cards) }.isSuccess }
            mutableState.update {
                if (success) it.copy(cards = cards, busy = false, cardEditor = null)
                else it.copy(busy = false, error = "卡片外观保存失败，请重试")
            }
        }
    }
    fun save(color: Long?) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO + NonCancellable) { runCatching { preferences.save(color) }.isSuccess }
            mutableState.update {
                if (success) it.copy(color = color, busy = false, editorOpen = false)
                else it.copy(busy = false, error = "颜色保存失败，请重试")
            }
        }
    }
}
