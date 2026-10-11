package io.github.meiyongai.qai.llm.model

import org.json.JSONObject

data class Role(
    var id: String,
    var name: String,
    var prompt: String,
    var isBuiltIn: Boolean = false
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("prompt", prompt)
            put("isBuiltIn", isBuiltIn)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): Role {
            return Role(
                id = json.optString("id", System.currentTimeMillis().toString()),
                name = json.optString("name", "未命名角色"),
                prompt = json.optString("prompt", ""),
                isBuiltIn = json.optBoolean("isBuiltIn", false)
            )
        }
    }
}
