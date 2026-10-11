package io.github.meiyongai.qai.llm.net

import io.github.meiyongai.qai.llm.model.Credentials
import io.github.meiyongai.qai.llm.model.LlmResponse
import io.github.meiyongai.qai.llm.utils.ModelUtil
import io.github.meiyongai.qai.llm.utils.UrlNormalizer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashSet
import java.util.concurrent.TimeUnit

object OpenAICompatClient {
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val testClient = httpClient.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    fun prepareCredentials(baseUrl: String?, apiKey: String?): Credentials {
        val cleanUrl = UrlNormalizer.normalizeBaseUrl(baseUrl)
        if (cleanUrl.isBlank()) {
            return Credentials("", "", "API Base URL 不能为空")
        }
        val cleanKey = apiKey?.trim() ?: ""
        if (cleanKey.isBlank()) {
            return Credentials(cleanUrl, "", "API Key 未设置")
        }
        return Credentials(cleanUrl, cleanKey, null)
    }

    fun fetchModels(baseUrl: String, apiKey: String): LlmResponse<List<String>> {
        val credentials = prepareCredentials(baseUrl, apiKey)
        if (credentials.isInvalid) {
            return LlmResponse(null, credentials.error, 0, 0)
        }
        val start = System.currentTimeMillis()
        val request = Request.Builder()
            .url("${credentials.baseUrl}/models")
            .header("Authorization", "Bearer ${credentials.apiKey}")
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                val duration = System.currentTimeMillis() - start
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return LlmResponse(null, "HTTP ${response.code}: $body", duration, response.code)
                }
                val models = parseModelIds(body)
                val filtered = models.filter { ModelUtil.isTextGenerationModel(it) }
                LlmResponse(filtered, null, duration, response.code)
            }
        } catch (e: Exception) {
            LlmResponse(null, e.localizedMessage ?: e.toString(), System.currentTimeMillis() - start, 0)
        }
    }

    fun testChatCompletions(baseUrl: String, apiKey: String, model: String): LlmResponse<String> {
        val credentials = prepareCredentials(baseUrl, apiKey)
        if (credentials.isInvalid) {
            return LlmResponse(null, credentials.error, 0, 0)
        }
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "user").put("content", "Hello! Please reply 'OK'."))
        }
        return chatCompletionsInternal(credentials, model, messages, 0.1f, testClient)
    }

    fun chatCompletions(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: JSONArray,
        temperature: Float
    ): LlmResponse<String> {
        val credentials = prepareCredentials(baseUrl, apiKey)
        if (credentials.isInvalid) {
            return LlmResponse(null, credentials.error, 0, 0)
        }
        return chatCompletionsInternal(credentials, model, messages, temperature, httpClient)
    }

    private fun chatCompletionsInternal(
        credentials: Credentials,
        model: String,
        messages: JSONArray,
        temperature: Float,
        client: OkHttpClient
    ): LlmResponse<String> {
        val start = System.currentTimeMillis()
        val requestJson = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            if (ModelUtil.supportsTemperature(model)) {
                put("temperature", temperature)
            }
            ModelUtil.applyReasoningParameters(this, credentials.baseUrl, model)
        }

        val request = Request.Builder()
            .url("${credentials.baseUrl}/chat/completions")
            .header("Authorization", "Bearer ${credentials.apiKey}")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val duration = System.currentTimeMillis() - start
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    return LlmResponse(null, "HTTP ${response.code}: $body", duration, response.code)
                }
                val content = parseFirstChoiceContent(body)
                if (content.isNullOrBlank()) {
                    LlmResponse(null, "返回内容为空: $body", duration, response.code)
                } else {
                    LlmResponse(content, null, duration, response.code)
                }
            }
        } catch (e: Exception) {
            LlmResponse(null, e.localizedMessage ?: e.toString(), System.currentTimeMillis() - start, 0)
        }
    }

    private fun parseFirstChoiceContent(body: String): String? {
        return try {
            val json = JSONObject(body)
            val choices = json.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val first = choices.getJSONObject(0)
            val message = first.optJSONObject("message") ?: return null
            if (message.has("content")) message.optString("content") else null
        } catch (e: Exception) {
            null
        }
    }

    private fun parseModelIds(body: String): List<String> {
        val out = LinkedHashSet<String>()
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()

        try {
            if (trimmed.startsWith("[")) {
                val array = JSONArray(trimmed)
                extractModelIds(array, out)
            } else {
                val json = JSONObject(trimmed)
                if (json.has("data") && json.get("data") is JSONArray) {
                    extractModelIds(json.getJSONArray("data"), out)
                } else if (json.has("models") && json.get("models") is JSONArray) {
                    extractModelIds(json.getJSONArray("models"), out)
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return out.toList()
    }

    private fun extractModelIds(array: JSONArray, out: LinkedHashSet<String>) {
        for (i in 0 until array.length()) {
            val item = array.opt(i)
            if (item is JSONObject) {
                val id = item.optString("id", "").trim()
                if (id.isNotEmpty()) out.add(id)
            } else if (item is String) {
                val id = item.trim()
                if (id.isNotEmpty()) out.add(id)
            }
        }
    }
}
