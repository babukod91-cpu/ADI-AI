# What the uploaded ADI AI v1.0 actually was (findings)

Input: `ADI_AI_v1_0_1__base_src.zip` = **apktool output of a built APK** (smali + decoded resources). No `.kt/.java`, no Gradle files.
Package `com.example` (applicationId `com.aistudio.adiai.vzkq`), minSdk 24, targetSdk 36. 304 smali classes in `com.example`.

## Recovered architecture (kept in v1.1)
MainActivity, AssistantViewModel, VoiceManager, AdiVoiceAccessibilityService, LlamaModelManager, LlamaInferenceEngine,
IntentParser-like logic inside IntelligentAiRepository, AndroidActionExecutor, AppLauncherManager, CameraAiAnalyzer, Room (`conversations`),
enums/models: AiEngineMode, AssistantMode, AvatarEmotion, LanguageMode (14 Indian languages + Hinglish), LlamaState/Status, SystemActionType,
AssistantAction (OpenApp, SetTimer, SetAlarm, MakePhoneCall, AnswerText, SendWhatsApp, SearchYouTube, PlaySpotify, SystemControl, NavigateMaps, OpenCameraAI),
PendingConfirmation, VoiceSettings, VisionScanResult. System prompt, model file name/URL, voice-selection markers, app alias tables (incl. Hindi/Bengali/Odia/Santali names), regexes and persona strings were carried over.

## Problems found (all addressed in v1.1 unless noted)
| # | Finding in v1.0 | v1.1 |
|---|---|---|
| 1 | "Llama" engine returned **canned strings** ("Processed locally via Meta Llama 3.2 3B..."). No native llama runtime in `lib/` (only AndroidX libs). It claimed offline LLM but never ran one. | Real `LlamaBackend` seam + download manager + prompt template. **Needs llama.cpp module added** (README step 3) - until then status says "runtime not bundled". |
| 2 | "ADI Hybrid / neural engine" was regex rules + canned replies marketed as a neural engine. | Kept as honest "ADI built-in (rules)"; fails on open questions so the router can use a real engine. |
| 3 | Camera AI returned **hard-coded fake results** ("INVOICE #4092", "Botanical Specimen"). | ML Kit OCR offline; Gemini vision online; result says which. |
| 4 | No Gemini call found in app code (Firebase AI lib present but unused by app classes). | REST client, key in EncryptedSharedPreferences, error mapping, fallback. |
| 5 | Accessibility service only logged connect/interrupt; actions depended on it ("Enable ADI Accessibility...") | Back/Home/Recents/Notifications, swipes, tap-by-text, YouTube first-result tap, screen text + screenshot. |
| 6 | Manifest: `QUERY_ALL_PACKAGES`, `CAMERA`, `VIBRATE`, no overlay/FGS/contacts, no speech `<queries>` (Android 11+ recognition visibility) | Minimal permissions, correct `<queries>`, FGS types, contacts on demand. |
| 7 | Alarm/timer/call/WhatsApp results reported as done after merely launching an intent | Messages now say what actually happened ("dialer opened", "request sent to clock app"). |
| 8 | No wake word, no overlay circle, no screen AI, no contact disambiguation, no confirmation flow beyond a data class | Added (untested on device). |
| 9 | Assets `res/raw/hello.wav`, `videoplayback.mp4` not referenced by any `com.example` class | Not carried over. |
| 10 | Firebase/Google sign-in resources and `common_google_signin_*` leftovers | Dropped (unused). |

Not recoverable from smali with reasonable effort: exact Compose layouts/colours of the v1.0 screens (UI re-designed, same identity/name/icon).
