.PHONY: run compose-up build version test test-integration test-workflow-routing test-erosion test-complexity-gate test-duplication test-clone-gate test-hotspots duplication hotspots test-public-readiness test-sbom test-actionlint test-cyrillic check-cyrillic lint sbom sbom-go sbom-android sbom-schema sbom-validate clean

version:
	go run ./internal/buildinfo/cmd/version -format=json

run:
	@set -eu; ldflags="$$(go run ./internal/buildinfo/cmd/version -format=ldflags)"; \
		go run -ldflags "$$ldflags" ./cmd/qmix

compose-up:
	@set -eu; eval "$$(go run ./internal/buildinfo/cmd/version -format=env)"; \
		export VERSION COMMIT DIRTY ANDROID_VERSION_CODE; \
		QMIX_LOG_GID="$$(python3 scripts/prepare_compose_logs.py)" docker compose up --build $(COMPOSE_ARGS)

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

test-workflow-routing: test-erosion test-duplication test-complexity-gate test-clone-gate test-hotspots
	python3 .github/scripts/workflow_paths_test.py -v

test-hotspots:
	python3 .github/scripts/hotspots_test.py -v

test-clone-gate:
	python3 .github/scripts/clone_gate_test.py -v

test-complexity-gate:
	python3 .github/scripts/complexity_gate_test.py -v

test-duplication:
	python3 .github/scripts/jscpd_report_test.py -v

# JSCPD_BIN must point to a separately SHA-256-verified jscpd v5.3.2 binary.
# Keep local output outside the working tree; set TMPDIR to a scratch directory.
duplication: SHELL := /bin/bash
duplication: test-duplication
	@set -euo pipefail; : "$${JSCPD_BIN:?Set JSCPD_BIN to the checksum-verified jscpd v5.3.2 binary}"; \
		: "$${TMPDIR:?Set TMPDIR to a scratch directory}"; \
		out=$$(mktemp -d "$$TMPDIR/qmix-jscpd.XXXXXX"); \
		python3 .github/scripts/jscpd_report.py sources . > "$$out/sources"; \
		mapfile -d '' -t sources < "$$out/sources"; \
		"$$JSCPD_BIN" --config .jscpd.json --absolute --reporters json \
			--threshold 100 --fail-on-empty --output "$$out" "$${sources[@]}"; \
		python3 .github/scripts/jscpd_report.py report "$$out/jscpd-report.json" \
			"$$out/report.json" "$$out/report.md"; \
		printf 'Duplication report: %s\n' "$$out/report.md"

# Local informational qmix#242 ranking. Supply the same SHA-verified binary as
# duplication; HOTSPOTS_OUT optionally selects a persistent output directory.
HOTSPOTS_REF ?= main
HOTSPOTS_DAYS ?= 90
HOTSPOTS_OUT ?=
hotspots: SHELL := /bin/bash
hotspots: test-hotspots
	@set -euo pipefail; : "$${JSCPD_BIN:?Set JSCPD_BIN to the checksum-verified jscpd v5.3.2 binary}"; \
		: "$${TMPDIR:?Set TMPDIR to a scratch directory}"; \
		out="$(HOTSPOTS_OUT)"; if [[ -z "$$out" ]]; then out=$$(mktemp -d "$$TMPDIR/qmix-hotspots.XXXXXX"); fi; \
		mkdir -p "$$out"; scratch=$$(mktemp -d "$$TMPDIR/qmix-hotspots-input.XXXXXX"); \
		trap 'rm -rf "$$scratch"' EXIT; \
		python3 .github/scripts/erosion.py --repo . --lizard "$$(command -v lizard)" \
			--output "$$scratch/erosion.json" --summary "$$scratch/erosion.md"; \
		python3 .github/scripts/jscpd_report.py sources . > "$$scratch/sources"; \
		mapfile -d '' -t sources < "$$scratch/sources"; \
		"$$JSCPD_BIN" --config .jscpd.json --absolute --reporters json \
			--threshold 100 --fail-on-empty --output "$$scratch" "$${sources[@]}"; \
		python3 .github/scripts/jscpd_report.py report "$$scratch/jscpd-report.json" \
			"$$scratch/duplication.json" "$$scratch/duplication.md"; \
		python3 .github/scripts/hotspots.py --repo . --erosion "$$scratch/erosion.json" \
			--duplication "$$scratch/duplication.json" --skip-list docs/code-quality.md \
			--ref "$(HOTSPOTS_REF)" --days "$(HOTSPOTS_DAYS)" \
			--json "$$out/report.json" --markdown "$$out/report.md"; \
		printf 'Hotspot reports: %s/report.md %s/report.json\n' "$$out" "$$out"

test-erosion:
	python3 .github/scripts/erosion_test.py -v
	python3 .github/scripts/kotlin_complexity_test.py -v
	python3 .github/scripts/benchmark_metrics_test.py -v

test-public-readiness:
	python3 .github/scripts/public_readiness_test.py -v

test-sbom:
	python3 .github/scripts/generate_sbom_test.py -v

# actionlint v1.7.7 predates GitHub's concurrency.queue syntax; suppress only
# that exact parser diagnostic. Policy tests require queue: max on both jobs.
test-actionlint:
	go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.7 -ignore 'unexpected key "queue" for "concurrency" section[.] expected one of "cancel-in-progress", "group"' .github/workflows/*.yml

test-cyrillic:
	python3 .github/scripts/check_cyrillic_test.py -v

check-cyrillic:
	python3 .github/scripts/check_cyrillic.py

lint: check-cyrillic
	@test -z "$$(gofmt -l .)" || { echo "not gofmt-ed:"; gofmt -l .; exit 1; }
	go vet ./...

sbom: sbom-go sbom-android

sbom-go: build
	python3 .github/scripts/generate_sbom.py go --output dist/sbom/qmix-go.cdx.json
	python3 .github/scripts/generate_sbom.py validate dist/sbom/qmix-go.cdx.json
	.github/scripts/validate_sbom_schema.sh dist/sbom/qmix-go.cdx.json

ANDROID_APK ?= android/app/build/outputs/apk/release/app-release-unsigned.apk
sbom-android:
	cd android && ./gradlew --no-daemon --dependency-verification=strict :app:assembleRelease
	python3 .github/scripts/generate_sbom.py android --apk "$(ANDROID_APK)" --output dist/sbom/qmix-android.cdx.json
	python3 .github/scripts/generate_sbom.py validate dist/sbom/qmix-android.cdx.json
	.github/scripts/validate_sbom_schema.sh dist/sbom/qmix-android.cdx.json

sbom-schema:
	.github/scripts/validate_sbom_schema.sh dist/sbom/qmix-go.cdx.json dist/sbom/qmix-android.cdx.json

sbom-validate:
	python3 .github/scripts/generate_sbom.py validate dist/sbom/qmix-go.cdx.json dist/sbom/qmix-android.cdx.json
	$(MAKE) sbom-schema

clean:
	rm -rf bin dist/sbom