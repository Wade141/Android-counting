package com.example.monthlyexpense.notification

data class RawNotification(
    val packageName: String,
    val title: String,
    val text: String,
    val bigText: String,
    val textLines: List<String>,
    val postTime: Long,
    val notificationKey: String,
    val channelId: String?,
    val subText: String = "",
    val isGroupSummary: Boolean = false,
    val groupKey: String? = null
) {
    fun contentParts(): List<String> = buildList {
        add(title)
        add(text)
        add(bigText)
        addAll(textLines)
        add(subText)
    }.map { it.normalizeWhitespace() }.filter(String::isNotEmpty).distinct()

    fun combinedContent(): String = contentParts().joinToString("\n")

    private fun String.normalizeWhitespace(): String = trim()
        .replace(UNICODE_WHITESPACE, " ")

    private companion object {
        val UNICODE_WHITESPACE = Regex("[\\s\\u00a0\\u2007\\u202f\\u3000]+")
    }
}
