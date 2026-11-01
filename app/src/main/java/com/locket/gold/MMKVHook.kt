package com.locket.gold

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

internal object MMKVHook {
    fun hookMMKV(cl: ClassLoader) {
        val mmkvClass = XposedHelpers.findClassIfExists("com.tencent.mmkv.MMKV", cl)
        if (mmkvClass != null) {
            ClassRegistry.add(mmkvClass.name)
            hookMMKVClass(mmkvClass)
        }
    }

    fun hookMMKVClass(mmkvClass: Class<*>) {
        try {
            XposedBridge.hookAllMethods(mmkvClass, "decodeString", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val res = param.result
                        if (res is String) {
                            val modified = mutateUserJsonString(res)
                            if (modified != res) {
                                param.result = modified
                                log("MMKV.decodeString() -> marked cached user as gold")
                            }
                        }
                    } catch (_: Throwable) {}
                }
            })
            log("Hooked MMKV.decodeString()")
        } catch (t: Throwable) {
            log("Error hooking MMKV: $t")
        }
    }
}
