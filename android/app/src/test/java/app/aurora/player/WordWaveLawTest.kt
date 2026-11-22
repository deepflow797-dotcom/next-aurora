package app.aurora.player

import app.aurora.player.spike.WordWaveLaw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-math tests for the letter-wave law — the reference demo's exact rule:
 * long (held) words lift/glow/scale letter by letter, short words fade plainly.
 * Runs on the JVM (no Android) because [WordWaveLaw] has no Android dependencies.
 */
class WordWaveLawTest {

    @Test
    fun bumpIsZeroAtBothEndsAndOneAtThePeak() {
        assertEquals(0f, WordWaveLaw.bump(0f), 0.001f)
        assertEquals(0f, WordWaveLaw.bump(1f), 0.001f)
        assertEquals(1f, WordWaveLaw.bump(WordWaveLaw.PEAK_AT), 0.001f)
        // Smoothstep-like: half of the bump at the edges of the peak.
        assertEquals(0.933f, WordWaveLaw.bump(0.5f), 0.01f)
        // Outside the window: nothing.
        assertEquals(0f, WordWaveLaw.bump(-0.5f), 0.001f)
        assertEquals(0f, WordWaveLaw.bump(1.5f), 0.001f)
    }

    @Test
    fun smoothIsClampedSmoothstep() {
        assertEquals(0f, WordWaveLaw.smooth(0f), 0.001f)
        assertEquals(0.5f, WordWaveLaw.smooth(0.5f), 0.001f)
        assertEquals(1f, WordWaveLaw.smooth(1f), 0.001f)
        assertEquals(0f, WordWaveLaw.smooth(-2f), 0.001f)
        assertEquals(1f, WordWaveLaw.smooth(2f), 0.001f)
    }

    @Test
    fun plainAlphaFadesFromDimToOneOverTheWindow() {
        assertEquals(WordWaveLaw.DIM, WordWaveLaw.plainAlpha(0f, 0f), 0.001f)
        assertEquals(0.35f + 0.65f * 0.5f, WordWaveLaw.plainAlpha(125f, 0f), 0.001f)
        assertEquals(1f, WordWaveLaw.plainAlpha(250f, 0f), 0.001f)
        assertEquals(1f, WordWaveLaw.plainAlpha(1000f, 0f), 0.001f)
    }

    @Test
    fun letterStateIsAtDimAtLetterStart() {
        val st = WordWaveLaw.LetterState()
        WordWaveLaw.letterState(0f, 0f, 2400f, 0, 6, st)
        assertEquals(0f, st.lift, 0.001f)
        assertEquals(WordWaveLaw.DIM, st.lit, 0.001f)
        assertEquals(1f, st.sc, 0.001f)
        assertEquals(0f, st.ga, 0.001f)
    }

    @Test
    fun letterStatePeaksMidLetterWindow() {
        // dur = clamp(hold*0.5,800,1800); hold=2000 -> dur=1000;
        // lift peaks at 40% of the letter window.
        val st = WordWaveLaw.LetterState()
        WordWaveLaw.letterState(400f, 0f, 2000f, 0, 2, st)
        assertEquals(WordWaveLaw.LIFT_EM, st.lift, 0.005f)
        assertEquals(1f + WordWaveLaw.LATIN_SCALE, st.sc, 0.005f)
        assertEquals(WordWaveLaw.GLOW_A, st.ga, 0.005f)
        assertEquals(WordWaveLaw.DIM + (1f - WordWaveLaw.DIM) * WordWaveLaw.smooth(0.8f), st.lit, 0.01f)
    }

    @Test
    fun letterStateLiftsProgressivelyAcrossLetters() {
        // 4 letters spread over 3400ms hold -> dur=1700, spread=1700.
        // Letter 0 starts at t=0, letter 3 at t=1700.
        val st0 = WordWaveLaw.LetterState()
        val st3 = WordWaveLaw.LetterState()
        WordWaveLaw.letterState(400f, 0f, 3400f, 0, 4, st0)
        WordWaveLaw.letterState(400f, 0f, 3400f, 3, 4, st3)
        // Letter 0 is already 100ms in and lifts; letter 3 is silent.
        assertTrue(st0.lift > 0.01f)
        assertEquals(0f, st3.lift, 0.001f)
    }

    @Test
    fun waveConditionMatchesTheDemo() {
        // n >= 2 AND dur >= 1400 AND (no median OR dur >= 2x median)
        assertTrue(!WordWaveLaw.isWaveWord(1, 5000L, null))
        assertTrue(!WordWaveLaw.isWaveWord(2, 1399L, null))
        assertTrue(WordWaveLaw.isWaveWord(2, 1400L, null))
        assertTrue(!WordWaveLaw.isWaveWord(2, 1399L, 700L))   // 2 x 700 = 1400 > 1399
        assertTrue(WordWaveLaw.isWaveWord(2, 1500L, 700L))    // 1500 >= 1400
        assertTrue(WordWaveLaw.isWaveWord(2, 1400L, 700L))    // 1400 == 2 x 700
        assertTrue(!WordWaveLaw.isWaveWord(3, 1000L, null))   // too short
    }

    @Test
    fun medianIsNullBelowThreeWords() {
        assertNull(WordWaveLaw.median(emptyList()))
        assertNull(WordWaveLaw.median(listOf(100L)))
        assertNull(WordWaveLaw.median(listOf(100L, 200L)))
    }

    @Test
    fun medianIsTheMiddleValue() {
        assertEquals(200L, WordWaveLaw.median(listOf(100L, 500L, 200L)))
        assertEquals(300L, WordWaveLaw.median(listOf(100L, 200L, 400L, 600L))) // upper-middle
    }

    @Test
    fun lyricWordBlurProgressUsesItsOwnWindow() {
        val word = LyricWord(text = "hello", activation = 0f, startMs = 1000, endMs = 2000)
        assertEquals(0f, word.blurProgress(1000), 0.001f)
        assertEquals(0.5f, word.blurProgress(1500), 0.001f)
        assertEquals(1f, word.blurProgress(2000), 0.001f)
    }

    @Test
    fun lyricWordWithoutWindowFallsBackToActivation() {
        val word = LyricWord(text = "hello", activation = 0.4f)
        assertEquals(0.4f, word.blurProgress(12345), 0.001f)
    }

    @Test
    fun rtlDetectionUsesFirstStrongCharacter() {
        assertEquals(true, isRtlText("سَلامٌ على الدُّنيا"))
        assertEquals(true, isRtlText("  123 سلام"))
        assertEquals(false, isRtlText("Hello Aurora"))
        assertEquals(false, isRtlText("123 Hello سلام"))
        assertEquals(false, isRtlText(""))
        assertEquals(false, isRtlText(null))
        assertEquals(false, isRtlText("  ...  "))
    }
}
