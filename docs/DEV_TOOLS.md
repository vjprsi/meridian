# Developer tools (portable, D:\DevTools)

Working notes on the local toolchain. Everything lives as portable copies under `D:\DevTools`; installed or refreshed
by `tools/setup/install-dev-tools.ps1` (re-runnable, skips what exists). The system JDK 17 and `JAVA_HOME` stay as they
are; nothing below changes the system PATH.

| Tool | Location | Notes |
| --- | --- | --- |
| JDK 25 (Temurin) | `D:\DevTools\jdk-25` | Selected by Gradle toolchains; `MERIDIAN_JDK25` user variable points here |
| Gradle | `D:\DevTools\gradle` | On the user PATH (appended) |
| kubectl, helm, tofu, temporal, k9s | `D:\DevTools\bin` | On the user PATH (appended) |
| DBeaver CE | `D:\DevTools\dbeaver` | Database GUI |
| Flutter SDK (stable) | `D:\DevTools\flutter` | Optional (`-WithFlutter`); **not** on PATH, call by full path |

## Flutter

- Installed: Flutter **3.47.6** (stable, framework 5fc346839b, 2026-09-30), Dart **3.13.5**, DevTools 2.60.0.
- Source: `https://storage.googleapis.com/flutter_infra_release/releases/stable/windows/flutter_windows_3.47.6-stable.zip`,
  SHA-256 `a01bb0d26de91bc23c97cd9ccfaad281a612fb8304213fdd5df1119a09404796`, verified against
  `releases/releases_windows.json` (`current_release.stable`) before extraction. The setup script does the same check
  for whatever release is current when it runs.
- Analytics disabled (`flutter --disable-analytics`, `dart --disable-analytics`; stored in the user profile).
- Use the full path, or put it on PATH for one shell only:
  - Git Bash: `/d/DevTools/flutter/bin/flutter --version`
  - PowerShell: `$env:Path = "D:\DevTools\flutter\bin;$env:Path"` (current session only)
- `flutter doctor` (2026-10-07):
  - OK: Flutter, Windows version, Chrome (web), connected devices (Windows, Chrome, Edge), network resources.
  - Flutter warns that `flutter` / `dart` are not on PATH (intended).
  - Missing **Android toolchain** (no Android SDK) - needed only for Android builds / emulators.
  - Missing **Visual Studio** (Desktop C++) - needed only for Windows desktop builds (not a target).
  - **Xcode / iOS** is not checked on Windows; iOS builds need a Mac.
- What works on this machine without those: `flutter pub get`, `flutter analyze`, `flutter test`, `flutter gen-l10n`,
  `flutter build web` (smoke builds of `mobile/customer_app` and `mobile/agent_app`).
- Pub cache: `%LOCALAPPDATA%\Pub\Cache` (default).

## Native mobile toolchains (not installed here)

See `mobile/README.md` ("Native builds"): Android Studio or the command-line tools + SDK 36 + an emulator image, then
`flutter config --android-sdk <path>`; iOS needs macOS with Xcode and CocoaPods / Swift Package Manager.
