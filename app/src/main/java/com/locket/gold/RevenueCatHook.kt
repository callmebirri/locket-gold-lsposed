package com.locket.gold

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

internal object RevenueCatHook {
    fun hookRevenueCatPassive(cl: ClassLoader) {
        val entClass = XposedHelpers.findClassIfExists("com.revenuecat.purchases.EntitlementInfo", cl)
        if (entClass != null) {
            ClassRegistry.add(entClass.name)
            hookEntitlementInfoPassive(entClass)
        }
    }

    fun hookEntitlementInfoPassive(entClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(entClass, "isActive", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            })
            XposedHelpers.findAndHookMethod(entClass, "getWillRenew", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            })
            log("Hooked RevenueCat EntitlementInfo passive getters")
        } catch (t: Throwable) {
            log("RevenueCat EntitlementInfo hook skipped: " + t.message)
        }
    }
}
