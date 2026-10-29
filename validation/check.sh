#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
task_tmp=$(mktemp -d)
trap 'rm -rf "$task_tmp"' EXIT
javac -d "$task_tmp" app/src/main/java/com/chk/agentbrowser/RelayPolicy.java app/src/main/java/com/chk/agentbrowser/BrowserScripts.java validation/ScriptChecks.java
java -cp "$task_tmp" ScriptChecks "$task_tmp"
node validation/browser-checks.cjs "$task_tmp"
node --check app/src/main/assets/agent_keyboard.js
node validation/keyboard-checks.cjs
node --check app/src/main/assets/agent_vision.js
node validation/vision-checks.cjs

javac -d "$task_tmp" app/src/main/java/com/chk/agentbrowser/WorkspacePaths.java validation/WorkspacePathChecks.java
java -cp "$task_tmp" WorkspacePathChecks "$task_tmp/workspace"

javac -d "$task_tmp" app/src/main/java/com/chk/agentbrowser/BrowserPolicy.java app/src/main/java/com/chk/agentbrowser/TransferLimits.java validation/CapacityPolicyChecks.java
java -cp "$task_tmp" CapacityPolicyChecks
