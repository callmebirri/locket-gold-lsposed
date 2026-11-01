package com.locket.gold

import android.util.Log
import de.robv.android.xposed.XposedBridge

internal fun log(msg: String) {
    Log.i(TAG, msg)
    XposedBridge.log("[$TAG] $msg")
}
