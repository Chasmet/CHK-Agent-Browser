#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ -f gradle/wrapper/gradle-wrapper.jar ]; then
    exec java -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain "$@"
fi
if command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
fi
echo "Gradle requis : utiliser GitHub Actions qui installe Gradle 8.6." >&2
exit 1
