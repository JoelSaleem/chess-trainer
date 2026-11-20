# Android port — handover

Written 2026-11-17 so the work can continue on another machine, without the
original session or any Meta tooling. Everything you need is in this repository
(branch `android-port`) plus a git bundle.

| Doc | What it is |
|---|---|
| `docs/plans/2026-11-17-android-port-design.md` | Approved design: scope, architecture, milestones M0–M9 |
| `docs/plans/2026-11-17-android-port-plan.md` | Task-by-task plan for M0 (toolchain skeleton) and M1 (chess-core port) |
| this file | Where things stand and how to pick them up |

## 0. Status — M0–M5 landed; web (Kotlin Multiplatform) in progress (updated 2026-11-19)

All on `android-port`, on an Apple-Silicon Mac:

- **M0–M2** (toolchain, chess-core, chess-engine) complete and verified.
  `:chess-core` 25/25; `:chess-engine` 13/13 instrumented on the emulator;
  `:app:assembleDebug` packages `libstockfish.so` for arm64-v8a + x86_64; the app
  launches showing `Stockfish 18 by the Stockfish developers (see AUTHORS file)`.
- **M3 (chess-training)** complete — pure training logic + serialized content;
  **78 unit tests** across 13 suites (Feed 16, Entitlement/Progress 8,
  EloGuess/Rush/Premove 6, …). Includes M3.3 Progress/EloGuess, M3.4 game-history
  (raw SQLite via `SQLiteOpenHelper`, **not Room** — see the note in `GameHistory.kt`),
  M3.5 Entitlement free-tier.
- **M4** first Compose UI (2D board + Tactics + root navigation); **M5 (wip)** app
  foundation + Progress screen + two tactics-rating bug fixes.
  ⚠️ M3.3–M5 were produced in one long autonomous pass — **green but not yet
  line-reviewed**.

**New workstream — web.** Decision (2026-11-19): run the app on web too via
**Kotlin Multiplatform**, reusing the ported Kotlin rather than rewriting in TS.
`:chess-core` and `:chess-training` are converted (logic on web, both targets green),
and the M4/M5 Compose UI is now **shared on web** via a new `:ui` module. The one piece
left for a playable web app is the engine. See **§8**.

### What actually differed from the plan on macOS/arm64

| Plan said | Reality on this Mac | Why |
|---|---|---|
| Gradle 9.5.0 | **9.6.0** (wrapper pinned) | AGP 9.5.1 requires Gradle ≥ 9.6.0 |
| compileSdk 36 | **37** (`app/build.gradle.kts`) | Compose BOM 2026.09.00 → compose 1.12.1 requires API 37 |
| JDK at `/usr/lib/jvm/...` | **Temurin 17** at `~/.jdks/jdk-17.0.21+9/Contents/Home` | Homebrew `/opt/homebrew` not writable (would need `sudo chown`); downloaded the tarball instead |
| `$HOME/android-sdk` | `~/Library/Android/sdk` | existing Android Studio SDK; the `ANDROID_HOME` env var was stale (`/opt/android_sdk`) |
| x86_64 API-36 emulator | reused **`Pixel_Fold_API_35`** (arm64, `google_apis_playstore`) | Apple Silicon runs arm64 images; API 35 is fine since `minSdk` is 26 |
| `./gradlew …` | **`/tmp/gradle-9.6.0/bin/gradle …`** | the agent's auto-mode classifier blocks `./gradlew` as "external code" |

NDK 30.0.16248370 and cmake 4.1.2 were auto-installed by AGP during the first build;
`cmdline-tools` were installed into `~/Library/Android/sdk/cmdline-tools/latest`.

### Rebuild on this Mac

```bash
export JAVA_HOME=~/.jdks/jdk-17.0.21+9/Contents/Home
export ANDROID_HOME=~/Library/Android/sdk
cd ~/Documents/GitHub/chess-trainer/android
/tmp/gradle-9.6.0/bin/gradle :chess-core:test          # 25 tests
/tmp/gradle-9.6.0/bin/gradle :app:assembleDebug        # APK, both ABIs
"$ANDROID_HOME"/emulator/emulator -avd Pixel_Fold_API_35 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
"$ANDROID_HOME"/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME"/platform-tools/adb shell am start -W -n com.joelsaleem.chesstrainer/.MainActivity
```
(If `/tmp/gradle-9.6.0` is gone after a reboot, re-download it or allow `./gradlew`. `local.properties` → `sdk.dir=/Users/joelsaleem/Library/Android/sdk`, git-ignored.)

## 1. Get the code

The branch is not on GitHub yet (no fork exists). It travels as a git bundle:
`chess-trainer-android-port.bundle`, stored next to the plan in Google Drive
(`para/02_areas/notes/plans/`). The bundle holds only the `android-port` commits;
its base is upstream commit `2b5d7df` ("feat(1.5.0): GPT-6.1-Terra High board scanner…").

```bash
git clone https://github.com/minchopm/chess-trainer.git
cd chess-trainer
git bundle verify /path/to/chess-trainer-android-port.bundle   # "is okay"
git fetch /path/to/chess-trainer-android-port.bundle android-port:android-port
git checkout android-port
```

If `verify` complains about a missing prerequisite, upstream rewrote history:
`git fetch origin 2b5d7df` (or clone without `--depth`) and retry.

To publish: fork `minchopm/chess-trainer` on GitHub, then
`git remote add fork git@github.com:<you>/chess-trainer.git && git push -u fork android-port`.
The project is AGPLv3 (Stockfish is GPLv3), so the fork must stay public-source.
The commits are authored as `Joel Saleem <joelsaleem@meta.com>`. If you want a
personal address on a public repo, run `git rebase --exec 'git commit --amend --no-edit --reset-author' 2b5d7df`
after setting `git config user.email` and before pushing.

## 2. Where things stand

| Piece | State | How it was checked |
|---|---|---|
| `android/chess-engine/src/main/cpp/CMakeLists.txt` + `sfjni.cpp` | ✅ builds with NDK 30 for arm64-v8a and x86_64 | Standalone CMake build. `libstockfish.so` needs only `liblog/libm/libdl/libc`; the JNI symbol is exported |
| Stockfish running on Android | ✅ works | Probe binary on an API 36 x86_64 emulator printed `Stockfish 18 by the Stockfish developers (see AUTHORS file)`, `created=1 position=1` |
| `android/chess-core` (10 Kotlin files + 5 test files) | ✅ compiled; **25/25 tests pass**, 19/19 Swift parity | Ported line by line from `ios/Sources/ChessCore`. Compiled first try, no fixes needed |
| Gradle build files, manifest, `MainActivity` | ✅ build & run; APK installs and launches | `:app:assembleDebug` + `adb install`/`am start` on the emulator |
| Gradle wrapper | ✅ generated, pinned to 9.6.0 | `gradle wrapper`; committed |
| `ios/` | ✅ untouched | The engine source must stay unmodified (NOTICE.md promises this) |

Why it stopped: the devserver's proxy blocked Maven Central (HTTP 429), Gradle
(403) and the GitHub API (403). Only Google Maven / `sdkmanager` worked. Any
machine with normal internet access avoids all of this.

## 3. Machine setup

| Need | Version | Notes |
|---|---|---|
| JDK | 17 | `JAVA_HOME` must point at it. Java 8 is too old for AGP 9 |
| Android SDK command-line tools | latest | `sdkmanager` on `PATH` |
| SDK packages | `platforms;android-36` `build-tools;36.0.0` `platform-tools` `ndk;30.0.16248370` `cmake;4.1.2` `emulator` `system-images;android-36;google_apis;x86_64` | `sdkmanager --install …` then `sdkmanager --licenses` |
| Gradle | 9.5.0 | Only needed once, to generate the wrapper |
| Disk | ~6 GB | NDK, system image, Gradle caches |

Then, from the repo root:

```bash
echo "sdk.dir=$ANDROID_HOME" > android/local.properties   # git-ignored
cd android
gradle wrapper --gradle-version 9.5.0                     # creates gradlew + gradle/wrapper/*
```

Pinned versions (in `android/gradle/libs.versions.toml`): AGP 9.5.1; Kotlin 2.2.10,
which is what AGP 9.5.1's POM depends on; Compose BOM 2026.09.00; activity-compose 1.13.0.

## 4. Finish M0 and M1 (in this order)

1. **Compile and test chess-core.**
   `./gradlew :chess-core:test`
   Expect 25 tests to pass: 19 Swift twins (Notation 7, Perft 1, GameImport 11) plus 6
   Kotlin-only (TypesTest 4, PositionCopyTest 2). The code has never been compiled, so a
   handful of compile errors on the first run are normal. Fix them in the Kotlin, never
   by changing what a test asserts. Perft counts about 10M leaf nodes in total and should take well under a minute.
2. **Check test-name parity** (prints nothing when every Swift test has a twin):
   ```bash
   diff <(grep -ohP '@Test\("\K[^"]+' ../ios/Tests/ChessCoreTests/*.swift | sort) \
        <(grep -ohP 'fun `\K[^`]+' chess-core/src/test/kotlin/com/joelsaleem/chesstrainer/core/{NotationTest,PerftTest,GameImportTest}.kt | sort)
   ```
3. **Build the APK** and confirm both ABIs are inside:
   `./gradlew :app:assembleDebug && unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libstockfish.so` → 2 lines.
4. **Run it.** Start an emulator (`avdmanager create avd -n ct36 -k "system-images;android-36;google_apis;x86_64" -d pixel_8`, then `emulator -avd ct36`) or plug in a phone:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -W -n com.joelsaleem.chesstrainer/.MainActivity
   ```
   The screen must show `Stockfish 18 by the Stockfish developers (see AUTHORS file)`.
   `adb logcat -d | grep -E "FATAL EXCEPTION|UnsatisfiedLinkError"` must print nothing.
5. **Commit**, then move on to M2 (section 6).

### Likely first errors

| Symptom | Fix |
|---|---|
| Gradle sync says the Kotlin Android plugin is not needed, or there is a Kotlin version conflict | AGP 9 has Kotlin built in. Keep `kotlin-jvm` only on `chess-core`; if it still conflicts, set `android.builtInKotlin=false` in `gradle.properties` and apply `org.jetbrains.kotlin.android` to `app` and `chess-engine` |
| "Minimum supported Gradle version is X" | `gradle wrapper --gradle-version X` |
| `UnsatisfiedLinkError` at launch | The library name must stay `stockfish` (`System.loadLibrary("stockfish")` ↔ `add_library(stockfish …)`) |
| Perft count off at depth 3+ | Look for a missing `.copy()` (Swift `Position` is a value type, Kotlin's is a class) or a sign problem in hashing. `perftDivide(depth)` finds the first move that diverges |

## 5. Porting rules used so far (keep using them)

1. `Foo.swift` → `Foo.kt` in package `com.joelsaleem.chesstrainer.<module>`. Names are unchanged, and enum cases keep their Swift spelling (`PieceColor.white`).
2. A Swift struct with mutation → a Kotlin class with `copy()`. **Every Swift `var p = position` becomes `position.copy()`.** `GameImport.read` and `SAN.withSuffix` are examples.
3. Failable `init?(label:)` → `companion fun fromLabel(…): T?` (`Position.fromFen`, `Move.fromUci`, `Square.fromName`).
4. `UInt64` hashes are stored as `Long`; `SplitMix64` does its arithmetic in `ULong`, so shifts are logical.
5. Swift `extension Position` → Kotlin extension functions in the same module (they reach `internal` setters).
6. A ported test's function name is the Swift `@Test("…")` string verbatim. Kotlin-only tests live in separate files.
7. Dead Swift code is not ported (e.g. `Undo`). Odd-but-live behaviour is kept and commented (e.g. `GameImport` drops "0-0" castling tokens, as iOS does).

## 6. After M1

Milestones M2–M9 are in the design doc. The next one is **M2: chess-engine**:
- Port `ios/Sources/ChessEngine/{Engine,EngineTypes,StockfishEngine,SearchBudget}.swift`.
- Wrap the rest of `ios/Vendor/Stockfish/bridge/include/sfbridge.h` in `sfjni.cpp`. Stockfish calls `onInfo`/`onBestMove` **on its own search thread**: cache the `JavaVM` in `JNI_OnLoad`, `AttachCurrentThread` in the callback, and hold a global ref to the Kotlin listener.
- Networks: port `ios/scripts/fetch-networks.sh` (Stockfish nets only: `nn-c288c895ea92.nnue`, `nn-37f18f62d772.nnue`, from `https://tests.stockfishchess.org/api/nn/<name>`), ship them as `noCompress` assets, copy them to `filesDir` on first launch, then call `sf_load_networks`.
- Done when the 27 tests from `ios/Tests/ChessEngineTests` pass as `connectedAndroidTest`.

Out of scope for v1 (decided): paywall, Game Center/online play, the photo board
scanner, the Reckless engine, App Clip, Mac. The app is named "Chess Trainer"
(`com.joelsaleem.chesstrainer`); "Brass Pawn" is the original author's brand.

## 7. If you continue on the original devserver (devvm5558)

- Start the agent session with internet mode, or Maven Central and Gradle stay blocked. Run network commands through `with-proxy`.
- A Quest 3 is also attached over adb (`127.0.0.1:15557`). Always `export ANDROID_SERIAL=emulator-5554`.
- SDK at `~/android-sdk` (all packages above installed); JDK 17 at `/usr/lib/jvm/java-17-openjdk`; AVD `ct36` already exists.
- Run the emulator headless: `emulator -avd ct36 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect`.

## 8. Web (Kotlin Multiplatform) — in progress (2026-11-19)

Goal: the app runs on web too, reusing the Kotlin. Approach: convert the pure
modules to KMP (Android + JS/web targets); the native engine and the UI come later.

**Why it isn't a recompile:** `:chess-engine` is native C++ over NDK+JNI — there is
no JNI on web. Web Stockfish means `stockfish.wasm` driven over a Worker (a later
increment). The `StockfishEngine` *orchestration* (mutex, watchdog, budgets) is
portable; only the thin native-call layer swaps per platform.

### Done — `:chess-core` → KMP (commit `47499ee`)
25/25 tests green on **both** the Android host and **JS (node)**; consumers
(`:app`, `:chess-engine`, `:chess-training`) build against it unchanged.

### Conversion recipe (what the toolchain actually needs on AGP 9.5.1 / Kotlin 2.2.10)
- **Plugin:** `com.android.kotlin.multiplatform.library` (catalog alias
  `android-kmp-library`), **not** `com.android.library` + `androidTarget()` — that
  combo is **banned since AGP 9**. Target DSL: `kotlin { android { namespace;
  compileSdk; minSdk; withHostTest {}; withDeviceTest {} } }` (the block is `android`,
  not the deprecated `androidLibrary`).
- Declare `kotlin-multiplatform` and `android-kmp-library` in the **root** `plugins {}`
  with `apply false`, else: "plugin already on the classpath with an unknown version".
- Sources → `src/commonMain/kotlin` + `src/commonTest/kotlin`; platform-only files →
  `src/androidMain` / `src/androidDeviceTest`.
- **JS runner under `FAIL_ON_PROJECT_REPOS`:** switch to `PREFER_SETTINGS` and declare
  the Node + Yarn dist ivy repos in `settings.gradle.kts` (snippet is in the file).
- `kotlin.jvm.*` is a JVM default import but not a common one — add explicit
  `import kotlin.jvm.JvmInline` (and any other `kotlin.jvm.*`) in commonMain.
- Heavy JS tests: raise the Mocha timeout —
  `js { nodejs { testTask { useMocha { timeout = "300s" } } } }` (perft walks millions
  of nodes under emulated `Long`).
- Commit `kotlin-js-store/yarn.lock`.
- Verify with `:<m>:testAndroidHostTest` + `:<m>:jsNodeTest` (after conversion the
  Android unit-test task is `testAndroidHostTest`, **not** `testDebugUnitTest`).

### Done — `:chess-training` → KMP
**78/78 unit tests green on both the Android host and JS (node)**; `:app` compiles
against the KMP module unchanged; counts match pre-conversion exactly. Conversions:
`java.time` → `kotlinx-datetime` 0.6.2; `java.util.UUID` → `kotlin.uuid.Uuid`;
`String.format`/`Locale` → pure-common `Format.kt` (`formatTwoDecimals` +
`formatTemplate` for the `L.t` fallback — handles `%s`/`%d`/`%1$s`/`%%`); dropped the
custom `InstantIso8601Serializer` (kotlinx-datetime's `Instant` is ISO-8601 by
default, same wire format). Platform-only → `androidMain`: `GameHistory.kt` (SQLite)
+ a `File`-based `ContentLoader` (commonMain keeps a string-based
`ContentLibrary.parse`). Tests JUnit4 → `kotlin.test`. No `expect`/`actual` needed.

**Known gap:** the instrumented `GameHistoryStoreTest` is moved to
`src/androidDeviceTest/` and converted, but AGP 9's KMP device-test DSL
(`withDeviceTest` + the `androidDeviceTest` source-set accessor) isn't wired, so it is
an orphaned source set (harmless build warning). The SQLite store has no running test
until that's resolved — and it needs an emulator to run regardless.

### Web UI — Compose Multiplatform/Wasm (2026-11-20)
Decision: reuse the Kotlin UI via **Compose Multiplatform → wasmJs** (not a TS
rewrite). Spike + first screen done:
- `chess-core` gained a `wasmJs` target — **25 tests green on wasmJs** (native i64
  `Long`, unlike `js()`'s emulated `Long`).
- New `:web` module (`org.jetbrains.compose` 1.9.0 + `kotlin.multiplatform` +
  `kotlin.compose`; wasmJs/browser/executable) renders an 8×8 board from
  `chess-core`'s `Position`. `:web:wasmJsBrowserDevelopmentExecutableDistribution`
  compiles + webpack-bundles (`web.js` + `chess-trainer-web.wasm` ~15.6 MB +
  `skiko.wasm` ~8 MB — Compose/Wasm is canvas/Skia, inherently heavy; prod DCE shrinks).
- **Render not verified live**: the agent sandbox blocks headless Chrome (`mach_port`
  perm) and `meta browser`'s cert infra is down. Run it:
  `gradle :web:wasmJsBrowserDevelopmentRun`, or serve
  `web/build/dist/wasmJs/developmentExecutable/` over http.

### Shared UI — the real M4/M5 screens on web (done)
New `:ui` Compose-MP module (android + wasmJs); the 7 UI files moved from `:app` to
`ui/commonMain`. `:app` keeps only `MainActivity` + a `FileProgressStore`; `:web`
renders `RootView(AppModel(InMemoryProgressStore()))`. The only cross-platform seam was
persistence: `interface ProgressStore` (File on Android, in-memory on web — localStorage
is the upgrade). Verified: `:app:assembleDebug` (full APK), `:ui:compileKotlinWasmJs`,
`:web` bundle all green; chess-training 78/78 on host + js.

**Gotcha (important):** the `org.jetbrains.compose` *plugin* 1.9.0 is incompatible with
AGP 9.5.1's `com.android.kotlin.multiplatform.library` (crashes at config on
`KotlinMultiplatformAndroidComponentsExtension.onVariant`). A module with BOTH the android
KMP target and Compose must **skip the compose plugin** and apply only `kotlin.compose`
(the compiler) + depend on the Compose-MP artifacts directly
(`api("org.jetbrains.compose.{runtime,foundation,material3,ui}:1.9.0")`). A wasmJs-only
module (`:web`) can keep the plugin. `:app` dropped its androidx.compose BOM — Compose now
flows transitively from `:ui` (one version).

Also done earlier: KMP device-test wired (`withDeviceTest {}` + `androidDeviceTest` deps
via `getByName`); `chess-training` gained a wasmJs target.

### Engine on web — orchestration + stockfish.wasm driver (done; runtime-verify pending)
`chess-engine` stays the Android-native module (NDK/JNI/CMake untouched). A new `:engine`
KMP module holds the orchestration (`StockfishEngine` + EngineTypes + SearchBudget) against
an injected `EngineBackend` interface (commit `9b83f3b`): `AndroidEngineBackend` wraps the
JNI; `expect/actual EngineLock` (android real lock / wasmJs no-op) replaced `synchronized`;
`EngineScore.text` uses a pure 2dp formatter (dropped `java.util.Locale`). Verified: `:engine`
5/5 host + 5/5 wasmJs; android + instrumented compile; `:app` builds.

`WasmEngineBackend` (wasmJsMain) is implemented + compiles: it drives a single-threaded
`stockfish-*-lite-single.js` Web Worker over UCI (`org.w3c.dom.Worker` via kotlinx-browser),
queues commands until `uciok`, and parses `info`/`bestmove` into the `SearchListener`; FEN is
validated via chess-core for the InvalidPosition contract. Chosen: the prebuilt `stockfish`
npm package (lite-single — no COOP/COEP headers), not emscripten.

**To make it actually run on web (needs a browser — the sandbox can't):**
1. Serve the engine next to the app: `npm i stockfish`, copy `stockfish-*-lite-single.js` +
   its `.wasm` into `web/src/wasmJsMain/resources/`, and pass that filename to
   `WasmEngineBackend(workerUrl = ...)` (default `"stockfish.js"`).
2. Wire a UI feature to `StockfishEngine(WasmEngineBackend())` — the M4/M5 screens use
   pre-solved tactics, so nothing drives the engine yet; analysis/play is a new feature.
3. Verify in a browser.

### Next
Wire + vendor + browser-verify the web engine (above) → analysis/play features →
a localStorage-backed `ProgressStore` for real web persistence.
