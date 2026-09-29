package com.cashup.qris

import com.google.gson.Gson
import com.google.gson.JsonParseException

internal class ParsedPush(
    val version: Int?,
    val type: String?,
    val module: String?,
    val transactionId: String?,
    val detail: QrisPushDetail?,
)

private class PushEnvelope(
    val v: Int? = null,
    val type: String? = null,
    val module: String? = null,
    val data: PushData? = null,
)

private class PushData(
    val transactionId: String? = null,
    val detail: QrisPushDetail? = null,
)

/** Payload rusak / bukan objek JSON -> `null` (dilewati), tidak pernah melempar. */
internal class PushParser(private val gson: Gson = Gson()) {
    fun parse(payload: String): ParsedPush? {
        val envelope = try {
            gson.fromJson(payload, PushEnvelope::class.java)
        } catch (e: JsonParseException) {
            null
        } ?: return null
        return ParsedPush(envelope.v, envelope.type, envelope.module, envelope.data?.transactionId, envelope.data?.detail)
    }
}
