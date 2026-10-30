#!/usr/bin/env bash
set -euo pipefail
# Emulator/debug application only. No production package data or phone credentials.
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell run-as com.chk.agentbrowser.debug mkdir -p files/speech_models
tar -C .ci-test-models -cf - vosk-model-small-en-us-0.15 | adb shell -T run-as com.chk.agentbrowser.debug tar -C files/speech_models -xf -
adb shell run-as com.chk.agentbrowser.debug test -d files/speech_models/vosk-model-small-en-us-0.15/graph
