# Phase log

Newest phase last. Every entry states what was actually executed, on what
hardware/environment, and what was *not* verified (PROJECT_SPEC.md 63.8).

Environment for all Phase 0 work: Fedora-class container, x86_64, **no GPU/DRM
node, no Wayland compositor, no PipeWire/ALSA device, no Android SDK/NDK, no
network access to crates.io**, Rust 1.99.0 (a standalone toolchain; `rustdoc` is
not part of it, so doctests cannot run). Anything that depends on those missing
pieces is marked as not verified rather than assumed.

---

## Phase 0 — Architecture (2026-11-13)

### Delivered

1. **Mandatory UI spike (63.5)** — `spikes/ui-spike`, separate Cargo workspace,
   candidates iced 0.14 (`iced_tiny_skia`) and egui 0.37 (epaint). Same scene:
   40 lyric lines (Arabic / English / mixed hero line), per-word
   scale+opacity+layered-glow highlight on a 60 FPS timeline, animated
   auto-follow spring, IBM Plex Sans Arabic from `assets/fonts/`, rendered
   headlessly into a 1280×800 framebuffer.
   - 60 s runs, both candidates: see the table in `ARCHITECTURE.md` §2 and
     `spikes/ui-spike/README.md`. Raw data in `spikes/ui-spike/evidence/`.
   - BiDi verdict images (`evidence/iced-probe.png`, `evidence/egui-probe.png`):
     iced orders the mixed line correctly, egui does not (reversed Latin,
     disconnected Arabic).
   - GPU % is `null`: no GPU device in this container.
2. **Technology selection** — `docs/technology-selection.md` (UI, audio decode
   and output, database, tags, lyrics parser, supporting crates), with
   alternatives, rejection reasons and risks. Measured rows are marked; the
   rest are explicitly assessments.
3. **Architecture** — `ARCHITECTURE.md`: workspace/crate layout, domain models,
   state separation, the atomic `(position, instant)` audio clock, lyrics
   discovery + estimated word timing, rendering/motion split, database and
   caching plan, testing strategy, portability traits and the Android
   Strategy A/B decision.
4. **Compiling workspace skeleton** — `aurora-core`, `aurora-lyrics`,
   `aurora-audio`, `aurora-library`, `aurora-db`, `aurora-ui`, `aurora-app`.
   Real code, no placeholder features: LRC parser with offsets/multi-timestamps,
   estimated word timing, seqlock playback clock, spring motion, lyric view
   model, path storage, file-name probe, null audio output, in-memory catalog.

### Gates (exact commands and results)

| Gate | Command | Result |
| --- | --- | --- |
| format | `rustfmt --edition 2024 --check $(find crates -name '*.rs')` | clean |
| format (spike) | `rustfmt --edition 2024 --check $(find spikes/ui-spike/src -name '*.rs')` | clean |
| lints | `cargo clippy --all-targets -- -D warnings` | 0 warnings |
| lints (spike) | `cargo clippy --release` in `spikes/ui-spike` | 0 warnings |
| tests | `cargo test --lib --bins --tests` | 28 passed, 0 failed |
| build | `cargo build` | ok |
| doctests | `cargo test --doc` | **could not run**: `rustdoc` is not installed in this toolchain |

`cargo fmt` itself is unavailable (the `cargo-fmt` subcommand is not shipped with
this standalone toolchain), so `rustfmt` was invoked directly on the same file
sets — same formatter, same edition, same config.

### Deliberately not done

- No Wayland/GPU run of the spike: no compositor, no DRM device. This is the
  single outstanding 63.5 item; the exact commands are in the spike README.
- No Android build/run of the spike: no SDK, NDK, emulator or device. Strategy A
  vs B is argued from the architecture and iced's lack of an Android backend,
  and is explicitly labelled provisional.
- No windowed app yet (that is Phase 5). `aurora-app` prints a demo frame plan.
- No audio device, so playback timing was exercised through `NullOutput` and the
  clock tests only.

### Notes / gotchas worth keeping

- The dev sandbox has no crates.io access; dependencies come from a vendored
  directory source built by `scripts/vendor/vendor.py` (documented in
  `scripts/README.md`, including the two source patches it applies).
- Two real bugs were found and fixed while making the spike honest: word boxes
  were overlapping because `LyricLine::text()` inserts its own separators (the
  spike now builds the drawn string itself), and RTL words disappeared because
  they were drawn into a full-width bound (each word now gets its own box).
- iced's `into_color` swaps R/B and tiny-skia premultiplies; the spike documents
  both in `rgba()` so colours are not a mystery next phase.

### Recommended next step

Phase 1 (Rust foundation: config, directories, error types, logging,
settings persistence, app shell wiring) — after, if possible, re-running the two
spike commands on the actual Fedora/Wayland machine so the "60 FPS on Wayland"
claim becomes verified instead of pending.

---

## Phase 1 — Rust foundation (2026-11-13)

Goal: config, directories, error types, logging, settings persistence, and app
shell wiring, with the iced stack proven to build offline before any of it is
built on top.

### 1. Offline iced build: the blocker, and how it was closed

`iced 0.14` now **builds and links** in this sandbox from the vendored directory
source (`/tmp/iced-probe`, a throwaway crate with only iced as a dependency, and
the app workspace itself). Getting there took five real fixes in
`scripts/vendor/vendor.py`, all documented in `scripts/README.md`:

1. **Tag selection had to become a curated policy.** `wayland-rs`'s default
   branch is pre-release 0.31.14 and moved `WEnum` out of `wayland_client`'s root,
   breaking `smithay-client-toolkit` 0.19/0.20 (157–184 errors). `softbuffer`'s
   branch is a 0.5-era rewrite that iced cannot compile against. Those crates are
   now in `NO_BRANCH` and come from release tags; `lyon` publishes releases from
   `main` without tags, so it is in `PREFER_HEAD`.
2. **Path dependencies are stripped.** A flat vendor tree has no
   `wayland-sys/../wayland-backend` sibling; keeping the registry version of the
   dependency is correct and unblocked `wayland-backend`.
3. **Generated files and submodule contents are grafted.** `EXTRA_TREES` copies
   the Wayland protocol XML from `wayland-mirror/wayland-protocols`,
   `swaywm/wlr-protocols` and `KDE/plasma-wayland-protocols`; without it
   `wayland_scanner` panics 108 times per build.
4. **The mirror copies of `raw-window-handle`, `softbuffer` and `wayland-*` were
   torn** (files from two releases in one directory) and are now pinned to git
   tags.
5. **X11 is cut from the build on purpose.** `x11` → `tiny-xlib` → `ctor ^0.10`,
   and `mmastrac/rust-ctor` only tags 0.13+/1.0, so an X11-enabled iced cannot be
   vendored offline. Aurora therefore builds Wayland-only; see
   "Not verified / known gaps".

### 2. What was added

- **`aurora-platform`** (new crate, Linux side of the portability seam):
  `XdgDirectories` (`AppDirectories`: config/data/cache/lyrics/database),
  `Settings` (§27 surface, TOML, atomic save, clamped on load), `logging`
  (`AURORA_LOG`), `session` (Wayland / X11-only / headless + socket probe).
- **`aurora-app`** now has a real boot sequence: args → logging → directories →
  settings → session → window or headless. Modules: `cli`, `startup`, `shell`,
  `headless`, `demo`.
- **iced shell window**: sidebar (Now Playing / Library / Settings) and a live
  settings pane; every control writes `settings.toml` immediately and the status
  line reports success or the exact error.
- **Headless mode is a real mode**: loads settings, scans the configured folders
  into the catalog, prints and logs the summary.
- **`fixtures/`** added (placeholder `.flac` + sidecar `.lrc`) so `--demo` has a
  library to scan; it was missing, which made the Phase 0 demo fail with
  "No such file or directory" on a clean checkout.

### 3. Measured here

```text
$ cargo build --workspace
    Finished `dev` profile ... (iced 0.14, tiny-skia, no wgpu, no X11)

$ cargo clippy --workspace --all-targets -- -D warnings
    (no output: 0 warnings)

$ cargo test --workspace
    16 + 2 + 9 + 1 + 3 + 11 + 22 + 2 = 66 passed, 0 failed
```

```text
$ cargo run -q -p aurora-app -- --demo
Aurora Player 0.1.0 (demo)
library root: .../fixtures
first track: Unknown Artist - Rih il-Bahar [Demo]
lyrics: 4 lines (word-timed: true)
after 1s: active line Some(0), 1 word(s) highlighted, scroll 0.0px
```

```text
$ cargo run -q -p aurora-app -- --headless --config-dir /tmp/aurora-run
 INFO aurora::startup: settings file created from defaults path=/tmp/aurora-run/aurora/settings.toml
 INFO aurora: no window; running headless session="headless"
Aurora Player 0.1.0 (headless mode)
settings: /tmp/aurora-run/aurora/settings.toml
lyrics: enabled=true animation=Standard word-highlighting=true | motion disabled=false
library: 1 folder(s), watch=true rescan-on-start=false
  /home/user/Aurora-/fixtures/music: 1 visited, 1 added, 0 skipped
 INFO aurora::headless: headless boot complete folders=1 tracks=1 catalog=1
```

A stale `WAYLAND_DISPLAY` is handled, not crashed on. iced *panics* when the
compositor connection fails, so boot probes the socket first and falls back:

```text
$ WAYLAND_DISPLAY=wayland-9 cargo run -q -p aurora-app -- --config-dir /tmp/aurora-run
 WARN aurora::startup: WAYLAND_DISPLAY is set but no compositor answers; starting headless
Aurora Player 0.1.0 (headless mode)
```

The settings file written on first run (`/tmp/aurora-run/aurora/settings.toml`)
is the §27 surface in the documented order (playback, lyrics, appearance,
performance, library), kebab-case keys, every field defaulted.

### 4. Headless render evidence

`crates/aurora-app/src/shot.rs` (test-only) drives the shell through iced's real
layout and draw passes into a `tiny_skia` pixmap — the same two passes the window
runs — and writes PNGs to `target/shots/`. Panes committed to
`docs/evidence/`:

| File | What it shows |
| --- | --- |
| `docs/evidence/shell-nowplaying.png` | sidebar + Now Playing pane, accent swatch |
| `docs/evidence/shell-library.png` | Library pane with a configured folder |
| `docs/evidence/shell-settings.png` | every §27 control, toggles showing on/off |

Three tests back this up: each pane renders more than 50 distinct colours (a
blank render fails), rendering is deterministic, and toggling four settings
changes the pixels. A fourth test *characterises an upstream iced bug*:

> `iced_tiny_skia`'s `into_color` calls
> `tiny_skia::Color::from_rgba(color.b, color.g, color.r, alpha)`, so the software
> renderer swaps red and blue. It is present on iced 0.14.0 and on iced master.
> **The screenshots therefore show the blue accent (`#4c9aff`) as orange.**
> The app does not compensate: the wgpu backend does not have the bug, so a
> channel swap in our code would make the real GPU build wrong to make the
> offline one look right. If the characterisation test ever fails, iced fixed it
> and the caveat (and the screenshots) should be refreshed.

This is also why the settings pane's palette looks salmon-ish: iced's default
primary colour is blue.

### Not verified / known gaps

- **No window was ever displayed.** The layout is proven by the headless
  renders above (same layout/draw code as the window), but there is no compositor
  and no DRM device here, so the window itself — winit surface creation, HiDPI
  scaling, input, frame pacing — is unverified. The first real check is
  `cargo run -p aurora-app` on the Fedora machine.
- **Screenshot colours are wrong on purpose**: red and blue are swapped by an
  upstream `iced_tiny_skia` bug (see above). Do not use these images as a colour
  reference for the design work.
- **X11 is not supported by this build** (see above). On an X11-only session the
  app logs a warning and starts headless instead of dying.
- **The accent colour and artwork effects are stored, not rendered.** Phase 1
  wires persistence; theming lands with the lyric view.
- **No SQLite yet.** The catalog is still `MemoryCatalog`; the headless scan is
  discarded on exit. Persistence of the *catalog* is Phase 3 work, settings
  persistence is done.
- Doctests still cannot run (no `rustdoc` in this toolchain).

### Recommended next step

Run `cargo run -p aurora-app` on the Fedora/Wayland machine (window sanity) and
`spikes/ui-spike`'s two candidate commands there (the 60 FPS claim), then Phase 2:
decoding (`symphonia`) and output (`cpal`) behind `AudioOutput`, with the clock
driven by the device counter.

---

## Phase 2 (partial) — decoders: WAV, FLAC, MP3, AAC, ALAC (2026-11-13)

Phase 2 proper is "decode + output". Output cannot be built here — no
ALSA/PipeWire, no `pkg-config`, and `cpal` needs `alsa-sys` — so this entry covers
the half that can be built and *verified*: decoding.

### Delivered

- **`aurora-audio::wav`** — a dependency-free RIFF/WAVE decoder: PCM 8/16/24/32,
  IEEE float 32/64, any channel count, `WAVE_FORMAT_EXTENSIBLE`, word-aligned
  chunks (odd sizes), `fmt ` after `data`, streamed `data` sizes. ADPCM/µ-law/
  compressed WAV are rejected by name. RF64 (>4 GiB) is not supported.
- **`aurora-audio::symphonia_decoder`** — `symphonia` 0.5.5 behind the same
  [`Decoder`] trait: FLAC, MP3, AAC, ALAC, CAF and M4A/MP4 containers. Packets
  are copied out as interleaved `f32`; a damaged packet is skipped instead of
  aborting playback (one corrupt frame should not end a track).
- **`aurora_audio::open(path)`** picks the decoder from the extension, and
  refuses unknown extensions by name instead of guessing.
- **`aurora --decode <FILE>`** decodes a whole file in 4096-sample blocks and
  prints rate/channels/bit depth, frames, duration, peak (dBFS) and RMS.
- **`fixtures/music/`**: `00 - Fixture Tone.wav` (0.05 s, −12 dBFS, 48 kHz mono,
  4.8 kB, written by the fixture script) and `00 - Fixture Steps.flac`
  (8192 frames: 4096 at 0.25 full scale, 4096 silent; 64 bytes).

### Getting `symphonia` offline

The mirror has no `symphonia`, so the whole workspace (22 crates) is vendored from
`pdeljanov/Symphonia` at tag `v0.5.5`; the vendoring script picks the right
subdirectory per crate name. Two things had to give:

- **`wav`/`aiff` features are off.** They pull `symphonia-format-riff`, which
  depends on the `extended` crate; that crate has no obtainable source here
  (GitHub 404 on every plausible repo), so WAV stays on the in-crate decoder and
  **AIFF was, at this point, not supported yet** — closed in Phase 2.6 below.
- **The FLAC fixture is generated, not borrowed.** There is no encoder on this
  machine and `flacenc` cannot be vendored (it needs `heapless` → `built`), so
  `scripts/fixtures/make-flac.py` writes a small, fully specified FLAC by hand:
  `fLaC` + STREAMINFO + two CONSTANT frames with real CRC-8/CRC-16. Symphonia
  accepting it is a genuine cross-check of both the fixture and the integration.

### Measured here

```text
$ cargo run -q -p aurora-app -- --decode "fixtures/music/00 - Fixture Steps.flac"
fixtures/music/00 - Fixture Steps.flac
  48000 Hz, 1 channel(s), 16-bit integer PCM
  8192 frames, 0.171 s
  peak 0.2500 (-12.0 dBFS), rms 0.1768

$ cargo run -q -p aurora-app -- --decode "fixtures/music/00 - Fixture Tone.wav"
  48000 Hz, 1 channel(s), 16-bit integer PCM
  2400 frames, 0.050 s
  peak 0.2500 (-12.0 dBFS), rms 0.1768
```

`cargo test --workspace`: **92 tests**, clippy `-D warnings` clean, rustfmt clean.
`aurora-audio` has 15 tests: WAV (16/24/32-bit, float, extensible headers, odd
padding, seek, bad RIFF, unsupported codec, missing file, partial reads), FLAC
through symphonia (exact sample values at both block boundaries, seek across a
block boundary), junk rejection, and the missing-file path.

### Seeking, and a bug it exposed

`Decoder::seek` is now part of the contract (frame-exact, never past the
request). WAV seeks by byte offset. Symphonia's `SeekMode::Accurate` only
guarantees landing *at or before* the request, and for a file with no SEEKTABLE
it returns the packet *containing* the target - so a naive implementation
replays the whole block. The decoder therefore remembers the requested frame and
trims the first packet after the seek (`target_frame` + `packet_frame_in_samples`
for time bases that are not `1/sample_rate`). The FLAC fixture, with its two
4096-sample blocks, is exactly the case that catches this: seek to 85.333 ms must
yield frame 4095 (`0.25`, the last of block 0) and then silence.

### Not done / not verified

- **No output device**: `NullOutput` is still the only `AudioOutput`; the clock is
  not driven by a device counter yet.
- **MP3/AAC/ALAC/CAF are compiled in but not exercised**: no encoder here can
  produce those files either, so their code paths are untested (the FLAC path
  exercises everything except the specific codecs).
- **AIFF was not supported** at this point (see above); the spec lists it, so it
  was a real gap until Phase 2.6.
- No resampling, channel mapping, gapless playback or seek yet.

---

## Phase 2.5 — playback engine, device-free (2026-11-13)

The decoder half of Phase 2 is done; output hardware is not available here. What
*can* be built and verified is the part between them: the engine that a device
callback drives.

### Delivered

- **`AudioSource`** — the pull-side contract (`sample_rate`, `channels`, `fill`,
  `seek`, `total_frames`). `DecoderSource` implements it over any `Decoder` plus
  linear gain; a future cpal/AAudio backend only has to call `fill` from its
  callback.
- **`PlaybackEngine`** — play / pause / seek / `pull`, counting frames handed to
  the device and publishing `(position, instant)` into the `PlaybackClock` from
  `aurora-core`. It never derives a position from a wall clock, which is the
  §63.4 rule: the device is the time source.
- **`NullDevice`** — models a device quantum (480 frames ≈ a 10 ms PipeWire
  graph), with `run_to_end` for tests.
- **`aurora --play <FILE>`** — runs the real engine against `NullDevice` and
  prints what the UI would show.

### Measured here

```text
$ cargo run -q -p aurora-app -- --play "fixtures/music/00 - Fixture Steps.flac"
fixtures/music/00 - Fixture Steps.flac
  played 0:00.170 of 0:00.170 in 18 device callbacks (480 frames each)
  clock position at end: 0:00.170
```

`cargo test --workspace`: **102 tests**, clippy `-D warnings` clean, rustfmt clean.
Engine tests cover: position advancing by frames rather than by callback count,
clock extrapolation from the published pair, volume scaling, pause keeping the
position and blanking the buffer, end-of-stream stopping exactly at 170.666 ms
(8 full callbacks + 1 short), seek moving both source and clock, and a broken
source stopping playback instead of being retried forever.

### Not done / not verified

- **Nothing is audible.** `NullDevice` counts frames; it does not open a device.
  The cpal backend is still blocked on `alsa-sys`, which cannot be fetched here.
- **No resampling or channel mapping**: a 44.1 kHz mono file cannot be handed to a
  48 kHz stereo device yet; `DecoderSource` reports what the file contains and the
  engine assumes the device agrees.
- **No queue, no gapless hand-off, no underrun handling**: `PlaybackEngine` plays
  one source. The transport state machine in `aurora-core` is not wired to it yet.

---

## Phase 2.6 — AIFF, and the error type gets a home (2026-11-13)

The spec (§17) lists AIFF next to WAV and FLAC, and the previous phase closed it
as "unsupported: `symphonia-format-riff` needs the `extended` crate". That was an
accurate statement about *symphonia's* feature list, not about what this project
can do: AIFF is linear PCM in a chunked container, i.e. the same problem WAV
already solved here. So it is now decoded in-crate.

### Delivered

- **`aurora-audio::aiff`** — an `AiffDecoder` over `FORM`/`AIFF` and
  `FORM`/`AIFC`:
  - `COMM` parsing, including the 80-bit IEEE 754 **extended float** sample rate
    (bias 16383, explicit leading mantissa bit - not an `f64` in disguise);
  - AIFF-C compression types `NONE`, `twos`, `sowt` (little-endian PCM),
    `fl32`/`FL32`, `fl64`/`FL64`; anything else (µ-law, A-law, IMA4 …) is
    `DecodeError::Unsupported` naming the codec it found;
  - PCM 8/16/24/32-bit, signed as the format defines - which is the one place
    AIFF and WAV disagree beyond byte order: RIFF stores 8-bit samples *unsigned*;
  - `SSND` `offset` honoured, odd-sized chunks padded, `numSampleFrames` winning
    over a longer `SSND` body, truncated files clamped instead of read past;
  - frame-exact `seek` (`SeekOutOfRange` past the end) and `total_frames`.
- **`aurora-audio::error`** — `DecodeError` moved out of `wav.rs` (it had quietly
  become the crate's error vocabulary) and grew `NotAiff`/`MissingComm`/
  `MissingSsnd` plus `DecodeError::path()`. `crate::wav::DecodeError` still
  resolves, so no call site changed.
- **One sample-scaling function.** `sample_to_f32(sample, SampleLayout)` is shared
  by both decoders; the layout travels as a value (`big_endian`, `signed_8_bit`)
  rather than as two booleans - the first draft of this refactor had the 8-bit
  branch identical on both sides, and the AIFF test caught it.
- **`fixtures/music/00 - Fixture Tone.aiff`** (4854 B) — the same 440 Hz tone as
  the WAV fixture, written by `scripts/fixtures/make-aiff.py`. Two containers, two
  byte orders, one expected result.
- `open()` dispatches `aiff`/`aif`/`aifc`; `--decode` and `--play` needed no
  changes beyond that.

### Measured here

```text
$ cargo run -q -p aurora-app -- --decode "fixtures/music/00 - Fixture Tone.aiff"
fixtures/music/00 - Fixture Tone.aiff
  48000 Hz, 1 channel(s), 16-bit integer PCM
  2400 frames, 0.050 s
  peak 0.2500 (-12.0 dBFS), rms 0.1768

$ cargo run -q -p aurora-app -- --play "fixtures/music/00 - Fixture Tone.aiff"
fixtures/music/00 - Fixture Tone.aiff
  played 0:00.050 of 0:00.050 in 6 device callbacks (480 frames each)
  clock position at end: 0:00.050
```

The `--decode` line is byte-identical to the WAV fixture's, which is the point.

`cargo test --workspace`: **118 tests** (was 102), clippy `-D warnings` clean,
rustfmt clean. The 16 new tests: extended-float round trip for six common rates
plus the exact bytes of 44.1/48 kHz headers, 16/24-bit and float decode, 8-bit
signed-vs-unsigned (both decoders, one test), `sowt`, `SSND` offset with an
odd-sized chunk in front of it, declared-frame-count trimming, seek to the end /
to frame 480 / past the end, non-FORM rejection, missing `COMM`/`SSND`, an
unimplemented compression naming itself, declared-but-absent bytes, the error
type's path helper, and the fixture equivalence test that decodes the same audio
through both decoders and compares every sample.

### Not done / not verified

- `aifc` files with `sowt` are tested from memory, not from a committed fixture
  (no encoder here writes AIFF-C); the compressed AIFF-C families stay
  unimplemented **and are reported by name**.
- Still no audible output, no resampling, no channel mapping, no queue/gapless.

---

## Phase 2.7 — Android app shell (2026-11-13)

The spec puts Android *after* the desktop app is stable (§63.9); building it now is
a deliberate scope decision, not the plan's order. What it buys: the portability
rules of §63.9 stop being aspirational, because something outside the desktop
binary now links the core crates and exercises them.

### What is actually here

| Layer | File | Verified how |
| --- | --- | --- |
| `Player` façade (open/play/pause/seek/volume/scan/state, JSON in and out) | `crates/aurora-mobile/src/facade.rs` | 17 host tests |
| `i64` handle registry, no handle reuse, stale-handle errors | `crates/aurora-mobile/src/registry.rs` | 5 host tests |
| four JNI entry points | `crates/aurora-mobile/src/jni_bridge.rs` | `cargo check --features jni-bridge` (typechecked, **never run**) |
| real-time paced device | `crates/aurora-audio/src/engine.rs` (`PacedDevice`) | 3 host tests, including a wall-clock assertion |
| Compose app: folder, library, transport, volume, Arabic strings | `android/app/src/main/**` | **not compiled** (no JDK/Gradle) |
| build recipe with precondition checks | `scripts/android/build.sh` | `--check` run, names all four missing tools |

### Measured here

The Kotlin activity does four things: create, scan, open+play, poll. The probe
binary does the same four through the same functions, so the app core is exercised
even though the app is not:

```text
$ cargo run -q -p aurora-mobile --bin aurora-mobile-probe -- fixtures/music
native handle: 1
scan: visited 3 added 3 skipped 0
  library: "Fixture Steps" ("fLaC")
  library: "Fixture Tone" ("AIFF")
  library: "Fixture Tone" ("WAVE")
open: "Fixture Steps" - 170 ms, 48000 Hz, 1 channel(s)
output: simulated output: this build has no audio device backend
state -> playing at 0 ms of 170 ms
  poll: 0 ms  [........................]
state -> finished at 170 ms of 170 ms
paused at 60 ms, still 60 ms after 250 ms of waiting (state paused)
seek to 20 ms landed at 20 ms
live players after destroy: 1
live players now: 0
```

The cdylib itself is checked too: the host build of the same source exports the
four names Kotlin will look up, which is the one JNI failure mode that can be
verified without a JVM.

```text
$ nm -D --defined-only target/debug/libaurora_mobile.so | grep Java_
T Java_app_aurora_player_NativePlayer_nativeCall
T Java_app_aurora_player_NativePlayer_nativeCreate
T Java_app_aurora_player_NativePlayer_nativeDestroy
T Java_app_aurora_player_NativePlayer_nativeLivePlayers
```

`cargo test --workspace`: **138 tests** (was 118), clippy `-D warnings` clean with
and without `--features jni-bridge`, rustfmt clean.

### Decisions worth recording

- **JSON façade instead of UniFFI.** UniFFI and its template/proc-macro stack
  cannot be vendored offline. The surface kept is the one UniFFI would generate -
  `(method, args) -> json` - with every decision in plain, tested Rust and the JNI
  layer reduced to four string-moving functions.
- **`PacedDevice` rather than pretending.** With no AAudio/Oboe backend the honest
  choices were "no timeline" or "a real-time timeline with no sound". The second
  makes the clock, the UI and the future lyrics view work today, and the screen
  prints `simulated output: this build has no audio device backend`.
- **`XdgDirectories::new(config, data, cache)`** already existed for tests, so the
  Android side needed no new directory abstraction: Kotlin passes `Context.filesDir`
  roots and the same type serves both platforms.
- **`jni` 0.22.4 came from the offline mirror**, so the bridge is written against a
  maintained crate instead of hand-rolled `JNIEnv` vtable pointer arithmetic - the
  kind of unsafe code that cannot be verified without a JVM.

### Not done / not verified

- **No APK, no device, no emulator.** No JDK, no Android SDK/NDK, and the toolchain
  ships only `x86_64-unknown-linux-gnu` std, so `cargo ndk` cannot even start.
- **The Kotlin has never been compiled.** Expect a round of import/typo fixes on the
  first `./gradlew assembleDebug`.
- **No sound.** AAudio/Oboe is as unobtainable here as ALSA/`cpal`.
- **No MediaStore/scoped-storage access**: the scanner walks a path the app can
  read. `content://` URIs are the `StorageBackend` seam, not yet implemented.
- **No foreground service, media notification or audio focus** - Kotlin glue that
  only makes sense once there is real audio.

---

## Phase 2.8 — lyrics in the app core — **WRITTEN, NOT COMPILED** (2026-11-13)

> **Read this before trusting the section.** The sandbox that built everything up
> to `c5894a8` was reset mid-phase: `/opt/rust` (the toolchain) and
> `/home/user/.cargo-vendor` (the offline crate source) lived *outside* the
> repository, so they did not survive, and `static.rust-lang.org` is unreachable
> from here. Nothing in this phase has been compiled, tested, linted or formatted.
> The last commit where `cargo test --workspace` (138 tests), `clippy -D warnings`
> and `rustfmt --check` were green is **`c5894a8`**. The code below was reviewed by
> hand against the signatures of `aurora-core`, `aurora-ui` and `aurora-lyrics`;
> that is not the same thing, and it is not claimed to be.

The unfinished half of the Strategy B experiment in `ARCHITECTURE.md` §9 was
"expose the clock and the frame plan over FFI and render a lyric screen". This is
that half, in the app core and in Compose.

### What was written

- **`crates/aurora-mobile/src/lyrics.rs`** — `LyricsSession`, which owns an
  `aurora_core` `LyricDocument` plus an `aurora_ui::LyricView`. Discovery is
  `aurora_lyrics::discover` (sidecar `.lrc`, then `<artist> - <title>.lrc` in the
  app's lyrics folder); line-only files get `estimate_word_timing` (spec 63.3), so
  the app always reads timed words. `to_json` emits, per line: text, translation,
  start/end, whether it is active, a highlight strength, and per-word strengths -
  everything Compose needs, and nothing it has to decide.
- **`Player::open` now loads lyrics with the track** (not on the first poll: a
  200 ms tick must not touch the disk), and `state` gained `hasLyrics`.
- **New command `lyrics`** on the JSON surface, plus `Lyrics`/`LyricLine`/`LyricWord`
  parsing in Kotlin.
- **`LyricsPanel` in Compose** — dim lines, the active line larger and bold, and a
  karaoke fill from each word's activation; `LaunchedEffect` follows the active
  line. BiDi is Compose's shaping, not a Kotlin pass, because the strings are mixed
  Arabic/English on purpose.
- **Probe** prints the column the way the panel consumes it.

### The tests that were written (and must be run)

`lyrics.rs` adds six: sidecar discovery on the FLAC fixture, Arabic text surviving
in logical order, active line following a published clock (none at 0 ms, line 0 at
600 ms, line 1 at 3.1 s), word activation filling across a line, "no lyrics" listing
what was tried, and opacity monotonicity. `facade.rs` adds two: the `lyrics`
command end-to-end through `dispatch`, and a track without lyrics reporting its
search paths.

```bash
cargo test -p aurora-mobile      # the first thing to run on a machine with cargo
cargo clippy --workspace --all-targets -- -D warnings
rustfmt --edition 2024 --check crates/*/src/*.rs crates/aurora-mobile/src/bin/*.rs
```

### Known caveats in this phase

- The FLAC fixture's lyric timings are 0.5-9 s while the fixture audio is 0.17 s,
  so live playback cannot reach line 0. The timing tests therefore drive the clock
  directly (`PlaybackClock::publish`), which is the same seam the device uses -
  but it means "line highlighting during real playback" is proven by unit test,
  not by watching it.
- The Kotlin in this phase is, like Phase 2.7's, uncompiled.

## Word-level blur rebuild (2026-11-19, branch arena/45bdc33d-aurora)

User decisions: priority C (blur mini-card + CI/APK), liquid glass
cancelled, rebuild from the old branch + handoff docs because the modern
source was not in this repo or on GitHub. Full status: `BLUR_REBUILD.md`.

Done:
- Merged `origin/arena/504adea6-aurora` (old desktop-first codebase) into
  this branch (fast-forward from the shared initial commit).
- Rust: lyrics JSON now includes per-word `startMs`/`endMs`
  (`crates/aurora-mobile/src/lyrics.rs`) + a test for it.
- Kotlin: `LyricWord.startMs/endMs/blurProgress()`, `Lyrics.positionMs`.
- New `spike/KaraokeLine.kt`: `WordBlurLaw` (BLUR_MAX_PX=14f,
  WORD_BLUR_MAX_PX=8f, 4 stroke passes 5/3.5/2/0.8 with alphas
  0.035/0.06/0.10/0.17), `drawBlurredWord()`, `KaraokeLine` composable.
- New `designsystem/Components.kt`: `AuroraLyricPreviewBox` (mini card
  under transport controls, next line dimmed, RTL from lyric text).
- `MainActivity`: preview box wired in; active lyric line renders via
  `KaraokeLine`; `isRtlText()` helper.
- `preview_blur_mini_card.html`: browser preview with the same `blurAt()` law.
- CI: `lint { warningsAsErrors = false; abortOnError = false }` in
  `android/app/build.gradle.kts`; new `.github/workflows/android.yml`
  (Rust fmt/clippy/test + Android lint/test/assembleDebug/assembleRelease,
  APK artifacts + Release on tags); JVM `WordBlurLawTest`.

Verification: **none possible in this sandbox** (no cargo/java/gradle).
All of the above is unverified until the first CI run. Blur updates at
the existing 200 ms poll rate on Android (stepped, not per-frame).

## Letter-wave rebuild (2026-11-22, branch arena/ad95660f-next-aurora)

User decisions: (1) replace the word-LEVEL BLUR law with the reference
letter-wave effect — long (held) words lift/scale/glow letter by letter
(Latin per letter, Arabic per slice), short words fade plainly; (2) the
mini card under LYRICS gets the same effect and a background matching the
main large-words list (same #16181D surface, hairline border), staying a
separate box (not merged); (3) UI locked after that; then next phase.

Done:
- `spike/WordWave.kt`: `WordWaveLaw` (exact port of the reference demo:
  bump/smooth/letterState/plainAlpha/isWaveWord/median), `WordCache`
  (pre-baked ink/glowMin/glowMax bitmaps), `WordWaveLine` composable
  (Latin per-letter draw with translate/scale/shadow; Arabic per-slice blit).
- `MainActivity.kt`: active lyric line now renders via `WordWaveLine` (20sp).
- `designsystem/Components.kt`: mini card (18sp) same effect; surface matches
  the main list + 1dp hairline border; separate box; no-word-timings lines
  fall back to plain dim text (same rule as the full panel).
- Deleted: `spike/KaraokeLine.kt` (`WordBlurLaw`/`KaraokeLine`),
  `preview_blur_mini_card.html`, `WordBlurLawTest.kt`.
- Tests: `WordWaveLawTest.kt` (JVM) replacing the blur tests.
- Docs: `BLUR_REBUILD.md` marked superseded; `LETTER_WAVE_REBUILD.md` added.
- CI release-body text updated.

Verification: **green in CI** (run `45021399562`, 2026-11-22): assembleDebug,
assembleRelease, lint, unit tests all SUCCESS; `aurora-apks` artifact
built (~30MB, debug + release). On-device visual result pending (no
emulator in sandbox). Audio output remains simulated.
