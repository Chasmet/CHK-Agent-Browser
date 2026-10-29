#!/usr/bin/env bash
set -euo pipefail
collect_studio_proof() {
  mkdir -p app/build/studio-proof
  adb shell pm list packages com.chk.agentbrowser
  adb exec-out run-as com.chk.agentbrowser.debug tar -C files/studio-proof -cf - . | tar -xf - -C app/build/studio-proof
}
trap collect_studio_proof EXIT
./gradlew --no-daemon connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true -Pandroid.testInstrumentationRunnerArguments.class=com.chk.agentbrowser.StudioAudioCropTest,com.chk.agentbrowser.StudioMultitrackIntegrationTest,com.chk.agentbrowser.StudioTouchIntegrationTest,com.chk.agentbrowser.Local3IndependenceTest
