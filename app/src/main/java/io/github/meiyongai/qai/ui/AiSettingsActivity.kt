package io.github.meiyongai.qai.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.meiyongai.qai.R
import io.github.meiyongai.qai.llm.config.LlmConfig
import io.github.meiyongai.qai.llm.memory.SessionMemory
import io.github.meiyongai.qai.llm.memory.SessionMemoryManager
import io.github.meiyongai.qai.llm.memory.SessionMemorySummaryItem
import io.github.meiyongai.qai.llm.model.Role
import io.github.meiyongai.qai.llm.net.OpenAICompatClient
import io.github.meiyongai.qai.llm.preset.PresetRegistry
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiSettingsActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var moduleContext: Context
    private var currentPreset: Int = PresetRegistry.DEEPSEEK

    // 视图组件引用
    private lateinit var rootView: View
    private lateinit var tvTitle: TextView
    private lateinit var btnClose: ImageView

    // 服务板块
    private lateinit var tvProviderValue: TextView
    private lateinit var tvBaseUrlValue: TextView
    private lateinit var tvApiKeyValue: TextView
    private lateinit var tvModelValue: TextView
    private lateinit var tvReasoningValue: TextView
    private lateinit var pbTest: ProgressBar
    private lateinit var tvTestStatus: TextView

    // 生成板块
    private lateinit var tvRoleValue: TextView
    private lateinit var tvTempValue: TextView
    private lateinit var sbTemp: SeekBar

    // 上下文板块
    private lateinit var tvContextCountValue: TextView
    private lateinit var sbContextCount: SeekBar
    private lateinit var switchExcludeImage: Switch
    private lateinit var switchExcludeEmoji: Switch
    private lateinit var switchExcludeCard: Switch
    private lateinit var switchExcludeVoice: Switch

    // 发送板块
    private lateinit var switchQuoteOnSend: Switch
    private lateinit var switchMultiChoice: Switch

    // 记忆与压缩板块
    private lateinit var switchSessionMemory: Switch
    private lateinit var switchContextCompress: Switch
    private lateinit var tvCompressThresholdValue: TextView
    private lateinit var tvCompressKeepTurnsValue: TextView

    // 视觉理解（图片反推模型）板块
    private lateinit var switchVisionCaption: Switch
    private lateinit var tvVisionBaseUrlValue: TextView
    private lateinit var tvVisionApiKeyValue: TextView
    private lateinit var tvVisionModelValue: TextView

    override fun attachBaseContext(newBase: Context) {
        val modCtx = try {
            newBase.createPackageContext(
                "io.github.meiyongai.qai",
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            )
        } catch (e: Throwable) {
            newBase
        }
        moduleContext = modCtx
        super.attachBaseContext(newBase)
    }

    override fun getResources(): Resources {
        val moduleRes = if (::moduleContext.isInitialized) moduleContext.resources else null
        val hostRes = runCatching { baseContext?.resources }.getOrNull()
        if (moduleRes == null) return hostRes ?: super.getResources()
        if (hostRes == null) return moduleRes

        return object : Resources(moduleRes.assets, moduleRes.displayMetrics, moduleRes.configuration) {
            override fun getText(id: Int): CharSequence {
                return runCatching { moduleRes.getText(id) }.getOrElse { hostRes.getText(id) }
            }

            override fun getString(id: Int): String {
                return runCatching { moduleRes.getString(id) }.getOrElse { hostRes.getString(id) }
            }

            override fun getDrawableForDensity(id: Int, density: Int, theme: Theme?): android.graphics.drawable.Drawable? {
                return runCatching { moduleRes.getDrawableForDensity(id, density, theme) }
                    .getOrElse { hostRes.getDrawableForDensity(id, density, theme) }
            }

            override fun getValueForDensity(id: Int, density: Int, outValue: android.util.TypedValue?, resolveRefs: Boolean) {
                try {
                    moduleRes.getValueForDensity(id, density, outValue, resolveRefs)
                } catch (e: NotFoundException) {
                    hostRes.getValueForDensity(id, density, outValue, resolveRefs)
                }
            }
        }
    }

    override fun getClassLoader(): ClassLoader {
        return AiSettingsActivity::class.java.classLoader ?: super.getClassLoader()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setTheme(android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        super.onCreate(savedInstanceState)

        val inflater = LayoutInflater.from(moduleContext)
        rootView = inflater.inflate(R.layout.activity_ai_settings, null)
        setContentView(rootView)

        currentPreset = LlmConfig.getSelectedPreset(this)
        bindViews(rootView)
        applyTheme(isNightMode())
        loadData()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    /**
     * 判断当前宿主 QQ 或系统环境是否处于深色/夜间模式
     *
     * 优先通过反射读取宿主 QQ 的 ThemeUtil 当前主题 ID（1103 或 2920 为夜间主题），
     * 若在独立运行模式或反射类不存在时，回退到系统的 Configuration.UI_MODE_NIGHT 检查。
     *
     * Args:
     *     无
     *
     * Returns:
     *     Boolean: 若当前处于夜间/深色模式返回 true，否则返回 false。
     *
     * Callers:
     *     - `AiSettingsActivity:onCreate`
     */
    private fun isNightMode(): Boolean {
        try {
            val themeUtil = classLoader.loadClass("com.tencent.mobileqq.theme.ThemeUtil")
            val method = themeUtil.getDeclaredMethod("getUserCurrentThemeId", Any::class.java, Class.forName("mqq.app.AppRuntime"))
            val appRuntime = classLoader.loadClass("com.tencent.common.app.BaseApplicationImpl")
                .getMethod("getApplication").invoke(null)?.let {
                    it.javaClass.getMethod("getRuntime").invoke(it)
                }
            val themeId = method.invoke(null, this, appRuntime) as? String
            if (themeId == "1103" || themeId == "2920" || themeId?.contains("dark", true) == true) {
                return true
            }
        } catch (e: Throwable) {
            android.util.Log.d("AiSettingsActivity", "读取 QQ 原生主题模式失败，回退系统配置检查: ${e.message}")
        }
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * 将密度无关像素 (DP) 转换为设备物理像素 (PX)
     *
     * Args:
     *     dp (Int): 待转换的 dp 数值。
     *
     * Returns:
     *     Int: 转换后对应的屏幕实际像素数。
     *
     * Callers:
     *     - `AiSettingsActivity:applyTheme`
     *     - `AiSettingsActivity:showBaseUrlDialog`
     *     - `AiSettingsActivity:showApiKeyDialog`
     */
    private fun dp(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    /**
     * 应用页面主题配色，严格对齐 QQ 原生 QUI 设计规范
     *
     * 依据 QQNT 官方设计语言设置页面底色、顶栏、卡片背景圆角、分割线、
     * 主次文本灰阶与透明度、系统状态栏/导航栏沉浸式图标样式，
     * 以及 Switch 胶囊开关与 SeekBar 控件的 QQ 标志性科技蓝主题色。
     *
     * Args:
     *     isNight (Boolean): 是否处于深色/夜间模式。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity:onCreate`
     */
    /**
     * 获取当前系统状态栏高度物理像素值。
     *
     * Args:
     *     无
     *
     * Returns:
     *     Int: 状态栏像素高度，若获取失败则回退默认 24dp 像素值。
     *
     * Callers:
     *     - `AiSettingsActivity:applyTheme`
     */
    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else dp(24)
    }

    /**
     * 应用页面主题配色，严格对齐 QQ 原生 QUI 设计规范
     *
     * 依据 QQNT 官方设计语言设置页面底色、顶栏、卡片背景圆角、分割线、
     * 主次文本灰阶与透明度、系统状态栏/导航栏沉浸式图标样式，
     * 以及 Switch 胶囊开关与 SeekBar 控件的 QQ 标志性科技蓝主题色。
     *
     * Args:
     *     isNight (Boolean): 是否处于深色/夜间模式。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity:onCreate`
     */
    private fun applyTheme(isNight: Boolean) {
        // QUI 规范色彩体系：告别纯黑，采用柔和沉浸的深灰与通透浅色
        val colorPageBg = if (isNight) Color.parseColor("#191919") else Color.parseColor("#F5F6F8")
        val colorTopBarBg = if (isNight) Color.parseColor("#F0191919") else Color.parseColor("#F0F5F6F8")
        val colorCardBg = if (isNight) Color.parseColor("#242424") else Color.parseColor("#FFFFFF")
        val colorDivider = if (isNight) Color.parseColor("#0FFFFFFF") else Color.parseColor("#0A000000")
        val colorTitle = if (isNight) Color.parseColor("#EBEBF5") else Color.parseColor("#1A1A1A")
        val colorTextPrimary = if (isNight) Color.parseColor("#EBEBF5") else Color.parseColor("#1A1A1A")
        val colorTextSecondary = if (isNight) Color.parseColor("#8E8E93") else Color.parseColor("#8E8E93")
        val colorArrow = if (isNight) Color.parseColor("#636366") else Color.parseColor("#C7C7CC")
        val colorBrand = Color.parseColor("#0099FF")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = colorPageBg
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags = if (!isNight) {
                    flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else {
                    flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = if (!isNight) {
                    flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                } else {
                    flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                }
            }
            window.decorView.systemUiVisibility = flags
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }

        rootView.setBackgroundColor(colorPageBg)

        // 顶栏沉浸式与半透明处理
        val statusBarHeight = getStatusBarHeight()
        rootView.findViewById<View>(R.id.statusBarPlaceholder)?.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, statusBarHeight)
        }
        val topBarContainer = rootView.findViewById<View>(R.id.topBarContainer)
        topBarContainer.setBackgroundColor(colorTopBarBg)
        tvTitle.setTextColor(colorTitle)
        btnClose.setColorFilter(colorTitle)
        rootView.findViewById<View>(R.id.topDivider).setBackgroundColor(colorDivider)

        // 内容滚动区域去除丑陋原生滚动条，并设置 paddingTop 为顶栏总高度以实现穿透滑动
        val topBarTotalHeight = statusBarHeight + dp(48)
        val scrollView = rootView.findViewById<ScrollView>(R.id.scrollView)
        scrollView.setPadding(0, topBarTotalHeight, 0, 0)
        scrollView.isVerticalScrollBarEnabled = false
        scrollView.overScrollMode = View.OVER_SCROLL_NEVER

        // 分组标题
        rootView.findViewById<TextView>(R.id.tvGroup1Title).setTextColor(colorTextSecondary)
        rootView.findViewById<TextView>(R.id.tvGroup2Title).setTextColor(colorTextSecondary)
        rootView.findViewById<TextView>(R.id.tvGroupContextTitle)?.setTextColor(colorTextSecondary)
        rootView.findViewById<TextView>(R.id.tvGroup3Title).setTextColor(colorTextSecondary)
        rootView.findViewById<TextView>(R.id.tvGroup4Title)?.setTextColor(colorTextSecondary)
        rootView.findViewById<TextView>(R.id.tvGroupVisionTitle)?.setTextColor(colorTextSecondary)

        // 卡片背景动态纯正着色 (标准 10dp 圆角)
        fun createCardDrawable(): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(colorCardBg)
            }
        }
        rootView.findViewById<View>(R.id.cardProvider).background = createCardDrawable()
        rootView.findViewById<View>(R.id.cardParams).background = createCardDrawable()
        rootView.findViewById<View>(R.id.cardContext)?.background = createCardDrawable()
        rootView.findViewById<View>(R.id.cardSend).background = createCardDrawable()
        rootView.findViewById<View>(R.id.cardMemory)?.background = createCardDrawable()
        rootView.findViewById<View>(R.id.cardVision)?.background = createCardDrawable()

        // 分割线着色
        val dividers = listOf(
            R.id.div1, R.id.div2, R.id.div3, R.id.div4, R.id.div5,
            R.id.divRoleTemp,
            R.id.divContext1, R.id.divContext2, R.id.divContext3, R.id.divContext4,
            R.id.divSend1,
            R.id.divMemory1, R.id.divMemory2, R.id.divMemory3, R.id.divMemory4,
            R.id.divVision1, R.id.divVision2, R.id.divVision3
        )
        dividers.forEach { id ->
            rootView.findViewById<View>(id)?.setBackgroundColor(colorDivider)
        }

        // 标签文字着色
        val labels = listOf(
            R.id.tvProviderLabel, R.id.tvBaseUrlLabel, R.id.tvApiKeyLabel, R.id.tvModelLabel,
            R.id.tvReasoningLabel, R.id.tvRoleLabel, R.id.tvTempLabel,
            R.id.tvContextCountLabel, R.id.tvExcludeImageLabel, R.id.tvExcludeEmojiLabel,
            R.id.tvExcludeCardLabel, R.id.tvExcludeVoiceLabel,
            R.id.tvQuoteOnSendLabel, R.id.tvMultiChoiceLabel,
            R.id.tvSessionMemoryLabel, R.id.tvContextCompressLabel,
            R.id.tvCompressThresholdLabel, R.id.tvCompressKeepTurnsLabel,
            R.id.tvManageSessionsLabel,
            R.id.tvVisionCaptionLabel, R.id.tvVisionBaseUrlLabel, R.id.tvVisionApiKeyLabel, R.id.tvVisionModelLabel
        )
        labels.forEach { id ->
            rootView.findViewById<TextView>(id)?.setTextColor(colorTextPrimary)
        }
        rootView.findViewById<TextView>(R.id.tvMultiChoiceDesc)?.setTextColor(colorTextSecondary)
        rootView.findViewById<TextView>(R.id.tvSessionMemoryDesc)?.setTextColor(colorTextSecondary)
        rootView.findViewById<TextView>(R.id.tvContextCompressDesc)?.setTextColor(colorTextSecondary)
        rootView.findViewById<TextView>(R.id.tvVisionCaptionDesc)?.setTextColor(colorTextSecondary)

        // 列表项向右箭头图标着色 (QUI 原生 tertiary 灰色阶)
        val arrows = listOf(
            R.id.ivProviderArrow, R.id.ivBaseUrlArrow, R.id.ivApiKeyArrow,
            R.id.ivModelArrow, R.id.ivReasoningArrow, R.id.ivTestArrow,
            R.id.ivRoleArrow, R.id.ivManageSessionsChevron,
            R.id.ivVisionBaseUrlArrow, R.id.ivVisionApiKeyArrow, R.id.ivVisionModelArrow
        )
        arrows.forEach { id ->
            rootView.findViewById<ImageView>(id)?.setColorFilter(colorArrow)
        }

        // 条目当前值着色
        tvProviderValue.setTextColor(colorTextSecondary)
        tvBaseUrlValue.setTextColor(colorTextSecondary)
        tvApiKeyValue.setTextColor(colorTextSecondary)
        tvModelValue.setTextColor(colorTextSecondary)
        tvReasoningValue.setTextColor(colorTextSecondary)
        tvRoleValue.setTextColor(colorTextSecondary)
        tvTempValue.setTextColor(colorTextSecondary)
        tvContextCountValue.setTextColor(colorTextSecondary)
        tvCompressThresholdValue.setTextColor(colorTextSecondary)
        tvCompressKeepTurnsValue.setTextColor(colorTextSecondary)
        tvVisionBaseUrlValue.setTextColor(colorTextSecondary)
        tvVisionApiKeyValue.setTextColor(colorTextSecondary)
        tvVisionModelValue.setTextColor(colorTextSecondary)

        // 测试链接文字
        rootView.findViewById<TextView>(R.id.tvTestLabel)?.setTextColor(colorTextPrimary)

        // Switch 胶囊开关动态设置原生轨道与滑块
        fun createSwitchTrackDrawable(): android.graphics.drawable.StateListDrawable {
            val checked = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(13).toFloat()
                setColor(colorBrand)
                setSize(dp(47), dp(26))
            }
            val unchecked = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(13).toFloat()
                setColor(if (isNight) Color.parseColor("#39393D") else Color.parseColor("#E9E9EB"))
                setSize(dp(47), dp(26))
            }
            return android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_checked), checked)
                addState(intArrayOf(), unchecked)
            }
        }

        listOf(
            switchExcludeImage, switchExcludeEmoji, switchExcludeCard,
            switchExcludeVoice, switchQuoteOnSend, switchMultiChoice,
            switchSessionMemory, switchContextCompress, switchVisionCaption
        ).forEach { sw ->
            sw.trackTintList = null
            sw.trackDrawable = createSwitchTrackDrawable()
        }

        // SeekBar 滑动条着色与防切割圆球柄定制
        val brandColorList = ColorStateList.valueOf(colorBrand)
        sbTemp.progressTintList = brandColorList
        sbContextCount.progressTintList = brandColorList

        fun createThumbDrawable(): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setSize(dp(18), dp(18))
                setColor(Color.WHITE)
                setStroke(dp(3), colorBrand)
            }
        }
        sbTemp.thumb = createThumbDrawable()
        sbTemp.splitTrack = false
        sbContextCount.thumb = createThumbDrawable()
        sbContextCount.splitTrack = false
    }

    /**
     * 绑定界面所有视图组件及其交互事件
     *
     * 包括顶栏返回、服务商选择、接口地址、密钥、模型、推理等级、温度 SeekBar、
     * 角色选择、上下文条数调节与精准输入、各项消息排除 Switch 开关、引用发送开关、
     * 会话长期记忆开关、智能上下文压缩开关、压缩参数配置及记忆数据管理。
     *
     * Args:
     *     root (View): 设置页面的根视图。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity:onCreate`
     */
    private fun bindViews(root: View) {
        tvTitle = root.findViewById(R.id.tvTitle)
        btnClose = root.findViewById(R.id.btnClose)

        tvProviderValue = root.findViewById(R.id.tvProviderValue)
        tvBaseUrlValue = root.findViewById(R.id.tvBaseUrlValue)
        tvApiKeyValue = root.findViewById(R.id.tvApiKeyValue)
        tvModelValue = root.findViewById(R.id.tvModelValue)
        tvReasoningValue = root.findViewById(R.id.tvReasoningValue)
        pbTest = root.findViewById(R.id.pbTest)
        tvTestStatus = root.findViewById(R.id.tvTestStatus)

        tvRoleValue = root.findViewById(R.id.tvRoleValue)
        tvTempValue = root.findViewById(R.id.tvTempValue)
        sbTemp = root.findViewById(R.id.sbTemp)

        tvContextCountValue = root.findViewById(R.id.tvContextCountValue)
        sbContextCount = root.findViewById(R.id.sbContextCount)
        switchExcludeImage = root.findViewById(R.id.switchExcludeImage)
        switchExcludeEmoji = root.findViewById(R.id.switchExcludeEmoji)
        switchExcludeCard = root.findViewById(R.id.switchExcludeCard)
        switchExcludeVoice = root.findViewById(R.id.switchExcludeVoice)

        // 顶栏返回
        btnClose.setOnClickListener {
            finish()
        }

        // 温度 SeekBar 直接内嵌调节，步长 0.1，共 20 档
        sbTemp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val temp = progress / 10.0f
                tvTempValue.text = String.format(Locale.US, "%.1f", temp)
                if (fromUser) {
                    LlmConfig.setTemperature(this@AiSettingsActivity, temp)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val temp = (seekBar?.progress ?: 7) / 10.0f
                LlmConfig.setTemperature(this@AiSettingsActivity, temp)
            }
        })

        // 上下文条数 SeekBar 调节（0-1000 条，0 为关闭）
        sbContextCount.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvContextCountValue.text = if (progress == 0) "已关闭" else "$progress 条"
                if (fromUser) {
                    LlmConfig.setContextCount(this@AiSettingsActivity, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: LlmConfig.DEFAULT_CONTEXT_COUNT
                LlmConfig.setContextCount(this@AiSettingsActivity, progress)
            }
        })

        // 支持点击上下文标签或条数值直接输入精准数值（0~1000）
        tvContextCountValue.setOnClickListener {
            showContextCountInputDialog()
        }
        root.findViewById<View>(R.id.tvContextCountLabel)?.setOnClickListener {
            showContextCountInputDialog()
        }

        // 排除项开关事件绑定
        switchExcludeImage.setOnCheckedChangeListener { _, isChecked ->
            LlmConfig.setExcludeImage(this, isChecked)
        }
        root.findViewById<View>(R.id.itemExcludeImage).setOnClickListener {
            switchExcludeImage.toggle()
        }

        switchExcludeEmoji.setOnCheckedChangeListener { _, isChecked ->
            LlmConfig.setExcludeEmoji(this, isChecked)
        }
        root.findViewById<View>(R.id.itemExcludeEmoji).setOnClickListener {
            switchExcludeEmoji.toggle()
        }

        switchExcludeCard.setOnCheckedChangeListener { _, isChecked ->
            LlmConfig.setExcludeCard(this, isChecked)
        }
        root.findViewById<View>(R.id.itemExcludeCard).setOnClickListener {
            switchExcludeCard.toggle()
        }

        switchExcludeVoice.setOnCheckedChangeListener { _, isChecked ->
            LlmConfig.setExcludeVoice(this, isChecked)
        }
        root.findViewById<View>(R.id.itemExcludeVoice).setOnClickListener {
            switchExcludeVoice.toggle()
        }

        // 服务商选择
        root.findViewById<View>(R.id.itemProvider).setOnClickListener {
            showProviderPicker()
        }

        // 接口地址修改
        root.findViewById<View>(R.id.itemBaseUrl).setOnClickListener {
            showBaseUrlDialog()
        }

        // API 密钥修改
        root.findViewById<View>(R.id.itemApiKey).setOnClickListener {
            showApiKeyDialog()
        }

        // 模型选择与拉取（合并项）
        root.findViewById<View>(R.id.itemModel).setOnClickListener {
            showModelPicker()
        }

        // 推理强度调节
        root.findViewById<View>(R.id.itemReasoning).setOnClickListener {
            showReasoningPicker()
        }

        // 测试链接
        root.findViewById<View>(R.id.itemTest).setOnClickListener {
            testApiConnection()
        }

        // 角色管理选择
        root.findViewById<View>(R.id.itemRole).setOnClickListener {
            showRoleSelectorDialog()
        }

        // 引用发送开关
        switchQuoteOnSend = root.findViewById(R.id.switchQuoteOnSend)
        switchQuoteOnSend.setOnCheckedChangeListener { _, isChecked ->
            LlmConfig.setQuoteOnSend(this, isChecked)
        }
        root.findViewById<View>(R.id.itemQuoteOnSend).setOnClickListener {
            switchQuoteOnSend.toggle()
        }

        // 多项候选回复开关
        switchMultiChoice = root.findViewById(R.id.switchMultiChoice)
        switchMultiChoice.setOnCheckedChangeListener { _, isChecked ->
            LlmConfig.setMultiChoiceEnabled(this, isChecked)
        }
        root.findViewById<View>(R.id.itemMultiChoice).setOnClickListener {
            switchMultiChoice.toggle()
        }

        // 记忆与压缩组件绑定
        switchSessionMemory = root.findViewById(R.id.switchSessionMemory)
        switchSessionMemory.setOnCheckedChangeListener { _, isChecked ->
            LlmConfig.setSessionMemoryEnabled(this, isChecked)
        }
        root.findViewById<View>(R.id.itemSessionMemory).setOnClickListener {
            switchSessionMemory.toggle()
        }

        switchContextCompress = root.findViewById(R.id.switchContextCompress)
        switchContextCompress.setOnCheckedChangeListener { _, isChecked ->
            LlmConfig.setContextCompressEnabled(this, isChecked)
        }
        root.findViewById<View>(R.id.itemContextCompress).setOnClickListener {
            switchContextCompress.toggle()
        }

        tvCompressThresholdValue = root.findViewById(R.id.tvCompressThresholdValue)
        root.findViewById<View>(R.id.itemCompressThreshold).setOnClickListener {
            showCompressThresholdPicker()
        }

        tvCompressKeepTurnsValue = root.findViewById(R.id.tvCompressKeepTurnsValue)
        root.findViewById<View>(R.id.itemCompressKeepTurns).setOnClickListener {
            showCompressKeepTurnsPicker()
        }

        root.findViewById<View>(R.id.itemManageSessions).setOnClickListener {
            showSessionManagerDialog()
        }

        // 视觉理解（图片反推模型）组件绑定
        switchVisionCaption = root.findViewById(R.id.switchVisionCaption)
        switchVisionCaption.setOnCheckedChangeListener { _, isChecked ->
            LlmConfig.setVisionCaptionEnabled(this, isChecked)
        }
        root.findViewById<View>(R.id.itemVisionCaption).setOnClickListener {
            switchVisionCaption.toggle()
        }

        tvVisionBaseUrlValue = root.findViewById(R.id.tvVisionBaseUrlValue)
        root.findViewById<View>(R.id.itemVisionBaseUrl).setOnClickListener {
            showVisionBaseUrlDialog()
        }

        tvVisionApiKeyValue = root.findViewById(R.id.tvVisionApiKeyValue)
        root.findViewById<View>(R.id.itemVisionApiKey).setOnClickListener {
            showVisionApiKeyDialog()
        }

        tvVisionModelValue = root.findViewById(R.id.tvVisionModelValue)
        root.findViewById<View>(R.id.itemVisionModel).setOnClickListener {
            showVisionModelDialog()
        }
    }

    /**
     * 从配置存储中加载并刷新当前页面展示的数据
     *
     * 读取当前生效的服务商、地址、脱敏密钥、模型名称、推理参数、当前角色、温度、
     * 上下文消息条数、各项排除标志、引用发送开关状态，以及会话长期记忆与压缩参数。
     *
     * Args:
     *     无
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity:onCreate`
     *     - `AiSettingsActivity:showProviderPicker`
     *     - `AiSettingsActivity:showBaseUrlDialog`
     *     - `AiSettingsActivity:showApiKeyDialog`
     *     - `AiSettingsActivity:showModelPicker`
     *     - `AiSettingsActivity:showReasoningPicker`
     *     - `AiSettingsActivity:showRoleSelectorDialog`
     */
    private fun loadData() {
        val names = resources.getStringArray(R.array.provider_names)
        tvProviderValue.text = names.getOrNull(currentPreset) ?: "自定义接口"
        tvBaseUrlValue.text = LlmConfig.getEffectiveBaseUrl(this, currentPreset)

        val key = LlmConfig.getApiKey(this, currentPreset)
        tvApiKeyValue.text = if (key.isBlank()) "未配置" else maskApiKey(key)

        val model = LlmConfig.getModel(this, currentPreset)
        tvModelValue.text = if (model.isBlank()) "点击选择模型" else model

        val effort = LlmConfig.getReasoningEffort(this)
        tvReasoningValue.text = getReasoningTitle(effort)

        val role = LlmConfig.getSelectedRole(this)
        tvRoleValue.text = role.name

        val temp = LlmConfig.getTemperature(this)
        tvTempValue.text = String.format(Locale.US, "%.1f", temp)
        sbTemp.progress = (temp * 10).roundToInt().coerceIn(0, 20)

        val contextCount = LlmConfig.getContextCount(this)
        tvContextCountValue.text = if (contextCount == 0) "已关闭" else "$contextCount 条"
        sbContextCount.progress = contextCount

        switchExcludeImage.isChecked = LlmConfig.isExcludeImage(this)
        switchExcludeEmoji.isChecked = LlmConfig.isExcludeEmoji(this)
        switchExcludeCard.isChecked = LlmConfig.isExcludeCard(this)
        switchExcludeVoice.isChecked = LlmConfig.isExcludeVoice(this)

        switchQuoteOnSend.isChecked = LlmConfig.isQuoteOnSend(this)
        switchMultiChoice.isChecked = LlmConfig.isMultiChoiceEnabled(this)

        switchSessionMemory.isChecked = LlmConfig.isSessionMemoryEnabled(this)
        switchContextCompress.isChecked = LlmConfig.isContextCompressEnabled(this)
        val threshold = LlmConfig.getContextCompressThreshold(this)
        tvCompressThresholdValue.text = "$threshold 字符"
        val keepTurns = LlmConfig.getContextCompressKeepTurns(this)
        tvCompressKeepTurnsValue.text = "$keepTurns 轮"

        switchVisionCaption.isChecked = LlmConfig.isVisionCaptionEnabled(this)
        val vBaseUrl = LlmConfig.getVisionBaseUrl(this)
        tvVisionBaseUrlValue.text = if (vBaseUrl.isBlank()) "继承主接口地址" else vBaseUrl
        val vApiKey = LlmConfig.getVisionApiKey(this)
        tvVisionApiKeyValue.text = if (vApiKey.isBlank()) "继承主接口密钥" else maskApiKey(vApiKey)
        val vModel = LlmConfig.getVisionModel(this)
        tvVisionModelValue.text = if (vModel.isBlank()) "继承主接口模型" else vModel
    }

    private fun getReasoningTitle(effort: Int): String {
        return when (effort) {
            LlmConfig.REASONING_LOW -> "低"
            LlmConfig.REASONING_MEDIUM -> "中"
            LlmConfig.REASONING_HIGH -> "高"
            else -> "关闭"
        }
    }

    private fun maskApiKey(key: String): String {
        return if (key.length <= 8) "••••••••" else "${key.take(4)}••••••••${key.takeLast(4)}"
    }

    // 创建与界面卡片严格同色、同风格的 Dialog Builder
    private fun createThemedDialogBuilder(): AlertDialog.Builder {
        val isNight = isNightMode()
        val themeRes = if (isNight) {
            android.R.style.Theme_DeviceDefault_Dialog_Alert
        } else {
            android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        }
        return AlertDialog.Builder(this, themeRes)
    }

    // 格式化 Dialog 窗口底色，确保与卡片背景完全一致
    private fun showAndStylizeDialog(dialog: AlertDialog) {
        val isNight = isNightMode()
        val cardColor = if (isNight) Color.parseColor("#242424") else Color.parseColor("#FFFFFF")
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(cardColor)
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(bgDrawable)

        val accentColor = if (isNight) Color.parseColor("#38ACFF") else Color.parseColor("#0099FF")
        val cancelColor = if (isNight) Color.parseColor("#98989F") else Color.parseColor("#8E8E93")
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(accentColor)
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.setTextColor(cancelColor)
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.setTextColor(accentColor)
    }

    // 1. 服务商选择抽屉
    private fun showProviderPicker() {
        val names: Array<CharSequence> = resources.getStringArray(R.array.provider_names)
            .map { it as CharSequence }.toTypedArray()

        val dialog = createThemedDialogBuilder()
            .setTitle("选择服务商")
            .setSingleChoiceItems(names, currentPreset, DialogInterface.OnClickListener { d, which ->
                if (which != currentPreset) {
                    currentPreset = which
                    LlmConfig.setSelectedPreset(this, currentPreset)
                    loadData()
                }
                d.dismiss()
            })
            .setNegativeButton("取消", null)
            .create()

        showAndStylizeDialog(dialog)
    }

    // 2. 接口地址修改
    private fun showBaseUrlDialog() {
        val isNight = isNightMode()
        if (currentPreset != PresetRegistry.CUSTOM) {
            val names = resources.getStringArray(R.array.provider_names)
            val providerName = names.getOrNull(currentPreset) ?: "当前服务商"
            val defaultUrl = PresetRegistry.getPresetBaseUrl(currentPreset)
            val dialog = createThemedDialogBuilder()
                .setTitle("接口地址")
                .setMessage("当前服务商 [${providerName}] 使用官方默认接口：\n\n${defaultUrl}\n\n如需自定义接口地址，请在服务商中切换为「自定义接口」。")
                .setPositiveButton("我知道了", null)
                .create()
            showAndStylizeDialog(dialog)
            return
        }

        val currentUrl = LlmConfig.getCustomBaseUrl(this)
        val input = EditText(this).apply {
            hint = "例如：https://api.openai.com/v1"
            setText(currentUrl)
            setSelection(text.length)
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
            setTextColor(if (isNight) Color.parseColor("#EBEBF5") else Color.parseColor("#1F1F1F"))
            setHintTextColor(if (isNight) Color.parseColor("#636366") else Color.parseColor("#BCBCC0"))
            setPadding(48, 36, 48, 36)
        }

        val dialog = createThemedDialogBuilder()
            .setTitle("设置自定义接口地址")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val newUrl = input.text.toString().trim()
                LlmConfig.setCustomBaseUrl(this, newUrl)
                tvBaseUrlValue.text = LlmConfig.getEffectiveBaseUrl(this, currentPreset)
            }
            .setNegativeButton("取消", null)
            .create()

        showAndStylizeDialog(dialog)
    }

    // 3. API 密钥输入
    private fun showApiKeyDialog() {
        val isNight = isNightMode()
        val currentKey = LlmConfig.getApiKey(this, currentPreset)
        val input = EditText(this).apply {
            hint = "请输入 API 密钥"
            setText(currentKey)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            setTextColor(if (isNight) Color.parseColor("#EBEBF5") else Color.parseColor("#1F1F1F"))
            setHintTextColor(if (isNight) Color.parseColor("#636366") else Color.parseColor("#BCBCC0"))
            setPadding(48, 36, 48, 36)
        }

        val dialog = createThemedDialogBuilder()
            .setTitle("设置 API 密钥")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val newKey = input.text.toString().trim()
                LlmConfig.setApiKey(this, currentPreset, newKey)
                tvApiKeyValue.text = if (newKey.isBlank()) "未配置" else maskApiKey(newKey)
            }
            .setNegativeButton("取消", null)
            .create()

        showAndStylizeDialog(dialog)
    }

    /**
     * 弹出设置视觉理解（图片反推模型）自定义 Base URL 的对话框
     *
     * 允许用户指定专用的多模态视觉 API 服务地址（如中转地址、本地 Ollama 或自建 vLLM），
     * 留空时系统将默认继承主模型当前生效的服务地址。
     *
     * Args:
     *     无
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity:bindViews`
     */
    private fun showVisionBaseUrlDialog() {
        val isNight = isNightMode()
        val currentUrl = LlmConfig.getVisionBaseUrl(this)
        val input = EditText(this).apply {
            hint = "留空则继承主接口地址"
            setText(currentUrl)
            setSelection(text.length)
            inputType = InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_CLASS_TEXT
            setTextColor(if (isNight) Color.parseColor("#EBEBF5") else Color.parseColor("#1F1F1F"))
            setHintTextColor(if (isNight) Color.parseColor("#636366") else Color.parseColor("#BCBCC0"))
            setPadding(48, 36, 48, 36)
        }

        val dialog = createThemedDialogBuilder()
            .setTitle("视觉接口地址")
            .setMessage("指定独立的视觉接口地址。若留空，则自动继承主接口地址。")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val newUrl = input.text.toString().trim()
                LlmConfig.setVisionBaseUrl(this, newUrl)
                tvVisionBaseUrlValue.text = if (newUrl.isBlank()) "继承主接口地址" else newUrl
            }
            .setNegativeButton("取消", null)
            .create()

        showAndStylizeDialog(dialog)
    }

    /**
     * 弹出设置视觉理解专用 API 密钥的对话框
     *
     * 允许用户为专用图片反推接口配置独立的身份验证 Token，
     * 留空时系统将默认继承主模型当前配置的 API 密钥。
     *
     * Args:
     *     无
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity:bindViews`
     */
    private fun showVisionApiKeyDialog() {
        val isNight = isNightMode()
        val currentKey = LlmConfig.getVisionApiKey(this)
        val input = EditText(this).apply {
            hint = "留空则继承主接口密钥"
            setText(currentKey)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            setTextColor(if (isNight) Color.parseColor("#EBEBF5") else Color.parseColor("#1F1F1F"))
            setHintTextColor(if (isNight) Color.parseColor("#636366") else Color.parseColor("#BCBCC0"))
            setPadding(48, 36, 48, 36)
        }

        val dialog = createThemedDialogBuilder()
            .setTitle("视觉 API 密钥")
            .setMessage("指定独立的视觉 API 密钥。若留空，则自动继承主接口密钥。")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val newKey = input.text.toString().trim()
                LlmConfig.setVisionApiKey(this, newKey)
                tvVisionApiKeyValue.text = if (newKey.isBlank()) "继承主接口密钥" else maskApiKey(newKey)
            }
            .setNegativeButton("取消", null)
            .create()

        showAndStylizeDialog(dialog)
    }

    /**
     * 弹出设置视觉理解模型名称的对话框
     *
     * 允许用户为专用图片反推接口指定独立的多模态模型名称，
     * 留空时系统将默认继承当前主模型配置的模型标识。
     *
     * Args:
     *     无
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity:bindViews`
     */
    private fun showVisionModelDialog() {
        val isNight = isNightMode()
        val currentModel = LlmConfig.getVisionModel(this)
        val input = EditText(this).apply {
            hint = "留空则继承主接口模型"
            setText(currentModel)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(if (isNight) Color.parseColor("#EBEBF5") else Color.parseColor("#1F1F1F"))
            setHintTextColor(if (isNight) Color.parseColor("#636366") else Color.parseColor("#BCBCC0"))
            setPadding(48, 36, 48, 36)
        }

        val dialog = createThemedDialogBuilder()
            .setTitle("视觉模型")
            .setMessage("指定独立的视觉模型。若留空，则自动继承主接口模型。")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val newModel = input.text.toString().trim()
                LlmConfig.setVisionModel(this, newModel)
                tvVisionModelValue.text = if (newModel.isBlank()) "继承主接口模型" else newModel
            }
            .setNegativeButton("取消", null)
            .create()

        showAndStylizeDialog(dialog)
    }

    // 4. 模型选择与拉取 (底部抽屉：内置静默拉取在线模型、实时搜索与自定义模型输入)
    private fun showModelPicker() {
        AiModelPicker.show(this, currentPreset) { selectedModel ->
            tvModelValue.text = selectedModel
        }
    }

    // 5. 测试链接
    private fun testApiConnection() {
        val apiKey = LlmConfig.getApiKey(this, currentPreset)
        if (apiKey.isBlank()) {
            Toast.makeText(this, "请先填写 API 密钥", Toast.LENGTH_SHORT).show()
            return
        }

        val baseUrl = LlmConfig.getEffectiveBaseUrl(this, currentPreset)
        val model = LlmConfig.getModel(this, currentPreset)

        pbTest.visibility = View.VISIBLE
        tvTestStatus.text = "正在检测..."
        tvTestStatus.setTextColor(Color.parseColor("#8E8E93"))

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                OpenAICompatClient.testChatCompletions(baseUrl, apiKey, model)
            }
            pbTest.visibility = View.GONE

            if (result.isSuccess) {
                tvTestStatus.text = "连通成功 (${result.durationMs}ms)"
                tvTestStatus.setTextColor(Color.parseColor("#34C759"))
                Toast.makeText(this@AiSettingsActivity, "API 连通测试成功！响应延迟: ${result.durationMs}ms", Toast.LENGTH_SHORT).show()
            } else {
                val err = result.error ?: "未知错误"
                tvTestStatus.text = "连通失败"
                tvTestStatus.setTextColor(Color.parseColor("#FF3B30"))
                val dialog = createThemedDialogBuilder()
                    .setTitle("测试失败")
                    .setMessage("错误信息：$err\n\n请检查 API 密钥、接口地址及网络连通性。")
                    .setPositiveButton("确定", null)
                    .create()
                showAndStylizeDialog(dialog)
            }
        }
    }

    // 6. 角色选择与管理 (支持内置角色、自定义角色、新增、编辑、删除)
    private fun showRoleSelectorDialog() {
        val isNight = isNightMode()
        val colorTextPrimary = if (isNight) Color.parseColor("#EBEBF5") else Color.parseColor("#1F1F1F")
        val colorTextSecondary = if (isNight) Color.parseColor("#8E8E93") else Color.parseColor("#8E8E93")
        val colorAccent = if (isNight) Color.parseColor("#38ACFF") else Color.parseColor("#0099FF")

        val currentRole = LlmConfig.getSelectedRole(this)
        val allRoles = LlmConfig.getAllRoles(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        var dialogRef: AlertDialog? = null

        // 顶部添加角色按钮
        val btnAddRole = TextView(this).apply {
            text = "+ 添加新角色"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorAccent)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(12))
            setOnClickListener {
                dialogRef?.dismiss()
                showRoleEditorDialog(null)
            }
        }
        container.addView(btnAddRole)

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300))
        }

        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        allRoles.forEach { role ->
            val isSelected = role.id == currentRole.id
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
                background = GradientDrawable().apply {
                    setColor(if (isSelected) (if (isNight) Color.parseColor("#263238") else Color.parseColor("#EDF6FF"))
                             else (if (isNight) Color.parseColor("#1C1C1E") else Color.parseColor("#F5F6F8")))
                    cornerRadius = dp(10).toFloat()
                    if (isSelected) {
                        setStroke(dp(1), colorAccent)
                    }
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }

            // 文本信息区（点击切换选中）
            val textLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    LlmConfig.setSelectedRoleId(this@AiSettingsActivity, role.id)
                    tvRoleValue.text = role.name
                    dialogRef?.dismiss()
                    Toast.makeText(this@AiSettingsActivity, "已切换为角色: ${role.name}", Toast.LENGTH_SHORT).show()
                }
            }

            val tvName = TextView(this).apply {
                text = role.name
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isSelected) colorAccent else colorTextPrimary)
            }

            val tvPromptPreview = TextView(this).apply {
                text = role.prompt
                textSize = 12f
                setTextColor(colorTextSecondary)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            }

            textLayout.addView(tvName)
            textLayout.addView(tvPromptPreview)
            row.addView(textLayout)

            // 操作区：自定义角色显示「编辑」与「删除」
            if (!role.isBuiltIn) {
                val btnEdit = TextView(this).apply {
                    text = "编辑"
                    textSize = 13f
                    setTextColor(colorAccent)
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    setOnClickListener {
                        dialogRef?.dismiss()
                        showRoleEditorDialog(role)
                    }
                }

                val btnDelete = TextView(this).apply {
                    text = "删除"
                    textSize = 13f
                    setTextColor(Color.parseColor("#FF3B30"))
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    setOnClickListener {
                        dialogRef?.dismiss()
                        showDeleteConfirmDialog(role)
                    }
                }

                row.addView(btnEdit)
                row.addView(btnDelete)
            } else if (isSelected) {
                val tvCurrentTag = TextView(this).apply {
                    text = "当前"
                    textSize = 12f
                    setTextColor(colorAccent)
                    setPadding(dp(6), dp(2), dp(6), dp(2))
                }
                row.addView(tvCurrentTag)
            }

            listLayout.addView(row)
        }

        scroll.addView(listLayout)
        container.addView(scroll)

        val dialog = createThemedDialogBuilder()
            .setTitle("角色")
            .setView(container)
            .setNegativeButton("关闭", null)
            .create()

        dialogRef = dialog
        showAndStylizeDialog(dialog)
    }

    // 角色编辑 / 新建弹窗
    private fun showRoleEditorDialog(roleToEdit: Role?) {
        val isNight = isNightMode()
        val isCreating = roleToEdit == null

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }

        val tvNameHint = TextView(this).apply {
            text = "角色名称"
            textSize = 13f
            setTextColor(if (isNight) Color.parseColor("#8E8E93") else Color.parseColor("#8E8E93"))
        }
        val etName = EditText(this).apply {
            hint = "例如：毒舌损友、英汉翻译家"
            setText(roleToEdit?.name ?: "")
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(if (isNight) Color.parseColor("#EBEBF5") else Color.parseColor("#1F1F1F"))
            setHintTextColor(if (isNight) Color.parseColor("#636366") else Color.parseColor("#BCBCC0"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val tvPromptHint = TextView(this).apply {
            text = "人设提示词"
            textSize = 13f
            setTextColor(if (isNight) Color.parseColor("#8E8E93") else Color.parseColor("#8E8E93"))
            setPadding(0, dp(12), 0, 0)
        }
        val etPrompt = EditText(this).apply {
            hint = "详细描述该角色的性格、语气规范与回复习惯..."
            setText(roleToEdit?.prompt ?: "")
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            setTextColor(if (isNight) Color.parseColor("#EBEBF5") else Color.parseColor("#1F1F1F"))
            setHintTextColor(if (isNight) Color.parseColor("#636366") else Color.parseColor("#BCBCC0"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        container.addView(tvNameHint)
        container.addView(etName)
        container.addView(tvPromptHint)
        container.addView(etPrompt)

        val dialog = createThemedDialogBuilder()
            .setTitle(if (isCreating) "添加新角色" else "编辑角色")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim()
                val prompt = etPrompt.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, "角色名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (prompt.isBlank()) {
                    Toast.makeText(this, "人设提示词不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val roleId = roleToEdit?.id ?: System.currentTimeMillis().toString()
                val newRole = Role(
                    id = roleId,
                    name = name,
                    prompt = prompt,
                    isBuiltIn = false
                )
                LlmConfig.saveCustomRole(this, newRole)
                LlmConfig.setSelectedRoleId(this, newRole.id)
                tvRoleValue.text = newRole.name
                Toast.makeText(this, "已保存角色: $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()

        showAndStylizeDialog(dialog)
    }

    private fun showDeleteConfirmDialog(role: Role) {
        val dialog = createThemedDialogBuilder()
            .setTitle("删除角色")
            .setMessage("确定要删除角色「${role.name}」吗？此操作无法撤销。")
            .setPositiveButton("删除") { _, _ ->
                LlmConfig.deleteCustomRole(this, role.id)
                val current = LlmConfig.getSelectedRole(this)
                tvRoleValue.text = current.name
                Toast.makeText(this, "已删除角色「${role.name}」", Toast.LENGTH_SHORT).show()
                showRoleSelectorDialog()
            }
            .setNegativeButton("取消") { _, _ ->
                showRoleSelectorDialog()
            }
            .create()

        showAndStylizeDialog(dialog)
    }

    // 7. 推理强度选择抽屉
    private fun showReasoningPicker() {
        val items = arrayOf<CharSequence>("关闭", "低", "中", "高")
        val efforts = intArrayOf(
            LlmConfig.REASONING_OFF,
            LlmConfig.REASONING_LOW,
            LlmConfig.REASONING_MEDIUM,
            LlmConfig.REASONING_HIGH
        )
        val currentEffort = LlmConfig.getReasoningEffort(this)
        val checkedIndex = efforts.indexOf(currentEffort).coerceAtLeast(0)

        val dialog = createThemedDialogBuilder()
            .setTitle("推理")
            .setSingleChoiceItems(items, checkedIndex, DialogInterface.OnClickListener { d, which ->
                val newEffort = efforts[which]
                LlmConfig.setReasoningEffort(this, newEffort)
                tvReasoningValue.text = items[which]
                d.dismiss()
            })
            .setNegativeButton("取消", null)
            .create()

        showAndStylizeDialog(dialog)
    }

    /**
     * 弹出上下文条数精确数值输入对话框
     *
     * 允许用户直接键盘输入 0~1000 范围内的数字，避免在 1000 档高精度滑动条上难以对齐目标数值。
     * 输入校验通过后同步更新配置存储、界面文本展示及 SeekBar 滑动条进度。
     *
     * Args:
     *     无
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity:bindViews`
     */
    private fun showContextCountInputDialog() {
        val currentCount = LlmConfig.getContextCount(this)
        val etInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentCount.toString())
            setSelection(text.length)
            val isNight = isNightMode()
            setTextColor(if (isNight) Color.parseColor("#EBEBF5") else Color.parseColor("#1A1A1A"))
            setHintTextColor(if (isNight) Color.parseColor("#636366") else Color.parseColor("#C7C7CC"))
            hint = "0~1000 (0为关闭)"
        }

        val container = FrameLayout(this).apply {
            val padH = dp(20)
            val padV = dp(12)
            setPadding(padH, padV, padH, padV)
            addView(etInput)
        }

        val dialog = createThemedDialogBuilder()
            .setTitle("设置上下文条数")
            .setMessage("请输入 0 到 1000 之间的整数（输入 0 表示完全关闭上下文附带）：")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val text = etInput.text.toString().trim()
                val count = text.toIntOrNull()
                if (count == null || count !in 0..1000) {
                    Toast.makeText(this, "请输入 0~1000 范围内的有效整数", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                LlmConfig.setContextCount(this, count)
                sbContextCount.progress = count
                tvContextCountValue.text = if (count == 0) "已关闭" else "$count 条"
                Toast.makeText(this, "上下文条数已更新为 $count 条", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()

        showAndStylizeDialog(dialog)
    }

    /**
     * 弹出智能上下文压缩触发阈值选择对话框
     *
     * 提供常见字数阈值档位（1000、2000、4000、8000、15000 字符）及自定义输入入口，
     * 当未压缩的历史消息总字符数达到所选阈值时，自动触发异步摘要提取与历史归档。
     *
     * Args:
     *     无
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity:bindViews`
     */
    private fun showCompressThresholdPicker() {
        val options = arrayOf<CharSequence>(
            "1000 字符",
            "2000 字符",
            "4000 字符",
            "8000 字符",
            "15000 字符",
            "自定义"
        )
        val values = intArrayOf(1000, 2000, 4000, 8000, 15000, -1)
        val currentThreshold = LlmConfig.getContextCompressThreshold(this)
        val selectedIdx = values.indexOf(currentThreshold).let { if (it >= 0) it else 5 }

        val dialog = createThemedDialogBuilder()
            .setTitle("选择压缩触发阈值")
            .setSingleChoiceItems(options, selectedIdx) { d, which ->
                d.dismiss()
                val targetValue = values[which]
                if (targetValue == -1) {
                    showCustomCompressThresholdDialog()
                } else {
                    LlmConfig.setContextCompressThreshold(this, targetValue)
                    tvCompressThresholdValue.text = "$targetValue 字符"
                }
            }
            .setNegativeButton("取消", null)
            .create()

        showAndStylizeDialog(dialog)
    }

    /**
     * 弹出自定义压缩触发阈值输入对话框
     *
     * 允许用户输入任意 500~100000 之间的字符阈值。
     *
     * Args:
     *     无
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity:showCompressThresholdPicker`
     */
    private fun showCustomCompressThresholdDialog() {
        val currentVal = LlmConfig.getContextCompressThreshold(this)
        val etInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentVal.toString())
            setSelection(text.length)
            val isNight = isNightMode()
            setTextColor(if (isNight) Color.parseColor("#EBEBF5") else Color.parseColor("#1A1A1A"))
            setHintTextColor(if (isNight) Color.parseColor("#636366") else Color.parseColor("#C7C7CC"))
            hint = "500~100000"
        }

        val container = FrameLayout(this).apply {
            val padH = dp(20)
            val padV = dp(12)
            setPadding(padH, padV, padH, padV)
            addView(etInput)
        }

        val dialog = createThemedDialogBuilder()
            .setTitle("自定义压缩阈值")
            .setMessage("请输入未压缩历史触发自动提炼的字符数阈值（500 ~ 100000）：")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val v = etInput.text.toString().trim().toIntOrNull()
                if (v == null || v !in 500..100000) {
                    Toast.makeText(this, "请输入 500~100000 之间的有效数字", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                LlmConfig.setContextCompressThreshold(this, v)
                tvCompressThresholdValue.text = "$v 字符"
            }
            .setNegativeButton("取消", null)
            .create()

        showAndStylizeDialog(dialog)
    }

    /**
     * 弹出压缩保留最新轮数选择对话框
     *
     * 选择在触发智能压缩时保留多少轮最新近的真实对白（不参与浓缩，原样呈递给大模型）。
     *
     * Args:
     *     无
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity:bindViews`
     */
    private fun showCompressKeepTurnsPicker() {
        val options = arrayOf<CharSequence>("2 轮", "3 轮", "5 轮", "8 轮", "10 轮", "15 轮")
        val values = intArrayOf(2, 3, 5, 8, 10, 15)
        val currentTurns = LlmConfig.getContextCompressKeepTurns(this)
        val selectedIdx = values.indexOf(currentTurns).let { if (it >= 0) it else 2 }

        val dialog = createThemedDialogBuilder()
            .setTitle("选择压缩保留最新轮数")
            .setSingleChoiceItems(options, selectedIdx) { d, which ->
                val turns = values[which]
                LlmConfig.setContextCompressKeepTurns(this, turns)
                tvCompressKeepTurnsValue.text = "$turns 轮"
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .create()

        showAndStylizeDialog(dialog)
    }

    /**
     * 弹出已存储会话记忆管理对话框
     *
     * 从 SQLite 数据库中异步查询群聊与私聊的所有会话记忆记录，
     * 呈现包含会话标题、类型、更新时间及记忆规模的列表，支持单条点击查看/删除与一键清空全部。
     *
     * Args:
     *     无
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity:bindViews`
     */
    private fun showSessionManagerDialog() {
        scope.launch {
            val sessions = withContext(Dispatchers.IO) {
                SessionMemoryManager.getAllSessions(this@AiSettingsActivity)
            }

            if (sessions.isEmpty()) {
                val emptyDialog = createThemedDialogBuilder()
                    .setTitle("会话记忆管理")
                    .setMessage("当前数据库中暂无任何已持久化的会话记忆数据。\n当在群聊或私聊中使用 AI 生成并发送回复后，系统将自动建立并累积对应会话的记忆。")
                    .setPositiveButton("知道了", null)
                    .create()
                showAndStylizeDialog(emptyDialog)
                return@launch
            }

            val items = sessions.map { sessionItem ->
                val typeTag = if (sessionItem.sessionType == SessionMemoryManager.SCENE_GROUP) "[群聊]" else "[私聊]"
                val summaryInfo = if (sessionItem.summary.isBlank()) "无长期提要" else "提要: ${sessionItem.summary.length}字"
                "$typeTag ${sessionItem.sessionTitle}\n$summaryInfo | 历史: ${sessionItem.turnCount}轮"
            }.toTypedArray<CharSequence>()

            val dialog = createThemedDialogBuilder()
                .setTitle("会话记忆列表 (共 ${sessions.size} 个)")
                .setItems(items) { _, which ->
                    val chosen = sessions[which]
                    scope.launch {
                        val detail = withContext(Dispatchers.IO) {
                            SessionMemoryManager.getSession(this@AiSettingsActivity, chosen.sessionId)
                        }
                        if (detail != null) {
                            showSessionDetailDialog(detail)
                        }
                    }
                }
                .setNeutralButton("清空全部") { _, _ ->
                    showClearAllSessionsConfirmDialog()
                }
                .setNegativeButton("关闭", null)
                .create()

            showAndStylizeDialog(dialog)
        }
    }

    /**
     * 弹出指定会话记忆的详细信息展示与管理对话框
     *
     * 展示会话标识、标题、长期记忆事实提要及最近历史对白概览，并提供单条会话清除功能。
     *
     * Args:
     *     session (SessionMemory): 当前选中的会话记忆实体对象。
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity:showSessionManagerDialog`
     */
    private fun showSessionDetailDialog(session: SessionMemory) {
        val typeStr = if (session.sessionType == SessionMemoryManager.SCENE_GROUP) "群聊" else "私聊"
        val summaryContent = if (session.summary.isBlank()) "(暂无浓缩长期记忆提要)" else session.summary
        val historyOverview = if (session.turns.isEmpty()) {
            "(暂无未归档历史对白)"
        } else {
            session.turns.takeLast(3).joinToString("\n") { turn ->
                "• 问: ${turn.userPrompt.take(30)}...\n  答: ${turn.assistantReply.take(30)}..."
            }
        }

        val message = "【会话标识】${session.sessionId}\n" +
                "【会话名称】${session.sessionTitle} ($typeStr)\n\n" +
                "【长期事实记忆提要】\n$summaryContent\n\n" +
                "【最近对白记录 (共${session.turns.size}轮)】\n$historyOverview"

        val dialog = createThemedDialogBuilder()
            .setTitle("会话记忆详情")
            .setMessage(message)
            .setPositiveButton("删除该会话记忆") { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        SessionMemoryManager.deleteSession(this@AiSettingsActivity, session.sessionId)
                    }
                    Toast.makeText(this@AiSettingsActivity, "已清除该会话的记忆数据", Toast.LENGTH_SHORT).show()
                    showSessionManagerDialog()
                }
            }
            .setNegativeButton("关闭") { _, _ ->
                showSessionManagerDialog()
            }
            .create()

        showAndStylizeDialog(dialog)
    }

    /**
     * 弹出清空全部会话记忆的二次确认对话框
     *
     * 防范用户误触，确认后清空 SQLite 数据库中的全部会话记忆与对话历史。
     *
     * Args:
     *     无
     *
     * Returns:
     *     Unit
     *
     * Callers:
     *     - `AiSettingsActivity:showSessionManagerDialog`
     */
    private fun showClearAllSessionsConfirmDialog() {
        val dialog = createThemedDialogBuilder()
            .setTitle("清空全部记忆")
            .setMessage("确定要清空所有群聊与私聊的长期记忆与对话历史数据吗？\n此操作将重置全部已沉淀的记忆提要且不可恢复。")
            .setPositiveButton("确认清空") { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        SessionMemoryManager.clearAllSessions(this@AiSettingsActivity)
                    }
                    Toast.makeText(this@AiSettingsActivity, "已成功清空全部会话记忆", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消") { _, _ ->
                showSessionManagerDialog()
            }
            .create()

        showAndStylizeDialog(dialog)
    }
}
