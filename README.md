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

## Стек

- Backend: Go, REST + SSE, состояние in-memory
- TV-хост: Kotlin + Media3 (планируется)
- Гости: PWA без логина (планируется)