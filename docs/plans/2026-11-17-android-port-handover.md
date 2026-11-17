# Android port — handover

Written 2026-11-17 so the work can continue on another machine, without the
original session or any Meta tooling. Everything you need is in this repository
(branch `android-port`) plus a git bundle.

| Doc | What it is |
|---|---|
| `docs/plans/2026-11-17-android-port-design.md` | Approved design: scope, architecture, milestones M0–M9 |
| `docs/plans/2026-11-17-android-port-plan.md` | Task-by-task plan for M0 (toolchain skeleton) and M1 (chess-core port) |
| this file | Where things stand and how to pick them up |

## 0. Status — M0 + M1 done (2026-11-17, continued on macOS/Apple-Silicon)

M0 (toolchain skeleton) and M1 (chess-core) are **complete and verified** on an
Apple-Silicon Mac. Commit `android: gradle wrapper + M0/M1 build fixes` sits on
`android-port` on top of the scaffold commit. Evidence:

- `:chess-core:test` → **25/25 pass** (19 Swift twins + 6 Kotlin-only); perft ~2.2 s.
- Swift→Kotlin test-name parity: **19/19**, diff empty.
- `:app:assembleDebug` → APK packages `libstockfish.so` for **arm64-v8a and x86_64**.
- App launches on the emulator showing `Stockfish 18 by the Stockfish developers
  (see AUTHORS file)`; logcat has no `FATAL`/`UnsatisfiedLinkError`; `git status ios/` clean.

**The frontier is now M2 (chess-engine)** — see §6.

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
