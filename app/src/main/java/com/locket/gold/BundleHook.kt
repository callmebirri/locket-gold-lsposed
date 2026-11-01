package com.locket.gold

import android.app.Application
import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

internal object BundleHook {
    @Volatile
    private var jsBundleFileHooked = false

    fun hookBundleLoading(cl: ClassLoader) {
        val companion = XposedHelpers.findClassIfExists("com.facebook.react.bridge.JSBundleLoader\$Companion", cl)
        if (companion != null) {
            try {
                XposedBridge.hookAllMethods(companion, "createAssetLoader", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val context = param.args[0] as Context
                            val path = BundlePatcher.preparePatchedBundle(context)
                            if (path != null) {
                                val fileLoader = XposedHelpers.callMethod(param.thisObject, "createFileLoader", path)
                                param.result = fileLoader
                                log("JSBundleLoader\$Companion.createAssetLoader -> createFileLoader($path)")
                            }
                        } catch (t: Throwable) {
                            log("Error in Companion.createAssetLoader hook: $t")
                        }
                    }
                })
                log("Hooked JSBundleLoader\$Companion.createAssetLoader")
            } catch (t: Throwable) {
                log("Error hooking JSBundleLoader\$Companion: $t")
            }
        }

        val loaderClass = XposedHelpers.findClassIfExists("com.facebook.react.bridge.JSBundleLoader", cl)
        if (loaderClass != null) {
            try {
                XposedBridge.hookAllMethods(loaderClass, "createAssetLoader", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val context = param.args[0] as Context
                            val path = BundlePatcher.preparePatchedBundle(context)
                            if (path != null) {
                                val fileLoader = XposedHelpers.callStaticMethod(
                                    loaderClass, "createFileLoader",
                                    path, param.args[1], param.args[2]
                                )
                                param.result = fileLoader
                                log("JSBundleLoader.createAssetLoader -> createFileLoader($path)")
                            }
                        } catch (t: Throwable) {
                            log("Error in createAssetLoader hook: $t")
                        }
                    }
                })
                log("Hooked JSBundleLoader.createAssetLoader")
            } catch (t: Throwable) {
                log("Error hooking JSBundleLoader: $t")
            }
        }

        var knownHost = XposedHelpers.findClassIfExists("com.locket.Locket.MainApplication\$a", cl)
        if (knownHost == null) {
            knownHost = XposedHelpers.findClassIfExists("com.facebook.react.ReactNativeHost", cl)
        }
        if (knownHost != null) {
            hookGetJSBundleFileOnSuperclasses(knownHost.name, knownHost)
        } else {
            log("Known ReactNativeHost classes not found; Application.onCreate walk will retry")
        }
    }

    @Synchronized
    fun hookGetJSBundleFileOnSuperclasses(startName: String, start: Class<*>?) {
        if (jsBundleFileHooked || start == null) return

        var c: Class<*>? = start
        while (c != null) {
            var target: Method? = null
            for (m in c.declaredMethods) {
                if ("getJSBundleFile" == m.name) {
                    target = m
                    break
                }
            }
            if (target != null) {
                val declaring = c
                try {
                    XposedBridge.hookMethod(target, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val app = XposedHelpers.callMethod(param.thisObject, "getApplication")
                                if (app is Context) {
                                    val path = BundlePatcher.preparePatchedBundle(app)
                                    if (path != null) {
                                        param.result = path
                                        log(declaring.name + ".getJSBundleFile() -> " + path)
                                    }
                                }
                            } catch (t: Throwable) {
                                log("Error in getJSBundleFile hook: $t")
                            }
                        }
                    })
                    jsBundleFileHooked = true
                    log("Hooked " + c.name + ".getJSBundleFile() (host chain of " + startName + ")")
                } catch (t: Throwable) {
                    log("Error hooking " + c.name + ".getJSBundleFile: " + t)
                }
                return
            }
            c = c.superclass
        }
    }

    fun hookApplicationOnCreate() {
        try {
            XposedHelpers.findAndHookMethod(Application::class.java, "onCreate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val app = param.thisObject as? Application
                        if (app != null) {
                            hookGetJSBundleFileOnSuperclasses(app.javaClass.name, app.javaClass)
                        }
                    } catch (t: Throwable) {
                        log("Error in Application.onCreate hook: $t")
                    }
                }
            })
        } catch (t: Throwable) {
            log("Error hooking Application.onCreate: $t")
        }
    }
}
