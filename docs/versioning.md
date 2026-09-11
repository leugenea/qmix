# Версии и build metadata

QMix выпускает сервер, CLI-утилиты, Docker-образ и будущий Android APK как
один совместимый набор. У компонентов нет независимых версий.

## Источник версии

Релиз задаётся единственным тегом на коммите:

- stable: `vMAJOR.MINOR.PATCH`, например `v0.2.0`;
- release candidate: `vMAJOR.MINOR.PATCH-rc.N`, где `N` — число от 1 до
  9998 без ведущих нулей, например `v0.2.0-rc.1`.

Числа `MAJOR`, `MINOR` и `PATCH` следуют SemVer 2.0 и не имеют ведущих нулей.
Build metadata (`+...`) и prerelease-формы, отличные от `rc.N`, в release-тегах
QMix не используются: это сохраняет однозначное монотонное отображение в
Android `versionCode`. На одном release-коммите должен быть ровно один тег.
Malformed tag, несколько тегов на `HEAD`, dirty tree у release-тега и
несогласованные встроенные поля приводят к ошибке сборки или `--version`.

Нетегированная сборка имеет вид
`0.0.0-dev.<12-hex-commit>[.dirty]`. Полный 40-символьный commit и `dirty`
также присутствуют отдельными полями. Timestamp не используется, поэтому
одинаковые входы дают одинаковую metadata.

Единственный вычислитель контракта:

```bash
# JSON: version, commit, dirty, androidVersionCode
make version
# те же значения как linker flags
go run ./internal/buildinfo/cmd/version -format=ldflags
# shell-переменные для Docker/CI
go run ./internal/buildinfo/cmd/version -format=env
```

`make build` встраивает эти значения через Go linker flags во все три бинаря.
`qmix --version`, `token-vk --version` и `token-ym --version` печатают одну
строку JSON с одинаковой схемой, например:

```json
{"version":"0.2.0-rc.1","commit":"0123456789abcdef0123456789abcdef01234567","dirty":false,"androidVersionCode":2000001}
```

Бинарники не читают `.git` во время выполнения.

## Android-контракт для #56

Android build обязан получать оба значения только из вывода общего
вычислителя:

- `versionName = version` (без префикса `v`);
- `versionCode = MAJOR*100000000 + MINOR*1000000 + PATCH*10000 + STAGE`;
- `STAGE = N` для `rc.N`, `STAGE = 9999` для stable;
- локальная dev-сборка получает `versionCode = 1` и не публикуется.

Допустимые диапазоны: `MAJOR 0..20`, `MINOR/PATCH 0..99`, `rc.N 1..9998`.
Таким образом, `rc.1 < rc.2 < stable`, а bump patch/minor/major всегда больше
предыдущей версии. Максимальный код — `2099999999`, ниже лимита Google Play
`2100000000`. В Android Gradle-файлах нельзя заводить отдельную константу
версии или вычислять код повторно: #56 должен вызвать этот механизм и
передать полученные поля в `versionName`/`versionCode`.

## Bump, RC и stable

1. Выберите следующий `MAJOR.MINOR.PATCH`: PATCH для совместимого исправления,
   MINOR для обратно совместимой функции, MAJOR для несовместимого изменения.
2. Убедитесь, что рабочее дерево чистое и нужный commit находится в основной
   линии разработки.
3. Для приёмки поставьте `vX.Y.Z-rc.1`; последующие кандидаты увеличивают только
   `N`. Не переносите существующий тег.
4. Stable — новый тег `vX.Y.Z` на принятом коммите. RC-тег не переименовывается.
5. Перед публикацией сравните JSON `--version` всех бинарей и OCI labels образа
   с тегом и commit. Публикация артефактов остаётся задачей #67.

Docker принимает обязательные build args `VERSION`, `COMMIT`, `DIRTY` и
`ANDROID_VERSION_CODE`, встраивает их в бинарник и проверяет его во время
сборки. Финальный образ содержит OCI labels
`org.opencontainers.image.version`, `org.opencontainers.image.revision` и
`org.opencontainers.image.source`. Локальная/CI-проверка:

```bash
.github/scripts/test_docker_metadata.sh qmix:metadata-test
```
