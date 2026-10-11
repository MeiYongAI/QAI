package io.github.meiyongai.qai.ui

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.meiyongai.qai.R
import io.github.meiyongai.qai.hook.AioInputHelper
import io.github.meiyongai.qai.llm.config.LlmConfig
import io.github.meiyongai.qai.llm.net.StreamChatClient
import io.github.meiyongai.qai.llm.utils.ModelUtil

class AiReplyBottomSheetDialog(
    private val activity: Activity,
    private val originPrompt: String,
    private val imagePaths: List<String> = emptyList(),
    private val quoteAction: (() -> Unit)? = null,
    private val contact: Any? = null,
    private val targetMsgItem: Any? = null
) : Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar) {

    constructor(
        activity: Activity,
        originPrompt: String,
        imagePath: String?,
        quoteAction: (() -> Unit)? = null,
        contact: Any? = null,
        targetMsgItem: Any? = null
    ) : this(
        activity = activity,
        originPrompt = originPrompt,
        imagePaths = if (imagePath != null) listOf(imagePath) else emptyList(),
        quoteAction = quoteAction,
        contact = contact,
        targetMsgItem = targetMsgItem
    )

    private val fullResponse = StringBuilder()
    private val reasoningBuilder = StringBuilder()
    private var reasoningStartTime = 0L
    private var reasoningSpentSeconds = 0L
    private var reasoningExpanded = false

    private var streamClient: StreamChatClient? = null

    // 推理 UI 组件
    private lateinit var reasoningBlock: LinearLayout
    private lateinit var tvReasoningTitle: TextView
    private lateinit var ivReasoningChevron: ImageView
    private lateinit var reasoningScroll: ScrollView
    private lateinit var tvReasoningContent: TextView

    // 候选回复与正文 UI 组件
    private var selectedCandidateIndex = 0
    private val candidateCards = ArrayList<LinearLayout>()
    private val candidateIndicators = ArrayList<TextView>()
    private val candidateTexts = ArrayList<TextView>()
    private lateinit var contentContainer: LinearLayout
    private lateinit var tvContent: TextView
    private lateinit var loadingView: ProgressBar
    private lateinit var scrollView: ScrollView
    private lateinit var generatingActionLayout: LinearLayout
    private lateinit var completedActionLayout: LinearLayout
    private lateinit var tvModeToggle: TextView

    // 动画控制组件
    private lateinit var sheetLayout: LinearLayout
    private lateinit var maskView: View
    private var isDismissing = false

    init {
        val isNight = isNightMode()
        val colorSheetBg = if (isNight) Color.parseColor("#242424") else Color.parseColor("#FFFFFF")
        window?.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0f)
            setWindowAnimations(0)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                navigationBarColor = colorSheetBg
                var flags = decorView.systemUiVisibility
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags = if (!isNight) {
                        flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    } else {
                        flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                    }
                }
                decorView.systemUiVisibility = flags
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    isNavigationBarContrastEnforced = false
                }
            }
        }
        setCanceledOnTouchOutside(true)

        val contentView = buildDialogView()
        setContentView(contentView)

        setOnDismissListener {
            streamClient?.cancel()
        }
    }

    /**
     * 显示对话框并启动底部面板平滑上滑展开动画与背景蒙层淡入动画。
     *
     * Callers:
     *     - `AioMenuHook.kt:AioMenuHook.init`
     */
    override fun show() {
        super.show()
        sheetLayout.visibility = View.INVISIBLE
        sheetLayout.post {
            val startY = if (sheetLayout.height > 0) sheetLayout.height.toFloat() else dp(500).toFloat()
            sheetLayout.translationY = startY
            sheetLayout.visibility = View.VISIBLE
            maskView.animate()
                .alpha(1f)
                .setDuration(260)
                .start()
            sheetLayout.animate()
                .translationY(0f)
                .setDuration(280)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.2f))
                .start()
        }
    }

    /**
     * 拦截退出事件，执行底部面板平滑下滑收起动画与背景蒙层渐隐动画。
     *
     * 保证在点击设置、插入、发送、遮罩区域或返回键时均有一致且丝滑的退场动效。
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.buildDialogView`
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.onBackPressed`
     */
    override fun dismiss() {
        if (isDismissing) return
        isDismissing = true
        streamClient?.cancel()
        if (::sheetLayout.isInitialized && sheetLayout.isAttachedToWindow) {
            val targetY = if (sheetLayout.height > 0) sheetLayout.height.toFloat() else dp(500).toFloat()
            if (::maskView.isInitialized) {
                maskView.animate()
                    .alpha(0f)
                    .setDuration(220)
                    .start()
            }
            sheetLayout.animate()
                .translationY(targetY)
                .setDuration(220)
                .setInterpolator(android.view.animation.AccelerateInterpolator(1.2f))
                .withEndAction {
                    super.dismiss()
                }
                .start()
        } else {
            super.dismiss()
        }
    }

    /**
     * 响应系统物理返回键，统一触发平滑退场动画。
     *
     * Callers:
     *     - 系统事件回调
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        dismiss()
    }

    /**
     * 将独立像素密度值（dp）换算为屏幕实际物理像素值（px）。
     *
     * Args:
     *     dp (Int): 待转换的 dp 数值。
     *
     * Returns:
     *     Int: 对应的物理像素整型值。
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.buildDialogView`
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.show`
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.dismiss`
     */
    private fun dp(dp: Int): Int {
        return (dp * activity.resources.displayMetrics.density).toInt()
    }

    /**
     * 动态绘制指定颜色与线宽的展开/收起箭头矢量 Drawable。
     *
     * 解决 Xposed 宿主进程无法通过模块 R.drawable 资源 ID 查找本地矢量图导致展示损坏矩形的问题。
     *
     * Args:
     *     color (Int): 箭头的绘制颜色。
     *     strokeWidth (Float): 箭头的线条粗细（px）。
     *
     * Returns:
     *     Drawable: 动态生成的箭头 Drawable。
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.buildDialogView`
     */
    private fun createChevronDrawable(color: Int, strokeWidth: Float): Drawable {
        return object : Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                this.style = android.graphics.Paint.Style.STROKE
                this.strokeWidth = strokeWidth
                this.strokeCap = android.graphics.Paint.Cap.ROUND
                this.strokeJoin = android.graphics.Paint.Join.ROUND
            }
            private val path = android.graphics.Path()

            override fun draw(canvas: android.graphics.Canvas) {
                val b = bounds
                val w = b.width().toFloat()
                val h = b.height().toFloat()
                if (w <= 0f || h <= 0f) return

                path.reset()
                path.moveTo(b.left + w * 0.35f, b.top + h * 0.2f)
                path.lineTo(b.left + w * 0.65f, b.top + h * 0.5f)
                path.lineTo(b.left + w * 0.35f, b.top + h * 0.8f)
                canvas.drawPath(path, paint)
            }

            override fun setAlpha(alpha: Int) {
                paint.alpha = alpha
            }

            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
                paint.colorFilter = colorFilter
            }

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    /**
     * 动态绘制指定颜色的复制图标矢量 Drawable。
     *
     * 解决宿主资源缺失导致回退加载模块资源失效的问题。
     *
     * Args:
     *     color (Int): 绘制颜色。
     *     strokeWidth (Float): 线条粗细（px）。
     *
     * Returns:
     *     Drawable: 动态生成的复制图标 Drawable。
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.buildDialogView`
     */
    private fun createCopyDrawable(color: Int, strokeWidth: Float): Drawable {
        return object : Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                this.style = android.graphics.Paint.Style.STROKE
                this.strokeWidth = strokeWidth
                this.strokeCap = android.graphics.Paint.Cap.ROUND
                this.strokeJoin = android.graphics.Paint.Join.ROUND
            }

            override fun draw(canvas: android.graphics.Canvas) {
                val b = bounds
                val w = b.width().toFloat()
                val h = b.height().toFloat()
                if (w <= 0f || h <= 0f) return

                val r = dp(2).toFloat()
                val bgPath = android.graphics.Path().apply {
                    moveTo(b.left + w * 0.38f, b.top + h * 0.18f)
                    lineTo(b.left + w * 0.78f, b.top + h * 0.18f)
                    lineTo(b.left + w * 0.78f, b.top + h * 0.65f)
                }
                canvas.drawPath(bgPath, paint)

                val fgRect = android.graphics.RectF(
                    b.left + w * 0.22f,
                    b.top + h * 0.32f,
                    b.left + w * 0.66f,
                    b.top + h * 0.82f
                )
                canvas.drawRoundRect(fgRect, r, r, paint)
            }

            override fun setAlpha(alpha: Int) {
                paint.alpha = alpha
            }

            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
                paint.colorFilter = colorFilter
            }

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    /**
     * 检查当前系统与界面是否处于深色/夜间主题模式。
     *
     * Returns:
     *     Boolean: 若处于深色模式返回 true，否则返回 false。
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.init`
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.buildDialogView`
     */
    private fun isNightMode(): Boolean {
        return (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * 动态渲染多项候选回复卡片列表。
     *
     * 依据大模型流式解析出的实际候选数量动态增删与复用候选卡片，
     * 突破固化卡片数量限制，自适应渲染任意条数的候选卡片及指示圆点。
     *
     * Args:
     *     candidates (List<String>): 候选回复文本列表。
     *     colorCardBg (Int): 卡片背景色。
     *     colorAccent (Int): 主题强调色。
     *     colorTextPrimary (Int): 主要文本颜色。
     *     colorTextSecondary (Int): 次级文本颜色。
     *     isNight (Boolean): 是否处于深色模式。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.startAiRequest`
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.restartAiRequest`
     */
    private fun renderCandidateCards(
        candidates: List<String>,
        colorCardBg: Int,
        colorAccent: Int,
        colorTextPrimary: Int,
        colorTextSecondary: Int,
        isNight: Boolean
    ) {
        if (selectedCandidateIndex !in candidates.indices) {
            selectedCandidateIndex = 0
        }

        // 动态创建卡片直到满足候选条数
        while (candidateCards.size < candidates.size) {
            val index = candidateCards.size
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedCandidateIndex = index
                    updateCandidateCardsSelection(colorCardBg, colorAccent, colorTextSecondary, isNight)
                }
            }

            val indicator = TextView(activity).apply {
                text = "○ 候选 ${index + 1}"
                textSize = 12f
                setTextColor(colorTextSecondary)
                includeFontPadding = false
            }
            card.addView(indicator)

            val tvCandidate = TextView(activity).apply {
                textSize = 14.5f
                setTextColor(colorTextPrimary)
                setLineSpacing(dp(3).toFloat(), 1f)
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(5)
                }
            }
            card.addView(tvCandidate)

            candidateCards.add(card)
            candidateIndicators.add(indicator)
            candidateTexts.add(tvCandidate)
            contentContainer.addView(card)
        }

        // 填充文本并切换可见性
        for (i in candidateCards.indices) {
            if (i < candidates.size) {
                candidateTexts[i].text = candidates[i]
                candidateCards[i].visibility = View.VISIBLE
            } else {
                candidateCards[i].visibility = View.GONE
            }
        }

        updateCandidateCardsSelection(colorCardBg, colorAccent, colorTextSecondary, isNight)
    }

    /**
     * 刷新候选卡片的选中状态样式。
     *
     * 遍历所有已激活的候选卡片，根据 selectedCandidateIndex 为选中的卡片应用主题色外边框和实心指示圆点，
     * 为未选中的卡片应用浅色细边框与空心圆点。
     *
     * Args:
     *     colorCardBg (Int): 卡片底色。
     *     colorAccent (Int): 主题强调色。
     *     colorTextSecondary (Int): 次级文本颜色。
     *     isNight (Boolean): 是否处于深色模式。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.renderCandidateCards`
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.restartAiRequest`
     *     - `AiReplyBottomSheetDialog.kt:candidateCard.setOnClickListener`
     */
    private fun updateCandidateCardsSelection(colorCardBg: Int, colorAccent: Int, colorTextSecondary: Int, isNight: Boolean) {
        for (i in candidateCards.indices) {
            val isSelected = i == selectedCandidateIndex
            val card = candidateCards[i]
            card.background = GradientDrawable().apply {
                setColor(colorCardBg)
                cornerRadius = dp(8).toFloat()
                if (isSelected) {
                    setStroke(dp(2), colorAccent)
                } else {
                    setStroke(dp(1), if (isNight) Color.parseColor("#28FFFFFF") else Color.parseColor("#14000000"))
                }
            }
            val indicator = candidateIndicators[i]
            indicator.text = if (isSelected) "● 候选 ${i + 1}" else "○ 候选 ${i + 1}"
            indicator.setTextColor(if (isSelected) colorAccent else colorTextSecondary)
            indicator.typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    /**
     * 获取当前需要执行复制、填入输入框或直接发送的目标回复文本。
     *
     * 在单条回复模式下直接返回完整响应文本；
     * 在多项候选模式下，解析出的候选列表中若包含选中项，则返回当前用户选中的该条候选回复。
     *
     * Returns:
     *     String: 目标回复文本字符串。
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog.kt:btnCopy.setOnClickListener`
     *     - `AiReplyBottomSheetDialog.kt:btnInsert.setOnClickListener`
     *     - `AiReplyBottomSheetDialog.kt:btnSend.setOnClickListener`
     */
    private fun getTargetTextToOperate(): String {
        val raw = fullResponse.toString()
        if (!LlmConfig.isMultiChoiceEnabled(activity)) {
            return raw
        }
        val candidates = LlmConfig.parseCandidates(raw)
        return if (candidates.isNotEmpty() && selectedCandidateIndex in candidates.indices) {
            candidates[selectedCandidateIndex]
        } else if (candidates.isNotEmpty()) {
            candidates[0]
        } else {
            raw
        }
    }

    /**
     * 重置界面状态并重新向大模型发起流式生成请求。
     *
     * 用于在用户点击切换「单条回复 / 3条候选」模式时重新生成。
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog.kt:tvModeToggle.setOnClickListener`
     */
    private fun restartAiRequest() {
        streamClient?.cancel()
        fullResponse.clear()
        reasoningBuilder.clear()
        reasoningSpentSeconds = 0L
        reasoningStartTime = 0L
        selectedCandidateIndex = 0

        val isMultiChoice = LlmConfig.isMultiChoiceEnabled(activity)
        val isNight = isNightMode()
        val colorCardBg = if (isNight) Color.parseColor("#1C1C1E") else Color.parseColor("#F5F6F8")
        val colorAccent = if (isNight) Color.parseColor("#38ACFF") else Color.parseColor("#0099FF")
        val colorTextSecondary = if (isNight) Color.parseColor("#8E8E93") else Color.parseColor("#8E8E93")

        tvContent.visibility = if (isMultiChoice) View.GONE else View.VISIBLE
        tvContent.text = ""
        for (card in candidateCards) {
            card.visibility = View.GONE
        }
        for (tv in candidateTexts) {
            tv.text = ""
        }
        updateCandidateCardsSelection(colorCardBg, colorAccent, colorTextSecondary, isNight)

        tvModeToggle.text = if (isMultiChoice) "多条候选" else "单条回复"
        tvModeToggle.setTextColor(if (isMultiChoice) colorAccent else colorTextSecondary)
        (tvModeToggle.background as? GradientDrawable)?.apply {
            if (isMultiChoice) {
                setStroke(dp(1), colorAccent)
            } else {
                setStroke(0, Color.TRANSPARENT)
            }
        }

        loadingView.visibility = View.VISIBLE
        generatingActionLayout.visibility = View.VISIBLE
        completedActionLayout.visibility = View.GONE
        startAiRequest()
    }

    /**
     * 构建包含半透明遮罩与可滑动内容面板的弹窗根视图。
     *
     * Returns:
     *     View: 组装就绪的顶层视图容器。
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.init`
     */
    private fun buildDialogView(): View {
        val isNight = isNightMode()
        val colorSheetBg = if (isNight) Color.parseColor("#242424") else Color.parseColor("#FFFFFF")
        val colorTextPrimary = if (isNight) Color.parseColor("#EBEBF5") else Color.parseColor("#1A1A1A")
        val colorTextSecondary = if (isNight) Color.parseColor("#8E8E93") else Color.parseColor("#8E8E93")
        val colorCardBg = if (isNight) Color.parseColor("#1C1C1E") else Color.parseColor("#F5F6F8")
        val colorBtnCopyBg = if (isNight) Color.parseColor("#323234") else Color.parseColor("#F2F4F7")
        val colorAccent = if (isNight) Color.parseColor("#38ACFF") else Color.parseColor("#0099FF")

        val rootContainer = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        maskView = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(if (isNight) Color.parseColor("#77000000") else Color.parseColor("#55000000"))
            alpha = 0f
            setOnClickListener {
                dismiss()
            }
        }
        rootContainer.addView(maskView)

        sheetLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
            background = GradientDrawable().apply {
                setColor(colorSheetBg)
                cornerRadii = floatArrayOf(
                    dp(16).toFloat(), dp(16).toFloat(),
                    dp(16).toFloat(), dp(16).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            }
            isClickable = true
        }
        rootContainer.addView(sheetLayout)

        val handleBar = View(activity).apply {
            background = GradientDrawable().apply {
                setColor(if (isNight) Color.parseColor("#3E3E40") else Color.parseColor("#E0E0E0"))
                cornerRadius = dp(3).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
        }
        sheetLayout.addView(handleBar)

        val preset = LlmConfig.getSelectedPreset(activity)
        val model = LlmConfig.getModel(activity, preset)
        val role = LlmConfig.getSelectedRole(activity)
        val isMultiChoiceInit = LlmConfig.isMultiChoiceEnabled(activity)

        val headerLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val tvTitle = TextView(activity).apply {
            text = "${role.name} ($model)"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextPrimary)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerLayout.addView(tvTitle)

        // 多候选 / 单回复 快速切换按钮
        tvModeToggle = TextView(activity).apply {
            text = if (isMultiChoiceInit) "多条候选" else "单条回复"
            textSize = 12f
            setTextColor(if (isMultiChoiceInit) colorAccent else colorTextSecondary)
            includeFontPadding = false
            background = GradientDrawable().apply {
                setColor(if (isNight) Color.parseColor("#2C2C2E") else Color.parseColor("#F0F0F2"))
                cornerRadius = dp(11).toFloat()
                if (isMultiChoiceInit) {
                    setStroke(dp(1), colorAccent)
                }
            }
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val nextMode = !LlmConfig.isMultiChoiceEnabled(activity)
                LlmConfig.setMultiChoiceEnabled(activity, nextMode)
                Toast.makeText(
                    activity,
                    if (nextMode) "已切换为「多项候选」模式" else "已切换为「单条回复」模式",
                    Toast.LENGTH_SHORT
                ).show()
                restartAiRequest()
            }
        }
        headerLayout.addView(tvModeToggle)

        val tvSettings = TextView(activity).apply {
            text = "设置"
            textSize = 14f
            setTextColor(colorAccent)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                dismiss()
                io.github.meiyongai.qai.hook.ActivityStubManager.startActivity(
                    activity,
                    AiSettingsActivity::class.java
                )
            }
        }
        headerLayout.addView(tvSettings)
        sheetLayout.addView(headerLayout)

        // 思考过程可折叠卡片 (默认隐藏，仅在真实开启推理并产生推理内容时呈现)
        reasoningBlock = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(colorCardBg)
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(6)
            }
        }

        val reasoningHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { toggleReasoning() }
        }

        tvReasoningTitle = TextView(activity).apply {
            text = "思考中..."
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextSecondary)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        reasoningHeader.addView(tvReasoningTitle)

        ivReasoningChevron = ImageView(activity).apply {
            setImageDrawable(createChevronDrawable(colorTextSecondary, dp(2).toFloat()))
            rotation = 90f // 初始向下指 (展开时平滑旋转为 270f 向上指)
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        reasoningHeader.addView(ivReasoningChevron)
        reasoningBlock.addView(reasoningHeader)

        tvReasoningContent = TextView(activity).apply {
            textSize = 13f
            setTextColor(colorTextSecondary)
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextIsSelectable(true)
        }

        reasoningScroll = ScrollView(activity).apply {
            visibility = View.GONE
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)).apply {
                topMargin = dp(6)
            }
            addView(tvReasoningContent)
        }
        reasoningBlock.addView(reasoningScroll)
        sheetLayout.addView(reasoningBlock)

        // 回复正文区域容器 (含居中转圈加载动画)
        val contentCard = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)).apply {
                topMargin = dp(4)
                bottomMargin = dp(14)
            }
        }

        scrollView = ScrollView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isVerticalScrollBarEnabled = false
        }

        contentContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // 单回复模式下的文本展示
        tvContent = TextView(activity).apply {
            text = ""
            textSize = 15f
            setTextColor(colorTextPrimary)
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextIsSelectable(true)
            visibility = if (isMultiChoiceInit) View.GONE else View.VISIBLE
        }
        contentContainer.addView(tvContent)

        // 多项候选卡片容器初始化（按需动态创建与复用）
        candidateCards.clear()
        candidateIndicators.clear()
        candidateTexts.clear()

        scrollView.addView(contentContainer)
        contentCard.addView(scrollView)

        loadingView = ProgressBar(activity).apply {
            isIndeterminate = true
            layoutParams = FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER)
        }
        contentCard.addView(loadingView)
        sheetLayout.addView(contentCard)

        // 底部动作按钮区域：
        // 1. 生成中状态操作栏：展示“终止”按钮
        generatingActionLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            visibility = View.VISIBLE
        }

        // 2. 生成完成/终止后操作栏：[复制图标 (44dp)] [插入 (1f)] [发送 (1f)]
        completedActionLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            visibility = View.GONE
        }

        val btnStop = TextView(activity).apply {
            text = "终止"
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            setTextColor(if (isNight) Color.parseColor("#FF5B5B") else Color.parseColor("#E03E3E"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(colorBtnCopyBg)
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                streamClient?.cancel()
                loadingView.visibility = View.GONE
                generatingActionLayout.visibility = View.GONE
                completedActionLayout.visibility = View.VISIBLE
            }
        }
        generatingActionLayout.addView(btnStop)

        // 复制图标按钮 (使用 QQ 原生长按菜单的 qui_copy 图标或矢量回退)
        val copyResId = activity.resources.getIdentifier("qui_copy", "drawable", activity.packageName)
        val btnCopy = ImageView(activity).apply {
            if (copyResId != 0) {
                setImageResource(copyResId)
            } else {
                setImageDrawable(createCopyDrawable(colorTextPrimary, dp(2).toFloat()))
            }
            setColorFilter(colorTextPrimary)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = GradientDrawable().apply {
                setColor(colorBtnCopyBg)
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                gravity = Gravity.CENTER_VERTICAL
                rightMargin = dp(8)
            }
            contentDescription = "复制"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val targetText = getTargetTextToOperate()
                if (targetText.isNotEmpty()) {
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("QAI", targetText))
                    Toast.makeText(activity, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "内容为空", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 插入按钮 (原“填入输入框”，使用 TextView 彻底消除原生 Button 系统 Inset 引起的对齐偏移)
        val btnInsert = TextView(activity).apply {
            text = "插入"
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            setTextColor(colorTextPrimary)
            textSize = 15f
            background = GradientDrawable().apply {
                setColor(colorBtnCopyBg)
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
                rightMargin = dp(8)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val targetText = getTargetTextToOperate()
                if (targetText.isNotEmpty()) {
                    if (AioInputHelper.insertTextToInput(targetText)) {
                        Toast.makeText(activity, "已填入输入框", Toast.LENGTH_SHORT).show()
                        dismiss()
                    } else {
                        Toast.makeText(activity, "未定位到聊天输入框", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(activity, "内容为空", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 发送按钮 (使用 TextView 确保垂直水平严格居中对齐)
        val btnSend = TextView(activity).apply {
            text = "发送"
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(colorAccent)
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val targetText = getTargetTextToOperate()
                if (targetText.isNotEmpty()) {
                    val isQuote = LlmConfig.isQuoteOnSend(activity)
                    val replyTarget = if (isQuote) targetMsgItem else null

                    // 优先采用 QQNT 底层内核消息服务静默直接投递，彻底绕过界面输入框
                    if (contact != null) {
                        val directSuccess = AioInputHelper.sendDirectMessage(
                            context = activity,
                            contact = contact,
                            text = targetText,
                            replyMsgItem = replyTarget
                        )
                        if (directSuccess) {
                            dismiss()
                            return@setOnClickListener
                        }
                    }

                    val actionToQuote = if (isQuote) quoteAction else null
                    AioInputHelper.sendText(activity, targetText, actionToQuote)
                    dismiss()
                } else {
                    Toast.makeText(activity, "内容为空", Toast.LENGTH_SHORT).show()
                }
            }
        }

        completedActionLayout.addView(btnCopy)
        completedActionLayout.addView(btnInsert)
        completedActionLayout.addView(btnSend)

        sheetLayout.addView(generatingActionLayout)
        sheetLayout.addView(completedActionLayout)

        startAiRequest()

        return rootContainer
    }

    /**
     * 切换思考链（Reasoning）内容滚动视图的展开与折叠状态，并执行箭头旋转动画。
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.buildDialogView`
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.startAiRequest`
     */
    private fun toggleReasoning() {
        reasoningExpanded = !reasoningExpanded
        reasoningScroll.visibility = if (reasoningExpanded) View.VISIBLE else View.GONE
        ivReasoningChevron.animate()
            .rotation(if (reasoningExpanded) 270f else 90f)
            .setDuration(180)
            .start()
        if (reasoningExpanded) {
            reasoningScroll.post { reasoningScroll.scrollTo(0, 0) }
        }
    }

    /**
     * 读取用户当前配置并向大模型服务商发起流式生成请求。
     *
     * 检查服务商地址有效性与密钥配置，通过 SSE 协议接收推理思考流与正式回复流并动态更新界面；
     * 根据生成完成与终止状态动态切换底部操作栏为完成态。
     *
     * Callers:
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.buildDialogView`
     *     - `AiReplyBottomSheetDialog.kt:AiReplyBottomSheetDialog.restartAiRequest`
     */
    private fun startAiRequest() {
        val preset = LlmConfig.getSelectedPreset(activity)
        val baseUrl = LlmConfig.getEffectiveBaseUrl(activity, preset)
        val apiKey = LlmConfig.getFirstApiKey(activity, preset)
        val model = LlmConfig.getModel(activity, preset)
        val systemPrompt = LlmConfig.getEffectiveSystemPrompt(activity)
        val temperature = LlmConfig.getTemperature(activity)
        val reasoningEffort = LlmConfig.getReasoningEffort(activity)

        if (!LlmConfig.isValidBaseUrl(baseUrl)) {
            loadingView.visibility = View.GONE
            generatingActionLayout.visibility = View.GONE
            completedActionLayout.visibility = View.VISIBLE
            tvContent.text = "【接口地址无效】\n当前服务商接口地址为空或缺少 http(s):// 前缀。\n请点击右上角「设置」配置服务商或自定义接口地址。"
            return
        }

        if (apiKey.isBlank()) {
            loadingView.visibility = View.GONE
            generatingActionLayout.visibility = View.GONE
            completedActionLayout.visibility = View.VISIBLE
            tvContent.text = "【未配置 API Key】\n请点击右上角「设置」填写对应的 API Key。"
            return
        }

        streamClient = StreamChatClient().apply {
            startStream(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                systemPrompt = systemPrompt,
                userPrompt = originPrompt,
                imagePaths = imagePaths,
                temperature = temperature,
                reasoningEffort = reasoningEffort,
                callback = object : StreamChatClient.Callback {
                    override fun onReasoning(chunk: String) {
                        if (reasoningEffort <= LlmConfig.REASONING_OFF) {
                            // 用户在设置中关闭了推理！忽略所有思维链内容，坚决不显示思考框
                            return
                        }
                        activity.runOnUiThread {
                            loadingView.visibility = View.GONE
                            if (reasoningBuilder.isEmpty()) {
                                reasoningStartTime = System.currentTimeMillis()
                                reasoningBlock.visibility = View.VISIBLE
                                tvReasoningTitle.text = "思考中..."
                                tvContent.text = ""
                            }
                            reasoningBuilder.append(chunk)
                            tvReasoningContent.text = reasoningBuilder.toString()
                            if (reasoningExpanded) {
                                reasoningScroll.post { reasoningScroll.fullScroll(ScrollView.FOCUS_DOWN) }
                            }
                        }
                    }

                    override fun onChunk(chunk: String) {
                        activity.runOnUiThread {
                            loadingView.visibility = View.GONE
                            // 正式内容开始吐出，若开启了推理且之前有推理思考过程，自动折叠并更新耗时
                            if (reasoningEffort > LlmConfig.REASONING_OFF && reasoningBuilder.isNotEmpty() && reasoningSpentSeconds == 0L) {
                                reasoningSpentSeconds = Math.max(1, (System.currentTimeMillis() - reasoningStartTime) / 1000)
                                tvReasoningTitle.text = "已深度思考 (${reasoningSpentSeconds}秒)"
                                if (reasoningExpanded) {
                                    toggleReasoning()
                                }
                            }

                            if (fullResponse.isEmpty()) {
                                tvContent.text = ""
                            }
                            fullResponse.append(chunk)

                            val isMultiChoice = LlmConfig.isMultiChoiceEnabled(activity)
                            if (isMultiChoice) {
                                val candidates = LlmConfig.parseCandidates(fullResponse.toString())
                                val isNight = isNightMode()
                                val colorCardBg = if (isNight) Color.parseColor("#1C1C1E") else Color.parseColor("#F5F6F8")
                                val colorAccent = if (isNight) Color.parseColor("#38ACFF") else Color.parseColor("#0099FF")
                                val colorTextPrimary = if (isNight) Color.parseColor("#EBEBF5") else Color.parseColor("#1A1A1A")
                                val colorTextSecondary = if (isNight) Color.parseColor("#8E8E93") else Color.parseColor("#8E8E93")
                                renderCandidateCards(candidates, colorCardBg, colorAccent, colorTextPrimary, colorTextSecondary, isNight)
                                if (candidates.size > 1) {
                                    tvModeToggle.text = "${candidates.size}条候选"
                                }
                            } else {
                                tvContent.text = fullResponse.toString()
                            }
                            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                        }
                    }

                    override fun onComplete(fullText: String) {
                        activity.runOnUiThread {
                            loadingView.visibility = View.GONE
                            generatingActionLayout.visibility = View.GONE
                            completedActionLayout.visibility = View.VISIBLE
                            if (reasoningEffort > LlmConfig.REASONING_OFF && reasoningBuilder.isNotEmpty() && reasoningSpentSeconds == 0L) {
                                reasoningSpentSeconds = Math.max(1, (System.currentTimeMillis() - reasoningStartTime) / 1000)
                                tvReasoningTitle.text = "已深度思考 (${reasoningSpentSeconds}秒)"
                            }
                        }
                    }

                    override fun onError(error: String) {
                        activity.runOnUiThread {
                            loadingView.visibility = View.GONE
                            generatingActionLayout.visibility = View.GONE
                            completedActionLayout.visibility = View.VISIBLE
                            val isMultiChoice = LlmConfig.isMultiChoiceEnabled(activity)
                            if (isMultiChoice) {
                                if (candidateCards.isNotEmpty()) {
                                    candidateCards[0].visibility = View.VISIBLE
                                    candidateTexts[0].text = "请求失败: $error"
                                }
                            } else {
                                if (fullResponse.isEmpty()) {
                                    tvContent.text = "请求失败: $error"
                                } else {
                                    tvContent.append("\n\n[网络中断: $error]")
                                }
                            }
                        }
                    }
                }
            )
        }
    }
}
