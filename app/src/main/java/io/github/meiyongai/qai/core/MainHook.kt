package io.github.meiyongai.qai.core

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.meiyongai.qai.hook.AioInputHelper
import io.github.meiyongai.qai.hook.AioMenuHook
import io.github.meiyongai.qai.hook.SettingHook

class MainHook : XposedModule() {

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != "com.tencent.mobileqq") return
        initHooks(param.defaultClassLoader)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != "com.tencent.mobileqq") return
        initHooks(param.classLoader)
    }

    private var initialized = false

    @Synchronized
    private fun initHooks(classLoader: ClassLoader?) {
        if (classLoader == null || initialized) return
        initialized = true
        SettingHook.init(this, classLoader)
        AioMenuHook.init(this, classLoader)
        AioInputHelper.init(this, classLoader)
        io.github.meiyongai.qai.hook.ActivityStubManager.init(this)
    }
}
