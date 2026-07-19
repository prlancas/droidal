# Droidal

> A voice-driven robot face for Android. Wake word -> speech-to-text -> LLM
> (with tool calls that can drive the robot or update memory) -> streamed
> text-to-speech, optionally augmented by the rear camera for vision.

<p>
  <a href="https://github.com/prlancas/droidal/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/prlancas/droidal/actions/workflows/ci.yml/badge.svg?branch=main"></a>
  <a href="https://github.com/prlancas/droidal/actions/workflows/codeql.yml"><img alt="CodeQL" src="https://github.com/prlancas/droidal/actions/workflows/codeql.yml/badge.svg?branch=main"></a>
  <a href="https://github.com/prlancas/droidal/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/prlancas/droidal?display_name=tag&sort=semver"></a>
  <a href="./LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/license-MIT-blue.svg"></a>
  <img alt="minSdk 31" src="https://img.shields.io/badge/minSdk-31-brightgreen">
  <img alt="Kotlin 2.1" src="https://img.shields.io/badge/Kotlin-2.1.0-7f52ff">
  <img alt="AGP 8.13" src="https://img.shields.io/badge/AGP-8.13.0-3DDC84">
</p>

---

## What it does

Drop Droidal on a phone (the development target is a Samsung S23) and it
becomes an always-listening conversational assistant with an animated
face. The full pipeline runs on-device by default, with optional cloud
fallback for stronger models:

1. **Listen** - Picovoice Porcupine handles the wake word entirely offline.
2. **Transcribe** - Android's `SpeechRecognizer` turns the follow-up into
   text.
3. **Think** - the chosen LLM (on-device LiteRT-LM, Gemini REST, or any
   OpenRouter model) replies. It can call **tools** to update memory,
   set timers, drive the face, or query the camera.
4. **Speak** - replies are streamed sentence-by-sentence to Android's
   `TextToSpeech` so the robot starts talking before the LLM is done.
5. **See** (optional) - the rear camera + ML Kit face detection wakes the
   robot when someone's looking at it; a local multimodal model (or
   Gemini Vision) can describe what it sees.
6. **Remember** - a per-user markdown memory store and a background
   "reflector" worker distil conversations into long-term notes.

## Highlights

- **100% on-device path** - LiteRT-LM 0.10.0 runs Gemma / Gemma-3n
  models on the phone's GPU (Adreno / Mali via OpenCL) or CPU. No data
  leaves the device unless you opt in.
- **Provider-agnostic brain** - a single `LlmProvider` interface
  abstracts the LLM. Cloud providers (`GeminiRestProvider`,
  `OpenRouterProvider`) reuse the same tool schemas as the local one
  via `DroidalTools`.
- **Streaming TTS** - `TtsStreamer` batches model deltas into
  clause-sized chunks so the robot's mouth moves while the LLM is still
  generating.
- **Background learning** - WorkManager workers reflect on past
  conversations, scout the BBC news feed, and tidy stale memory while
  the screen is off.
- **Sample-style allowlist** - mirrors Google AI Edge Gallery's
  `model_allowlists/<version>.json` shape so adding a new on-device
  model is a JSON edit.

## Architecture

```
+-------------------+      +-------------------+      +------------------+
|   listen/         |----->|   brain/          |----->|   speech/        |
|  Porcupine wake   |      |  Agent + LLM +    |      |  TtsStreamer +   |
|  + SpeechRecog.   |      |  DroidalTools     |      |  Android TTS     |
+-------------------+      +-------------------+      +------------------+
                                    |
                                    v
                          +-------------------+      +------------------+
                          |   memory/learning/|      |   ui/face        |
                          |   FTS + reflector |      |   Compose canvas |
                          +-------------------+      +------------------+
                                    ^
                                    |
                          +-------------------+
                          |   camera/ vision/ |
                          |   CameraX + MLKit |
                          +-------------------+
```

Source layout under
[`app/src/main/java/com/prlancas/droidal/`](app/src/main/java/com/prlancas/droidal):

| Module              | What lives here                                                                 |
| ------------------- | ------------------------------------------------------------------------------- |
| `brain/`            | `Agent` orchestration loop; LLM providers; the `DroidalTools` surface.          |
| `brain/llm/`        | `LlmProvider` + `LiteRtLmProvider`, `GeminiRestProvider`, `OpenRouterProvider`. |
| `brain/tools/`      | Single source of truth for tools, with Gemini + OpenAI tool-call schemas.       |
| `speech/`           | `Speak`, `TtsStreamer` (clause batching), `MarkdownStripper`, `ListenLock`.     |
| `listen/`           | Wake-word wiring (Porcupine) + `WakeGuard` against double-listen races.         |
| `settings/`         | Encrypted prefs for API keys; Compose settings UI; model catalog loader.        |
| `camera/` `vision/` | CameraX + ML Kit face detection; local multimodal & Gemini Vision describers.   |
| `memory/learning/`  | Markdown stores, SQLite + FTS5 conversation log, reflector / news workers.      |
| `ui/`               | `FaceCanvas` / `FaceController` for the animated face.                          |
| `lifecycle/`        | `AppForegroundTracker` so background workers don't fight the UI for GPU.        |

## Quick start

### Prerequisites

- **JDK 17 (Corretto)** - pinned in [`.tool-versions`](.tool-versions). If
  you use [asdf](https://asdf-vm.com/), `asdf install` from the repo
  root grabs the right version automatically.
- **Android SDK** with platform 35 and the latest build-tools. Point
  `local.properties` at it (`sdk.dir=...`).
- **An Android 12+ device** (the OpenCL / GPU LLM path needs `minSdk 31`).
  A Samsung S23 is the primary development target.

### Build the debug APK

```bash
git clone git@github.com:prlancas/droidal.git
cd droidal

# Fast smoke test of the Kotlin source.
./gradlew :app:compileDebugKotlin

# Full debug APK. ALSO runs :app:testDebugUnitTest + :app:detekt as
# dependencies, so a green assemble means the tests and lint passed.
./gradlew :app:assembleDebug

# Output:
#   app/build/outputs/apk/debug/app-debug.apk
```

Sideload it onto your phone with `adb install` and you're off.

### Configure providers

Open the Droidal app, then `Settings`. The interesting knobs:

| Setting          | Where it goes                                                                                |
| ---------------- | -------------------------------------------------------------------------------------------- |
| Wake word        | Picovoice Porcupine - paste your free access key, pick a built-in word, or train a custom one. |
| LLM provider     | LiteRT-LM (on-device), Gemini REST, or OpenRouter.                                           |
| Local model      | Picks a model from the bundled allowlist (mirror of Google AI Edge Gallery `1_0_12.json`).   |
| Gemini key       | Stored in `EncryptedSharedPreferences`. Never logged.                                        |
| Hugging Face token | Needed for the gated Gemma-3n repos.                                                       |
| Learning loops   | Reflection / news-scout / memory-tidy worker cadences.                                       |

Provider resolution is in
[`LlmProviderFactory`](app/src/main/java/com/prlancas/droidal/brain/llm/LlmProviderFactory.kt)
and falls back gracefully if the chosen path isn't usable yet (e.g. local
model not downloaded -> OpenRouter if keyed -> Gemini if keyed -> clear
error in TTS).

## Development workflow

### Day-to-day commands

```bash
# Cheapest compile check after any Kotlin edit.
./gradlew :app:compileDebugKotlin

# JVM-only unit tests. Fast inner loop - seconds, not minutes. Most of
# Droidal's interesting code (TtsStreamer, MarkdownStore, conversation
# policy, tool schemas) is pure Kotlin and runs on the host JVM.
./gradlew :app:testDebugUnitTest

# Static analysis.
./gradlew :app:detekt

# Gold-standard pre-merge check - build + tests + detekt.
./gradlew :app:assembleDebug

# Wipe caches if Gradle feels stale.
./gradlew clean :app:assembleDebug
```

### Testing rules

JVM unit tests live in `app/src/test/` and run on the host JVM (no
emulator). They cover everything that doesn't strictly need Android.
Instrumented tests live in `app/src/androidTest/` and need a device or
emulator - reserve those for things that genuinely need Android (Compose
UI tests, real `SpeechRecognizer`).

You **must** add tests when you touch:

- `brain/ConversationListenPolicy` and `brain/ConversationErrorMessages`
  (load-bearing for UX - retry-after-blank-STT and friendly error mapping).
- `speech/TtsStreamer` and `speech/MarkdownStripper` (last stop before
  the speaker).
- `listen/WakeGuard` and `speech/ListenLock` (multi-thread CAS races
  that stop "double-listen").
- `memory/learning/MarkdownStore` and `memory/learning/LearningPaths`.
- Anything in `brain/tools/` - add the tool to **both**
  `OpenAIToolSchema` and `GeminiToolSchema` plus `DroidalToolDispatcher`,
  then update `ToolSchemaTest.expectedToolNames`.

Full guidance lives in
[`.cursor/rules/testing.mdc`](.cursor/rules/testing.mdc).

### Detekt baseline

[`app/config/detekt/baseline.xml`](app/config/detekt/baseline.xml)
snapshots existing lint issues so the build passes today. **Never
regenerate the whole baseline** to clear new violations - that defeats
the point. To clear a baselined entry: fix the underlying issue, delete
the matching `<ID>` line, run `./gradlew :app:detekt` to confirm.

## CI / CD

Three workflows live in [`.github/workflows/`](.github/workflows/):

### `ci.yml` - run on every push and PR

- Validates the Gradle wrapper signature (defends against tampered wrappers).
- Runs `:app:assembleDebug`, which transitively executes
  `:app:testDebugUnitTest` and `:app:detekt`.
- Runs `:app:assembleRelease` as a release-build smoke check, so
  ProGuard / resource-shrinker breakage shows up on the PR that caused
  it, not on the release that ships it.
- Publishes JUnit annotations on the PR diff via
  [`mikepenz/action-junit-report`](https://github.com/mikepenz/action-junit-report).
- Uploads the debug APK, unsigned release APK, detekt HTML report, and
  unit-test HTML report as artifacts (14-day retention).
- Cache is read-only on PRs and read-write on `main`, so an untrusted PR
  cannot poison the cache.

### `codeql.yml` - security scanning

GitHub CodeQL with the `security-extended` query suite, on every push,
every PR, and a weekly cron. Findings surface in the repo's **Security**
tab.

### `release.yml` - tag-triggered releases

Push a `vX.Y.Z` tag (or fire `workflow_dispatch` with a version string)
and the workflow:

1. Patches `versionCode` (epoch seconds) and `versionName` (the tag) into
   `app/build.gradle` for that build only.
2. Builds `:app:assembleRelease`.
3. Signs the APK with `apksigner` using the production keystore loaded
   from repo secrets - see below.
4. Verifies the signature.
5. Generates a markdown changelog from `git log` since the previous tag.
6. Creates the GitHub Release with the signed APK attached.

#### Setting up release signing

Generate a keystore once and store it as a repo secret. The workflow
uses GitHub Environments so you can require a manual review before
anything tagged hits the Release page.

```bash
# 1. Generate the keystore.
keytool -genkey -v -keystore droidal-release.jks \
  -keyalg RSA -keysize 2048 -validity 36500 \
  -alias droidal

# 2. Base64-encode it so it fits in a GitHub Secret.
base64 -i droidal-release.jks | pbcopy   # macOS
# base64 droidal-release.jks | xclip     # Linux
```

Then, in repo `Settings -> Secrets and variables -> Actions`, create an
**Environment** called `release` and add:

| Secret              | Value                                       |
| ------------------- | ------------------------------------------- |
| `KEYSTORE_BASE64`   | Output from `base64 droidal-release.jks`.   |
| `KEYSTORE_PASSWORD` | The keystore password you entered above.    |
| `KEY_ALIAS`         | `droidal` (or whatever `-alias` you used).  |
| `KEY_PASSWORD`      | The key password (same as keystore if you accepted the prompt). |

**Back the keystore up offline.** If you lose it, you cannot ship an
update on top of the existing app - users have to uninstall first.

### Dependency hygiene

- [`.github/dependabot.yml`](.github/dependabot.yml) opens grouped PRs
  weekly for Gradle dependencies and GitHub Actions. Major Kotlin and
  AndroidX bumps land as their own PRs so they get a real review;
  patch / minor bumps are grouped to keep the noise down.
- `dependency-review.yml` blocks PRs that introduce high-severity CVEs
  or copyleft-incompatible licenses.

### Ideas worth adding later

If you want to make CI even nicer, these all slot in cleanly:

1. **APK size diff on PRs** - run
   [`gradle-android-size`](https://github.com/mihaip/gradle-android-size)
   or `apkanalyzer` and post the delta as a sticky PR comment. Catches
   accidental dependency bloat.
2. **Screenshot tests** - Paparazzi or Roborazzi render the Compose UI
   on the JVM. Snapshots travel with the PR; visual regressions become
   obvious diffs.
3. **Macrobenchmark** - measure wake-word -> first-TTS latency on a
   physical device farm (Firebase Test Lab, BrowserStack App Live, or a
   self-hosted runner with a phone wired in over USB). Track the metric
   over time.
4. **F-Droid metadata** - add a `fastlane/metadata/android` directory
   and submit to [F-Droid](https://f-droid.org) for free, reproducible
   distribution.
5. **Reproducible builds** - pin the Android SDK build-tools version
   explicitly and add a build-comparison job that re-runs
   `assembleRelease` from a tagged commit and diffs the APKs.
6. **Play Store Internal Testing track** - add a `publish-play` job that
   uses [`r0adkll/upload-google-play`](https://github.com/r0adkll/upload-google-play)
   to push the signed AAB to the Internal Test track on every tag.
7. **Conventional commits + release-please** - drop the manual changelog
   step; let [release-please](https://github.com/googleapis/release-please)
   open a permanent "release PR" that bumps the version and writes
   `CHANGELOG.md` from commit prefixes (`feat:`, `fix:`, ...).
8. **Renovate alongside Dependabot** - Renovate handles LiteRT-LM /
   Hugging Face / model-allowlist updates as a custom datasource that
   Dependabot can't see.
9. **Build provenance + SBOM** - add
   [`actions/attest-build-provenance`](https://github.com/actions/attest-build-provenance)
   to sign the artifact, plus
   [`anchore/sbom-action`](https://github.com/anchore/sbom-action) for a
   CycloneDX SBOM. Worth it the day you ship to anyone but yourself.
10. **Sticky PR triage bot** - a tiny labeler workflow that auto-tags
    PRs based on changed paths (`brain/`, `speech/`, `ui/` ...).
11. **Nightly on-device smoke test** - a self-hosted runner connected to
    a spare S23, scheduled nightly, that installs the latest debug APK
    and runs an instrumented test that says the wake word into the
    speaker and asserts the TTS reply arrives in <3s.

## Roadmap

See open issues. Big-rock items live in
[`plans/`](plans/) - currently `conversation-naturalness.md`.

## References

This project mirrors patterns from two upstream samples that live in the
parent workspace (read-only):

- **[google-ai-edge/gallery](https://github.com/google-ai-edge/gallery)** -
  source of the LiteRT-LM engine config and the model-allowlist JSON
  shape.
- **[off-grid-mobile-ai](https://github.com/off-grid-mobile-ai)** -
  llama.cpp-based mobile AI patterns (OpenCL/Adreno, Qualcomm HTP,
  `<uses-native-library>` declarations).

## Contributing

PRs welcome. Before opening one:

1. Read [`.cursor/rules/`](.cursor/rules/) - the project conventions
   live there (build, testing, description).
2. `./gradlew :app:assembleDebug` should be green.
3. Add tests for anything in the modules listed under
   [Testing rules](#testing-rules) above.
4. Fill in the PR template - the "How it was tested" section is
   load-bearing for review speed.

## License

Droidal is released under the [MIT License](./LICENSE).
