package com.locxx.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Short “ding” when the player marks a score cell. Three timbres — pick one in [LocxxMarkDingConfig].
 */
enum class LocxxMarkDingVariant {
    /** Crisp high ping (~1.3 kHz) with a little shimmer; shortest. */
    BRIGHT_PING,

    /** Warmer two-partial chime (~659 Hz + light overtone). */
    SOFT_CHIME,

    /** Bellier stack (C-major-ish partials) with a softer decay. */
    BELL_STACK
}

object LocxxMarkDingConfig {
    /** Current timbre; use Sound settings in the app or [LocxxSoundPrefs]. */
    @JvmField
    var variant: LocxxMarkDingVariant = LocxxMarkDingVariant.BELL_STACK
}

private const val SAMPLE_RATE = 44100

private fun floatsToPcm16(samples: FloatArray): ShortArray =
    ShortArray(samples.size) { i ->
        (samples[i] * 32767f)
            .toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

private fun synthMarkDing(v: LocxxMarkDingVariant): FloatArray {
    val durationSec = when (v) {
        LocxxMarkDingVariant.BRIGHT_PING -> 0.10
        LocxxMarkDingVariant.SOFT_CHIME -> 0.16
        LocxxMarkDingVariant.BELL_STACK -> 0.24
    }
    val n = (SAMPLE_RATE * durationSec).toInt().coerceAtLeast(1)
    val out = FloatArray(n)
    val twoPi = 2.0 * PI
    for (i in 0 until n) {
        val t = i / SAMPLE_RATE.toDouble()
        val sample = when (v) {
            LocxxMarkDingVariant.BRIGHT_PING -> {
                val env = exp(-43.0 * t)
                val f = 1318.25
                (sin(twoPi * f * t) * 0.5 + sin(twoPi * f * 2.02 * t) * 0.1) * env
            }
            LocxxMarkDingVariant.SOFT_CHIME -> {
                val env = exp(-22.0 * t)
                (sin(twoPi * 659.25 * t) * 0.42 + sin(twoPi * 1318.5 * t) * 0.12) * env
            }
            LocxxMarkDingVariant.BELL_STACK -> {
                val env = exp(-5.0 * t).coerceAtLeast(0.0)
                (sin(twoPi * 523.25 * t) * exp(-9.0 * t) * 0.38 +
                    sin(twoPi * 783.99 * t) * exp(-11.0 * t) * 0.22 +
                    sin(twoPi * 1046.5 * t) * exp(-14.0 * t) * 0.12) * env
            }
        }
        out[i] = (sample * 0.9).toFloat().coerceIn(-1f, 1f)
    }
    return out
}

/** Plays one [variant] for Sound settings preview (does not change [LocxxMarkDingConfig]). */
fun playLocxxMarkDingPreview(variant: LocxxMarkDingVariant) {
    playMarkDingPcm(synthMarkDing(variant))
}

/** Plays the configured mark ding on a background thread (no-op if audio fails). */
fun playLocxxMarkDing() {
    playMarkDingPcm(synthMarkDing(LocxxMarkDingConfig.variant))
}

/**
 * LAN “game about to start” countdown: [step] 0 = lowest, 2 = highest (short ascending beeps);
 * 3 = four short tones (200ms each), 1ms rest between, pitch just above step 2 (E5).
 */
fun playLocxxGameStartCountdownBeep(step: Int) {
    when (step.coerceIn(0, 3)) {
        3 -> playMarkDingPcm(synthGameStartFourLongTones())
        else -> {
            val hz = when (step) {
                0 -> 392.0 // G4
                1 -> 523.25 // C5
                else -> GAME_START_THIRD_BEEP_HZ // E5
            }
            playMarkDingPcm(synthCountdownBeep(hz))
        }
    }
}

/** Must stay a hair above [synthCountdownBeep] step 2 (E5 659.25 Hz). */
private const val GAME_START_THIRD_BEEP_HZ = 659.25

/** ~30 cents sharp of E5 — audibly “a little higher” than the third beep. */
private val GAME_START_LAST_TONE_HZ =
    GAME_START_THIRD_BEEP_HZ * 2.0.pow(30.0 / 1200.0)

/** One sustained sine for [durationSec], with short faded edges to avoid clicks. */
private fun synthGameStartSustainedTone(fHz: Double, durationSec: Double): FloatArray {
    val n = (SAMPLE_RATE * durationSec).toInt().coerceAtLeast(1)
    val out = FloatArray(n)
    val twoPi = 2.0 * PI
    val edge = (SAMPLE_RATE * 0.012).toInt().coerceAtLeast(1)
    for (i in 0 until n) {
        val t = i / SAMPLE_RATE.toDouble()
        val aIn = (i + 1).toFloat() / edge
        val aOut = (n - i).toFloat() / edge
        val env = minOf(1f, aIn, aOut).toDouble()
        out[i] = (sin(twoPi * fHz * t) * env * 0.72).toFloat().coerceIn(-1f, 1f)
    }
    return out
}

private fun synthGameStartFourLongTones(): FloatArray {
    val toneSec = 0.2
    val gapSec = 0.001
    val tone = synthGameStartSustainedTone(GAME_START_LAST_TONE_HZ, toneSec)
    val gapLen = (SAMPLE_RATE * gapSec).toInt().coerceAtLeast(0)
    val gap = FloatArray(gapLen)
    val total = tone.size * 4 + gapLen * 3
    val out = FloatArray(total)
    var o = 0
    repeat(4) { i ->
        tone.copyInto(out, o)
        o += tone.size
        if (i < 3) {
            gap.copyInto(out, o)
            o += gap.size
        }
    }
    return out
}

private fun synthCountdownBeep(fHz: Double, durationSec: Double = 0.11): FloatArray {
    val n = (SAMPLE_RATE * durationSec).toInt().coerceAtLeast(1)
    val out = FloatArray(n)
    val twoPi = 2.0 * PI
    for (i in 0 until n) {
        val t = i / SAMPLE_RATE.toDouble()
        val env = exp(-28.0 * t).coerceAtLeast(0.0)
        out[i] = (sin(twoPi * fHz * t) * env * 0.75).toFloat().coerceIn(-1f, 1f)
    }
    return out
}

private fun playMarkDingPcm(pcmF: FloatArray) {
    thread(name = "locxx-mark-ding") {
        val pcm = floatsToPcm16(pcmF)
        val minBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBytes <= 0) return@thread
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBytes.coerceAtLeast(pcm.size * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: UnsupportedOperationException) {
            return@thread
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return@thread
        }
        track.play()
        var offset = 0
        val chunk = 2048
        while (offset < pcm.size) {
            val w = track.write(pcm, offset, minOf(chunk, pcm.size - offset))
            if (w <= 0) break
            offset += w
        }
        val ms = (pcm.size * 1000L) / SAMPLE_RATE + 60L
        Thread.sleep(ms)
        runCatching {
            track.stop()
            track.release()
        }
    }
}
