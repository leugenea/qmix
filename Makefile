.PHONY: run build version test test-integration lint clean

version:
	go run ./internal/buildinfo/cmd/version -format=json

run:
	@set -eu; ldflags="$$(go run ./internal/buildinfo/cmd/version -format=ldflags)"; \
		go run -ldflags "$$ldflags" ./cmd/qmix

build:
	@mkdir -p bin
	@set -eu; ldflags="$$(go run ./internal/buildinfo/cmd/version -format=ldflags)"; \
		go build -ldflags "$$ldflags" -o bin/qmix ./cmd/qmix; \
		go build -ldflags "$$ldflags" -o bin/token-vk ./cmd/token-vk; \
		go build -ldflags "$$ldflags" -o bin/token-ym ./cmd/token-ym

test:
	go test ./...

# Mandatory integration level (qmix#17): core scenarios over HTTP on a real
# socket with the full App handler — no network, no secrets. The build tag
# keeps these tests out of `go test ./...` and the coverage gate.
test-integration:
	go test -race -tags=integration -run 'Integration' ./internal/server/...

lint:
	@test -z "$$(gofmt -l .)" || { echo "not gofmt-ed:"; gofmt -l .; exit 1; }
	go vet ./...

clean:
	rm -rf bin