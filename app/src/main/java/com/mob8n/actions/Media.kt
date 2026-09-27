package com.mob8n.actions

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import com.mob8n.core.ExecutionContext
import com.mob8n.core.Gate
import com.mob8n.core.Node
import com.mob8n.core.NodeException
import com.mob8n.core.NodeInput
import com.mob8n.core.NodeKind
import com.mob8n.core.NodeResult
import com.mob8n.core.NodeSpec
import com.mob8n.core.add
import com.mob8n.core.appPicker
import com.mob8n.core.bool
import com.mob8n.core.choice
import com.mob8n.core.durationMs
import com.mob8n.core.multiline
import com.mob8n.core.number
import com.mob8n.core.out
import com.mob8n.core.text
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** Legal from any component once notification access is granted (DESIGN §2 import rules). A function, not a val: keeps JVM tests free of Android class init. */
internal fun listenerComponent() = ComponentName("com.mob8n", "com.mob8n.triggers.NotifListener")

internal fun streamOf(name: String): Int = when (name) {
    "ring" -> AudioManager.STREAM_RING; "alarm" -> AudioManager.STREAM_ALARM; "notification" -> AudioManager.STREAM_NOTIFICATION; else -> AudioManager.STREAM_MUSIC
}

object MediaControlNode : Node() {
    override val spec = NodeSpec(
        id = "action.media_control", name = "Media control", kind = NodeKind.ACTION,
        description = "Play/pause/skip/stop the active media session or set a volume stream to a percent.",
        params = listOf(
            choice("command", "Command", listOf("play", "pause", "play_pause", "next", "previous", "stop", "set_volume"), "play_pause"),
            appPicker("targetApp", "Target app", help = "Blank = the active session"),
            choice("stream", "Volume stream", listOf("music", "ring", "alarm", "notification")),
            number("percent", "Volume %", 50.0, min = 0.0, max = 100.0),
        ),
        gates = listOf(Gate.Advisory(Gate.NotificationListener), Gate.Advisory(Gate.DndPolicy)), agentTool = true,
    )

    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val cmd = ctx.str("command")
        val am = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (cmd == "set_volume") {
            val stream = streamOf(ctx.str("stream"))
            val max = am.getStreamMaxVolume(stream)
            val prev = am.getStreamVolume(stream)
            val target = ((ctx.double("percent") ?: 50.0).coerceIn(0.0, 100.0) * max / 100.0).roundToInt()
            try { am.setStreamVolume(stream, target, 0) } catch (e: SecurityException) { throw NodeException("Needs ${Gate.DndPolicy.label} to change this volume while Do Not Disturb is on", e) }
            return out(ctx.item.add("ok" to true, "previousVolume" to (if (max > 0) prev * 100 / max else 0)))
        }
        if (!Gate.NotificationListener.granted(a)) throw NodeException("Needs ${Gate.NotificationListener.label}")
        val target = ctx.strOrNull("targetApp")
        val ok = withContext(Dispatchers.Main.immediate) {
            val msm = a.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions: List<MediaController> = try { msm.getActiveSessions(listenerComponent()) } catch (e: SecurityException) { throw NodeException("Needs ${Gate.NotificationListener.label}", e) }
            val c = sessions.filter { target == null || it.packageName == target }
                .sortedByDescending { it.playbackState?.state == PlaybackState.STATE_PLAYING }.firstOrNull()
            if (c == null) {
                if (target != null) throw NodeException("No active media session for $target")
                // ponytail: no session -> media button broadcast (headset-key semantics); upgrade = remember the last session's package
                val code = when (cmd) { "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE; "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; "stop" -> KeyEvent.KEYCODE_MEDIA_STOP; else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE }
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                return@withContext false
            }
            val t = c.transportControls
            when (cmd) {
                "play" -> t.play(); "pause" -> t.pause(); "next" -> t.skipToNext(); "previous" -> t.skipToPrevious(); "stop" -> t.stop()
                else -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            }
            true
        }
        return out(ctx.item.add("ok" to ok, "previousVolume" to null))
    }
}

/** Process-wide TextToSpeech; init awaited once, utterances awaited by id. */
object Tts {
    private var engine: TextToSpeech? = null
    private val lock = Mutex()
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    suspend fun get(ctx: Context): TextToSpeech = lock.withLock {
        engine?.let { return it }
        val e = withTimeout(10_000) {
            suspendCancellableCoroutine<TextToSpeech> { cont ->
                lateinit var t: TextToSpeech
                t = TextToSpeech(ctx.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) cont.resume(t)
                    else { runCatching { t.shutdown() }; cont.resumeWithException(NodeException("Text-to-speech engine failed to start (status $status)")) }
                }
                cont.invokeOnCancellation { runCatching { t.shutdown() } } // init timeout: release the bound engine instead of leaking it
            }
        }
        e.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { waiting.remove(id)?.complete(true) }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { waiting.remove(id)?.complete(false) }
            override fun onError(id: String?, code: Int) { waiting.remove(id)?.complete(false) }
            override fun onStop(id: String?, interrupted: Boolean) { waiting.remove(id)?.complete(false) }
        })
        engine = e
        e
    }

    suspend fun speak(ctx: Context, text: String, language: String?, rate: Float, pitch: Float, wait: Boolean): Boolean {
        val e = withContext(Dispatchers.Main.immediate) { get(ctx) }
        if (!language.isNullOrBlank()) {
            val r = e.setLanguage(Locale.forLanguageTag(language))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) throw NodeException("Language $language is not available for speech")
        } else {
            // ponytail: reset the shared engine to the device default each call so a blank language does not inherit the previous node's locale; upgrade = per-run engine
            val v = e.defaultVoice
            if (v != null) e.setVoice(v) else e.setLanguage(Locale.getDefault())
        }
        e.setSpeechRate(rate.coerceIn(0.1f, 4f)); e.setPitch(pitch.coerceIn(0.1f, 4f))
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        if (wait) waiting[id] = done
        val q = e.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        if (q != TextToSpeech.SUCCESS) { waiting.remove(id); throw NodeException("Speech request rejected by the TTS engine") }
        if (!wait) return true
        return withTimeoutOrNull(60_000) { done.await() } ?: run { waiting.remove(id); throw NodeException("Speech did not finish within 60 s") }
    }
}

object TtsNode : Node() {
    override val spec = NodeSpec(
        id = "action.tts", name = "Speak (TTS)", kind = NodeKind.ACTION,
        description = "Read text aloud with the device text-to-speech engine.",
        params = listOf(
            multiline("text", "Text", "{{text}}", required = true),
            text("language", "Language (BCP-47)", help = "e.g. en-US; blank = device default"),
            number("rate", "Rate", 1.0, min = 0.1, max = 4.0), number("pitch", "Pitch", 1.0, min = 0.1, max = 4.0),
            bool("waitUntilDone", "Wait until spoken", true),
        ),
        timeoutMs = 75_000, agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val text = ctx.req("text").take(3_900) // TextToSpeech.getMaxSpeechInputLength() is 4000
        val spoken = Tts.speak(a, text, ctx.strOrNull("language"), (ctx.double("rate") ?: 1.0).toFloat(), (ctx.double("pitch") ?: 1.0).toFloat(), ctx.bool("waitUntilDone"))
        return out(ctx.item.add("spoken" to spoken))
    }
}

object PlaySoundNode : Node() {
    override val spec = NodeSpec(
        id = "action.play_sound", name = "Play sound", kind = NodeKind.ACTION,
        description = "Play the default notification/ringtone/alarm sound or a custom URI for a while.",
        params = listOf(
            choice("sound", "Sound", listOf("notification", "ringtone", "alarm", "custom")),
            text("uri", "Custom sound URI"),
            durationMs("durationMs", "Duration", 3_000, minMs = 100, maxMs = 60_000),
            choice("stream", "Stream", listOf("notification", "music", "alarm")),
        ),
        timeoutMs = 65_000, agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val uri: Uri = when (val s = ctx.str("sound")) {
            "custom" -> Uri.parse(ctx.req("uri"))
            else -> RingtoneManager.getActualDefaultRingtoneUri(a, when (s) { "ringtone" -> RingtoneManager.TYPE_RINGTONE; "alarm" -> RingtoneManager.TYPE_ALARM; else -> RingtoneManager.TYPE_NOTIFICATION })
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        }
        val usage = when (ctx.str("stream")) { "music" -> AudioAttributes.USAGE_MEDIA; "alarm" -> AudioAttributes.USAGE_ALARM; else -> AudioAttributes.USAGE_NOTIFICATION }
        val ring = RingtoneManager.getRingtone(a, uri) ?: throw NodeException("Cannot load sound $uri")
        ring.audioAttributes = AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        try {
            withContext(Dispatchers.Main.immediate) { ring.play() }
            delay(ctx.long("durationMs") ?: 3_000)
        } finally { runCatching { ring.stop() } }
        return out(ctx.item)
    }
}

object VibrateNode : Node() {
    override val spec = NodeSpec(
        id = "action.vibrate", name = "Vibrate", kind = NodeKind.ACTION,
        description = "Vibrate with an off/on millisecond pattern.",
        params = listOf(text("pattern", "Pattern (ms)", "0,200,100,200", required = true, help = "off,on,off,on,... in milliseconds")),
        agentTool = true,
    )
    override suspend fun execute(ctx: ExecutionContext, input: NodeInput): NodeResult {
        val a = ctx.requireAndroid()
        val pattern = ctx.req("pattern").split(',', ' ', ';').filter { it.isNotBlank() }.map { it.trim().toLongOrNull()?.coerceIn(0, 10_000) ?: throw NodeException("Pattern must be comma-separated milliseconds, got '$it'") }
        if (pattern.isEmpty() || pattern.sum() == 0L) throw NodeException("Vibration pattern is empty")
        val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) (a.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (a.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        if (!v.hasVibrator()) throw NodeException("This device has no vibrator")
        v.vibrate(VibrationEffect.createWaveform(pattern.toLongArray(), -1))
        return out(ctx.item)
    }
}
