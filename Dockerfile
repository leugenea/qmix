FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod go.sum* ./
RUN go mod download
COPY . .
ARG VERSION
ARG COMMIT
ARG DIRTY
ARG ANDROID_VERSION_CODE
RUN test -n "$VERSION" && test -n "$COMMIT" && test -n "$DIRTY" && test -n "$ANDROID_VERSION_CODE"
RUN CGO_ENABLED=0 go build \
    -ldflags "-X=github.com/leugenea/qmix/internal/buildinfo.Version=${VERSION} -X=github.com/leugenea/qmix/internal/buildinfo.Commit=${COMMIT} -X=github.com/leugenea/qmix/internal/buildinfo.Dirty=${DIRTY} -X=github.com/leugenea/qmix/internal/buildinfo.AndroidVersionCode=${ANDROID_VERSION_CODE}" \
    -o /bin/qmix ./cmd/qmix
# Validation is part of the image build: malformed or inconsistent inputs fail.
RUN /bin/qmix --version >/dev/null

FROM alpine:3.24
ARG VERSION
ARG COMMIT
LABEL org.opencontainers.image.version=$VERSION \
      org.opencontainers.image.revision=$COMMIT \
      org.opencontainers.image.source=https://github.com/leugenea/qmix
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
