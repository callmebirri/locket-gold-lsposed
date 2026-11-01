package com.locket.gold

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

internal object NetworkingHook {
    fun hookNetworking(cl: ClassLoader) {
        val netModule = XposedHelpers.findClassIfExists("com.facebook.react.modules.network.NetworkingModule", cl)
        if (netModule != null) {
            ClassRegistry.add(netModule.name)
            hookNetworkingClass(netModule)
        }
    }

    fun hookNetworkingClass(netModule: Class<*>) {
        try {
            XposedBridge.hookAllMethods(netModule, "onDataReceived", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        for (i in param.args.indices) {
                            val arg = param.args[i]
                            if (arg is String) {
                                val modified = mutateUserJsonString(arg)
                                if (modified != arg) {
                                    param.args[i] = modified
                                    log("NetworkingModule.onDataReceived() -> marked user as gold")
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }
            })
            log("Hooked NetworkingModule.onDataReceived")
        } catch (t: Throwable) {
            log("Error hooking NetworkingModule: $t")
        }
    }
}
