# Conversation naturalness — implementation plan

Goal: make Droidal feel less like a request/response engine and more like
a charismatic, attentive conversational partner who knows the user, can
be interrupted, and stays interesting between turns.

This plan is organised into six phases, ordered so each phase only
depends on capabilities introduced in earlier phases. Each item lists
the **why**, the **what** (concrete code change), the **where** (which
modules), the **tests** to add (per `.cursor/rules/testing.mdc`), and a
rough **size** estimate (S/M/L/XL).

Out of scope (explicitly): KV-cache quantisation knob — LiteRT-LM 0.10.0
doesn't expose this; revisit when the SDK gains a `kvCacheDtype` /
similar field.

---

## Glossary of Droidal modules referenced

- `brain/Agent.kt` — orchestrates a conversation: wake → listen → LLM
  → stream TTS → listen.
- `brain/llm/LlmProvider.kt`, `LiteRtLmProvider`, `GeminiRestProvider`,
  `OpenRouterProvider`, `LlmProviderFactory` — provider abstraction.
- `brain/tools/DroidalTools.kt`, `DroidalToolDispatcher`,
  `OpenAIToolSchema`, `GeminiToolSchema` — LLM-callable tools.
- `speech/Speak.kt` — Android TextToSpeech wrapper, listens for `Say`
  events on the EventBus.
- `speech/TtsStreamer.kt` — chunks streaming LLM deltas into TTS-sized
  Say events, owns the `[END_CONVERSATION]` control marker.
- `speech/Filler.kt` — short conversational fillers ("Hmm…", "Yes?").
- `speech/SpeechToText.kt`, `listen/Listen.kt`, `listen/WakeGuard.kt` —
  STT pipeline + wake-word integration (Picovoice Porcupine).
- `brain/ConversationListenPolicy.kt` — patient pause + soft-prompt
  policy (pure-JVM testable).
- `memory/learning/LearningStore.kt`,
  `MarkdownStore.kt`, `LearningDatabase.kt`, `LearningPaths.kt` — the
  per-user MEMORY.md / USER.md / SQLite conversation store.
- `memory/learning/workers/ReflectorWorker.kt`,
  `NewsScoutWorker.kt`, `MemoryTidyWorker.kt` — background `WorkManager`
  jobs.
- `settings/SettingsRepository.kt`, `SettingsActivity.kt` — persistent
  prefs + Compose UI.
- `settings/learning/LearningActivity.kt` — Manage Learning page.
- `ui/FaceCanvas.kt`, `FaceController.kt` — animated face.
- `event/EventBus.kt` and `event/events/{Say, Look, StartConversation,
  StopSpeaking, OpenSettings}` — process-wide pub/sub.
- `lifecycle/AppForegroundTracker.kt` — gates worker LiteRT-LM loads.
- `debug/DebugBus.kt`, `DebugActivityState.kt` — UI state stream
  (currently used for the activity overlay).

---

## Cross-cutting conventions

- **Tests first when possible.** Every pure / mostly-pure piece of
  policy gets a JVM unit test under
  `Droidal/app/src/test/java/...`. Android-coupled logic gets a tiny
  injectable seam (lambda or interface) so its core is JVM-testable —
  same pattern `ConversationListenPolicy` and `TtsStreamer` already
  use.
- **No regressions in detekt baseline.** New code adds no new entries
  to `Droidal/app/config/detekt/baseline.xml`. Targeted
  `@Suppress(...)` only when truly unavoidable, with a one-line
  comment.
- **Settings keys.** Every new tunable lives in `SettingsRepository`,
  defaulted, with a Compose toggle on the relevant settings page. New
  experimental knobs default to OFF unless they're strictly an
  improvement.
- **Privacy.** No new network egress. All extra inference uses the
  existing on-device LiteRT-LM engine or whatever the user already
  selected (cloud Gemini / OpenRouter).
- **Tool registry.** New LLM-callable tools always go to
  `DroidalTools`, both schemas (`OpenAIToolSchema`, `GeminiToolSchema`)
  and `DroidalToolDispatcher`, and `ToolSchemaTest` is updated.
- **System prompt sections.** When a phase adds a new system-prompt
  block (interests, callbacks, dialectic instructions) it goes through
  `LearningStore.systemPromptBlock` so all three providers see it.

---

## Phase 1 — Foundations + low-risk wins

The foundation pieces: small in code, big in unblocking later phases,
and individually shipable.

### F1.1 — Per-conversation `InterruptFlag`  (size: **S**)

**Why.** Barge-in (Phase 2), the clarify tool (Phase 2) and several
later items need a thread-safe "stop whatever you're saying right
now" signal. We already publish `StopSpeaking` events for TTS; this
adds a per-conversation atomic flag that Agent + tools can check too.

**What.**
- New `brain/InterruptFlag.kt` — a tiny `AtomicBoolean` wrapper
  with `set()`, `clear()`, `isSet()`, scoped to one
  `Agent.haveConversation` call.
- `Agent.haveConversation` creates one per turn and publishes it
  through `LearningContext` so tools can read it on any thread.
- `DroidalTools` exposes a synchronous `interruptIfRequested()`
  helper for long-running tools.

**Tests.** `InterruptFlagTest` — concurrent set/clear/isSet, idempotent
set, multi-thread CAS race like `WakeGuardTest`.

### F1.2 — Time-of-day filler banks  (size: **S**)

**Why.** Today `Filler.kt` has flat pools. A small "morning Droidal
sounds different to evening Droidal" change is a free win in feel.

**What.**
- Refactor `Filler` so each pool is `Map<TimeOfDay, List<String>>`,
  with a `TimeOfDay` enum (`MORNING`, `AFTERNOON`, `EVENING`,
  `NIGHT`) and a clock-injection seam so unit tests are deterministic.
- Add bank-specific entries: morning is brisk ("Right, here we go"),
  evening is softer ("Mm, let's see…"), night is hushed.
- Public API unchanged (`sayLoadingBrain()`, `sayThinking()`, etc.).

**Tests.** Extend / add `FillerTest` (currently absent — also
back-fills coverage) for: deterministic clock injection, bank
selection, no-back-to-back-repeat preserved per (pool, time-of-day).

### F1.3 — Time-of-day persona slice in system prompt  (size: **S**)

**Why.** The LLM's voice should also drift with time-of-day — a
charismatic person isn't the same at 7 am and 11 pm.

**What.**
- Add a one-line "It is currently the {morning|afternoon|evening|
  late night} — match your tone accordingly" appendix to
  `LearningStore.systemPromptBlock`. Already has a `nowLine`; we
  expand it with a derived bucket.
- Reuse the `TimeOfDay` from F1.2 so wording stays consistent.

**Tests.** `LearningStoreSystemPromptTest` (extend or create) —
with a clock injection point, assert the suffix is present and bucket
maps correctly across the day.

### F1.4 — `<memory-context>` fence + `StreamingContextScrubber`  (size: **M**)

**Why.** Mirroring hermes-agent's pattern: wrap the recalled MEMORY /
USER / skills blocks in `<memory-context>…</memory-context>` with a
short system note. This (a) helps the LLM treat them as background
data rather than the user's current utterance, and (b) means we can
cleanly strip them out of streamed responses if they ever leak back.

**What.**
- `LearningStore.systemPromptBlock` now wraps the per-turn injected
  blocks (memory, user profile, skills index, recent sessions, news,
  and Phase-3 interests) inside a single fenced span with a
  `[System note: recalled background data, NOT new user input]`
  preface.
- New `speech/StreamingContextScrubber.kt` — a state machine that
  consumes streamed deltas and removes any `<memory-context>` span
  that sneaks into a model reply, even when the open and close tags
  arrive in different deltas. Modelled on hermes-agent's class of
  the same name.
- `TtsStreamer` runs feeds through the scrubber before its existing
  stop-token logic, so the streamed *spoken* text is clean.
- Static `MarkdownStore.sanitize(text)` helper for one-shot strips
  (used by `MemoriesPage` and `LearningActivity` when rendering
  potentially-tainted markdown).

**Tests.** `StreamingContextScrubberTest` — split-tag-across-deltas,
nested fences (defensive), trailing partial tag at flush, idempotent
on clean text. `LearningStoreSystemPromptTest` — fence is present,
preface line is present, removing it leaves the same content.

### F1.5 — Idle micro-behaviour for the face  (size: **M**)

**Why.** While Droidal is `LISTENING_FOR_WAKE_WORD` the face is
either fully static or in its base loop. Real people don't freeze.
A subtle randomised micro-state (eye-flick, tiny head tilt, slow
blink, small "pondering glance") every 4–9 seconds dramatically
improves "feels alive".

**What.**
- New `ui/FaceIdleAnimator.kt` — a small driver that, while the
  current `DebugActivityState` is `LISTENING_FOR_WAKE_WORD` and
  TTS isn't speaking, picks a micro-expression at random and
  publishes a brief `Look(...)` event. Subscribes to `DebugBus`
  and a new `Speak.isSpeakingFlow()`.
- `FaceController` consumes `Look` already; we add transient
  micro-expressions to `Expression` if needed.
- Frequency / on-off is gated by a new
  `SettingsRepository.idleAnimationsEnabled()` flag (default ON).

**Tests.** `FaceIdleAnimatorTest` — clock-driven, asserts no events
during SPEAKING / LISTENING_TO_USER / CALLING_LLM, asserts
distribution over WAKE_WORD idle, asserts respect of disabled flag.

---

## Phase 2 — Conversational repair

These are the items the user *feels* in the first 30 seconds of using
Droidal: getting interrupted gracefully, being asked back instead of
guessed at, and the soft-prompt becoming a useful "did you mean…?"

### F2.1 — Soft barge-in / interrupt during TTS  (size: **L**)

**Why.** Today TTS plays to completion. If Droidal is mid-sentence and
the user starts talking, they have to wait. A real conversation
allows interruption.

**What.**
- `Speak` exposes `isSpeaking()` (already partly modelled via
  `DebugBus.activity == SPEAKING`) and a new `stopAndDrainQueue()`
  that wraps `ttobj.stop()` and clears latches/callbacks.
- A new `BargeInDetector` (in `listen/BargeInDetector.kt`) that, while
  the agent is speaking, runs a low-power pass on the mic — *either*
  via Porcupine's keyword detection on a "barge in" wake word *or* a
  simple RMS / VAD check. (Initial implementation: piggy-back on the
  Porcupine session that's currently *paused* during a conversation.)
- When triggered: `EventBus.publish(StopSpeaking)` and fire the
  `InterruptFlag` from F1.1; the agent's TtsStreamer feed loop
  short-circuits and the agent immediately switches to listening
  for the next user turn.
- `SettingsRepository.bargeInEnabled()` (default ON), and an option
  for "wake-word barge-in only" vs "any voice".

**Tests.**
- `BargeInDetectorTest` — pure-JVM with injected RMS source / wake
  events, asserts trigger behaviour, debouncing (so a single cough
  doesn't barge in), and that the detector ignores audio while STT
  is the primary listener.
- `TtsStreamerInterruptTest` — feeding deltas after `interrupt()`
  is a no-op; existing buffered chunks are dropped; pending Say
  callbacks are released so `finishAndAwait()` doesn't hang.
- `UserJourneyIntegrationTest` — extend with a "barge-in mid-reply
  → user's new utterance is the next LLM turn" scenario.

**Risk.** Microphone contention. Picovoice keeps the mic when running,
Android `SpeechRecognizer` keeps the mic when running, and
TextToSpeech keeps a speaker stream. Initial slice should be
**wake-word-only** barge-in (already audio-friendly) and add
RMS/VAD as a follow-up.

### F2.2 — `clarify` LLM-callable tool  (size: **M**)

**Why.** When the model is uncertain it currently rambles through
options inside one reply. A natural "would you like X, or Y?" is a
much better UX, and it lets the agent stay short.

**What.**
- New tool in `DroidalTools`:
  `clarify(question: String, choices: List<String>)`. Capped at 4
  choices (5th = "Other / type your answer", reserved for future text
  fallback).
- Dispatcher behaviour: speak the question, then speak each numbered
  choice with a short pause; listen for one turn; map the heard
  answer to a choice using a small fuzzy matcher (number words,
  ordinals, exact-text, prefix-match). Return the matched choice.
- Update both schema files and `DroidalToolDispatcher`. Update
  `ToolSchemaTest.expectedToolNames`.

**Tests.**
- `ClarifyMatcherTest` (pure-JVM): "one"/"two"/"three", "first",
  "the second one", exact-text, prefix, ambiguous → returns null.
- `ToolSchemaTest` — pin the new tool name in both schemas.
- `DroidalToolDispatcherTest` (extend or create) — the dispatcher
  invokes Speak / Listen seams via injection; deterministic with
  scripted listen.

**System-prompt nudge.** Add a one-liner to
`LearningStore.systemPromptBlock`: "When you have a binary or
small-set decision, prefer calling `clarify(...)` over reading the
options aloud."

### F2.3 — Guess-and-confirm on blank STT mid-turn  (size: **S**)

**Why.** Today after a blank STT result mid-conversation Droidal
nudges with `Filler.sayStillThere()`. But often we *do* have a
non-blank `lastPartialText` from the recogniser before it gave up.
Asking "did you mean: $partial?" is far better than "still there?".

**What.**
- Expose `Listen.lastPartialText()` (a passthrough to
  `SpeechToText.lastPartialText`) — currently it's only used
  internally for the salvage path in `onResults` / `onError`.
- New helper `brain/PartialTextConfirm.kt` — pure decision logic:
  given the partial text, decide whether to ask "did you mean…?"
  (length + character heuristic), and if so, build the question.
- Wire into `ConversationListenPolicy`: when a soft-prompt would
  fire, if a non-trivial partial exists, call the new helper instead
  of `Filler.sayStillThere`.

**Tests.** `PartialTextConfirmTest` — short/blank → no question,
medium → "did you mean…?" question shape, repeated triggers within
the same turn don't ask twice.

---

## Phase 3 — Personality and recall

### F3.1 — Voice prosody markers  (size: **L**)

**Why.** Same words said calmly vs excitedly are different
conversations. Letting the LLM emit `<calm>`, `<excited>`,
`<thoughtful>`, `<sad>`, `<conspiratorial>` markers and having
`TtsStreamer` translate them into `Speak` rate/pitch and `Look(...)`
events gives the face + voice + content a single shared mood.

**What.**
- Define a small allowed-marker set in `speech/ProsodyMarkers.kt`:
  enum values mapping to `(rateMultiplier, pitchMultiplier,
  expression)`.
- `TtsStreamer` keeps a small state machine alongside its
  `[END_CONVERSATION]` handling that recognises markers and updates
  the *current prosody* used for subsequent chunks until the next
  marker.
- `Say` event grows two optional fields `rate: Float = 1.0f`,
  `pitch: Float = 1.0f` (defaulted so existing call sites
  unaffected). `Speak.say` uses `Bundle.putFloat(KEY_PARAM_RATE, …)`
  / `setSpeechRate(…)`/`setPitch(…)` per utterance.
- `LearningStore.systemPromptBlock` gains a short "you may use
  `<calm>` / `<excited>` / `<thoughtful>` / `<sad>` /
  `<conspiratorial>` markers; they will not be spoken aloud."

**Tests.** `TtsStreamerProsodyTest` — marker-only deltas, marker-mid-
sentence (cue-the-next-chunk), unknown marker (fall through),
markers stripped from spoken text, `Look` event published when a
marker maps to an expression.

### F3.2 — Look-and-respond synchrony  (size: **S**)

**Why.** Real people glance up the moment they hear someone speak.
The camera knows where the user's face is. Turning toward them on
`onBeginningOfSpeech` is a tiny touch with a big impact.

**What.**
- `SpeechToText.onBeginningOfSpeech` already exists. Wire it (via
  Listen) to publish a `Look(x, y, Expression.NORMAL)` directed at
  the most-recent face position from `FaceContourDetectionProcessor`.
- `GlobalStatus` already tracks `lastFaceSeenAtMs`; extend to also
  publish `lastFaceX`, `lastFaceY` (normalised camera-space).
- Keep this gated by `idleAnimationsEnabled` from F1.5 since
  it's the same "alive face" feature family.

**Tests.** `LookAndRespondTest` — given a recent face position, a
begin-of-speech triggers exactly one `Look` event with the right
coordinates; with no recent face, no event is published.

### F3.3 — User interests / recent obsessions slice  (size: **M**)

**Why.** Charismatic people remember what you've been excited about
and bring it back up. The data already exists in
`LearningDatabase.conversationDao.sessions(userId, limit = N)`
with timestamps + raw turn text. We need a small extractor and a
system-prompt slot.

**What.**
- New `memory/learning/InterestExtractor.kt` — a pure ranker that,
  given the last N user turns and an optional prior MEMORY.md, picks
  the top 3–5 nouns / topics by frequency × recency (TF-IDF-lite,
  no LLM call).
- New `LearningStore.recentInterests(userId)` returns a short bullet
  list; `systemPromptBlock` adds an "RECENT INTERESTS" section
  when non-empty.
- Refresh on every conversation start (cheap — pure query).

**Tests.** `InterestExtractorTest` — given scripted turn text,
top-K extraction, weight decay, blocklist of stopwords, stable
ordering on ties.

### F3.4 — Recall-callback opener  (size: **S**)

**Why.** "How did the cake turn out?" feels human in a way that
"Hello!" doesn't. We have the prior session timestamps + summaries.

**What.**
- `LearningStore.lastSessionSummary(userId, withinHours = 24)`
  returns the most recent session's stored summary if it's fresh
  enough.
- `Agent.haveConversation` after wake-word, *before*
  `Filler.sayAcknowledged()`, optionally seeds the LLM with a "If
  natural, open with a callback to: <summary>" instruction in the
  system prompt for that single turn.
- New setting `SettingsRepository.recallCallbacksEnabled()` (default
  ON) so users who hate it can disable it.

**Tests.** `RecallCallbackTest` — pure decision: stale summary →
no callback, fresh summary + setting on → callback line present in
prompt extra, setting off → suppressed.

### F3.5 — `becomeCurious` tool + curiosity queue  (size: **M**)

**Why.** Charismatic listeners have follow-up questions. The model
should be able to mark "I want to ask X about Y next time" without
having to derail the current conversation to do it.

**What.**
- New tool in `DroidalTools`:
  `becomeCurious(topic: String, why: String)`. Persists a row in
  a small `curiosity_queue` SQLite table (new in `LearningDatabase`)
  scoped to userId, with a created-at timestamp.
- `LearningStore.systemPromptBlock` adds a "CURIOSITY QUEUE" section
  when the user has un-asked items, instructing the model to bring
  one up if there's a natural lull.
- A tool `markCuriosityAsked(id)` clears items the model brought up.
- Update `OpenAIToolSchema`, `GeminiToolSchema`, `DroidalToolDispatcher`,
  `ToolSchemaTest`.

**Tests.** `CuriosityStoreTest` — TemporaryFolder-based SQLite test
mirroring `MarkdownStoreTest`. `ToolSchemaTest` updated.

### F3.6 — Adapt-pace heuristic  (size: **S**)

**Why.** Match-the-user pace makes Droidal feel attentive.

**What.**
- New `memory/learning/PaceTracker.kt` — pure: given recent user
  turns (text + duration when available; for now derive a
  per-turn-words count), compute a fast/medium/slow bucket.
- `systemPromptBlock` adds a one-liner: "The user typically speaks
  in <slow|medium|fast> bursts; match their pace in your replies."
- Optionally also influences `TtsStreamer.streamingMode` selection
  (clause vs sentence) on a per-turn basis.

**Tests.** `PaceTrackerTest` — bucket boundaries, sliding window
respect, empty/cold-start fallback.


---

## Phase 4 — Memory modelling

These deepen Droidal's model of the user but don't change the
moment-to-moment feel.

### F4.1 — Honcho-style dialectic depth in `ReflectorWorker`  (size: **L**)

**Why.** Today reflection is single-pass: "look at the recent turns
and emit JSON edits". A dialectic depth knob (1 = today, 2 =
self-audit + synthesis, 3 = + reconciliation) gives meaningfully
better extraction quality at the cost of more inference. Local
default = 1 (cheap), cloud default = 2.

**What.**
- `ReflectorWorker.runReflection` factored into:
  `pass1_extract()` → `pass2_audit()` → `pass3_reconcile()`,
  each gated by `dialecticDepth` from `SettingsRepository`.
- Pass 1 stays as today.
- Pass 2 takes pass-1's edits + recent turns, asks the model
  "what did pass 1 miss / what's wrong here?" and emits a corrected
  edit list.
- Pass 3 (only if depth=3) reconciles contradictions across passes
  into a final list, useful when the user's latest stance
  contradicts MEMORY.md.
- Each pass has a per-pass "reasoning level" hint (cloud only;
  no-op locally for now).

**Tests.** `DialecticReflectorTest` — pure-JVM with a stub
provider that can be scripted to return deterministic JSON; assert
correct number of passes per depth, correct prompts per pass,
graceful degradation when the model emits malformed JSON in any
pass.

### F4.2 — First-turn-only injection mode  (size: **S**)

**Why.** Today every conversation rebuilds the system prompt
including the full memory blocks. For chatty users that's many
unnecessary tokens per session. Injecting on the first turn only
(and a lightweight refresh every N turns) saves tokens.

**What.**
- New setting `SettingsRepository.memoryInjectionFrequency()` enum:
  `EVERY_TURN` (today's behaviour, kept as default to stay
  conservative), `FIRST_TURN_ONLY`, `EVERY_N_TURNS(n)`.
- `Agent.haveConversation` reads a `MemoryInjectionPolicy` and
  decides whether to add the heavy memory block to a given turn or
  use a slim "system constraints only" prompt.

**Tests.** `MemoryInjectionPolicyTest` — pure decision logic.

### F4.3 — Idle-triggered reflector (in addition to scheduled)  (size: **S**)

**Why.** The current `ReflectorWorker` is timer-driven via
`PeriodicWorkRequestBuilder`. Hermes-agent's curator is
*inactivity-triggered*: run when the agent is idle and the last run
is older than threshold. That fits Droidal better — fire reflection
exactly when the GPU and CPU are free.

**What.**
- `AppForegroundTracker` already tells us when the app is in the
  background. Add an idle hook from `Agent` that, when a conversation
  ends, schedules a `OneTimeWorkRequestBuilder<ReflectorWorker>`
  with a small delay if no run has happened in N hours.
- Coexist with the existing periodic work — the periodic request
  remains the fallback so reflection still happens after long
  silent stretches.

**Tests.** `IdleReflectorPolicyTest` — pure: last-run timestamp +
foreground state + setting → boolean "should enqueue?".

### F4.4 — Context compression on overflow / near-cap  (size: **L**)

**Why.** A `LiteRtLmJniException` from prefill overflow currently
poisons the cached engine and ends the conversation. With an
auxiliary compression pass we can keep going indefinitely on the
on-device model.

**What.**
- New `brain/ContextCompressor.kt` — when the running session
  approaches `localMaxNumTokens` (heuristic: total user+assistant
  chars > 0.7 × maxTokens × 4), spin up an auxiliary `ChatSession`
  on the same provider, summarise the older half of the conversation
  into a short paragraph, and start a fresh session with that
  paragraph injected as a "PRIOR CONVERSATION SUMMARY" block.
- Hook the recovery path in `Agent.haveConversation`'s catch block:
  on `LiteRtLmJniException`, attempt one compression-and-retry
  before falling through to the friendly error.

**Tests.** `ContextCompressorTest` — stub provider, assert
compression call shape, summary placement, recovery sequence.
`UserJourneyIntegrationTest` extension — long conversation
crosses the cap, gets compressed, continues.

---

## Phase 5 — Observability + maintenance

### F5.1 — Conversation Insights page  (size: **L**)

**Why.** Easy "how is Droidal doing this week" view. Also helps the
user notice when their daily routines are working.

**What.**
- New `settings/InsightsActivity.kt` (or sub-page in
  `SettingsActivity` like Memories / Learning) reading from
  `LearningDatabase.conversationDao` + `SkillStore`. Shows:
    - Total turns this week / month, broken down per user.
    - Average reply latency per provider (LiteRT-LM / Gemini /
      OpenRouter).
    - Top 5 topics this week (reuse F3.3's `InterestExtractor`).
    - Number of skills learned, news items surfaced.
- Read-only — no buttons that mutate state. Drill-down to the
  per-user Memories / Learning pages already exists.

**Tests.** `InsightsAggregatorTest` — pure-JVM aggregator over
in-memory turn lists. UI-level smoke test deferred to manual QA.

### F5.2 — Skill archive (don't delete)  (size: **M**)

**Why.** Skills are user-relevant content; an accidental delete
should be recoverable.

**What.**
- `SkillStore` grows an `archived` flag column (SQLite migration).
- `delete()` becomes `archive()` by default; an explicit
  `permanentlyDelete()` is reserved for the danger-zone wipe-user
  path.
- `LearningActivity` and `MemoriesPage` add "Show archived" toggles
  and a "Restore" button per archived skill.

**Tests.** `SkillStoreTest` — round-trip archive → list visibility,
restore round-trip, migration upgrade.

---

## Phase 6 — Optional / experimental

These are gated by per-feature settings, default-OFF, and only land
if Phases 1–5 are working well. They're documented here so the user
can decide which to keep.

### F6.1 — Backchannels  (size: **L**, feel-risky)

Short "mm", "right", "yeah" interjections during the user's
mid-utterance pauses. Hard to get right — easy to feel like
interruption.

Approach: only fire when STT `lastPartialText` indicates an
incomplete sentence (no terminal punctuation) AND voice has been
quiet for 800–1500 ms AND face is visible. Setting:
`backchannelsEnabled` default OFF.

### F6.2 — Mixture-of-agents tool (cloud-only)  (size: **M**)

LLM-callable tool that asks two cloud providers in parallel and
returns the better answer. Useful only when the user has both
Gemini AND OpenRouter configured. Setting: `mixtureOfAgentsEnabled`
default OFF.

### F6.3 — Cron / natural-language routines  (size: **L**)

A `scheduleRoutine(naturalLanguageSchedule, prompt, recipient)`
tool that registers a periodic `WorkManager` job which fires a
proactive `StartConversation`. Inspired by hermes-agent's cron
scheduler.

### F6.4 — Prosody-aware end-of-turn  (size: **L**, audio-risky)

Heuristic on RMS / partial-text inflection (rising vs falling) to
distinguish a question end from a statement end. Adjusts STT silence
timeouts accordingly.

### F6.5 — Joint storytelling / yes-and mode  (size: **M**)

When the model detects the user is telling an anecdote (verb tense
heuristic), bias the next reply to backchannel-only and short
follow-up questions. Tool `enterYesAndMode(reason)` to flip an
`AnecdoteFlag` consulted by the system prompt.


---

## Recommended order of attack

I will land features in roughly this order, each as its own
build-green commit so the user can review / revert per item:

1. **Phase 1** in full — F1.1, F1.2, F1.3, F1.4, F1.5. Each is
   small, isolated, individually testable, and ships value the
   moment it's merged.
2. **Phase 2 F2.3 (guess-and-confirm)** — a small but very visible
   UX win that reuses existing partial-text plumbing.
3. **Phase 2 F2.2 (clarify tool)** — needs the InterruptFlag from
   F1.1 to safely cancel a clarify mid-listen, and the prosody
   pipeline isn't required.
4. **Phase 2 F2.1 (barge-in)** — the most architecturally risky
   single item, so it goes after the clarify tool has been
   exercising the interrupt flag.
5. **Phase 3** in order F3.2 (look glance) → F3.3 (interests)
   → F3.4 (recall callbacks) → F3.5 (curiosity) → F3.1 (prosody)
   → F3.6 (pace).
6. **Phase 4** in order F4.2 → F4.3 → F4.1 → F4.4 (each builds
   incrementally on the reflector / system-prompt plumbing).
7. **Phase 5** F5.2 (skill archive) → F5.1 (insights page).
8. **Phase 6** items only after Phases 1–5 are landed and the user
   has lived with them for a bit.

---

## Test gates per phase

Per `.cursor/rules/testing.mdc`, each phase ships with:

- New JVM unit tests in `Droidal/app/src/test/java/...`. The
  bulleted test names above are the minimum bar.
- `:app:assembleDebug` green (which runs `testDebugUnitTest` +
  `detekt` + the build). No new entries in
  `Droidal/app/config/detekt/baseline.xml`.
- Where a tool is added, `ToolSchemaTest.expectedToolNames` is
  updated.
- Where a system-prompt block changes, an existing or new
  `LearningStoreSystemPromptTest` covers the wording.

---

## Open questions for the user

1. **Phase 6 F6.1 backchannels** are notoriously hard to get right
   — they sound great when they work and excruciating when they
   don't. OK to keep them default-OFF and behind a clearly-labelled
   experimental setting?
2. **Phase 6 F6.2 mixture-of-agents** doubles cloud cost. Confirm
   it should land but be disabled by default.
3. **F4.1 dialectic depth defaults**: local default = 1 (cheap) vs
   cloud default = 2 (better quality). OK to ship with these, with
   a settings override?
4. **F5.1 insights page** — is a third sub-page in Settings the
   right home, or would a dedicated entry in `MainActivity`'s cog
   menu be better?
5. **F1.5 idle micro-behaviour** wording: "blink", "look around",
   "head tilt" — happy to tune the inventory after the first
   user-test.

I'll pause for direction before tackling Phase 6 in particular.
