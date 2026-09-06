FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod go.sum* ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /bin/qmix ./cmd/qmix

FROM alpine:3.22
# yt-dlp is required by the streaming backend (internal/stream) to resolve
# YouTube audio. Pinned to the version published in the alpine v3.22
# community repo; bump deliberately by updating this exact version.
RUN apk add --no-cache yt-dlp=2026.06.16-r0
RUN adduser -D -H qmix
USER qmix
COPY --from=build /bin/qmix /usr/local/bin/qmix
EXPOSE 8080
ENTRYPOINT ["qmix"]
