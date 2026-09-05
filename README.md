# QMix

Коллаборативный музыкальный плеер с мульти-сервисными бэкендами: хост запускает
плеер на Google TV, друзья кидают ссылки из VK / Яндекс Музыки / Spotify в общую
очередь, а бэкенд резолвит трек и стримит аудио.

Мета-задача: leugenea/idea-engine#1.

## Статус

M0 — каркас: Go-скелет backend с `/healthz`, Makefile, docker-compose, CI.

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

## Запуск

```bash
make run
curl localhost:8080/healthz

# или через docker
docker compose up --build
```

## Токены сервисов

Авторизованный резолвинг (VK / Яндекс Музыка / Spotify) включается токенами
сервисов. Они читаются из окружения при старте в стиле `QMIX_ADDR`:

| Переменная | Сервис | Что это |
|---|---|---|
| `QMIX_VK_TOKEN` | VK | токен доступа VK Music |
| `QMIX_YM_TOKEN` | Яндекс Музыка | OAuth-токен Яндекс Музыки |
| `QMIX_SPOTIFY_CLIENT_ID` | Spotify | Client ID приложения |
| `QMIX_SPOTIFY_CLIENT_SECRET` | Spotify | Client Secret приложения |

Без токенов бэкенд работает в анонимном режиме (M2): Spotify — через oEmbed,
VK / Яндекс — честный 422. Токены нужны только для авторизованных путей
(милстоуны #8–#10).

### Где взять

**Spotify** — создать приложение в [Spotify Developer Dashboard](https://developer.spotify.com/dashboard),
оттуда взять Client ID и Client Secret.

**VK** — токен получается через утилиту `cmd/token-vk` (см. ниже) по номеру
телефона и паролю VK с подтверждением кода (пуш / SMS / почта).

**Яндекс Музыка** — токен получается через CLI `synchro` по OAuth-флоу Яндекс
(открывается страница, вводится код).

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
workflow `live.yml` на self-hosted раннере `nas`: вручную
(`gh workflow run live`) и еженедельно по расписанию; основной CI не
блокирует.

### Получение токена VK через `cmd/token-vk` (предпочтительно)

Утилита `cmd/token-vk` — обёртка над `github.com/oklookat/vkmauth` с тем же
флоу, что у `synchro`, но токен печатается в stdout и **ничего не сохраняет
на диск**. Телефон, пароль и код 2FA читаются из stdin (не из argv), поэтому
пароль не попадает в shell-history.

**Сборка и запуск:**

```bash
go run ./cmd/token-vk
# или собрать бинарник:
go build -o bin/token-vk ./cmd/token-vk
./bin/token-vk
```

Утилита последовательно запросит:

1. `Phone:` — номер телефона VK (полный, например `+79000000000`).
2. `Password:` — пароль от VK.
3. Код подтверждения — утилита покажет, куда он отправлен (пуш / SMS / почта),
   и предложит переотправить другим способом (пустой ввод = переотправить).

**Формат вывода** (машиночитаемый, построчно):

```
access_token=...
refresh_token=...
```

Удобно для `gh secret set` или env-файла:

```bash
go run ./cmd/token-vk | sed -n 's/^access_token=//p' | gh secret set QMIX_VK_TOKEN
```

Токены печатаются только в stdout и не логируются. При ошибке (неверный
пароль, отмена) утилита пишет человекочитаемое сообщение в stderr и
завершается с ненулевым кодом.

### Получение токенов VK и Яндекса через `synchro` (запасной способ)

> Для VK предпочтителен `cmd/token-vk` (см. выше). `synchro` остаётся
> запасным способом, а для Яндекс Музыки — основным.

Команды взяты из исходников `github.com/oklookat/synchro` (CLI `commander/cli`),
`github.com/oklookat/vkmauth` и `github.com/oklookat/yandexauth/v3`.

**VK:**

```bash
synchro account add vkmusic --phone <PHONE> --password <PASSWORD>
```

CLI запросит код подтверждения (пуш / SMS / почта) и сохранит токен в локальную
БД `synchro` (`data/data.sqlite`). Токен не печатается в stdout — его нужно
достать из БД аккаунта (поле `auth`, JSON с `access_token`).

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
  получить заново через `cmd/token-vk` (или `synchro account add vkmusic`).
- **Яндекс** — отозвать OAuth-токен в настройках Яндекс ID (раздел
  «Приложения» / «Управление доступом»); новый токен получить через
  `synchro account add yandexmusic`.

## Стек

- Backend: Go, REST + SSE, состояние in-memory
- TV-хост: Kotlin + Media3 (планируется)
- Гости: PWA без логина (планируется)
