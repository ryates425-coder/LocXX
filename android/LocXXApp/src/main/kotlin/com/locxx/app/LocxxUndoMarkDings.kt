package com.locxx.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Short “pull back” cue when a score cell mark is undone — timbres meant as inverses of [LocxxMarkDingVariant]
 * (descending pitch, slower attack, recessive decay).
 */
enum class LocxxUndoMarkDingVariant {
    /** High tone that slides down (~1.35 kHz → ~420 Hz), like the reverse of a bright ping. */
    DESCENDING_SWOOP,

    /** Warm partials with a rounded attack then gentle release — inverse envelope shape vs soft chime. */
    SOFT_RETRACT,

    /** Lower bell partials dominated by a closing fundamental — complementary to bell stack. */
    INVERTED_BELL,

    /** Narrow-band noise / tone dipping quickly — “suction” or seal pop. */
    SUCTION_DIP,

    /** Very soft high band that falls away — barely-there cancellation. */
    WHISPER_PULL
}

object LocxxUndoMarkDingConfig {
    @JvmField
    var variant: LocxxUndoMarkDingVariant = LocxxUndoMarkDingVariant.DESCENDING_SWOOP
}

private const val SAMPLE_RATE = 44100

private fun floatsToPcm16Undo(samples: FloatArray): ShortArray =
    ShortArray(samples.size) { i ->
        (samples[i] * 32767f)
            .toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

private fun synthUndoMarkDing(v: LocxxUndoMarkDingVariant): FloatArray {
    val durationSec = when (v) {
        LocxxUndoMarkDingVariant.DESCENDING_SWOOP -> 0.13
        LocxxUndoMarkDingVariant.SOFT_RETRACT -> 0.18
        LocxxUndoMarkDingVariant.INVERTED_BELL -> 0.26
        LocxxUndoMarkDingVariant.SUCTION_DIP -> 0.085
        LocxxUndoMarkDingVariant.WHISPER_PULL -> 0.15
    }
    val n = (SAMPLE_RATE * durationSec).toInt().coerceAtLeast(1)
    val out = FloatArray(n)
    val twoPi = 2.0 * PI
    for (i in 0 until n) {
        val t = i / SAMPLE_RATE.toDouble()
        val u = i / (n - 1).coerceAtLeast(1).toDouble()
        val sample = when (v) {
            LocxxUndoMarkDingVariant.DESCENDING_SWOOP -> {
                val f0 = 1350.0
                val f1 = 410.0
                val f = f0 * kotlin.math.exp(kotlin.math.ln(f1 / f0) * u)
                val env = sin(PI * u).let { s -> s * s }
                sin(twoPi * f * t) * env * 0.92
            }
            LocxxUndoMarkDingVariant.SOFT_RETRACT -> {
                val att = 1.0 - exp(-120.0 * t)
                val rel = exp(-14.0 * t)
                val env = att * rel
                val x = sin(twoPi * 622.0 * t) * 0.5 +
                    sin(twoPi * 933.0 * t) * 0.18 +
                    sin(twoPi * 1244.0 * t) * 0.06
                x * env * 0.85
            }
            LocxxUndoMarkDingVariant.INVERTED_BELL -> {
                val envEarly = exp(-7.5 * t)
                val body = sin(twoPi * 392.0 * t) * exp(-4.0 * t) * 0.55 +
                    sin(twoPi * 587.33 * t) * exp(-12.0 * t) * 0.22 +
                    sin(twoPi * 698.46 * t) * exp(-18.0 * t) * -0.14
                val gate = 1.0 - exp(-55.0 * t)
                body * envEarly * gate * 0.95
            }
            LocxxUndoMarkDingVariant.SUCTION_DIP -> {
                val f = 880.0 * exp(-38.0 * t)
                val osc = sin(twoPi * f * t)
                val noise = sin(twoPi * 17.3 * t) * sin(twoPi * 23.7 * t)
                val env = exp(-48.0 * t) * (1.0 - exp(-200.0 * t))
                tanh((osc * 0.62 + noise * 0.22) * env * 1.5) * 0.88
            }
            LocxxUndoMarkDingVariant.WHISPER_PULL -> {
                val f = 2100.0 * exp(-12.0 * t)
                val env = (1.0 - exp(-90.0 * t)) * exp(-22.0 * t)
                sin(twoPi * f * t) * env * 0.22 +
                    sin(twoPi * f * 0.5 * t) * env * 0.08
            }
        }
        out[i] = (sample * 0.9).toFloat().coerceIn(-1f, 1f)
    }
    return out
}

fun LocxxUndoMarkDingVariant.displayLabel(): String = when (this) {
    LocxxUndoMarkDingVariant.DESCENDING_SWOOP -> "Descending swoop"
    LocxxUndoMarkDingVariant.SOFT_RETRACT -> "Soft retract"
    LocxxUndoMarkDingVariant.INVERTED_BELL -> "Inverted bell"
    LocxxUndoMarkDingVariant.SUCTION_DIP -> "Suction dip"
    LocxxUndoMarkDingVariant.WHISPER_PULL -> "Whisper pull"
}

fun playLocxxUndoMarkDingPreview(variant: LocxxUndoMarkDingVariant) {
    playUndoMarkDingPcm(synthUndoMarkDing(variant))
}

fun playLocxxUndoMarkDing() {
    playUndoMarkDingPcm(synthUndoMarkDing(LocxxUndoMarkDingConfig.variant))
}

private fun playUndoMarkDingPcm(pcmF: FloatArray) {
    thread(name = "locxx-undo-mark-ding") {
        val pcm = floatsToPcm16Undo(pcmF)
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
