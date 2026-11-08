# ADI AI v1.1 - rebuilt source

Rebuilt from the decompiled `ADI_AI_v1.0(1)` APK (apktool/smali). **No original Kotlin source was in the upload**, so every class was
re-written in Kotlin using the names, models, enums, language table, prompts, manifest and behaviour recovered from the smali.
Same package (`com.example`), same applicationId (`com.aistudio.adiai.vzkq`), same app name and ADI identity.

> **Status: written, NOT compiled, NOT run.** The build environment used had no Android SDK/Gradle/network. Expect a few compile
> fixes on first sync (typical for ~3000 lines written without a compiler). See `docs/TEST_REPORT.md` for the honest matrix.

## Build
1. Android Studio (Ladybug or newer), JDK 17. Open this folder. If the Gradle wrapper jar is missing: `gradle wrapper --gradle-version 8.9`.
2. Sync, then Run on a real phone (Android 8+; Android 11+ for screenshot-based screen AI). Emulators lack good speech recognition.
3. Release APK: copy `keystore.properties.example` -> `keystore.properties` and fill it in, then `./gradlew assembleRelease`.

## The AI brain (auto-select)
Settings > AI brain > **Auto** (default). Per message, `BrainRouter` picks the engine:
1. Small talk it has a rule for (greeting, time, date, who are you) -> built-in engine (instant, offline, no data sent).
2. Online + Gemini key -> Gemini. Offline (or "Prefer on-device" on) + Llama ready -> Llama 3.2 3B.
3. If the chosen engine fails, it falls to the next. Conversation history is re-sent from the local DB to every engine, so context survives switching.
4. If nothing can answer it says so honestly. Phone commands never need an AI engine.
You can also pin Hybrid / Llama / Gemini (it still falls back if the pinned one is unavailable). The reply shows "via <engine>".

## External requirements
| Need | Why | How |
|---|---|---|
| Gemini API key (free tier) | Cloud brain, camera/screen understanding | aistudio.google.com/apikey -> Settings > Gemini. Stored encrypted, never in the APK. |
| llama.cpp Android runtime | v1.0 shipped none, so "Llama" never really ran | step 3 below |
| Llama-3.2-3B-Instruct-Q4_K_M.gguf (~2 GB) | Offline brain | In-app download (Wi-Fi), resumable. Needs >=3.5 GB RAM (6 GB better), 2.4 GB free. |
| Google app / Speech Services + Hindi & English-India TTS voices | STT/TTS | Android Settings > Languages > Text-to-speech |
| Accessibility service (user enables) | Back/Recents/Notifications, swipes, tap-by-text, screen reading/screenshot | Settings > Open Accessibility settings |
| "Display over other apps" | Floating circle | Asked when you switch it on |

### Step 3 - enable real Llama inference
```
git clone https://github.com/ggml-org/llama.cpp
cp -r llama.cpp/examples/llama.android/llama  ./llama      # the library module (NOT the sample app)
```
Rebuild. `settings.gradle.kts` auto-includes `:llama` when the folder exists and `LlamaCppBackendImpl` (src/llamaAdapter) is compiled in.
It targets `android.llama.cpp.LLamaAndroid` (`instance()`, `load`, `send`, `unload`); if your checkout changed these, edit that one file.
Needs NDK + CMake via SDK Manager. Real-world speed on a mid phone: roughly 3-8 tokens/s.

## Alternatives where something can't be fully offline
- Wake word: uses SpeechRecognizer loops (needs Google speech; may beep; battery cost). Better: Porcupine or openWakeWord behind `VoiceManager`.
- Santali: no Android voice/recogniser exists; STT falls back to Hindi, TTS reads an approximate Roman transliteration.
- Image understanding offline: only OCR (ML Kit). Scene description needs Gemini.

## Safety behaviours
Calls and WhatsApp sends need confirmation (tap or say "haan"). ADI opens the dialer / prepares the chat - **you** press call/send, so it never claims a
call or message happened. Screen AI only on request, with a one-time consent, skips banking/password apps, and Android blocks secure windows.
Mic is used only while listening; "Hey ADI" shows a persistent notification. Backups are off.
