# QMix

Коллаборативный музыкальный плеер с мульти-сервисными бэкендами: хост запускает
плеер на Google TV, друзья кидают ссылки из VK / Яндекс Музыки / Spotify в общую
очередь, а бэкенд резолвит трек и стримит аудио.

Мета-задача: leugenea/idea-engine#1.

## Статус

Готово:

- **M0** — каркас: Go-скелет backend с `/healthz`, Makefile, docker-compose, CI.
- **M1** — комната и очередь: create/get, add track, skip/reorder, SSE-события.
- **M2** — резолвер ссылка → трек через публичные endpoints (Spotify oEmbed, честный 422 для VK/Яндекса без токена).
- **M3** — стриминг: `yt-dlp` StreamBackend + HTTP-прокси с Range/seek и TTL-кэшем ссылок.
- **token-vk v2** — токен api.vk.com со scope `audio` (разблокирует #9).
- **token-ym** — мини-CLI для OAuth-токена Яндекс Музыки через device-flow, без sqlite (закрывает #12).
- **Авторизованный резолвинг Spotify** (#8) — Client Credentials (`accounts.spotify.com/api/token`) + Web API (`api.spotify.com`); oEmbed остаётся fallback без credentials.
- **Авторизованный резолвинг VK** (#9) — audio-ссылки VK резолвятся через `audio.getById` (api.vk.com, v5.131, мобильный User-Agent клиента из `cmd/token-vk`); без токена `QMIX_VK_TOKEN` остаётся анонимный og-meta путь.
- **Интеграционные тесты** основных сценариев — обязательная job `integration-mandatory` в CI.
- Свежий тулчейн: Go 1.25 / alpine 3.24, compose в рамках деплой-ограничений (порт 8180, `mem_limit`, `restart: on-failure:3`).

Дальше:

- **M4** — TV-приложение (Android TV), **M5** — PWA для гостей, **M6** — Definition of Done (E2E + v0.1.0).
- Авторизованный резолвинг сервисов: Яндекс Музыка (#10; токен выдаёт `cmd/token-ym`).

## Стриминг

Аудио для текущего трека отдаёт **StreamBackend** — по умолчанию поиск на
YouTube через `yt-dlp`. Когда хост нажимает *skip*, трек становится текущим,
и плеер берёт его поток с эндпоинта `GET /rooms/{code}/current/stream`.

Как это работает:

1. Бэкенд по метаданным трека (`Artist - Title`) ищет на YouTube через
   `yt-dlp --skip-download --dump-json -f bestaudio "ytsearch:..."`.
2. Прямая аудио-ссылка кэшируется на ~5 минут — повторные запросы того же
   трека не ищут заново.
3. Эндпоинт стримит аудио с поддержкой **HTTP Range/seek**: полный поток —
   `200`, удовлетворённый диапазон — `206 Partial Content`, некорректный
   диапазон — `416`. Заголовки `Content-Type`, `Content-Length`,
   `Content-Range`, `Accept-Ranges` пробрасываются клиенту.

**Требование:** на хосте, где крутится бэкенд, должен быть установлен `yt-dlp`
в `PATH` (или путь через `QMIX_YTDLP_BIN`). В Docker-образе `yt-dlp` уже
включён (версия зафиксирована в `Dockerfile`). Кэш настраивается через
`QMIX_STREAM_CACHE_TTL` (по умолчанию `5m`). Если YouTube доступен только
через прокси — задайте `HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY` в окружении,
compose прокидывает их в контейнер (учитываются и yt-dlp, и HTTP-прокси
аудио).

## Интеграционные тесты

Обязательный уровень — `make test-integration`: сценарии по HTTP на реальном
сокете с полным `App.Handler()`, без сети и секретов:

- `/healthz`;
- жизненный цикл комнаты: create → get → add track → skip/reorder (host-токен);
- SSE: snapshot на подключении, события мутаций, snapshot на реконнекте;
- добавление трека через мок-resolver (неизвестная ссылка → 422);
- стриминг через фейковый `yt-dlp` (путь через `QMIX_YTDLP_BIN`) и локальный
  мок-апстрим с поддержкой Range: 200/206;
- TTL: пустая комната протухает, комната с очередью живёт.

Тесты помечены build-тегом `integration`, поэтому обычный `go test ./...` и
coverage-гейт их не трогают. В CI идут отдельной job `integration-mandatory`.

Сетевые live-сценарии (VK/Spotify/Яндекс с реальными токенами, реальный
yt-dlp) остаются в gated-режиме: job `integration` с секретами
(`continue-on-error`) и workflow `live.yml` на self-hosted раннере.

## Запуск

```bash
make run
curl localhost:8080/healthz

# или через docker (внешний порт 8180)
docker compose up --build
curl localhost:8180/healthz
```

## Токены сервисов

Авторизованный резолвинг (VK / Яндекс Музыка / Spotify) включается токенами
сервисов. Они читаются из окружения при старте в стиле `QMIX_ADDR`:

| Переменная | Сервис | Что это |
|---|---|---|
| `QMIX_VK_TOKEN` | VK | токен доступа api.vk.com (scope `audio`) |
| `QMIX_YM_TOKEN` | Яндекс Музыка | OAuth-токен Яндекс Музыки |
| `QMIX_SPOTIFY_CLIENT_ID` | Spotify | Client ID приложения |
| `QMIX_SPOTIFY_CLIENT_SECRET` | Spotify | Client Secret приложения |

Без токенов бэкенд работает в анонимном режиме (M2): Spotify — через oEmbed,
VK / Яндекс — честный 422. Токены нужны только для авторизованных путей
(милстоуны #8–#10).

### Где взять

**Spotify** — создать приложение в [Spotify Developer Dashboard](https://developer.spotify.com/dashboard),
оттуда взять Client ID и Client Secret.

**VK** — токен api.vk.com со scope `audio` получается через утилиту
`cmd/token-vk` (см. ниже) по логину (телефон или email) и паролю VK
с подтверждением кодом (2FA-приложение / SMS / звонок).

**Яндекс Музыка** — токен получается через утилиту `cmd/token-ym` (см.
ниже) по OAuth device-flow Яндекс: утилита печатает URL и код, вы
подтверждаете в браузере.

### Как передать

**env при старте:**

```bash
QMIX_VK_TOKEN=... QMIX_YM_TOKEN=... \
QMIX_SPOTIFY_CLIENT_ID=... QMIX_SPOTIFY_CLIENT_SECRET=... \
make run
```

**`.env`-файл** (в `.gitignore`, не коммитится):

```bash
QMIX_VK_TOKEN=...
QMIX_YM_TOKEN=...
QMIX_SPOTIFY_CLIENT_ID=...
QMIX_SPOTIFY_CLIENT_SECRET=...
```

**docker-compose `env_file`:**

```yaml
services:
  backend:
    env_file: .env
```

**CI (GitHub Actions):**

```bash
gh secret set QMIX_VK_TOKEN
gh secret set QMIX_YM_TOKEN
gh secret set QMIX_SPOTIFY_CLIENT_ID
gh secret set QMIX_SPOTIFY_CLIENT_SECRET
```

Секреты прокидываются в отдельную `integration`-job (см. `.github/workflows/ci.yml`),
которая не блокирует основной CI.

Live-проверка YouTube (реальный yt-dlp с домашнего IP) идёт в отдельном
workflow `live.yml` на self-hosted раннере `nas`: при каждом пуше в main,
вручную (`gh workflow run live`) и еженедельно по расписанию; основной CI
не блокирует.

### Получение токена VK через `cmd/token-vk`

Утилита `cmd/token-vk` получает токен api.vk.com со scope `audio,offline`
через password grant `oauth.vk.com/token` от имени официального клиента
VK for Android — тот же флоу, что используют поддерживаемые VK-клиенты
(напр. vkpymusic). Токен печатается в stdout и **ничего не сохраняет на
диск**. Логин, пароль и код 2FA читаются из stdin (не из argv), поэтому
пароль не попадает в shell-history.

**Сборка и запуск:**

```bash
go run ./cmd/token-vk
# или собрать бинарник:
go build -o bin/token-vk ./cmd/token-vk
./bin/token-vk
```

Утилита последовательно запросит:

1. `Login (phone or email):` — логин VK (телефон или email).
2. `Password:` — пароль от VK.
3. Код подтверждения — утилита покажет, куда он отправлен (SMS /
   authenticator app / звонок), при неверном коде предложит ввести ещё раз.

**Формат вывода** (машиночитаемый, построчно):

```
access_token=...
user_id=...
expires_in=...
```

Поле `expires_in` имеет три состояния:

| Значение | Что сказал VK | Поведение утилиты |
| --- | --- | --- |
| `0` | токен бессрочный, scope `offline` применён | тихо |
| `>0` | токен временный, `offline` не выдан | предупреждение в stderr |
| `unknown` | поля `expires_in` в ответе не было | предупреждение в stderr |

В password grant VK игнорирует запрошенный `scope` и возвращает
собственную маску прав приложения, поэтому `offline` не гарантирован —
отсюда и необходимость смотреть на фактический ответ. Состояние `unknown`
отделено от `0` намеренно: отсутствующее поле не даёт оснований
утверждать, что токен бессрочный.

> `expires_in=0` означает лишь отсутствие планового срока годности. Токен
> всё равно привязан к сессии VK и может быть отозван досрочно — при смене
> пароля, завершении сеансов в настройках безопасности или срабатывании
> антифрода. См. [Отзыв и ротация](#отзыв-и-ротация).

Удобно для `gh secret set` или env-файла:

```bash
go run ./cmd/token-vk | sed -n 's/^access_token=//p' | gh secret set QMIX_VK_TOKEN
```

Токен печатается только в stdout и не логируется. При ошибке (неверный
пароль, flood control, капча) утилита пишет человекочитаемое сообщение
в stderr и завершается с ненулевым кодом. Код ответа можно переотправить,
перезапустив утилиту.

### Получение токена Яндекс Музыки через `cmd/token-ym` (основной способ)

Утилита `cmd/token-ym` получает OAuth-токен Яндекс Музыки через
**device code flow** Яндекс OAuth (`oauth.yandex.ru/device/code` +
`oauth.yandex.ru/token`) с кредами приложения Яндекс Музыки — теми же,
что встроены в исходники `synchro` (`remote/yandexmusic`). Ничего не
сохраняет на диск и не логирует: токен печатается в stdout в
машиночитаемом виде.

**Сборка и запуск:**

```bash
go run ./cmd/token-ym
# или собрать бинарник:
go build -o bin/token-ym ./cmd/token-ym
./bin/token-ym
```

Утилита напечатает URL страницы подтверждения и `user_code`: откройте
URL в браузере, войдите в Яндекс-аккаунт и введите код. После
подтверждения утилита напечатает токены:

```
access_token=...
refresh_token=...
```

Удобно для `gh secret set` или env-файла:

```bash
go run ./cmd/token-ym | sed -n 's/^access_token=//p' | gh secret set QMIX_YM_TOKEN
```

Токен живёт ~1 год (судя по ответу `expires_in`); для обновления
перезапустите утилиту. При ошибке (`expired_token` — код не ввели за
10 минут, `access_denied` — отказ на странице) утилита пишет
человекочитаемое сообщение в stderr и завершается с ненулевым кодом.

### Получение токена Яндекса через `synchro` (запасной способ)

> Для Яндекс Музыки основной способ — `cmd/token-ym` (см. выше);
> для VK `synchro` больше не используется (токен VK выдаёт
> `cmd/token-vk`).

Команды взяты из исходников `github.com/oklookat/synchro` (CLI `commander/cli`).

**Яндекс Музыка:**

```bash
synchro account add yandexmusic
```

CLI откроет страницу Яндекс OAuth, нужно войти и ввести код; токен сохраняется
в ту же локальную БД `synchro`.

> Точная команда извлечения токена из БД `synchro` уточняется — CLI не имеет
> готовой команды «показать токен»; при необходимости токен читается напрямую
> из `data/data.sqlite` (таблица `account`, колонка `auth`).

### Правила безопасности

- Токены **не коммитятся** в репозиторий (`.env` и `.env.*` в `.gitignore`).
- Токены **не логируются** — значения не выводятся в лог и не попадают в
  ответы API.
- Токены **не попадают в фикстуры** и тестовые данные.
- Для CI токены передаются только через GitHub Secrets, не через код.

### Отзыв и ротация

- **Spotify** — ротация в [Developer Dashboard](https://developer.spotify.com/dashboard):
  сгенерировать новый Client Secret, старый перестанет работать.
- **VK** — завершить активные сессии в настройках безопасности VK; новый токен
  получить заново через `cmd/token-vk`.
- **Яндекс** — отозвать OAuth-токен в настройках Яндекс ID (раздел
  «Приложения» / «Управление доступом»; токен выдан устройству
  `qmix-token-ym`); новый токен получить через `cmd/token-ym`.

## Стек

- Backend: Go, REST + SSE, состояние in-memory
- TV-хост: Kotlin + Media3 (планируется)
- Гости: PWA без логина (планируется)
