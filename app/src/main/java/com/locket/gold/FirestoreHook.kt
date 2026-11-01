package com.locket.gold

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

internal object FirestoreHook {
    fun hookFirestore(cl: ClassLoader) {
        val docClasses = arrayOf(
            "com.google.firebase.firestore.DocumentSnapshot",
            "com.google.firebase.firestore.d",
            "com.google.firebase.firestore.d\$a"
        )
        for (className in docClasses) {
            val cls = XposedHelpers.findClassIfExists(className, cl)
            if (cls != null) {
                ClassRegistry.add(className)
                hookFirestoreGetData(cls)
            }
        }
    }

    fun hookFirestoreGetData(cls: Class<*>) {
        var hasGetData = false
        for (m in cls.declaredMethods) {
            if ("getData" == m.name) {
                hasGetData = true
                break
            }
        }
        if (!hasGetData) return

        try {
            XposedBridge.hookAllMethods(cls, "getData", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val result = param.result
                        if (result !is Map<*, *>) return

                        @Suppress("UNCHECKED_CAST")
                        val mutated = mutateUserMap(result as Map<String, Any?>)
                        if (mutated !== result) {
                            param.result = mutated
                            log("Firestore.getData() -> marked user as gold")
                        }
                    } catch (t: Throwable) {
                        log("Error in Firestore getData hook: $t")
                    }
                }
            })
            log("Hooked " + cls.name + ".getData()")
        } catch (t: Throwable) {
            log("Error hooking " + cls.name + ": " + t)
        }
    }
}
