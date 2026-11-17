#!/usr/bin/env bash
# Builds the engine and starts the Spring Boot API with 6 embedded cache nodes + in-memory H2.
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -B -q -DskipTests install
mvn -B -pl management-api spring-boot:run
