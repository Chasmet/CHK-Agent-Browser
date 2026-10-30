#!/usr/bin/env bash
set -euo pipefail
bash validation/with_tor_dns_capture.sh ./gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=com.chk.agentbrowser.TorIsolationTest,com.chk.agentbrowser.TorHttpFramingTest,com.chk.agentbrowser.TorNetworkIntegrationTest -Pandroid.testInstrumentationRunnerArguments.tor_network_test=true
