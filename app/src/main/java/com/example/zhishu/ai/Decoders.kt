package com.example.zhishu.ai

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

fun <T> jsonDecoder(serializer: KSerializer<T>): JsonDecoder<T> = object : JsonDecoder<T> {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    override fun decode(text: String): T = json.decodeFromString(serializer, text)
}
