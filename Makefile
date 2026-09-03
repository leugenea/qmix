.PHONY: run build test lint clean

run:
	go run ./cmd/qmix

build:
	go build -o bin/qmix ./cmd/qmix

test:
	go test ./...

lint:
	@test -z "$$(gofmt -l .)" || { echo "not gofmt-ed:"; gofmt -l .; exit 1; }
	go vet ./...

clean:
	rm -rf bin