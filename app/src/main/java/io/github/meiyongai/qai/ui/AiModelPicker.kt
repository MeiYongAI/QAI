package io.github.meiyongai.qai.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.meiyongai.qai.R
import io.github.meiyongai.qai.llm.config.LlmConfig
import io.github.meiyongai.qai.llm.net.OpenAICompatClient
import io.github.meiyongai.qai.llm.preset.PresetRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.Locale

class AiModelPicker private constructor(
    private val activity: Activity,
    private val preset: Int,
    private val onPick: (String) -> Unit
) : Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val currentModel: String = LlmConfig.getModel(activity, preset)

    private val allModels = ArrayList<String>()
    private val filteredModels = ArrayList<String>()
    private var typedText = ""
    private var customModel: String? = null

    private lateinit var adapter: ModelAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyView: TextView

    // 动画控制组件
    private lateinit var sheetLayout: LinearLayout
    private lateinit var maskView: View
    private var isDismissing = false

    companion object {
        /**
         * 呼出模型选择底部弹窗。
         *
         * Args:
         *     activity (Activity): 当前依附的宿主活动。
         *     preset (Int): 当前选中的服务商预设 ID。
         *     onPick ((String) -> Unit): 用户选中或自定义输入模型名称时的回调函数。
         *
         * Callers:
         *     - `AiSettingsActivity.kt:AiSettingsActivity.showModelPicker`
         */
        fun show(activity: Activity, preset: Int, onPick: (String) -> Unit) {
            AiModelPicker(activity, preset, onPick).show()
        }
    }

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

        val view = buildContentView()
        setContentView(view)

        setOnDismissListener {
            scope.cancel()
        }
    }

    /**
     * 显示模型选择对话框并启动底部面板平滑展开与蒙层淡入动画。
     *
     * Callers:
     *     - `AiModelPicker.kt:AiModelPicker.Companion.show`
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
     * 拦截退出事件，执行底部面板平滑收起与背景蒙层渐隐动画。
     *
     * Callers:
     *     - `AiModelPicker.kt:AiModelPicker.buildContentView`
     *     - `AiModelPicker.kt:AiModelPicker.onBackPressed`
     */
    override fun dismiss() {
        if (isDismissing) return
        isDismissing = true
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
     * 将独立像素密度值（dp）换算为屏幕物理像素值（px）。
     *
     * Args:
     *     dp (Int): 待转换的 dp 数值。
     *
     * Returns:
     *     Int: 物理像素数值。
     *
     * Callers:
     *     - `AiModelPicker.kt:AiModelPicker.buildContentView`
     *     - `AiModelPicker.kt:AiModelPicker.show`
     *     - `AiModelPicker.kt:AiModelPicker.dismiss`
     */
    private fun dp(dp: Int): Int {
        return (dp * activity.resources.displayMetrics.density).toInt()
    }

    /**
     * 检查当前系统与界面是否处于深色主题模式。
     *
     * Returns:
     *     Boolean: 若处于深色模式返回 true，否则返回 false。
     *
     * Callers:
     *     - `AiModelPicker.kt:AiModelPicker.init`
     *     - `AiModelPicker.kt:AiModelPicker.buildContentView`
     */
    private fun isNightMode(): Boolean {
        return (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * 构建包含背景蒙层与带圆角底部卡片面板的完整视图结构。
     *
     * Returns:
     *     View: 组装就绪的顶层视图容器。
     *
     * Callers:
     *     - `AiModelPicker.kt:AiModelPicker.init`
     */
    private fun buildContentView(): View {
        val isNight = isNightMode()
        val colorSheetBg = if (isNight) Color.parseColor("#242424") else Color.parseColor("#FFFFFF")
        val colorTextPrimary = if (isNight) Color.parseColor("#EBEBF5") else Color.parseColor("#1F1F1F")
        val colorTextSecondary = if (isNight) Color.parseColor("#8E8E93") else Color.parseColor("#8E8E93")
        val colorInputBg = if (isNight) Color.parseColor("#1C1C1E") else Color.parseColor("#F5F6F8")
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
            background = GradientDrawable().apply {
                setColor(colorSheetBg)
                cornerRadii = floatArrayOf(
                    dp(16).toFloat(), dp(16).toFloat(),
                    dp(16).toFloat(), dp(16).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
            setPadding(0, dp(12), 0, dp(16))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            }
            isClickable = true
        }
        rootContainer.addView(sheetLayout)

        // 顶栏拖动把手
        val handleBar = View(activity).apply {
            background = GradientDrawable().apply {
                setColor(if (isNight) Color.parseColor("#3E3E40") else Color.parseColor("#E0E0E0"))
                cornerRadius = dp(3).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            }
        }
        sheetLayout.addView(handleBar)

        // 标题栏：标题 + 加载旋转指示器
        val headerLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val tvTitle = TextView(activity).apply {
            text = "模型"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorTextPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerLayout.addView(tvTitle)

        progressBar = ProgressBar(activity).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        }
        headerLayout.addView(progressBar)
        sheetLayout.addView(headerLayout)

        // 搜索 / 自定义输入框
        val searchContainer = FrameLayout(activity).apply {
            setPadding(dp(20), 0, dp(20), dp(10))
        }

        val searchInput = EditText(activity).apply {
            hint = "搜索或输入模型"
            textSize = 15f
            setTextColor(colorTextPrimary)
            setHintTextColor(if (isNight) Color.parseColor("#636366") else Color.parseColor("#BCBCC0"))
            isSingleLine = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(colorInputBg)
                cornerRadius = dp(8).toFloat()
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    typedText = s?.toString()?.trim() ?: ""
                    applyFilter()
                }
            })
        }
        searchContainer.addView(searchInput)
        sheetLayout.addView(searchContainer)

        // 列表展示区
        val listContainer = FrameLayout(activity).apply {
            val screenHeight = activity.resources.displayMetrics.heightPixels
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (screenHeight * 0.55).toInt())
        }

        val recyclerView = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        adapter = ModelAdapter()
        recyclerView.adapter = adapter
        listContainer.addView(recyclerView)

        emptyView = TextView(activity).apply {
            text = "服务商未返回模型列表"
            textSize = 14f
            setTextColor(colorTextSecondary)
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        }
        listContainer.addView(emptyView)
        sheetLayout.addView(listContainer)

        // 加载初始列表并触发静默拉取
        initModelsAndFetch()

        return rootContainer
    }

    /**
     * 初始化模型候选列表并向大模型服务端异步拉取更新。
     *
     * 整合当前选中模型、默认模型以及本地历史缓存模型；若配置了 API Key，
     * 则在后台协程发起网络拉取，并在完成后刷新候选列表与缓存。
     *
     * Callers:
     *     - `AiModelPicker.kt:AiModelPicker.buildContentView`
     */
    private fun initModelsAndFetch() {
        val candidate = LinkedHashSet<String>()
        val defaultModel = PresetRegistry.getDefaultModel(preset)
        if (currentModel.isNotBlank()) candidate.add(currentModel)
        if (defaultModel.isNotBlank()) candidate.add(defaultModel)
        candidate.addAll(LlmConfig.getCachedModels(activity, preset))

        allModels.clear()
        allModels.addAll(candidate)
        applyFilter()

        // 异步向服务商静默拉取最新在线模型
        val baseUrl = LlmConfig.getEffectiveBaseUrl(activity, preset)
        val apiKey = LlmConfig.getFirstApiKey(activity, preset)

        if (apiKey.isBlank()) {
            progressBar.visibility = View.GONE
            return
        }

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                OpenAICompatClient.fetchModels(baseUrl, apiKey)
            }
            progressBar.visibility = View.GONE

            if (result.isSuccess && !result.data.isNullOrEmpty()) {
                val fetched = result.data
                LlmConfig.setCachedModels(activity, preset, fetched)

                val updatedSet = LinkedHashSet<String>()
                if (currentModel.isNotBlank()) updatedSet.add(currentModel)
                if (defaultModel.isNotBlank()) updatedSet.add(defaultModel)
                updatedSet.addAll(fetched)

                allModels.clear()
                allModels.addAll(updatedSet)
                applyFilter()
            }
        }
    }

    /**
     * 根据搜索框文本实时过滤模型列表，并动态注入自定义模型项。
     *
     * Callers:
     *     - `AiModelPicker.kt:AiModelPicker.buildContentView`
     *     - `AiModelPicker.kt:AiModelPicker.initModelsAndFetch`
     */
    private fun applyFilter() {
        filteredModels.clear()
        val query = typedText.lowercase(Locale.ROOT)
        var exactMatch = false

        for (m in allModels) {
            if (query.isEmpty() || m.lowercase(Locale.ROOT).contains(query)) {
                filteredModels.add(m)
            }
            if (m.equals(typedText, ignoreCase = true)) {
                exactMatch = true
            }
        }

        // 如果用户输入的模型不在列表中，在首项显示自定义模型项
        customModel = if (typedText.isNotEmpty() && !exactMatch) typedText else null
        if (customModel != null) {
            filteredModels.add(0, customModel!!)
        }

        emptyView.visibility = if (filteredModels.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    /**
     * 判断指定模型标识是否具备深度思考或推理能力。
     *
     * Args:
     *     modelId (String): 模型唯一标识符。
     *
     * Returns:
     *     Boolean: 若为推理模型返回 true，否则返回 false。
     *
     * Callers:
     *     - `AiModelPicker.kt:AiModelPicker.ModelAdapter.onBindViewHolder`
     */
    private fun isReasoningModel(modelId: String): Boolean {
        val lower = modelId.lowercase(Locale.ROOT)
        return lower.contains("deepseek-reasoner") || lower.contains("reasoning") ||
                lower.startsWith("o1") || lower.startsWith("o3") || lower.contains("r1")
    }

    private inner class ModelAdapter : RecyclerView.Adapter<ModelAdapter.ViewHolder>() {

        inner class ViewHolder(
            itemView: View,
            val tvName: TextView,
            val tvSub: TextView,
            val tvCheck: TextView
        ) : RecyclerView.ViewHolder(itemView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val isNight = isNightMode()
            val colorTextPrimary = if (isNight) Color.parseColor("#EBEBF5") else Color.parseColor("#1F1F1F")
            val colorTextSecondary = if (isNight) Color.parseColor("#8E8E93") else Color.parseColor("#8E8E93")
            val colorAccent = if (isNight) Color.parseColor("#38ACFF") else Color.parseColor("#0099FF")

            val layout = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
                background = activity.getDrawable(R.drawable.bg_qq_item_ripple)
                isClickable = true
                isFocusable = true
                setPadding(dp(20), 0, dp(20), 0)
            }

            val textLayout = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvName = TextView(parent.context).apply {
                textSize = 15f
                setTextColor(colorTextPrimary)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
            textLayout.addView(tvName)

            val tvSub = TextView(parent.context).apply {
                textSize = 12f
                setTextColor(colorTextSecondary)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }
            textLayout.addView(tvSub)
            layout.addView(textLayout)

            val tvCheck = TextView(parent.context).apply {
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colorAccent)
            }
            layout.addView(tvCheck)

            return ViewHolder(layout, tvName, tvSub, tvCheck)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val modelId = filteredModels[position]
            val isCustom = modelId == customModel
            val isSelected = !isCustom && modelId == currentModel
            val isNight = isNightMode()
            val colorAccent = if (isNight) Color.parseColor("#38ACFF") else Color.parseColor("#0099FF")
            val colorTextPrimary = if (isNight) Color.parseColor("#EBEBF5") else Color.parseColor("#1F1F1F")

            if (isCustom) {
                holder.tvName.text = modelId
                holder.tvName.typeface = Typeface.DEFAULT_BOLD
                holder.tvName.setTextColor(colorAccent)
                holder.tvSub.text = "使用此模型"
                holder.tvSub.visibility = View.VISIBLE
                holder.tvCheck.visibility = View.GONE
            } else {
                val hasReasoning = isReasoningModel(modelId)
                holder.tvName.text = if (hasReasoning) "✦ $modelId" else modelId
                holder.tvName.typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                holder.tvName.setTextColor(if (isSelected) colorAccent else colorTextPrimary)

                holder.tvSub.visibility = View.GONE
                holder.tvCheck.text = "✓"
                holder.tvCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
            }

            holder.itemView.setOnClickListener {
                LlmConfig.setModel(activity, preset, modelId)
                onPick(modelId)
                dismiss()
            }
        }

        override fun getItemCount(): Int = filteredModels.size
    }
}
