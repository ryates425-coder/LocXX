package com.locxx.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

enum class LocxxPenaltyBuzzerVariant {
    /** ~690 Hz harmonic stack, slight pitch drop — default game-show “wrong” buzz. */
    CLASSIC,

    /** ~940 Hz, faster decay, extra high harmonics — sharper, more piercing. */
    BRIGHT,

    /** ~360 Hz, slower decay, bullhorn-ish — deep arena / stadium feel. */
    STADIUM
}

object LocxxPenaltyBuzzerConfig {
    @JvmField
    var variant: LocxxPenaltyBuzzerVariant = LocxxPenaltyBuzzerVariant.CLASSIC
}

private const val SAMPLE_RATE = 44100

private fun floatsToPcm16(samples: FloatArray): ShortArray =
    ShortArray(samples.size) { i ->
        (samples[i] * 32767f)
            .toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

private fun normalizePeak(out: FloatArray, ceiling: Float = 0.98f) {
    var peak = 1e-6f
    for (v in out) peak = maxOf(peak, kotlin.math.abs(v))
    if (peak > ceiling && peak > 1e-5f) {
        val g = ceiling / peak
        for (i in out.indices) out[i] *= g
    }
}

private fun synthPenaltyClassic(): FloatArray {
    val dur = 0.26
    val n = (SAMPLE_RATE * dur).toInt().coerceAtLeast(1)
    val out = FloatArray(n)
    val twoPi = 2.0 * PI
    for (i in 0 until n) {
        val t = i / SAMPLE_RATE.toDouble()
        val attack = 1.0 - exp(-t * 1100.0)
        val decay = exp(-5.8 * t)
        val env = attack * decay
        val bend = 1.0 - 0.2 * (1.0 - exp(-14.0 * t))
        val f0 = 688.0 * bend
        val ph = twoPi * f0 * t
        var buzz =
            sin(ph) * 0.48 +
                sin(ph * 3.0) / 3.0 * 0.88 +
                sin(ph * 5.0) / 5.0 * 0.72 +
                sin(ph * 7.0) / 7.0 * 0.5 +
                sin(ph * 9.0) / 9.0 * 0.32
        buzz = tanh(buzz * 1.35)
        val rasp = sin(twoPi * 2140.0 * t + ph * 0.08) * 0.07 * env
        out[i] = ((buzz + rasp) * env * 1.28).toFloat().coerceIn(-1f, 1f)
    }
    normalizePeak(out)
    return out
}

private fun synthPenaltyBright(): FloatArray {
    val dur = 0.2
    val n = (SAMPLE_RATE * dur).toInt().coerceAtLeast(1)
    val out = FloatArray(n)
    val twoPi = 2.0 * PI
    for (i in 0 until n) {
        val t = i / SAMPLE_RATE.toDouble()
        val attack = 1.0 - exp(-t * 1400.0)
        val decay = exp(-8.2 * t)
        val env = attack * decay
        val bend = 1.0 - 0.12 * (1.0 - exp(-18.0 * t))
        val f0 = 948.0 * bend
        val ph = twoPi * f0 * t
        var buzz =
            sin(ph) * 0.42 +
                sin(ph * 3.0) / 3.0 * 0.92 +
                sin(ph * 5.0) / 5.0 * 0.78 +
                sin(ph * 7.0) / 7.0 * 0.55 +
                sin(ph * 9.0) / 9.0 * 0.4 +
                sin(ph * 11.0) / 11.0 * 0.28
        buzz = tanh(buzz * 1.42)
        val rasp = sin(twoPi * 2680.0 * t + ph * 0.1) * 0.09 * env
        out[i] = ((buzz + rasp) * env * 1.32).toFloat().coerceIn(-1f, 1f)
    }
    normalizePeak(out)
    return out
}

private fun synthPenaltyStadium(): FloatArray {
    val dur = 0.32
    val n = (SAMPLE_RATE * dur).toInt().coerceAtLeast(1)
    val out = FloatArray(n)
    val twoPi = 2.0 * PI
    for (i in 0 until n) {
        val t = i / SAMPLE_RATE.toDouble()
        val attack = 1.0 - exp(-t * 620.0)
        val decay = exp(-4.0 * t)
        val env = attack * decay
        val bend = 1.0 - 0.25 * (1.0 - exp(-10.0 * t))
        val f0 = 362.0 * bend
        val phM = twoPi * f0 * t
        val phH = twoPi * (f0 * 1.84 + 2.0 * sin(twoPi * 4.2 * t)) * t
        var buzz =
            sin(phM) * 0.5 +
                sin(phM * 2.0) / 2.0 * 0.38 +
                sin(phM * 3.0) / 3.0 * 0.62 +
                sin(phM * 5.0) / 5.0 * 0.48 +
                sin(phH) * 0.12 * exp(-2.8 * t)
        buzz = tanh(buzz * 1.22)
        val air = sin(twoPi * 1180.0 * t) * 0.05 * env * exp(-6.0 * t)
        out[i] = ((buzz + air) * env * 1.26).toFloat().coerceIn(-1f, 1f)
    }
    normalizePeak(out)
    return out
}

private fun synthPenaltyBuzzer(v: LocxxPenaltyBuzzerVariant): FloatArray = when (v) {
    LocxxPenaltyBuzzerVariant.CLASSIC -> synthPenaltyClassic()
    LocxxPenaltyBuzzerVariant.BRIGHT -> synthPenaltyBright()
    LocxxPenaltyBuzzerVariant.STADIUM -> synthPenaltyStadium()
}

private fun playPenaltyBuzzerPcm(pcmF: FloatArray) {
    val pcm = floatsToPcm16(pcmF)
    val minBytes =
        AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
    if (minBytes <= 0) return
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
        return
    }
    if (track.state != AudioTrack.STATE_INITIALIZED) {
        track.release()
        return
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

/** Preview in Sound settings; does not change [LocxxPenaltyBuzzerConfig]. */
fun playLocxxPenaltyBuzzerPreview(variant: LocxxPenaltyBuzzerVariant) {
    thread(name = "locxx-penalty-buzzer-preview", priority = Thread.NORM_PRIORITY - 1) {
        playPenaltyBuzzerPcm(synthPenaltyBuzzer(variant))
    }
}

/** Non-blocking one-shot for gameplay; uses [LocxxPenaltyBuzzerConfig]. */
fun playLocxxPenaltyThud() {
    thread(name = "locxx-penalty-thud", priority = Thread.NORM_PRIORITY - 1) {
        playPenaltyBuzzerPcm(synthPenaltyBuzzer(LocxxPenaltyBuzzerConfig.variant))
    }
}
