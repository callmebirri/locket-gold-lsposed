package com.locket.gold

import android.app.Application
import android.content.Context
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Arrays

class HookMain : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "LocketGold"
        private const val TARGET_PACKAGE = "com.locket.Locket"
        private const val BUNDLE_ASSET = "index.android.bundle"
        private const val BUNDLE_CACHE_PREFIX = "locket_gold_bundle_"
        private const val CACHE_VERSION = "v5"
        private const val STOCK_BUNDLE_ASSET_URL = "assets://index.android.bundle"

        private const val GOLD_OVERRIDE_KEY = "android_gold_subscription_override"

        private const val HERMES_VERSION_SUPPORTED = 96
        private const val FN_BADGE = "doesUserHaveGoldBadge"
        private const val FN_PAYMENTS = "PaymentsProvider"
        private const val STR_ENTITLEMENT = "subscription_entitlement"

        private val FINGERPRINTS = arrayOf(
            arrayOf(
                "1.234.x",
                "9D8B|0B0A0A0B0D0A|92060A08", "780D790A780D",
                "6C010173|008B9A0F0001005C00", "78005C00"
            ),
            arrayOf(
                "1.236.0",
                "0B9C81|0B0A0A0B0D0A|92060A08", "780D790A780D",
                "2E010002|6C00010F|0000015C00", "78005C00"
            ),
            arrayOf(
                "1.238.x",
                "0B28DD|0B0A0A0B0D0A|92060A08", "780D790A780D",
                "2E010002|6C00010F|0000015C00", "78005C00"
            )
        )

        private const val VIDEO_CONFIG_JSON = "{\n" +
                "  \"display_mode\": \"toggle\",\n" +
                "  \"default\": {\n" +
                "    \"maximum_duration\": 2500,\n" +
                "    \"size\": 720,\n" +
                "    \"file_type\": \"mp4\",\n" +
                "    \"video_codec\": \"h265\"\n" +
                "  },\n" +
                "  \"gold\": {\n" +
                "    \"maximum_duration\": 10000,\n" +
                "    \"size\": 720,\n" +
                "    \"file_type\": \"mp4\",\n" +
                "    \"video_codec\": \"h265\"\n" +
                "  }\n" +
                "}"

        private const val FEATURE_GATES_JSON = "{\n" +
                "  \"video\": {\"enabled\": true, \"gold_only\": false},\n" +
                "  \"longer_video\": {\"enabled\": true, \"gold_only\": false},\n" +
                "  \"video_recording\": {\"enabled\": true, \"gold_only\": false},\n" +
                "  \"unlimited_friends\": {\"enabled\": true, \"gold_only\": false},\n" +
                "  \"remove_ads\": {\"enabled\": true, \"gold_only\": false},\n" +
                "  \"camera_theme\": {\"enabled\": true, \"gold_only\": false},\n" +
                "  \"app_icon\": {\"enabled\": true, \"gold_only\": false}\n" +
                "}"

        private val hookedClasses = HashSet<String>()
        @Volatile
        private var patchedBundlePath: String? = null
        @Volatile
        private var jsBundleFileHooked = false

        private fun log(msg: String) {
            Log.i(TAG, msg)
            XposedBridge.log("[$TAG] $msg")
        }

        private fun bytes(hex: String): ByteArray {
            val clean = hex.replace("\\s+".toRegex(), "")
            val out = ByteArray(clean.length / 2)
            for (i in out.indices) {
                out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            return out
        }

        private fun context(spec: String): Array<ByteArray> {
            val groups = spec.split("\\|".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            val parts = arrayOfNulls<ByteArray>(groups.size)
            for (i in groups.indices) {
                parts[i] = bytes(groups[i])
            }
            @Suppress("UNCHECKED_CAST")
            return parts as Array<ByteArray>
        }

        @JvmStatic
        @Synchronized
        fun initHooks(cl: ClassLoader?) {
            if (cl == null) return
            hookBundleLoading(cl)
            hookSourceCodeModule(cl)
            hookFirebaseConfig(cl)
            hookMMKV(cl)
            hookNetworking(cl)
            hookFirestore(cl)
            hookRevenueCatPassive(cl)
            hookDynamicClassLoading()
        }

        private fun hookBundleLoading(cl: ClassLoader) {
            val companion = XposedHelpers.findClassIfExists("com.facebook.react.bridge.JSBundleLoader\$Companion", cl)
            if (companion != null) {
                try {
                    XposedBridge.hookAllMethods(companion, "createAssetLoader", object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val context = param.args[0] as Context
                                val path = preparePatchedBundle(context)
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
                                val path = preparePatchedBundle(context)
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
        private fun hookGetJSBundleFileOnSuperclasses(startName: String, start: Class<*>?) {
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
                                        val path = preparePatchedBundle(app)
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

        private fun structuralPatch(data: ByteArray): Boolean {
            try {
                val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                val version = buf.getInt(8)
                if (version != HERMES_VERSION_SUPPORTED) {
                    log("Structural patch: Hermes version $version not supported")
                    return false
                }
                val functionCount = buf.getInt(40)
                val stringKindCount = buf.getInt(44)
                val identifierCount = buf.getInt(48)
                val stringCount = buf.getInt(52)
                val overflowCount = buf.getInt(56)
                val stringStorageSize = buf.getInt(60)

                var p = align(data.size, 32, 111)
                val headersStart = p
                p = align(data.size, 4, p + functionCount * 16)
                p = align(data.size, 4, p + stringKindCount * 4)
                p = align(data.size, 4, p + identifierCount * 4)
                val smallStringTablePos = p
                p = align(data.size, 4, p + stringCount * 4)
                val overflowTablePos = p
                p = align(data.size, 4, p + overflowCount * 8)
                val storagePos = p
                if (p + stringStorageSize > data.size) {
                    log("Structural patch: string storage out of bounds")
                    return false
                }

                val entWant = STR_ENTITLEMENT.toByteArray(charset("UTF-8"))
                var entId = -1
                for (i in 0 until stringCount) {
                    val entry = buf.getInt(smallStringTablePos + i * 4).toLong() and 0xFFFFFFFFL
                    val isUtf16 = (entry and 1L).toInt()
                    if (isUtf16 != 0) continue
                    val off = ((entry shr 1) and 0x7FFFFFL).toInt()
                    var len = ((entry ushr 24) and 0xFFL).toInt()
                    val abs: Int
                    if (len == 0xFF) {
                        val ov = buf.getInt(overflowTablePos + off * 8).toLong() and 0xFFFFFFFFL
                        len = buf.getInt(overflowTablePos + off * 8 + 4)
                        abs = storagePos + ov.toInt()
                    } else {
                        abs = storagePos + off
                    }
                    if (len == entWant.size && regionEqualsAscii(data, abs, entWant)) {
                        entId = i
                        break
                    }
                }
                if (entId < 0) {
                    log("Structural patch: 'subscription_entitlement' string not found")
                    return false
                }

                val badgeWant = FN_BADGE.toByteArray(charset("UTF-8"))
                val paymentsWant = FN_PAYMENTS.toByteArray(charset("UTF-8"))
                var badgeOffset = -1
                var badgeSize = 0
                var paymentsOffset = -1
                var paymentsSize = 0
                for (i in 0 until functionCount) {
                    val base = headersStart + i * 16
                    val w1 = buf.getInt(base + 4)
                    val overflowed = ((data[base + 15].toInt() shr 4) and 1) != 0
                    if (overflowed) continue
                    val nameId = (w1 ushr 15) and 0x1FFFF
                    if (nameId >= stringCount) continue

                    val entry = buf.getInt(smallStringTablePos + nameId * 4).toLong() and 0xFFFFFFFFL
                    val isUtf16 = (entry and 1L).toInt()
                    if (isUtf16 != 0) continue
                    val off = ((entry shr 1) and 0x7FFFFFL).toInt()
                    var len = ((entry ushr 24) and 0xFFL).toInt()
                    val abs: Int
                    if (len == 0xFF) {
                        val ov = buf.getInt(overflowTablePos + off * 8).toLong() and 0xFFFFFFFFL
                        len = buf.getInt(overflowTablePos + off * 8 + 4)
                        abs = storagePos + ov.toInt()
                    } else {
                        abs = storagePos + off
                    }

                    if (badgeOffset < 0 && len == badgeWant.size && regionEqualsAscii(data, abs, badgeWant)) {
                        badgeOffset = buf.getInt(base) and 0x1FFFFFF
                        badgeSize = w1 and 0x7FFF
                    } else if (paymentsOffset < 0 && len == paymentsWant.size && regionEqualsAscii(data, abs, paymentsWant)) {
                        paymentsOffset = buf.getInt(base) and 0x1FFFFFF
                        paymentsSize = w1 and 0x7FFF
                    }
                    if (badgeOffset >= 0 && paymentsOffset >= 0) break
                }
                if (badgeOffset < 0 || paymentsOffset < 0) {
                    log("Structural patch: target functions not found (badge=" + (badgeOffset >= 0)
                            + " payments=" + (paymentsOffset >= 0) + ")")
                    return false
                }

                if (badgeSize < 4 || (data[badgeOffset + badgeSize - 2].toInt() and 0xFF) != 0x5C) {
                    log("Structural patch: badge function shape unexpected")
                    return false
                }
                val retReg = data[badgeOffset + badgeSize - 1].toInt() and 0xFF
                data[badgeOffset] = 0x78.toByte()
                data[badgeOffset + 1] = retReg.toByte()
                data[badgeOffset + 2] = 0x5C.toByte()
                data[badgeOffset + 3] = retReg.toByte()
                log("Structural patch doesUserHaveGoldBadge: applied at file offset 0x"
                        + Integer.toHexString(badgeOffset))

                val idLe = byteArrayOf((entId and 0xFF).toByte(), ((entId shr 8) and 0xFF).toByte())
                val body = Arrays.copyOfRange(data, paymentsOffset, paymentsOffset + paymentsSize)
                for (i in 0..body.size - 12) {
                    if (body[i + 4] == idLe[0] && body[i + 5] == idLe[1]
                            && body[i + 6] == 0x0B.toByte() && body[i + 7] == body[i + 8]
                            && body[i + 9] == 0x0B.toByte() && body[i + 11] == body[i + 7]) {
                        val d1 = body[i + 7].toInt() and 0xFF
                        val d2 = body[i + 10].toInt() and 0xFF
                        val at = paymentsOffset + i + 6
                        data[at] = 0x78.toByte()
                        data[at + 1] = d2.toByte()
                        data[at + 2] = 0x79.toByte()
                        data[at + 3] = d1.toByte()
                        data[at + 4] = 0x78.toByte()
                        data[at + 5] = d2.toByte()
                        log("Structural patch hasGoldSubscription: applied at file offset 0x"
                                + Integer.toHexString(at))
                        return true
                    }
                }
                log("Structural patch: Not/Not sequence not found in PaymentsProvider")
                return false
            } catch (t: Throwable) {
                log("Structural patch error: $t")
                return false
            }
        }

        private fun align(total: Int, alignment: Int, position: Int): Int {
            val aligned = (position + alignment - 1) and (alignment - 1).inv()
            if (aligned > total) return Int.MAX_VALUE - 1
            return aligned
        }

        private fun regionEqualsAscii(data: ByteArray, pos: Int, want: ByteArray): Boolean {
            if (pos < 0 || pos + want.size > data.size) return false
            for (i in want.indices) {
                if (data[pos + i] != want[i]) return false
            }
            return true
        }

        @Synchronized
        private fun preparePatchedBundle(context: Context): String? {
            val currentPatchedPath = patchedBundlePath
            if (currentPatchedPath != null && File(currentPatchedPath).exists()) {
                return currentPatchedPath
            }
            try {
                val bos = ByteArrayOutputStream()
                val `is` = context.assets.open(BUNDLE_ASSET)
                val temp = ByteArray(8192)
                var read: Int
                while (`is`.read(temp).also { read = it } != -1) {
                    bos.write(temp, 0, read)
                }
                `is`.close()
                val buffer = bos.toByteArray()

                val md5 = MessageDigest.getInstance("MD5")
                val md5Hex = toHex(md5.digest(buffer))

                val outFile = File(context.filesDir, BUNDLE_CACHE_PREFIX + CACHE_VERSION + "_" + md5Hex + ".bin")
                if (outFile.exists() && outFile.length() == buffer.size.toLong()) {
                    patchedBundlePath = outFile.absolutePath
                    log("Reusing cached patched bundle " + outFile.name)
                    cleanupOldBundles(context, outFile.name)
                    return patchedBundlePath
                }

                var appliedFingerprint: String? = null
                if (structuralPatch(buffer)) {
                    appliedFingerprint = "structural"
                } else {
                    for (fp in FINGERPRINTS) {
                        val ctx1 = context(fp[1])
                        val new1 = bytes(fp[2])
                        val ctx2 = context(fp[3])
                        val new2 = bytes(fp[4])

                        if (!contextMatchesOnce(buffer, ctx1, "hasGoldSubscription/" + fp[0])) continue
                        if (!contextMatchesOnce(buffer, ctx2, "goldBadge/" + fp[0])) continue

                        val p1 = patchUnique(buffer, ctx1, new1, "hasGoldSubscription")
                        val p2 = patchUnique(buffer, ctx2, new2, "doesUserHaveGoldBadge")
                        if (p1 && p2) {
                            appliedFingerprint = fp[0]
                            break
                        }
                        log("Fingerprint " + fp[0] + " matched dry-run but failed to apply — trying next")
                    }
                }

                if (appliedFingerprint == null) {
                    log("No bundle fingerprint matched this Locket build (md5 " + md5Hex
                            + ") — keeping ORIGINAL bundle; runtime hooks will try to unlock. "
                            + "Please report the md5 to add a fingerprint.")
                    return null
                }

                val sha1 = MessageDigest.getInstance("SHA-1")
                sha1.update(buffer, 0, buffer.size - 20)
                val digest = sha1.digest()
                System.arraycopy(digest, 0, buffer, buffer.size - 20, 20)

                val fos = FileOutputStream(outFile)
                fos.write(buffer)
                fos.close()

                patchedBundlePath = outFile.absolutePath
                log("Generated patched Hermes bundle " + outFile.name
                        + " (method " + appliedFingerprint + ", size " + buffer.size + ")")
                cleanupOldBundles(context, outFile.name)
                return patchedBundlePath
            } catch (t: Throwable) {
                log("Error preparing patched bundle: $t")
                return null
            }
        }

        private fun contextMatchesOnce(src: ByteArray, context: Array<ByteArray>, name: String): Boolean {
            val full = concat(context, 0, context.size)
            val first = indexOf(src, full, 0)
            if (first < 0) {
                log("Fingerprint check $name: context NOT found")
                return false
            }
            if (indexOf(src, full, first + 1) >= 0) {
                log("Fingerprint check $name: context occurs MULTIPLE times")
                return false
            }
            return true
        }

        private fun patchUnique(src: ByteArray, context: Array<ByteArray>, replacement: ByteArray, name: String): Boolean {
            val prefix = concat(context, 0, context.size - 2)
            val full = concat(context, 0, context.size)
            val innerLen = context[context.size - 2].size
            if (innerLen != replacement.size) {
                log("Patch " + name + ": replacement size mismatch (" + replacement.size
                        + " vs " + innerLen + ")")
                return false
            }

            val first = indexOf(src, full, 0)
            if (first < 0) {
                log("Patch " + name + ": context NOT found in bundle")
                return false
            }

            val innerStart = first + prefix.size
            System.arraycopy(replacement, 0, src, innerStart, replacement.size)
            log("Patch " + name + ": applied at file offset 0x" + Integer.toHexString(innerStart)
                    + " (inner " + replacement.size + " bytes)")
            return true
        }

        private fun concat(parts: Array<ByteArray>, from: Int, to: Int): ByteArray {
            var len = 0
            for (i in from until to) len += parts[i].size
            val out = ByteArray(len)
            var pos = 0
            for (i in from until to) {
                System.arraycopy(parts[i], 0, out, pos, parts[i].size)
                pos += parts[i].size
            }
            return out
        }

        private fun indexOf(src: ByteArray, pat: ByteArray, from: Int): Int {
            if (pat.isEmpty()) return from
            val start = Math.max(0, from)
            val end = src.size - pat.size
            outer@ for (i in start..end) {
                for (j in pat.indices) {
                    if (src[i + j] != pat[j]) continue@outer
                }
                return i
            }
            return -1
        }

        private fun toHex(b: ByteArray): String {
            val sb = StringBuilder(b.size * 2)
            for (x in b) sb.append(String.format("%02x", x))
            return sb.toString()
        }

        private fun cleanupOldBundles(context: Context, keepName: String) {
            val dir = context.filesDir
            val files = dir.listFiles() ?: return
            for (f in files) {
                if (f.name.startsWith(BUNDLE_CACHE_PREFIX) && f.name != keepName) {
                    f.delete()
                }
            }
        }

        private fun hookSourceCodeModule(cl: ClassLoader) {
            val cls = XposedHelpers.findClassIfExists("com.facebook.react.modules.debug.SourceCodeModule", cl)
            if (cls != null) {
                hookedClasses.add(cls.name)
                hookSourceCodeClass(cls)
            }
        }

        private fun hookSourceCodeClass(cls: Class<*>) {
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

        private fun hookDynamicClassLoading() {
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
            if (hookedClasses.contains(className)) return

            try {
                if (className.startsWith("io.invertase.firebase.config")
                        && className.endsWith("ReactNativeFirebaseConfigModule")) {
                    hookedClasses.add(className)
                    hookFirebaseConfigClass(clazz)
                    hookFirebaseConfigResult(clazz)
                } else if ("com.google.firebase.remoteconfig.FirebaseRemoteConfig" == className) {
                    hookedClasses.add(className)
                    hookFirebaseRemoteConfigClass(clazz)
                } else if ("com.tencent.mmkv.MMKV" == className) {
                    hookedClasses.add(className)
                    hookMMKVClass(clazz)
                } else if ("com.facebook.react.modules.debug.SourceCodeModule" == className) {
                    hookedClasses.add(className)
                    hookSourceCodeClass(clazz)
                } else if ("com.facebook.react.modules.network.NetworkingModule" == className) {
                    hookedClasses.add(className)
                    hookNetworkingClass(clazz)
                } else if (className.startsWith("com.google.firebase.firestore")) {
                    hookedClasses.add(className)
                    hookFirestoreGetData(clazz)
                } else if ("com.revenuecat.purchases.EntitlementInfo" == className) {
                    hookedClasses.add(className)
                    hookEntitlementInfoPassive(clazz)
                }
            } catch (t: Throwable) {
                log("Error hooking single class $className: $t")
            }
        }

        private fun mutateJsonObject(obj: JSONObject?): Boolean {
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

        private fun mutateUserJsonString(original: String?): String? {
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

        private fun mutateUserMap(original: Map<String, Any?>): Map<String, Any?> {
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

        private fun hookFirebaseConfig(cl: ClassLoader) {
            val cls = XposedHelpers.findClassIfExists("io.invertase.firebase.config.ReactNativeFirebaseConfigModule", cl)
            if (cls != null) {
                hookedClasses.add(cls.name)
                hookFirebaseConfigClass(cls)
                hookFirebaseConfigResult(cls)
            }
            val remoteConfigCls = XposedHelpers.findClassIfExists("com.google.firebase.remoteconfig.FirebaseRemoteConfig", cl)
            if (remoteConfigCls != null) {
                hookedClasses.add(remoteConfigCls.name)
                hookFirebaseRemoteConfigClass(remoteConfigCls)
            }
        }

        private fun hookFirebaseConfigResult(cls: Class<*>) {
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

        private fun hookFirebaseRemoteConfigClass(cls: Class<*>) {
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

        private fun hookFirebaseConfigClass(cls: Class<*>) {
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

        private fun hookFirestore(cl: ClassLoader) {
            val docClasses = arrayOf(
                "com.google.firebase.firestore.DocumentSnapshot",
                "com.google.firebase.firestore.d",
                "com.google.firebase.firestore.d\$a"
            )
            for (className in docClasses) {
                val cls = XposedHelpers.findClassIfExists(className, cl)
                if (cls != null) {
                    hookedClasses.add(className)
                    hookFirestoreGetData(cls)
                }
            }
        }

        private fun hookFirestoreGetData(cls: Class<*>) {
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

        private fun hookRevenueCatPassive(cl: ClassLoader) {
            val entClass = XposedHelpers.findClassIfExists("com.revenuecat.purchases.EntitlementInfo", cl)
            if (entClass != null) {
                hookedClasses.add(entClass.name)
                hookEntitlementInfoPassive(entClass)
            }
        }

        private fun hookEntitlementInfoPassive(entClass: Class<*>) {
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

        private fun hookMMKV(cl: ClassLoader) {
            val mmkvClass = XposedHelpers.findClassIfExists("com.tencent.mmkv.MMKV", cl)
            if (mmkvClass != null) {
                hookedClasses.add(mmkvClass.name)
                hookMMKVClass(mmkvClass)
            }
        }

        private fun hookMMKVClass(mmkvClass: Class<*>) {
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

        private fun hookNetworking(cl: ClassLoader) {
            val netModule = XposedHelpers.findClassIfExists("com.facebook.react.modules.network.NetworkingModule", cl)
            if (netModule != null) {
                hookedClasses.add(netModule.name)
                hookNetworkingClass(netModule)
            }
        }

        private fun hookNetworkingClass(netModule: Class<*>) {
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

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (TARGET_PACKAGE != lpparam.packageName) return

        log("Loaded in " + lpparam.packageName + " (process " + lpparam.processName + ")")
        initHooks(lpparam.classLoader)

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
