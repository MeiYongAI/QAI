package io.github.meiyongai.qai.hook

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method

object ActivityStubManager {
    private const val TAG = "QAI_StubMgr"
    const val STUB_ACTIVITY = "com.tencent.mobileqq.activity.photo.CameraPreviewActivity"
    const val EXTRA_TARGET_ACTIVITY = "qai_target_activity"

    private var initialized = false

    fun init(xposed: XposedModule) {
        if (initialized) return
        initialized = true

        // 方案一：通过 LibXposed hook Instrumentation.newActivity
        var hookedViaXposed = false
        try {
            val newActivityMethod = Instrumentation::class.java.getDeclaredMethod(
                "newActivity",
                ClassLoader::class.java,
                String::class.java,
                Intent::class.java
            )
            xposed.hook(newActivityMethod).intercept { chain ->
                val intent = chain.getArg(2) as? Intent
                val target = intent?.getStringExtra(EXTRA_TARGET_ACTIVITY)
                if (!target.isNullOrEmpty()) {
                    val moduleCl = ActivityStubManager::class.java.classLoader
                    val targetClass = runCatching { moduleCl?.loadClass(target) }.getOrNull()
                    if (targetClass != null) {
                        Log.i(TAG, "Creating stubbed activity via LibXposed: $target")
                        return@intercept targetClass.newInstance() as Activity
                    }
                }
                chain.proceed()
            }
            hookedViaXposed = true
            Log.i(TAG, "Hooked Instrumentation.newActivity successfully via LibXposed")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to hook Instrumentation.newActivity via LibXposed, trying reflection fallback", e)
        }

        // 方案二：作为兜底保障，通过反射注入 ProxyInstrumentation
        try {
            installReflectionProxy()
        } catch (e: Throwable) {
            if (!hookedViaXposed) {
                Log.e(TAG, "Both LibXposed and reflection proxy failed!", e)
            }
        }
    }

    private fun installReflectionProxy() {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentActivityThreadMethod = activityThreadClass.getDeclaredMethod("currentActivityThread")
        currentActivityThreadMethod.isAccessible = true
        val activityThread = currentActivityThreadMethod.invoke(null) ?: return

        val mInstrumentationField = activityThreadClass.getDeclaredField("mInstrumentation")
        mInstrumentationField.isAccessible = true
        val original = mInstrumentationField.get(activityThread) as? Instrumentation ?: return

        if (original is ProxyInstrumentation) {
            return
        }

        mInstrumentationField.set(activityThread, ProxyInstrumentation(original))
        Log.i(TAG, "Installed ProxyInstrumentation fallback successfully")
    }

    fun startActivity(context: Context, targetClass: Class<out Activity>) {
        val intent = Intent()
        val pkg = context.packageName
        intent.setClassName(pkg, STUB_ACTIVITY)
        intent.putExtra(EXTRA_TARGET_ACTIVITY, targetClass.name)
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to startActivity with stub: ${targetClass.name}", e)
        }
    }

    private class ProxyInstrumentation(private val base: Instrumentation) : Instrumentation() {
        override fun newActivity(cl: ClassLoader?, className: String?, intent: Intent?): Activity {
            val target = intent?.getStringExtra(EXTRA_TARGET_ACTIVITY)
            if (!target.isNullOrEmpty()) {
                val moduleCl = ActivityStubManager::class.java.classLoader
                val targetClass = runCatching { moduleCl?.loadClass(target) }.getOrNull()
                if (targetClass != null) {
                    Log.i(TAG, "Creating stubbed activity via ProxyInstrumentation: $target")
                    return targetClass.newInstance() as Activity
                }
            }
            return base.newActivity(cl, className, intent)
        }
    }
}
