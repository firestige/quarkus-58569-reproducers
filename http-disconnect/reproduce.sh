#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
export REPRO_PG_PORT="${REPRO_PG_PORT:-19082}"
repro_project="${REPRO_COMPOSE_PROJECT:-hr-http-$$}"
mkdir -p evidence
repro_options=()
if [[ "${REPRO_TIMING:-false}" == true ]]; then
  ./diagnostics/build-agent.sh
  repro_options+=("-Drepro.timing=true" "-DargLine=-javaagent:\"$PWD/diagnostics/target/timing-agent.jar\"")
fi
cleanup() {
  docker compose -p "$repro_project" logs --no-color > evidence/postgres.log 2>&1 || true
  docker compose -p "$repro_project" down -v > evidence/cleanup.log 2>&1 || true
}
trap cleanup EXIT
docker compose -p "$repro_project" up -d --wait
set +e
./mvnw -B -ntp -Drepro.pg.port="$REPRO_PG_PORT" -Dtest=HttpDisconnectTest "${repro_options[@]}" test 2>&1 | tee evidence/test.log
repro_exit=${PIPESTATUS[0]}
set -e
printf '%s\n' "$repro_exit" > evidence/maven-exit-code.txt
exit "$repro_exit"
