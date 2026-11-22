package app.aurora.player

import org.json.JSONArray
import org.json.JSONObject

/**
 * The four JNI entry points exported by `aurora-mobile`.
 *
 * Nothing here decides anything: each function hands a string to Rust and gets a
 * JSON string back. The logic - playback, scanning, the clock - lives in
 * `crates/aurora-mobile/src/facade.rs`, where it is unit-tested on a normal
 * machine; this file exists only so Kotlin can reach it.
 *
 * Symbol names are `Java_app_aurora_player_NativePlayer_<name>`; renaming the
 * package or this object breaks the link at run time, which is why
 * `jni_bridge.rs` asserts the mangling in a test.
 */
object NativePlayer {
    init {
        System.loadLibrary("aurora_mobile")
    }

    external fun nativeCreate(rootsJson: String): Long
    external fun nativeCall(handle: Long, method: String, argsJson: String): String
    external fun nativeDestroy(handle: Long): Boolean
    external fun nativeLivePlayers(): Long
}

/**
 * One word of the active lyric line, with its highlight strength.
 *
 * The strength is computed in Rust by the shared view model; Compose only turns it
 * into a colour. That is the whole point of Strategy B: no animation logic lives in
 * Kotlin, so the phone and the desktop cannot disagree about what is lit.
 */
data class LyricWord(
    val text: String,
    val activation: Float,
    /** Singing window in ms (from Rust `TimedWord.start/end`). 0 when the
     *  payload predates word timings — the blur law then falls back to
     *  [activation] instead of dividing by a zero-length window. */
    val startMs: Long = 0L,
    val endMs: Long = 0L,
) {
    /**
     * Word-level blur progress `0..1` at [nowMs]:
     * `((nowMs - startMs) / (endMs - startMs))`, clamped.
     *
     * Kept for callers that need the raw singing progress. The visible
     * word effect (full line and mini card) uses WordWaveLaw instead —
     * long words wave, short words fade plainly — and is a pure function
     * of the audio-clock position, so seeks and pauses stay correct.
     */
    fun blurProgress(nowMs: Long): Float {
        if (endMs <= startMs) {
            // No usable window (legacy payload / estimated single word):
            // fall back to the Rust fade-in activation.
            return activation.coerceIn(0f, 1f)
        }
        return ((nowMs - startMs).toFloat() / (endMs - startMs).toFloat()).coerceIn(0f, 1f)
    }
}

/** One line of the lyric column. */
data class LyricLine(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val active: Boolean,
    val activation: Float,
    val words: List<LyricWord>,
)

/** The lyric column for the current position. */
data class Lyrics(
    val available: Boolean,
    val source: String,
    val wordTimed: Boolean,
    val activeLine: Int?,
    val scrollPx: Float,
    val lines: List<LyricLine>,
    val tried: List<String>,
    /** Audio-clock position in ms at the instant this payload was produced.
     *  This is `nowMs` for the word-blur law. 0 when absent. */
    val positionMs: Long = 0L,
) {
    companion object {
        fun from(json: JSONObject): Lyrics {
            val lines = json.optJSONArray("lines")
            val parsed = ArrayList<LyricLine>(lines?.length() ?: 0)
            if (lines != null) {
                for (index in 0 until lines.length()) {
                    val line = lines.getJSONObject(index)
                    val words = line.optJSONArray("words")
                    val parsedWords = ArrayList<LyricWord>(words?.length() ?: 0)
                    if (words != null) {
                        for (w in 0 until words.length()) {
                            val word = words.getJSONObject(w)
                            parsedWords.add(
                                LyricWord(
                                    text = word.optString("text"),
                                    activation = word.optDouble("activation", 0.0).toFloat(),
                                    startMs = word.optLong("startMs", 0L),
                                    endMs = word.optLong("endMs", 0L),
                                )
                            )
                        }
                    }
                    parsed.add(
                        LyricLine(
                            text = line.optString("text"),
                            startMs = line.optLong("startMs"),
                            endMs = line.optLong("endMs"),
                            active = line.optBoolean("active", false),
                            activation = line.optDouble("activation", 0.0).toFloat(),
                            words = parsedWords,
                        )
                    )
                }
            }
            val tried = ArrayList<String>()
            json.optJSONArray("tried")?.let { array ->
                for (index in 0 until array.length()) tried.add(array.getString(index))
            }
            return Lyrics(
                available = json.optBoolean("available", false),
                source = json.optString("source"),
                wordTimed = json.optBoolean("wordTimed", false),
                activeLine = if (json.isNull("activeLine")) null else json.optInt("activeLine"),
                scrollPx = json.optDouble("scrollPx", 0.0).toFloat(),
                lines = parsed,
                tried = tried,
                positionMs = json.optLong("positionMs", 0L),
            )
        }
    }
}

/** One track the scanner found. */
data class LibraryTrack(
    val path: String,
    val title: String,
    val artist: String,
    val codec: String?,
    val durationMs: Long,
)

/** Everything the screen renders, parsed from one `state` call. */
data class Snapshot(
    val state: String,
    val positionMs: Long,
    val durationMs: Long?,
    val volume: Float,
    val output: String,
    val title: String?,
    val subtitle: String?,
    val error: String?,
) {
    val isPlaying: Boolean get() = state == "playing"

    companion object {
        fun from(json: JSONObject): Snapshot {
            val track = json.optJSONObject("track")
            val artist = track?.optString("artist").orEmpty()
            val rate = track?.optInt("sampleRateHz", 0) ?: 0
            val channels = track?.optInt("channels", 0) ?: 0
            return Snapshot(
                state = json.optString("state", "idle"),
                positionMs = json.optLong("positionMs", 0),
                durationMs = if (json.isNull("durationMs")) null else json.optLong("durationMs"),
                volume = json.optDouble("volume", 1.0).toFloat(),
                output = json.optString("output", ""),
                title = track?.optString("title"),
                subtitle = if (track == null) null else "$artist · ${rate / 1000} kHz · ${channels}ch",
                error = if (json.isNull("error")) null else json.optString("error"),
            )
        }

        fun error(message: String) = Snapshot(
            state = "error",
            positionMs = 0,
            durationMs = null,
            volume = 1f,
            output = "",
            title = null,
            subtitle = null,
            error = message,
        )
    }
}

/**
 * A handle plus the JSON plumbing.
 *
 * Deliberately dumb: every call crosses JNI once and returns the whole state, so
 * the UI can never hold a half-updated model. That is cheap here (a few hundred
 * bytes of JSON per poll) and removes a whole class of desynchronisation bugs.
 */
class Player(configRoot: String, dataRoot: String, cacheRoot: String) : AutoCloseable {
    private var handle: Long = NativePlayer.nativeCreate(
        JSONObject()
            .put("configRoot", configRoot)
            .put("dataRoot", dataRoot)
            .put("cacheRoot", cacheRoot)
            .toString()
    )

    val isOpen: Boolean get() = handle != 0L

    private fun call(method: String, args: JSONObject = JSONObject()): JSONObject {
        if (handle == 0L) {
            return JSONObject().put("error", "no_such_player").put("message", "player is closed")
        }
        val response = NativePlayer.nativeCall(handle, method, args.toString())
        return JSONObject(response)
    }

    fun state(): Snapshot {
        val json = call("state")
        return if (json.has("error")) Snapshot.error(json.optString("message")) else Snapshot.from(json)
    }

    fun open(path: String): Snapshot {
        val json = call("open", JSONObject().put("path", path))
        return if (json.has("error")) Snapshot.error(json.optString("message")) else state()
    }

    fun play(): Snapshot = toggle("play")
    fun pause(): Snapshot = toggle("pause")
    fun toggle(): Snapshot = toggle("toggle")

    private fun toggle(method: String): Snapshot {
        val json = call(method)
        return if (json.has("error")) Snapshot.error(json.optString("message")) else Snapshot.from(json)
    }

    fun seek(positionMs: Long): Snapshot {
        call("seek", JSONObject().put("positionMs", positionMs))
        return state()
    }

    fun setVolume(volume: Float): Snapshot {
        call("setVolume", JSONObject().put("volume", volume.toDouble()))
        return state()
    }

    /** The lyric column for the current position. Advances the shared scroll by one frame. */
    fun lyrics(): Lyrics = Lyrics.from(call("lyrics"))

    fun scan(folder: String): List<LibraryTrack> {
        val json = call("scan", JSONObject().put("folder", folder))
        val array: JSONArray = json.optJSONArray("library") ?: JSONArray()
        return (0 until array.length()).map { index ->
            val track = array.getJSONObject(index)
            LibraryTrack(
                path = track.optString("path"),
                title = track.optString("title"),
                artist = track.optString("artist"),
                codec = if (track.isNull("codec")) null else track.optString("codec"),
                durationMs = track.optLong("durationMs"),
            )
        }
    }

    override fun close() {
        if (handle != 0L) {
            NativePlayer.nativeDestroy(handle)
            handle = 0L
        }
    }
}
