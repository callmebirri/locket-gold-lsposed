# Locket Gold (Xposed Module)

An Xposed / LSPosed module targeting the Locket application (`com.locket.Locket`), designed for client-side runtime analysis and instrumentation.

## Technical Architecture

The module operates across three distinct execution layers within the target process:

- **Hermes Bytecode Modification (v96)**:
  - Parses the Hermes binary format of `index.android.bundle`.
  - Mutates conditional opcodes within account status validation functions.
  - Recalculates the 20-byte SHA-1 integrity digest in the bundle footer to satisfy Hermes runtime verification during initialization.

- **Bundle Loading and Runtime Hooks**:
  - `JSBundleLoader`: Intercepts bundle retrieval to supply the patched bytecode buffer instead of the default asset stream.
  - `SourceCodeModule`: Normalizes `scriptURL` to standard React Native asset paths to prevent runtime exceptions.
  - `ClassLoader` / `BaseDexClassLoader`: Hooks dynamic class loading to intercept Firebase, RevenueCat, and MMKV components as soon as they are loaded into memory.

- **State and Remote Configuration Synchronization**:
  - Firebase Remote Config: Overrides target configuration keys in process memory.
  - RevenueCat and Firestore: Normalizes entitlement and subscription state fields within the local session.

## Requirements

- **Runtime Environment**:
  - Android 8.0 (API Level 26) or higher with root access.
  - LSPosed (Zygisk or Riru) or a compatible modern Xposed framework.
- **Build Environment**:
  - JDK 17 (or compatible Java runtime configured via Gradle).
  - Android SDK with Platform SDK 34.
  - Bundled Gradle Wrapper.

## Build Instructions

Build using the included Gradle Wrapper:

```bash
# Debug build
./gradlew assembleDebug

# Release build (automatically signed with debug keystore)
./gradlew assembleRelease
```

Generated APK output locations:
- Release: `app/build/outputs/apk/release/LocketGold-v1.0.0-release.apk`
- Debug: `app/build/outputs/apk/debug/LocketGold-v1.0.0-debug.apk`

## Installation and Activation

1. Install the generated APK on the target device:
   ```bash
   adb install app/build/outputs/apk/release/LocketGold-v1.0.0-release.apk
   ```
2. Open LSPosed Manager and enable the **Locket Gold** module.
3. Verify that **Locket** (`com.locket.Locket`) is selected in the module scope.
4. Force stop the application process:
   ```bash
   adb shell am force-stop com.locket.Locket
   ```
5. Launch Locket and verify hook execution via logcat:
   ```bash
   adb logcat -s LocketGold
   ```

## License

This project is licensed under the **PolyForm Noncommercial License 1.0.0**.

You are free to use, modify, and distribute this software for **non-commercial purposes** only. Commercial use is strictly prohibited without explicit permission from the author.

See the [LICENSE](LICENSE) file for the full license text.

## Author & Credits

**Author:** birri (callmebirri)
**GitHub:** [github.com/callmebirri](https://github.com/callmebirri)

## Contributing

Contributions, issues, and feature requests are welcome!
Feel free to check the [issues page](https://github.com/callmebirri/BirriMonitor/issues).

## Disclaimer

**This software is provided "as is", without any warranty.** Use at your own risk. The author is not responsible for any damage or data loss caused by this tool.

---
<div align="center">

Developed with ❤️ by **birri**

</div>
