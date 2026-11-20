#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
export REPRO_PG_PORT="${REPRO_PG_PORT:-19169}"
export INVESTMENT_TEST_RUNTIME_PG_URL="postgresql://127.0.0.1:$REPRO_PG_PORT/repro"
export INVESTMENT_TEST_RUNTIME_PG_PASSWORD=p1-local-only P1_PATH_COMPARISON=true
project="hr58569-$$"
mkdir -p evidence
cleanup() {
  docker compose -p "$project" logs --no-color > evidence/postgres.log
  docker compose -p "$project" down -v
}
trap cleanup EXIT
docker compose -p "$project" up -d --wait
./mvnw -B -ntp -Dtest=P1TransactionPathComparisonTest test 2>&1 | tee evidence/direct-hr.log
