package io.github.meiyongai.qai.hook

import android.content.Context
import net.bytebuddy.ByteBuddy
import net.bytebuddy.android.AndroidClassLoadingStrategy
import net.bytebuddy.implementation.FixedValue
import net.bytebuddy.implementation.MethodCall
import net.bytebuddy.matcher.ElementMatchers
import java.io.File

object CustomMenuHelper {

    fun createMenuItem(
        context: Context,
        absMenuItemClass: Class<*>,
        msgItemClass: Class<*>,
        msgItem: Any,
        title: String,
        menuId: Int,
        onClick: () -> Unit
    ): Any {
        val clickMethod = absMenuItemClass.methods.firstOrNull {
            it.returnType == java.lang.Void.TYPE && it.parameterTypes.isEmpty()
        } ?: absMenuItemClass.declaredMethods.first {
            it.returnType == java.lang.Void.TYPE && it.parameterTypes.isEmpty()
        }

        val intMethods = (absMenuItemClass.methods + absMenuItemClass.declaredMethods)
            .filter { it.returnType == java.lang.Integer.TYPE && it.parameterTypes.isEmpty() }
            .distinctBy { it.name }

        val idMethod = intMethods.firstOrNull { 
            it.name.contains("id", ignoreCase = true) || it.name == "getId" || it.name == "getItemId"
        } ?: intMethods.getOrNull(1)

        val iconMethod = intMethods.firstOrNull { 
            it.name.contains("icon", ignoreCase = true) || it.name.contains("res", ignoreCase = true) || it != idMethod
        } ?: intMethods.getOrNull(0)

        // 直接获取 QQ 原生 AI 图标 qui_ai_summarize，无任何兜底
        val iconResId = pickIconResId(context)

        val dexDir = File(context.codeCacheDir, "qai_dex").apply { mkdirs() }
        val strategy = AndroidClassLoadingStrategy.Wrapping(dexDir)

        var builder = ByteBuddy()
            .subclass(absMenuItemClass)
            .method(ElementMatchers.returns(String::class.java))
            .intercept(FixedValue.value(title))

        if (idMethod != null) {
            builder = builder.method(ElementMatchers.named(idMethod.name)).intercept(FixedValue.value(menuId))
        }
        if (iconMethod != null && iconMethod != idMethod) {
            builder = builder.method(ElementMatchers.named(iconMethod.name)).intercept(FixedValue.value(iconResId))
        }
        if (idMethod == null && iconMethod == null) {
            builder = builder
                .method(ElementMatchers.returns(java.lang.Integer.TYPE as Class<*>))
                .intercept(FixedValue.value(menuId))
        }

        builder = builder
            .method(ElementMatchers.named(clickMethod.name))
            .intercept(MethodCall.call { onClick() })

        val dynamicClass = builder
            .make()
            .load(absMenuItemClass.classLoader, strategy)
            .loaded

        val ctor = runCatching {
            dynamicClass.getDeclaredConstructor(msgItemClass)
        }.getOrNull() ?: dynamicClass.declaredConstructors.first()

        return if (ctor.parameterTypes.isEmpty()) {
            ctor.newInstance()
        } else {
            ctor.newInstance(msgItem)
        }
    }

    private fun pickIconResId(context: Context): Int {
        return context.resources.getIdentifier("qui_ai_summarize", "drawable", context.packageName)
    }
}
