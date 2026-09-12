# QMix TV для Android

Минимальное Android TV-приложение находится в одном модуле `android/app`.
Версию APK Gradle не вычисляет самостоятельно: при конфигурации он запускает
`go run ./internal/buildinfo/cmd/version -format=json` из корня репозитория и
берёт `versionName` из `version`, а `versionCode` из `androidVersionCode`.

## Зафиксированный toolchain

- minSdk 23, targetSdk 36, compileSdk 36;
- JDK 17;
- Gradle 8.13 (checked-in Wrapper с SHA-256 дистрибутива);
- Android Gradle Plugin 8.13.2;
- Kotlin/Compose Compiler plugin 2.3.21;
- Compose BOM 2026.05.01 и Compose for TV Material 1.1.0;
- Media3 ExoPlayer 1.11.1;
- Android Build Tools 35.0.0.

Нужны Go 1.25+, JDK 17 и Android SDK с platform 36/build-tools 35.0.0.
Локальный `android/local.properties` при необходимости задаёт `sdk.dir` и не
коммитится.

## Локальные проверки

```bash
cd android
./gradlew --no-daemon :app:assembleDebug
./gradlew --no-daemon :app:lintDebug
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebugAndroidTest
./gradlew --no-daemon :app:printSharedVersion
```

Нативный smoke запускается только на Android TV emulator/device:

```bash
cd android
./gradlew --no-daemon :app:connectedDebugAndroidTest
```

Он устанавливает APK, запускает activity через `LEANBACK_LAUNCHER`, проверяет
начальный фокус и отправляет D-pad OK. В CI используется Android TV API 36
(`android-tv`, x86, профиль `tv_1080p`).

## Покрытие

Обязательный gate — не менее 95% instruction coverage для всего bytecode пакета
`com.qmix.tv`. Исключаются только генерируемые Android-классы `R` и
`BuildConfig`; handwritten UI/state/domain-классы и Compose-код не исключаются.
Отчёт объединяет JVM/Robolectric и native instrumentation coverage:

```bash
cd android
./gradlew --no-daemon :app:jacocoDebugCoverageVerification
```

Команда требует подключённый emulator/device, потому что сама выполняет
`connectedDebugAndroidTest`. HTML/XML лежат в
`app/build/reports/jacoco/jacocoDebugReport/`.
