# D-TECH APK Inspector & Explorer

**Offline Power-User Tool for Android APK Analysis**

## Overview
D-TECH APK Inspector is a fully offline, privacy-first Android application designed for power users, security researchers, and developers. It allows deep inspection of APK files (installed or external) without requiring internet access, root, or remote servers.

## Features
- **100% Offline**: No internet permission requested. No analytics. No tracking.
- **Deep File Explorer**: Browse `classes.dex`, `resources.arsc`, `AndroidManifest.xml` and more.
- **Static Analysis**: Detect dangerous permissions, debug flags, and native libraries.
- **Hex Viewer**: Inspect binary files byte-by-byte.
- **Risk Scoring**: Heuristic engine to evaluate app safety.
- **Export**: Generate PDF reports of your findings.

## Building
To build the project, you need JDK 17 and Android SDK.

```bash
./gradlew assembleDebug
```

## Constraints & Privacy
- **No Internet**: The app functionality is strictly local.
- **SAF (Storage Access Framework)**: We treat your storage with respect. We only access files you explicitly select.

## Tech Stack
- Kotlin
- Jetpack Compose
- Native Android APIs (PdfDocument, ZipFile)
