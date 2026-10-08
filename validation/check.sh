#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
task_tmp=$(mktemp -d)
trap 'rm -rf "$task_tmp"' EXIT
javac -d "$task_tmp" app/src/main/java/com/chk/agentbrowser/RelayPolicy.java app/src/main/java/com/chk/agentbrowser/BrowserScripts.java validation/ScriptChecks.java
java -cp "$task_tmp" ScriptChecks "$task_tmp"
node validation/browser-checks.cjs "$task_tmp"
