# FFmpeg / FFmpegKit — notice de redistribution / redistribution notice

CHK Agent Browser uses the non-GPL full variant of FFmpegKit maintained for Android, version 8.1.9 (Maven coordinate dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9), including FFmpeg and LAME. FFmpegKit credits Arthenica and the FFmpegKit maintained contributors; FFmpeg credits the FFmpeg developers; LAME credits the LAME developers. See individual source trees for all copyright notices. The LGPL and GPL texts referenced by LGPL are included in this folder. Individual third-party libraries may carry additional terms.

Upstream release: https://github.com/ffmpegkit-maintained/ffmpeg/releases/tag/v8.1.9-lts-android
Upstream source reference: https://github.com/ffmpegkit-maintained/ffmpeg/tree/v8.1.9-lts-android
Source archive: https://github.com/ffmpegkit-maintained/ffmpeg/archive/refs/tags/v8.1.9-lts-android.tar.gz
Build recipe: https://github.com/ffmpegkit-maintained/ffmpeg/blob/v8.1.9-lts-android/.github/workflows/build-81-free-full.yml
Source pins: SOURCE-PINS.sh in this folder (upstream copy, unchanged); use the public Android 8.1 LTS source tree, patches and dependencies.
FFmpeg source: https://github.com/arthenica/FFmpeg/tree/n8.1.3
LAME source: https://github.com/arthenica/lame/tree/RELEASE__3_100

The CHK application's Java integration is available in the public repository https://github.com/Chasmet/CHK-Agent-Browser . Recipients may modify the LGPL components and reverse engineer the combined work for debugging such modifications as provided by the LGPL. No CHK signing key is required to build a locally signed modified APK. A rebuilt APK will not have the publisher's signing identity; install a debug variant alongside the original app, never uninstall the original without a backup.

To substitute an interface-compatible rebuilt AAR, use:
./gradlew :app:assembleDebug -PchkFfmpegAar=/absolute/path/to/rebuilt-ffmpeg-kit.aar
Keep the existing Java API and both supported ABIs if needed. The debug application uses the .debug package suffix. Android SDK/NDK requirements and the upstream free-full build command are recorded in the linked recipe. This is a developer relinking path, not a claim that a modified APK has been tested.

Distribution gate: the exact published Maven AAR-to-source relationship, all notices from the actual dependency set, and a relinking test must be verified before redistribution. These reference files alone are not a certification of compliance.
