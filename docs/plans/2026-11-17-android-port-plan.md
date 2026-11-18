# Android port — implementation plan (M0 + M1 detailed)

Spec: `~/chess-trainer/docs/plans/2026-11-17-android-port-design.md` (approved 2026-11-17, sections 1–4)
Repo: `~/chess-trainer` (git clone of github.com/minchopm/chess-trainer, branch `android-port`)
Scope of detail: M0 (toolchain skeleton) and M1 (chess-core) task-by-task. M2–M9 at milestone level;
expand each with writing-plans once M0 has proved the toolchain.
Verification contract: the RECOMMENDED rung (below). Device: x86_64 emulator only until M9.
Commits: run the commit steps only once the user has approved commits for this execution (standing rule).

## Environment (every shell)

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export ANDROID_HOME=$HOME/android-sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
```

`~/.gradle/gradle.properties` already routes Gradle through fwdproxy. Use `with-proxy` for curl and sdkmanager.
The SDK's `adb` must win over `/usr/local/bin/adb` (PATH order above) so the two never fight over the server.

## Pinned versions

| Item | Version | Source |
|---|---|---|
| Android Gradle plugin | 9.5.1 | Google Maven metadata, checked 2026-11-17 |
| Gradle | 9.5.0 | `~/.gradle/wrapper/dists/gradle-9.5.0-bin` (partial cache) |
| Compose BOM | 2026.09.00 | Google Maven metadata |
| NDK / CMake | 30.0.16248370 / 4.1.2 | `sdkmanager --list` |
| Emulator image | `system-images;android-36;google_apis;x86_64` | `sdkmanager --list` |
| Kotlin | 2.2.10 (what AGP 9.5.1 is built against) | AGP 9.5.1 POM on Google Maven |
| activity-compose | 1.13.0 | Google Maven metadata |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 | platforms installed: 34, 36 |

## Porting conventions (all port tasks, M1 onward)

1. `Foo.swift` → `Foo.kt` in the same-named module and package `com.joelsaleem.chesstrainer.<module>`. Type and member names unchanged; enum cases keep their Swift spelling (`PieceColor.white`, `Move.Kind.enPassant`).
2. A Swift struct with `var` fields or `mutating` funcs → a Kotlin `class` with `fun copy()` doing a deep copy. Every Swift `var p = position` becomes `val p = position.copy()`. An immutable struct → `data class`; a single-field struct → `@JvmInline value class` (`Square`, `CastlingRights`).
3. Failable `init?(label:)` → `companion object { fun from<Label>(…): T? }`: `Position.fromFen`, `Move.fromUci`, `Square.fromName`, `Piece.fromCharacter`, `PieceKind.fromLetter`.
4. Swift `Int` → Kotlin `Int`, except for counts that can pass 2^31 (perft nodes, `nodes`, `timeMs`) → `Long`. `UInt64` → `Long`, using `ushr` (never `shr`) and wrapping multiplication.
5. Swift `extension Position` files → top-level Kotlin extension functions in the same module, so they can reach `internal` setters.
6. Tests use kotlin.test on JUnit 5. A ported test's function name is the Swift `@Test("…")` display string verbatim, in backticks. Kotlin-only tests live in separate files, so the parity diff can ignore them.

## File map

```
android/
  settings.gradle.kts            T1 (include :chess-core), T2 (+:chess-engine), T3 (+:app)
  build.gradle.kts               T1  plugins … apply false
  gradle.properties              T1
  gradle/libs.versions.toml      T1, T3
  gradlew, gradle/wrapper/*      T1  generated
  .gitignore                     T1
  local.properties               T1  sdk.dir, git-ignored
  chess-core/                    T1, T4–T6  pure Kotlin/JVM, no Android dependencies
    build.gradle.kts
    src/main/kotlin/com/joelsaleem/chesstrainer/core/
      Types.kt Zobrist.kt Position.kt Move.kt MoveGeneration.kt MakeMove.kt Perft.kt SAN.kt MoveValues.kt GameImport.kt
    src/test/kotlin/com/joelsaleem/chesstrainer/core/
      TypesTest.kt PositionCopyTest.kt      (Kotlin-only)
      NotationTest.kt PerftTest.kt GameImportTest.kt   (Swift twins)
  chess-engine/                  T2  Android library + NDK
    build.gradle.kts
    src/main/cpp/CMakeLists.txt  src/main/cpp/sfjni.cpp
    src/main/kotlin/com/joelsaleem/chesstrainer/engine/StockfishNative.kt
  app/                           T3
    build.gradle.kts
    src/main/AndroidManifest.xml
    src/main/kotlin/com/joelsaleem/chesstrainer/MainActivity.kt
```

## Dependencies

| Task | Depends on | Shares files with | Can run parallel to |
|---|---|---|---|
| T0 Pre-flight | — | — | — |
| T1 Gradle skeleton + Types | T0 | settings, catalog (T2, T3) | — |
| T2 chess-engine NDK | T1 | settings (T3) | T4–T6 |
| T3 app + emulator | T2 | settings, catalog | T4–T6 |
| T4 Position + Zobrist | T1 | — | T2, T3 |
| T5 Moves + perft | T4 | NotationTest.kt (T6) | T2, T3 |
| T6 SAN + MoveValues + GameImport | T5 | NotationTest.kt | T2, T3 |

---

## M0 — toolchain skeleton

### Task 0: Pre-flight (network, SDK packages, Gradle, AVD)

- [ ] **Step 1: Prove the network is open**
  Run: `for u in https://repo.maven.apache.org/maven2/ https://services.gradle.org/distributions/ https://plugins.gradle.org/m2/; do with-proxy curl -s -o /dev/null -w "%{http_code} $u\n" --max-time 20 $u; done`
  Expected: `200` on all three. Anything else (429/403 earlier today) → stop: the session needs `--dangerously-enable-internet-mode`.
- [ ] **Step 2: Install SDK packages**
  Run: `yes | with-proxy sdkmanager --licenses >/dev/null; with-proxy sdkmanager "ndk;30.0.16248370" "cmake;4.1.2" "emulator" "platform-tools" "system-images;android-36;google_apis;x86_64"`
  Expected: `ls $ANDROID_HOME/ndk/30.0.16248370/source.properties $ANDROID_HOME/cmake/4.1.2/bin/cmake $ANDROID_HOME/emulator/emulator` lists all three.
- [ ] **Step 3: Fetch Gradle**
  Run: `with-proxy curl -sLfo /tmp/gradle-9.5.0-bin.zip https://services.gradle.org/distributions/gradle-9.5.0-bin.zip && unzip -qo -d /tmp /tmp/gradle-9.5.0-bin.zip && /tmp/gradle-9.5.0/bin/gradle --version | head -3`
  Expected: `Gradle 9.5.0`.
- [ ] **Step 4: Resolve the open versions**
  Run: `with-proxy curl -s https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml | grep -oE "<release>[^<]+"` and `with-proxy curl -s https://dl.google.com/android/maven2/androidx/activity/activity-compose/maven-metadata.xml | grep -oE "<version>[0-9.]+</version>" | tail -1`
  Expected: a stable Kotlin version (no `-Beta`/`-RC`) and a stable activity-compose version. Write both into the Pinned versions table, replacing the [RESOLVE] markers.
- [ ] **Step 5: Create and boot the AVD**
  Run: `echo no | avdmanager create avd -n ct36 -k "system-images;android-36;google_apis;x86_64" -d pixel_8`, then in the background `emulator -avd ct36 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect`; wait (use Monitor, not sleep) until `adb shell getprop sys.boot_completed` prints `1`.
  Expected: `adb devices` lists `emulator-5554  device`.

### Task 1: Gradle skeleton + chess-core `Types.kt`

**Files:** create everything marked T1 in the file map.

- [ ] **Step 1: Write the build files**
  - `settings.gradle.kts`: `pluginManagement` repositories `google()`, `mavenCentral()`, `gradlePluginPortal()`; `dependencyResolutionManagement` with `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, repositories `google()`, `mavenCentral()`; `rootProject.name = "chess-trainer"`; `include(":chess-core")`.
  - `gradle/libs.versions.toml`: versions `agp`, `kotlin`, `composeBom`, `activityCompose` from the pinned table. Plugins `android-application`, `android-library` (`com.android.*`, version ref `agp`), `kotlin-jvm`, `kotlin-compose` (`org.jetbrains.kotlin.plugin.compose`, version ref `kotlin`).
  - `build.gradle.kts`: all four plugins `apply false`.
  - `gradle.properties`: `org.gradle.jvmargs=-Xmx8g`, `android.useAndroidX=true`, `kotlin.code.style=official`.
  - `.gitignore`: `.gradle/ build/ .cxx/ local.properties .idea/ *.iml`.
  - `local.properties`: `sdk.dir=/home/joelsaleem/android-sdk`.
  - `chess-core/build.gradle.kts`: plugin `kotlin-jvm`; `kotlin { jvmToolchain(17) }`; `testImplementation(kotlin("test"))`; `tasks.test { useJUnitPlatform() }`.
  - Wrapper: `cd ~/chess-trainer/android && /tmp/gradle-9.5.0/bin/gradle wrapper --gradle-version 9.5.0`.
- [ ] **Step 2: Write the failing test** `TypesTest.kt`:
  ```kotlin
  @Test fun `square names round-trip`() { for (i in 0 until 64) assertEquals(i, Square.fromName(Square(i).toString())!!.index) }
  @Test fun `e4 is index 28`() { assertEquals(28, Square.fromName("e4")!!.index); assertNull(Square.fromName("i9")) }
  @Test fun `piece characters`() { assertEquals(Piece(PieceColor.black, PieceKind.knight), Piece.fromCharacter('n')) }
  @Test fun `castling fen strings`() { assertEquals("KQkq", CastlingRights.all.fenString); assertEquals("-", CastlingRights(0).fenString) }
  ```
- [ ] **Step 3: Run it and watch it fail.** Run `./gradlew :chess-core:test`. Expected: compile errors for `Square`, `Piece` and so on.
- [ ] **Step 4: Port `ios/Sources/ChessCore/Types.swift` → `Types.kt`** following the conventions: `enum class PieceColor { white, black }` with `opponent`, `pawnStep`, `pawnRank`, `promotionRank`; `PieceKind` with `letter`, `fromLetter`, `value`; `data class Piece(color, kind)` with `character`, `fromCharacter`; `@JvmInline value class Square(val index: Int)` with `file`, `rank`, the `(file, rank)` factory, `fromName`, `toString`; `@JvmInline value class CastlingRights(val rawValue: Int)` with the four constants, `all`, `contains`, `fenString`.
- [ ] **Step 5: Run the tests.** `./gradlew :chess-core:test`. Expected: `BUILD SUCCESSFUL`, 4 tests passed (check `chess-core/build/test-results/test/*.xml`).
- [ ] **Step 6: Commit** (only once approved): `git add android/ && git commit -m "android: gradle skeleton, chess-core types"`

### Task 2: chess-engine — Stockfish via NDK + `engineInfo` JNI

**Files:** `settings.gradle.kts` (+`include(":chess-engine")`), every chess-engine file in the file map.

- [ ] **Step 1: `chess-engine/build.gradle.kts`**: plugin `android-library`; `namespace = "com.joelsaleem.chesstrainer.engine"`; `compileSdk = 36`; `minSdk = 26`; `ndkVersion = "30.0.16248370"`; `defaultConfig.ndk.abiFilters += listOf("arm64-v8a", "x86_64")`; `externalNativeBuild.cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "4.1.2" }`. No dependency on chess-core until M2.
- [ ] **Step 2: `CMakeLists.txt`.** Exact build configuration, mirroring `ios/Package.swift:21-42` and `src/Makefile:349-812`:
  ```cmake
  cmake_minimum_required(VERSION 3.22)
  project(stockfish CXX)
  set(CMAKE_CXX_STANDARD 17)
  set(SF ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../ios/Vendor/Stockfish)
  file(GLOB SF_SRC ${SF}/src/*.cpp ${SF}/src/nnue/*.cpp ${SF}/src/nnue/features/*.cpp ${SF}/src/syzygy/*.cpp)
  list(REMOVE_ITEM SF_SRC ${SF}/src/main.cpp)
  add_library(stockfish SHARED ${SF_SRC} ${SF}/bridge/sfbridge.cpp sfjni.cpp)
  target_include_directories(stockfish PRIVATE ${SF}/src ${SF}/bridge/include)
  target_compile_definitions(stockfish PRIVATE NDEBUG NNUE_EMBEDDING_OFF IS_64BIT USE_PTHREADS USE_POPCNT)
  if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_definitions(stockfish PRIVATE USE_NEON=8)
  elseif(ANDROID_ABI STREQUAL "x86_64")
    target_compile_definitions(stockfish PRIVATE USE_SSE41 USE_SSSE3 USE_SSE2)
    target_compile_options(stockfish PRIVATE -msse4.1 -mssse3 -mpopcnt)
  endif()
  target_link_libraries(stockfish log)
  ```
  No dotprod: devices without it would crash at the first search. Revisit for speed in M2.
- [ ] **Step 3: `StockfishNative.kt`**: `object StockfishNative { init { System.loadLibrary("stockfish") }; external fun engineInfo(): String }`.
- [ ] **Step 4: `sfjni.cpp`**: `extern "C" JNIEXPORT jstring JNICALL Java_com_joelsaleem_chesstrainer_engine_StockfishNative_engineInfo(JNIEnv* env, jobject)` returns `env->NewStringUTF(sf_engine_info())`. Include `<jni.h>` and `"sfbridge.h"`.
- [ ] **Step 5: Build and inspect.**
  Run: `./gradlew :chess-engine:assembleDebug && unzip -l chess-engine/build/outputs/aar/chess-engine-debug.aar | grep libstockfish.so`
  Expected: two lines, `jni/arm64-v8a/libstockfish.so` and `jni/x86_64/libstockfish.so`.
  Run: `$ANDROID_HOME/ndk/30.0.16248370/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm -D --defined-only $(find chess-engine/build -path '*x86_64*' -name libstockfish.so | head -1) | grep -c Java_com_joelsaleem`
  Expected: `1`.
- [ ] **Step 6: Prove the engine source is untouched.** `git status --porcelain ios/` → empty.
- [ ] **Step 7: Commit** (only once approved): `git add android/ && git commit -m "android: build Stockfish 18 via NDK, engineInfo JNI"`

### Task 3: app — Compose shell showing the engine string on the emulator

**Files:** `settings.gradle.kts` (+`include(":app")`), `libs.versions.toml` (Compose libraries), every app file in the file map.

- [ ] **Step 1: `app/build.gradle.kts`**: plugins `android-application`, `kotlin-compose`; `namespace` and `applicationId` = `"com.joelsaleem.chesstrainer"`; `compileSdk = 36`, `minSdk = 26`, `targetSdk = 36`; `buildFeatures.compose = true`; dependencies `platform(libs.compose.bom)`, `androidx.activity:activity-compose`, `androidx.compose.material3:material3`, `implementation(project(":chess-engine"))`.
- [ ] **Step 2: Manifest**: `<application android:label="Chess Trainer">`, with `.MainActivity` exported and holding the MAIN/LAUNCHER intent filter.
- [ ] **Step 3: `MainActivity.kt`**: `class MainActivity : ComponentActivity()`. `onCreate` calls `setContent { MaterialTheme { Surface { Text(StockfishNative.engineInfo()) } } }`. This is a placeholder until M4's root view.
- [ ] **Step 4: Check the build (DOWN rung).**
  Run: `./gradlew :app:assembleDebug && unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -c libstockfish.so`
  Expected: `2`.
- [ ] **Step 5: Check on the emulator (RECOMMENDED rung).**
  Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell am start -W -n com.joelsaleem.chesstrainer/.MainActivity && adb exec-out screencap -p > /tmp/m0.png`, then Read `/tmp/m0.png`.
  Expected: `Success` and `Status: ok`; the screenshot shows text starting `Stockfish 18`; `adb logcat -d | grep -E "FATAL EXCEPTION|UnsatisfiedLinkError"` prints nothing.
- [ ] **Step 6: Commit** (only once approved): `git add android/ && git commit -m "android: compose app shell shows Stockfish engine info"`

---

## M1 — chess-core

### Task 4: `Position` (FEN, attacks, copy) + `Zobrist`

**Files:** `Position.kt`, `Zobrist.kt`, `NotationTest.kt` (first two twins). Sources: `ios/Sources/ChessCore/Position.swift`, `Zobrist.swift`.

- [ ] **Step 1: Write the failing tests** in `NotationTest.kt`: `` `FEN round-trips through parsing and printing` `` and `` `Illegal positions are rejected` ``, ported line for line from `ios/Tests/ChessCoreTests/NotationTests.swift:5-27`, with the same FEN strings and `assertEquals(fen, Position.fromFen(fen)!!.fen)` / `assertNull(Position.fromFen(…))`.
- [ ] **Step 2: Run them and watch them fail.** `./gradlew :chess-core:test` → compile failure.
- [ ] **Step 3: Port `Zobrist.swift`** → `internal object Zobrist` plus `internal class SplitMix64(seed: Long) { fun next(): Long }`. Same seed and same key-table layout; `ushr` throughout.
- [ ] **Step 4: Port `Position.swift`** → `class Position` with:
  `val squares: Array<Piece?>` (exposed read-only through `operator fun get(square: Square): Piece?`); `sideToMove`, `castling`, `enPassant`, `halfmoveClock`, `fullmoveNumber`, `hash: Long`, each `var … internal set`; `internal val history: MutableList<Long>`; `val fen: String`; `fun speculating(color: PieceColor): Position`; `fun king(color: PieceColor): Square?`; `fun isInCheck(color: PieceColor): Boolean`; `val isCheck`; `fun isAttacked(square: Square, by: PieceColor): Boolean`; `fun copy(): Position` (deep-copies `squares` and `history`); `companion object { const val startFEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"; fun fromFen(fen: String): Position?; operator fun invoke(): Position = fromFen(startFEN)!! }`.
- [ ] **Step 5: Run the tests.** Expected: 6 passed (4 TypesTest + 2 NotationTest).
- [ ] **Step 6: Commit** (only once approved): `git add android/chess-core && git commit -m "chess-core: Position, FEN, Zobrist"`

### Task 5: `Move`, move generation, make-move, game end, perft

**Files:** `Move.kt`, `MoveGeneration.kt`, `MakeMove.kt`, `Perft.kt`, `PerftTest.kt`, `PositionCopyTest.kt`, `NotationTest.kt` (+2 twins). Sources: the same-named Swift files.

- [ ] **Step 1: Write the failing tests.**
  - `PerftTest.kt`: `` `Perft counts match the published values` `` loops over the 6 cases from `PerftTests.swift:11-33` (startpos `[20, 400, 8902, 197281, 4865609]`, kiwipete `[48, 2039, 97862, 4085603]`, endgame with en passant `[14, 191, 2812, 43238, 674624]`, promotion and pins `[6, 264, 9467]`, under-promotion `[44, 1486, 62379]`, dense middlegame `[46, 2079, 89890]`), asserting `assertEquals(expected[d-1].toLong(), p.perft(d), "${name} depth $d")`.
  - `NotationTest.kt`: `` `Threefold repetition is detected` `` and `` `Insufficient material covers the usual cases` ``, ported from `NotationTests.swift:73-94`.
  - `PositionCopyTest.kt`: `` `copy is independent of the original` ``: `val a = Position(); val b = a.copy(); assertNotNull(b.make(uci = "e2e4")); assertEquals(Position.startFEN, a.fen); assertNotEquals(a.hash, b.hash)`.
- [ ] **Step 2: Run them and watch them fail.**
- [ ] **Step 3: Port.**
  - `data class Move(val from: Square, val to: Square, val promotion: PieceKind? = null, val kind: Kind = Kind.quiet)` with `enum class Kind { quiet, capture, doublePawnPush, enPassant, kingsideCastle, queensideCastle }`, plus `isCapture`, `isCastle`, `uci`, `toString() = uci`, `companion fun fromUci(uci: String): Move?`, `fun matchesNotation(other: Move): Boolean`. Port `Undo` as an internal class.
  - `fun Position.legalMoves(): List<Move>` and `fun Position.legalMoves(from: Square): List<Move>`.
  - `fun Position.make(notation: Move): Move?`, `fun Position.make(uci: String): Move?`, `internal fun Position.makeUnchecked(move: Move)`, and the properties `isCheckmate`, `isStalemate`, `isFiftyMoveRule`, `isThreefoldRepetition`, `isInsufficientMaterial`, `isDraw`, `isGameOver`.
  - `fun Position.perft(depth: Int): Long` and `fun Position.perftDivide(depth: Int): List<Pair<String, Long>>`.
- [ ] **Step 4: Run the tests.** Expected: all pass. Perft finishes in under 60 s on the JVM; if it is slower, profile before going on, because M3 grading calls `legalMoves` constantly.
- [ ] **Step 5: Commit** (only once approved): `git add android/chess-core && git commit -m "chess-core: move generation, make-move, perft"`

### Task 6: SAN, `MoveValues`, `GameImport` + the parity check

**Files:** `SAN.kt`, `MoveValues.kt`, `GameImport.kt`, `GameImportTest.kt`, `NotationTest.kt` (+3 twins).

- [ ] **Step 1: Write the failing tests.**
  - `NotationTest.kt`: `` `SAN is produced and parsed consistently` ``, `` `Disambiguation names the right piece` `` and `` `Checkmate is marked with #, plain check with +` ``, ported from `NotationTests.swift:29-71`. Expected values include `"Nde4"`, `"R5a4"`, `"Qh4#"`, `"Re1+"`.
  - `GameImportTest.kt`: all 11 `@Test`s from `GameImportTests.swift`, with the same names, inputs and assertions (e.g. the typo case `stoppedAt == StoppedAt("Qxq9", 3)`, and `GameImport.read("hello there") == null`).
- [ ] **Step 2: Run them and watch them fail.**
- [ ] **Step 3: Port.**
  - `fun Position.san(move: Move): String` and `fun Position.move(san: String): Move?`.
  - `class MoveValues(val byMove: Map<String, Int>, val best: Int)` with `loss(uci): Int?` and `score(uci): Int?`.
  - `class GameImport(val start: Position, val moves: List<Move>, val final: Position, val stoppedAt: StoppedAt?)` with `data class StoppedAt(val token: String, val afterMoves: Int)` and `val isEmpty`. `equals`/`hashCode` compare `start.fen`, `moves`, `final.fen` and `stoppedAt`, exactly as `GameImport.swift:21-27` does. `companion fun read(text: String): GameImport?`.
- [ ] **Step 4: Run the whole suite and check parity.**
  Run: `./gradlew :chess-core:test`. Expected: `BUILD SUCCESSFUL`.
  Run: `diff <(grep -ohP '@Test\("\K[^"]+' ../ios/Tests/ChessCoreTests/*.swift | sort) <(grep -ohP 'fun `\K[^`]+' chess-core/src/test/kotlin/com/joelsaleem/chesstrainer/core/{NotationTest,PerftTest,GameImportTest}.kt | sort)`
  Expected: no output (19 twins, names identical).
- [ ] **Step 5: Commit** (only once approved): `git add android/chess-core && git commit -m "chess-core: SAN, MoveValues, GameImport; 19/19 Swift tests ported"`

---

## M2–M9 (milestone level; expand after M0)

| # | Deliverable | Exit check | Notes for expansion |
|---|---|---|---|
| M2 | chess-engine: full `sf_*` JNI, `StockfishEngine` (port of `Engine.swift`, `StockfishEngine.swift`, `SearchBudget.swift`), network fetch script + first-run copy, `ContentLibrary` | 27 engine tests green on the emulator (`connectedAndroidTest`); content counts match the JSON | Callbacks arrive on Stockfish's search thread: cache the `JavaVM`, `AttachCurrentThread`, global refs. Cancellation → `sf_stop` + `sf_wait_for_search` |
| M3 | chess-training (20 Swift files) | 127 tests green; parity diff empty | `Codable` → kotlinx.serialization; `GameHistory` (SwiftData) → Room |
| M4 | board-ui 2D + Tactics + root navigation | A puzzle solved on the emulator, screenshot verified | Root composable replaces the M0 placeholder |
| M5 | Positional, Endgames, Play & coach, Progress, Settings | Screenshot per screen; a game played against Stockfish | |
| M6 | Rush, Guess the Elo, Watch, Today, Position editor, History | Screenshot per screen; history survives an app restart | |
| M7 | board-scene meshes + title sequence (SceneView) | 41 mesh tests green; menu and Watch render | Mesh generators stay pure Kotlin so they run as JVM tests |
| M8 | 3D LiveBoard | Moves played by tap and by drag on the dimensional board | Ray ∩ board plane → square |
| M9 | xcstrings → strings.xml, signed APK, README/NOTICE | Two locales verified; user sideloads the arm64 APK | First physical-device check |

---

## Requirements traceability

| Requirement (design doc) | Task(s) | Evidence |
|---|---|---|
| Gradle multi-module project in `android/` | T1 | `:chess-core:test` passes |
| Stockfish built unmodified for arm64-v8a + x86_64 | T2 | AAR lists both `.so`; `git status ios/` empty |
| JNI over the existing `sf_*` API | T2 (`engineInfo`), M2 (the rest) | `llvm-nm` symbol; emulator screenshot |
| App identity "Chess Trainer" / `com.joelsaleem.chesstrainer` | T3 | Manifest; launcher label in the screenshot |
| chess-core in pure Kotlin, no Android dependencies | T1, T4–T6 | `kotlin-jvm` plugin only |
| 19 ChessCoreTests ported, perft exact | T4–T6 | Parity diff empty; perft d5 = 4,865,609 |
| Value semantics of `Position` preserved | T4, T5 | `copy is independent of the original` |
| Verify on the emulator, by screenshot | T0, T3 | `/tmp/m0.png` read back |

## Assumptions & unknowns

| # | Assumption | Confidence | Validate | If false |
|---|---|---|---|---|
| A1 | Internet mode unblocks Maven Central and Gradle | high (a Gradle 9.5.0 download was started here before) | T0 Step 1 | Blocked: user restarts the session in internet mode |
| A2 | AGP 9.5.1's built-in Kotlin coexists with `kotlin-jvm` and `kotlin-compose` at the resolved Kotlin version | med | T1 and T3 sync | Follow the sync error: align the Kotlin version, or set `android.builtInKotlin=false` and apply `org.jetbrains.kotlin.android` explicitly |
| A3 | Gradle 9.5.0 meets AGP 9.5.1's minimum | med | T1 sync | Use the minimum version the sync error names |
| A4 | Stockfish 18 compiles under NDK 30 clang | high (upstream has `__ANDROID__` guards in `shm.h:39-46`, `memory.cpp:27-31`, `numa.h`) | T2 Step 5 | Fix with a compile definition in CMake, never by editing `ios/Vendor` |
| A5 | Headless swiftshader emulator renders Compose | high | T3 Step 5 | Retry with `-gpu guest`; else use Robolectric screenshots for UI |
| A6 | Values held in Swift `Int` in ChessCore fit 32 bits, except node counts | med | T5 perft d5 exact; review grep | Widen the field to `Long` |

## Risks & mitigations

| Risk | Prevent | Detect | Recover |
|---|---|---|---|
| fwdproxy blocks dependency hosts | Start in internet mode | T0 Step 1 returns non-200 | Stop; restart the session in internet mode |
| Kotlin/AGP plugin mismatch | Resolve versions in T0 Step 4 | Gradle sync error in T1/T3 | See A2 |
| A Kotlin reference silently aliases a Swift value copy | Convention 2; copy test | `PositionCopyTest`; review grep for `= position` | Insert `.copy()` at the alias |
| 64-bit to 32-bit overflow, or `shr` vs `ushr` in hashing | Conventions 4–5 | Perft and threefold tests | Widen to `Long`, use `ushr` |
| Stockfish compiles but crashes at runtime (bad SIMD flags) | Baseline flags only, no dotprod | M0: library load in T3; M2: search test | Drop to `-DUSE_POPCNT` only for that ABI |
| Two `adb` binaries fight over the server | PATH order | `adb devices` flaps | `adb kill-server` and use only the SDK adb |

## Test plan

| Scenario | Before | Commands | After |
|---|---|---|---|
| S1 Both ABIs packaged (DOWN) | No `android/` directory | `cd ~/chess-trainer/android && ./gradlew :app:assembleDebug && unzip -l app/build/outputs/apk/debug/app-debug.apk \| grep -c libstockfish.so` | Prints `2` |
| S2 Engine string on screen (RECOMMENDED) | `adb shell pm list packages \| grep chesstrainer` empty | `adb install -r …app-debug.apk; adb shell am start -W -n com.joelsaleem.chesstrainer/.MainActivity; adb exec-out screencap -p > /tmp/m0.png` | Screenshot shows `Stockfish 18 …`; logcat has no FATAL or UnsatisfiedLinkError |
| S3 Core parity (RECOMMENDED) | No Kotlin tests | `./gradlew :chess-core:test` + the T6 Step 4 diff | `BUILD SUCCESSFUL`; diff empty |
| S4 Engine untouched | Clean `ios/` | `git status --porcelain ios/` | Empty |
| S5 (UP, stretch) JNI search smoke | — | Moved to M2 (`connectedAndroidTest`) | — |

## Verification strategy

- JVM unit tests for chess-core (kotlin.test / JUnit 5); Swift-name parity diff as the completeness check.
- NDK: artifact inspection (`unzip -l`, `llvm-nm`) plus runtime library load on the emulator.
- App: emulator screenshot read back, plus a logcat crash grep. No coverage tooling until M3.

## Device verification strategy

| Scenario | Device | Proof |
|---|---|---|
| Launch the app; the engine string appears | API 36 x86_64 emulator (swiftshader) | `/tmp/m0.png` shows `Stockfish 18`; no FATAL in logcat |
| arm64 build | none until M9 | Packaging + `llvm-nm` only. Known gap: arm64 never runs until the user sideloads at M9 |

## Review focus

1. Every Swift `var x = somePosition` must be a Kotlin `.copy()`. Test: `copy is independent of the original` (T5).
2. Node counts and hashes use `Long` with `ushr`. Tests: perft d5 = 4,865,609; threefold repetition (T5).
3. JNI symbol name and receiver (`jobject` for a Kotlin `object` member). Proof: `llvm-nm` count = 1 (T2) and the screenshot (T3).
4. FEN validation rejects positions where the side not to move is in check, and boards with no king. Test: `Illegal positions are rejected` (T4).
5. GameImport stops at the first bad token and reports where. Tests: `A typo stops the line and says where` and `An illegal but well-formed move stops it too` (T6).

---

## M2 — chess-engine (expanded 2026-11-17, after M0/M1 proved the toolchain)

**Scope decision — Reckless is out for v1** (handover §6). `RecklessEngine.swift` and all
Reckless-only tests are *not* ported. With one engine the Swift `protocol Engine: Actor`
collapses to the concrete class: **no interface** (YAGNI, one implementation), and
`valueEveryMove` + the budget-convenience overloads become members of `StockfishEngine`.
`:chess-engine` gains a dependency on `:chess-core` (for `Position`/`MoveValues`), which
M1 deferred.

**Test target: 13 instrumented tests** (all drive the real engine + networks, so all
`connectedAndroidTest`), not the 27 in `ios/Tests/ChessEngineTests`:

| Swift file | Ported | Skipped |
|---|---|---|
| StockfishTests (8) | 8 | — |
| ValueCoverageTests (2) | 2 | — |
| BudgetTests (5) | 3 | 2 (Reckless) |
| RecklessTests (7) + RecklessCoverageTests (2) | — | 9 (Reckless out) |
| LegalTextsInSync (2) + VendoredFramework (1) | — | 3 (iOS resource/packaging; revisit at M9) |

**Concurrency port:** Swift `actor` + `async`/`await` + `CheckedContinuation` + watchdog
`Task` → Kotlin `suspend` funs on a class guarded by a `kotlinx.coroutines.sync.Mutex`
(the one-search-at-a-time gate), `suspendCancellableCoroutine` for the search wait, and a
launched coroutine with `delay` for the 10 s watchdog. Adds `kotlinx-coroutines-core` to
`:chess-engine`.

**JNI callbacks on the search thread:** cache `JavaVM` in `JNI_OnLoad`,
`AttachCurrentThread` inside `onInfo`/`onBestMove`, hold a global ref to the Kotlin
listener, `DetachCurrentThread` when done. `SFEngine*` is stored as a `jlong` handle.

**Networks:** the two Stockfish nets (`nn-c288c895ea92.nnue`, `nn-37f18f62d772.nnue` from
`https://tests.stockfishchess.org/api/nn/<name>`) ship as `noCompress` assets, copied to
`filesDir` on first launch, then `sf_load_networks`. A Gradle task (port of
`ios/scripts/fetch-networks.sh`) downloads them into `src/main/assets/` if absent. The big
net is ~104 MB, so the test APK is large; the harness sets `Threads=1`, `Hash=64`.

### Increments

- **A — pure types (JVM-verifiable, no emulator):** `EngineScore`, `EngineLine`,
  `Analysis`, `EngineException`, `EngineCapabilities`, `SearchBudget`; one Kotlin-only
  `EngineScoreTest`. Exit: `:chess-engine:testDebugUnitTest` green.
- **B — JNI bridge:** expand `sfjni.cpp` to wrap every `sf_*`; `StockfishNative.kt` (handle
  as `jlong`, a listener interface, threaded callbacks). Exit: AAR builds both ABIs,
  `llvm-nm` shows the new symbols.
- **C — StockfishEngine.kt:** the coroutine port (gate, session sink, watchdog,
  `analyse`/`chooseMove`/`valueEveryMove`/`setOption`/`newGame`/`stop`). Exit: compiles.
- **D — networks + instrumented tests:** fetch/bundle nets, first-run copy, androidTest
  harness (`makeEngine`), the 13 tests. Exit: 13 green via `connectedAndroidTest`.

**Status (2026-11-17): A–D all complete.** 13/13 instrumented tests green on an arm64
emulator. Note kept faithful to iOS: `chooseMove`'s elo limit is reset by `analyse`, so
the limiter is currently a no-op during the search — matched rather than silently fixed.
Instrumented test method names are camelCase (the Swift func names), not the backtick
display strings: D8 forbids spaces in class names for `minSdk < 35`, which the lambda
classes inside backtick-named tests would otherwise trigger.

---

## M3 — chess-training (expanded 2026-11-18)

**Scope.** The iOS module is 20 files / 4228 LOC; v1 drops online play and the paywall
(design §Scope), so the Android port is the offline-training subset:

| Ported (v1) | Dropped (out of v1) |
|---|---|
| Appearance, Clock, Content, EloGuess, Features, GameHistory, Grading, Localized, MaterialBalance, MoveDescription, Premove, Progress, Rush, Selection, Feed (Today-feed parsing) | MatchSession, MatchProtocol, Invitation, OnlineRecord (Game Center online play); Entitlement → a hard-coded "always Pro" stub |

Dropped tests: MatchTests (500), InvitationTests (76), RatingPoolTests (151),
EntitlementTests (127 → replaced by a trivial always-Pro check). Ported JVM unit tests:
Appearance 77, EloGuess 91, GameHistory 95, MaterialBalance 40, Premove 101, Rush 77,
Training 270, Feed 289 (parsing only) — the "127 tests" minus dropped features, per
design §Verification line 97.

**New module** `:chess-training` (`com.android.library`; offline logic + Room; most tests
run on the JVM). Deps: `:chess-core` (and `:chess-engine` where Grading/MoveDescription
use `EngineScore`/`Analysis`). kotlinx.serialization (Codable models) and Room
(`GameHistory`, exercised at M6) are added as later increments need them.

### Increments
- **M3.0 — module scaffold + first pure slice:** create `:chess-training`; port
  MaterialBalance + its 3 tests. Exit: `:chess-training:testDebugUnitTest` green.
- **M3.1 — pure training logic:** Grading, MoveDescription, EloGuess, Rush, Premove,
  Selection, Clock, Features (+ tests). JVM unit tests.
- **M3.2 — serialized content:** Content (ContentLibrary), Feed parsing, Appearance via
  kotlinx.serialization; bundle `data/*.json` into assets. JVM tests.
- **M3.3 — Progress** (682 LOC) + tests.
- **M3.4 — GameHistory → Room** + GameHistoryTests (Robolectric or instrumented).
- **M3.5 — Entitlement** always-Pro stub + a trivial test.

Exit (M3): the ported chess-training suite green; parity diff empty over ported files.
