package io.github.meiyongai.qai.hook

import java.util.regex.Pattern

/**
 * QQ 文本消息元素解析器。
 *
 * 负责将包含 @ 成员语法及协议标记的文本切片解析为结构化的消息单元（纯文本与 @ 成员），
 * 并提供输入框文本的标准化转义支持。
 */
object QQRichTextParser {

    /**
     * 解析后的消息元素单元基类。
     */
    sealed class RichSegment {
        /**
         * 纯文本元素。
         *
         * @property text 文本内容。
         */
        data class Text(val text: String) : RichSegment()

        /**
         * @ 成员强提醒元素。
         *
         * @property atType 艾特类型：1 为全体成员，2 为指定成员。
         * @property uid 目标用户的字符串 UID。
         * @property uin 目标用户的数字 QQ 号。
         * @property displayContent 界面渲染展示的文本（例如 "@张三 "）。
         */
        data class At(
            val atType: Int,
            val uid: String,
            val uin: Long,
            val displayContent: String
        ) : RichSegment()
    }

    /**
     * 将输入的文本解析为包含纯文本与 @ 成员的有序元素切片列表。
     *
     * Args:
     *     rawText (String): 待解析的原始纯文本。
     *     targetUid (String?): 当前选中的目标成员 UID（可选）。
     *     targetUin (Long?): 当前选中的目标成员 QQ 号（可选）。
     *     targetNick (String?): 当前选中的目标成员展示昵称（可选）。
     *
     * Returns:
     *     List<RichSegment>: 解析得到的有序元素切片列表。
     *
     * Callers:
     *     - `AioInputHelper.kt:AioInputHelper.sendDirectMessage`
     */
    fun parseRichText(
        rawText: String,
        targetUid: String? = null,
        targetUin: Long? = null,
        targetNick: String? = null
    ): List<RichSegment> {
        if (rawText.isEmpty()) return emptyList()

        val tokenPatterns = mutableListOf<String>()

        // 1. CQ 码 @: [CQ:at,qq=123456], [CQ:at,uin=123456], [CQ:at,uid=u_xxx], [CQ:at,all=true]
        tokenPatterns.add("""\[CQ:at,(?:qq|uin)=(\d+)\]""")
        tokenPatterns.add("""\[CQ:at,uid=([a-zA-Z0-9_\-]+)\]""")
        tokenPatterns.add("""\[CQ:at,(?:all=true|type=all)\]""")

        // 2. 全体成员 @ 文本
        tokenPatterns.add("""@全体成员(?:\s+|$)""")

        // 3. 目标昵称与代词 @ 文本
        val cleanNick = targetNick?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanNick != null) {
            tokenPatterns.add("""@${Pattern.quote(cleanNick)}(?:\s+|$)""")
        }
        tokenPatterns.add("""@(?:对方|他|她)(?:\s+|$)""")

        val combinedRegex = Pattern.compile(tokenPatterns.joinToString("|"))
        val matcher = combinedRegex.matcher(rawText)

        val result = mutableListOf<RichSegment>()
        var lastEnd = 0

        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()

            if (start > lastEnd) {
                val plainText = rawText.substring(lastEnd, start)
                addTextSegment(result, plainText)
            }

            val matchedStr = matcher.group()
            val segment = resolveAtSegment(
                matchedStr = matchedStr,
                targetUid = targetUid,
                targetUin = targetUin,
                targetNick = cleanNick
            )

            if (segment != null) {
                result.add(segment)
            } else {
                addTextSegment(result, matchedStr)
            }

            lastEnd = end
        }

        if (lastEnd < rawText.length) {
            val remainingText = rawText.substring(lastEnd)
            addTextSegment(result, remainingText)
        }

        return result
    }

    /**
     * 将命中的 @ 语法片段解析为 RichSegment.At 实例。
     *
     * Args:
     *     matchedStr (String): 正则命中的字符串片段。
     *     targetUid (String?): 目标成员 UID。
     *     targetUin (Long?): 目标成员 QQ 号。
     *     targetNick (String?): 目标成员昵称。
     *
     * Returns:
     *     RichSegment.At?: 解析成功返回对应的 At 实例，否则返回 null。
     *
     * Callers:
     *     - `QQRichTextParser.kt:QQRichTextParser.parseRichText`
     */
    private fun resolveAtSegment(
        matchedStr: String,
        targetUid: String?,
        targetUin: Long?,
        targetNick: String?
    ): RichSegment.At? {
        if (matchedStr.startsWith("[CQ:at,all=true]") || matchedStr.startsWith("[CQ:at,type=all]")) {
            return RichSegment.At(atType = 1, uid = "", uin = 0L, displayContent = "@全体成员 ")
        }
        if (matchedStr.startsWith("[CQ:at,qq=") || matchedStr.startsWith("[CQ:at,uin=")) {
            val prefix = if (matchedStr.startsWith("[CQ:at,qq=")) "[CQ:at,qq=" else "[CQ:at,uin="
            val uinStr = matchedStr.removePrefix(prefix).removeSuffix("]")
            val uin = uinStr.toLongOrNull() ?: 0L
            val display = if (uin > 0L) "@$uin " else "@用户 "
            return RichSegment.At(atType = 2, uid = "", uin = uin, displayContent = display)
        }
        if (matchedStr.startsWith("[CQ:at,uid=")) {
            val uid = matchedStr.removePrefix("[CQ:at,uid=").removeSuffix("]")
            return RichSegment.At(atType = 2, uid = uid, uin = 0L, displayContent = "@用户 ")
        }
        if (matchedStr.startsWith("@全体成员")) {
            return RichSegment.At(atType = 1, uid = "", uin = 0L, displayContent = "@全体成员 ")
        }
        if (matchedStr.startsWith("@")) {
            val effectiveNick = targetNick ?: "对方"
            val effectiveUid = targetUid.orEmpty()
            val effectiveUin = targetUin ?: 0L
            if (effectiveUid.isNotEmpty() || effectiveUin > 0L) {
                return RichSegment.At(
                    atType = 2,
                    uid = effectiveUid,
                    uin = effectiveUin,
                    displayContent = "@$effectiveNick "
                )
            }
        }
        return null
    }

    /**
     * 向段落列表中追加纯文本，并在相邻时自动合并以精简元素数量。
     *
     * Args:
     *     list (MutableList<RichSegment>): 目标元素列表。
     *     text (String): 待追加的纯文本。
     *
     * Callers:
     *     - `QQRichTextParser.kt:QQRichTextParser.parseRichText`
     */
    private fun addTextSegment(list: MutableList<RichSegment>, text: String) {
        if (text.isEmpty()) return
        val last = list.lastOrNull()
        if (last is RichSegment.Text) {
            list[list.size - 1] = RichSegment.Text(last.text + text)
        } else {
            list.add(RichSegment.Text(text))
        }
    }

    /**
     * 将包含特殊标记的文本标准化为适合输入框展示的普通文本。
     *
     * Args:
     *     rawText (String): 原始文本。
     *     targetNick (String?): 目标成员展示昵称。
     *
     * Returns:
     *     String: 规范化后的输入框展示文本。
     *
     * Callers:
     *     - `AioInputHelper.kt:AioInputHelper.insertTextToInput`
     *     - `AioInputHelper.kt:AioInputHelper.sendText`
     */
    fun normalizeForInputBox(rawText: String, targetNick: String? = null): String {
        var result = rawText
        result = result.replace(Regex("""\[CQ:at,(?:all=true|type=all)\]"""), "@全体成员 ")
        val effectiveNick = targetNick?.trim()?.takeIf { it.isNotEmpty() } ?: "对方"
        result = result.replace(Regex("""\[CQ:at,(?:qq|uin)=\d+\]"""), "@$effectiveNick ")
        result = result.replace(Regex("""\[CQ:at,uid=[a-zA-Z0-9_\-]+\]"""), "@$effectiveNick ")
        return result
    }
}
