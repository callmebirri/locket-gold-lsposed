package com.locket.gold

internal const val TAG = "LocketGold"
internal const val TARGET_PACKAGE = "com.locket.Locket"
internal const val BUNDLE_ASSET = "index.android.bundle"
internal const val BUNDLE_CACHE_PREFIX = "locket_gold_bundle_"
internal const val CACHE_VERSION = "v5"
internal const val STOCK_BUNDLE_ASSET_URL = "assets://index.android.bundle"

internal const val GOLD_OVERRIDE_KEY = "android_gold_subscription_override"

internal const val HERMES_VERSION_SUPPORTED = 96
internal const val FN_BADGE = "doesUserHaveGoldBadge"
internal const val FN_PAYMENTS = "PaymentsProvider"
internal const val STR_ENTITLEMENT = "subscription_entitlement"

internal val FINGERPRINTS = arrayOf(
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

internal const val VIDEO_CONFIG_JSON = "{\n" +
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

internal const val FEATURE_GATES_JSON = "{\n" +
        "  \"video\": {\"enabled\": true, \"gold_only\": false},\n" +
        "  \"longer_video\": {\"enabled\": true, \"gold_only\": false},\n" +
        "  \"video_recording\": {\"enabled\": true, \"gold_only\": false},\n" +
        "  \"unlimited_friends\": {\"enabled\": true, \"gold_only\": false},\n" +
        "  \"remove_ads\": {\"enabled\": true, \"gold_only\": false},\n" +
        "  \"camera_theme\": {\"enabled\": true, \"gold_only\": false},\n" +
        "  \"app_icon\": {\"enabled\": true, \"gold_only\": false}\n" +
        "}"
