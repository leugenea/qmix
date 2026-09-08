FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod go.sum* ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /bin/qmix ./cmd/qmix

FROM alpine:3.24
# yt-dlp is required by the streaming backend (internal/stream) to resolve
# YouTube audio. Pinned to the version published in the alpine v3.24
# community repo; bump deliberately by updating this exact version.
RUN apk add --no-cache yt-dlp=2026.08.19-r0
# libcrypto3/libssl3 back python's HTTPS (yt-dlp). The alpine:3.24 base layer
# lags the v3.24 repo (trivy: CVE-2026-14456 in libcrypto3 3.5.7-r0 even on
# the 3.24.1 floating tag, qmix#38), so the two packages are upgraded
# explicitly. A targeted --upgrade, not a full `apk upgrade`, keeps the
# yt-dlp pin intact.
RUN apk add --no-cache --upgrade libcrypto3 libssl3
RUN adduser -D -H qmix
USER qmix
COPY --from=build /bin/qmix /usr/local/bin/qmix
EXPOSE 8080
ENTRYPOINT ["qmix"]
