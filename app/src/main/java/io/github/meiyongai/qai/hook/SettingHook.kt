package io.github.meiyongai.qai.hook

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Proxy

object SettingHook {
    private const val TAG = "QAI_Setting"

    fun init(xposed: XposedInterface, classLoader: ClassLoader) {
        Log.i(TAG, "Initializing SettingHook via LibXposed 102...")

        val providerClasses = listOf(
            "com.tencent.mobileqq.setting.main.NewSettingConfigProvider",
            "com.tencent.mobileqq.setting.main.b",
            "com.tencent.mobileqq.setting.main.MainSettingConfigProvider"
        )

        var hookTargetMethod: Method? = null
        for (name in providerClasses) {
            val clazz = runCatching { classLoader.loadClass(name) }.getOrNull() ?: continue
            val method = clazz.declaredMethods.firstOrNull {
                it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Context::class.java &&
                        List::class.java.isAssignableFrom(it.returnType)
            }
            if (method != null) {
                hookTargetMethod = method
                Log.i(TAG, "Found config provider method in: $name -> ${method.name}")
                break
            }
        }

        if (hookTargetMethod == null) {
            Log.e(TAG, "Config provider method not found")
            return
        }

        var kAbstractItemProcessor: Class<*>? = null
        for (parent in listOf(
            "com.tencent.mobileqq.setting.main.processor.AccountSecurityItemProcessor",
            "com.tencent.mobileqq.setting.main.processor.AboutItemProcessor"
        )) {
            val k = runCatching { classLoader.loadClass(parent) }.getOrNull() ?: continue
            kAbstractItemProcessor = k.superclass
            if (kAbstractItemProcessor != null) break
        }

        if (kAbstractItemProcessor == null) {
            Log.e(TAG, "AbstractItemProcessor not found")
            return
        }

        val candidateNames = listOf(
            "com.tencent.mobileqq.setting.processor.j",
            "com.tencent.mobileqq.setting.processor.i",
            "com.tencent.mobileqq.setting.processor.h",
            "com.tencent.mobileqq.setting.processor.g",
            "as3.i"
        )

        var kSimpleItemProcessor: Class<*>? = null
        for (name in candidateNames) {
            val k = runCatching { classLoader.loadClass(name) }.getOrNull() ?: continue
            if (k.superclass == kAbstractItemProcessor) {
                kSimpleItemProcessor = k
                Log.i(TAG, "Found SimpleItemProcessor: $name")
                break
            }
        }

        if (kSimpleItemProcessor == null) {
            Log.e(TAG, "SimpleItemProcessor not found")
            return
        }

        val setOnClickListener = kSimpleItemProcessor.declaredMethods.firstOrNull {
            it.returnType == Void.TYPE &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].name == "kotlin.jvm.functions.Function0"
        } ?: run {
            Log.e(TAG, "setOnClickListener not found on SimpleItemProcessor")
            return
        }

        var ctorSimpleItemProcessor: Constructor<*>? = null
        var ctorArgc = 0
        try {
            ctorSimpleItemProcessor = kSimpleItemProcessor.getDeclaredConstructor(
                Context::class.java, Int::class.javaPrimitiveType, CharSequence::class.java,
                Int::class.javaPrimitiveType, String::class.java
            )
            ctorArgc = 5
        } catch (_: NoSuchMethodException) {
            ctorSimpleItemProcessor = kSimpleItemProcessor.getDeclaredConstructor(
                Context::class.java, Int::class.javaPrimitiveType, CharSequence::class.java,
                Int::class.javaPrimitiveType
            )
            ctorArgc = 4
        }

        xposed.hook(hookTargetMethod).intercept { chain ->
            @Suppress("UNCHECKED_CAST")
            val result = chain.proceed() as? MutableList<Any>
            if (result != null && result.isNotEmpty()) {
                val ctx = chain.getArg(0) as Context

                val kItemProcessorGroup = result[0].javaClass
                val defaultMarkerClass = runCatching {
                    classLoader.loadClass("kotlin.jvm.internal.DefaultConstructorMarker")
                }.getOrNull()

                val ctorGroup: Constructor<*>? = if (defaultMarkerClass != null) {
                    runCatching {
                        kItemProcessorGroup.getDeclaredConstructor(
                            List::class.java, CharSequence::class.java, CharSequence::class.java,
                            Int::class.javaPrimitiveType, defaultMarkerClass
                        )
                    }.getOrNull()
                } else null ?: runCatching {
                    kItemProcessorGroup.getDeclaredConstructor(
                        List::class.java, CharSequence::class.java, CharSequence::class.java
                    )
                }.getOrNull()

                if (ctorGroup != null) {
                    @SuppressLint("DiscouragedApi")
                    val resId = ctx.resources.getIdentifier("qui_tuning", "drawable", ctx.getPackageName())

                    val entryItem = if (ctorArgc == 5) {
                        ctorSimpleItemProcessor!!.newInstance(ctx, 0x7F0B0001, "QAI", resId, null)
                    } else {
                        ctorSimpleItemProcessor!!.newInstance(ctx, 0x7F0B0001, "QAI", resId)
                    }

                    val thatFunction0 = setOnClickListener.parameterTypes[0]
                    val theUnit = thatFunction0.classLoader?.loadClass("kotlin.Unit")?.getField("INSTANCE")?.get(null)
                    val func0 = Proxy.newProxyInstance(classLoader, arrayOf(thatFunction0)) { _, method, _ ->
                        if (method.name == "invoke") {
                            ActivityStubManager.startActivity(ctx, io.github.meiyongai.qai.ui.AiSettingsActivity::class.java)
                            return@newProxyInstance theUnit
                        }
                        null
                    }
                    setOnClickListener.invoke(entryItem, func0)

                    val groupList = ArrayList<Any>(1)
                    groupList.add(entryItem)

                    val group = if (ctorGroup.parameterTypes.size == 5) {
                        ctorGroup.newInstance(groupList, "", "", 6, null)
                    } else {
                        ctorGroup.newInstance(groupList, "", "")
                    }

                    val insertIndex = if (result.size > 2) 2 else result.size
                    result.add(insertIndex, group)
                    Log.i(TAG, "QAI card item successfully injected via LibXposed at index $insertIndex")
                }
            }
            result
        }
    }
}
