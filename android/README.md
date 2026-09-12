# QMix for Android TV

The minimal Android TV application is contained in the single `android/app` module.
Gradle does not calculate the APK version itself: during configuration, it runs
`go run ./internal/buildinfo/cmd/version -format=json` from the repository root
and takes `versionName` from `version` and `versionCode` from `androidVersionCode`.

## Pinned toolchain

- minSdk 23, targetSdk 36, compileSdk 36;
- JDK 17;
- Gradle 8.13 (checked-in Wrapper with the distribution SHA-256);
- Android Gradle Plugin 8.13.2;
- Kotlin/Compose Compiler plugin 2.3.21;
- Compose BOM 2026.05.01 and Compose for TV Material 1.1.0;
- Media3 ExoPlayer 1.11.1;
- Android Build Tools 35.0.0.

Go 1.25+, JDK 17, and the Android SDK with platform 36/build-tools 35.0.0 are required.
The local `android/local.properties` file may define `sdk.dir` if necessary and is
not committed.

## Local checks

```bash
cd android
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:lintDebug
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebugAndroidTest
./gradlew --no-daemon :app:printSharedVersion
```

Run the instrumentation suite only on an Android TV emulator/device:

```bash
cd android
./gradlew --no-daemon :app:connectedDebugAndroidTest
```

The suite installs the APK, launches the activity through `LEANBACK_LAUNCHER`,
checks the initial focus, and sends D-pad OK. CI uses Android TV API 36
(`android-tv`, x86, `tv_1080p` profile).

## Coverage

The required gate is at least 95% instruction coverage for all bytecode in the
`com.qmix.tv` package. Only the generated Android classes `R` and `BuildConfig`
are excluded; handwritten UI/state/domain classes and Compose code are not.
The report combines JVM/Robolectric and native instrumentation coverage:

```bash
cd android
./gradlew --no-daemon :app:jacocoDebugCoverageVerification
```

The command requires a connected emulator/device because it runs
`connectedDebugAndroidTest` itself. The HTML/XML reports are located in
`app/build/reports/jacoco/jacocoDebugReport/`.
