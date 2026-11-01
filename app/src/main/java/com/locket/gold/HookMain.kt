package com.locket.gold

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class HookMain : IXposedHookLoadPackage {

    companion object {
        @JvmStatic
        @Synchronized
        fun initHooks(cl: ClassLoader?) {
            if (cl == null) return
            BundleHook.hookBundleLoading(cl)
            SourceCodeHook.hookSourceCodeModule(cl)
            FirebaseHook.hookFirebaseConfig(cl)
            MMKVHook.hookMMKV(cl)
            NetworkingHook.hookNetworking(cl)
            FirestoreHook.hookFirestore(cl)
            RevenueCatHook.hookRevenueCatPassive(cl)
            DynamicClassHook.hookDynamicClassLoading()
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (TARGET_PACKAGE != lpparam.packageName) return

        log("Loaded in " + lpparam.packageName + " (process " + lpparam.processName + ")")
        initHooks(lpparam.classLoader)
        BundleHook.hookApplicationOnCreate()
    }
}
