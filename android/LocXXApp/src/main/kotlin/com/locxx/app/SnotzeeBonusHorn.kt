package com.locxx.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Android port of Snotzee’s Web Audio `playBonusHorn()` in `game.js`:
 * detuned sawtooths, lowpass ~1.4 kHz, same four-note “dun–dun–da–dunnnnn” timing.
 */
private const val SAMPLE_RATE = 44100

private data class HornNote(val freqHz: Double, val startSec: Double, val durationSec: Double)

private val bonusHornNotes = listOf(
    HornNote(392.0, 0.0, 0.18),
    HornNote(392.0, 0.26, 0.18),
    HornNote(440.0, 0.50, 0.10),
    HornNote(523.0, 0.62, 0.85)
)

private fun saw(tSec: Double, freqHz: Double): Float {
    val x = tSec * freqHz
    val frac = x - floor(x)
    return (2.0 * frac - 1.0).toFloat()
}

private fun noteEnvelope(timeInNoteSec: Double, durationSec: Double): Float {
    val attack = 0.018
    val release = 0.07
    if (timeInNoteSec <= 0.0) return 0f
    if (timeInNoteSec < attack) return (timeInNoteSec / attack * 0.28).toFloat()
    if (timeInNoteSec > durationSec - release) {
        val t = ((durationSec - timeInNoteSec) / release).coerceIn(0.0, 1.0)
        return (t * 0.28).toFloat()
    }
    return 0.28f
}

private fun applyLowpassRbj(samples: FloatArray, fcHz: Double, q: Double): FloatArray {
    val fs = SAMPLE_RATE.toDouble()
    val w0 = 2 * PI * fcHz / fs
    val cosw0 = cos(w0)
    val sinw0 = sin(w0)
    val alpha = sinw0 / (2 * q)
    val a0 = 1 + alpha
    val b0 = ((1 - cosw0) / 2 / a0).toFloat()
    val b1 = ((1 - cosw0) / a0).toFloat()
    val b2 = ((1 - cosw0) / 2 / a0).toFloat()
    val a1 = ((-2 * cosw0) / a0).toFloat()
    val a2 = ((1 - alpha) / a0).toFloat()
    val out = FloatArray(samples.size)
    var x1 = 0f
    var x2 = 0f
    var y1 = 0f
    var y2 = 0f
    for (i in samples.indices) {
        val x0 = samples[i]
        val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x0
        y2 = y1
        y1 = y0
        out[i] = y0
    }
    return out
}

private fun synthesizeBonusHornPcm(): FloatArray {
    val durationSec = 1.55
    val n = (SAMPLE_RATE * durationSec).toInt()
    val raw = FloatArray(n)
    for (i in 0 until n) {
        val t = i / SAMPLE_RATE.toDouble()
        var s = 0f
        for ((freq, t0, dur) in bonusHornNotes) {
            if (t < t0 || t >= t0 + dur) continue
            val u = t - t0
            val env = noteEnvelope(u, dur)
            s += (saw(t, freq) + saw(t, freq * 1.012)) * 0.5f * env
        }
        raw[i] = s * 0.42f
    }
    return applyLowpassRbj(raw, fcHz = 1400.0, q = 1.5)
}

private fun floatsToPcm16(samples: FloatArray): ShortArray =
    ShortArray(samples.size) { i ->
        (samples[i] * 32767f)
            .toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

fun playSnotzeeBonusHorn() {
    thread(name = "locxx-snotzee-horn") {
        val pcmF = synthesizeBonusHornPcm()
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
            val wrote = track.write(pcm, offset, minOf(chunk, pcm.size - offset))
            if (wrote <= 0) break
            offset += wrote
        }
        val ms = (pcm.size * 1000L) / SAMPLE_RATE + 80L
        Thread.sleep(ms)
        runCatching {
            track.stop()
            track.release()
        }
    }
}
