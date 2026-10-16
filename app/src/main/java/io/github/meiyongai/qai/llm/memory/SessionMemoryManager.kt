package io.github.meiyongai.qai.llm.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 描述单条会话历史对话轮次的数据模型。
 *
 * @property userPrompt 用户选中的提问或指令。
 * @property assistantReply 大模型返回并最终发送的回复正文。
 * @property timestamp 产生该对话轮次的 Unix 时间戳（秒）。
 */
data class MemoryTurn(
    val userPrompt: String,
    val assistantReply: String,
    val timestamp: Long = System.currentTimeMillis() / 1000L
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("user", userPrompt)
            put("assistant", assistantReply)
            put("time", timestamp)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): MemoryTurn {
            return MemoryTurn(
                userPrompt = json.optString("user", ""),
                assistantReply = json.optString("assistant", ""),
                timestamp = json.optLong("time", 0L)
            )
        }
    }
}

/**
 * 会话记忆条目数据载体。
 *
 * @property sessionId 会话唯一标识符（如 group_123456 或 friend_789012）。
 * @property sessionTitle 会话显示名称（群名或好友昵称）。
 * @property sessionType 会话类型（GROUP 或 FRIEND）。
 * @property summary 经由大模型智能压缩后的长期记忆摘要。
 * @property turns 当前未被压缩的最近若干轮对话记录。
 * @property updatedAt 最后交互更新时间戳（毫秒）。
 */
data class SessionMemory(
    val sessionId: String,
    val sessionTitle: String,
    val sessionType: String,
    val summary: String,
    val turns: List<MemoryTurn>,
    val updatedAt: Long
)

/**
 * 提供给 UI 设置界面的会话记忆统计摘要实体。
 *
 * @property sessionId 会话唯一标识符。
 * @property sessionTitle 会话显示名称。
 * @property sessionType 会话类型。
 * @property summary 记忆摘要（可能为空）。
 * @property turnCount 未压缩的对话轮数。
 * @property updatedAt 最后更新时间戳。
 */
data class SessionMemorySummaryItem(
    val sessionId: String,
    val sessionTitle: String,
    val sessionType: String,
    val summary: String,
    val turnCount: Int,
    val updatedAt: Long
)

/**
 * 会话聊天数据本地持久化存储管理器。
 *
 * 采用 Android 原生 SQLite 实现轻量级、零第三方侵入的会话级长期记忆数据库。
 * 提供对群聊与私聊独立记忆的存储、检索、上下文提要更新及单项/全局清理能力。
 */
object SessionMemoryManager {
    private const val TAG = "QAI_SessionMemory"
    private const val DB_NAME = "qai_session_memory.db"
    private const val DB_VERSION = 1

    const val SCENE_GROUP = "GROUP"
    const val SCENE_FRIEND = "FRIEND"

    private const val TABLE_NAME = "session_memory"
    private const val COL_SESSION_ID = "session_id"
    private const val COL_TITLE = "session_title"
    private const val COL_TYPE = "session_type"
    private const val COL_SUMMARY = "summary"
    private const val COL_HISTORY_JSON = "history_json"
    private const val COL_UPDATED_AT = "updated_at"

    private class DatabaseHelper(context: Context) :
        SQLiteOpenHelper(context.applicationContext ?: context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            val createSql = """
                CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                    $COL_SESSION_ID TEXT PRIMARY KEY,
                    $COL_TITLE TEXT,
                    $COL_TYPE TEXT,
                    $COL_SUMMARY TEXT,
                    $COL_HISTORY_JSON TEXT,
                    $COL_UPDATED_AT INTEGER
                )
            """.trimIndent()
            db.execSQL(createSql)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
            onCreate(db)
        }
    }

    @Volatile
    private var helperInstance: DatabaseHelper? = null

    private fun getHelper(context: Context): DatabaseHelper {
        return helperInstance ?: synchronized(this) {
            helperInstance ?: DatabaseHelper(context).also { helperInstance = it }
        }
    }

    /**
     * 将一次问答对白追加持久化至指定会话的历史记录中。
     *
     * 若该会话记录已存在，则追加对话轮次并更新最后交互时间；若不存在则自动创建会话记录。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *     sessionId (String): 会话唯一标识符（例如 group_123456 或 friend_789012）。
     *     title (String): 会话名称（群名或好友昵称）。
     *     sceneType (String): 会话类型（"GROUP" 或 "FRIEND"）。
     *     userPrompt (String): 用户发送的提问或被回复的目标文本。
     *     assistantReply (String): 大模型生成的回复内容。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.btnSend.setOnClickListener`
     */
    fun saveTurn(
        context: Context,
        sessionId: String,
        title: String,
        sceneType: String,
        userPrompt: String,
        assistantReply: String
    ) {
        if (sessionId.isBlank() || userPrompt.isBlank() || assistantReply.isBlank()) return

        val existing = getSession(context, sessionId)
        val turns = existing?.turns?.toMutableList() ?: mutableListOf()
        turns.add(MemoryTurn(userPrompt = userPrompt, assistantReply = assistantReply))

        val jsonArray = JSONArray()
        for (turn in turns) {
            jsonArray.put(turn.toJson())
        }

        val values = ContentValues().apply {
            put(COL_SESSION_ID, sessionId)
            put(COL_TITLE, title.ifBlank { existing?.sessionTitle ?: sessionId })
            put(COL_TYPE, sceneType)
            put(COL_SUMMARY, existing?.summary ?: "")
            put(COL_HISTORY_JSON, jsonArray.toString())
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }

        val db = getHelper(context).writableDatabase
        db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        Log.i(TAG, "Saved turn for session $sessionId, current turns count=${turns.size}")
    }

    /**
     * 获取指定会话的完整记忆记录（含长期提要与所有未压缩轮次）。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *     sessionId (String): 目标会话标识符。
     *
     * Returns:
     *     SessionMemory?: 查找到的会话数据模型，不存在时返回 null。
     *
     * Callers:
     *     - `SessionMemoryManager.kt:SessionMemoryManager.saveTurn`
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.buildDialogView`
     *     - `ContextCompressor.kt:ContextCompressor.triggerCompressionIfNeeded`
     */
    fun getSession(context: Context, sessionId: String): SessionMemory? {
        if (sessionId.isBlank()) return null
        val db = getHelper(context).readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            arrayOf(COL_SESSION_ID, COL_TITLE, COL_TYPE, COL_SUMMARY, COL_HISTORY_JSON, COL_UPDATED_AT),
            "$COL_SESSION_ID = ?",
            arrayOf(sessionId),
            null,
            null,
            null
        )

        cursor.use {
            if (it.moveToFirst()) {
                val title = it.getString(it.getColumnIndexOrThrow(COL_TITLE)) ?: ""
                val type = it.getString(it.getColumnIndexOrThrow(COL_TYPE)) ?: "FRIEND"
                val summary = it.getString(it.getColumnIndexOrThrow(COL_SUMMARY)) ?: ""
                val historyJsonStr = it.getString(it.getColumnIndexOrThrow(COL_HISTORY_JSON)) ?: "[]"
                val updatedAt = it.getLong(it.getColumnIndexOrThrow(COL_UPDATED_AT))

                val turns = mutableListOf<MemoryTurn>()
                val array = JSONArray(historyJsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i)
                    if (obj != null) {
                        turns.add(MemoryTurn.fromJson(obj))
                    }
                }

                return SessionMemory(
                    sessionId = sessionId,
                    sessionTitle = title,
                    sessionType = type,
                    summary = summary,
                    turns = turns,
                    updatedAt = updatedAt
                )
            }
        }
        return null
    }

    /**
     * 更新指定会话的长期记忆摘要并更新保留的未压缩历史轮次。
     *
     * 当智能压缩器提炼出旧历史概要后调用此接口完成数据替换。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *     sessionId (String): 目标会话标识符。
     *     summary (String): 提炼浓缩出的长期事实提要。
     *     retainedTurns (List<MemoryTurn>): 压缩后保留的最新对话轮次列表。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `ContextCompressor.kt:ContextCompressor.triggerCompressionIfNeeded`
     */
    fun updateSummary(
        context: Context,
        sessionId: String,
        summary: String,
        retainedTurns: List<MemoryTurn>
    ) {
        val jsonArray = JSONArray()
        for (turn in retainedTurns) {
            jsonArray.put(turn.toJson())
        }

        val values = ContentValues().apply {
            put(COL_SUMMARY, summary)
            put(COL_HISTORY_JSON, jsonArray.toString())
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }

        val db = getHelper(context).writableDatabase
        db.update(TABLE_NAME, values, "$COL_SESSION_ID = ?", arrayOf(sessionId))
        Log.i(TAG, "Updated summary for session $sessionId, retained turns=${retainedTurns.size}")
    }

    /**
     * 获取当前本地数据库中记录的所有会话摘要列表。
     *
     * 供设置管理界面展示会话清单、条数及最后更新时间。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *
     * Returns:
     *     List<SessionMemorySummaryItem>: 所有已保存的会话摘要列表（按更新时间降序排列）。
     *
     * Callers:
     *     - `AiSettingsActivity.kt:AiSettingsActivity.showSessionManagerDialog`
     */
    fun getAllSessions(context: Context): List<SessionMemorySummaryItem> {
        val result = mutableListOf<SessionMemorySummaryItem>()
        val db = getHelper(context).readableDatabase
        val cursor = db.query(
            TABLE_NAME,
            arrayOf(COL_SESSION_ID, COL_TITLE, COL_TYPE, COL_SUMMARY, COL_HISTORY_JSON, COL_UPDATED_AT),
            null,
            null,
            null,
            null,
            "$COL_UPDATED_AT DESC"
        )

        cursor.use {
            while (it.moveToNext()) {
                val sId = it.getString(it.getColumnIndexOrThrow(COL_SESSION_ID))
                val title = it.getString(it.getColumnIndexOrThrow(COL_TITLE)) ?: sId
                val type = it.getString(it.getColumnIndexOrThrow(COL_TYPE)) ?: "FRIEND"
                val summary = it.getString(it.getColumnIndexOrThrow(COL_SUMMARY)) ?: ""
                val historyJsonStr = it.getString(it.getColumnIndexOrThrow(COL_HISTORY_JSON)) ?: "[]"
                val updatedAt = it.getLong(it.getColumnIndexOrThrow(COL_UPDATED_AT))

                val array = JSONArray(historyJsonStr)
                result.add(
                    SessionMemorySummaryItem(
                        sessionId = sId,
                        sessionTitle = title,
                        sessionType = type,
                        summary = summary,
                        turnCount = array.length(),
                        updatedAt = updatedAt
                    )
                )
            }
        }
        return result
    }

    /**
     * 删除指定会话的全部记忆存储记录。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *     sessionId (String): 待删除的目标会话 ID。
     *
     * Returns:
     *     Boolean: true 为删除成功，false 为未命中或删除失败。
     *
     * Callers:
     *     - `AiSettingsActivity.kt:AiSettingsActivity.showSessionManagerDialog`
     */
    fun deleteSession(context: Context, sessionId: String): Boolean {
        if (sessionId.isBlank()) return false
        val db = getHelper(context).writableDatabase
        val rows = db.delete(TABLE_NAME, "$COL_SESSION_ID = ?", arrayOf(sessionId))
        Log.i(TAG, "Deleted session $sessionId, affected rows=$rows")
        return rows > 0
    }

    /**
     * 一键清空数据库中的所有会话记忆。
     *
     * Args:
     *     context (Context): Android 上下文对象。
     *
     * Returns:
     *     Boolean: true 为清空成功。
     *
     * Callers:
     *     - `AiSettingsActivity.kt:AiSettingsActivity.showSessionManagerDialog`
     */
    fun clearAllSessions(context: Context): Boolean {
        val db = getHelper(context).writableDatabase
        db.delete(TABLE_NAME, null, null)
        Log.i(TAG, "Cleared all sessions memory in database")
        return true
    }
}
