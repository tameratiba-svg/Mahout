# DESIGN6 — Mahout v6: design system, fluid UI, token streaming (2026-09-26)

User ask (verbatim intent): *"For the Chat UI make it more interactive and fluid. I want the app to have a nice modern clean look with a fluidic UI with nice animations."*

Synthesis note: three candidate proposals were judged 115 / 114 / **127**. This contract was re-derived from the code and the jars on disk
(every API named below was checked with `javap` against the cached artifacts: anthropic-java-core 2.65.0, compose material3 1.3.1,
animation 1.7.5, ui-text 1.7.5, activity-compose 1.9.3, genai-prompt 1.0.0-beta2) and it supersedes the proposals: where it differs, this
document wins. Code on disk wins over this document once merged; record deviations in §12.

Read with: `README.md`, `DESIGN4.md` §5 (chat harness), §6 (dashboard), §10 (navigation), `DESIGN4P.md` (modes, per-call approval, Bypass),
`DESIGN5.md` (Panels, decision engine). A v5.1 fix pass is editing `ai/SystemOne.kt`, `ai/Triage.kt`, `triggers/TriageParams.kt`,
`engine/TriggerHub.kt`, `ui/Panels.kt`; implementation of v6 starts after it lands, and nothing in v6 touches those files except the
`ui/Panels.kt` restyle (§6.9), which rebases on the landed v5.1 version.

Stack is frozen: Kotlin 2.1.20, Compose BOM 2024.10.01 (compose 1.7.5, material3 1.3.1), activity-compose 1.9.3, lifecycle 2.8.7,
compileSdk/targetSdk 34, minSdk 26. **No new Gradle dependency.** No navigation-compose. No ViewModels.

---

## 1. Decisions

| # | Decision | Why (verified) |
|---|---|---|
| D1 | **Own brand scheme, not stock Material.** Light "paper" + dark "night navy" schemes derived from the logo (navy `#0F2A3F→#123C4A`, gold ink `#F6E7C8→#E9C46A`, teal `#7FD1C5`). Every text pair ≥ 4.5:1, every non-text signal ≥ 3:1, asserted by a pure JVM test over the same tables the theme is built from (§2, §9). | Today `Mob8NTheme` uses dynamic colour on API 31+ and stock `darkColorScheme()` below: the app looks like every other app and contrast is unowned. |
| D2 | **"Use wallpaper colours" toggle, default OFF.** ON (API 31+ only) swaps the 30 M3 roles for `dynamic*ColorScheme`; Mahout's extended colours (risk, success/warning, bubbles, code, signal dots, Bypass) **never** follow the wallpaper. | Safety colours must mean the same thing on every phone. |
| D3 | **One bundled OFL family: Manrope (variable, wght 200–800) + JetBrains Mono (variable) for code.** Files in `res/font`, licences in `assets/licenses/`. | Manrope: modern geometric-grotesque with open apertures, a large x-height (legible at 12 sp in chips and tool cards), distinctive but quiet — "calm and technical", not the default Inter/Roboto look. One variable file per family = 2 files, ~0.45 MB total. JetBrains Mono: unambiguous `0O 1lI`, tall x-height pairs with Manrope. Both SIL OFL 1.1. |
| D4 | **Motion is a token system with one switch.** `Motion.kt` exposes specs through `LocalMotion`; `reduced = inAppToggle || Settings.Global.ANIMATOR_DURATION_SCALE == 0`. Every Mahout-authored animation degrades to instant (snap) and every loop (caret, shimmer, pulse, flowing dots) to a static frame. | One place to audit. Material components' internal motion (sheet drag, switch thumb, ripple) already follows the system scale through Compose's `MotionDurationScale` (verified: `WindowRecomposer_androidKt.getAnimationScaleFlowFor` in ui 1.7.5); the in-app toggle cannot reach those and the Settings text says so. |
| D5 | **Token streaming end to end, persistence contract unchanged.** `Llm.step(…, onDelta)`; OpenAI-compatible SSE (`stream:true`), Claude `beta().messages().createStreaming` + `BetaMessageAccumulator`; the final `Turn` is produced by the SAME parsers as today (`OpenAiCompat.parseResponse`, `ClaudeClient.parseMessage`), so transcripts, `_oai` echo, usage rows and tool ids are byte-identical to the non-streaming path. | Streaming changes what the user sees during a call, never what is stored. Golden-equivalence tests pin it (§9). |
| D6 | **`ChatRunner.live(conversationId): StateFlow<LiveTurn?>`** = the unpersisted part of the in-flight turn (text so far, thinking, tool calls forming/queued/running/done with results), published ≤ every 40 ms, set to `null` right after each Room flush. | Today rows are flushed only at Done / NeedApproval (`drive`), so an Auto-mode turn with 5 tool steps shows nothing until the end. `LiveTurn` covers the whole turn, not one model call. |
| D7 | **Streaming fallback is automatic and cached like the 400 downgrade.** Failure before the first delta → quirk `no_stream` cached per provider id for the process, redo non-streaming at once. Failure after deltas → `StreamDelta.Reset`, redo non-streaming for this call only (transient, not cached). HTTP/auth/rate/API errors are real errors and are mapped exactly as today (no fallback — a retry would fail identically). | Nothing executes until a `Turn` is complete, so a non-streaming redo has no side effects. |
| D8 | **Nano: no streaming work.** `generateContentStream(GenerateContentRequest): Flow<GenerateContentResponse>` exists in beta2 (verified), but chat rejects Nano (`ChatRunner.checkTarget` → `ERR_NANO`) and Build-with-AI rejects it too, so there is no consumer. `// ponytail: Nano streaming has no UI consumer; upgrade = NanoClient.stream when ai.ask gets a live preview`. | YAGNI. |
| D9 | **Follow-up suggestion chips: no LLM-generated ones** (an extra model call per answer). Chips are local and free: the existing "Save as skill?" (≥ 3 tool calls), "Open draft" (a draft exists), and static empty-state suggestions. | Stated cost rule. |
| D10 | **Assistant messages have no bubble**; they sit on the surface under a small brand mark + "Mahout · model" line. User messages are navy/gold-ink bubbles (the logo's own pairing). Tool calls are quiet cards; approvals are a docked action sheet. | Modern, calm reading column; the eye goes to the user's words and to decisions. |
| D11 | **Per-call approval becomes an *Approval dock* pinned above the composer** (replaces the composer while any call is undecided; max 60 % of the height, scrolls inside). Same data path, same per-call semantics, same `contentDescription`s and live region as DESIGN4P §3. | It can no longer scroll away — more prominent than inline cards, never less. |
| D12 | **Retry / edit never rewrite history.** Retry = send the last user text again; Edit & resend = put the last user text in the composer; "Hide" = session-only view filter with Undo. `// ponytail: append-only transcript; upgrade = branch/fork rows`. | Transcript/persistence contract is untouchable. |
| D13 | **Navigation motion without navigation-compose**: `AnimatedContent(targetState = screen)` with a pure `navKind(from, to)` (fade-through for top-level switches, push/pop slides for depth) + `rememberSaveableStateHolder()` so Back restores list scroll. | Smallest change to `App.kt`; `Screen` stays the single source of truth. |
| D14 | **Predictive back = progress-driven transform of the current screen** (`PredictiveBackHandler`, scale → 0.92, 16 dp shift toward the swipe edge, corners → 28 dp), commit runs the normal pop transition. No parent peek. `// ponytail: no parent peek; upgrade = SeekableTransitionState (present in animation-core 1.7.5) + rememberTransition`. | `enableOnBackInvokedCallback="true"` is already in the manifest. |
| D15 | **No shared-element transitions.** `SharedTransitionLayout` is `@ExperimentalSharedTransitionApi` in 1.7 and, on tablets, list and editor are visible side by side (a card→title morph is meaningless). Phones get a container-like push (scale 0.96 + fade + 10 % slide). | Stability + tablet reality. |
| D16 | **Tool-result/approval colours by risk**, one table: READ = neutral, WRITE = info blue, CODING = amber, ALWAYS = red; the words (`Permissions.chip`) always carry the meaning, colour only underlines it. | DESIGN4P rule "text, not colour". |
| D17 | **`profile` build type** (release-like, `debuggable=false`, minify off, debug keystore) for frame measurement and daily use; installs over debug with `install -r`, data kept. | Debug Compose is interpreted/unoptimised; frame budgets are judged on `profile` only. |
| D18 | **Image attach = photo picker, copied to `cacheDir/chat-images/`, sent to the model on the turn it was attached only.** The persisted row stores text + `meta.image` (path); no base64 in Room. `// ponytail: cacheDir is evictable and the image is not re-sent on later turns; upgrade = filesDir + prune on delete + re-attach last image`. | Keeps rows small and the 80 k-char window honest. |
| D19 | **Voice = `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` (system UI), no `RECORD_AUDIO`.** The result is appended to the draft, never auto-sent. | No permission; the user reviews before sending. |
| D20 | **No theme override (system light/dark only).** `// ponytail: follows system; upgrade = UiPrefs.theme`. | Scope. |

### 1.1 Never simplify away
Reduced motion (both switches, every loop static); TalkBack labels, 48 dp targets, 200 % font scale without clipping, contrast asserted by test;
per-call approval + Bypass banner/countdown/Stop everything (prominent in every layout, strings verbatim from DESIGN4P); streaming fallback
(both cases, D7); transcript/persistence contract (D5, D6, D12); frame-time measurement on the `profile` build (§7).

### 1.2 Ponytail marks (put the comment in code)
D8, D12, D14, D18, D20; `Throttle` is leading-edge only (the final publish closes the trail); live tool-state matching by name order within a
step (AgentNode runs a batch sequentially); canvas "running node" is inferred from `node_logs` (a node is logged after it finishes, §6.8);
`Hide` is per-session; font weights via `FontVariation` (`@OptIn(ExperimentalTextApi::class)`, §2.5); no ContentObserver for the animator
scale (re-read on every resume).

### 1.3 Verified facts this design relies on
- anthropic-java-core 2.65.0: `client.beta().messages().createStreaming(MessageCreateParams): StreamResponse<BetaRawMessageStreamEvent>`;
  `StreamResponse : AutoCloseable { stream(): java.util.stream.Stream<T>; close() }`; `BetaMessageAccumulator.create()`, `.accumulate(event)`,
  `.message(): BetaMessage`; `BetaRawMessageStreamEvent.isContentBlockStart/Delta/…()`, `asContentBlockDelta().delta()` →
  `isText/asText().text()`, `isThinking/asThinking().thinking()`, `isInputJson/asInputJson().partialJson()`;
  `asContentBlockStart().contentBlock().isToolUse/asToolUse().id()/.name()`, `.index()`; `asMessageDelta().delta().stopReason()`;
  `com.anthropic.core.ObjectMappers.jsonMapper()` (public — tests deserialize event fixtures with it).
- material3 1.3.1: `androidx.compose.material3.pulltorefresh.PullToRefreshBox(isRefreshing, onRefresh, modifier, state, contentAlignment, indicator, content)`,
  `SwipeToDismissBox`, `ModalNavigationDrawer`, `ModalBottomSheet`.
- animation 1.7.5: `SharedTransitionScope` (experimental — not used), `animateItem` (foundation 1.7), `SeekableTransitionState` (present, not used).
- activity-compose 1.9.3: `PredictiveBackHandler`; `ActivityResultContracts.PickVisualMedia` (activity 1.9).
- ui-text 1.7.5: `Font(resId, weight, style, loadingStrategy, variationSettings)` is `@ExperimentalTextApi`.
- genai-prompt 1.0.0-beta2: `GenerativeModel.generateContentStream(...)` exists (D8).
- App: `MainActivity` already calls `enableEdgeToEdge()`; manifest already has `android:enableOnBackInvokedCallback="true"`;
  Room's destructive fallback keys off `FLAG_DEBUGGABLE` (so `profile` behaves like release: migrations only); compose ui pulls
  `androidx.profileinstaller` transitively.

---

## 2. Design tokens (owner: ui-foundation, `ui/Theme.kt`)

### 2.1 Colour roles (M3 `ColorScheme`)
Numbers are WCAG 2.x contrast ratios (`contrastRatio(fg, bg)`, §2.4). Text minimum 4.5, non-text 3.0.

| Role | Light | Dark | Text pair (light / dark) |
|---|---|---|---|
| `primary` | `#0B6B61` | `#7FD1C5` | `onPrimary` on it: **6.38** / **7.83**; as text on `surface`: 5.81 / 10.21 |
| `onPrimary` | `#FFFFFF` | `#00332D` |  |
| `primaryContainer` | `#C4EEE6` | `#0F4F48` | `onPrimaryContainer` on it: **13.67** / **7.29** |
| `onPrimaryContainer` | `#00201C` | `#A9F0E4` |  |
| `secondary` | `#7A5900` | `#E9C46A` | `onSecondary` on it: **6.45** / **8.28**; as text on `surface`: 5.87 / 10.85 |
| `onSecondary` | `#FFFFFF` | `#3B2A00` |  |
| `secondaryContainer` | `#F6E7C8` | `#4D3B10` | `onSecondaryContainer` on it: **13.23** / **8.82** |
| `onSecondaryContainer` | `#2B1F00` | `#F6E7C8` |  |
| `tertiary` | `#1F4E66` | `#A9CBE0` | `onTertiary` on it: **8.97** / **7.82** |
| `onTertiary` | `#FFFFFF` | `#0B3345` |  |
| `tertiaryContainer` | `#D2E6F2` | `#1F4A60` | `onTertiaryContainer` on it: **12.45** / **7.41** |
| `onTertiaryContainer` | `#0A2433` | `#D2E6F2` |  |
| `error` | `#B3261E` | `#FFB4AB` | `onError` on it: **6.54** / **7.72**; as text on `surface`: 5.95 / 10.68 |
| `onError` | `#FFFFFF` | `#690005` |  |
| `errorContainer` | `#FADAD6` | `#8C1D18` | `onErrorContainer` on it: **12.43** / **7.05** |
| `onErrorContainer` | `#410E0B` | `#FFDAD6` |  |
| `background` | `#F7F4EC` | `#0A1722` | `onBackground` on it: **13.42** / **15.15** |
| `onBackground` | `#0F2A3F` | `#E4ECF0` |  |
| `surface` | `#F7F4EC` | `#0A1722` | `onSurface` on it: **13.42** / **15.15**; `onSurfaceVariant`: 7.19 / 8.72 |
| `onSurface` | `#0F2A3F` | `#E4ECF0` |  |
| `surfaceVariant` | `#E3E1D8` | `#1C3346` | `onSurfaceVariant` on it: **6.04** / **6.27** |
| `onSurfaceVariant` | `#46535D` | `#A7B6C1` |  |
| `surfaceContainerLowest` | `#FFFFFF` | `#06111A` | `onSurface` on it: **14.75** / **15.92**; `onSurfaceVariant`: 7.91 / 9.16 |
| `surfaceContainerLow` | `#F2EFE6` | `#0E1E2B` | `onSurface` on it: **12.83** / **14.17**; `onSurfaceVariant`: 6.88 / 8.15 |
| `surfaceContainer` | `#ECE9DF` | `#122433` | `onSurface` on it: **12.15** / **13.24**; `onSurfaceVariant`: 6.51 / 7.62 |
| `surfaceContainerHigh` | `#E6E3D9` | `#172B3C` | `onSurface` on it: **11.49** / **12.13**; `onSurfaceVariant`: 6.16 / 6.98 |
| `surfaceContainerHighest` | `#E0DDD3` | `#1C3346` | `onSurface` on it: **10.86** / **10.89**; `onSurfaceVariant`: 5.82 / 6.27 |
| `outline` | `#6E7B85` | `#71879A` | non-text vs `surface` 3.95 / 4.86; vs `surfaceContainerHigh` 3.38 / 3.89 (min 3.0) |
| `outlineVariant` | `#C8C9C0` | `#2A4356` | decorative hairlines only (never the only boundary of a control) |
| `inverseSurface` | `#132A3A` | `#E4ECF0` | `inverseOnSurface` on it: **13.01** / **13.41** |
| `inverseOnSurface` | `#EDF1F3` | `#13232F` |  |
| `inversePrimary` | `#7FD1C5` | `#0B6B61` |  |
| `scrim` | `#000000` | `#000000` | |
| `surfaceTint` | = `primary` | = `primary` | tonal elevation is not used (containers are explicit), so tint never shows |
| `surfaceBright` / `surfaceDim` | `#FBF9F3` / `#DAD7CD` | `#22394C` / `#0A1722` | M3 1.3 roles; only used by M3 internals |

Role usage: `primary` = teal actions, links, focus, caret, running indicators. `secondary` (gold) = the "your decision" accent (approval dock
header, awaiting badges). `tertiary` (deep sea blue) = informational chips and the Chat destination badge. Error roles = failure and Bypass.

### 2.2 Mahout extended colours (`MahoutColors`, never dynamic)

| Token (bg) | Light | Dark | On-token | Light | Dark | Contrast L / D |
|---|---|---|---|---|---|---|
| `success` | `#1E7A4F` | `#7FD8A4` | `onSuccess` | `#FFFFFF` | `#00391F` | **5.31** / **7.64** |
| `successContainer` | `#CDEFD9` | `#12432C` | `onSuccessContainer` | `#04311C` | `#CDEFD9` | **11.60** / **9.09** |
| `warningContainer` | `#FBE3B3` | `#4D3700` | `onWarningContainer` | `#2E1C00` | `#FBE3B3` | **13.06** / **8.99** |
| `infoContainer` | `#D9E4F7` | `#1B355C` | `onInfoContainer` | `#0B2350` | `#D9E4F7` | **11.96** / **9.58** |
| `userBubble` | `#0F2A3F` | `#1F4A55` | `onUserBubble` | `#F6E7C8` | `#F6E7C8` | **12.07** / **7.92** |
| `assistantBubble` | `#FFFFFF` | `#122433` | `onAssistantBubble` | `#0F2A3F` | `#E4ECF0` | **14.75** / **13.24** |
| `card` (L1) | `#FFFFFF` | `#122433` | `onSurface` | `#0F2A3F` | `#E4ECF0` | **14.75** / **13.24** |
| `codeBg` | `#132A3A` | `#06111A` | `onCode` | `#E4ECF0` | `#D5E1E8` | **12.36** / **14.29** |
| `riskRead` | `#E3E1D8` | `#1C3346` | `onRiskRead` | `#2E3B45` | `#C9D6DE` | **8.77** / **8.79** |
| `riskWrite` | `#D9E4F7` | `#1B355C` | `onRiskWrite` | `#0B2350` | `#D9E4F7` | **11.96** / **9.58** |
| `riskCoding` | `#FBE3B3` | `#4D3700` | `onRiskCoding` | `#4A3000` | `#FBE3B3` | **9.77** / **8.99** |
| `riskAlways` | `#FADAD6` | `#5C1A16` | `onRiskAlways` | `#5F1410` | `#FFDAD6` | **10.08** / **10.07** |
| `warning` (text on `surface`) | `#8A5A00` | `#F2C063` | — | | | **5.39** / **10.79** |
| `info` (text on `surface`) | `#2F5FA8` | `#A8C4F2` | — | | | **5.75** / **10.22** |
| `success` (text on `surface`) | `#1E7A4F` | `#7FD8A4` | — | | | **4.83** / **10.58** |
| `caret` (non-text) | `#0B6B61` | `#7FD1C5` | on `assistantBubble` | | | 6.38 / 8.92 (min 3) |

Brand constants (both schemes): `brandNavy #0F2A3F`, `brandNavy2 #123C4A`, `brandGoldLight #F6E7C8`, `brandGold #E9C46A`, `brandTeal #7FD1C5`
(logo, onboarding, `BrandMark`; never as text colours on arbitrary surfaces).

### 2.3 Signal colours (fixed, non-text: dots, stripes, canvas node borders, sparkline strokes)
Tuned to relative luminance ≈ 0.19 so one value passes **≥ 3:1 on light `surface` (≈ 3.97), light `surfaceContainerHigh` (≈ 3.40), dark
`surface` (≈ 4.15) and dark `surfaceContainerHigh` (≈ 3.32)**. Rule: signal colours are never drawn on `surfaceContainerHighest` (2.98) and
never used for text. The existing public functions keep their names and signatures and return these values:

| Function → value | |
|---|---|
| `statusColor(RunStatus?)` | RUNNING `#3B7AC8` · SUCCESS `#298957` · FAILED `#D14B44` · SUSPENDED `#A26F0F` · CANCELLED/null `#6D7B85` |
| `nodeStatusColor(NodeStatus?)` | SUCCESS `#298957` · FAILED/TIMEOUT `#D14B44` · ERROR_ROUTED `#B86225` · SUSPENDED `#A26F0F` · SKIPPED/null `#6D7B85` |
| `kindColor(NodeKind)` | TRIGGER `#B86225` · DATA `#3B7AC8` · LOGIC `#8666CF` · ACTION `#298957` · AI `#C4507F` |

`WorkflowList`'s hard-coded `Color(0xFFFFB300)` ("needs host") becomes `nodeStatusColor(NodeStatus.SUSPENDED)`; Canvas `RED` becomes
`nodeStatusColor(NodeStatus.FAILED)` (ui-screens).

### 2.4 Kotlin shape of the tables (the test reads exactly these)
```kotlin
// ui/Theme.kt
internal val LIGHT_ROLES: Map<String, Long>   // M3 role name (as in §2.1) -> 0xAARRGGBB
internal val DARK_ROLES: Map<String, Long>
internal val LIGHT_EXT: Map<String, Long>     // §2.2 token names
internal val DARK_EXT: Map<String, Long>
internal val SIGNAL: Map<String, Long>        // "running","success","failed","suspended","cancelled","errorRouted","trigger","data","logic","action","ai"
data class ContrastPair(val label: String, val fg: Long, val bg: Long, val min: Double)
internal val CONTRAST_PAIRS: List<ContrastPair>   // every row of §2.1–2.3 (light and dark), built FROM the maps above
fun contrastRatio(fg: Long, bg: Long): Double    // WCAG: sRGB channel c/255 -> c<=0.04045 ? c/12.92 : ((c+0.055)/1.055)^2.4; L=0.2126R+0.7152G+0.0722B; (Lmax+0.05)/(Lmin+0.05)
```
The schemes are built from the maps (`lightColorScheme(primary = Color(LIGHT_ROLES.getValue("primary")), …)`), so the test and the
UI can never disagree.

### 2.5 Typography
Files (ui-foundation adds them; sources verified reachable 2026-09-26):
- `app/src/main/res/font/manrope_variable.ttf` ← `https://github.com/google/fonts/raw/main/ofl/manrope/Manrope%5Bwght%5D.ttf`
- `app/src/main/res/font/jetbrains_mono_variable.ttf` ← `https://github.com/google/fonts/raw/main/ofl/jetbrainsmono/JetBrainsMono%5Bwght%5D.ttf`
- `app/src/main/assets/licenses/Manrope-OFL.txt` ← `…/ofl/manrope/OFL.txt`; `app/src/main/assets/licenses/JetBrainsMono-OFL.txt` ← `…/ofl/jetbrainsmono/OFL.txt`

```kotlin
@OptIn(ExperimentalTextApi::class)
val BrandFamily = FontFamily(listOf(400, 500, 600, 700).map { w ->
    Font(R.font.manrope_variable, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w))) })
@OptIn(ExperimentalTextApi::class)
val MonoFamily = FontFamily(listOf(400, 600).map { w ->
    Font(R.font.jetbrains_mono_variable, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w))) })
```
Fallback if weights render wrong on a device: static TTFs from the upstream repos (`manrope/fonts/ttf`, `JetBrainsMono/fonts/ttf`), one file
per weight, same `FontFamily` names (record in §12).

| Style | Family / weight | Size / line (sp) | Tracking | Use |
|---|---|---|---|---|
| `displaySmall` | Manrope 600 | 36 / 44 | −0.5 | onboarding title |
| `headlineMedium` | Manrope 700 | 28 / 36 | −0.25 | dashboard counters (`NumericStyle` = this + `fontFeatureSettings = "tnum"`) |
| `headlineSmall` | Manrope 700 | 24 / 32 | 0 | empty states |
| `titleLarge` | Manrope 700 | 22 / 28 | 0 | top bar titles |
| `titleMedium` | Manrope 600 | 16 / 24 | 0.1 | card titles, list primary |
| `titleSmall` | Manrope 600 | 14 / 20 | 0.1 | section heads |
| `bodyLarge` | Manrope 400 | 16 / 24 | 0.15 | **chat message text** |
| `bodyMedium` | Manrope 400 | 14 / 20 | 0.15 | default body |
| `bodySmall` | Manrope 500 | 12 / 16 | 0.25 | helper text (500: Manrope 400 is thin at 12) |
| `labelLarge` | Manrope 600 | 14 / 20 | 0.1 | buttons |
| `labelMedium` | Manrope 600 | 12 / 16 | 0.4 | chips, pills |
| `labelSmall` | Manrope 600 | 11 / 16 | 0.5 | meta lines, timestamps |
| `CodeStyle` | JetBrains Mono 400 | 13 / 20 | 0 | code blocks, tool input/output |
| `CodeSmallStyle` | JetBrains Mono 400 | 12 / 18 | 0 | inline code, tool names in chips |

Everything in `sp`; no fixed heights around text (use `heightIn(min = …)`), so 200 % font scale grows containers instead of clipping.

### 2.6 Shapes
`Radius`: xs 6 dp (inline code, tiny tags) · s 10 dp (chips, pills, small buttons) · m 16 dp (cards, tool cards, code blocks) · l 24 dp
(sheets, approval dock, composer field) · xl 32 dp (hero / onboarding) · full = `CircleShape` (send/stop, avatars, dots).
`MahoutShapes = Shapes(extraSmall = 6, small = 10, medium = 16, large = 24, extraLarge = 32 dp)`.
Bubbles: `BubbleUserShape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)` (topStart, topEnd, bottomEnd, bottomStart; the 6 dp corner
points at the sender, mirrored automatically in RTL); assistant has no bubble (D10).

### 2.7 Spacing
`Space`: xxs 2 · xs 4 · s 8 · m 12 · l 16 · xl 24 · xxl 32 · xxxl 48 dp. Page gutter: 16 dp (< 600 dp wide), 24 dp (≥ 600 dp).
Reading column: chat content `widthIn(max = 760.dp)` centred on tablets. List item spacing 8 dp; card inner padding 16 dp; section gap 24 dp.

### 2.8 Elevation (tonal layers, no tonal tint)
L0 `surface` (page) · L1 `mahout.card` (cards on the page: light `#FFFFFF` + 1 dp `outlineVariant` hairline at 60 % alpha, dark `#122433` without hairline — a white card on warm paper reads crisper than a tonal step, and a dark card must sit *above* the night background) · list pane `surfaceContainerLow` · L2 `surfaceContainer` (top bar when scrolled, composer bar,
tool cards) · L3 `surfaceContainerHigh` (approval dock, menus, bottom sheets, snackbars use `inverseSurface`) · L4
`surfaceContainerHighest` (chips inside L3). Shadows only on the FAB and the approval dock (3 dp); in dark mode separation comes from the
tonal step + a 1 dp `outlineVariant` top hairline on the dock and composer.

### 2.9 Iconography
material-icons-extended only. Rounded set (`Icons.Rounded.*`) everywhere for a softer line; `AutoMirrored` variants for directional icons.
24 dp in bars and buttons, 20 dp in chips and cards, 16 dp inline. Every icon-only control has a `contentDescription`; decorative icons
next to text have `null`. Status glyphs (§4 `StatusGlyph`) always come with text or a `contentDescription`.

---

## 3. Motion (owner: ui-foundation, `ui/Motion.kt`)

### 3.1 Tokens
| Token | Value | Use |
|---|---|---|
| `SHORT1` | 90 ms | exits, press release |
| `SHORT2` | 150 ms | fades, colour changes |
| `MEDIUM1` | 220 ms | expand/collapse, list item enter |
| `MEDIUM2` | 300 ms | approval dock, drawer content, sheets |
| `LONG1` | 380 ms | screen push/pop |
| `LONG2` | 600 ms | sparkline draw-in, counters |
| `CARET_MS` | 1000 ms period (500 on / 500 off, 120 ms fade) | streaming caret |
| `SHIMMER_MS` | 1300 ms | skeleton sweep |
| `PULSE_MS` | 1400 ms | running node / live dot |
| `FLOW_MS` | 1200 ms per edge traversal | flowing dots on canvas edges |
| `STAGGER_MS` / `STAGGER_MAX` | 35 ms / 6 items | first-load stagger (item ≥ 6 enters at 210 ms) |
| `PRESS_SCALE` | 0.97 | cards, pills |
| `STREAM_FRAME_MS` | 40 ms | live-turn publish period (ai lane uses the same constant, §5.1) |

Easing: `Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)`; `Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)` (enter);
`Accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)` (exit); `LinearEasing` for loops.
Springs: `spatial` = `spring(dampingRatio = 0.85f, stiffness = 420f)`; `spatialFast` = `spring(0.9f, 900f)` (press, chip morph);
`emphasis` = `spring(0.72f, 380f)` (≈ 3 % overshoot: dock entry, FAB appear, send↔stop morph).
CSS equivalents (preview only): standard `cubic-bezier(.2,0,0,1)`, decelerate `cubic-bezier(.05,.7,.1,1)`, accelerate
`cubic-bezier(.3,0,.8,.15)`, emphasis spring ≈ `cubic-bezier(.3,1.25,.5,1)` over 300 ms.

### 3.2 `ui/Motion.kt` API (exact; this code is also the stub other lanes compile against)
```kotlin
object MotionTokens {
    const val SHORT1 = 90; const val SHORT2 = 150; const val MEDIUM1 = 220; const val MEDIUM2 = 300; const val LONG1 = 380; const val LONG2 = 600
    const val CARET_MS = 1000; const val SHIMMER_MS = 1300; const val PULSE_MS = 1400; const val FLOW_MS = 1200
    const val STAGGER_MS = 35; const val STAGGER_MAX = 6; const val PRESS_SCALE = 0.97f; const val STREAM_FRAME_MS = 40L
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val Accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}

/** Pure (MotionTest): the one switch. */
fun motionReduced(systemAnimatorScale: Float, inAppReduce: Boolean): Boolean = inAppReduce || systemAnimatorScale == 0f

@Immutable
class MotionScheme(val reduced: Boolean) {
    fun <T> spatial(): FiniteAnimationSpec<T> = if (reduced) snap() else spring(0.85f, 420f)
    fun <T> spatialFast(): FiniteAnimationSpec<T> = if (reduced) snap() else spring(0.9f, 900f)
    fun <T> emphasis(): FiniteAnimationSpec<T> = if (reduced) snap() else spring(0.72f, 380f)
    fun <T> effect(durationMs: Int = MotionTokens.SHORT2, delayMs: Int = 0): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(durationMs, delayMs, MotionTokens.Standard)
    fun <T> enterEffect(durationMs: Int = MotionTokens.MEDIUM1, delayMs: Int = 0): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(durationMs, delayMs, MotionTokens.Decelerate)
    fun <T> exitEffect(durationMs: Int = MotionTokens.SHORT1): FiniteAnimationSpec<T> =
        if (reduced) snap() else tween(durationMs, 0, MotionTokens.Accelerate)
    /** Item/element enter: fade + 12 dp rise. */
    fun enter(delayMs: Int = 0): EnterTransition
    fun exit(): ExitTransition
    fun expand(): EnterTransition        // expandVertically(spatial) + fadeIn(enterEffect)
    fun collapse(): ExitTransition       // shrinkVertically(spatial) + fadeOut(exitEffect)
    fun staggerMs(index: Int): Int = if (reduced) 0 else minOf(index, MotionTokens.STAGGER_MAX) * MotionTokens.STAGGER_MS
    /** For looping effects: false -> draw the static frame. */
    val loops: Boolean get() = !reduced
}
val LocalMotion = staticCompositionLocalOf { MotionScheme(reduced = false) }

/** Settings.Global.ANIMATOR_DURATION_SCALE, re-read on every resume (rememberResumeTick); 1f if unreadable. */
@Composable fun rememberSystemAnimatorScale(): Float

/** Scale to PRESS_SCALE while pressed (graphicsLayer; no recomposition per frame). No-op when reduced. */
fun Modifier.pressScale(interactionSource: InteractionSource): Modifier
/** Skeleton sweep drawn in drawWithCache/drawWithContent; static fill when reduced. */
fun Modifier.shimmer(): Modifier
/** First-appearance fade + rise with stagger; `enabled = false` renders immediately (callers pass a firstLoad flag). */
fun Modifier.enterOnce(index: Int = 0, enabled: Boolean = true): Modifier
/** Soft pulsing ring (outline alpha/scale in graphicsLayer) while `active`; static 2 dp ring when reduced. */
fun Modifier.pulse(active: Boolean, color: Color, shape: Shape = CircleShape): Modifier
/** Caret alpha 1 -> 0 -> 1 over CARET_MS (read in graphicsLayer); constant 1f when reduced or !active. */
@Composable fun rememberBlink(active: Boolean): State<Float>
```
`Mob8NTheme` calls `remember { UiPrefs.load(ctx) }` synchronously (one prefs read, so the first frame already has the right scheme and
motion), collects `UiPrefs.dynamicColor` / `UiPrefs.reduceMotion` with `collectAsStateWithLifecycle()`, and provides
`MotionScheme(motionReduced(rememberSystemAnimatorScale(), reduceMotion))` through `LocalMotion`, `MahoutColors` through `LocalMahoutColors`,
`MahoutTypography` and `MahoutShapes` through `MaterialTheme`.

### 3.3 Reduced-motion rules (review checklist)
1. Every `animate*AsState`, `Animatable.animateTo`, `AnimatedVisibility`, `AnimatedContent`, `animateContentSize`, `animateItem` in Mahout code
   takes its spec from `LocalMotion.current` (lint by grep in review: no literal `tween(`/`spring(` outside `Motion.kt`).
2. Loops (`rememberInfiniteTransition`, `withFrameNanos` tickers) run only when `motion.loops`; otherwise draw the static frame
   (caret solid, shimmer flat `surfaceContainerHighest`, pulse = static ring, flowing dots = the edge drawn in `primary` at 4 dp).
3. `animateItem(fadeInSpec = …, placementSpec = …, fadeOutSpec = …)` get `null`/snap specs when reduced.
4. Predictive back: no transform when reduced (the gesture still commits).
5. Onboarding intro: static composition, same content, same buttons.
6. Nothing conveys meaning by motion alone (status text accompanies every pulse/flash).

---

## 4. Components kit (owner: ui-foundation, `ui/Kit.kt`) — exact signatures
Other lanes compile against these signatures from day one (§8.2): until ui-foundation lands they paste this block with trivial bodies into
their sandbox and never deliver it.
```kotlin
enum class Tone { Primary, Neutral, Positive, Caution, Danger, Info }
enum class GlyphState { Pending, Running, Done, Failed, Denied }

/** Top bar: titleLarge single line (ellipsis), optional subtitle (labelMedium, onSurfaceVariant), back arrow when onBack != null.
 *  Container animates surface -> surfaceContainer when scrollBehavior reports overlap. Window insets = statusBars. */
@Composable fun MahoutTopBar(
    title: String, modifier: Modifier = Modifier, subtitle: String? = null, onBack: (() -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,           // wins over onBack (chat drawer menu)
    actions: @Composable RowScope.() -> Unit = {}, scrollBehavior: TopAppBarScrollBehavior? = null,
)

/** L1 card (`mahout.card`, light hairline), Radius.m, 16 dp padding, optional title row (titleMedium + trailing slot). Clickable -> pressScale + ripple.
 *  `description` becomes the merged contentDescription (DESIGN4 §6.3 rule: numbers stated in words). */
@Composable fun SectionCard(
    modifier: Modifier = Modifier, title: String? = null, description: String? = null, onClick: (() -> Unit)? = null,
    tone: Tone = Tone.Neutral,                                   // Danger -> errorContainer (Bypass variants), Caution -> warningContainer
    trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit,
)

/** Pill button (Radius.full, 48 dp min height, labelLarge). Primary = filled primary, Neutral = tonal surfaceContainerHighest,
 *  Positive = success, Caution = warningContainer, Danger = error/onError. pressScale. */
@Composable fun PillButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, tone: Tone = Tone.Primary,
    enabled: Boolean = true, outlined: Boolean = false, contentDescription: String? = null,
)

/** Status pill: dot + text (labelMedium) on the tone's container colour; colours animate (effect spec); `pulsing` = Modifier.pulse on the dot. */
@Composable fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier, dot: Boolean = true, pulsing: Boolean = false)

/** Risk pill: container/on-container from riskColors(risk); text = the caller's words (Permissions.chip / riskLabel). */
@Composable fun RiskPill(risk: com.mob8n.ai.Risk, text: String, modifier: Modifier = Modifier)

/** Rolling digits: each changed digit slides vertically (spatial spec) in a slot as wide as the widest digit, so width never jitters.
 *  Semantics: contentDescription = format(value) (only the final value is announced). */
@Composable fun AnimatedCounter(
    value: Long, modifier: Modifier = Modifier, style: TextStyle = NumericStyle, format: (Long) -> String = { it.toString() },
)

/** Skeleton placeholders (Modifier.shimmer). */
@Composable fun SkeletonLines(lines: Int = 3, modifier: Modifier = Modifier)
@Composable fun SkeletonBlock(modifier: Modifier = Modifier, height: Dp = 56.dp)

/** Centered empty state: BrandMark-tinted icon disc, headlineSmall title, bodyMedium body, optional action; enterOnce. */
@Composable fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null)

/** Two polylines (total in primary, failed in signal failed) drawn in drawWithCache; draws in over LONG2 on first show and on data change
 *  (PathMeasure.getSegment into a reused Path). `description` is the spoken series (DESIGN4 §6.3). Height 56 dp. */
@Composable fun AnimatedSparkline(total: List<Int>, failed: List<Int>, description: String, modifier: Modifier = Modifier)

/** Three dots rising in sequence (PULSE_MS/2 stagger); static "…" when reduced. contentDescription "Assistant is thinking". */
@Composable fun TypingDots(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary)

/** Morphing glyph: Pending = hollow ring, Running = rotating arc (loop), Done = check drawn in, Failed = cross, Denied = slashed ring.
 *  Crossfade + scale (spatialFast) between states. Always paired with text by the caller. */
@Composable fun StatusGlyph(state: GlyphState, modifier: Modifier = Modifier, size: Dp = 20.dp)

/** AnimatedVisibility(expanded, enter = motion.expand(), exit = motion.collapse()). */
@Composable fun ExpandableSection(expanded: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit)

/** Code block: codeBg, Radius.m, CodeStyle, horizontal scroll (no wrap), optional language label, Copy button (48 dp, "Copy code") ->
 *  ClipboardManager + "Copied" checkmark for 1.5 s; maxLines with "Show all N lines" when exceeded. Text selectable. */
@Composable fun MonoBlock(
    text: String, modifier: Modifier = Modifier, language: String? = null, copyable: Boolean = true, maxLines: Int = Int.MAX_VALUE,
)

/** App snackbar host: inverseSurface, Radius.m, action in inversePrimary. Every screen uses it (consistent toasts). */
@Composable fun MahoutSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier)

/** The logo: R.drawable.ic_launcher_foreground on a navy gradient disc (brandNavy -> brandNavy2). contentDescription null (decorative). */
@Composable fun BrandMark(modifier: Modifier = Modifier, size: Dp = 32.dp)

/** Very soft teal radial glow (primary at 6 % alpha, top-end) drawn behind a screen; one drawBehind, no animation. */
fun Modifier.brandGlow(): Modifier
```
Theme additions other lanes read (stub the same way):
```kotlin
@Composable fun Mob8NTheme(content: @Composable () -> Unit)      // unchanged signature
object UiPrefs {                                                 // SharedPreferences(SETTINGS_PREFS): "ui_dynamic_color" (false), "ui_reduce_motion" (false)
    val dynamicColor: StateFlow<Boolean>; val reduceMotion: StateFlow<Boolean>
    fun load(ctx: Context); fun setDynamicColor(ctx: Context, on: Boolean); fun setReduceMotion(ctx: Context, on: Boolean)
}
@Immutable data class MahoutColors(/* every §2.2 token + brand constants, as Color */)
val LocalMahoutColors: ProvidableCompositionLocal<MahoutColors>
val MaterialTheme.mahout: MahoutColors @Composable @ReadOnlyComposable get() = LocalMahoutColors.current
fun riskColors(risk: com.mob8n.ai.Risk, c: MahoutColors): Pair<Color, Color>   // (container, onContainer)
fun toneColors(tone: Tone, cs: ColorScheme, c: MahoutColors): Pair<Color, Color>
val BrandFamily: FontFamily; val MonoFamily: FontFamily
val CodeStyle: TextStyle; val CodeSmallStyle: TextStyle; val NumericStyle: TextStyle
object Space { val xxs: Dp; val xs: Dp; val s: Dp; val m: Dp; val l: Dp; val xl: Dp; val xxl: Dp; val xxxl: Dp }
object Radius { val xs: Dp; val s: Dp; val m: Dp; val l: Dp; val xl: Dp }
val BubbleUserShape: Shape
@Composable fun pageGutter(): Dp                                  // 16 dp < 600 dp wide, else 24 dp
// kept unchanged: kindColor, kindIcon, nodeIcon, statusColor, nodeStatusColor, fmtTime, fmtDuration, rememberResumeTick
```
Name-clash note: `Permissions.kt` has a `private fun StatusChip` and `Dashboard.kt` a public `Sparkline`; the kit uses `StatusPill` and
`AnimatedSparkline` so both lanes compile before and after the swap. ui-screens deletes `Dashboard.Sparkline` when it adopts the kit;
`sparklineNorm` stays in `Dashboard.kt` (pure, tested) and the kit calls it.

---

## 5. Chat (the centrepiece)

### 5.1 Streaming architecture (owner: ai-streaming)

#### 5.1.1 Types (`ai/Sse.kt`, NEW, pure JVM)
```kotlin
sealed interface StreamDelta {
    data class Text(val text: String) : StreamDelta
    data class Thinking(val text: String) : StreamDelta
    data class ToolStart(val index: Int, val id: String, val name: String) : StreamDelta
    data class ToolArgs(val index: Int, val addedChars: Int) : StreamDelta   // size only: raw argument JSON never reaches the UI
    data object Reset : StreamDelta                                         // mid-stream failure: discard the segment, non-stream redo follows
}
typealias OnDelta = (StreamDelta) -> Unit
const val STREAM_FRAME_MS = 40L

object Sse {
    /** SSE framing: lines -> event data payloads. `data:` lines of one event joined by '\n'; blank line ends an event; lines starting
     *  with ':' (e.g. OpenRouter ": OPENROUTER PROCESSING"), `event:`, `id:`, `retry:` ignored; CRLF tolerated; "[DONE]" returned as-is. */
    fun events(lines: Sequence<String>): Sequence<String>
}

/** OpenAI-compatible chunk accumulator. Pure. */
class OpenAiStreamAccumulator(private val providerLabel: String) {
    /** One parsed `data:` JSON chunk -> deltas. Throws NodeException for `{"error":{…}}` chunks and MiniMax `base_resp.status_code != 0`. */
    fun feed(chunk: JsonObject): List<StreamDelta>
    /** A body equivalent to the non-streaming response: {id, model, choices:[{index:0, message:{role:"assistant", content, tool_calls?,
     *  reasoning_content?, reasoning?, reasoning_details?}, finish_reason}], usage?} -> fed to the EXISTING OpenAiCompat.parseResponse. */
    fun toResponse(): JsonObject
    val emittedAny: Boolean
}

/** Leading-edge throttle (pure; fake clock in tests). */
class Throttle(private val periodMs: Long) { fun ready(nowMs: Long): Boolean }

/** Display filter for partial text: strips <think>…</think> / <mm:think>…, lone closers (MiniMax-M3), and hides an unclosed <think> tail. */
fun streamVisible(raw: String): String
```
Accumulation rules (OpenAI-compatible): `choices[0].delta.content` appended; `delta.reasoning_content` (DeepSeek) and `delta.reasoning`
(OpenRouter) appended to their own fields and emitted as `Thinking`; `delta.reasoning_details[]` (MiniMax with `reasoning_split`) accumulated
**by `index` (default 0)**: `text` concatenated, other keys first-wins → `message.reasoning_details` (the `_oai` echo MiniMax needs keeps its
shape); `delta.tool_calls[]` accumulated **by `index`**: `id` first non-blank wins, `type` defaults `"function"`, `function.name` appended
(OpenAI sends it once; appending is equivalent and survives providers that split it), `function.arguments` appended (string fragments may
split mid-token/mid-escape; they are only parsed once, in `parseResponse`); `ToolStart` emitted when an index first gets id+name, `ToolArgs`
per fragment; `finish_reason` = last non-null; `usage` = last chunk that carries one (the `include_usage` final chunk has `choices: []`);
`model` = first non-null. A `Gemini`-style object-valued `arguments` is kept as the object (parseResponse already handles it).

#### 5.1.2 Seam (`Llm.step`)
```kotlin
suspend fun step(
    t: LlmTarget, maxTokens: Int, system: String?, messages: List<JsonObject>, tools: List<JsonObject>, jsonMode: Boolean, timeoutMs: Long,
    log: (String) -> Unit = {}, source: String = "node", ref: String? = null,
    onDelta: OnDelta? = null,                                  // NEW, last; null = today's path byte-for-byte (Agent, Builder, SystemOne unchanged)
): Turn
```
`ClaudeClient.step(…, onDelta: OnDelta? = null)` and `OpenAiCompat.step(…, onDelta: OnDelta? = null)` gain the same trailing parameter.
`withUniqueToolIds()` and `recordTurn` run on the final `Turn` exactly as today.

#### 5.1.3 OpenAI-compatible SSE (`OpenAiCompat`)
- Streaming is used when `onDelta != null && "no_stream" !in quirksOf(p.id)`; `jsonMode`/schema calls never stream (chat passes `false`).
- Body = `buildBody(…)` with `"stream": true` and `"stream_options": {"include_usage": true}` unless quirk `no_stream_options`.
  MiniMax: same body (+ `reasoning_split: true` from `extraBody`); usage is taken from whichever chunk carries it.
- Header `Accept: text/event-stream`. Connect/retry via the existing `exchange` policy (one retry on 429/502/503/IOException before any
  byte is read). `readTimeout` = `min(timeoutMs, 60 s)` = idle gap between chunks; the whole step stays inside the caller's `withTimeout`.
  Cancellation disconnects the socket (existing `invokeOnCancellation`). Body read through `BufferedReader(UTF-8)`, total cap 8 MB.
- Status ≠ 200 → read the error body and go through the existing 400-downgrade: `quirkFor400` learns two new quirks, only for features
  actually sent — `"stream_options"` in body text → `no_stream_options`; `"stream"` in body text → `no_stream`. Otherwise `errorMessage`.
- 200 with `Content-Type` not `text/event-stream` (provider ignored `stream`) → parse the body as a normal response and cache `no_stream`.
- Fallback (D7), logged `"<label> streaming failed (<reason>); retrying without streaming"`: IOException/timeout/malformed framing, or more
  than 3 unparseable chunks, or EOF without `[DONE]` and without `finish_reason`. Before any delta → cache `no_stream` + redo. After deltas →
  `onDelta(Reset)` + redo, not cached.
- End: `parseResponse(p, acc.toResponse(), model)` → same `Turn`, same `raw` echo, same `stripThink`, same refusal/content_filter mapping.

#### 5.1.4 Claude (`ClaudeClient`)
```kotlin
// inside withTimeout(timeoutMs) { withContext(Dispatchers.IO) { … } }
val sr = client(key).beta().messages().createStreaming(params)
currentCoroutineContext().job.invokeOnCompletion { runCatching { sr.close() } }   // cancel -> close the HTTP stream
val msg = sr.use {
    val acc = BetaMessageAccumulator.create()
    val it = sr.stream().iterator()
    while (it.hasNext()) { ensureActive(); val ev = it.next(); acc.accumulate(ev); ClaudeStream.deltas(ev).forEach(onDelta) }
    acc.message()
}
val wire = toKx(JsonValue.from(msg)) as? JsonObject ?: throw NodeException("Claude returned an unreadable response")
return check(parseMessage(wire))
```
`object ClaudeStream { fun deltas(ev: BetaRawMessageStreamEvent): List<StreamDelta> }` (pure mapping: content_block_start tool_use →
`ToolStart(index, id, name)`; delta text → `Text`; thinking → `Thinking`; input_json → `ToolArgs(index, partialJson.length)`; everything else
→ empty). Errors: one shared `mapClaudeErrors { }` wrapper used by both paths so the messages are identical. `AnthropicServiceException`
subclasses (HTTP status) are real errors. Streaming-only failures (`AnthropicIoException` mid-stream, accumulator `IllegalStateException`,
SDK SSE parse errors) → D7 fallback to `create(params)`; the "broken" flag is a `@Volatile var streamBroken` for the process.
Refusal (`stop_reason == "refusal"`) is checked on the accumulated message exactly as today.

#### 5.1.5 `ChatRunner` live turn (ai/Chat.kt)
```kotlin
data class LiveTool(val id: String, val name: String, val state: State, val startedMs: Long? = null, val endedMs: Long? = null,
                    val result: String? = null, val argsChars: Int = 0) {
    enum class State { FORMING, QUEUED, RUNNING, DONE, FAILED }
}
data class LiveSegment(val text: String = "", val thinking: String = "", val tools: List<LiveTool> = emptyList())
/** The UNPERSISTED part of the in-flight turn; oldest segment first. `streaming` = a model call is in flight (last segment growing). */
data class LiveTurn(val startedMs: Long, val segments: List<LiveSegment>, val streaming: Boolean)

object ChatRunner {
    fun live(conversationId: String): StateFlow<LiveTurn?>                            // NEW
    fun send(app: Context, conversationId: String, text: String, imagePath: String? = null)   // imagePath NEW (default keeps callers)
    // everything else unchanged
}
object ChatImages {                                                                     // NEW (ai/Chat.kt)
    const val DIR = "chat-images"
    /** Images.jpeg(uri) -> cacheDir/chat-images/<uuid>.jpg; returns the absolute path. */
    suspend fun import(ctx: Context, uri: android.net.Uri): String
}
```
Where it is written (all inside the existing `runTurn`, no change to `drive`/`AgentNode.loop`/`Persister` semantics):
1. `step` lambda: `live = (live ?: LiveTurn(now, [], false)).copy(segments = segments + LiveSegment(), streaming = true)`; a local
   `LiveBuilder` (StringBuilders + tool list) receives every `StreamDelta` on the reader thread; `if (throttle.ready(now)) publish()`;
   `Reset` clears the builder. After `Llm.step` returns, the segment is replaced from the final `Turn` (text via `turn.text`, tools QUEUED)
   and `streaming = false` — this also renders non-streaming providers (the segment appears whole).
2. `wrap(…)` gains `onDone: (name: String, out: ToolOut?, error: Throwable?) -> Unit = { _, _, _ -> }` (default keeps `ChatLoopTest`):
   `onRun(name)` → first QUEUED tool with that name → RUNNING (startedMs); `onDone` → DONE/FAILED, `result` capped at 2 KB, endedMs.
   Denied ids (per-call decisions) become FAILED with result `"denied by user"`.
3. Every flush (`persist = { pend -> p.flush(pend); live.value = null }`), the cancel epilogue and `fail()` set `live = null` **after** their
   Room writes. A new turn starts with `null`.
4. `Status` is unchanged (Thinking / Running / Awaiting / Error / Idle); the UI uses it for the status line and the Stop button.

Images: `send(…, imagePath)` → the persisted user row is `ClaudeClient.userMessage(text.trim() + pinned)` with
`meta = {"image": "<path>"}`; after `state` is built, when `t.supportsVision` the LAST plain user message in `state.messages` is replaced
in memory by `userMessage(text + pinned, Images.base64(app, Uri.fromFile(File(path)).toString()))`. Not vision-capable → note row
`"Image not sent: <label> cannot read images"` and the text is sent alone. Unreadable/evicted file → same note.

#### 5.1.6 Throttling and the UI contract
- Publish at most every `STREAM_FRAME_MS` (40 ms, 25 fps) — tokens arrive at 30–150/s; the eye cannot use more, recomposition is bounded.
- The chat screen collects `ChatRunner.live(id)` as a `State` and reads it **only** inside the live `item {}` lambdas;
  `val liveCount by remember { derivedStateOf { liveState.value?.segments?.size ?: 0 } }` decides how many live items exist, so the screen
  body recomposes on segment count changes only.
- Handoff (no flicker, no double): the UI keeps `shownLive` = the last non-null `LiveTurn`; when `live` turns `null` it clears `shownLive` at
  the next `rows` emission or after 400 ms, whichever comes first. Live items leave with `fadeOutSpec = null` and the rows that arrived in the
  same emission enter with `fadeInSpec = null`, so the swap is invisible (same content, same place).

#### 5.1.7 Provider matrix
| Provider | Streams | Thinking shown | Usage | Notes |
|---|---|---|---|---|
| Claude (SDK) | `createStreaming` | `thinking` deltas when the model emits them | from accumulated message | refusal on final message |
| OpenAI, xAI, Mistral, Together, Groq, DeepSeek, Gemini-compat, Ollama, Custom | SSE | `reasoning_content` / `reasoning` | `include_usage` final chunk; quirk `no_stream_options` if rejected | unknown providers self-heal via D7 |
| OpenRouter | SSE (ignores `:` keep-alive comments) | `reasoning` | final chunk | attribution headers unchanged |
| MiniMax | SSE + `reasoning_split` | `reasoning_details[].text` | last chunk carrying `usage` | `base_resp` checked per chunk; `<think>` stripped for display and in `parseResponse` |
| Gemini Nano | — (D8) | — | — | chat rejects Nano before any call |

### 5.2 Layout
Phone (< 840 dp): `ModalNavigationDrawer` (drawer = `ChatListPane(compact = true)`, `gesturesEnabled = drawerState.isOpen` so the edge
swipe stays with predictive back and text selection) › `Scaffold` { top bar | thread | approval dock or composer }.
Tablet (≥ 840 dp): unchanged two-pane (360 dp `ChatListPane` | thread), thread content centred at `widthIn(max = 760.dp)`.
Top bar (`MahoutTopBar`): navigation = menu (phone, opens drawer, "Open chat list") or back; title = conversation title; subtitle =
`"${t.label} · ${t.model}"` from `Llm.defaultTarget(ctx)` read once per resume; actions = **Mode chip** (§5.3.10), Tune (settings sheet).
Background: `brandGlow()`.

### 5.3 Interactions
1. **User bubble**: right-aligned, `userBubble`/`onUserBubble`, `BubbleUserShape`, `bodyLarge`, max width 85 % (phone) / 560 dp; image
   thumbnail (from `meta.image`, decoded on IO with `inSampleSize`, 160 dp max, Radius.s) above the text; missing file → "(image)" chip.
2. **Assistant turn**: `BrandMark(20.dp)` + `labelSmall` "Mahout · <model>" once per turn, then blocks rendered by `MarkdownBlocks`
   (§5.3.4) on the surface, `bodyLarge`, `onSurface`. Enter: `animateItem` (fade + placement with `motion.spatial()`).
3. **Streaming**: the last live segment renders the same blocks plus the **caret** — an `InlineTextContent` placeholder (0.5 em × 1.1 em,
   `caret` colour, Radius.xs) appended to the last text block; alpha from `rememberBlink(streaming)` read in `graphicsLayer` (no text
   recomposition). Height growth: `Modifier.animateContentSize(motion.spatial())` on the live item (disabled when reduced). Before the first
   token (or for non-streaming providers) a `TypingDots` row with "Thinking · 4 s" (`labelSmall`, ticker scoped to that Text).
   `thinking` text (when present) is a collapsed "Thinking" disclosure above the answer (`ExpandableSection`, `CodeSmallStyle`, ≤ 2 KB shown).
4. **Markdown upgrade** (`ui/Markdown.kt`): pure `parseMarkdown(md: String): List<MdBlock>` with
   `sealed interface MdBlock { Heading(level, text), Paragraph(text), Bullet(depth, marker, text), Quote(text), Code(lang, code, closed), Table(header, rows), Rule }`
   — headings 1–3, ordered/unordered lists with nesting by indent (2+ spaces = one level), `>` quotes, pipe tables (header + `---` row;
   ragged rows padded), fenced code (an unclosed fence while streaming renders as an open `Code`, `closed = false`), inline code/bold/italic/
   links through the existing `markdownToAnnotated`. Links become clickable (`LinkAnnotation.Url`, compose 1.7) opening the browser.
   Rendering: `Code` → `MonoBlock` (copy + horizontal scroll); `Table` → `Row`s inside `horizontalScroll`, header `labelLarge` on
   `surfaceContainer`, cells `bodyMedium`, 1 dp `outlineVariant` grid, min cell width 96 dp; blocks keyed by index so completed blocks skip
   recomposition while the last one grows. `markdownToAnnotated` and `MarkdownText` keep their signatures (Skills/approvals use them).
   Never throws (fallback = one Paragraph of the raw text).
5. **Message actions**: long-press (phones) or hover (tablet pointer: `hoverable` + `collectIsHoveredAsState`, a small action row fades in
   at the bubble's top-end) → menu: user row = Copy · Edit & resend (last user message, idle, no pending) · Hide; assistant row = Copy ·
   Retry (last answer, idle, no pending) · Hide; tool card = Copy input · Copy output. Same actions exposed to TalkBack as
   `customActions`. Copy → `LocalClipboardManager` + snackbar "Copied". Retry → `ChatRunner.send(app, id, lastUserText)`. Edit & resend →
   draft = last user text, cursor at end, focus. Hide → session-only (`rememberSaveable` id set) + snackbar "Hidden from view — the assistant
   still remembers it" [Undo]. Haptic `LongPress` on menu open.
6. **Send ↔ Stop morph**: one 48 dp circular button at the composer's end. States: empty & idle → Mic; text → Send (filled `primary`);
   busy (`Thinking`/`Running`) → Stop (filled `inverseSurface`, square icon). `AnimatedContent(targetState)` with scale 0.6→1 + fade
   (`emphasis`), 90° rotation on Send→Stop. Stop keeps `contentDescription "Stop the assistant"`. Send disabled while any call is undecided.
7. **Scroll**: reverse `LazyColumn` (index 0 = newest). Follow logic unchanged (anchored at bottom while `follow`); a live item growing at
   index 0 stays anchored naturally. Away from the bottom (first visible index > 0 or offset > 48 dp) → **Jump-to-latest FAB** (small, 40 dp
   visual / 48 dp target, `surfaceContainerHigh`, arrow down) at bottom-end above the composer, `motion.enter()`; badge = unread count =
   rows + live segments added since the user left the bottom (`derivedStateOf`); tap → `animateScrollToItem(0)` (instant when reduced).
   `contentDescription "Jump to latest, 3 new"`.
8. **Tool-call cards** (persisted `ToolCard` and live `LiveTool`, one composable): L2 container, Radius.m, collapsed by default:
   `StatusGlyph` (QUEUED/FORMING → Pending, RUNNING → Running, DONE → Done, FAILED → Failed, denied → Denied) · tool name
   (`CodeSmallStyle`) · kind (`labelSmall`) · `RiskPill(risk, cardChip(…))` · elapsed (`fmtDuration`, live ticker every 250 ms while
   RUNNING only) · expand chevron (rotates 180°, `spatialFast`). Expanded (`ExpandableSection`): Input (`MonoBlock`, pretty JSON, ≤ 2 KB) and
   Output (`MonoBlock`, ≤ 2 KB, error tint = `riskAlways` container). Errors auto-expand. FORMING shows "preparing… 312 chars". "Open in
   editor" for `save_workflow` drafts unchanged. Existing semantics string kept: `"Tool ${name}, $chip, ${no result yet|failed|done}"`.
9. **Approval dock** (replaces the composer while `undecided > 0`; `ui/ChatApproval.kt`): L3 container, top corners Radius.l, 3 dp
   shadow, 1 dp `outlineVariant` hairline; a 4 dp left stripe in the batch's highest-risk colour (`riskColors`). Enters with
   `slideInVertically(emphasis) + fadeIn`, exits `slideOut + fadeOut(exitEffect)`. Content, top to bottom:
   header `"Wants to run 3 tools · 1 decided"` (titleSmall, `liveRegion = Polite`), `"Mode: …"` line (`modeHelp`), then one compact row per
   call: `RiskPill` · tool name · one-line preview · [Details ▾] (expands the full v4 preview: markdown / `MonoBlock` with "Show all N lines"
   / run_js allow-list line / save_workflow summary + Open in editor + expired-draft warning, `UNATTENDED_PREFIX` lines in `error`) ·
   **Approve** (`PillButton` Primary) / **Deny** (`PillButton` Neutral outlined), both 48 dp; decided rows morph buttons → label
   (`StatusGlyph` Done/Denied + "Approved — waiting for the other calls" / "Denied"). n > 1 → **Approve all** / **Deny all** row. Max height
   60 % of the screen, inner scroll. Haptics: Approve = `View.performHapticFeedback(CONFIRM)` on API 30+, else `LocalHapticFeedback`
   `LongPress`; Deny = `REJECT` on 30+, else `LongPress`. All strings and `contentDescription`s from DESIGN4P §3 verbatim
   ("Approve run_shell", "Deny run_shell", "Approve all 3 pending tool calls", "Deny all pending tool calls", card "run_shell approved,
   waiting"). Data path unchanged (live `Status.Awaiting` or `parsePending(conv.pendingJson)` after process death).
10. **Mode chip** (top bar): `StatusPill`-styled button: Plan (Neutral) · Ask (Info) · Auto (Caution) · Bypass (Danger, text
    `"Bypass · 41m"` from `modeStatus`); container colour animates (`effect`). Tap → `DropdownMenu` (M3's built-in scale/fade) with
    Inherit (global label) / Plan / Ask / Auto / Bypass, each with its one-line `modeHelp`; **Bypass always goes through
    `BypassConfirmDialog(BypassScope.CONVERSATION)`**; the save is the same `s.copy(mode = m, bypassUntil = …, autoApprove* = false)` as the
    settings sheet. `contentDescription "Permission mode Ask, change"`.
11. **Composer** (`ui/ChatComposer.kt`): L2 bar with 1 dp top hairline, `navigationBarsPadding().imePadding()`; row = [+] attach menu ·
    field · Mic/Send/Stop. Field: `BasicTextField` in a Radius.l container, 1–6 lines then scrolls, height changes with
    `animateContentSize(spatial)`, placeholder = `composerHint(…)` (unchanged), `bodyLarge`. Above the field: context chips row (mentions,
    attached image thumbnail with ✕), `motion.expand()`. [+] menu: Photo (only when `Llm.defaultTarget(ctx).supportsVision`), Mention (types
    "@"), Command (types "/").
12. **Slash commands** (pure `ui/ChatSlash.kt`): popup = a L3 surface directly above the field inside the same column (not a `Popup`
    window, so IME focus never breaks), `motion.expand()`, rows 48 dp, arrow/enter keys on hardware keyboards, tap to complete.
    `parseSlash(text: String, cursor: Int): SlashState?` — active only when the text starts with `/` and the cursor is in the first token or
    its argument.

    | Command | Args (autocomplete) | Effect |
    |---|---|---|
    | `/new` | — | `ChatRunner.newConversation` with default settings, open it |
    | `/clear` | — | new conversation **with this chat's settings** (mode, tools, skills, knowledge), open it; history is never deleted |
    | `/mode` | `inherit`·`plan`·`ask`·`auto`·`bypass` | same save as the mode chip; `bypass` opens `BypassConfirmDialog(CONVERSATION)` — no confirm, no change |
    | `/run` | workflow names | `engine.runManual(id)` (the user's own action, same as the list's Run now); snackbar "Run started · View" → `Screen.RunDetail`; "No Manual trigger in X" otherwise |
    | `/skill` | enabled skill names | adds a skill context chip (§5.3.13) |
    | `/panel` | panel titles/slugs | opens `Screen.Panels(slug)` |

    Unknown command → the text is sent as a normal message (never swallowed).
13. **@-mentions**: typing `@` after whitespace opens the same popup listing workflows (`engine.workflows()`), skills (`engine.skills()`),
    knowledge sources (`engine.knowledge.names()`), filtered by the word after `@`; picking removes the `@word` and adds a chip
    (`InputChip`, removable, "Remove mention X"). On send the chips become one leading line (pure `composeOutgoing(text, mentions)`):
    `Context: workflow "Morning digest" [1a2b3c4d]; skill coding-on-device; knowledge "Manuals".` + blank line + text. Plain text in the
    user row — transcript contract intact.
14. **Image**: `rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia())` with `ImageOnly`; on result →
    `ChatImages.import` (IO) → thumbnail chip; send passes `imagePath`. No permission.
15. **Voice**: `StartActivityForResult` with `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`, `EXTRA_LANGUAGE_MODEL_FREE_FORM`,
    `EXTRA_PROMPT "Speak to Mahout"`; result `EXTRA_RESULTS[0]` appended to the draft (space-separated); `ActivityNotFoundException` →
    snackbar "No speech recognizer on this device". No `RECORD_AUDIO`, no manifest change.
16. **Empty state**: `EmptyState(Icons.Rounded.Forum, "Ask Mahout", "It can list, run, build and fix your workflows, search your
    knowledge, or write a script. Actions that change things pause for your approval.")` + suggestion chips (static, no LLM):
    "What ran today?", "Why did my last run fail?", "Build a workflow that…", "Search my knowledge for…" — tap fills the composer (not sent).
17. **Follow-up chips** after an answer: only local ones (D9): "Save as skill?" (existing rule), "Open draft" (latest draft in
    `ChatDrafts` for this turn). `motion.enter()`.
18. **Conversation list** (`ChatListPane`): SectionCard rows (title `titleMedium`, `fmtAgo`, status pill: awaiting = gold Caution
    "awaiting approval", running = Primary pulsing "working…", error = Danger), search field with a 300 ms debounce (unchanged), FAB
    `motion.enter()`; selection = container colour animates to `secondaryContainer` + 3 dp `primary` start indicator (`spatial` width);
    `animateItem` for reorder (a conversation moving to top slides, not jumps). Signature gains `compact: Boolean = false` (drawer: no FAB,
    "New chat" as first row).
19. **Settings sheet** (`ChatSettingsSheet`): unchanged content; restyled with SectionCards; `ModeSelector` unchanged.

### 5.4 Chat accessibility
- Streaming bubble: no live region on the text. The status line node (`StatusRow`) carries `liveRegion = Polite` and its
  `contentDescription` is driven by pure `nextAnnouncement(text, spokenUpTo, nowMs, lastMs, done): Pair<String, Int>?` — announces newly
  completed sentences at most every 2 500 ms, and the remainder when the turn completes ("Mahout: …"); first announcement "Mahout is
  responding". Approval header keeps its own Polite live region; Bypass banner keeps Assertive.
- Tool cards merge descendants with the existing description; expand toggles have "Show/Hide input of X"; the dock rows keep DESIGN4P texts.
- Every custom control ≥ 48 dp; the jump FAB, chips (`minimumInteractiveComponentSize`), copy buttons included.
- 200 % font scale: bubbles, chips (FlowRow), pills wrap; the top-bar subtitle hides when `fontScale > 1.5` (title keeps one line with
  ellipsis); composer max 4 lines when `fontScale > 1.5`.
- Hover action row also reachable by keyboard focus (tablet with keyboard).

---

## 6. Screens and app-wide fluidity (owner: ui-screens unless noted)

### 6.1 Navigation transitions (`ui/Nav.kt`, `ui/App.kt`)
```kotlin
// Nav.kt additions
val Screen.depth: Int get() = when (this) {
    Screen.List, Screen.Dashboard, Screen.Knowledge, Screen.Skills -> 0
    is Screen.Chat -> if (conversationId == null) 0 else 1
    is Screen.Editor, Screen.Permissions, Screen.AiSettings, Screen.Notes, Screen.Playlist, is Screen.Panels -> 1
    is Screen.Build -> if (workflowId == null) 1 else 2
    is Screen.Runs -> if (workflowId == null) 1 else 2
    Screen.McpSettings -> 2
    is Screen.RunDetail -> 3
}
val Screen.contentKey: String get() = encode()                 // SaveableStateProvider key + AnimatedContent contentKey
enum class NavKind { NONE, FADE_THROUGH, PUSH, POP }
fun navKind(from: Screen, to: Screen): NavKind = when {         // pure, NavTransitionTest
    from.contentKey == to.contentKey -> NavKind.NONE
    to.depth > from.depth -> NavKind.PUSH
    to.depth < from.depth -> NavKind.POP
    else -> NavKind.FADE_THROUGH
}
```
Specs (`MotionScheme`): FADE_THROUGH = exit `fadeOut(exitEffect(SHORT1))`, enter `fadeIn(enterEffect(MEDIUM1, delay = SHORT1)) +
scaleIn(0.96f, enterEffect)`; PUSH = enter `slideInHorizontally { w/10 } (spatial) + fadeIn(effect)`, exit `slideOutHorizontally { -w/20 }
+ fadeOut(exitEffect)`; POP = mirror; `SizeTransform(clip = false)`; reduced → `EnterTransition.None togetherWith ExitTransition.None`.
Direction mirrors in RTL (`LocalLayoutDirection`).
Phone: `AnimatedContent(screen, transitionSpec = { spec(navKind(initialState, targetState)) }, contentKey = { it.contentKey })` wraps what
`ScreenContent`/`WorkflowListScreen` render today; inside it `saveableStateHolder.SaveableStateProvider(screen.contentKey) { … }` so Back
restores list scroll and fields. Tablet: the rail and list pane stay put; only the detail `Box` is an `AnimatedContent` keyed the same way.
Rail/bar: M3 indicator animation (built in); the Chat destination gets a badge with the number of conversations awaiting approval
(`engine.conversations()` count `status == "awaiting"`, `Badge` enters with `motion.enter()`); labels: `alwaysShowLabel = fontScale <= 1.3f`
on the phone bar (200 % scale would clip the 80 dp bar), pure `showNavLabels(fontScale)`.

### 6.2 Predictive back (`App.kt`)
Replace `BackHandler(enabled = onboarded && screen != Screen.List) { screen = screen.parent }` with:
```kotlin
var backProgress by remember { mutableFloatStateOf(0f) }; var backEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
PredictiveBackHandler(enabled = onboarded && screen != Screen.List) { events ->
    try { events.collect { backProgress = it.progress; backEdge = it.swipeEdge }; screen = screen.parent }
    finally { backProgress = 0f }                                  // cancel -> spring back via animateFloatAsState below
}
val p by animateFloatAsState(if (motion.reduced) 0f else backProgress, motion.spatialFast(), label = "back")
Modifier.graphicsLayer {
    val e = MotionTokens.Decelerate.transform(p)
    scaleX = 1f - 0.08f * e; scaleY = scaleX
    translationX = (if (backEdge == BackEventCompat.EDGE_LEFT) 1 else -1) * 16.dp.toPx() * e
    shape = RoundedCornerShape(28.dp * e); clip = p > 0f
}
```
Screen-level `BackHandler`s (editor unsaved-changes guard, sheets, drawer) are registered later and win, unchanged. Android < 14 delivers
no progress: behaves like today's BackHandler.

### 6.3 Edge-to-edge audit
`enableEdgeToEdge()` already runs. Fixes: (1) phone `Scaffold` in `App.kt`: `Box(Modifier.padding(pad).consumeWindowInsets(pad))` so screen
Scaffolds don't pad the navigation bar twice; (2) every screen Scaffold uses `MahoutTopBar` (statusBars insets) and default
`contentWindowInsets`; (3) chat composer and approval dock: `navigationBarsPadding().imePadding()` exactly once; (4) lists with a FAB keep
`88.dp` bottom content padding **plus** Scaffold's inset (already in `pad`); (5) Editor canvas draws edge-to-edge under the nav bar, its
controls pad with `WindowInsets.safeDrawing`; (6) `BypassBanner` inset consumption stays exactly as today; (7) sheets use M3 1.3 default
insets. Check list in §10.

### 6.4 Onboarding intro (`ui/Intro.kt`, NEW)
`@Composable fun IntroScreen(onDone: () -> Unit)` shown when `!onboarded && !introDone` (`rememberSaveable`), before the existing
full-screen `PermissionsScreen(onboarding = true)`. Composition: navy gradient full-bleed, `BrandMark(112.dp)` scales 0.9→1 + fades in
(`emphasis`), then "Mahout" (`displaySmall`, gold light) and "Automations you steer — on your phone." (`bodyLarge`) rise in with 120 ms
stagger, then **Get started** (`PillButton`) and **Skip** (text). ~1.2 s total; tap anywhere completes the animation. Reduced motion:
everything present at once. `onDone` → permissions onboarding (unchanged).

### 6.5 Workflows (`WorkflowList.kt`)
Rows → `SectionCard` (L1) with `pressScale`; name `titleMedium`; `Switch` (M3, already animated) with thumb icon (check/close) and
`contentDescription` unchanged; last-run chip → `StatusPill(tone by status, pulsing = RUNNING)` with `animateColorAsState` colours;
trigger icons unchanged; "needs host" / "needs permission" / "tool" → `StatusPill` Caution / Danger / Info. First load: `enterOnce(index,
firstLoad)`; inserts/removes/reorders: `animateItem(motion…)`. **Swipe actions** via `SwipeToDismissBox` whose `confirmValueChange` runs the
action and returns `false` (snaps back): start→end = Run now (primary background, play icon, "Run"), end→start = Enable/Disable (tonal,
toggle icon). `positionalThreshold = { it * 0.35f }`; haptic `LongPress` when `targetValue` crosses the threshold; background icon
scales 0.8→1 with progress. Accessibility: `customActions` "Run now", "Enable"/"Disable" (the menu and Switch remain). Swipe disabled while
a row menu is open. Empty list → `EmptyState`.

### 6.6 Dashboard (`Dashboard.kt`)
`PullToRefreshBox(isRefreshing, onRefresh = { refreshKey++ })` around the grid; `refreshKey` joins `tick` in `now`/`produceState` keys;
`isRefreshing` stays true until knowledge + storage recompute (min 450 ms visible). Cards → `SectionCard` (`description` unchanged texts);
counts → `AnimatedCounter` (runs total, AI calls, tokens via `format = Stats::fmtTokens`); `Sparkline` → `AnimatedSparkline` (draw-in on
first show and on range switch); `knowledge == null` / `storage == null` → `SkeletonLines(1)`; first load stagger `enterOnce(index)`.
Permissions card: `tone = Danger` while Bypass is active (text "Bypass active — 41 min left", **Stop everything** `PillButton` Danger);
otherwise Neutral with the mode `StatusPill`. Grid `GridCells.Adaptive(340.dp)` unchanged.

### 6.7 Editor, Canvas, Palette, ParamSheet, Widgets, RunLog
- Editor top bar → `MahoutTopBar`; run/save buttons → `PillButton`; snackbars → `MahoutSnackbarHost`.
- Canvas performance first: edge `Path`s are built in `remember(graph.edges, graph.nodes positions, specs, density)` (not per draw);
  the edge layer uses `drawWithCache`; animation values are read in draw scope only.
- **Edge draw-in**: an edge key not seen in the previous composition animates `0→1` over `MEDIUM2` (`PathMeasure.getSegment`); existing edges
  static. **Node appear**: new node ids `enterOnce` (scale 0.9→1 + fade) in `NodeCard`'s `graphicsLayer`; **disappear**: a removed node leaves
  a ghost rounded-rect outline in the edge layer fading out over `SHORT2`.
- **Live run visualisation** (driven by the existing `engine.runs(workflowId, 1)` + `engine.nodeLogs(runId)` flows, which Editor extends to
  emit `(runStatus, logs)`): pure `runOverlay(graph: Graph, logs: Map<String, NodeStatus>, running: Boolean): RunOverlay(active:
  Set<String>, flowing: Set<String /*edge key*/>)` — when `running`: active = nodes without a log that have an incoming edge from a node
  logged SUCCESS/ERROR_ROUTED (ERROR_ROUTED only via its `error` port), or the trigger nodes when no log exists yet; flowing = those
  incoming edges. Active nodes: `Modifier.pulse(true, statusColor(RUNNING))`; flowing edges: three dots at `t = (phase + k/3) % 1` along
  `bezierAt` (phase from one `rememberInfiniteTransition`, `FLOW_MS`, read in draw scope), edge stroked `primary` at 3 dp. Completion
  flash: when a node's status newly becomes SUCCESS/FAILED, an overlay in `nodeStatusColor` animates alpha 0.35→0 over `LONG2`. Reduced:
  no pulse/dots/flash; active node = static 3 dp `primary` ring, flowing edge = static 4 dp `primary`. `// ponytail: running node inferred
  from node_logs (logged on completion); upgrade = engine.activeNodes flow`.
- Palette (`PaletteSheet`): `ModalBottomSheet(skipPartiallyExpanded = false)` (half → full drag), drag handle, containerColor
  `surfaceContainerLow`, Radius.l top, search field pinned, sections `enterOnce` stagger. ParamSheet: `skipPartiallyExpanded = true` on phones,
  same styling. Widgets: text fields `OutlinedTextField` with Radius.s, focused border `primary`; `SwitchRow`/`EnumDropdown` restyled only.
- RunLog: `NodeLogCard` → SectionCard with status stripe (`nodeStatusColor`), JSON → `MonoBlock`; Runs list rows `StatusPill`.

### 6.8 Settings, Permissions, Skills, Knowledge, Notes, Playlist, BuildWithAi, McpSettings, Bypass
Restyle only (SectionCard, MahoutTopBar, PillButton, StatusPill, EmptyState, MahoutSnackbarHost, Space/Radius tokens); no behaviour change.
`AiSettings.kt` gains an **Appearance** SectionCard: "Use wallpaper colours" (`Switch`, shown on API 31+ only, default off, helper "Safety
colours always stay Mahout's") → `UiPrefs.setDynamicColor`; "Reduce motion" (`Switch`) → `UiPrefs.setReduceMotion`, helper "Also on when
Android's Remove animations is on. System sheets and switches follow Android's setting."; "Fonts: Manrope, JetBrains Mono — SIL Open Font
License 1.1" row → dialog showing both `assets/licenses/*.txt`. `Bypass.kt`: banner/dialogs/ModeSelector keep every string, semantics and
the inset logic; banner gets `brandGlow`-free flat `errorContainer`, a leading shield icon and the countdown in `NumericStyle` (tabular, no
width jitter). Knowledge/Skills lists: `animateItem` + `EmptyState`.

### 6.9 Panels
After v5.1 lands: `PanelsScreen` restyle (MahoutTopBar, tiles Radius.m, `SkeletonBlock` while a frame loads). No logic change.

### 6.10 Snackbars/toasts
All `SnackbarHost(snack)` → `MahoutSnackbarHost(snack)`; texts unchanged; no `Toast` added.

---

## 7. Performance protocol

### 7.1 `profile` build type (integrator, `app/build.gradle.kts`)
```kotlin
buildTypes {
    debug { isMinifyEnabled = false }
    release { … unchanged … }
    // Release-like for frame measurement and daily use; debug keystore so `adb install -r` replaces the debug build and keeps data.
    create("profile") {
        initWith(getByName("release"))
        isDebuggable = false
        isMinifyEnabled = false
        signingConfig = signingConfigs.getByName("debug")
        matchingFallbacks += listOf("release")
    }
}
```
`app/src/profile/AndroidManifest.xml` (NEW): `<application><profileable android:shell="true" tools:targetApi="29"/></application>`
(lets Perfetto/simpleperf attach; not needed for gfxinfo). `install.sh`: `VARIANT=${MOB8N_VARIANT:-debug}` → `assemble${Variant}` and
`app/build/outputs/apk/$VARIANT/app-$VARIANT.apk`. Caveats: `run-as com.mob8n` fails on `profile` (not debuggable) — verifiers switch back
to debug (`MOB8N_VARIANT=debug ./install.sh`, data kept) for DB/prefs inspection; Room's destructive fallback is off on `profile`
(release semantics).

### 7.2 Before measuring
`adb shell cmd package compile -m speed-profile -f com.mob8n` (applies the baseline profiles Compose ships via profileinstaller, like a Play
install); `settings get global animator_duration_scale` = 1; screen on, no other app animating; tablet on power.

### 7.3 Scenarios (reset → act → dump, each 3 runs, report the median)
```
S="-s 67240DLKX0092Z"; adb $S shell dumpsys gfxinfo com.mob8n reset
# … scenario …
adb $S shell dumpsys gfxinfo com.mob8n > <name>.txt              # "Janky frames: n (x%)", "90th percentile: n ms", "99th percentile"
adb $S shell dumpsys gfxinfo com.mob8n framestats > <name>-fs.txt
```
| # | Scenario | Action |
|---|---|---|
| P1 | Chat streaming | MiniMax-M2.7 default AI, thread open, send "Explain how Mahout runs a workflow in ~400 words with one table and one code block"; dump after the turn ends |
| P2 | Chat scroll | a 60-row conversation; 10 flings up/down (`input swipe 700 1400 700 400 120` and back) |
| P3 | Workflow list scroll | ≥ 30 workflows (seed/duplicate); 10 flings |
| P4 | Navigation | rail taps Dashboard→Workflows→Chat→Knowledge→Skills ×3, then open/close an editor 5× (Back) |
| P5 | Canvas run | open the seed editor, Run now, wait for completion (flowing dots + flashes) |
| P6 | Dashboard | pull-to-refresh ×3, scroll to bottom and back |
Coordinates come from one `uiautomator dump` per layout. Framestats per-frame: duration = `FrameCompleted − IntendedVsync` (columns by
header name); janky = duration > 16.67 ms (Pixel Tablet panel is 60 Hz; on a 120 Hz phone use its vsync period).

### 7.4 Thresholds (on `profile`)
Janky frames **< 5 %** and p90 **< 16 ms** for P1–P6; p99 < 32 ms reported (not gated). Debug numbers are recorded for comparison only.
Any miss → Perfetto trace of that scenario on `profile` (`profileable`), fix, re-measure; record in §12.

### 7.5 Compose rules for the hot paths (review checklist)
Stable `key`s + `contentType` on every lazy list (chat: "user", "assistant", "tool", "live", "note"); fast-changing state read in the lowest
scope (live turn in item lambdas, caret/pulse/phase in `graphicsLayer`/draw lambdas, elapsed tickers inside their own small `Text`);
`derivedStateOf` for scroll-derived booleans/counts; `remember` for parsing (`parseMarkdown(text)`, `resultsOf(rows)`), paths
(`drawWithCache`) and lambdas passed to items; no allocation per frame in draw code (reuse `Path`, no `listOf` in draw); no
`animateContentSize` on list items except the live one; `collectAsStateWithLifecycle` everywhere; `LiveTurn` publishes ≤ 25/s.

---

## 8. File ownership and sequencing

### 8.1 Lanes (zero overlap; paths under `app/src/main/java/com/mob8n/` unless noted)
| Lane | Owns (creates/edits) |
|---|---|
| **ui-foundation** | `ui/Theme.kt`, `ui/Motion.kt` (NEW), `ui/Kit.kt` (NEW), `app/src/main/res/font/manrope_variable.ttf`, `app/src/main/res/font/jetbrains_mono_variable.ttf`, `app/src/main/assets/licenses/Manrope-OFL.txt`, `app/src/main/assets/licenses/JetBrainsMono-OFL.txt`, tests `test/…/ui/ThemeContrastTest.kt`, `MotionTest.kt`, `KitLogicTest.kt` |
| **ai-streaming** | `ai/Sse.kt` (NEW), `ai/OpenAiCompat.kt`, `ai/ClaudeClient.kt`, `ai/Llm.kt`, `ai/Chat.kt` (live turn, `ChatImages`, `send(imagePath)`, `wrap(onDone)`), tests `test/…/ai/SseTest.kt`, `OpenAiStreamTest.kt`, `ClaudeStreamTest.kt`, `ThrottleTest.kt`, `ChatLiveTest.kt`, fixtures `app/src/test/resources/sse/*` |
| **ui-chat** | `ui/Chat.kt`, `ui/Markdown.kt`, `ui/ChatComposer.kt` (NEW), `ui/ChatApproval.kt` (NEW), `ui/ChatSlash.kt` (NEW, pure parser + `composeOutgoing` + `nextAnnouncement`), tests `test/…/ui/SlashTest.kt`, `MentionTest.kt`, `MarkdownBlocksTest.kt`, `AnnounceTest.kt`, `ChatUiLogicTest.kt` |
| **ui-screens** | `ui/App.kt`, `ui/Nav.kt`, `ui/Intro.kt` (NEW), `ui/WorkflowList.kt`, `ui/Dashboard.kt`, `ui/Editor.kt`, `ui/Canvas.kt`, `ui/Palette.kt`, `ui/ParamSheet.kt`, `ui/Widgets.kt`, `ui/RunLog.kt`, `ui/AiSettings.kt`, `ui/McpSettings.kt`, `ui/Permissions.kt`, `ui/Skills.kt`, `ui/Knowledge.kt`, `ui/Panels.kt` (after v5.1), `ui/Notes.kt`, `ui/Playlist.kt`, `ui/Bypass.kt`, `ui/BuildWithAi.kt`, tests `test/…/ui/NavTransitionTest.kt`, `RunOverlayTest.kt`, existing `BypassUiTest.kt`/`CanvasHitTest.kt` kept green |
| **integrator** | `app/build.gradle.kts` (profile), `app/src/profile/AndroidManifest.xml` (NEW), `app/src/main/res/values/themes.xml` + NEW `res/values/colors.xml` / `res/values-night/colors.xml` (window background `#F7F4EC` / `#0A1722` so no white flash at cold start), `app/src/main/res/values/strings.xml` (if any string moves), `install.sh`, `MainActivity.kt` (only if the device shows a scrim issue: `window.isNavigationBarContrastEnforced = false` on API 29+), `README.md`, `docs/DESIGN6.md` §12 |
Untouched by v6: `ui/ParamLogic.kt`, `ui/Drafts.kt`, `ai/NanoClient.kt`, `ai/Agent.kt`, everything under `engine/`, `core/`, `triggers/`, `apps/`, `logic/`, `data/`, `actions/`.

### 8.2 Parallelism
- **All four code lanes run in parallel** (wave A) against stubs: ui-chat and ui-screens paste §3.2 + §4 (signatures with trivial bodies) into
  their sandbox (stub `BrandFamily`/`MonoFamily` = `FontFamily.Default` / `FontFamily.Monospace`, since `R.font.*` only exists once
  ui-foundation lands the files); ui-chat also pastes the §5.1.5 types with `fun live(id) = MutableStateFlow<LiveTurn?>(null)` and a `send(…, imagePath)`
  overload. Stubs are never delivered. ai-streaming depends on nothing new. ui-foundation depends on nothing new.
- **Merge order (sequential, full `./build.sh` after each):** ui-foundation → ai-streaming → ui-chat → ui-screens → integrator. Any
  signature drift found at merge is fixed in the *consumer* lane's files, never by editing another lane's files.
- The integrator then runs the device plan (§10) and fills §12.

---

## 9. Tests (JUnit 4 + kotlinx-coroutines-test, pure JVM; `isReturnDefaultValues = true`)

### ui-foundation
- `ThemeContrastTest`: every `CONTRAST_PAIRS` entry ≥ its `min`; `LIGHT_ROLES`/`DARK_ROLES` contain all 30 M3 role names; `LIGHT_EXT`/
  `DARK_EXT` contain all §2.2 tokens; every `SIGNAL` value ≥ 3.0 on light `surface`, light `surfaceContainerHigh`, dark `surface`, dark
  `surfaceContainerHigh`; `contrastRatio(0xFFFFFFFF, 0xFF000000) == 21.0 ± 0.01`, `contrastRatio(x, x) == 1.0`.
- `MotionTest`: `motionReduced` truth table (scale 0/0.5/1 × toggle); `MotionScheme(true).spatial<Float>()` is `SnapSpec`, `.effect<Float>()`
  is `SnapSpec`, `.staggerMs(5) == 0`, `.loops == false`; `MotionScheme(false).staggerMs(10) == 210`, `.staggerMs(2) == 70`.
- `KitLogicTest`: `AnimatedCounter` digit diff helper (pure `digitChanges(from, to)`), `toneColors` total over `Tone`.

### ai-streaming (fixtures under `app/src/test/resources/sse/`)
- `SseTest`: framing — CRLF, multi-line `data:`, `:` comments, `event:` lines, blank-line boundaries, trailing event without blank line,
  `[DONE]`.
- `OpenAiStreamTest` — **golden equivalence**: for each fixture pair `X.sse` + `X.json` (the same answer non-streamed),
  `parseResponse(p, acc(X.sse).toResponse(), m)` equals `parseResponse(p, X.json, m)` (content, stopReason, raw echo, usage, model):
  `openai_text` (content + include_usage final chunk with `choices: []`); `openai_tools_parallel` (two tool calls, `id`/`name` only in the
  first chunk of each index, `arguments` split mid-token and mid-escape `\"`, interleaved indices, `finish_reason: tool_calls`);
  `minimax_reasoning_split` (`reasoning_details` deltas by index + content + one tool call + usage on the last chunk; echo keeps
  `reasoning_details`); `minimax_think_tags` (content with a lone `</think>` prefix → `streamVisible` and `parseResponse` strip it);
  `minimax_base_resp_error` (chunk with `base_resp.status_code 1008` → NodeException "insufficient balance" text); `openrouter_comments`
  (`: OPENROUTER PROCESSING` lines, `reasoning` deltas); `deepseek_reasoning_content`; `error_chunk` (`data: {"error":{"message":…}}` →
  NodeException, no fallback); `truncated_no_done` (EOF mid-stream → fallback signal). Plus delta emission order: `ToolStart` once per
  index, `ToolArgs` sizes sum to the argument length.
- `OpenAiFallbackTest` (pure policy fn `streamFailureAction(emittedAny, cause): Fallback {CACHE_AND_REDO, RESET_AND_REDO, THROW}`) and
  `quirkFor400` learns `no_stream_options` / `no_stream` only when those features were sent.
- `ClaudeStreamTest`: events deserialized with `com.anthropic.core.jsonMapper().readValue(line, BetaRawMessageStreamEvent::class.java)`
  from `claude_text_tool.jsonl` (message_start, text deltas, content_block_start tool_use, input_json deltas split mid-token, message_delta
  `stop_reason: tool_use` + usage, message_stop) → `BetaMessageAccumulator` → `parseMessage(toKx(JsonValue.from(msg)))` equals
  `parseMessage(claude_text_tool.json)`; `ClaudeStream.deltas` emits Text/ToolStart/ToolArgs in order; `claude_refusal.jsonl` → `check()`
  throws "Claude declined…". (If `jsonMapper` deserialization of beta events fails on the JVM, fall back to building events with the SDK
  builders; record in §12.)
- `ThrottleTest`: fake clock — 0, 10, 39 ms → one publish; 40 → publish; 1000 deltas over 1 s → ≤ 26 publishes.
- `ChatLiveTest` (drives `ChatRunner.drive` with a fake streaming `step` that calls `onDelta`): segments grow; tools go
  QUEUED→RUNNING→DONE via `wrap(onDone)`; denied ids → FAILED "denied by user"; `live` is `null` after each `persist`; persisted rows are
  identical with and without `onDelta` (the persistence contract test).
- `streamVisible` cases: `<think>abc</think>x` → `x`; `</think>x` → `x`; `x<think>partial` → `x`; `<mm:think>…</mm:think>x` → `x`.

### ui-chat
- `SlashTest`: `/` → all six; `/mo` → `/mode`; `/mode b` → `bypass` suggestion flagged `needsConfirm`; `/run Morn` filters workflows
  case-insensitively; `/unknown text` → null (sent as a message); `hello /mode` → null (not at start); cursor outside the command → null.
- `MentionTest`: `composeOutgoing("hi", [])` == `"hi"`; one of each kind renders the exact `Context:` line; quotes in names escaped as `\"`;
  duplicate chips collapse.
- `MarkdownBlocksTest`: headings, nested bullets, ordered lists, quotes, pipe table (ragged rows padded, alignment row skipped), fenced code
  with language, **unclosed fence → `Code(closed = false)`**, inline code inside table cells, never throws on 1 000 random strings.
- `AnnounceTest`: no announcement within 2 500 ms; sentence boundary after 2 500 ms → the new sentences; `done` flushes the remainder; nothing
  re-announced.
- `ChatUiLogicTest`: existing helpers (`cardChip`, `composerHint`, `parsePending`, `toolCallsInLastTurn`) unchanged outputs; `lastUserText`;
  unread-count helper; `showNavLabels`-style `subtitleVisible(fontScale)`.

### ui-screens
- `NavTransitionTest`: List→Editor PUSH, Editor→List POP, Dashboard→Skills FADE_THROUGH, Chat()→Chat(id) PUSH, Chat(a)→Chat(b) FADE_THROUGH,
  same screen NONE, Runs(wf)→RunDetail PUSH, McpSettings→AiSettings POP, Editor(w)→Build(w) PUSH; `showNavLabels(1.0f) == true`, `(2.0f) == false`.
- `RunOverlayTest`: no logs + running → triggers active; linear A→B→C with A logged → B active, edge A→B flowing; ERROR_ROUTED node activates
  only its error-port successor; FAILED node activates nothing; `running = false` → empty.
- `BypassUiTest`, `CanvasHitTest` unchanged and green.

### integrator
Full `./build.sh` (assembleDebug + testDebugUnitTest) and `./build.sh assembleProfile` green; test count ≥ previous + new.

---

## 10. Device plan (Pixel Tablet, Android 16, serial `67240DLKX0092Z`; MiniMax-M2.7 as Default AI, Claude second)
Never uninstall (data is live). Before each settings change, read the current value and restore it exactly afterwards.
1. `MOB8N_VARIANT=debug ./install.sh` (debug over v5.1): launches, no crash; Workflows landing; cold start shows the brand window
   background, no white flash.
2. Theme: light + dark (system toggle) screenshots of Workflows, Dashboard, Chat, Editor, Settings; compare to
   `docs/brand/mahout-ui-preview.html`. Wallpaper colours ON → M3 roles change, risk/Bypass/bubble colours do not; OFF restores.
3. Chat streaming (MiniMax): tokens appear progressively with caret; tool cards pending→running→done with live elapsed; approval dock in Ask
   (run_shell), Approve/Deny with haptic; dock survives process death (`am kill` while awaiting → reopen → decisions still possible).
4. Fallback: set the MiniMax base URL override to an HTTP endpoint that returns JSON regardless of `stream` (or a local `nc` stub) → answer still
   arrives; log shows "streaming failed … retrying without streaming" once, later calls skip SSE.
5. Claude: same prompt streams; stop mid-stream (Stop) → cancelled cleanly, "cancelled by user" results, no dangling rows.
6. Composer: `/mode bypass` → confirm dialog (Cancel = no change; Confirm = red banner + chip "Bypass · 60m"); `/run <wf>` → snackbar + run;
   `/panel`; `/clear` keeps settings; `@` mention → Context line in the sent message; photo picker (MiniMax-M3) → thumbnail in bubble, model
   describes it; voice → text in the draft.
7. Message actions: copy, retry, edit & resend, hide + undo; TalkBack custom actions present.
8. Navigation: fade-through between rail destinations, push/pop into editor, list scroll restored on Back; predictive back gesture shows the
   scale/shift and cancels cleanly; drawer on phone width.
9. **Phone width**: `adb shell wm size 1080x2400 && adb shell wm density 420` (→ 411 dp), repeat 3, 6, 8 (drawer, bottom bar, composer,
   dock at 60 % height); then `wm size reset; wm density reset`.
10. Reduced motion: in-app toggle → caret solid, no shimmer/pulse/dots, instant navigation; toggle off; then
    `settings put global animator_duration_scale 0` → same result with the in-app toggle off; restore the previous value.
11. Font scale: `settings put system font_scale 2.0` → Chat, Dashboard, Workflows, Settings, approval dock: nothing clipped, bar labels hidden,
    subtitle hidden; restore.
12. TalkBack: read `settings get secure enabled_accessibility_services`, **append** `com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService`
    with `:` (Mahout's own accessibility service must stay in the list), `settings put secure accessibility_enabled 1`; verify: streaming
    announces "Mahout is responding" then sentences ≤ every 2.5 s, dock buttons read "Approve run_shell", mode chip, jump FAB; restore the
    exact original string (or ask the user to toggle TalkBack with the volume-key shortcut instead).
13. Canvas: Run the seed workflow from the editor → active node pulse, flowing dots, success/fail flash; reduced motion static variant.
14. Dashboard: pull-to-refresh, counters roll, sparkline draws in, skeletons while loading; Bypass active → red Permissions card + banner.
15. **Frames**: `MOB8N_VARIANT=profile ./install.sh` (installs over debug, data kept), §7.2 prep, P1–P6 ×3 → table in §12 (profile vs debug).
    Switch back to debug before any `run-as` inspection.

---

## 11. Risks
| Risk | Mitigation |
|---|---|
| MiniMax streaming chunk shape for `reasoning_details` differs from the fixture assumption | Capture a real SSE with `curl -N` (key from Settings, never written to the repo/log) into a scratch file on day 1 of ai-streaming; fixtures are built from it; accumulator rule is "by index, text concat, first-wins" which tolerates either whole or fragmented entries; D7 fallback covers the rest. |
| A provider accepts `stream:true` but emits a non-standard SSE | D7 caches `no_stream`; user sees the non-streamed answer; logged once. |
| SDK streaming on Android (OkHttp SSE + `java.util.stream`) misbehaves | Stream API 24+, desugaring on; any streaming-only failure → `streamBroken` + non-stream path (today's proven path). |
| Recomposition storms while streaming long answers | 40 ms throttle, state read in item scope, block-keyed markdown, P1 gate in §7. |
| `FontVariation` weights ignored on some devices (experimental API) | Static TTF fallback (§2.5); visual check in step 2. |
| Approval dock hides context on small phones | 60 % cap + inner scroll; transcript still scrolls above it; Details expands per call. |
| Predictive back conflicts with drawer/edge gestures | Drawer gestures only when open; screen BackHandlers win; tested at phone width (step 8–9). |
| Dynamic colour lowers contrast of M3 roles | Default OFF; safety colours never dynamic; helper text says so. |
| TalkBack setup overwrites Mahout's accessibility service | Step 12 appends and restores the exact string. |
| `profile` build can't `run-as` | Documented switch back to debug; data kept across `install -r`. |
| v5.1 pass changes `ui/Panels.kt` under ui-screens | Panels restyle rebases on the landed file; restyle-only diff. |
| Hidden messages still reach the model | Snackbar says so; D12 marks it. |
| Image cache eviction | Placeholder chip; D18 marks it. |

---

## 12. Integration record (integrator fills; code wins)

Integration 2026-09-26 (merge order ui-foundation → ai-streaming → ui-chat → ui-screens (core + settings) → integrator). No sandbox stub
leaked (grep for stub markers, duplicate `LiveTurn`/`MotionTokens`/`Tone`/`UiPrefs`/`MahoutColors` definitions and `FontFamily.Default`
fonts: none; `Motion.kt`, `Kit.kt`, `Theme.kt`, `ai/Chat.kt`, `ai/Sse.kt` are the owning lanes' real files). No signature drift needed a
fix. `./build.sh` (assembleDebug + testDebugUnitTest) green: **666 unit tests, 0 failures** (576 → 666, 90 classes);
`./build.sh assembleProfile` green (profile APK: not debuggable, `profileable shell=true`, signed with the same debug certificate as
`app-debug.apk`, so `install -r` swaps them). No literal `tween(`/`spring(`/`infiniteRepeatable(` outside `Motion.kt`. `RegexIcuLintTest`
green. No new Gradle dependency.

### 12.1 Deviations
Integrator:
- `res/values[-night]/colors.xml` also hold `bool mahout_light_bars` so the platform theme's `windowLightStatusBar` is correct in dark mode
  before `enableEdgeToEdge()` runs (the parent theme stays `android:Theme.Material.Light.NoActionBar`; `windowBackground` and
  `colorBackground` = `#F7F4EC` / `#0A1722`). `MainActivity` unchanged (already calls `enableEdgeToEdge()`; the scrim fix is device-gated).
- `install.sh` rejects any `MOB8N_VARIANT` other than `debug`/`profile`.
- Added the §1.2 ponytail marks the lanes had not placed: D8 in `ai/Chat.kt` (`checkTarget`), D12 + per-session Hide in `ui/Chat.kt`
  (comments only).

ui-foundation: `MahoutColors` has one extra field `cardLine` (L1 hairline: light 60 % `outlineVariant`, dark transparent); internal helpers
`loopSpec`, `caretAlpha`, `risePx` (12 dp rise via system density, ponytail); `rememberBlink` derives alpha from a linear phase through the pure
`caretAlpha` (same visible 500/500 ms with 120 ms fades); `toneColors` maps to container pairs (PillButton keeps its own filled mapping,
Info = tertiary); StatusPill/RiskPill use `Radius.s`; `CONTRAST_PAIRS` > 100 pairs (adds onSurface/onSurfaceVariant on card, inversePrimary on
inverseSurface); `MahoutTopBar` itself hides the subtitle above font scale 1.5.

ai-streaming: `wrap(tool, decision, bypassActive, onDone = …, onRun)` (onDone before the trailing onRun so `ChatLoopTest` still binds);
Claude cancellation closes the `StreamResponse` via `suspendCancellableCoroutine.invokeOnCancellation` (the §5.1.4 `invokeOnCompletion`
fires only after the blocking read returns) and `mapClaudeErrors` rethrows `CancellationException`; Claude failures inside
`createStreaming` (connect/HTTP/SDK retries) and `SseException` are real errors, only failures while iterating trigger D7;
`OpenAiStreamAccumulator(providerLabel, tolerateCumulative = false)` — true for MiniMax, merges a chunk that starts with the whole text so far
(ponytail); a repeated full `function.name` fragment is not appended again; a 200 that is neither SSE nor JSON caches `no_stream` and
redoes; a header-phase `SocketTimeoutException` is a StreamFailure (D7) not a timeout error; image notes "Image not sent: <label> cannot read
images" / "Image not sent: the attached image could not be read"; `streamVisible` also hides a partial trailing tag (ICU-safe `{0,8}`);
`ClaudeStreamTest` deserializes events through `ClaudeClient.jv(json).convert(BetaRawMessageStreamEvent::class.java)` instead of
`jsonMapper().readValue`; the Claude fallback is not logged (no log parameter on `ClaudeClient.step`); MiniMax fixtures are built from the
documented format (no real capture: the key is never used on the Mac) — the device phase checks the first live answer for duplicated text.
NanoClient untouched (D8).

ui-chat: `/clear` in a Bypass chat starts the new chat in Inherit (snackbar says so; Bypass never reaches a new conversation without its
own dialog); a known command with a missing/unknown argument keeps the draft and shows a hint; picking a suggestion equal to the draft sends
it; Details starts expanded for single-call batches, collapsed for multi-call ones, and the expired-draft warning is always visible; the dock's
4 dp stripe uses the on-container risk colour (containers are too pale on L3); message text is not in a `SelectionContainer` (long-press =
menu; Copy covers it; code blocks stay selectable); StatusRow keeps a 1 dp min height so the polite live-region node stays (ponytail); inline
code uses `CodeSmallStyle` on `surfaceContainerHighest`, and inside user bubbles links/code use the bubble ink (teal fails on navy);
`liveText` trims and skips blank segments; an image goes with the next text message (no image-only send); `ChatScreen.onBack` kept for
signature compatibility (phones use the drawer menu; system back still pops).

ui-screens (core): Editor keeps an M3 `TopAppBar` (its title is the editable name field; `MahoutTopBar` takes a String) with PillButton
actions; the phone shell uses `contentWindowInsets = WindowInsets(0)` + `padding(pad).consumeWindowInsets(pad)` (screens own their status/nav
insets, the canvas draws under the nav bar); tablet: the whole right area fades through by rail section, only the detail pane is
depth-animated, predictive back transforms the right area; PUSH adds `scaleIn(0.96)` (D15), POP is the exact reverse with the leaving screen on
top; above font scale 1.3 the phone bar drops labels entirely (icon carries the name); flowing dots use a `withFrameNanos` ticker (no
`rememberInfiniteTransition`); `runOverlay` identifies triggers by the `trigger.` type prefix; Dashboard shows "unavailable" when a knowledge
read fails, hides the "Mode:" line while global Bypass is live, and uses `titleLarge` + tnum for the three usage counters (fit a phone card);
Intro "Skip" and "Get started" both call `onDone`; SwipeRow keeps a 500 ms guard against a double confirm (ponytail). Shared elements and
parent peek not built (D15, D14).

ui-screens (settings): Appearance card adds the line "Mahout follows the system light / dark setting." (D20); new Playlist/Panels empty-state
bodies; the Knowledge workspace listing is kept while collapsing (no "No files yet" flash); section heads use `titleSmall` in
`onSurfaceVariant`; Default AI / Decision engine chips are non-clickable StatusPills (contentDescriptions unchanged); shared
`internal fun RotatingChevron` lives in `AiSettings.kt`; Bypass minutes use NumericStyle's family + tnum at the banner's size; no WEB-tile
skeleton in Panels (would need `onPageFinished` in the hardened client — v5.1 hardening, MATCH_PARENT layout, same-site redirects and the
blocked-navigation tile are byte-identical), the SkeletonBlock shows only for IMAGE tiles.

### 12.2 Frame measurements (profile vs debug, median of 3)
Pixel Tablet `4B291HFH80ETW2` (panel 60 Hz), landscape 2560x1600 @ 272 dpi, dark theme, animator scale 1, `profile` after
`cmd package compile -m speed-profile -f` (baseline + snapshot profile). `dumpsys gfxinfo` summary values (janky = missed frame deadline).
| Scenario | Janky % (profile) | p90 ms (profile) | p99 ms (profile) | Janky % (debug) | p90 ms (debug) | Gate (<5 %, p90 <16) |
|---|---|---|---|---|---|---|
| P1 Chat streaming (MiniMax-M3, ~400 words + table + code) | 0.24 | 13 | 22 | – | – | pass |
| P2 Chat scroll (10 flings) | 1.01 | 13 | 20 | – | – | pass |
| P3 Workflow list scroll (32 workflows, 10 flings) | 0.00 | 9 | 15 | 56.96 | 77 | pass |
| P4 Navigation (rail ×3 + editor open/back ×5) | 3.83 | 18 | 27 | 25.71 | 57 | janky pass, p90 miss |
| P5 Canvas run (Manual → delay 3 s → delay 2 s → log) | 0.29 | 12 | 17 | – | – | pass |
| P6 Dashboard (pull-to-refresh ×3, scroll ×4) | 0.90 | 19 | 21 | – | – | janky pass, p90 miss |

P4/P6 p90: under 5 % of frames miss a deadline, but frame latency sits just above one vsync. P6 is GPU/RenderThread-bound: gpu p50 9 ms and
p90 13 ms against a UI thread of about 2 ms, with no recomposition in the hot path. P4's slow frames are first frames of newly composed destinations
(recompose/layout 5–15 ms). The first measurement cycle before the profile compile was worse (P4 6.3–6.9 %). Fix attempts: the sparkline draws its stable
paths once drawn in (kept; no measurable change); SectionCard without the Surface clip (GPU worse, reverted). Left open (§12.3).

### 12.3 Device phase results (2026-09-26, device verifier)
- Install: debug `install -r -g` over v5.1. Room `user_version` 3 unchanged, FATAL 0, data kept. The intro did not show, because the user had already
  onboarded. The profile build is installed at the end for daily use; future `run-as`/DB verification must reinstall debug first.
- Streaming (MiniMax-M3, `stream` in every POST log line). No "streaming failed"/fallback line in any call, so D7 never triggered. Text grows
  progressively with the caret (frames 250–300 ms apart). Live MiniMax shape: `content` is incremental. The accumulator is right and there is no
  duplicated text. Seen in some answers (for example "orch tile\"…", "there, glad you're here!"): the first word(s) of the answer arrive at the
  end of `reasoning_details` / `reasoning_content` ("…information.\"T"), not in `content`. This is a provider-side split at the
  think/answer boundary, and the non-stream path shows it too in older rows. Not fixed: needs a captured stream to rule on. See Remaining.
- Chat: markdown table and code block, and Copy code ("Copied"). The tool card `list_workflows` runs in Auto. The approval dock in Ask (`enable_workflow`)
  enters above the composer with the DESIGN4P strings; Deny left Torch tile disabled; the dock survives phone width at 200 % font. Stop mid-stream
  persisted the user row and no partial assistant row. Retry and Edit & resend append new rows. `/new`, `/mode bypass` opens the
  confirmation dialog and Cancel keeps the mode. `/run Torch tile` starts the run and a "Run started · View" snackbar, and the run fails honestly
  ("Needs Camera flash"). An @-mention adds the `Context: workflow "Torch tile" [seed-8].` line. Photo picker: the image went into
  `cacheDir/chat-images`, the thumbnail shows in the bubble, and M3 described it correctly. The voice button opens the system recognizer
  and Back cancels it.
- Motion: fade-through and push transitions; the predictive back gesture shows the scale, shift and corner transform, and it cancels and commits
  cleanly; swipe-to-run on a list row; pull-to-refresh indicator; the canvas pulses the running node with flowing dots on the edge. Reduced motion
  (system scale 0 and the in-app toggle): the canvas shows the static 3 dp ring and edge with identical frames, and navigation is instant. Both
  switches were restored.
- Phone width (1080x2400 @ 420 dpi): the bottom bar, drawer and composer fit, and the dock stays under the cap with no double inset padding.
  200 % font scale: bar labels and the chat subtitle hide, and chat and dashboard do not clip.
- Fixed in this phase: (1) the status bar and tablet caption handle kept the previous theme's icon colour after a light/dark switch
  (`uiMode` is in `configChanges`), fixed in `MainActivity.onConfigurationChanged`. (2) A streamed answer showed twice for about 400 ms at
  handoff, because Room's rows landed before `live` went null; `ui/Chat.kt` now compares against the rows seen at the last live publish.
  (3) A double brand header showed while thinking; (4) live-item growth is now bottom-aligned (`animateContentSize(alignment = BottomStart)`).
  (5) At 200 % font on a phone, the SectionCard title and trailing pill squeezed the title to 3 letters per line; they now wrap with FlowRow in
  `ui/Kit.kt`. (6) At 200 % font, ModeSelector clipped "Bypass"; above 1.3× it now uses two segments per row (`ui/Bypass.kt`).
  (7) The sparkline draws its stable paths.
- Not run: TalkBack (labels checked through `uiautomator` content-descriptions instead), Claude streaming, D7 fallback via a stub endpoint,
  dock process-death, wallpaper colours ON.

### 12.4 Fixer pass M1–M7 (2026-09-26 late, tablet `4B291HFH80ETW2`, debug build for the A/B)
- **M1 MiniMax lost first words: measured, then repaired in both modes.** New setting `settings["minimax_reasoning_split"]` (Boolean; a
  "true"/"false" String also accepted; default `true`, unchanged), applied in `Providers.effective` on every target resolution, and a
  "Separate reasoning" switch in Settings > AI > MiniMax. Debug-only logcat line `minimax split=<b> first40=<visible>` (gated on
  FLAG_DEBUGGABLE, never the key or the full content). A/B on MiniMax-M3, same prompts: split=true lost the first word in 3 of 12 answers
  ("don't have…", "common reasons…", "me check…"). split=false (inline `<think>`) lost it in 1 of 19 ("day at 08:00…"). The raw echoes
  show the same model behaviour in both modes: the answer's first token is glued to the end of the reasoning, right after its last
  punctuation (`…two concise reasons.Two` + content `common reasons…`; inline `…One sentence.Every\n</think>\n\nday at 08:00…`).
  So reasoning_split=false is **not** the fix. `OpenAiCompat.repairGluedAnswer` (MiniMax only) moves the fragment back when the answer
  starts in lower case and the reasoning ends in punctuation plus a capitalised fragment of 12 letters or fewer. A single letter other
  than I/A joins without a space ("T"+"orch"). An answer that is only punctuation (the reasoning's own period after `</think>` before a
  tool call) is dropped. The raw message is still echoed verbatim. split=false also works end to end: the live view hides the `<think>`
  block, "Show thinking" gets the inline text (`streamThinking`), the final text is stripped (a leading unclosed block too), history
  echoes the raw content with `<think>`, and usage is unchanged. Seen in both modes and not fixed here: after `list_runs`, "What ran
  today?" ended with an empty final answer (the model's content was empty after `</think>`).
- **M2** Dashboard Workflows card: rows only for workflows that exist (`Stats.perWorkflow`). Runs of deleted workflows are one muted line,
  "Deleted workflows · N runs" (`Stats.deletedRuns`, 30 days), and tapping it opens All runs. No run rows are touched. The line waits
  for the workflows flow to load, so it never flashes.
- **M3** Approval dock: a one-line preview is shown once, without the repeated tool name (`summaryLine`), and wraps to 3 lines with no
  Details toggle. Details only appear when the preview has more than that line (`previewHasDetails`), and an expanded row blanks
  its summary.
- **M4** `install.sh`: `MOB8N_SERIAL`, else the single `adb devices` entry, else exit 1 with the device list.
- **M5** AI settings Default AI: above 1.3× font scale, the Model field is full width and Fetch models moves below it.
- **M6** `Mob8NTheme` sets `isAppearanceLightStatusBars/NavigationBars = !dark` in a SideEffect, so the bar icons follow the theme that
  is drawn, not what `enableEdgeToEdge` inferred. Not verified on the device in light mode, because that needs a system theme switch.
- **M7** Cheap wins only: the gate checks (binder calls), `Shell.status()` and the WebView provider lookup now run off the main thread
  (`rememberGateCensus`: one pass, each gate checked once, per-row badges from the same census) instead of in the first-compose frame
  of Dashboard/Workflows. `enterOnce` adds no graphicsLayer to items that do not animate. Re-measured on profile (speed-profile compiled,
  3 runs): P6 janky 1.8–2.1 %, p90 19 ms in all 3 runs, p99 26–30 ms, so the GPU-bound PTR frame is unchanged. P4 was run as rail taps
  only (Dashboard→…→Skills ×3, no editor open/back), which has proportionally more first-compose frames: janky 6.1–6.9 %, p90 19–23 ms.
  That is not comparable with the §12.2 row, and no improvement is claimed. Both p90 gates stay open.

### 12.5 Device verifier v6.1 (2026-09-27, tablet `4B291HFH80ETW2`, debug for the A/B, profile for frames)
- **M1 decided: default `minimax_reasoning_split` = false** (`Providers.MINIMAX_SPLIT_DEFAULT`; the stored pref and the Settings switch
  still win). A/B on MiniMax-M3, one fresh chat per mode, the same 8 prompts (exact reply, "start with Banana", "Result:", "- One" bullet,
  list my workflows (tool call + answer), "Certainly", "Twelve monkeys", "Yes/No"). Displayed answer intact: split=true 6 of 8 prompts,
  split=false 8 of 8. Raw boundary errors (from the verbatim echo): split=true 3 of 9 turns, and two of them are not the "glued after
  punctuation" shape, so `repairGluedAnswer` cannot fix them: the reasoning's tail spilled into content (reasoning `…tool to fetch` +
  content `them.` shown as the tool-call preamble) and the bullet marker went to reasoning (`…no tools needed.-` + `One\n- Two`, shown
  without the "- "). The third, `…concisely.You` + content `have 11 workflows…` (no leading space), was displayed as **"Youhave"**: the
  split-form whitespace rule (§12.4 fixer 7) assumes a leading space that MiniMax does not send. split=false: 1 of 9 raw glues
  (`reply.Twelve\n</think>\n\nmonkeys`), repaired to "Twelve monkeys jumped.". With the earlier 3/12 vs 1/19 that is 6 of 21 vs 2 of 28.
  Latency is equal (stop 0.9–2.8 s both modes). After the switch (pref key removed): 4 more prompts, all intact, `split=false` in the log.
  No `<think>` text in any dump or screenshot during streaming. Tool calls work in both modes (Ask dock, Deny in split=true, Approve in
  split=false). Model behaviour seen once in split=false: "disable Torch tile" answered "Done — Torch tile is disabled." without a tool call.
- **M2** pass: the Workflows card lists only existing workflows (10 rows) plus "Deleted workflows · 12 runs" (DB: 12 runs of 9 deleted ids
  in 30 days); tapping it opens All runs. **M3** pass: the dock shows `disable_workflow` and one line "Disable workflow seed-8".
  **M5** pass (1080x2400 @ 420 dpi, font 2.0): Model field full width, Fetch models below. **M6** pass: light mode status bar icons dark on
  cream after a live switch and after a cold start. Display, font scale 0.85, density 272 and night mode yes restored.
- Frames, profile after `cmd package compile -m speed-profile -f`, summary values: P4 (rail ×3 + editor open/back ×5) median of 3 warm runs
  janky 5.41 %, p90 19 ms, p99 46 ms (the 3 runs right after the compile: 5.77–7.99 %, p90 19–26 ms); P6 median janky 1.13 %, p90 19 ms,
  p99 24 ms (unchanged); P1 streaming janky 0.16 %, p90 13 ms, p99 22 ms. P4 and P6 p90 gates stay open; P4 janky now sits just above 5 %.
- Device note: reinstalling and then `kill -9` within seconds left `UiAutomationService` stuck in "Binding services" and `uiautomator dump`
  returned a null root; another `install -r` rebinds it. Wait for "Bound services" before killing the process.

### 12.6 Post-verification tweaks (2026-09-27)
- `OpenAiCompat.repairGluedAnswer`: split mode drops the content's leading space (device capture `…concisely.You` + `have 11 workflows`), so the join is decided by a length heuristic (a lone capital other than I/A is a word piece, longer fragments are words; apostrophe-led content joins without a space). Known ceiling: `Th`+`ere` → `Th ere`. Only reachable with *Separate reasoning* turned on; the default is inline `<think>` since the v6.1 A/B.
- Operator rule tightened: every change (run, enable, disable, save, delete, write) needs its tool call in the same turn; the model must not report a state change without a confirming tool result (seen once on MiniMax-M3: "Done — Torch tile is disabled." with no tool call).
- Gate: 674 unit tests green; profile APK reinstalled on the Pixel Tablet (`install -r -g`, speed-profile compiled), FATAL 0.
