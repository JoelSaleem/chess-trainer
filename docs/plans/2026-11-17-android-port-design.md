# Android port — design

Date: 2026-11-17 · Branch: `android-port` · Source: `ios/` (Brass Pawn, ~30k lines Swift)

## Goal

A native Android app (Kotlin + Jetpack Compose) that ports the iOS app's offline
features 1:1: every training mode, the 2D and 3D boards, and on-device Stockfish 18.
Done means: a signed APK that runs on a phone and on the x86_64 emulator, with the
ported test suites green and every screen verified by screenshot.

## Scope

| In v1 | Out of v1 |
|---|---|
| Tactics, Positional, Endgames, Play & coach, Progress, Settings | StoreKit paywall (`Entitlement` hard-coded to Pro) |
| Puzzle Rush, Guess the Elo, Watch (classics), Today feed, Position editor, History | Game Center / online play, friend invites |
| 2D board (photographed Staunton sets) | Photo board scanner (calls the author's backend) |
| 3D title sequence and interactive dimensional board | Reckless engine (Rust), App Clip, Mac Catalyst, review prompts |
| Localisation (xcstrings → strings.xml) | Play Store distribution (sideload first) |

Identity: app name "Chess Trainer", package `com.joelsaleem.chesstrainer`. "Brass Pawn"
is the original author's brand. Licence stays AGPLv3; About/Licences screen ships.

## Architecture

Gradle modules mirror the Swift packages; Swift is the spec.

```
app ──► chess-training ──► chess-core
 ├───► chess-engine ─────► chess-core      (+ :stockfish NDK target)
 ├───► board-ui ─────────► chess-core, chess-training
 └───► board-scene ──────► chess-core
```

| iOS | Android |
|---|---|
| SwiftUI + `@Observable` | Compose + `mutableStateOf` in plain classes |
| SwiftData (`GameHistory`) | Room |
| `UserDefaults`, Codable files | SharedPreferences, kotlinx.serialization files |
| SceneKit + simd | Filament via SceneView, small `Vec3` |
| CoreGraphics/CoreText, AVFoundation | Compose Canvas + TextMeasurer, SoundPool |
| `CStockfish` bridge | JNI shim over the unchanged `sf_*` C API |

`chess-core` has no Android dependency so it tests on the JVM.

## Engine

- `Vendor/Stockfish/src` + `bridge/sfbridge.cpp` built by CMake, unmodified
  (NOTICE.md promises "vendored unchanged").
- `sfjni.cpp` (~100 lines): `jlong` handle ⇄ `SFEngine*`, `SFInfo` ⇄ Kotlin data class,
  callbacks into Kotlin.
- `StockfishEngine` ports the Swift `Engine` protocol: one search at a time,
  `suspend analyse(fen, depth, movetimeMs, multiPV)` and `chooseMove(fen, elo, …)`;
  coroutine cancellation calls `sf_stop`. The opponent ladder keeps `UCI_Elo`.
- ABIs: `arm64-v8a` (NEON/dotprod) and `x86_64` (emulator).
- Networks `nn-c288c895ea92.nnue` (big) and `nn-37f18f62d772.nnue` (small) are
  fetched by a port of `ios/scripts/fetch-networks.sh`, never committed, packed with
  `noCompress`, copied once to `filesDir` on first launch (size-checked), then passed
  to `sf_load_networks`. Missing or corrupt → blocking "engine unavailable" screen.

## Content

Gradle copies `data/{tactics,positions,endgames,games,classics}.json` into assets —
the same files iOS bundles. `ContentLibrary.load` ports 1:1 with kotlinx.serialization;
an unreadable file yields an empty list, as on iOS.

## UI

- Navigation: one root composable with `selection` (8 destinations: today, watch,
  guessTheElo, tactics, positional, endgames, play, progress) and `showsMenu`, the
  full-screen menu over the 3D title scene. No Navigation library. Back opens the
  menu; `confirmExit` guards a game in progress.
- Each `*Screen.swift` → `*Screen.kt`. `Components`, `CustomControls`, `Typography`
  → app theme package.
- Flat board: Compose Canvas; piece PNGs (5 colour sets × 6) → `drawable-nodpi`.
- Fonts: Cormorant Garamond, JetBrains Mono (OFL) → `res/font`. Sounds → `res/raw`.

## 3D

- Meshes are procedural on iOS (lathe-turned pieces, variable-depth knight, boxes,
  planes); the generators in `Solids`, `Pieces`, `Knight`, `Parlour`, `Stage` port as
  pure Kotlin producing vertex/normal buffers, so their 41 tests run on the JVM.
- Title sequence and `GamePlayer` (menu, Watch, Settings preview); `OrbitCamera` ported.
- `LiveBoard` (dimensional play): legal destinations, premoves, highlights, coach
  arrows, value tags, promotion. Picking = ray ∩ board plane → square.
- Materials: SceneView built-in lit/unlit. Procedural textures (roughness, value tags)
  drawn with `android.graphics.Canvas` → Bitmap → Filament texture.
  Fallback if the look is off: custom `.mat` compiled with `matc` at build time.
- Dust: instanced unlit quads; first thing cut if frame rate suffers.
- `SceneQuality.high/.low` chosen from the device performance class.

## Verification

| Layer | Runs on | Oracle |
|---|---|---|
| chess-core (19), chess-training (127), board-scene (41), board-ui (16), app logic (72 minus dropped) | JVM | Same-named Kotlin twin per Swift test; per-suite counts match except dropped features |
| chess-engine (27) | x86_64 emulator | `connectedAndroidTest` |
| Screens, 3D | emulator | `adb exec-out screencap`, screenshot read back |
| Smoke | emulator | launch → solve a puzzle → 5 moves vs Stockfish → toggle dimensional board |

## Milestones

| # | Deliverable | Done when |
|---|---|---|
| M0 | Gradle project in `android/`, NDK Stockfish, JNI | App on emulator shows `sf_engine_info()` ("Stockfish 18") |
| M1 | chess-core | Perft and the 19 core tests green |
| M2 | chess-engine + content + network copy | 27 engine tests green on emulator; content counts match the JSON |
| M3 | chess-training | 127 tests green |
| M4 | 2D board + Tactics | A puzzle solved end-to-end on emulator, screenshot verified |
| M5 | Positional, Endgames, Play & coach, Progress, Settings | Each screen screenshot-verified; a game played vs Stockfish |
| M6 | Rush, Guess the Elo, Watch, Today, Position editor, History (Room) | Each screen screenshot-verified; history survives restart |
| M7 | 3D meshes + title sequence | 41 mesh tests green; menu and Watch render |
| M8 | 3D LiveBoard | Move played by tap and by drag on the dimensional board |
| M9 | Localisation + signed APK + README/NOTICE | Two locales verified; APK installs on a device |

## Prerequisites and risks

- Session must run with internet mode: Maven Central (429) and Gradle (403) are
  blocked by fwdproxy otherwise; the cached Gradle 9.5 dist is incomplete.
- JDK 17 at `/usr/lib/jvm/java-17-openjdk` (default `java` is 1.8). Android SDK at
  `~/android-sdk` (platforms 34/36); NDK and emulator image to install via sdkmanager.
- APK carries ~100 MB of networks; first launch copies them (progress screen).
- SceneView materials may not match SceneKit's look; `matc` fallback above.
