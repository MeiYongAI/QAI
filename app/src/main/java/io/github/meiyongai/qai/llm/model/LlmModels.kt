package io.github.meiyongai.qai.llm.model

data class LlmResponse<T>(
    val data: T?,
    val error: String?,
    val durationMs: Long,
    val httpCode: Int
) {
    val isSuccess: Boolean get() = error == null && data != null
}

data class Credentials(
    val baseUrl: String,
    val apiKey: String,
    val error: String? = null
) {
    val isInvalid: Boolean get() = error != null
}
