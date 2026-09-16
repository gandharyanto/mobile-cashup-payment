package com.cashup.common.network

/**
 * Amplop respons backend: `{ "data": ..., "error": ..., "meta": ... }`.
 *
 * Selalu bercabang pada [EnvelopeError.code], **tidak pernah** pada
 * [EnvelopeError.message] — `message` berbahasa Indonesia, ditujukan untuk
 * manusia, dan boleh berubah kapan saja.
 */
data class ApiEnvelope<T>(
    val data: T? = null,
    val error: EnvelopeError? = null,
    val meta: EnvelopeMeta? = null,
)

data class EnvelopeError(
    val code: String,
    val message: String,
)

data class EnvelopeMeta(
    val correlationId: String? = null,
)