#!/usr/bin/env bash
set -euo pipefail
output="${RUNNER_TEMP:-/tmp}/omni-imaging-tests"
g++ -O3 -std=c++17 -pthread -Wall -Wextra -I app/src/main/cpp \
  app/src/main/cpp/imaging_kernel.cpp app/src/test/cpp/imaging_kernel_test.cpp -o "$output"
"$output"
