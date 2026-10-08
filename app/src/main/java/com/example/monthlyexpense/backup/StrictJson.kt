package com.example.monthlyexpense.backup

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader

internal object StrictJson {
    private val integerPattern = Regex("-?(?:0|[1-9]\\d*)")

    fun parseObject(json: String): JSONObject = JsonReader(StringReader(json)).use { reader ->
        reader.isLenient = false
        val value = readValue(reader)
        if (value !is JSONObject || reader.peek() != JsonToken.END_DOCUMENT) {
            throw InvalidExpenseBackupException("Backup must contain exactly one JSON object")
        }
        value
    }

    private fun readValue(reader: JsonReader): Any = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> readObject(reader)
        JsonToken.BEGIN_ARRAY -> readArray(reader)
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> reader.nextString().let { raw ->
            if (!integerPattern.matches(raw)) {
                throw InvalidExpenseBackupException("Backup numbers must be integers")
            }
            raw.toLongOrNull()
                ?: throw InvalidExpenseBackupException("Backup integer is outside supported range")
        }
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> reader.nextNull().let { JSONObject.NULL }
        else -> throw InvalidExpenseBackupException("Unexpected JSON token")
    }

    private fun readObject(reader: JsonReader): JSONObject {
        val result = JSONObject()
        val keys = mutableSetOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (!keys.add(key)) throw InvalidExpenseBackupException("Duplicate JSON field: $key")
            result.put(key, readValue(reader))
        }
        reader.endObject()
        return result
    }

    private fun readArray(reader: JsonReader): JSONArray {
        val result = JSONArray()
        reader.beginArray()
        while (reader.hasNext()) result.put(readValue(reader))
        reader.endArray()
        return result
    }
}
