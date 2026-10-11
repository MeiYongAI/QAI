package io.github.meiyongai.qai.llm.utils

object UrlNormalizer {
    fun normalizeBaseUrl(baseUrl: String?): String {
        if (baseUrl.isNullOrBlank()) return ""
        var normalized = baseUrl.trim()
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length - 1)
        }
        return normalized
    }
}
