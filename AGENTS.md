# AGENTS.md

## Project Constraints
This project is an **Offline APK Inspector & Explorer**.
Any future changes MUST adhere to the following constraints:

1. **NO INTERNET PERMISSION**: The app must never request `android.permission.INTERNET`.
2. **NO TELEMETRY**: Do not add any analytics SDKs (Firebase, Crashlytics, etc.).
3. **NO BACKEND**: All logic must run locally on the device.
4. **SAF ONLY**: Use Android Storage Access Framework for file access. No broad storage permissions.
5. **STATIC ANALYSIS**: Do not execute any code from the analyzed APK. Analyze it statically.

## Architecture
- **Language**: Kotlin
- **UI**: Jetpack Compose
- **Build System**: Gradle
- **Parsing**: Custom or lightweight OSS parsers (No GPL).

## Code Style
- Follow standard Kotlin coding conventions.
- Keep the "Analyzer" logic separate from "UI" logic.
- Ensure all IO operations happen on `Dispatchers.IO`.
