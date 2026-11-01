package com.locket.gold

import org.json.JSONArray
import org.json.JSONObject

internal fun mutateJsonObject(obj: JSONObject?): Boolean {
    if (obj == null) return false
    var modified = false
    try {
        val isUser = obj.has("username") || obj.has("first_name") || obj.has("phone_number")
                || obj.has("user_uid") || obj.has("subscription_entitlement") || obj.has("badge")
        if (isUser) {
            obj.put("badge", "locket_gold")
            obj.put("subscription_entitlement", "locket_gold")
            obj.put("subscription_store", "play_store")
            obj.put("is_gold", true)
            modified = true
        }

        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val valObj = obj.opt(key)
            if (valObj is JSONObject) {
                if (mutateJsonObject(valObj)) modified = true
            } else if (valObj is JSONArray) {
                for (i in 0 until valObj.length()) {
                    val item = valObj.opt(i)
                    if (item is JSONObject && mutateJsonObject(item)) {
                        modified = true
                    }
                }
            }
        }
    } catch (_: Throwable) {}
    return modified
}

internal fun mutateUserJsonString(original: String?): String? {
    if (original == null || original.isEmpty()) return original
    try {
        val trimmed = original.trim()
        if (trimmed.startsWith("{")) {
            val obj = JSONObject(trimmed)
            if (mutateJsonObject(obj)) return obj.toString()
        } else if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            var changed = false
            for (i in 0 until arr.length()) {
                val item = arr.opt(i)
                if (item is JSONObject && mutateJsonObject(item)) {
                    changed = true
                }
            }
            if (changed) return arr.toString()
        }
    } catch (_: Throwable) {}
    return original
}

internal fun mutateUserMap(original: Map<String, Any?>): Map<String, Any?> {
    val map = HashMap<String, Any?>(original)
    try {
        val isUser = map.containsKey("username") || map.containsKey("first_name")
                || map.containsKey("phone_number") || map.containsKey("user_uid")
                || map.containsKey("subscription_entitlement") || map.containsKey("badge")
        if (isUser) {
            map["badge"] = "locket_gold"
            map["subscription_entitlement"] = "locket_gold"
            map["subscription_store"] = "play_store"
            map["is_gold"] = true
        }
    } catch (_: Throwable) {}
    return map
}
