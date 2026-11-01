package com.locket.gold

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

internal object DynamicClassHook {
    fun hookDynamicClassLoading() {
        val loaderHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val className = param.args[0] as? String
                val resultClass = param.result as? Class<*>
                if (resultClass == null || className == null) return
                if (!(className.startsWith("com.revenuecat.purchases")
                        || className.startsWith("io.invertase.firebase")
                        || className.startsWith("com.google.firebase")
                        || className.startsWith("com.facebook.react")
                        || className.startsWith("com.locket.Locket")
                        || className.startsWith("com.tencent.mmkv"))) return
                if (resultClass.classLoader == null) return
                hookSingleClass(className, resultClass)
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                ClassLoader::class.java, "loadClass",
                String::class.java, Boolean::class.javaPrimitiveType, loaderHook
            )
            val baseDex = Class.forName("dalvik.system.BaseDexClassLoader")
            XposedBridge.hookAllMethods(baseDex, "loadClass", loaderHook)
            log("Registered dynamic loadClass hooker")
        } catch (t: Throwable) {
            log("Error hooking loadClass: $t")
        }
    }

    @Synchronized
    private fun hookSingleClass(className: String, clazz: Class<*>) {
        if (ClassRegistry.contains(className)) return

        try {
            if (className.startsWith("io.invertase.firebase.config")
                    && className.endsWith("ReactNativeFirebaseConfigModule")) {
                ClassRegistry.add(className)
                FirebaseHook.hookFirebaseConfigClass(clazz)
                FirebaseHook.hookFirebaseConfigResult(clazz)
            } else if ("com.google.firebase.remoteconfig.FirebaseRemoteConfig" == className) {
                ClassRegistry.add(className)
                FirebaseHook.hookFirebaseRemoteConfigClass(clazz)
            } else if ("com.tencent.mmkv.MMKV" == className) {
                ClassRegistry.add(className)
                MMKVHook.hookMMKVClass(clazz)
            } else if ("com.facebook.react.modules.debug.SourceCodeModule" == className) {
                ClassRegistry.add(className)
                SourceCodeHook.hookSourceCodeClass(clazz)
            } else if ("com.facebook.react.modules.network.NetworkingModule" == className) {
                ClassRegistry.add(className)
                NetworkingHook.hookNetworkingClass(clazz)
            } else if (className.startsWith("com.google.firebase.firestore")) {
                ClassRegistry.add(className)
                FirestoreHook.hookFirestoreGetData(clazz)
            } else if ("com.revenuecat.purchases.EntitlementInfo" == className) {
                ClassRegistry.add(className)
                RevenueCatHook.hookEntitlementInfoPassive(clazz)
            }
        } catch (t: Throwable) {
            log("Error hooking single class $className: $t")
        }
    }
}
