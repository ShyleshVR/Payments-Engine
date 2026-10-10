#!/bin/bash
# Finds the highest arrival rate (payments/s) at which every SLO holds for a steady 3-minute run:
# step runs at increasing rates until one fails. Usage: scripts/load-ceiling.sh [rate ...]
#   scripts/load-ceiling.sh                 # 25 50 75 100 125 150 200 250 300 400
#   scripts/load-ceiling.sh 100 110 120     # a finer search around a known ceiling
# A warm-up run at half the first rate comes first and isn't judged (WARMUP=2m by default, 0 to
# skip): freshly started JVMs are still compiling, and pods the autoscaler adds during a step
# would otherwise make that step measure the warm-up rather than the system. Half, because cold
# pods at full rate fall behind, and the first judged step would inherit their backlog.
set -uo pipefail
cd "$(dirname "$0")/.."

RATES=("$@")
[ ${#RATES[@]} -eq 0 ] && RATES=(25 50 75 100 125 150 200 250 300 400)

if [ "${WARMUP:-2m}" != "0" ]; then
  echo "==> warm-up at $((RATES[0] / 2)) payments/s for ${WARMUP:-2m} (not judged)"
  scripts/load-test.sh step "$((RATES[0] / 2))" "${WARMUP:-2m}" >/dev/null
  sleep 30
fi

ceiling=0
for rate in "${RATES[@]}"; do
  output=$(scripts/load-test.sh step "$rate" "${STEP_DURATION:-3m}")
  echo "$output" | tail -n +2
  if echo "$output" | grep -q "^PASS"; then
    ceiling=$rate
    sleep 30   # let the system settle before the next step
  else
    echo "==> ceiling: $ceiling payments/s (the first rate that broke an SLO: $rate)"
    exit 0
  fi
done
echo "==> every rate passed; ceiling is at least $ceiling payments/s"
