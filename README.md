# QMix

Коллаборативный музыкальный плеер с мульти-сервисными бэкендами: хост запускает
плеер на Google TV, друзья кидают ссылки из VK / Яндекс Музыки / Spotify в общую
очередь, а бэкенд резолвит трек и стримит аудио.

Мета-задача: leugenea/idea-engine#1.

## Статус

M0 — каркас: Go-скелет backend с `/healthz`, Makefile, docker-compose, CI.

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

**VK** — токен получается через CLI `synchro` (см. ниже) по номеру телефона и
паролю VK с подтверждением кода (пуш / SMS / почта).

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

### Получение токенов VK и Яндекса через `synchro`

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
  получить заново через `synchro account add vkmusic`.
- **Яндекс** — отозвать OAuth-токен в настройках Яндекс ID (раздел
  «Приложения» / «Управление доступом»); новый токен получить через
  `synchro account add yandexmusic`.

## Стек

- Backend: Go, REST + SSE, состояние in-memory
- TV-хост: Kotlin + Media3 (планируется)
- Гости: PWA без логина (планируется)