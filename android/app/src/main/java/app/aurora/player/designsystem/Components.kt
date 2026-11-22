package app.aurora.player.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aurora.player.LyricLine
import app.aurora.player.Lyrics
import app.aurora.player.spike.WordWaveLine

/**
 * Mini lyric preview card — same letter-wave law as the full lyric line.
 *
 * The small glass-less card shown under the transport controls (liquid
 * glass was cancelled, so this is a plain dark surface card): it shows
 * the current line with the same letter-wave effect as the full lyric
 * line ([WordWaveLine]), and the next line dimmed below it. Tapping the
 * card opens the full lyrics (wired by the caller via [onClick]); the
 * queue affordance stays with the caller, as before.
 *
 * Differences from the full line, by design:
 * - Smaller type (18sp, vs 20sp in the full line) — same law, smaller text.
 * - RTL: [isRtl] comes from the paragraph direction of the lyric text
 *   (see `isRtlText` in MainActivity), not from the device locale, so an
 *   Arabic song in an English UI still lays out right-to-left.
 *
 * This component lives in the app module under a `designsystem` package
 * because the rebuilt codebase has no `:designsystem` Gradle module
 * (Phase D was cancelled). It is self-contained and can be moved into a
 * real design-system module later without API changes.
 */
@Composable
fun AuroraLyricPreviewBox(
    currentLine: LyricLine?,
    nextLine: LyricLine?,
    nowMs: Long,
    isRtl: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(20.dp)
    val cardModifier = modifier
        .fillMaxWidth()
        .clip(shape)
        // Same surface as the main large-words list, plus a hairline
        // border so the card reads as its own box without a heavy frame.
        .background(MaterialTheme.colorScheme.surface, shape)
        .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 12.dp)

    Column(cardModifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "LYRICS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            Column(Modifier.weight(1f)) {
                when {
                    currentLine == null && nextLine == null -> {
                        Text(
                            text = "No lyrics",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    currentLine != null -> {
                        if (currentLine.words.isNotEmpty()) {
                            WordWaveLine(
                                words = currentLine.words,
                                nowMs = nowMs,
                                isRtl = isRtl,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        } else {
                            Text(
                                text = currentLine.text,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        nextLine?.let { next ->
                            Text(
                                text = next.text,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                    .copy(alpha = 0.4f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    else -> {
                        // No active line yet (before the first line): show the
                        // upcoming line dimmed instead of an empty card.
                        Text(
                            text = nextLine?.text.orEmpty(),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                .copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Convenience overload taking the whole [Lyrics] payload: picks the
 * active line and the following line, and uses its audio-clock
 * [Lyrics.positionMs] as `nowMs`.
 */
@Composable
fun AuroraLyricPreviewBox(
    lyrics: Lyrics?,
    isRtl: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val activeIndex = lyrics?.activeLine
    val current = activeIndex?.let { lyrics.lines.getOrNull(it) }
    val next = activeIndex?.let { lyrics.lines.getOrNull(it + 1) }
        ?: lyrics?.lines?.firstOrNull()
    AuroraLyricPreviewBox(
        currentLine = current,
        nextLine = next,
        nowMs = lyrics?.positionMs ?: 0L,
        isRtl = isRtl,
        modifier = modifier,
        onClick = onClick,
    )
}
