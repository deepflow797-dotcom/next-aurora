# Letter-wave rebuild — status (2026-11-22)

Branch: `arena/ad95660f-next-aurora` · Base: `12d0818` (from `main`).
This rebuild replaces the 2026-11-19 word-blur effect (see the superseded
`BLUR_REBUILD.md`).

User decisions (2026-11-22):
- **Long words** (the held ones — `n >= 2` letters, `durMs >= 1400`, and at
  least `2.0x` the line's median word duration when the line has 3+ words)
  get the **letter-wave effect**, exactly the reference demo's law:
  - Latin: real per-letter drawing (translate / scale / shadow, glow
    widths 0.10→0.26em by the letter's bump, lift 0.09em).
  - Arabic: per-slice drawing (3px slices) from pre-baked bitmaps
    (ink + glowMin + glowMax), because Arabic glyphs join and cannot be
    drawn letter by letter.
  - `bump(p)` is the demo's single-pulse curve: peak at `p = 0.40`,
    `sin(πq)^2` shape, `q` piecewise-linear.
  - `letterState`: each letter starts `lift`-ing progressively across
    the word, fully bright at `0.5*dur`; `dur` = `clamp(hold*0.5, 800, 1800)`.
- **Short words** stay plain: alpha `DIM (0.35) → 1` over `250ms`
  (no wave, no motion).
- Applies to **both** the full lyrics panel (active line, 20sp) and the
  mini card under LYRICS (18sp, same effect, next line dimmed).
- Mini card background = the **same surface** as the main large-words
  list (`#16181D`), with a hairline border so the card reads as its own
  box. **Not merged** into the list. Remaining UI untouched.

## What was built

### `spike/WordWave.kt` — new
- `WordWaveLaw`: constants + pure math (`bump`, `smooth`, `letterState`,
  `plainAlpha`, `isWaveWord`, `median`).
- `WordCache` / `bakeCache` / `bakeBitmap`: pre-baked per-word bitmaps
  (ink, glowMin 0.10em, glowMax 0.26em) at the current density, 1.8em tall,
  text baseline 1.3em from top. Padding 0.4762em on each side.
- `WordWaveLine` composable: measures words with `TextMeasurer`, lays out
  left-to-right (LTR) or right-to-left (RTL), starts every word on the
  same baseline (1.3em in a 1.8em canvas), computes the line's median
  duration (null with <3 words), then per word:
  - waved Latin → `drawLatinWave()` (per-letter translate / scale / glow);
  - waved Arabic → `drawArabicWave()` (slice-by-slice blit from the cache);
  - short → plain `drawText` with `plainAlpha`.
- No animation timer: everything is a pure function of `nowMs` (Rust
  audio-clock `Lyrics.positionMs`), like the previous blur law.

### Wiring
- `MainActivity.kt` `LyricsPanel`: active line renders via `WordWaveLine`
  (20sp, `onSurface`), replacing `KaraokeLine`. Inactive lines unchanged.
- `designsystem/Components.kt` `AuroraLyricPreviewBox`: current line renders
  via `WordWaveLine` (18sp), replacing `KaraokeLine`; **no word timings →
  plain dim text** (same rule as the full panel). Card surface matches the
  main list + 1dp hairline border (`rgba(255,255,255,0.12)`), keeps its
  rounded shape and stays a separate box.
- Deleted: `spike/KaraokeLine.kt` (`WordBlurLaw`, `KaraokeLine`),
  `preview_blur_mini_card.html` (stale blur preview),
  `WordBlurLawTest.kt` (replaced by `WordWaveLawTest.kt`).
- `NativePlayer.kt`: `LyricWord.blurProgress` kept (data-model contract)
  but its doc now points at the letter-wave law.

### Tests (`WordWaveLawTest.kt`, JVM)
`bump`/`smooth`/`plainAlpha`, `letterState` (dim at start, peak at 0.40
of the letter window, alpha progress across the word, clamped `dur`),
`isWaveWord` (n>=2, dur>=1400, 2x-median rule), `median` (null <3, upper-
middle at even size), and the kept `LyricWord.blurProgress` +
`isRtlText` tests.

### CI workflow (`.github/workflows/android.yml`)
Release-body wording updated (letter-wave rebuild).

## Verification status
**Verified green in CI** — run [`45021399562`](https://github.com/deepflow797-dotcom/next-aurora/actions/runs/45021399562)
(2026-11-22): `Assemble debug APK`, `Assemble release APK`, `Gradle lint`,
`Gradle unit tests`, both APK artifacts — all SUCCESS. APKs are attached
to the run as the `aurora-apks` artifact (debug + release,
~30MB). Kotlin compile of `WordWave.kt` + both call sites passed, and the
new `WordWaveLawTest` passed. Still unverified: on-device rendering — no
Android emulator or device in this sandbox, so the actual visual
result (Latin lift/glow, Arabic slice blit) is unverified until the user
opens the APK. Audio output remains simulated.

## Known caveats
- RTL wave words use the slice-blit method ( Arabic cannot be drawn
  letter-by-letter without breaking joined glyphs). Latin words keep the
  reference's exact per-letter drawing.
- The wave is stepped at the existing 200 ms poll rate, not per-frame —
  the demo shows lift varying on the audio clock; the Android port
  follows the same clock through `positionMs`.
- The letter-wave replaces the 2026-11-19 blur law on `refs/tags/v0.2.0-blur`;
  the previous tagged APK (and release) is unchanged.
