package io.github.meiyongai.qai.hook

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

object AioInputHelper {

    fun init(xposed: XposedInterface, classLoader: ClassLoader) {
        // 当前 Activity 通过 ActivityThread 反射机制动态精准获取，无需繁琐生命周期 hook
    }

    fun getCurrentActivity(): Activity? {
        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread", false, Application::class.java.classLoader)
            val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val activitiesField = activityThreadClass.getDeclaredField("mActivities").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val activities = activitiesField.get(activityThread) as? Map<*, *> ?: return null

            for (record in activities.values) {
                if (record == null) continue
                val recordClass = record.javaClass
                val pausedField = recordClass.getDeclaredField("paused").apply { isAccessible = true }
                if (!pausedField.getBoolean(record)) {
                    val activityField = recordClass.getDeclaredField("activity").apply { isAccessible = true }
                    return activityField.get(record) as? Activity
                }
            }
            null
        }.getOrNull()
    }

    /**
     * 底层直接发送聊天消息（绕过输入框与界面模拟点击）。
     *
     * 获取 QQNT 内核消息服务实例（IKernelMsgService），
     * 构造包含 TextElement 以及可选 ReplyElement 的 MsgElement 列表，
     * 直接向指定 Contact 会话发起底层静默消息投递。
     *
     * Args:
     *     context (Context): Android 上下文环境。
     *     contact (Any): 目标会话 Contact 实例。
     *     text (String): 待发送的文本内容。
     *     replyMsgItem (Any?): 可选的被引用消息项（AIOMsgItem）。
     *     targetSenderNick (String?): 可选的目标成员展示昵称。
     *
     * Returns:
     *     Boolean: true 表示成功向底层服务提交发送请求，false 表示提交失败。
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog:btnSend.setOnClickListener`
     */
    fun sendDirectMessage(
        context: Context,
        contact: Any,
        text: String,
        replyMsgItem: Any? = null,
        targetSenderNick: String? = null
    ): Boolean {
        try {
            val classLoader = context.classLoader
            val msgService = getKernelMsgService(context)
            if (msgService == null) {
                android.util.Log.e("QAI_DirectSend", "KernelMsgService instance is null")
                return false
            }

            val msgElementClass = classLoader.loadClass("com.tencent.qqnt.kernel.nativeinterface.MsgElement")
            val textElementClass = classLoader.loadClass("com.tencent.qqnt.kernel.nativeinterface.TextElement")
            val elementsList = ArrayList<Any>()

            var targetUid = ""
            var targetUin = 0L
            var effectiveTargetNick = targetSenderNick

            // 1. 若提供了被引用消息项，提取目标成员信息并构造 ReplyElement 插入首位
            if (replyMsgItem != null) {
                val replyRecord = AioMenuHook.extractMsgRecord(replyMsgItem)
                if (replyRecord != null) {
                    val replyElementClass = classLoader.loadClass("com.tencent.qqnt.kernel.nativeinterface.ReplyElement")
                    val replyElem = replyElementClass.getConstructor().newInstance()

                    val msgId = (getFieldValue(replyRecord, "msgId") as? Long) ?: 0L
                    val msgSeq = (getFieldValue(replyRecord, "msgSeq") as? Long) ?: 0L
                    val senderUid = (getFieldValue(replyRecord, "senderUid") as? String) ?: ""
                    val senderUin = (getFieldValue(replyRecord, "senderUin") as? Long)
                        ?: (getFieldValue(replyRecord, "senderUin")?.toString()?.toLongOrNull() ?: 0L)
                    val sourceSummary = AioMenuHook.extractSingleMessageText(replyMsgItem)

                    targetUid = senderUid
                    targetUin = senderUin
                    if (effectiveTargetNick.isNullOrBlank()) {
                        effectiveTargetNick = AioMenuHook.extractSenderName(replyMsgItem, replyRecord)
                    }

                    setFieldValue(replyElem, "replayMsgId", msgId)
                    setFieldValue(replyElem, "replayMsgSeq", msgSeq)
                    setFieldValue(replyElem, "senderUidStr", senderUid)
                    setFieldValue(replyElem, "sourceMsgText", sourceSummary)
                    setFieldValue(replyElem, "sourceMsgIdInRecords", msgId)

                    val replyMsgElement = msgElementClass.getConstructor().newInstance()
                    setFieldValue(replyMsgElement, "elementType", 7)
                    setFieldValue(replyMsgElement, "replyElement", replyElem)
                    elementsList.add(replyMsgElement)
                }
            }

            // 2. 切片解析文本中的 @ 成员标记
            val segments = QQRichTextParser.parseRichText(
                rawText = text,
                targetUid = targetUid.takeIf { it.isNotEmpty() },
                targetUin = targetUin.takeIf { it > 0L },
                targetNick = effectiveTargetNick
            )

            if (segments.isEmpty() && text.isNotEmpty()) {
                val textElem = textElementClass.getConstructor().newInstance()
                setFieldValue(textElem, "content", text)
                val textMsgElement = msgElementClass.getConstructor().newInstance()
                setFieldValue(textMsgElement, "elementType", 1)
                setFieldValue(textMsgElement, "textElement", textElem)
                elementsList.add(textMsgElement)
            } else {
                for (seg in segments) {
                    when (seg) {
                        is QQRichTextParser.RichSegment.Text -> {
                            val textElem = textElementClass.getConstructor().newInstance()
                            setFieldValue(textElem, "content", seg.text)
                            val textMsgElement = msgElementClass.getConstructor().newInstance()
                            setFieldValue(textMsgElement, "elementType", 1)
                            setFieldValue(textMsgElement, "textElement", textElem)
                            elementsList.add(textMsgElement)
                        }
                        is QQRichTextParser.RichSegment.At -> {
                            val atElem = textElementClass.getConstructor().newInstance()
                            setFieldValue(atElem, "content", seg.displayContent)
                            setFieldValue(atElem, "atType", seg.atType)
                            if (seg.uid.isNotEmpty()) {
                                setFieldValue(atElem, "atNtUid", seg.uid)
                                setFieldValue(atElem, "atUid", seg.uid)
                            }
                            if (seg.uin > 0L) {
                                setFieldValue(atElem, "atTinyId", seg.uin)
                            }

                            val atMsgElement = msgElementClass.getConstructor().newInstance()
                            setFieldValue(atMsgElement, "elementType", 1)
                            setFieldValue(atMsgElement, "textElement", atElem)
                            elementsList.add(atMsgElement)
                        }
                    }
                }
            }

            // 3. 计算唯一消息 ID 并分发给底层服务
            val chatType = (getFieldValue(contact, "chatType") as? Int) ?: 1
            val generateMethod = msgService.javaClass.methods.firstOrNull { method: Method ->
                method.name == "generateMsgUniqueId" && method.parameterTypes.size == 1 && method.parameterTypes[0] == java.lang.Integer.TYPE
            }
            val uniqueId = (generateMethod?.invoke(msgService, chatType) as? Long) ?: 0L

            val sendMsgMethod = msgService.javaClass.methods.firstOrNull { method: Method ->
                method.name == "sendMsg" && method.parameterTypes.size == 5
            }
            if (sendMsgMethod != null) {
                sendMsgMethod.invoke(msgService, uniqueId, contact, elementsList, HashMap<Int, Any>(), null)
                android.util.Log.i("QAI_DirectSend", "sendDirectMessage via sendMsg succeeded, uniqueId=$uniqueId, elements=${elementsList.size}")
                return true
            }

            val addSendMsgMethod = msgService.javaClass.methods.firstOrNull { method: Method ->
                method.name == "addSendMsg" && method.parameterTypes.size == 4
            }
            if (addSendMsgMethod != null) {
                addSendMsgMethod.invoke(msgService, uniqueId, contact, elementsList, HashMap<Int, Any>())
                android.util.Log.i("QAI_DirectSend", "sendDirectMessage via addSendMsg succeeded, uniqueId=$uniqueId, elements=${elementsList.size}")
                return true
            }

            android.util.Log.e("QAI_DirectSend", "Neither sendMsg nor addSendMsg method was found on IKernelMsgService")
            return false
        } catch (e: Throwable) {
            android.util.Log.e("QAI_DirectSend", "sendDirectMessage execution failed: ${e.message}", e)
            return false
        }
    }

    /**
     * 获取 QQNT 内核消息服务实例（IKernelMsgService）。
     *
     * 优先通过 KernelServiceUtil.getMsgService 反射获取，失败后降级为 AppRuntime.getRuntimeService。
     *
     * Args:
     *     context (Context): Android 上下文环境。
     *
     * Returns:
     *     Any?: 内核消息服务实例，获取失败返回 null。
     *
     * Callers:
     *     - `AioInputHelper.kt:AioInputHelper.sendDirectMessage`
     */
    private fun getKernelMsgService(context: Context): Any? {
        val classLoader = context.classLoader
        val utilClasses = listOf("com.tencent.qqnt.msg.f", "com.tencent.qqnt.msg.KernelServiceUtil")
        for (cName in utilClasses) {
            try {
                val clazz = classLoader.loadClass(cName)
                val m = clazz.methods.firstOrNull { method: Method ->
                    (method.name == "f" || method.name == "getMsgService") && method.parameterTypes.isEmpty()
                }
                val service = m?.invoke(null)
                if (service != null) return service
            } catch (_: ClassNotFoundException) {
            } catch (_: NoSuchMethodException) {
            }
        }

        try {
            val mobileQQClass = classLoader.loadClass("mqq.app.MobileQQ")
            val getMobileQQMethod = mobileQQClass.getMethod("getMobileQQ")
            val mobileQQ = getMobileQQMethod.invoke(null) ?: return null
            val peekAppRuntimeMethod = mobileQQClass.getMethod("peekAppRuntime")
            val app = peekAppRuntimeMethod.invoke(mobileQQ) ?: return null
            val iKernelServiceClass = classLoader.loadClass("com.tencent.qqnt.kernel.api.IKernelService")
            val getRuntimeServiceMethod = app.javaClass.getMethod("getRuntimeService", java.lang.Class::class.java, String::class.java)
            val kernelService = getRuntimeServiceMethod.invoke(app, iKernelServiceClass, "") ?: return null
            val getMsgServiceMethod = kernelService.javaClass.getMethod("getMsgService")
            return getMsgServiceMethod.invoke(kernelService)
        } catch (e: Throwable) {
            android.util.Log.e("QAI_DirectSend", "Failed to retrieve IKernelMsgService via AppRuntime", e)
            return null
        }
    }

    /**
     * 递归检索对象及其基类中的指定字段值。
     *
     * Args:
     *     obj (Any): 目标对象实例。
     *     fieldName (String): 字段名称。
     *
     * Returns:
     *     Any?: 字段值，未找到返回 null。
     *
     * Callers:
     *     - `AioInputHelper.kt:AioInputHelper.sendDirectMessage`
     */
    private fun getFieldValue(obj: Any, fieldName: String): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            try {
                val f = c.getDeclaredField(fieldName)
                f.isAccessible = true
                return f.get(obj)
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }

    /**
     * 递归写入对象及其基类中的指定字段值。
     *
     * Args:
     *     obj (Any): 目标对象实例。
     *     fieldName (String): 字段名称。
     *     value (Any?): 待写入的属性值。
     *
     * Callers:
     *     - `AioInputHelper.kt:AioInputHelper.sendDirectMessage`
     */
    private fun setFieldValue(obj: Any, fieldName: String, value: Any?) {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            try {
                val f = c.getDeclaredField(fieldName)
                f.isAccessible = true
                f.set(obj, value)
                return
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
    }

    /**
     * 将指定文本填入聊天输入框
     *
     * 遍历当前顶层 Activity 的视图树寻找显示的输入框，并将文本追加或填入其中。
     * 自动将特殊协议标记（如 CQ 码）标准化为自然的表情中文字符与 @ 文本。
     *
     * Args:
     *     text (String): 待填入的文本内容。
     *     targetNick (String?): 目标成员展示昵称（可选）。
     *
     * Returns:
     *     Boolean: true 表示成功定位并填入输入框，false 表示未能找到输入框。
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog:btnInsert.setOnClickListener`
     */
    fun insertTextToInput(text: String, targetNick: String? = null): Boolean {
        val activity = getCurrentActivity() ?: return false
        val decor = activity.window?.decorView ?: return false
        val editText = findChatEditText(decor) ?: return false

        val normalized = QQRichTextParser.normalizeForInputBox(text, targetNick)
        editText.post {
            val origin = editText.text?.toString() ?: ""
            editText.setText(if (origin.isEmpty()) normalized else "$origin $normalized")
            editText.setSelection(editText.text?.length ?: 0)
            editText.requestFocus()
        }
        return true
    }

    /**
     * 发送文本到当前聊天窗口
     *
     * 支持可选的引用原消息动作；将生成的文本填入聊天输入框并触发界面发送按钮或输入法发送动作。
     * 自动将特殊协议标记（如 CQ 码）标准化为自然的表情中文字符与 @ 文本。
     *
     * Args:
     *     activity (Activity): 承载聊天界面的 Activity。
     *     text (String): 待发送的文本内容。
     *     quoteAction (() -> Unit)?: 可选的引用动作，在填入文本前触发 QQ 原生引用模式。
     *     targetNick (String?): 目标成员展示昵称（可选）。
     *
     * Returns:
     *     Boolean: true 表示操作已提交执行，false 表示未定位到当前窗口。
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog:btnSend.setOnClickListener`
     */
    fun sendText(
        activity: Activity,
        text: String,
        quoteAction: (() -> Unit)? = null,
        targetNick: String? = null
    ): Boolean {
        val decor = activity.window?.decorView ?: return false
        val normalized = QQRichTextParser.normalizeForInputBox(text, targetNick)

        activity.runOnUiThread {
            if (quoteAction != null) {
                // 1. 若配置并提供了引用动作，先在主线程执行引用回复
                quoteAction.invoke()

                // 2. 延迟 200ms 等待 QQ 引用挂载条与输入焦点就绪后再填入文字并触发发送
                decor.postDelayed({
                    val editText = findChatEditText(decor)
                    if (editText != null) {
                        editText.setText(normalized)
                        editText.setSelection(normalized.length)
                        editText.requestFocus()

                        editText.postDelayed({
                            val sentByButton = clickSendButton(decor)
                            if (!sentByButton) {
                                editText.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
                            }
                        }, 120)
                    }
                }, 200)
            } else {
                // 无引用动作，直接填入输入框并触发发送
                val editText = findChatEditText(decor)
                if (editText != null) {
                    editText.setText(normalized)
                    editText.setSelection(normalized.length)
                    editText.requestFocus()

                    editText.postDelayed({
                        val sentByButton = clickSendButton(decor)
                        if (!sentByButton) {
                            editText.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
                        }
                    }, 120)
                }
            }
        }
        return true
    }

    /**
     * 递归遍历视图树寻找并触发发送按钮的点击
     *
     * 优先匹配按钮文本或 contentDescription 为"发送"的可点击 View，或 ID 包含"send"的 View。
     *
     * Args:
     *     view (View): 起始遍历根视图。
     *
     * Returns:
     *     Boolean: true 表示已找到并触发点击，false 表示未找到。
     *
     * Callers:
     *     - `AioInputHelper:sendText`
     */
    private fun clickSendButton(view: View): Boolean {
        if (view.isShown && view.isClickable) {
            val desc = view.contentDescription?.toString() ?: ""
            if (desc == "发送") {
                view.performClick()
                return true
            }
            if (view is android.widget.TextView && view.text.toString() == "发送") {
                view.performClick()
                return true
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (clickSendButton(view.getChildAt(i))) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * 在视图树中寻找当前可见的聊天 EditText
     *
     * 递归深度优先遍历查找显示的 EditText 实例。
     *
     * Args:
     *     view (View): 当前检查的 View 节点。
     *
     * Returns:
     *     EditText?: 查找到的输入框实例，未找到返回 null。
     *
     * Callers:
     *     - `AioInputHelper:insertTextToInput`
     *     - `AioInputHelper:sendText`
     */
    private fun findChatEditText(view: View): EditText? {
        if (view is EditText && view.isShown) {
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findChatEditText(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }
}
