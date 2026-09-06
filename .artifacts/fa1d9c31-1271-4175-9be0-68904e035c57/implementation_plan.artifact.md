# Implementation Plan - Fix FFmpegKit Dependency Resolution

The project is failing to build because it's trying to resolve `dev.ffmpegkit-maintained:ffmpeg-kit-full-81:8.1.4` from Maven Central. However, the "Full" version of the community-maintained FFmpegKit fork is a paid tier and is not hosted on public Maven repositories.

## Proposed Changes

### [app]

#### [MODIFY] [build.gradle.kts](file:///C:/Users/danie/AndroidStudioProjects/UchuvaTwinApp/app/build.gradle.kts)
- Switch from the non-public `ffmpeg-kit-full-81` to the public `ffmpeg-kit-free-81` artifact.
- Update the version to `8.1.7`, which is the latest stable version available on Maven Central.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:assembleDebug` to verify that the dependency resolves correctly and the project builds.

### Manual Verification
- Deploy the app to a device/emulator and verify that the FFmpeg conversion logic in `DronActivity.kt` still works as expected (it uses `mpeg4` codec which should be available in the free tier).
