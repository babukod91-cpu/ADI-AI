# Test report - ADI AI v1.1 (honest)

**Environment used to produce this:** Linux container, Java only, **no Android SDK, no Gradle, no network, no device/emulator, no microphone**.
Therefore **nothing has been compiled or run.** Nothing below is "tested on device".

## Fully implemented AND tested
None. (Only a static bracket-balance check of all 36 Kotlin files was run: 0 imbalances. That is not a compile.)

## Implemented, unit tests written but NOT executed (run `./gradlew test`)
- IntentParser (calls, WhatsApp, YouTube, alarm, timer, maps, system controls, swipes, screen/camera triggers), Replies (yes/no/stop/ordinal)
- LanguageDetector (scripts + Hinglish/English)
- BrainRouter.order (auto-select / fallback ordering)

## Implemented, NOT tested (needs build + real phone)
- Room history + clear, settings store, encrypted Gemini key
- Gemini REST client, hybrid built-in engine, router fallback across engines, shared context
- SpeechRecognizer loop + error recovery, TTS Indian voice selection, continuous mode, wake-word loop, audio focus
- App launching, calls (dialer), WhatsApp chat prep, YouTube search + first-result tap, Maps, alarm/timer, volume, torch, media keys
- Accessibility actions, screenshot + text screen AI, sensitive-app skip, consent flow
- Floating circle + quick card, Compose UI (orb states, chat sheet, settings, dark/light)
- ML Kit OCR + Gemini vision (Camera AI)

## Partially implemented
- Offline Llama: download manager, prompt template, engine and adapter exist; **real inference requires adding llama.cpp** (not in this ZIP, needs NDK).
- YouTube "play": taps first result via accessibility description match (fragile across YouTube versions); no pause/next other than media keys.
- Wake word: SpeechRecognizer-based (battery/beep caveats); voice barge-in *during* TTS is not implemented (echo) - tap orb or say stop when listening.
- Santali: no STT/TTS engine exists on Android; Hindi STT fallback + approximate transliteration for TTS.
- Screen AI uses AccessibilityService.takeScreenshot (Android 11+), not MediaProjection.

## Requires external API / model / permission / hardware
Gemini key; llama.cpp + 2 GB GGUF + 3.5 GB+ RAM; Google speech services + Indian TTS voices; Accessibility; overlay permission; mic/contacts/notification permissions; Android 11+ for screenshots.

## Known limits
Release APK not produced (no signing config / no build). WhatsApp: ADI opens the chat with text prefilled; you press send. Calls: dialer opens; you press call. 10-digit numbers assume +91.
