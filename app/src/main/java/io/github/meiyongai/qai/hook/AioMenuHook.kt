package io.github.meiyongai.qai.hook

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import io.github.libxposed.api.XposedInterface
import io.github.meiyongai.qai.llm.config.LlmConfig
import io.github.meiyongai.qai.ui.AiReplyBottomSheetDialog
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Modifier
import java.util.Locale
import org.json.JSONException
import org.json.JSONObject

/**
 * 描述单张多模态图片的上下文归属信息。
 *
 * @property tag 文本中对应的锚定标签（例如 [图片 #1]）。
 * @property imagePath 本地图片文件的绝对路径。
 * @property sender 发送该图片的群友或好友昵称。
 * @property timeStr 发送时间戳字符串（例如 21:05）。
 * @property isTargetMsg 是否来源于当前长按的目标消息本身。
 */
data class ImageOriginInfo(
    val tag: String,
    val imagePath: String,
    val sender: String,
    val timeStr: String,
    val isTargetMsg: Boolean = false
)

data class MessagePayload(
    val text: String,
    val imagePaths: List<String>,
    val sessionId: String = "",
    val sessionTitle: String = "",
    val sessionType: String = "FRIEND",
    val imageInfos: List<ImageOriginInfo> = emptyList()
)

private data class MessageClassifier(
    val hasText: Boolean,
    val hasPic: Boolean,
    val hasEmoji: Boolean,
    val hasCard: Boolean,
    val hasVoice: Boolean,
    val isOnlyEmoji: Boolean
)

object AioMenuHook {
    private const val TAG = "QAI_Menu"
    private const val MENU_ID_AI = 0x7F0B8888

    fun init(xposed: XposedInterface, classLoader: ClassLoader) {
        val baseComponentClass = runCatching {
            classLoader.loadClass("com.tencent.mobileqq.aio.msglist.holder.component.BaseContentComponent")
        }.getOrNull() ?: run {
            Log.e(TAG, "BaseContentComponent not found")
            return
        }

        val msgItemClass = runCatching {
            classLoader.loadClass("com.tencent.mobileqq.aio.msg.AIOMsgItem")
        }.getOrNull() ?: run {
            Log.e(TAG, "AIOMsgItem not found")
            return
        }

        val getMsgMethod = baseComponentClass.declaredMethods.firstOrNull {
            it.returnType == msgItemClass && it.parameterTypes.isEmpty()
        }?.apply { isAccessible = true } ?: run {
            Log.e(TAG, "getMsgMethod not found")
            return
        }

        val menuListMethod = baseComponentClass.declaredMethods.firstOrNull {
            Modifier.isAbstract(it.modifiers) &&
                    MutableList::class.java.isAssignableFrom(it.returnType) &&
                    it.parameterTypes.isEmpty()
        } ?: run {
            Log.e(TAG, "menuListMethod not found")
            return
        }

        val ctors = baseComponentClass.declaredConstructors
        val hookedClasses = mutableSetOf<Class<*>>()

        for (ctor in ctors) {
            xposed.hook(ctor).intercept { chain ->
                val result = chain.proceed()
                val targetObj = chain.thisObject
                if (targetObj != null) {
                    val componentClass = targetObj.javaClass
                    if (hookedClasses.add(componentClass)) {
                        val subMenuListMethod = runCatching {
                            componentClass.getMethod(menuListMethod.name)
                        }.getOrNull()
                        if (subMenuListMethod != null) {
                            xposed.hook(subMenuListMethod).intercept { menuChain ->
                                @Suppress("UNCHECKED_CAST")
                                val originalList = menuChain.proceed() as? MutableList<Any>
                                if (originalList != null && originalList.isNotEmpty()) {
                                    // 严格去重：如果当前菜单列表里已经有 QAI 或 询问 AI，坚决不重复添加
                                    val alreadyHasQai = originalList.any { item ->
                                        val t = runCatching {
                                            item.javaClass.methods.firstOrNull { 
                                                it.returnType == String::class.java && it.parameterTypes.isEmpty() 
                                            }?.invoke(item) as? String
                                        }.getOrNull()
                                        t == "QAI" || t == "询问 AI"
                                    }
                                    if (alreadyHasQai) {
                                        return@intercept originalList
                                    }

                                    val component = menuChain.thisObject ?: return@intercept originalList
                                    val msgItem = getMsgMethod.invoke(component)
                                    val activity = AioInputHelper.getCurrentActivity()

                                    if (msgItem != null && activity != null) {
                                        try {
                                            val sampleItem = originalList[0]
                                            val absMenuItemClass = sampleItem.javaClass.superclass ?: sampleItem.javaClass
                                            val replyItem = originalList.firstOrNull { item ->
                                                val title = runCatching {
                                                    val hMethod = item.javaClass.methods.firstOrNull { 
                                                        it.name == "h" && it.returnType == String::class.java && it.parameterTypes.isEmpty() 
                                                    }
                                                    hMethod?.invoke(item) as? String
                                                }.getOrNull() ?: runCatching {
                                                    val methods = (item.javaClass.methods + item.javaClass.declaredMethods).filter {
                                                        it.returnType == String::class.java && it.parameterTypes.isEmpty() && it.name != "toString"
                                                    }
                                                    methods.firstNotNullOfOrNull { m ->
                                                        m.isAccessible = true
                                                        m.invoke(item) as? String
                                                    }
                                                }.getOrNull() ?: item.toString()

                                                title == "回复" || title == "引用" || title.contains("title='回复'") || title.contains("title='引用'")
                                            }

                                            val quoteAction: (() -> Unit)? = if (replyItem != null) {
                                                {
                                                    try {
                                                        val clickMethod = absMenuItemClass.methods.firstOrNull {
                                                            it.returnType == java.lang.Void.TYPE && it.parameterTypes.isEmpty()
                                                        } ?: absMenuItemClass.declaredMethods.firstOrNull {
                                                            it.returnType == java.lang.Void.TYPE && it.parameterTypes.isEmpty()
                                                        }
                                                        if (clickMethod != null) {
                                                            clickMethod.isAccessible = true
                                                            clickMethod.invoke(replyItem)
                                                            Log.i(TAG, "Successfully invoked reply item click method: ${clickMethod.name}")
                                                        }
                                                    } catch (e: Throwable) {
                                                        Log.e(TAG, "Failed to invoke reply item action", e)
                                                    }
                                                }
                                            } else {
                                                Log.w(TAG, "Reply item not found in originalList of size ${originalList.size}")
                                                null
                                            }

                                            val aiItem = CustomMenuHelper.createMenuItem(
                                                context = activity,
                                                absMenuItemClass = absMenuItemClass,
                                                msgItemClass = msgItemClass,
                                                msgItem = msgItem,
                                                title = "QAI",
                                                menuId = MENU_ID_AI
                                            ) {
                                                val payload = extractMessagePayload(activity, component, msgItem)
                                                if (payload.text.isBlank() && payload.imagePaths.isEmpty()) {
                                                    activity.runOnUiThread {
                                                        Toast.makeText(activity, "未获取到该消息的文本或图片内容", Toast.LENGTH_SHORT).show()
                                                    }
                                                } else {
                                                    activity.runOnUiThread {
                                                        val contact = extractContact(msgItem)
                                                        AiReplyBottomSheetDialog(
                                                            activity = activity,
                                                            originPrompt = payload.text,
                                                            imagePaths = payload.imagePaths,
                                                            quoteAction = quoteAction,
                                                            contact = contact,
                                                            targetMsgItem = msgItem,
                                                            sessionId = payload.sessionId,
                                                            sessionTitle = payload.sessionTitle,
                                                            sessionType = payload.sessionType
                                                        ).show()
                                                    }
                                                }
                                            }

                                            val newList = ArrayList<Any>(originalList.size + 1)
                                            newList.add(aiItem)
                                            newList.addAll(originalList)
                                            Log.i(TAG, "Successfully injected single QAI menu item")
                                            return@intercept newList
                                        } catch (e: Throwable) {
                                            Log.e(TAG, "Failed to create or inject custom menu item", e)
                                        }
                                    } else {
                                        Log.w(TAG, "Cannot inject menu: msgItem=$msgItem, activity=$activity")
                                    }
                                }
                                originalList
                            }
                        }
                    }
                }
                result
            }
        }
    }

    /**
     * 从消息项对象及关联组件视图中提取待处理的消息有效载荷（包含纯文本与本地图片路径）。
     *
     * 深入遍历消息记录中的所有消息元素（MsgElement），提取文字、语音转换文本；
     * 优先解析图片元素中的本地磁盘路径，若缺失则穿透组件视图树直接提取屏幕正在渲染的位图；
     * 若用户在设置中启用了上下文条数，则向前回溯指定条数且未被排除的消息并结构化拼装为带有语境的提示词。
     *
     * Args:
     *     context (Context): Android 上下文环境。
     *     component (Any?): 长按触发的内容组件对象（BaseContentComponent）。
     *     msgItem (Any): QQ 内部的 AIOMsgItem 实例。
     *
     * Returns:
     *     MessagePayload: 包含提炼后文本内容与本地图片绝对路径的数据对象。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.init`
     */
    private fun extractMessagePayload(context: Context, component: Any?, msgItem: Any): MessagePayload {
        val msgRecord = extractMsgRecord(msgItem)
        var hasRealPicElement = false
        var hasElements = false

        if (msgRecord != null) {
            val elements = getFieldValueRecursive(msgRecord, "elements") as? List<*>
            Log.d(TAG, "msgRecord: ${msgRecord.javaClass.name}, elements.size=${elements?.size}")
            if (!elements.isNullOrEmpty()) {
                hasElements = true
                for (element in elements) {
                    if (element == null) continue
                    val elementType = getFieldValueRecursive(element, "elementType") as? Int
                    val picElem = getFieldValueRecursive(element, "picElement")
                    if (elementType == 2 || picElem != null) {
                        hasRealPicElement = true
                    }
                }
            }
        }

        val componentClassName = component?.javaClass?.name?.lowercase(Locale.ROOT) ?: ""
        val isExplicitImageComponent = (componentClassName.contains("pic") || componentClassName.contains("image")) &&
                !componentClassName.contains("text") && !componentClassName.contains("reply") && !componentClassName.contains("bubble")
        val shouldExtractImage = if (hasElements) hasRealPicElement else isExplicitImageComponent

        // 1. 优先从底层 MsgRecord 提取原图/缩略图路径，若未命中且判定为图片视图则从 View 结构提取
        val targetDirectImages = extractPicPathsFromMsgItem(msgItem).toMutableList()
        if (targetDirectImages.isEmpty() && shouldExtractImage && component != null) {
            targetDirectImages.addAll(extractImagesFromComponent(context, component))
        }

        val allMsgList = if (component != null) findMessageListFromComponent(component) else null

        // 2. 若当前消息存在引用回复元素（ReplyElement），溯源获取被引用原消息中的图片
        val quotedImages = mutableListOf<String>()
        if (allMsgList != null && msgRecord != null) {
            val elements = getFieldValueRecursive(msgRecord, "elements") as? List<*>
            val replyElem = elements?.firstNotNullOfOrNull { elem ->
                elem?.let { getFieldValueRecursive(it, "replyElement") }
            }
            if (replyElem != null) {
                val quotedItem = findQuotedMessageItem(allMsgList, replyElem)
                if (quotedItem != null) {
                    quotedImages.addAll(extractPicPathsFromMsgItem(quotedItem))
                }
            }
        }

        val targetSender = extractSenderName(msgItem, msgRecord)
        var currentMsgText = extractStructuredTextFromMsgItem(msgItem)?.trim() ?: ""

        // 3. 检查用户是否配置了上下文消息条数，并提取上下文文本行与关联的图片路径
        val contextCount = LlmConfig.getContextCount(context)
        val contextHistory = if (contextCount > 0 && component != null) {
            extractContextHistory(context, component, msgItem, contextCount)
        } else {
            ContextHistory(emptyList(), emptyList(), emptyList())
        }

        val sessionScene = extractSessionScene(context, msgItem, msgRecord)

        val chatType = (getFieldValueRecursive(msgRecord ?: msgItem, "chatType") as? Int) ?: 1
        val peerUid = (getFieldValueRecursive(msgRecord ?: msgItem, "peerUid") as? String) ?: ""
        val sessionType = if (chatType == 2) "GROUP" else "FRIEND"
        val sessionId = if (chatType == 2) "group_$peerUid" else "friend_$peerUid"
        var peerName = (getFieldValueRecursive(msgRecord ?: msgItem, "peerName") as? String)?.trim() ?: ""
        if (peerName.isBlank() && context is Activity) {
            val actTitle = context.title?.toString()?.trim() ?: ""
            if (actTitle.isNotBlank() && actTitle != "QQ") {
                peerName = actTitle
            }
        }
        val sessionTitle = peerName.ifBlank { sessionId }

        val now = java.util.Calendar.getInstance()
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm (E)", Locale.getDefault())
        val currentTimeStr = sdf.format(now.time)

        // 统一收集与对齐所有多模态图片（上下文历史图片 + 目标消息直接/引用图片）
        val allVisualInfos = mutableListOf<ImageOriginInfo>()
        allVisualInfos.addAll(contextHistory.imageInfos)

        var imageCounter = contextHistory.imageInfos.size + 1
        val targetAllPics = (targetDirectImages + quotedImages).distinct()
        if (targetAllPics.isNotEmpty()) {
            val targetTags = mutableListOf<String>()
            for (pic in targetAllPics) {
                val f = File(pic)
                if (f.exists() && f.isFile && f.length() > 0) {
                    val tag = "[图片 #$imageCounter]"
                    targetTags.add(tag)
                    allVisualInfos.add(
                        ImageOriginInfo(
                            tag = tag,
                            imagePath = pic,
                            sender = targetSender,
                            timeStr = "当前",
                            isTargetMsg = true
                        )
                    )
                    imageCounter++
                }
            }
            if (targetTags.isNotEmpty()) {
                val tagsStr = targetTags.joinToString(" ")
                currentMsgText = when {
                    currentMsgText.contains("[图片]") -> currentMsgText.replace("[图片]", tagsStr)
                    currentMsgText.isBlank() -> tagsStr
                    else -> "$currentMsgText $tagsStr"
                }
            }
        }

        val promptBuilder = StringBuilder()
        promptBuilder.append("<system_context>\n")
        promptBuilder.append("Current Time: ").append(currentTimeStr).append("\n")
        promptBuilder.append("Scene: ").append(sessionScene).append("\n")
        promptBuilder.append("Target Sender: ").append(targetSender).append("\n")
        promptBuilder.append("</system_context>\n\n")

        if (contextHistory.lines.isNotEmpty()) {
            promptBuilder.append("<chat_history>\n")
            for (historyLine in contextHistory.lines) {
                promptBuilder.append(historyLine).append("\n")
            }
            promptBuilder.append("</chat_history>\n\n")
        }

        val targetContent = if (currentMsgText.isNotEmpty()) currentMsgText else "[消息]"
        promptBuilder.append("<target_message>\n")
        promptBuilder.append(targetContent).append("\n")
        promptBuilder.append("</target_message>\n\n")

        // 4. 若涉及图片，注入客观的视觉图片映射与来源归属
        if (allVisualInfos.isNotEmpty()) {
            promptBuilder.append("<visual_context>\n")
            for (info in allVisualInfos) {
                val srcDesc = if (info.isTargetMsg) "当前目标消息附带配图" else "由 ${info.sender} 于 ${info.timeStr.ifEmpty { "前序" }} 发送"
                promptBuilder.append("- ${info.tag}: $srcDesc\n")
            }
            promptBuilder.append("</visual_context>\n\n")
        }

        val finalText = promptBuilder.toString().trim()

        val combinedImages = allVisualInfos
            .map { it.imagePath }
            .filter { path ->
                val f = File(path)
                f.exists() && f.isFile && f.length() > 0
            }
            .distinct()
            .take(5)

        val finalPayload = MessagePayload(
            text = finalText,
            imagePaths = combinedImages,
            sessionId = sessionId,
            sessionTitle = sessionTitle,
            sessionType = sessionType,
            imageInfos = allVisualInfos
        )
        Log.i(TAG, "extractMessagePayload result: sessionId=$sessionId, contextLines=${contextHistory.lines.size}, textLen=${finalPayload.text.length}, imageCount=${finalPayload.imagePaths.size}")
        return finalPayload
    }

    /**
     * 上下文历史提取结果数据载体。
     *
     * 封装前序自然时序历史消息行文本列表、历史图片路径列表以及对应的图片归属信息。
     */
    data class ContextHistory(
        val lines: List<String>,
        val imagePaths: List<String>,
        val imageInfos: List<ImageOriginInfo> = emptyList()
    )

    /**
     * 提取当前长按消息前序的上下文历史消息列表及关联多模态图片。
     *
     * 从 BaseContentComponent 穿透绑定的 AIO 运行时环境（AIOContext -> IListUIOperationApi -> u()）
     * 获取当前会话全量消息列表，定位当前选中消息项并向前检索最多 maxCount 条未被排除的历史消息，
     * 按自然时间升序格式化为 "发送者: 内容" 的字符串列表，并同步收集历史消息中的本地图片文件。
     *
     * Args:
     *     context (Context): Android 上下文环境。
     *     component (Any): 长按触发的内容组件对象（BaseContentComponent）。
     *     currentMsgItem (Any): 当前选中的 AIOMsgItem 实例。
     *     maxCount (Int): 期望检索的最大上下文条数。
     *
     * Returns:
     *     ContextHistory: 包含文本列表与图片列表的上下文结果对象。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.extractMessagePayload`
     */
    private fun extractContextHistory(
        context: Context,
        component: Any,
        currentMsgItem: Any,
        maxCount: Int
    ): ContextHistory {
        val allMsgList = findMessageListFromComponent(component) ?: return ContextHistory(emptyList(), emptyList())
        if (allMsgList.isEmpty()) return ContextHistory(emptyList(), emptyList())

        val targetRecord = extractMsgRecord(currentMsgItem)
        val targetMsgId = targetRecord?.let { getFieldValueRecursive(it, "msgId") as? Long }

        var currentIndex = allMsgList.indexOf(currentMsgItem)
        if (currentIndex < 0 && targetMsgId != null) {
            currentIndex = allMsgList.indexOfFirst { item ->
                val r = extractMsgRecord(item)
                r != null && getFieldValueRecursive(r, "msgId") == targetMsgId
            }
        }

        Log.i(TAG, "extractContextHistory: allMsgList.size=${allMsgList.size}, currentIndex=$currentIndex, maxCount=$maxCount")
        if (currentIndex <= 0) return ContextHistory(emptyList(), emptyList())

        val candidateItems = mutableListOf<Any>()
        var scanIndex = currentIndex - 1
        while (scanIndex >= 0 && candidateItems.size < maxCount) {
            val item = allMsgList[scanIndex]
            val classifier = classifyMessage(item)
            if (!isMessageExcluded(context, classifier)) {
                candidateItems.add(item)
            }
            scanIndex--
        }

        // 保证时间先后顺序：向前查找得到的是逆序列表，反转为自然时间升序
        candidateItems.reverse()

        val resultLines = mutableListOf<String>()
        val resultImages = mutableListOf<String>()
        val resultImageInfos = mutableListOf<ImageOriginInfo>()
        val timeSdf = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
        var contextImgCounter = 1

        for (item in candidateItems) {
            val record = extractMsgRecord(item)
            val sender = extractSenderName(item, record)
            var text = extractSingleMessageText(item)
            val msgTime = (getFieldValueRecursive(record, "msgTime") as? Long) ?: 0L
            val timeStr = if (msgTime > 0L) timeSdf.format(java.util.Date(msgTime * 1000L)) else ""
            val timePrefix = if (timeStr.isNotEmpty()) "[$timeStr] " else ""

            val pics = extractPicPathsFromMsgItem(item)
            if (pics.isNotEmpty()) {
                val assignedTags = mutableListOf<String>()
                for (pic in pics) {
                    val tag = "[图片 #$contextImgCounter]"
                    assignedTags.add(tag)
                    resultImageInfos.add(
                        ImageOriginInfo(
                            tag = tag,
                            imagePath = pic,
                            sender = sender,
                            timeStr = timeStr,
                            isTargetMsg = false
                        )
                    )
                    resultImages.add(pic)
                    contextImgCounter++
                }
                val tagsStr = assignedTags.joinToString(" ")
                text = when {
                    text.contains("[图片]") -> text.replace("[图片]", tagsStr)
                    text.isBlank() -> tagsStr
                    else -> "$text $tagsStr"
                }
            } else if (text.isBlank()) {
                text = "[图片]"
            }

            if (text.isNotBlank()) {
                resultLines.add("$timePrefix$sender: $text")
            }
        }
        return ContextHistory(resultLines, resultImages, resultImageInfos)
    }

    /**
     * 尝试根据 ReplyElement 中的 replayMsgId 从全量消息列表中查找被引用的原始消息项。
     *
     * Args:
     *     allMsgList (List<Any>): 全量消息项列表。
     *     replyElem (Any): ReplyElement 实例。
     *
     * Returns:
     *     Any?: 查找到的被引用 AIOMsgItem 实例，若未找到则返回 null。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.extractMessagePayload`
     */
    private fun findQuotedMessageItem(allMsgList: List<Any>, replyElem: Any): Any? {
        val replayMsgId = (getFieldValueRecursive(replyElem, "replayMsgId") as? Long)
            ?: (getFieldValueRecursive(replyElem, "sourceMsgIdInRecords") as? Long)
            ?: return null
        if (replayMsgId == 0L) return null

        return allMsgList.firstOrNull { item ->
            val r = extractMsgRecord(item)
            r != null && getFieldValueRecursive(r, "msgId") == replayMsgId
        }
    }

    /**
     * 从 AIOMsgItem 或 MsgRecord 中提取本地有效的图片文件路径列表。
     *
     * 严谨扫描其内部 elements 中的 PicElement，优先读取原图 sourcePath；
     * 若原图未下载或路径不存在，则检索 thumbPath 缩略图路径映射中的有效本地文件。
     *
     * Args:
     *     msgItem (Any): 待提取图片的 AIOMsgItem 或 MsgRecord 实例。
     *
     * Returns:
     *     List<String>: 本地存在的图片绝对路径列表。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.extractMessagePayload`
     *     - `AioMenuHook.kt:AioMenuHook.extractContextHistory`
     */
    private fun extractPicPathsFromMsgItem(msgItem: Any): List<String> {
        val record = extractMsgRecord(msgItem) ?: return emptyList()
        val elements = getFieldValueRecursive(record, "elements") as? List<*> ?: return emptyList()
        val validPaths = mutableListOf<String>()

        for (elem in elements) {
            if (elem == null) continue
            val picElem = getFieldValueRecursive(elem, "picElement") ?: continue

            // 1. 优先读取高清原图本地存储路径
            val sourcePath = getFieldValueRecursive(picElem, "sourcePath") as? String
            if (!sourcePath.isNullOrBlank()) {
                val f = File(sourcePath)
                if (f.exists() && f.isFile && f.length() > 0) {
                    validPaths.add(sourcePath)
                    continue
                }
            }

            // 2. 检查 direct 路径属性 (downloadPath, filePath)
            val downloadPath = getFieldValueRecursive(picElem, "downloadPath") as? String
            if (!downloadPath.isNullOrBlank()) {
                val f = File(downloadPath)
                if (f.exists() && f.isFile && f.length() > 0) {
                    validPaths.add(downloadPath)
                    continue
                }
            }

            val filePath = getFieldValueRecursive(picElem, "filePath") as? String
            if (!filePath.isNullOrBlank()) {
                val f = File(filePath)
                if (f.exists() && f.isFile && f.length() > 0) {
                    validPaths.add(filePath)
                    continue
                }
            }

            // 3. 遍历 thumbPath 缩略图映射
            val thumbPathMap = getFieldValueRecursive(picElem, "thumbPath") as? Map<*, *>
            var foundThumb: String? = null
            if (!thumbPathMap.isNullOrEmpty()) {
                for (v in thumbPathMap.values) {
                    val p = v as? String ?: continue
                    if (p.isNotBlank()) {
                        val f = File(p)
                        if (f.exists() && f.isFile && f.length() > 0) {
                            foundThumb = p
                            break
                        }
                    }
                }
            }
            if (foundThumb != null) {
                validPaths.add(foundThumb)
                continue
            }

            // 4. 反射遍历 picElem 中所有以 '/' 开头的有效绝对路径字段
            var foundReflectPath: String? = null
            try {
                var c: Class<*>? = picElem.javaClass
                while (c != null && c != Any::class.java) {
                    for (fld in c.declaredFields) {
                        if (fld.type == String::class.java) {
                            fld.isAccessible = true
                            val strVal = fld.get(picElem) as? String
                            if (!strVal.isNullOrBlank() && strVal.startsWith("/") && !strVal.contains("http")) {
                                val file = File(strVal)
                                if (file.exists() && file.isFile && file.length() > 0) {
                                    foundReflectPath = strVal
                                    break
                                }
                            }
                        }
                    }
                    if (foundReflectPath != null) break
                    c = c.superclass
                }
            } catch (_: Throwable) {
            }

            if (foundReflectPath != null) {
                validPaths.add(foundReflectPath)
            }
        }
        return validPaths.distinct()
    }

    /**
     * 对单条消息进行多维度类型识别与特征分类。
     *
     * 严谨扫描其内部的 MsgElement 元素集合，标识是否包含文本、图片、表情、卡片以及语音。
     *
     * Args:
     *     msgItem (Any): 待识别分类的 AIOMsgItem 实例。
     *
     * Returns:
     *     MessageClassifier: 包含该条消息类型判定特征的分类对象。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.extractContextHistory`
     */
    private fun classifyMessage(msgItem: Any): MessageClassifier {
        val record = extractMsgRecord(msgItem)
        var hasText = false
        var hasPic = false
        var hasEmoji = false
        var hasCard = false
        var hasVoice = false
        var otherElementCount = 0

        if (record != null) {
            val elements = getFieldValueRecursive(record, "elements") as? List<*>
            if (!elements.isNullOrEmpty()) {
                for (elem in elements) {
                    if (elem == null) continue
                    val elemType = getFieldValueRecursive(elem, "elementType") as? Int

                    val textElem = getFieldValueRecursive(elem, "textElement")
                    if (elemType == 1 || textElem != null) {
                        val content = textElem?.let { getFieldValueRecursive(it, "content") as? String }
                        if (!content.isNullOrBlank()) {
                            hasText = true
                        }
                    }

                    val picElem = getFieldValueRecursive(elem, "picElement")
                    if (elemType == 2 || picElem != null) {
                        hasPic = true
                        otherElementCount++
                    }

                    val faceElem = getFieldValueRecursive(elem, "faceElement")
                    val marketFaceElem = getFieldValueRecursive(elem, "marketFaceElement")
                    if (elemType == 6 || elemType == 11 || faceElem != null || marketFaceElem != null) {
                        hasEmoji = true
                    }

                    val arkElem = getFieldValueRecursive(elem, "arkElement")
                    val structElem = getFieldValueRecursive(elem, "structMsgElement")
                    val structLongElem = getFieldValueRecursive(elem, "structLongMsgElement")
                    val multiForwardElem = getFieldValueRecursive(elem, "multiForwardMsgElement")
                    if (elemType == 10 || elemType == 14 || elemType == 16 || elemType == 115 ||
                        arkElem != null || structElem != null || structLongElem != null || multiForwardElem != null) {
                        hasCard = true
                        otherElementCount++
                    }

                    val pttElem = getFieldValueRecursive(elem, "pttElement")
                    if (elemType == 4 || pttElem != null) {
                        hasVoice = true
                        otherElementCount++
                    }
                }
            }
        }

        val isOnlyEmoji = hasEmoji && !hasText && !hasPic && !hasCard && !hasVoice
        return MessageClassifier(
            hasText = hasText,
            hasPic = hasPic,
            hasEmoji = hasEmoji,
            hasCard = hasCard,
            hasVoice = hasVoice,
            isOnlyEmoji = isOnlyEmoji
        )
    }

    /**
     * 依据用户在设置界面中配置的排除规则，判定该消息是否应该从上下文中过滤。
     *
     * Args:
     *     context (Context): Android 上下文环境。
     *     classifier (MessageClassifier): 消息特征分类对象。
     *
     * Returns:
     *     Boolean: true 表示该条消息应被过滤排除，false 表示保留。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.extractContextHistory`
     */
    private fun isMessageExcluded(context: Context, classifier: MessageClassifier): Boolean {
        if (LlmConfig.isExcludeImage(context) && classifier.hasPic) {
            return true
        }
        if (LlmConfig.isExcludeEmoji(context) && classifier.isOnlyEmoji) {
            return true
        }
        if (LlmConfig.isExcludeCard(context) && classifier.hasCard) {
            return true
        }
        if (LlmConfig.isExcludeVoice(context) && classifier.hasVoice) {
            return true
        }
        return false
    }

    /**
     * 提取消息发送者的展示昵称或别名。
     *
     * 优先判断是否为自己发送的消息（是则返回“我”）；
     * 他人消息优先调用 AIOMsgItem.H0() 读取屏幕已渲染的群名片/好友备注/昵称；
     * 若未命中，则深度提取 MsgRecord 中的群名片、好友备注、昵称与发送者 QQ 号。
     *
     * Args:
     *     msgItem (Any): AIOMsgItem 实例。
     *     msgRecord (Any?): 关联的 MsgRecord 实例。
     *
     * Returns:
     *     String: 发送者展示名称。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.extractContextHistory`
     *     - `AioInputHelper.kt:AioInputHelper.sendDirectMessage`
     */
    fun extractSenderName(msgItem: Any, msgRecord: Any?): String {
        if (isMessageSentBySelf(msgItem, msgRecord)) {
            return "我"
        }

        // 1. 优先调用 AIOMsgItem.H0() 获取 AIO 视图当前已解析渲染的群名片/昵称 CharSequence
        val h0Method = msgItem.javaClass.methods.firstOrNull {
            it.name == "H0" && it.parameterTypes.isEmpty() && CharSequence::class.java.isAssignableFrom(it.returnType)
        }
        val displayedNick = runCatching { h0Method?.invoke(msgItem)?.toString() }.getOrNull()
        if (!displayedNick.isNullOrBlank()) {
            return displayedNick.trim()
        }

        // 2. 依次读取 MsgRecord 中的结构化群名片、好友备注、昵称与发送者 QQ 号
        if (msgRecord != null) {
            val nickFields = listOf("sendMemberName", "sendRemarkName", "sendNickName", "senderRemark", "senderNick", "senderName")
            for (fName in nickFields) {
                val v = getFieldValueRecursive(msgRecord, fName) as? String
                if (!v.isNullOrBlank()) return v.trim()
            }
            val uin = getFieldValueRecursive(msgRecord, "senderUin")
            if (uin != null && uin.toString().isNotBlank() && uin.toString() != "0") {
                return uin.toString()
            }
        }

        val chatType = (getFieldValueRecursive(msgRecord ?: msgItem, "chatType") as? Int) ?: 1
        return if (chatType == 1) "对方" else "群友"
    }

    /**
     * 根据消息记录和当前会话环境，提取结构化的会话场景描述。
     *
     * 明确区分群聊、私聊或频道等场景，以及会话对象/群名称。
     *
     * Args:
     *     context (Context): Android 上下文环境。
     *     msgItem (Any): AIOMsgItem 实例。
     *     msgRecord (Any?): 关联的 MsgRecord 实例。
     *
     * Returns:
     *     String: 会话场景文本描述（例如“群聊（技术讨论群）”或“与「张三」的好友私聊”）。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.extractMessagePayload`
     */
    private fun extractSessionScene(context: Context, msgItem: Any, msgRecord: Any?): String {
        val chatType = (getFieldValueRecursive(msgRecord ?: msgItem, "chatType") as? Int) ?: 1
        var peerName = (getFieldValueRecursive(msgRecord ?: msgItem, "peerName") as? String)?.trim() ?: ""
        if (peerName.isBlank() && context is Activity) {
            val actTitle = context.title?.toString()?.trim() ?: ""
            if (actTitle.isNotBlank() && actTitle != "QQ") {
                peerName = actTitle
            }
        }
        return when (chatType) {
            2 -> if (peerName.isNotEmpty()) "群聊（$peerName）" else "群聊"
            3 -> if (peerName.isNotEmpty()) "频道（$peerName）" else "频道"
            1 -> if (peerName.isNotEmpty()) "与「$peerName」的好友私聊" else "好友私聊"
            else -> if (peerName.isNotEmpty()) "会话（$peerName）" else "私聊"
        }
    }

    /**
     * 判定指定消息是否由当前登录用户自己发送。
     *
     * 基于 QQNT 内核中 MsgRecord.sendType == 1（1 为发送方，0/2 为接收方）及 AIOMsgItem.X0() 进行判定。
     *
     * Args:
     *     msgItem (Any): AIOMsgItem 实例。
     *     msgRecord (Any?): 关联的 MsgRecord 实例。
     *
     * Returns:
     *     Boolean: true 为自己发送的消息，false 为他人发送。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.extractSenderName`
     */
    private fun isMessageSentBySelf(msgItem: Any, msgRecord: Any?): Boolean {
        if (msgRecord != null) {
            val sendType = getFieldValueRecursive(msgRecord, "sendType") as? Int
            if (sendType != null) {
                return sendType == 1
            }
            val sendBySelf = (getFieldValueRecursive(msgRecord, "isSendBySelf") as? Boolean)
                ?: (getFieldValueRecursive(msgRecord, "sendBySelf") as? Boolean)
                ?: (getFieldValueRecursive(msgRecord, "isSelf") as? Boolean)
            if (sendBySelf != null) return sendBySelf
        }

        val x0Method = msgItem.javaClass.methods.firstOrNull {
            it.name == "X0" && it.parameterTypes.isEmpty() && it.returnType == java.lang.Boolean.TYPE
        }
        val x0Result = runCatching { x0Method?.invoke(msgItem) as? Boolean }.getOrNull()
        if (x0Result != null) return x0Result

        val methodNames = listOf("isSendBySelf", "isSelf", "isSelfSend")
        for (mName in methodNames) {
            val res = runCatching {
                msgItem.javaClass.methods.firstOrNull { it.name == mName && it.parameterTypes.isEmpty() }?.invoke(msgItem) as? Boolean
            }.getOrNull()
            if (res != null) return res
        }

        return false
    }

    /**
     * 提取用于上下文展示的单条消息文本描述。
     *
     * 统一路由至 extractStructuredTextFromMsgItem 获取普通文本、合并转发及卡片文本，
     * 纯图片消息返回空串以避免在上下文提示词中注入图片占位符。
     *
     * Args:
     *     msgItem (Any): AIOMsgItem 实例。
     *
     * Returns:
     *     String: 单条消息的文本摘要。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.extractContextHistory`
     *     - `AioInputHelper.kt:AioInputHelper.sendDirectMessage`
     */
    fun extractSingleMessageText(msgItem: Any): String {
        val structuredText = extractStructuredTextFromMsgItem(msgItem)
        return structuredText?.trim() ?: ""
    }

    /**
     * 通过组件绑定的 AIO 运行时环境获取会话的全量消息列表。
     *
     * 穿透 BaseContentComponent (f202618m 或 mAIOContext) -> AIOContextImpl -> p() (IListUIOperationApi) -> u()。
     *
     * Args:
     *     component (Any): 消息组件对象。
     *
     * Returns:
     *     List<Any>?: 消息项列表，未获取到返回 null。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.extractContextHistory`
     */
    private fun findMessageListFromComponent(component: Any): List<Any>? {
        val aioContext = getFieldValueRecursive(component, "f202618m")
            ?: getFieldValueRecursive(component, "mAIOContext")
            ?: runCatching {
                val m = component.javaClass.methods.firstOrNull { it.name.startsWith("getMAIOContext") && it.parameterTypes.isEmpty() }
                m?.invoke(component)
            }.getOrNull()

        if (aioContext != null) {
            val ryOperation = getFieldValueRecursive(aioContext, "f70150b")
                ?: runCatching {
                    val pMethod = aioContext.javaClass.methods.firstOrNull {
                        (it.name == "p" || it.returnType.name.contains("IListUIOperationApi")) && it.parameterTypes.isEmpty()
                    }
                    pMethod?.invoke(aioContext)
                }.getOrNull()

            if (ryOperation != null) {
                val uMethod = ryOperation.javaClass.methods.firstOrNull {
                    it.name == "u" && List::class.java.isAssignableFrom(it.returnType) && it.parameterTypes.isEmpty()
                }
                @Suppress("UNCHECKED_CAST")
                val list = runCatching { uMethod?.invoke(ryOperation) as? List<Any> }.getOrNull()
                if (!list.isNullOrEmpty()) {
                    Log.i(TAG, "findMessageListFromComponent: successfully obtained ${list.size} messages from IListUIOperationApi.u()")
                    return list
                }
            }
        }
        return null
    }

    /**
     * 从 AIOMsgItem 实例中提取目标会话 Contact 实例。
     *
     * 优先调用 AIOMsgItem.D() 获取 Contact 实例，若未命中则通过 MsgRecord 字段反射构建。
     *
     * Args:
     *     msgItem (Any): AIOMsgItem 实例。
     *
     * Returns:
     *     Any?: Contact 实例，失败返回 null。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.init`
     */
    fun extractContact(msgItem: Any): Any? {
        val dMethod = msgItem.javaClass.methods.firstOrNull {
            it.name == "D" && it.parameterTypes.isEmpty() && it.returnType.name.contains("Contact")
        }
        val directContact = runCatching { dMethod?.invoke(msgItem) }.getOrNull()
        if (directContact != null) return directContact

        val msgRecord = extractMsgRecord(msgItem) ?: return null
        val chatType = (getFieldValueRecursive(msgRecord, "chatType") as? Int) ?: 1
        val peerUid = (getFieldValueRecursive(msgRecord, "peerUid") as? String) ?: ""
        val guildId = (getFieldValueRecursive(msgRecord, "guildId") as? String) ?: ""

        val classLoader = msgItem.javaClass.classLoader ?: return null
        val contactClasses = listOf(
            "com.tencent.qqnt.kernelpublic.nativeinterface.Contact",
            "com.tencent.qqnt.kernel.nativeinterface.Contact"
        )
        for (cName in contactClasses) {
            try {
                val clazz = classLoader.loadClass(cName)
                val ctor = clazz.getConstructor(java.lang.Integer.TYPE, String::class.java, String::class.java)
                return ctor.newInstance(chatType, peerUid, guildId)
            } catch (_: ClassNotFoundException) {
            } catch (_: NoSuchMethodException) {
            }
        }
        return null
    }

    /**
     * 针对单个 MsgElement 解析其结构化的人类可读文本内容。
     *
     * 覆盖普通文本（TextElement）、语音识别（PttElement）、系统小黄脸表情（FaceElement）、
     * 商城表情（MarketFaceElement）、引用回复（ReplyElement）、Ark卡片（ArkElement）、
     * 结构化XML卡片（StructMsgElement）、合并转发（MultiForwardMsgElement）及文件/视频。
     *
     * Args:
     *     element (Any): 待解析的单个 MsgElement 实例。
     *
     * Returns:
     *     String?: 格式化后的文本片段，若无对应文本内容则返回 null。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.extractMessagePayload`
     */
    private fun extractElementText(element: Any): String? {
        // 1. 普通文本
        val textElem = getFieldValueRecursive(element, "textElement")
        if (textElem != null) {
            val content = getFieldValueRecursive(textElem, "content") as? String
            if (!content.isNullOrEmpty()) return content
        }

        // 2. 语音转文字
        val pttElem = getFieldValueRecursive(element, "pttElement")
        if (pttElem != null) {
            val transText = (getFieldValueRecursive(pttElem, "text") as? String)
                ?: (getFieldValueRecursive(pttElem, "words") as? String)
            if (!transText.isNullOrBlank()) return transText
            return "[语音消息]"
        }

        // 3. 系统小黄脸表情
        val faceElem = getFieldValueRecursive(element, "faceElement")
        if (faceElem != null) {
            val faceText = getFieldValueRecursive(faceElem, "faceText") as? String
            if (!faceText.isNullOrBlank()) return faceText
            return "[表情]"
        }

        // 4. 商城表情包
        val marketFaceElem = getFieldValueRecursive(element, "marketFaceElement")
        if (marketFaceElem != null) {
            val faceName = getFieldValueRecursive(marketFaceElem, "faceName") as? String
            if (!faceName.isNullOrBlank()) return "[表情: $faceName]"
            return "[表情包]"
        }

        // 5. 图片消息元素
        val picElem = getFieldValueRecursive(element, "picElement")
        if (picElem != null) {
            val summary = getFieldValueRecursive(picElem, "summary") as? String
            if (!summary.isNullOrBlank()) {
                return "[图片: $summary]"
            }
            return "[图片]"
        }

        // 6. 引用回复消息
        val replyElem = getFieldValueRecursive(element, "replyElement")
        if (replyElem != null) {
            val sourceText = getFieldValueRecursive(replyElem, "sourceMsgText") as? String
            val isIncPic = (getFieldValueRecursive(replyElem, "sourceMsgIsIncPic") as? Boolean) == true
            val sourceNick = (getFieldValueRecursive(replyElem, "sourceMsgSenderNick") as? String)
                ?: (getFieldValueRecursive(replyElem, "senderNick") as? String)
            val nickStr = if (!sourceNick.isNullOrBlank()) "@$sourceNick: " else ""
            val contentStr = when {
                !sourceText.isNullOrBlank() && isIncPic -> "$sourceText [图片]"
                !sourceText.isNullOrBlank() -> sourceText
                isIncPic -> "[图片]"
                else -> "[消息]"
            }
            return "「引用 $nickStr$contentStr」\n"
        }

        // 6. Ark 卡片消息（小程序、文档、第三方分享等）
        val arkElem = getFieldValueRecursive(element, "arkElement")
        if (arkElem != null) {
            val bytesData = getFieldValueRecursive(arkElem, "bytesData") as? String
            if (!bytesData.isNullOrBlank()) {
                val arkText = parseArkCardText(bytesData)
                if (!arkText.isNullOrBlank()) return arkText
            }
        }

        // 7. 结构化 XML 卡片消息（包含 StructMsgElement 与 StructLongMsgElement）
        val structMsgElem = getFieldValueRecursive(element, "structMsgElement")
            ?: getFieldValueRecursive(element, "structLongMsgElement")
            ?: getFieldValueRecursive(element, "xmlElement")
        if (structMsgElem != null) {
            val xmlContent = (getFieldValueRecursive(structMsgElem, "xmlContent") as? String)
                ?: (getFieldValueRecursive(structMsgElem, "xml") as? String)
            if (!xmlContent.isNullOrBlank()) {
                val xmlText = parseStructMsgXml(xmlContent)
                if (!xmlText.isNullOrBlank()) return xmlText
            }
        }

        // 8. 聊天记录合并转发卡片（MultiForwardMsgElement）
        val multiForwardElem = getFieldValueRecursive(element, "multiForwardMsgElement")
        if (multiForwardElem != null) {
            val xmlContent = (getFieldValueRecursive(multiForwardElem, "xmlContent") as? String)
                ?: (getFieldValueRecursive(multiForwardElem, "xml") as? String)
            if (!xmlContent.isNullOrBlank()) {
                val fullText = parseFullMultiForwardXml(xmlContent)
                if (!fullText.isNullOrBlank()) return fullText
            }

            val summaryList = (getFieldValueRecursive(multiForwardElem, "summaryList") as? List<*>)
                ?: (getFieldValueRecursive(multiForwardElem, "summarys") as? List<*>)
                ?: (getFieldValueRecursive(multiForwardElem, "summaries") as? List<*>)
            val title = getFieldValueRecursive(multiForwardElem, "title") as? String
            if (!summaryList.isNullOrEmpty()) {
                val sb = StringBuilder()
                sb.append("[合并转发: ").append(title ?: "聊天记录").append("]\n")
                summaryList.filterIsInstance<String>().forEach { summary ->
                    sb.append("- ").append(summary).append("\n")
                }
                return sb.toString().trim()
            }
        }

        // 9. 文件消息
        val fileElem = getFieldValueRecursive(element, "fileElement")
        if (fileElem != null) {
            val fileName = getFieldValueRecursive(fileElem, "fileName") as? String
            if (!fileName.isNullOrBlank()) return "[文件: $fileName]"
        }

        // 10. 视频消息
        val videoElem = getFieldValueRecursive(element, "videoElement")
        if (videoElem != null) {
            val fileName = getFieldValueRecursive(videoElem, "fileName") as? String
            if (!fileName.isNullOrBlank()) return "[视频: $fileName]"
        }

        return null
    }

    /**
     * 解析 Ark 卡片消息的 JSON 字符串（bytesData），提炼卡片标题、描述与提示文本。
     *
     * Args:
     *     bytesData (String): Ark 卡片的原始 JSON 字符串。
     *
     * Returns:
     *     String?: 提炼出的格式化卡片文本，解析失败或无内容时返回 null。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.extractElementText`
     */
    private fun parseArkCardText(bytesData: String): String? {
        if (bytesData.isBlank()) return null
        return try {
            val root = JSONObject(bytesData)
            val prompt = root.optString("prompt", "").trim()
            val desc = root.optString("desc", "").trim()
            val meta = root.optJSONObject("meta")

            val details = StringBuilder()
            if (meta != null) {
                val keys = meta.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val subObj = meta.optJSONObject(key) ?: continue
                    val title = subObj.optString("title", "").trim()
                    val subDesc = subObj.optString("desc", "").trim()
                    val source = subObj.optString("source", "").trim()

                    if (source.isNotEmpty()) details.append("来源: ").append(source).append("\n")
                    if (title.isNotEmpty()) details.append("标题: ").append(title).append("\n")
                    if (subDesc.isNotEmpty()) details.append("描述: ").append(subDesc).append("\n")
                }
            }

            val result = StringBuilder()
            if (prompt.isNotEmpty()) {
                result.append(prompt).append("\n")
            } else if (desc.isNotEmpty()) {
                result.append("[").append(desc).append("]\n")
            }
            if (details.isNotEmpty()) {
                result.append(details.toString().trim())
            }
            result.toString().trim().ifEmpty { null }
        } catch (e: JSONException) {
            Log.w(TAG, "parseArkCardText failed to parse json payload: ${e.message}")
            null
        }
    }

    /**
     * 解析结构化消息与合并转发中的 XML 字符串，提取概要、标题与全量摘要列表。
     *
     * 支持包含属性的标签（如 <title color="#000000"> 与 <summary color="#878B99">），
     * 兼容单双引号属性、CDATA 文本块以及 XML 字符实体反转义，确保多条转发记录无遗漏提取。
     *
     * Args:
     *     xmlContent (String): 结构化卡片或合并转发的 XML 原始字符串。
     *
     * Returns:
     *     String?: 格式化后的多行文本，若无有效信息则返回 null。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.extractElementText`
     */
    private fun parseStructMsgXml(xmlContent: String): String? {
        if (xmlContent.isBlank()) return null
        val sb = StringBuilder()

        val briefRegex = Regex("""brief=["']([^"']+)["']""")
        val rawBrief = briefRegex.find(xmlContent)?.groupValues?.getOrNull(1)?.trim()
        val brief = rawBrief?.let { cleanXmlText(it) }

        val titleRegex = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
        val rawTitle = titleRegex.find(xmlContent)?.groupValues?.getOrNull(1)?.trim()
        val title = rawTitle?.let { cleanXmlText(it) }

        val summaryRegex = Regex("""<summary[^>]*>(.*?)</summary>""", RegexOption.DOT_MATCHES_ALL)
        val summaries = summaryRegex.findAll(xmlContent)
            .map { it.groupValues.getOrNull(1)?.trim() }
            .filter { !it.isNullOrBlank() }
            .map { cleanXmlText(it!!) }
            .filter { it.isNotBlank() }
            .toList()

        val isMultiForward = xmlContent.contains("""serviceID="35"""") ||
                xmlContent.contains("""serviceID='35'""") ||
                xmlContent.contains("viewMultiMsg") ||
                summaries.size > 1

        if (isMultiForward) {
            val headerTitle = if (!title.isNullOrBlank()) title else if (!brief.isNullOrBlank()) brief else "聊天记录"
            sb.append("[合并转发: ").append(headerTitle).append("]\n")
            for (summary in summaries) {
                sb.append(summary).append("\n")
            }
        } else {
            if (!brief.isNullOrBlank()) {
                sb.append(brief).append("\n")
            }
            if (!title.isNullOrBlank()) {
                sb.append("标题: ").append(title).append("\n")
            }
            for (summary in summaries) {
                sb.append("描述: ").append(summary).append("\n")
            }
        }

        return sb.toString().trim().ifEmpty { null }
    }

    /**
     * 深度解析合并转发卡片 XML，递归提取所有的消息条目、时间戳、发送人及内容。
     *
     * 优先解析标准 XML 树中的所有 <item> 块，提取所有 <title>、<summary>、<text>，
     * 展平所有子发言人与内容，确保数十条记录全量还原而非仅显示粗略预览。
     *
     * Args:
     *     xmlContent (String): 合并转发卡片 XML 原始报文。
     *
     * Returns:
     *     String?: 格式化后的完整聊天记录明细文本，若无有效信息则返回 null。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.extractElementText`
     */
    private fun parseFullMultiForwardXml(xmlContent: String): String? {
        if (xmlContent.isBlank()) return null

        val briefRegex = Regex("""brief=["']([^"']+)["']""")
        val rawBrief = briefRegex.find(xmlContent)?.groupValues?.getOrNull(1)?.trim()
        val brief = rawBrief?.let { cleanXmlText(it) }

        val titleRegex = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
        val rawTitle = titleRegex.find(xmlContent)?.groupValues?.getOrNull(1)?.trim()
        val title = rawTitle?.let { cleanXmlText(it) }

        val headerTitle = if (!title.isNullOrBlank()) title else if (!brief.isNullOrBlank()) brief else "聊天记录"

        // 提取所有 item 块
        val itemRegex = Regex("""<item[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
        val itemMatches = itemRegex.findAll(xmlContent).toList()

        val recordLines = mutableListOf<String>()

        if (itemMatches.isNotEmpty()) {
            for (match in itemMatches) {
                val itemBody = match.groupValues.getOrNull(1) ?: continue
                val textTags = Regex("""<(?:summary|title|text|p)[^>]*>(.*?)</(?:summary|title|text|p)>""", RegexOption.DOT_MATCHES_ALL)
                val lines = textTags.findAll(itemBody)
                    .map { cleanXmlText(it.groupValues.getOrNull(1)?.trim().orEmpty()) }
                    .filter { it.isNotBlank() }
                    .toList()
                if (lines.isNotEmpty()) {
                    recordLines.add(lines.joinToString(" "))
                }
            }
        }

        if (recordLines.isEmpty()) {
            val summaryRegex = Regex("""<summary[^>]*>(.*?)</summary>""", RegexOption.DOT_MATCHES_ALL)
            recordLines.addAll(
                summaryRegex.findAll(xmlContent)
                    .map { cleanXmlText(it.groupValues.getOrNull(1)?.trim().orEmpty()) }
                    .filter { it.isNotBlank() }
            )
        }

        if (recordLines.isEmpty()) return null

        val sb = StringBuilder()
        sb.append("[合并转发: ").append(headerTitle).append("]\n")
        val contentLines = if (recordLines.isNotEmpty() && recordLines[0] == headerTitle) recordLines.drop(1) else recordLines
        for (line in contentLines) {
            sb.append("- ").append(line).append("\n")
        }
        return sb.toString().trim().ifEmpty { null }
    }

    /**
     * 清理 XML 文本中的 CDATA 包装标签及字符实体引用。
     *
     * 解决包含 XML 字符转义序列以及 CDATA 包装块导致的文本乱码与解析失真问题。
     *
     * Args:
     *     text (String): 待清理的原始 XML 片段。
     *
     * Returns:
     *     String: 解码并清理后的纯文本字符串。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.parseStructMsgXml`
     */
    private fun cleanXmlText(text: String): String {
        var clean = text
        val cdataRegex = Regex("""<!\[CDATA\[(.*?)\]\]>""", RegexOption.DOT_MATCHES_ALL)
        val cdataMatch = cdataRegex.find(clean)
        if (cdataMatch != null) {
            clean = cdataMatch.groupValues.getOrNull(1) ?: clean
        }
        return clean.replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .trim()
    }

    /**
     * 从 AIOMsgItem 中提取 QQ 内部的核心消息记录对象（MsgRecord）。
     *
     * 兼容方法调用、内部成员字段以及对象本身为 MsgRecord 的情况。
     *
     * Args:
     *     msgItem (Any): AIOMsgItem 实例。
     *
     * Returns:
     *     Any?: 提取到的 MsgRecord 实例，若未识别则返回 null。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.extractMessagePayload`
     *     - `AioMenuHook.kt:AioMenuHook.extractContextHistory`
     *     - `AioInputHelper.kt:AioInputHelper.sendDirectMessage`
     */
    fun extractMsgRecord(msgItem: Any): Any? {
        if (msgItem.javaClass.name.contains("MsgRecord")) {
            return msgItem
        }

        val methodNames = listOf("getMsgRecord", "getRecord", "getMsg", "msgRecord")
        for (name in methodNames) {
            val method = msgItem.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
                ?: msgItem.javaClass.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }?.apply { isAccessible = true }
            val res = runCatching { method?.invoke(msgItem) }.getOrNull()
            if (res != null) return res
        }

        val fieldNames = listOf("msgRecord", "mMsgRecord", "record", "messageRecord", "f201801e")
        for (name in fieldNames) {
            val res = getFieldValueRecursive(msgItem, name)
            if (res != null) return res
        }

        if (getFieldValueRecursive(msgItem, "elements") != null) {
            return msgItem
        }
        return null
    }

    /**
     * 从长按触发的 AIO 消息组件对象中提取屏幕渲染的全部图片位图并持久化为临时图片文件。
     *
     * 针对单图及多图网格（如九宫格、连续图）布局，完整递归遍历视图树以捕获所有合格主图位图，
     * 写入私有缓存目录后返回有序的文件绝对路径列表。
     *
     * Args:
     *     context (Context): Android 上下文环境。
     *     component (Any?): 长按触发的 BaseContentComponent 或类似组件对象。
     *
     * Returns:
     *     List<String>: 写入私有缓存目录后的临时图片绝对路径列表。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.extractMessagePayload`
     */
    private fun extractImagesFromComponent(context: Context, component: Any?): List<String> {
        if (component == null) return emptyList()
        val view = extractViewFromComponent(component) ?: run {
            Log.w(TAG, "extractImagesFromComponent: cannot obtain View from ${component.javaClass.name}")
            return emptyList()
        }
        val bitmaps = mutableListOf<Bitmap>()
        findAllBitmapsFromViewHierarchy(view, bitmaps)
        if (bitmaps.isEmpty()) {
            Log.w(TAG, "extractImagesFromComponent: no valid bitmap found in view hierarchy of ${view.javaClass.name}")
            return emptyList()
        }
        return bitmaps.mapNotNull { saveBitmapToCache(context, it) }
    }

    /**
     * 从组件对象中反射提取关联的根 View 实例。
     *
     * 支持组件自身为 View、调用常用视图获取方法（如 getView/getContentView/getItemView）、
     * 以及读取常用视图与 ViewHolder 成员字段。
     *
     * Args:
     *     component (Any): 消息组件对象。
     *
     * Returns:
     *     View?: 提取到的根 View 实例，未匹配则返回 null。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.extractImageFromComponent`
     */
    private fun extractViewFromComponent(component: Any): View? {
        if (component is View) return component

        // 1. 优先调用 QQNT BaseContentComponent 核心视图契约方法 R1()
        val r1Method = component.javaClass.methods.firstOrNull { it.name == "R1" && it.parameterTypes.isEmpty() && View::class.java.isAssignableFrom(it.returnType) }
            ?: component.javaClass.declaredMethods.firstOrNull { it.name == "R1" && it.parameterTypes.isEmpty() && View::class.java.isAssignableFrom(it.returnType) }?.apply { isAccessible = true }
        val r1View = runCatching { r1Method?.invoke(component) as? View }.getOrNull()
        if (r1View != null) return r1View

        // 2. 尝试调用返回 View 的常用方法（s2, getView, getContentView 等）
        val methodNames = listOf("s2", "getView", "getContentView", "getItemView", "getRootView", "view")
        for (name in methodNames) {
            val method = component.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() && View::class.java.isAssignableFrom(it.returnType) }
                ?: component.javaClass.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() && View::class.java.isAssignableFrom(it.returnType) }?.apply { isAccessible = true }
            val v = runCatching { method?.invoke(component) as? View }.getOrNull()
            if (v != null) return v
        }

        // 3. 尝试字段与直接引用
        val fieldNames = listOf("mContentView", "contentView", "itemView", "mItemView", "view", "mView", "mRootView", "rootView")
        for (name in fieldNames) {
            val v = getFieldValueRecursive(component, name) as? View
            if (v != null) return v
        }

        // 4. 尝试从 ViewHolder 中提取
        val holderNames = listOf("holder", "mHolder", "viewHolder", "mViewHolder")
        for (hName in holderNames) {
            val holder = getFieldValueRecursive(component, hName) ?: continue
            val v = (getFieldValueRecursive(holder, "itemView") as? View)
                ?: (getFieldValueRecursive(holder, "view") as? View)
            if (v != null) return v
        }

        // 5. 递归扫描持有 View 或 Lazy<View> 的字段
        var currentClass: Class<*>? = component.javaClass
        while (currentClass != null && currentClass != Any::class.java) {
            for (field in currentClass.declaredFields) {
                if (Lazy::class.java.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    val lazyObj = runCatching { field.get(component) as? Lazy<*> }.getOrNull()
                    if (lazyObj != null && lazyObj.isInitialized()) {
                        val lazyVal = lazyObj.value
                        if (lazyVal is View && lazyVal.width > 0 && lazyVal.height > 0) {
                            return lazyVal
                        }
                    }
                } else if (View::class.java.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    val v = runCatching { field.get(component) as? View }.getOrNull()
                    if (v != null && v.width > 0 && v.height > 0) {
                        return v
                    }
                }
            }
            currentClass = currentClass.superclass
        }

        return null
    }

    /**
     * 递归遍历视图树以收集所有包含真实图片内容的 Bitmap 对象。
     *
     * 针对单条消息包含多张图片的场景，遍历所有子控件而不在首个命中时中断。
     * 过滤尺寸过小的指示图标与头像，采用独立的 Bitmap 副本避免宿主原图被回收导致异常。
     *
     * Args:
     *     view (View): 待遍历检查的根视图。
     *     result (MutableList<Bitmap>): 用于收集有效位图的列表容器。
     *
     * Returns:
     *     Unit: 无返回值，结果直接填充至 result 容器中。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.extractImagesFromComponent`
     */
    private fun findAllBitmapsFromViewHierarchy(view: View, result: MutableList<Bitmap>) {
        val viewClassName = view.javaClass.name.lowercase(Locale.ROOT)

        // 过滤辅助图标、指示箭头与小头像等非聊天主图控件
        if (viewClassName.contains("arrow") || viewClassName.contains("icon") ||
            viewClassName.contains("avatar") || viewClassName.contains("reply") || viewClassName.contains("quote")) {
            return
        }

        // 1. 若为 ImageView，必须满足真实聊天主图的尺寸门槛（宽高均不小于120px）
        if (view is ImageView) {
            val d = view.drawable
            if (d != null && view.width >= 120 && view.height >= 120) {
                val bmp = extractBitmapFromDrawable(d, view.width, view.height)
                if (bmp != null && bmp.width >= 120 && bmp.height >= 120) {
                    result.add(bmp)
                    return
                }
            }
        }

        // 2. 检查自定义视图中持有的 Bitmap 字段（同样必须达到有效主图尺寸）
        val directBmp = (getFieldValueRecursive(view, "mBitmap") as? Bitmap)
            ?: (getFieldValueRecursive(view, "bitmap") as? Bitmap)
        if (directBmp != null && !directBmp.isRecycled && directBmp.width >= 120 && directBmp.height >= 120) {
            result.add(directBmp.copy(Bitmap.Config.ARGB_8888, true))
            return
        }

        // 3. 若为具备有效尺寸且类名明确属于图片主图的自定义绘制控件，通过 Canvas 生成快照
        if ((viewClassName.contains("pic") || viewClassName.contains("image")) &&
            !viewClassName.contains("reply") && !viewClassName.contains("quote") &&
            view.width >= 120 && view.height >= 120 && view !is ViewGroup) {
            val snap = runCatching {
                val s = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(s)
                view.draw(canvas)
                s
            }.getOrNull()
            if (snap != null) {
                result.add(snap)
                return
            }
        }

        // 4. 若为 ViewGroup，递归遍历所有子控件收集所有图片
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                findAllBitmapsFromViewHierarchy(child, result)
            }
        }
    }

    /**
     * 从 Drawable 对象中提取或渲染出 Bitmap 副本。
     *
     * Args:
     *     drawable (Drawable): 目标可绘制对象。
     *     fallbackWidth (Int): 备用宽度（当 Drawable 无固有宽度时使用）。
     *     fallbackHeight (Int): 备用高度（当 Drawable 无固有高度时使用）。
     *
     * Returns:
     *     Bitmap?: 提炼出的位图对象，失败返回 null。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.findBitmapFromViewHierarchy`
     */
    private fun extractBitmapFromDrawable(drawable: Drawable, fallbackWidth: Int, fallbackHeight: Int): Bitmap? {
        if (drawable is BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
            return runCatching {
                drawable.bitmap.copy(Bitmap.Config.ARGB_8888, true)
            }.getOrNull()
        }

        val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else fallbackWidth
        val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else fallbackHeight
        if (w <= 0 || h <= 0) return null

        return runCatching {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val oldBounds = drawable.bounds
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            drawable.bounds = oldBounds
            bmp
        }.getOrNull()
    }

    /**
     * 将 Bitmap 压缩为高质量 JPEG 格式并持久化存储至应用私有缓存目录。
     *
     * Args:
     *     context (Context): Android 上下文环境。
     *     bitmap (Bitmap): 待保存的位图对象。
     *
     * Returns:
     *     String: 保存成功后的本地绝对文件路径。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.extractImageFromComponent`
     */
    private fun saveBitmapToCache(context: Context, bitmap: Bitmap): String {
        val cacheDir = File(context.cacheDir, "qai_images").apply { if (!exists()) mkdirs() }
        val targetFile = File(cacheDir, "qai_comp_${System.currentTimeMillis()}.jpg")
        FileOutputStream(targetFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
            fos.flush()
        }
        return targetFile.absolutePath
    }



    /**
     * 递归检索指定对象及其所有基类中声明的目标字段。
     *
     * 解决常规 getDeclaredField 无法检索父类 private 字段的问题。
     *
     * Args:
     *     obj (Any?): 待检索的目标对象实例。
     *     fieldName (String): 目标字段名称。
     *
     * Returns:
     *     Any?: 字段的值，若不存在或读取失败则返回 null。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.extractMessagePayload`
     *     - `AioMenuHook.kt:AioMenuHook.extractMsgRecord`
     *     - `AioMenuHook.kt:AioMenuHook.extractViewFromComponent`
     */
    private fun getFieldValueRecursive(obj: Any?, fieldName: String): Any? {
        if (obj == null) return null
        var curClazz: Class<*>? = obj.javaClass
        while (curClazz != null && curClazz != Any::class.java) {
            try {
                val field = curClazz.declaredFields.firstOrNull { it.name == fieldName }
                if (field != null) {
                    field.isAccessible = true
                    return field.get(obj)
                }
            } catch (_: Throwable) {
            }
            curClazz = curClazz.superclass
        }
        return null
    }

    /**
     * 从 AIOMsgItem 及其关联数据模型中提取结构化文本内容。
     *
     * 优先针对合并转发消息项直接读取其已解析的标题与条目；
     * 随后穿透遍历 MsgRecord 中的全部 MsgElement 并执行精细化解析。
     *
     * Args:
     *     msgItem (Any): AIOMsgItem 实例。
     *
     * Returns:
     *     String?: 提炼出的格式化消息文本，若为空则返回 null。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.extractMessagePayload`
     *     - `AioMenuHook.kt:AioMenuHook.extractSingleMessageText`
     */
    private fun extractStructuredTextFromMsgItem(msgItem: Any): String? {
        // 1. 优先针对合并转发消息项（y 及其子类或实现类）提取结构化列表
        val multiForwardText = extractMultiForwardFromMsgItem(msgItem)
        if (!multiForwardText.isNullOrBlank()) {
            return multiForwardText
        }

        // 2. 深入遍历 MsgRecord 中的元素集合
        val msgRecord = extractMsgRecord(msgItem)
        if (msgRecord != null) {
            val elements = getFieldValueRecursive(msgRecord, "elements") as? List<*>
            if (!elements.isNullOrEmpty()) {
                val sb = StringBuilder()
                for (element in elements) {
                    if (element == null) continue
                    val elemText = extractElementText(element)
                    if (!elemText.isNullOrBlank()) {
                        sb.append(elemText)
                    }
                }
                val result = sb.toString().trim()
                if (result.isNotEmpty()) {
                    return result
                }
            }
        }

        // 3. 尝试调用 MsgItem 原生概要方法（过滤掉泛型占位符）
        val rawSummary = extractRawTextFromMsgItem(msgItem)?.trim()
        if (!rawSummary.isNullOrBlank() &&
            rawSummary != "[聊天记录]" && rawSummary != "[合并转发]" && rawSummary != "[卡片消息]" &&
            rawSummary != "聊天记录" && rawSummary != "合并转发" && rawSummary != "卡片消息") {
            return rawSummary
        }
        return null
    }

    /**
     * 针对 QQNT 合并转发专用消息项（com.tencent.mobileqq.aio.msg.y）提取结构化转发内容。
     *
     * 穿透反射调用 y.getTitle() 获取卡片主标题，调用 y.M2() 遍历内部已解码的
     * 结构化元素（com.tencent.qqnt.structmsg.element.a），通过 a.b().a() 获取每条记录的文本。
     *
     * Args:
     *     msgItem (Any): 待提取的 AIOMsgItem 实例。
     *
     * Returns:
     *     String?: 格式化后的多行合并转发文本，非合并转发项或解析无内容时返回 null。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.extractStructuredTextFromMsgItem`
     */
    private fun extractMultiForwardFromMsgItem(msgItem: Any): String? {
        val m2Method = msgItem.javaClass.methods.firstOrNull { it.name == "M2" && it.parameterTypes.isEmpty() }
            ?: msgItem.javaClass.declaredMethods.firstOrNull { it.name == "M2" && it.parameterTypes.isEmpty() }?.apply { isAccessible = true }
            ?: return null

        @Suppress("UNCHECKED_CAST")
        val elementList = runCatching { m2Method.invoke(msgItem) as? List<Any> }.getOrNull()
        if (elementList.isNullOrEmpty()) return null

        val titleMethod = msgItem.javaClass.methods.firstOrNull { it.name == "getTitle" && it.parameterTypes.isEmpty() }
            ?: msgItem.javaClass.declaredMethods.firstOrNull { it.name == "getTitle" && it.parameterTypes.isEmpty() }?.apply { isAccessible = true }
        val cardTitle = runCatching { titleMethod?.invoke(msgItem) as? String }.getOrNull()?.trim()

        val textLines = mutableListOf<String>()
        for (element in elementList) {
            val bMethod = element.javaClass.methods.firstOrNull { it.name == "b" && it.parameterTypes.isEmpty() }
                ?: element.javaClass.declaredMethods.firstOrNull { it.name == "b" && it.parameterTypes.isEmpty() }?.apply { isAccessible = true }
            val bObj = runCatching { bMethod?.invoke(element) }.getOrNull()
            if (bObj != null) {
                val aMethod = bObj.javaClass.methods.firstOrNull { it.name == "a" && it.parameterTypes.isEmpty() && it.returnType == String::class.java }
                    ?: bObj.javaClass.declaredMethods.firstOrNull { it.name == "a" && it.parameterTypes.isEmpty() && it.returnType == String::class.java }?.apply { isAccessible = true }
                val lineText = runCatching { aMethod?.invoke(bObj) as? String }.getOrNull()?.trim()
                if (!lineText.isNullOrBlank()) {
                    textLines.add(lineText)
                }
            }
        }

        if (textLines.isEmpty() && cardTitle.isNullOrBlank()) return null

        val sb = StringBuilder()
        val finalTitle = if (!cardTitle.isNullOrBlank()) cardTitle else (textLines.firstOrNull() ?: "聊天记录")
        sb.append("[合并转发: ").append(finalTitle).append("]\n")
        val contentLines = if (textLines.isNotEmpty() && textLines[0] == finalTitle) textLines.drop(1) else textLines
        for (line in contentLines) {
            sb.append(line).append("\n")
        }
        return sb.toString().trim()
    }

    /**
     * 尝试从 MsgItem 对象自带的概要方法中提取预览文本。
     *
     * Args:
     *     msgItem (Any): AIOMsgItem 实例。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.extractStructuredTextFromMsgItem`
     */
    private fun extractRawTextFromMsgItem(msgItem: Any): String? {
        val methodNames = listOf("getSummary", "getMsgSummary", "getContentSummary", "getMsgContent", "getMsgText")
        for (name in methodNames) {
            try {
                val method = msgItem.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
                val result = method?.invoke(msgItem)
                if (result is String && result.isNotBlank()) {
                    return result
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }
}
