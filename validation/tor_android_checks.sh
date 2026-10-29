#!/usr/bin/env bash
set -euo pipefail
collect_tor_proof() {
  mkdir -p app/build/tor-proof
  adb exec-out run-as com.chk.agentbrowser.debug tar -C files/tor-proof -cf - . | tar -xf - -C app/build/tor-proof
}
trap collect_tor_proof EXIT
./gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=com.chk.agentbrowser.TorIsolationTest,com.chk.agentbrowser.TorHttpFramingTest,com.chk.agentbrowser.TorNetworkIntegrationTest -Pandroid.testInstrumentationRunnerArguments.tor_network_test=true
