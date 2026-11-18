#!/usr/bin/env bash
set -euo pipefail

SERIAL=$(adb devices | awk '$1 ~ /^emulator-[0-9]+$/ && $2 == "device" {print $1; exit}')
test -n "$SERIAL"
STATE=$(mktemp -d)
LOG="$STATE/companion.log"
PC_PID=''
PORT=''
cleanup() {
    if [[ -n "$PORT" ]]; then adb -s "$SERIAL" reverse --remove "tcp:$PORT" || true; fi
    if [[ -n "$PC_PID" ]]; then kill "$PC_PID" 2>/dev/null || true; fi
    rm -rf "$STATE"
}
trap cleanup EXIT
python3 pc/CHK-Android-Emulator/chk_emulator.py --serial "$SERIAL" --port 0 --state-dir "$STATE" > "$LOG" 2>&1 &
PC_PID=$!
PAIRING=''
for _ in $(seq 1 30); do
    PAIRING=$(sed -n '/^https:\/\/127.0.0.1:/p' "$LOG")
    if [[ -n "$PAIRING" ]]; then break; fi
    if ! kill -0 "$PC_PID" 2>/dev/null; then break; fi
    sleep 1
done
if [[ -z "$PAIRING" ]]; then echo 'Le compagnon AVD ne démarre pas.'; exit 1; fi
PORT=${PAIRING%%|*}
PORT=${PORT##*:}
adb -s "$SERIAL" reverse "tcp:$PORT" "tcp:$PORT"
./gradlew --no-daemon :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.chk.agentbrowser.AndroidEmulatorWorkspaceTest \
    "-Pandroid.testInstrumentationRunnerArguments.chk_emulator_pairing=$PAIRING"
