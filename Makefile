.PHONY: run build test test-integration lint clean

run:
	go run ./cmd/qmix

build:
	go build -o bin/qmix ./cmd/qmix

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