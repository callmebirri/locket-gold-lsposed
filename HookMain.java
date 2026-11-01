package com.locket.gold;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class HookMain implements IXposedHookLoadPackage {

    private static final String TAG = "LocketGold";
    private static final String TARGET_PACKAGE = "com.locket.Locket";
    private static final String BUNDLE_ASSET = "index.android.bundle";
    private static final String BUNDLE_CACHE_PREFIX = "locket_gold_bundle_";
    private static final String CACHE_VERSION = "v5";
    private static final String STOCK_BUNDLE_ASSET_URL = "assets://index.android.bundle";

    private static final String GOLD_OVERRIDE_KEY = "android_gold_subscription_override";

    private static final int HERMES_VERSION_SUPPORTED = 96;
    private static final String FN_BADGE = "doesUserHaveGoldBadge";
    private static final String FN_PAYMENTS = "PaymentsProvider";
    private static final String STR_ENTITLEMENT = "subscription_entitlement";

    private static final String[][] FINGERPRINTS = {
            {"1.234.x",
                    "9D8B|0B0A0A0B0D0A|92060A08", "780D790A780D",
                    "6C010173|008B9A0F0001005C00", "78005C00"},
            {"1.236.0",
                    "0B9C81|0B0A0A0B0D0A|92060A08", "780D790A780D",
                    "2E010002|6C00010F|0000015C00", "78005C00"},
            {"1.238.x",
                    "0B28DD|0B0A0A0B0D0A|92060A08", "780D790A780D",
                    "2E010002|6C00010F|0000015C00", "78005C00"},
    };

    private static final String VIDEO_CONFIG_JSON = "{\n" +
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
            "}";

    private static final String FEATURE_GATES_JSON = "{\n" +
            "  \"video\": {\"enabled\": true, \"gold_only\": false},\n" +
            "  \"longer_video\": {\"enabled\": true, \"gold_only\": false},\n" +
            "  \"video_recording\": {\"enabled\": true, \"gold_only\": false},\n" +
            "  \"unlimited_friends\": {\"enabled\": true, \"gold_only\": false},\n" +
            "  \"remove_ads\": {\"enabled\": true, \"gold_only\": false},\n" +
            "  \"camera_theme\": {\"enabled\": true, \"gold_only\": false},\n" +
            "  \"app_icon\": {\"enabled\": true, \"gold_only\": false}\n" +
            "}";

    private static final Set<String> hookedClasses = new HashSet<>();
    private static volatile String patchedBundlePath = null;
    private static volatile boolean jsBundleFileHooked = false;

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log("[" + TAG + "] " + msg);
    }

    private static byte[] bytes(String hex) {
        String clean = hex.replaceAll("\\s+", "");
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[][] context(String spec) {
        String[] groups = spec.split("\\|");
        byte[][] parts = new byte[groups.length][];
        for (int i = 0; i < groups.length; i++) {
            parts[i] = bytes(groups[i]);
        }
        return parts;
    }

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;

        log("Loaded in " + lpparam.packageName + " (process " + lpparam.processName + ")");
        initHooks(lpparam.classLoader);

        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Application app = (Application) param.thisObject;
                        if (app != null) {
                            hookGetJSBundleFileOnSuperclasses(app.getClass().getName(), app.getClass());
                        }
                    } catch (Throwable t) {
                        log("Error in Application.onCreate hook: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            log("Error hooking Application.onCreate: " + t);
        }
    }

    public static synchronized void initHooks(ClassLoader cl) {
        if (cl == null) return;
        hookBundleLoading(cl);
        hookSourceCodeModule(cl);
        hookFirebaseConfig(cl);
        hookMMKV(cl);
        hookNetworking(cl);
        hookFirestore(cl);
        hookRevenueCatPassive(cl);
        hookDynamicClassLoading(cl);
    }

    private static void hookBundleLoading(ClassLoader cl) {
        Class<?> companion = XposedHelpers.findClassIfExists("com.facebook.react.bridge.JSBundleLoader$Companion", cl);
        if (companion != null) {
            try {
                XposedBridge.hookAllMethods(companion, "createAssetLoader", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Context context = (Context) param.args[0];
                            String path = preparePatchedBundle(context);
                            if (path != null) {
                                Object fileLoader = XposedHelpers.callMethod(param.thisObject, "createFileLoader", path);
                                param.setResult(fileLoader);
                                log("JSBundleLoader$Companion.createAssetLoader -> createFileLoader(" + path + ")");
                            }
                        } catch (Throwable t) {
                            log("Error in Companion.createAssetLoader hook: " + t);
                        }
                    }
                });
                log("Hooked JSBundleLoader$Companion.createAssetLoader");
            } catch (Throwable t) {
                log("Error hooking JSBundleLoader$Companion: " + t);
            }
        }

        Class<?> loaderClass = XposedHelpers.findClassIfExists("com.facebook.react.bridge.JSBundleLoader", cl);
        if (loaderClass != null) {
            try {
                XposedBridge.hookAllMethods(loaderClass, "createAssetLoader", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Context context = (Context) param.args[0];
                            String path = preparePatchedBundle(context);
                            if (path != null) {
                                Object fileLoader = XposedHelpers.callStaticMethod(loaderClass, "createFileLoader",
                                        path, param.args[1], param.args[2]);
                                param.setResult(fileLoader);
                                log("JSBundleLoader.createAssetLoader -> createFileLoader(" + path + ")");
                            }
                        } catch (Throwable t) {
                            log("Error in createAssetLoader hook: " + t);
                        }
                    }
                });
                log("Hooked JSBundleLoader.createAssetLoader");
            } catch (Throwable t) {
                log("Error hooking JSBundleLoader: " + t);
            }
        }

        Class<?> knownHost = XposedHelpers.findClassIfExists("com.locket.Locket.MainApplication$a", cl);
        if (knownHost == null) {
            knownHost = XposedHelpers.findClassIfExists("com.facebook.react.ReactNativeHost", cl);
        }
        if (knownHost != null) {
            hookGetJSBundleFileOnSuperclasses(knownHost.getName(), knownHost);
        } else {
            log("Known ReactNativeHost classes not found; Application.onCreate walk will retry");
        }
    }

    private static synchronized void hookGetJSBundleFileOnSuperclasses(String startName, Class<?> start) {
        if (jsBundleFileHooked || start == null) return;

        Class<?> c = start;
        while (c != null) {
            Method target = null;
            for (Method m : c.getDeclaredMethods()) {
                if ("getJSBundleFile".equals(m.getName())) {
                    target = m;
                    break;
                }
            }
            if (target != null) {
                final Class<?> declaring = c;
                try {
                    XposedBridge.hookMethod(target, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object app = XposedHelpers.callMethod(param.thisObject, "getApplication");
                                if (app instanceof Context) {
                                    String path = preparePatchedBundle((Context) app);
                                    if (path != null) {
                                        param.setResult(path);
                                        log(declaring.getName() + ".getJSBundleFile() -> " + path);
                                    }
                                }
                            } catch (Throwable t) {
                                log("Error in getJSBundleFile hook: " + t);
                            }
                        }
                    });
                    jsBundleFileHooked = true;
                    log("Hooked " + c.getName() + ".getJSBundleFile() (host chain of " + startName + ")");
                } catch (Throwable t) {
                    log("Error hooking " + c.getName() + ".getJSBundleFile: " + t);
                }
                return;
            }
            c = c.getSuperclass();
        }
    }

    private static boolean structuralPatch(byte[] data) {
        try {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int version = buf.getInt(8);
            if (version != HERMES_VERSION_SUPPORTED) {
                log("Structural patch: Hermes version " + version + " not supported");
                return false;
            }
            int functionCount = buf.getInt(40);
            int stringKindCount = buf.getInt(44);
            int identifierCount = buf.getInt(48);
            int stringCount = buf.getInt(52);
            int overflowCount = buf.getInt(56);
            int stringStorageSize = buf.getInt(60);

            int p = align(data.length, 32, 111);
            int headersStart = p;
            p = align(data.length, 4, p + functionCount * 16);
            p = align(data.length, 4, p + stringKindCount * 4);
            p = align(data.length, 4, p + identifierCount * 4);
            int smallStringTablePos = p;
            p = align(data.length, 4, p + stringCount * 4);
            int overflowTablePos = p;
            p = align(data.length, 4, p + overflowCount * 8);
            int storagePos = p;
            if (p + stringStorageSize > data.length) {
                log("Structural patch: string storage out of bounds");
                return false;
            }

            byte[] entWant = STR_ENTITLEMENT.getBytes("UTF-8");
            int entId = -1;
            for (int i = 0; i < stringCount; i++) {
                long entry = buf.getInt(smallStringTablePos + i * 4) & 0xFFFFFFFFL;
                int isUtf16 = (int) (entry & 1);
                if (isUtf16 != 0) continue;
                int off = (int) ((entry >> 1) & 0x7FFFFF);
                int len = (int) ((entry >>> 24) & 0xFF);
                int abs;
                if (len == 0xFF) {
                    long ov = buf.getInt(overflowTablePos + off * 8) & 0xFFFFFFFFL;
                    len = buf.getInt(overflowTablePos + off * 8 + 4);
                    abs = storagePos + (int) ov;
                } else {
                    abs = storagePos + off;
                }
                if (len == entWant.length && regionEqualsAscii(data, abs, entWant)) {
                    entId = i;
                    break;
                }
            }
            if (entId < 0) {
                log("Structural patch: 'subscription_entitlement' string not found");
                return false;
            }

            byte[] badgeWant = FN_BADGE.getBytes("UTF-8");
            byte[] paymentsWant = FN_PAYMENTS.getBytes("UTF-8");
            int badgeOffset = -1, badgeSize = 0, paymentsOffset = -1, paymentsSize = 0;
            for (int i = 0; i < functionCount; i++) {
                int base = headersStart + i * 16;
                int w1 = buf.getInt(base + 4);
                boolean overflowed = ((data[base + 15] >> 4) & 1) != 0;
                if (overflowed) continue;
                int nameId = (w1 >>> 15) & 0x1FFFF;
                if (nameId >= stringCount) continue;

                long entry = buf.getInt(smallStringTablePos + nameId * 4) & 0xFFFFFFFFL;
                int isUtf16 = (int) (entry & 1);
                if (isUtf16 != 0) continue;
                int off = (int) ((entry >> 1) & 0x7FFFFF);
                int len = (int) ((entry >>> 24) & 0xFF);
                int abs;
                if (len == 0xFF) {
                    long ov = buf.getInt(overflowTablePos + off * 8) & 0xFFFFFFFFL;
                    len = buf.getInt(overflowTablePos + off * 8 + 4);
                    abs = storagePos + (int) ov;
                } else {
                    abs = storagePos + off;
                }

                if (badgeOffset < 0 && len == badgeWant.length && regionEqualsAscii(data, abs, badgeWant)) {
                    badgeOffset = buf.getInt(base) & 0x1FFFFFF;
                    badgeSize = w1 & 0x7FFF;
                } else if (paymentsOffset < 0 && len == paymentsWant.length && regionEqualsAscii(data, abs, paymentsWant)) {
                    paymentsOffset = buf.getInt(base) & 0x1FFFFFF;
                    paymentsSize = w1 & 0x7FFF;
                }
                if (badgeOffset >= 0 && paymentsOffset >= 0) break;
            }
            if (badgeOffset < 0 || paymentsOffset < 0) {
                log("Structural patch: target functions not found (badge=" + (badgeOffset >= 0)
                        + " payments=" + (paymentsOffset >= 0) + ")");
                return false;
            }

            if (badgeSize < 4 || (data[badgeOffset + badgeSize - 2] & 0xFF) != 0x5C) {
                log("Structural patch: badge function shape unexpected");
                return false;
            }
            int retReg = data[badgeOffset + badgeSize - 1] & 0xFF;
            data[badgeOffset] = 0x78;
            data[badgeOffset + 1] = (byte) retReg;
            data[badgeOffset + 2] = 0x5C;
            data[badgeOffset + 3] = (byte) retReg;
            log("Structural patch doesUserHaveGoldBadge: applied at file offset 0x"
                    + Integer.toHexString(badgeOffset));

            byte[] idLe = {(byte) (entId & 0xFF), (byte) ((entId >> 8) & 0xFF)};
            byte[] body = java.util.Arrays.copyOfRange(data, paymentsOffset, paymentsOffset + paymentsSize);
            for (int i = 0; i <= body.length - 12; i++) {
                if (body[i + 4] == idLe[0] && body[i + 5] == idLe[1]
                        && body[i + 6] == 0x0B && body[i + 7] == body[i + 8]
                        && body[i + 9] == 0x0B && body[i + 11] == body[i + 7]) {
                    int d1 = body[i + 7] & 0xFF;
                    int d2 = body[i + 10] & 0xFF;
                    int at = paymentsOffset + i + 6;
                    data[at] = 0x78;
                    data[at + 1] = (byte) d2;
                    data[at + 2] = 0x79;
                    data[at + 3] = (byte) d1;
                    data[at + 4] = 0x78;
                    data[at + 5] = (byte) d2;
                    log("Structural patch hasGoldSubscription: applied at file offset 0x"
                            + Integer.toHexString(at));
                    return true;
                }
            }
            log("Structural patch: Not/Not sequence not found in PaymentsProvider");
            return false;
        } catch (Throwable t) {
            log("Structural patch error: " + t);
            return false;
        }
    }

    private static int align(int total, int alignment, int position) {
        int aligned = (position + alignment - 1) & ~(alignment - 1);
        if (aligned > total) return Integer.MAX_VALUE - 1;
        return aligned;
    }

    private static boolean regionEqualsAscii(byte[] data, int pos, byte[] want) {
        if (pos < 0 || pos + want.length > data.length) return false;
        for (int i = 0; i < want.length; i++) {
            if (data[pos + i] != want[i]) return false;
        }
        return true;
    }

    private static synchronized String preparePatchedBundle(Context context) {
        if (patchedBundlePath != null && new File(patchedBundlePath).exists()) {
            return patchedBundlePath;
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream is = context.getAssets().open(BUNDLE_ASSET);
            byte[] temp = new byte[8192];
            int read;
            while ((read = is.read(temp)) != -1) {
                bos.write(temp, 0, read);
            }
            is.close();
            byte[] buffer = bos.toByteArray();

            MessageDigest md5 = MessageDigest.getInstance("MD5");
            String md5Hex = toHex(md5.digest(buffer));

            File outFile = new File(context.getFilesDir(), BUNDLE_CACHE_PREFIX + CACHE_VERSION + "_" + md5Hex + ".bin");
            if (outFile.exists() && outFile.length() == buffer.length) {
                patchedBundlePath = outFile.getAbsolutePath();
                log("Reusing cached patched bundle " + outFile.getName());
                cleanupOldBundles(context, outFile.getName());
                return patchedBundlePath;
            }

            String appliedFingerprint = null;
            if (structuralPatch(buffer)) {
                appliedFingerprint = "structural";
            } else {
                for (String[] fp : FINGERPRINTS) {
                    byte[][] ctx1 = context(fp[1]);
                    byte[] new1 = bytes(fp[2]);
                    byte[][] ctx2 = context(fp[3]);
                    byte[] new2 = bytes(fp[4]);

                    if (!contextMatchesOnce(buffer, ctx1, "hasGoldSubscription/" + fp[0])) continue;
                    if (!contextMatchesOnce(buffer, ctx2, "goldBadge/" + fp[0])) continue;

                    boolean p1 = patchUnique(buffer, ctx1, new1, "hasGoldSubscription");
                    boolean p2 = patchUnique(buffer, ctx2, new2, "doesUserHaveGoldBadge");
                    if (p1 && p2) {
                        appliedFingerprint = fp[0];
                        break;
                    }
                    log("Fingerprint " + fp[0] + " matched dry-run but failed to apply — trying next");
                }
            }

            if (appliedFingerprint == null) {
                log("No bundle fingerprint matched this Locket build (md5 " + md5Hex
                        + ") — keeping ORIGINAL bundle; runtime hooks will try to unlock. "
                        + "Please report the md5 to add a fingerprint.");
                return null;
            }

            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(buffer, 0, buffer.length - 20);
            byte[] digest = sha1.digest();
            System.arraycopy(digest, 0, buffer, buffer.length - 20, 20);

            FileOutputStream fos = new FileOutputStream(outFile);
            fos.write(buffer);
            fos.close();

            patchedBundlePath = outFile.getAbsolutePath();
            log("Generated patched Hermes bundle " + outFile.getName()
                    + " (method " + appliedFingerprint + ", size " + buffer.length + ")");
            cleanupOldBundles(context, outFile.getName());
            return patchedBundlePath;
        } catch (Throwable t) {
            log("Error preparing patched bundle: " + t);
            return null;
        }
    }

    private static boolean contextMatchesOnce(byte[] src, byte[][] context, String name) {
        byte[] full = concat(context, 0, context.length);
        int first = indexOf(src, full, 0);
        if (first < 0) {
            log("Fingerprint check " + name + ": context NOT found");
            return false;
        }
        if (indexOf(src, full, first + 1) >= 0) {
            log("Fingerprint check " + name + ": context occurs MULTIPLE times");
            return false;
        }
        return true;
    }

    private static boolean patchUnique(byte[] src, byte[][] context, byte[] replacement, String name) {
        byte[] prefix = concat(context, 0, context.length - 2);
        byte[] full = concat(context, 0, context.length);
        int innerLen = context[context.length - 2].length;
        if (innerLen != replacement.length) {
            log("Patch " + name + ": replacement size mismatch (" + replacement.length
                    + " vs " + innerLen + ")");
            return false;
        }

        int first = indexOf(src, full, 0);
        if (first < 0) {
            log("Patch " + name + ": context NOT found in bundle");
            return false;
        }

        int innerStart = first + prefix.length;
        System.arraycopy(replacement, 0, src, innerStart, replacement.length);
        log("Patch " + name + ": applied at file offset 0x" + Integer.toHexString(innerStart)
                + " (inner " + replacement.length + " bytes)");
        return true;
    }

    private static byte[] concat(byte[][] parts, int from, int to) {
        int len = 0;
        for (int i = from; i < to; i++) len += parts[i].length;
        byte[] out = new byte[len];
        int pos = 0;
        for (int i = from; i < to; i++) {
            System.arraycopy(parts[i], 0, out, pos, parts[i].length);
            pos += parts[i].length;
        }
        return out;
    }

    private static int indexOf(byte[] src, byte[] pat, int from) {
        if (pat.length == 0) return from;
        outer:
        for (int i = Math.max(0, from); i <= src.length - pat.length; i++) {
            for (int j = 0; j < pat.length; j++) {
                if (src[i + j] != pat[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static void cleanupOldBundles(Context context, String keepName) {
        File dir = context.getFilesDir();
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().startsWith(BUNDLE_CACHE_PREFIX) && !f.getName().equals(keepName)) {
                f.delete();
            }
        }
    }

    private static void hookSourceCodeModule(ClassLoader cl) {
        Class<?> cls = XposedHelpers.findClassIfExists("com.facebook.react.modules.debug.SourceCodeModule", cl);
        if (cls != null) {
            hookedClasses.add(cls.getName());
            hookSourceCodeClass(cls);
        }
    }

    private static void hookSourceCodeClass(Class<?> cls) {
        try {
            XC_MethodHook constantsHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object result = param.getResult();
                        if (!(result instanceof Map)) return;

                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = new HashMap<>((Map<String, Object>) result);
                        Object scriptUrl = map.get("scriptURL");
                        if (!(scriptUrl instanceof String)) return;

                        String url = (String) scriptUrl;
                        if (url.contains(BUNDLE_CACHE_PREFIX)) {
                            map.put("scriptURL", STOCK_BUNDLE_ASSET_URL);
                            param.setResult(map);
                            log("SourceCode.scriptURL " + url + " -> " + STOCK_BUNDLE_ASSET_URL);
                        }
                    } catch (Throwable t) {
                        log("Error in SourceCodeModule constants hook: " + t);
                    }
                }
            };
            XposedBridge.hookAllMethods(cls, "getTypedExportedConstants", constantsHook);
            XposedBridge.hookAllMethods(cls, "getConstants", constantsHook);
            log("Hooked SourceCodeModule constants (" + cls.getName() + ")");
        } catch (Throwable t) {
            log("Error hooking SourceCodeModule: " + t);
        }
    }

    private static void hookDynamicClassLoading(final ClassLoader cl) {
        XC_MethodHook loaderHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                String className = (String) param.args[0];
                Class<?> resultClass = (Class<?>) param.getResult();
                if (resultClass == null || className == null) return;
                if (!(className.startsWith("com.revenuecat.purchases")
                        || className.startsWith("io.invertase.firebase")
                        || className.startsWith("com.google.firebase")
                        || className.startsWith("com.facebook.react")
                        || className.startsWith("com.locket.Locket")
                        || className.startsWith("com.tencent.mmkv"))) return;
                if (resultClass.getClassLoader() == null) return;
                hookSingleClass(className, resultClass);
            }
        };
        try {
            XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass",
                    String.class, boolean.class, loaderHook);
            Class<?> baseDex = Class.forName("dalvik.system.BaseDexClassLoader");
            XposedBridge.hookAllMethods(baseDex, "loadClass", loaderHook);
            log("Registered dynamic loadClass hooker");
        } catch (Throwable t) {
            log("Error hooking loadClass: " + t);
        }
    }

    private static synchronized void hookSingleClass(String className, Class<?> clazz) {
        if (hookedClasses.contains(className)) return;

        try {
            if (className.startsWith("io.invertase.firebase.config")
                    && className.endsWith("ReactNativeFirebaseConfigModule")) {
                hookedClasses.add(className);
                hookFirebaseConfigClass(clazz);
                hookFirebaseConfigResult(clazz);
            } else if ("com.google.firebase.remoteconfig.FirebaseRemoteConfig".equals(className)) {
                hookedClasses.add(className);
                hookFirebaseRemoteConfigClass(clazz);
            } else if ("com.tencent.mmkv.MMKV".equals(className)) {
                hookedClasses.add(className);
                hookMMKVClass(clazz);
            } else if ("com.facebook.react.modules.debug.SourceCodeModule".equals(className)) {
                hookedClasses.add(className);
                hookSourceCodeClass(clazz);
            } else if ("com.facebook.react.modules.network.NetworkingModule".equals(className)) {
                hookedClasses.add(className);
                hookNetworkingClass(clazz);
            } else if (className.startsWith("com.google.firebase.firestore")) {
                hookedClasses.add(className);
                hookFirestoreGetData(clazz);
            } else if ("com.revenuecat.purchases.EntitlementInfo".equals(className)) {
                hookedClasses.add(className);
                hookEntitlementInfoPassive(clazz);
            }
        } catch (Throwable t) {
            log("Error hooking single class " + className + ": " + t);
        }
    }

    private static boolean mutateJsonObject(JSONObject obj) {
        if (obj == null) return false;
        boolean modified = false;
        try {
            boolean isUser = obj.has("username") || obj.has("first_name") || obj.has("phone_number")
                    || obj.has("user_uid") || obj.has("subscription_entitlement") || obj.has("badge");
            if (isUser) {
                obj.put("badge", "locket_gold");
                obj.put("subscription_entitlement", "locket_gold");
                obj.put("subscription_store", "play_store");
                obj.put("is_gold", true);
                modified = true;
            }

            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object val = obj.opt(key);
                if (val instanceof JSONObject) {
                    if (mutateJsonObject((JSONObject) val)) modified = true;
                } else if (val instanceof JSONArray) {
                    JSONArray arr = (JSONArray) val;
                    for (int i = 0; i < arr.length(); i++) {
                        Object item = arr.opt(i);
                        if (item instanceof JSONObject && mutateJsonObject((JSONObject) item)) {
                            modified = true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return modified;
    }

    private static String mutateUserJsonString(String original) {
        if (original == null || original.isEmpty()) return original;
        try {
            String trimmed = original.trim();
            if (trimmed.startsWith("{")) {
                JSONObject obj = new JSONObject(trimmed);
                if (mutateJsonObject(obj)) return obj.toString();
            } else if (trimmed.startsWith("[")) {
                JSONArray arr = new JSONArray(trimmed);
                boolean changed = false;
                for (int i = 0; i < arr.length(); i++) {
                    Object item = arr.opt(i);
                    if (item instanceof JSONObject && mutateJsonObject((JSONObject) item)) {
                        changed = true;
                    }
                }
                if (changed) return arr.toString();
            }
        } catch (Throwable ignored) {}
        return original;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutateUserMap(Map<String, Object> original) {
        Map<String, Object> map = new HashMap<>(original);
        try {
            boolean isUser = map.containsKey("username") || map.containsKey("first_name")
                    || map.containsKey("phone_number") || map.containsKey("user_uid")
                    || map.containsKey("subscription_entitlement") || map.containsKey("badge");
            if (isUser) {
                map.put("badge", "locket_gold");
                map.put("subscription_entitlement", "locket_gold");
                map.put("subscription_store", "play_store");
                map.put("is_gold", true);
            }
        } catch (Throwable ignored) {}
        return map;
    }

    private static void hookFirebaseConfig(ClassLoader cl) {
        Class<?> cls = XposedHelpers.findClassIfExists("io.invertase.firebase.config.ReactNativeFirebaseConfigModule", cl);
        if (cls != null) {
            hookedClasses.add(cls.getName());
            hookFirebaseConfigClass(cls);
            hookFirebaseConfigResult(cls);
        }
        Class<?> remoteConfigCls = XposedHelpers.findClassIfExists("com.google.firebase.remoteconfig.FirebaseRemoteConfig", cl);
        if (remoteConfigCls != null) {
            hookedClasses.add(remoteConfigCls.getName());
            hookFirebaseRemoteConfigClass(remoteConfigCls);
        }
    }

    private static void hookFirebaseConfigResult(Class<?> cls) {
        try {
            XposedBridge.hookAllMethods(cls, "resultWithConstants", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object result = param.getResult();
                        if (result == null) return;

                        Object copyObj = XposedHelpers.callMethod(result, "toHashMap");
                        if (!(copyObj instanceof Map)) return;

                        @SuppressWarnings("unchecked")
                        Map<String, Object> root = (Map<String, Object>) copyObj;
                        injectGoldConfigValues(root);

                        Object constantsObj = root.get("constants");
                        if (constantsObj instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> constants = (Map<String, Object>) constantsObj;
                            Object valuesObj = constants.get("values");
                            if (valuesObj instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> values = (Map<String, Object>) valuesObj;
                                injectGoldConfigValues(values);
                            } else {
                                Map<String, Object> values = new HashMap<>();
                                injectGoldConfigValues(values);
                                constants.put("values", values);
                            }
                        }

                        Class<?> arguments = cls.getClassLoader().loadClass("com.facebook.react.bridge.Arguments");
                        Object newMap = XposedHelpers.callStaticMethod(arguments, "makeNativeMap", root);
                        param.setResult(newMap);
                        log("Injected gold flags into config constants (resultWithConstants)");
                    } catch (Throwable t) {
                        log("resultWithConstants hook error: " + t);
                    }
                }
            });
            log("Hooked ReactNativeFirebaseConfigModule.resultWithConstants");
        } catch (Throwable t) {
            log("Error hooking resultWithConstants: " + t);
        }
    }

    private static void injectGoldConfigValues(Map<String, Object> map) {
        map.put(GOLD_OVERRIDE_KEY, configValue("true"));
    }

    private static Map<String, Object> configValue(String value) {
        Map<String, Object> val = new HashMap<>();
        val.put("value", value);
        val.put("source", "remote");
        return val;
    }

    private static void hookFirebaseRemoteConfigClass(Class<?> cls) {
        try {
            XposedHelpers.findAndHookMethod(cls, "getBoolean", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String key = (String) param.args[0];
                    if (GOLD_OVERRIDE_KEY.equals(key) || "android_subscribe_gold_button_enabled".equals(key)) {
                        param.setResult(true);
                        log("FirebaseRemoteConfig.getBoolean(" + key + ") -> true");
                    }
                }
            });

            XposedHelpers.findAndHookMethod(cls, "getString", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String key = (String) param.args[0];
                    if (GOLD_OVERRIDE_KEY.equals(key)) {
                        param.setResult("true");
                        log("FirebaseRemoteConfig.getString(" + key + ") -> 'true'");
                    } else if ("android_camera_video_recording".equals(key)) {
                        param.setResult(VIDEO_CONFIG_JSON);
                        log("FirebaseRemoteConfig.getString(android_camera_video_recording) -> injected gold video config");
                    }
                }
            });
            log("Hooked FirebaseRemoteConfig native getBoolean/getString");
        } catch (Throwable t) {
            log("Error hooking FirebaseRemoteConfig: " + t);
        }
    }

    private static void hookFirebaseConfigClass(Class<?> cls) {
        try {
            XposedBridge.hookAllMethods(cls, "getConstants", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object res = param.getResult();
                        if (!(res instanceof Map)) return;

                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = new HashMap<>((Map<String, Object>) res);
                        putRemoteConfigValue(map, GOLD_OVERRIDE_KEY, "true");

                        Object values = map.get("values");
                        if (values instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> valuesMap = new HashMap<>((Map<String, Object>) values);
                            putRemoteConfigValue(valuesMap, GOLD_OVERRIDE_KEY, "true");
                            map.put("values", valuesMap);
                        }

                        param.setResult(map);
                        log("Injected gold/video flags into ReactNativeFirebaseConfigModule.getConstants");
                    } catch (Throwable t) {
                        log("Error in FirebaseConfigModule hook: " + t);
                    }
                }
            });
            log("Hooked ReactNativeFirebaseConfigModule.getConstants");
        } catch (Throwable t) {
            log("Error hooking ReactNativeFirebaseConfigModule: " + t);
        }
    }

    private static void putRemoteConfigValue(Map<String, Object> map, String key, String value) {
        Map<String, Object> valObj = new HashMap<>();
        valObj.put("value", value);
        valObj.put("source", "remote");
        map.put(key, valObj);
    }

    private static void hookFirestore(ClassLoader cl) {
        String[] docClasses = {
                "com.google.firebase.firestore.DocumentSnapshot",
                "com.google.firebase.firestore.d",
                "com.google.firebase.firestore.d$a"
        };
        for (String className : docClasses) {
            Class<?> cls = XposedHelpers.findClassIfExists(className, cl);
            if (cls != null) {
                hookedClasses.add(className);
                hookFirestoreGetData(cls);
            }
        }
    }

    private static void hookFirestoreGetData(Class<?> cls) {
        boolean hasGetData = false;
        for (Method m : cls.getDeclaredMethods()) {
            if ("getData".equals(m.getName())) {
                hasGetData = true;
                break;
            }
        }
        if (!hasGetData) return;

        try {
            XposedBridge.hookAllMethods(cls, "getData", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object result = param.getResult();
                        if (!(result instanceof Map)) return;

                        @SuppressWarnings("unchecked")
                        Map<String, Object> mutated = mutateUserMap((Map<String, Object>) result);
                        if (mutated != result) {
                            param.setResult(mutated);
                            log("Firestore.getData() -> marked user as gold");
                        }
                    } catch (Throwable t) {
                        log("Error in Firestore getData hook: " + t);
                    }
                }
            });
            log("Hooked " + cls.getName() + ".getData()");
        } catch (Throwable t) {
            log("Error hooking " + cls.getName() + ": " + t);
        }
    }

    private static void hookRevenueCatPassive(ClassLoader cl) {
        Class<?> entClass = XposedHelpers.findClassIfExists("com.revenuecat.purchases.EntitlementInfo", cl);
        if (entClass != null) {
            hookedClasses.add(entClass.getName());
            hookEntitlementInfoPassive(entClass);
        }
    }

    private static void hookEntitlementInfoPassive(Class<?> entClass) {
        try {
            XposedHelpers.findAndHookMethod(entClass, "isActive", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(true);
                }
            });
            XposedHelpers.findAndHookMethod(entClass, "getWillRenew", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(true);
                }
            });
            log("Hooked RevenueCat EntitlementInfo passive getters");
        } catch (Throwable t) {
            log("RevenueCat EntitlementInfo hook skipped: " + t.getMessage());
        }
    }

    private static void hookMMKV(ClassLoader cl) {
        Class<?> mmkvClass = XposedHelpers.findClassIfExists("com.tencent.mmkv.MMKV", cl);
        if (mmkvClass != null) {
            hookedClasses.add(mmkvClass.getName());
            hookMMKVClass(mmkvClass);
        }
    }

    private static void hookMMKVClass(Class<?> mmkvClass) {
        try {
            XposedBridge.hookAllMethods(mmkvClass, "decodeString", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object res = param.getResult();
                        if (res instanceof String) {
                            String modified = mutateUserJsonString((String) res);
                            if (!modified.equals(res)) {
                                param.setResult(modified);
                                log("MMKV.decodeString() -> marked cached user as gold");
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });
            log("Hooked MMKV.decodeString()");
        } catch (Throwable t) {
            log("Error hooking MMKV: " + t);
        }
    }

    private static void hookNetworking(ClassLoader cl) {
        Class<?> netModule = XposedHelpers.findClassIfExists("com.facebook.react.modules.network.NetworkingModule", cl);
        if (netModule != null) {
            hookedClasses.add(netModule.getName());
            hookNetworkingClass(netModule);
        }
    }

    private static void hookNetworkingClass(Class<?> netModule) {
        try {
            XposedBridge.hookAllMethods(netModule, "onDataReceived", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        for (int i = 0; i < param.args.length; i++) {
                            if (param.args[i] instanceof String) {
                                String raw = (String) param.args[i];
                                String modified = mutateUserJsonString(raw);
                                if (!modified.equals(raw)) {
                                    param.args[i] = modified;
                                    log("NetworkingModule.onDataReceived() -> marked user as gold");
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });
            log("Hooked NetworkingModule.onDataReceived");
        } catch (Throwable t) {
            log("Error hooking NetworkingModule: " + t);
        }
    }
}
