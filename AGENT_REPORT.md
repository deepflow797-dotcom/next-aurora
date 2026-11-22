# Agent report

Short, honest status for the human. Updated at the end of every phase.

---

## Phase 0 — Architecture — DONE (2026-11-13)

### What works (verified by running it)

- The Cargo workspace builds; `cargo clippy --all-targets -- -D warnings` is
  clean; **28 unit tests pass**.
- The lyrics engine parses plain LRC, enhanced (word-timed) LRC, multi-timestamp
  lines, `[offset:±ms]`, tolerates malformed lines, and estimates word timing
  for plain LRC (all covered by tests).
- The playback clock is a seqlock over `(position, instant, playing)`; tests
  prove readers never see a torn pair, that a paused clock does not advance and
  that a seek re-anchors.
- The lyric view model produces `FramePlan`/`WordPlan` (active line, scroll
  offset, per-word activation/scale/opacity) with no UI toolkit involved.
- The UI spike runs both candidates for 60 s at ~59.4 FPS in a 1280×800
  software framebuffer and writes a report + screenshot:
  - iced: render p95 **2.18 ms**, CPU **9.7 %** of one core, **19.7 MiB** RSS;
  - egui: render p95 **5.38 ms**, CPU **24.5 %**, **29.0 MiB** RSS.
- Arabic rendering verified visually: iced shapes and orders a mixed
  Arabic/English line correctly (screenshot evidence committed); egui shapes the
  glyphs but lays the line out LTR, reversing Latin and disconnecting Arabic.

### What does not work / is not verified

- **No Wayland session, no GPU.** The spike ran headless with a software
  rasterizer, so "60 FPS on Fedora/Wayland" is *not* verified and GPU numbers do
  not exist (`gpu_percent: null`). Rerun on the real machine:
  `cd spikes/ui-spike && cargo run --release -- --candidate iced --seconds 60 --out out/iced`.
- **No Android.** No SDK/NDK/emulator/device here, so the 63.9 Android spike was
  not attempted. Strategy B (shared Rust core + Compose UI) is the recommendation
  and is labelled provisional.
- **No audio hardware.** `NullOutput` only; no real PipeWire playback yet.
- **No windowed application.** `aurora-app` is a console demo that prints a frame
  plan; the real window arrives in Phase 5.
- **Doctests were not run** (`rustdoc` is missing from this toolchain).

### How to run what exists

```text
cargo build
cargo test --lib --bins --tests
cargo run -p aurora-app                 # prints a lyric frame plan for the demo track

cd spikes/ui-spike
cargo run --release -- --candidate iced --seconds 60 --out out/iced
cargo run --release -- --candidate iced --probe --out out/probe-iced   # BiDi evidence
```

### Recommendation

Start **Phase 1 (Rust foundation)** next, and on the real Fedora machine first
re-run the two spike commands (plus `--probe`) so the Wayland/60 FPS claim moves
from "pending" to "verified" before the UI work in Phase 5 depends on it.

---

## Phase 1 — Rust foundation — DONE (2026-11-13)

### What works (verified by running it)

- iced 0.14 (tiny-skia, Wayland) **builds and links offline** from the vendored
  directory source. That was the phase's prerequisite: the whole toolkit graph
  (iced → winit → smithay-client-toolkit/softbuffer/zbus) now resolves.
- `cargo build --workspace` succeeds; clippy `--all-targets -- -D warnings` is
  clean; **102 unit tests pass** (was 28).
- The binary boots end to end: XDG directories are created, `settings.toml` is
  created-or-loaded, `tracing` is installed, and it picks a front end.
- The **§27 settings surface** is implemented as typed data (playback, lyrics,
  appearance, performance, library), persisted as TOML, saved atomically after
  every UI change, clamped and defaulted on load, and tolerant of unknown keys.
- **Headless mode** loads settings, scans the configured music folders into the
  catalog and reports counts. `--headless`, `AURORA_HEADLESS=1` and "no
  compositor" all reach it.
- A **stale `WAYLAND_DISPLAY` is handled**: the socket is probed before iced is
  allowed to run, and the window call is wrapped in `catch_unwind`, because iced
  panics (not errors) when the compositor connection fails.
- `--demo` runs the Phase 0 pipeline again, now against a real `fixtures/`
  library (it previously failed on a clean checkout: no such directory existed).

- The shell's layout is rendered headlessly through iced's own layout/draw passes
  (`src/shot.rs`, test-only) and committed to `docs/evidence/`: all three panes,
  with toggles showing their real on/off state. Tests assert the renders are
  non-blank, deterministic and respond to settings changes.

### What does not work / is not verified

- **No window has ever been displayed here.** No compositor, no DRM device: the
  window path (surface creation, HiDPI, input, frame pacing) is unproven. Run
  `cargo run -p aurora-app` on Fedora first.
- **Screenshot colours are swapped** (red↔blue) by an upstream `iced_tiny_skia`
  bug — the blue accent renders orange in `docs/evidence/`. Not compensated for
  in our code, because the wgpu backend is not affected; a characterisation test
  fails loudly if iced fixes it.
- **X11 is unsupported in this build**: the X11 backend needs `ctor ^0.10`, which
  has no vendorable source here. X11-only sessions get a warning and headless
  mode.
- **No audio, no lyric rendering, no SQLite** yet — Phase 2/3 work. The catalog
  is still in-memory, so a headless scan is discarded on exit.
- The settings pane's accent colour and artwork effects are stored but not
  painted yet.
- Doctests still cannot run (no `rustdoc`).

### How to run what exists

```text
cargo run -p aurora-app                  # shell window (Wayland), else headless
cargo run -p aurora-app -- --headless    # settings + scan, no window
cargo run -p aurora-app -- --demo        # Phase 0 pipeline over fixtures/
cargo run -p aurora-app -- --help
```

Settings live at `~/.config/aurora/settings.toml`; logs honour `AURORA_LOG`.

### Recommendation

On the Fedora machine: run the app, then the two spike commands, so both the
window and the 60 FPS claim become verified. Then Phase 2 — Symphonia decode +
CPAL output behind `AudioOutput`, with the clock driven by the device counter.

---

## Phase 2 (partial) — decoders — DONE (2026-11-13)

### What works (verified by running it)

- **Two decoders behind one `Decoder` trait**: a dependency-free WAV decoder
  (PCM 8/16/24/32, float 32/64, any channel count, `WAVE_FORMAT_EXTENSIBLE`,
  odd-sized chunks, `fmt ` after `data`) and **Symphonia 0.5.5** for
  FLAC/MP3/AAC/ALAC/CAF/M4A, vendored offline from `pdeljanov/Symphonia`.
- **FLAC decoding is verified on real data**: `fixtures/music/00 - Fixture
  Steps.flac` decodes to exactly 8192 frames with the expected sample values at
  both block boundaries. That fixture is written byte by byte by
  `scripts/fixtures/make-flac.py` (real CRC-8/CRC-16), so the test checks two
  implementations against each other rather than trusting either alone.
- `aurora --decode <FILE>` prints format, frames, duration, peak (dBFS) and RMS,
  decoding the whole file in blocks so a corrupt stream fails immediately.
- **Frame-exact seeking** (`Decoder::seek`, never past the request) for both
  decoders. This caught a real trap: Symphonia's accurate seek only lands at or
  before the target, so the first packet after a seek is trimmed to the requested
  frame - otherwise a file without a seek table replays the whole block.

### What does not work / is not verified

- **No audio output at all**: no ALSA/PipeWire here and `cpal` cannot be
  vendored (needs `alsa-sys`), so `NullOutput` remains the only `AudioOutput`
  and the clock is still driven by tests, not a device.
- **MP3/AAC/ALAC/CAF are compiled in but never exercised** — no encoder on this
  machine can produce those files.
- **AIFF was unsupported** at this point: `symphonia-format-riff` (needed for WAV
  and AIFF) depends on the `extended` crate, which cannot be obtained offline.
  Closed in Phase 2.6 by an in-crate AIFF/AIFF-C decoder.
- No resampling, channel mapping, gapless playback or queue yet.

---

## Phase 2.5 — playback engine (device-free) — DONE (2026-11-13)

### What works (verified by running it)

- `AudioSource` / `DecoderSource` / `PlaybackEngine` / `NullDevice` in
  `aurora-audio`: the engine a real device callback will drive. Position comes
  from frames handed to the device and is published into the `aurora-core`
  seqlock clock, never derived from a timer.
- `aurora --play <FILE>` renders a whole file through the engine with no sound
  card: `0:00.170 of 0:00.170 in 18 device callbacks (480 frames each)`.
- Tests cover position arithmetic, clock extrapolation, volume, pause, exact
  end-of-stream, seek, and a decoding failure stopping playback cleanly.

### What does not work / is not verified

- **Nothing is audible** — `NullDevice` counts frames, it does not open a device;
  cpal remains blocked on the unfetchable `alsa-sys`.
- No resampling, channel mapping, queue or gapless hand-off; `aurora-core`'s
  `Transport` state machine is not wired to the engine yet.

---

## Phase 2.6 — AIFF in-crate — DONE (2026-11-13)

### What works (verified by running it)

- `aurora-audio::aiff::AiffDecoder`: `FORM`/`AIFF` and `FORM`/`AIFC`. `COMM`
  parsing with the 80-bit extended-float sample rate, `SSND` with its `offset`
  field, odd-chunk padding, `numSampleFrames` trimming, PCM 8/16/24/32-bit
  (signed 8-bit, unlike RIFF) and IEEE float 32/64, plus `NONE`/`twos`/`sowt`/
  `fl32`/`fl64` AIFF-C compressions. Frame-exact `seek`, `total_frames`.
- Unimplemented AIFF-C compressions fail as `DecodeError::Unsupported` with the
  codec name (`AIFF-C compression 'ulaw' is not implemented`), never as silence.
- `DecodeError` now lives in `aurora-audio::error` with AIFF variants and a
  `path()` accessor; `crate::wav::DecodeError` is a re-export, so nothing broke.
- New fixture `fixtures/music/00 - Fixture Tone.aiff` (4854 B) holding the same
  audio as the WAV fixture; `make-aiff.py` regenerates it deterministically.

```text
$ cargo run -q -p aurora-app -- --decode "fixtures/music/00 - Fixture Tone.aiff"
  48000 Hz, 1 channel(s), 16-bit integer PCM
  2400 frames, 0.050 s
  peak 0.2500 (-12.0 dBFS), rms 0.1768
```

`cargo test --workspace`: **118 tests** (was 102), clippy `-D warnings` clean,
rustfmt clean. The equivalence test decodes the same audio through `WavDecoder`
and `AiffDecoder` and compares every sample to within 1e-7.

### What does not work / is not verified

- AIFF-C `sowt`/`fl32` are tested from in-memory files only; no committed AIFF-C
  fixture exists because nothing here can write one.
- Compressed AIFF-C (µ-law, A-law, IMA4) is not implemented - it is named in the
  error instead.
- Still no audible output (no ALSA/`cpal`), no resampling, no channel mapping, no
  queue, no gapless hand-off.

---

## Phase 2.7 — Android app shell — BUILT, NOT RUN ON A DEVICE (2026-11-13)

### What works (verified by running it)

- `crates/aurora-mobile`: a `Player` façade over the same decoders, engine and
  scanner the desktop binary uses, reachable either as Rust methods or as one JSON
  `dispatch(method, args)` call; plus an `i64` handle registry so Kotlin can hold a
  player without owning a Rust object.
- `PacedDevice` in `aurora-audio`: the engine runs on its own thread and pulls 480
  frames every 10 ms, so positions advance at wall-clock speed. Tested with a real
  elapsed-time assertion.
- 22 new tests (17 façade + 5 registry, plus 3 engine tests), including: real-time
  play to `finished`, pause freezing the position, seek landing, restart-after-end,
  live volume reaching the engine, scanning the fixture folder, JSON errors for
  unknown methods/bad arguments/stale handles, and handle non-reuse.
- `aurora-mobile-probe` prints the exact sequence the Kotlin view model performs.
  Latest run is quoted in `PHASE_LOG.md`.

```text
$ cargo check -p aurora-mobile --features jni-bridge   # the JNI layer compiles
$ nm -D target/debug/libaurora_mobile.so | grep Java_  # the symbols Kotlin links
  T Java_app_aurora_player_NativePlayer_nativeCall
  T Java_app_aurora_player_NativePlayer_nativeCreate
  T Java_app_aurora_player_NativePlayer_nativeDestroy
  T Java_app_aurora_player_NativePlayer_nativeLivePlayers
$ bash scripts/android/build.sh --check
  MISSING rustup / cargo-ndk / NDK / java 17 / aarch64 target
```

`cargo test --workspace`: **138 tests**, clippy `-D warnings` clean with and
without the `jni-bridge` feature, rustfmt clean.

### What does not work / is not verified

- **No APK exists.** No JDK, no Android SDK/NDK, and no `aarch64-linux-android`
  std in this toolchain; `scripts/android/build.sh` refuses with a list of what is
  missing instead of failing halfway.
- **The JNI functions have never been executed** - they are typechecked against
  `jni` 0.22.4 and covered indirectly by registry tests, nothing more.
- **The Kotlin/Compose sources have never been compiled.**
- **No audio output on Android** (no AAudio/Oboe), no MediaStore/`content://`
  support, no foreground service or media notification.
- Per the spec's Android rule, nothing here may be called "done": it is *written and
  host-verified*, pending a real device and an Android version to report.

---

## Phase 2.8 — lyrics in the app core — WRITTEN, NOT COMPILED (2026-11-13)

> The sandbox was reset in the middle of this phase and the Rust toolchain
> (`/opt/rust`) and vendored crates (`/home/user/.cargo-vendor`) went with it - they
> are outside the repository, so they are not in the snapshot. `static.rust-lang.org`
> is unreachable, so they cannot be restored. **Nothing below has been compiled.**
> Last green commit: `c5894a8` (138 tests, clippy clean, rustfmt clean).

### What was written

- `crates/aurora-mobile/src/lyrics.rs`: `LyricsSession` = `LyricDocument` (from
  `aurora_lyrics::discover`, with `estimate_word_timing` applied) + `aurora_ui::LyricView`,
  serialised as JSON per line/word for Compose.
- `Player::open` loads lyrics with the track; `state` reports `hasLyrics`; a new
  `lyrics` command returns the column; `Lyrics`/`LyricLine`/`LyricWord` parsers in Kotlin.
- `LyricsPanel` composable: dim inactive lines, larger active line, per-word karaoke
  colour fill, auto-follow via `LaunchedEffect`.
- Eight new tests (six in `lyrics.rs`, two in `facade.rs`), and a probe step that
  prints the column.

### Verification status

- **Compiled: no. Tested: no. Linted: no. Formatted by hand.**
- The code was checked against the real signatures of `aurora-core::lyrics`,
  `aurora-core::clock`, `aurora-ui::{LyricView, Layout, FramePlan}` and
  `aurora_lyrics::{discover, estimate_word_timing, Lookup}`; four mistakes were
  caught that way (a missing import, a source-label assertion that used the enum
  name instead of `LyricSource::label()`, a `Debug` bound needed by `expect_err`,
  and a clippy `manual_clamp`). Compiler-only errors may remain.
- `android/README.md` and `README.md` now point at `c5894a8` as the last fully
  verified state rather than implying the tree is always green.

## Word-level blur rebuild (2026-11-19)

See `BLUR_REBUILD.md` for the full, honest status. Summary: the modern
glass codebase described in the handoff documents was not present in
this repo or on GitHub, so the word-blur effect was rebuilt from the old
branch (`arena/504adea6-aurora`) + the handoff prompt, with liquid glass
cancelled by user decision. Implemented: Rust word timings in the lyrics
JSON, Kotlin `KaraokeLine`/`WordBlurLaw` (14f full line, 8f mini card,
4 stroke passes), `AuroraLyricPreviewBox` wired under the transport
controls, browser preview `preview_blur_mini_card.html`, JVM unit tests,
and a new GitHub Actions workflow that builds debug+release APKs as
artifacts (lint warnings no longer fail the build). **Unverified:**
nothing was compiled or run in this sandbox (no JDK/Gradle/Rust);
first CI run is the real check. Audio output remains simulated.

## Letter-wave rebuild (2026-11-22)

See `LETTER_WAVE_REBUILD.md` for the full status. Summary: word-level blur
was rejected by the user and replaced by the reference letter-wave law —
long held words (n>=2, dur>=1400ms, >=2x the line median when 3+ words)
lift/scale/glow letter by letter (Latin per letter, Arabic per 3px slice
from pre-baked bitmaps), short words fade plainly (DIM 0.35 -> 1 over 250ms).
Both surfaces (full LyricsPanel 20sp + mini card 18sp) use the same effect;
the mini card keeps its own rounded box but now shares the main list's
#16181D surface + hairline border. `KaraokeLine`/`WordBlurLaw` deleted;
`WordBlurLawTest` replaced by `WordWaveLawTest`.
**Verified green in CI** (run `45021399562`): assembleDebug, assembleRelease,
lint, unit tests — all SUCCESS; `aurora-apks` artifact (~30MB, debug +
release) built. Visual on-device result still pending — no emulator in this
sandbox.
