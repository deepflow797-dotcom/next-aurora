package app.aurora.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aurora.player.designsystem.AuroraLyricPreviewBox
import app.aurora.player.spike.WordWaveLine

/**
 * One screen: folder, library, transport.
 *
 * Everything visual is a pure function of [UiState]; the only side effects are
 * calls into [PlayerViewModel]. That split is why the podcast/demo screens the
 * desktop build will grow can reuse the same model.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AuroraTheme {
                val model: PlayerViewModel = viewModel()
                val ui by model.ui.collectAsStateWithLifecycle()
                AuroraScreen(
                    ui = ui,
                    onFolder = model::setFolder,
                    onScan = model::scan,
                    onOpen = model::open,
                    onToggle = model::toggle,
                    onSeek = model::seek,
                    onVolume = model::setVolume,
                )
            }
        }
    }
}

@Composable
fun AuroraTheme(content: @Composable () -> Unit) {
    // Dark by default: this is a music player, and the spec's shell is dark.
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF7FB2FF),
            background = Color(0xFF0E0F12),
            surface = Color(0xFF16181D),
        ),
        content = content,
    )
}

@Composable
fun AuroraScreen(
    ui: UiState,
    onFolder: (String) -> Unit,
    onScan: () -> Unit,
    onOpen: (LibraryTrack) -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
) {
    val snapshot = ui.snapshot
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = ui.folder,
                    onValueChange = onFolder,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(R.string.library_hint)) },
                )
                Spacer(Modifier.padding(4.dp))
                Button(onClick = onScan, enabled = !ui.busy) {
                    Text(stringResource(R.string.scan))
                }
            }

            // Now playing.
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = snapshot?.title ?: stringResource(R.string.nothing_loaded),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = snapshot?.subtitle.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                snapshot?.error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            var dragging by remember { mutableStateOf<Float?>(null) }
            val duration = (snapshot?.durationMs ?: 0L).coerceAtLeast(1L)
            val position = dragging?.toLong() ?: (snapshot?.positionMs ?: 0L)
            Slider(
                value = position.coerceIn(0L, duration).toFloat(),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { onSeek(it.toLong()) }
                    dragging = null
                },
                valueRange = 0f..duration.toFloat(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = format(position), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = snapshot?.durationMs?.let(::format) ?: "--:--",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onToggle) {
                    Icon(
                        imageVector = if (snapshot?.isPlaying == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (snapshot?.isPlaying == true) R.string.pause else R.string.play
                        ),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = snapshot?.volume ?: 1f,
                    onValueChange = onVolume,
                    modifier = Modifier.weight(1f),
                )
            }

            // Mini lyric preview card under the transport controls:
            // current line with word-level blur, next line dimmed.
            // Tapping it scrolls the full lyric panel to the active line
            // (the panel below is the full lyrics; there is no separate
            // full-screen lyrics screen in this rebuilt codebase).
            AuroraLyricPreviewBox(
                lyrics = ui.lyrics,
                isRtl = isRtlText(
                    ui.lyrics?.activeLine?.let { ui.lyrics.lines.getOrNull(it)?.text }
                        ?: ui.lyrics?.lines?.firstOrNull()?.text
                ),
            )

            LyricsPanel(ui.lyrics, Modifier.height(220.dp))

            // Say it on screen: the timeline is real, the sound is not.
            Text(
                text = snapshot?.output ?: stringResource(R.string.simulated_output),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp),
            )

            Spacer(Modifier.height(4.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(ui.library) { track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${track.artist} · ${track.codec ?: "?"} · ${format(track.durationMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Button(onClick = { onOpen(track) }) {
                            Text(stringResource(R.string.play))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The lyric column.
 *
 * Everything here is a *rendering* of the plan Rust produced: opacity from
 * [LyricLine.activation], the karaoke fill from [LyricWord.activation], the active
 * line from `activeLine`. When a line is not active it stays dim, which is what
 * makes the current line readable at a glance on a phone held at arm's length.
 *
 * BiDi comes for free: Compose shapes each line with cosmic-text-like logic and
 * applies the paragraph direction per string, so mixed Arabic/English lines are
 * laid out correctly without a Kotlin-side bidi pass.
 */
@Composable
private fun LyricsPanel(lyrics: Lyrics?, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    val active = lyrics?.activeLine
    LaunchedEffect(active) {
        if (active != null) {
            // Keep the sung line roughly centred; the native side owns the spring,
            // this only follows it so the list does not drift off-screen.
            state.animateScrollToItem(active.coerceAtLeast(0))
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        when {
            lyrics == null -> Unit
            !lyrics.available -> Text(
                text = "no lyrics" + if (lyrics.tried.isEmpty()) "" else " (looked for ${lyrics.tried.size} file(s))",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(state = state) {
                items(lyrics.lines) { line ->
                    if (line.active && line.words.isNotEmpty()) {
                        // Active line: letter-wave effect (long words wave,
                        // short words fade plainly). Pure function of the
                        // audio-clock position.
                        WordWaveLine(
                            words = line.words,
                            nowMs = lyrics.positionMs,
                            isRtl = isRtlText(line.text),
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        )
                    } else {
                        Text(
                            text = lineText(line, MaterialTheme.colorScheme.primary),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Builds one line, tinting each word by its own activation (karaoke fill). */
private fun lineText(line: LyricLine, accent: Color): AnnotatedString = buildAnnotatedString {
    if (line.words.isEmpty()) {
        append(line.text)
        return@buildAnnotatedString
    }
    line.words.forEachIndexed { index, word ->
        if (index > 0) append(' ')
        withStyle(
            SpanStyle(
                color = accent.copy(
                    alpha = if (line.active) 0.35f + 0.65f * word.activation else 0f
                )
            )
        ) {
            append(word.text)
        }
    }
}

/**
 * Paragraph direction of lyric [text]: true when the first strong
 * directional character is right-to-left (Arabic/Hebrew).
 *
 * This is deliberately based on the lyric text, not the device locale:
 * an Arabic song in an English UI must still lay out RTL, and an English
 * song on an Arabic phone must stay LTR. Weak characters (spaces,
 * punctuation, digits) are skipped, matching the Unicode bidi rule for
 * paragraph direction. Null/empty text defaults to LTR.
 */
internal fun isRtlText(text: String?): Boolean {
    if (text.isNullOrEmpty()) return false
    for (ch in text) {
        when (Character.getDirectionality(ch)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
        }
    }
    return false
}

private fun format(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
