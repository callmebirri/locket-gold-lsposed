package com.locket.gold

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

internal object FirebaseHook {
    fun hookFirebaseConfig(cl: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists("io.invertase.firebase.config.ReactNativeFirebaseConfigModule", cl)
        if (cls != null) {
            ClassRegistry.add(cls.name)
            hookFirebaseConfigClass(cls)
            hookFirebaseConfigResult(cls)
        }
        val remoteConfigCls = XposedHelpers.findClassIfExists("com.google.firebase.remoteconfig.FirebaseRemoteConfig", cl)
        if (remoteConfigCls != null) {
            ClassRegistry.add(remoteConfigCls.name)
            hookFirebaseRemoteConfigClass(remoteConfigCls)
        }
    }

    fun hookFirebaseConfigResult(cls: Class<*>) {
        try {
            XposedBridge.hookAllMethods(cls, "resultWithConstants", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val result = param.result ?: return

                        val copyObj = XposedHelpers.callMethod(result, "toHashMap")
                        if (copyObj !is Map<*, *>) return

                        @Suppress("UNCHECKED_CAST")
                        val root = copyObj as MutableMap<String, Any?>
                        injectGoldConfigValues(root)

                        val constantsObj = root["constants"]
                        if (constantsObj is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val constants = constantsObj as MutableMap<String, Any?>
                            val valuesObj = constants["values"]
                            if (valuesObj is Map<*, *>) {
                                @Suppress("UNCHECKED_CAST")
                                val values = valuesObj as MutableMap<String, Any?>
                                injectGoldConfigValues(values)
                            } else {
                                val values = HashMap<String, Any?>()
                                injectGoldConfigValues(values)
                                constants["values"] = values
                            }
                        }

                        val classLoader = cls.classLoader ?: return
                        val arguments = classLoader.loadClass("com.facebook.react.bridge.Arguments")
                        val newMap = XposedHelpers.callStaticMethod(arguments, "makeNativeMap", root)
                        param.result = newMap
                        log("Injected gold flags into config constants (resultWithConstants)")
                    } catch (t: Throwable) {
                        log("resultWithConstants hook error: $t")
                    }
                }
            })
            log("Hooked ReactNativeFirebaseConfigModule.resultWithConstants")
        } catch (t: Throwable) {
            log("Error hooking resultWithConstants: $t")
        }
    }

    private fun injectGoldConfigValues(map: MutableMap<String, Any?>) {
        map[GOLD_OVERRIDE_KEY] = configValue("true")
    }

    private fun configValue(value: String): Map<String, Any?> {
        val valMap = HashMap<String, Any?>()
        valMap["value"] = value
        valMap["source"] = "remote"
        return valMap
    }

    fun hookFirebaseRemoteConfigClass(cls: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(cls, "getBoolean", String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = param.args[0] as? String
                    if (GOLD_OVERRIDE_KEY == key || "android_subscribe_gold_button_enabled" == key) {
                        param.result = true
                        log("FirebaseRemoteConfig.getBoolean($key) -> true")
                    }
                }
            })

            XposedHelpers.findAndHookMethod(cls, "getString", String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = param.args[0] as? String
                    if (GOLD_OVERRIDE_KEY == key) {
                        param.result = "true"
                        log("FirebaseRemoteConfig.getString($key) -> 'true'")
                    } else if ("android_camera_video_recording" == key) {
                        param.result = VIDEO_CONFIG_JSON
                        log("FirebaseRemoteConfig.getString(android_camera_video_recording) -> injected gold video config")
                    }
                }
            })
            log("Hooked FirebaseRemoteConfig native getBoolean/getString")
        } catch (t: Throwable) {
            log("Error hooking FirebaseRemoteConfig: $t")
        }
    }

    fun hookFirebaseConfigClass(cls: Class<*>) {
        try {
            XposedBridge.hookAllMethods(cls, "getConstants", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val res = param.result
                        if (res !is Map<*, *>) return

                        @Suppress("UNCHECKED_CAST")
                        val map = HashMap<String, Any?>(res as Map<String, Any?>)
                        putRemoteConfigValue(map, GOLD_OVERRIDE_KEY, "true")

                        val values = map["values"]
                        if (values is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val valuesMap = HashMap<String, Any?>(values as Map<String, Any?>)
                            putRemoteConfigValue(valuesMap, GOLD_OVERRIDE_KEY, "true")
                            map["values"] = valuesMap
                        }

                        param.result = map
                        log("Injected gold/video flags into ReactNativeFirebaseConfigModule.getConstants")
                    } catch (t: Throwable) {
                        log("Error in FirebaseConfigModule hook: $t")
                    }
                }
            })
            log("Hooked ReactNativeFirebaseConfigModule.getConstants")
        } catch (t: Throwable) {
            log("Error hooking ReactNativeFirebaseConfigModule: $t")
        }
    }

    private fun putRemoteConfigValue(map: MutableMap<String, Any?>, key: String, value: String) {
        val valObj = HashMap<String, Any?>()
        valObj["value"] = value
        valObj["source"] = "remote"
        map[key] = valObj
    }
}
