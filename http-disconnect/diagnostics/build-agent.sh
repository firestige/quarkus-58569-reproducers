#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p diagnostics/target/classes
./mvnw -B -ntp org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy \
  -Dartifact=net.bytebuddy:byte-buddy:1.17.7 \
  -DoutputDirectory="$PWD/diagnostics/target" -Dmdep.stripVersion=true
javac -cp diagnostics/target/byte-buddy.jar -d diagnostics/target/classes diagnostics/src/diagnostic/TimingAgent.java
printf 'Premain-Class: diagnostic.TimingAgent\nClass-Path: byte-buddy.jar\n\n' > diagnostics/target/manifest.mf
jar cfm diagnostics/target/timing-agent.jar diagnostics/target/manifest.mf -C diagnostics/target/classes .
