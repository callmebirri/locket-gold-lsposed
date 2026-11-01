package com.locket.gold

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

internal object SourceCodeHook {
    fun hookSourceCodeModule(cl: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists("com.facebook.react.modules.debug.SourceCodeModule", cl)
        if (cls != null) {
            ClassRegistry.add(cls.name)
            hookSourceCodeClass(cls)
        }
    }

    fun hookSourceCodeClass(cls: Class<*>) {
        try {
            val constantsHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val result = param.result
                        if (result !is Map<*, *>) return

                        @Suppress("UNCHECKED_CAST")
                        val map = HashMap<String, Any?>(result as Map<String, Any?>)
                        val scriptUrl = map["scriptURL"]
                        if (scriptUrl !is String) return

                        if (scriptUrl.contains(BUNDLE_CACHE_PREFIX)) {
                            map["scriptURL"] = STOCK_BUNDLE_ASSET_URL
                            param.result = map
                            log("SourceCode.scriptURL $scriptUrl -> $STOCK_BUNDLE_ASSET_URL")
                        }
                    } catch (t: Throwable) {
                        log("Error in SourceCodeModule constants hook: $t")
                    }
                }
            }
            XposedBridge.hookAllMethods(cls, "getTypedExportedConstants", constantsHook)
            XposedBridge.hookAllMethods(cls, "getConstants", constantsHook)
            log("Hooked SourceCodeModule constants (" + cls.name + ")")
        } catch (t: Throwable) {
            log("Error hooking SourceCodeModule: $t")
        }
    }
}
