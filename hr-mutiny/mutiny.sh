#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p evidence
./mvnw -B -ntp dependency:copy -Dartifact=io.smallrye.reactive:mutiny:3.3.1 -DoutputDirectory=target/probe-lib
java -Xlog:class+load=info:file=evidence/classes.log -cp target/probe-lib/mutiny-3.3.1.jar ResourceOrderProbe.java | tee evidence/mutiny.log
