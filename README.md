# Ara for Android

A pink-themed Kotlin and Jetpack Compose Android starter project, with a separate cloud-runtime foundation.

## Current status

The Android app currently provides local chat-history and private-note storage plus a local pause preference. Chat is still a placeholder: no AI provider or cloud connection is configured. The cloud runtime, task recovery, snapshots and action-permission gateway are code foundations, not a deployed service.

## Build a debug APK

1. Open the repository's **Actions** tab.
2. Select **Build Ara Android APK** and choose **Run workflow** (or wait for a push build).
3. Open the completed run and download the **ara-debug-apk** artifact.
4. Extract `app-debug.apk` and install it on Android.

The Android project uses Gradle 8.9, Android Gradle Plugin 8.7.3 and Kotlin 2.0.21. The build workflow installs Gradle on the runner, so a local Gradle wrapper is not required for the CI build.

Never commit API keys. Financial actions and decisions remain under the user's control. Outbound communication and other sensitive external actions must require explicit approval.
