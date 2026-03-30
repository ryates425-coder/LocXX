package com.locxx.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

enum class LocxxDiceRollSoundVariant {
    /** Current rustling / flutter (~1.05 s). */
    ORIGINAL_FLUTTER,

    /** Faster chatter, tighter clacks (~0.9 s). */
    RATTLE,

    /** Slower, deeper cup shake (~1.12 s). */
    CUP_SHAKE,

    /** Shorter, brighter table-top clickiness (~0.58 s). */
    CRISP_CLICK,

    /** Rhythmic tap bursts like dice hitting wood (~0.82 s). */
    TABLE_TAP
}

object LocxxDiceRollFlutterConfig {
    @JvmField
    var enabled: Boolean = true

    @JvmField
    var variant: LocxxDiceRollSoundVariant = LocxxDiceRollSoundVariant.ORIGINAL_FLUTTER
}

private const val SAMPLE_RATE = 44100

private fun floatsToPcm16(samples: FloatArray): ShortArray =
    ShortArray(samples.size) { i ->
        (samples[i] * 32767f)
            .toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

private class XorShift01(private var seed: Long) {
    fun next01(): Double {
        seed = seed xor (seed shl 13)
        seed = seed xor (seed ushr 7)
        seed = seed xor (seed shl 17)
        return (seed ushr 16 and 0xFFFF).toDouble() / 65535.0
    }
}

private fun normalizePeak(out: FloatArray, ceiling: Float = 0.92f) {
    var peak = 1e-6f
    for (v in out) peak = maxOf(peak, kotlin.math.abs(v))
    if (peak > ceiling && peak > 1e-5f) {
        val g = ceiling / peak
        for (i in out.indices) out[i] *= g
    }
}

private fun onePoleSmooth(samples: FloatArray, coeff: Float) {
    var y = 0f
    val a = coeff.coerceIn(0f, 0.999f)
    for (i in samples.indices) {
        y = a * y + (1f - a) * samples[i]
        samples[i] = y
    }
}

/** Rustling / fluttering dice noise — original LocXX recipe. */
private fun synthDiceRollOriginalFlutter(): FloatArray {
    val dur = 1.05
    val n = (SAMPLE_RATE * dur).toInt()
    val rng = XorShift01(2463534242L)
    val twoPi = 2.0 * PI
    val out = FloatArray(n)
    for (i in 0 until n) {
        val t = i / SAMPLE_RATE.toDouble()
        val noise = (_rngNextBipolar(rng))
        val flutterA = 0.52 + 0.48 * sin(twoPi * 10.7 * t)
        val flutterB =
            0.58 + 0.42 * sin(twoPi * 4.15 * t + 0.9 * sin(twoPi * 2.1 * t))
        val envelope =
            exp(-0.55 * t) * (0.82 + 0.18 * sin(twoPi * 7.2 * t))
        var s = noise * (flutterA * flutterB).toFloat() * envelope.toFloat() * 0.26f
        val grit =
            (sin(twoPi * 180.0 * t + noise * 4.0) * 0.04 * envelope).toFloat()
        s = (s + grit).coerceIn(-1f, 1f)
        out[i] = s
    }
    onePoleSmooth(out, 0.78f)
    normalizePeak(out)
    return out
}

private fun _rngNextBipolar(rng: XorShift01): Float {
    return (rng.next01() * 2.0 - 1.0).toFloat()
}

private fun synthDiceRollRattle(): FloatArray {
    val dur = 0.9
    val n = (SAMPLE_RATE * dur).toInt()
    val rng = XorShift01(1802240333L)
    val twoPi = 2.0 * PI
    val out = FloatArray(n)
    for (i in 0 until n) {
        val t = i / SAMPLE_RATE.toDouble()
        val noise = _rngNextBipolar(rng)
        val chatter =
            (0.45 + 0.55 * sin(twoPi * 38.0 * t) * sin(twoPi * 52.0 * t))
        val shake = 0.35 + 0.65 * sin(twoPi * 18.0 * t + 1.2 * sin(twoPi * 9.0 * t))
        val env = exp(-1.35 * t) * (0.72 + 0.28 * sin(twoPi * 11.0 * t))
        var s =
            noise * chatter.toFloat() * shake.toFloat() * env.toFloat() * 0.34f
        val click =
            (sin(twoPi * 240.0 * t + noise * 6.0) * 0.055 * env).toFloat()
        out[i] = (s + click).coerceIn(-1f, 1f)
    }
    onePoleSmooth(out, 0.62f)
    normalizePeak(out)
    return out
}

private fun synthDiceRollCupShake(): FloatArray {
    val dur = 1.12
    val n = (SAMPLE_RATE * dur).toInt()
    val rng = XorShift01(4104159847L)
    val twoPi = 2.0 * PI
    val out = FloatArray(n)
    for (i in 0 until n) {
        val t = i / SAMPLE_RATE.toDouble()
        val noise = _rngNextBipolar(rng)
        val slowA = 0.48 + 0.52 * sin(twoPi * 3.2 * t + 0.4 * sin(twoPi * 1.1 * t))
        val slowB = 0.55 + 0.45 * sin(twoPi * 5.4 * t)
        val env =
            exp(-0.48 * t) * (0.78 + 0.22 * sin(twoPi * 2.8 * t))
        var s = noise * slowA.toFloat() * slowB.toFloat() * env.toFloat() * 0.22f
        val low =
            (sin(twoPi * 95.0 * t + noise * 2.0) * 0.045 * env).toFloat()
        out[i] = (s + low).coerceIn(-1f, 1f)
    }
    onePoleSmooth(out, 0.86f)
    normalizePeak(out)
    return out
}

private fun synthDiceRollCrispClick(): FloatArray {
    val dur = 0.58
    val n = (SAMPLE_RATE * dur).toInt()
    val rng = XorShift01(3016284511L)
    val twoPi = 2.0 * PI
    val out = FloatArray(n)
    for (i in 0 until n) {
        val t = i / SAMPLE_RATE.toDouble()
        val noise = _rngNextBipolar(rng)
        val env = exp(-11.0 * t) * (0.88 + 0.12 * sin(twoPi * 24.0 * t))
        val tick =
            (0.62 + 0.38 * sin(twoPi * 110.0 * t) * sin(twoPi * 88.0 * t))
        var s = noise * tick.toFloat() * env.toFloat() * 0.36f
        val hi =
            (sin(twoPi * 620.0 * t + noise * 8.0) * 0.08 * env).toFloat()
        out[i] = (s + hi).coerceIn(-1f, 1f)
    }
    onePoleSmooth(out, 0.48f)
    normalizePeak(out)
    return out
}

private fun synthDiceRollTableTap(): FloatArray {
    val dur = 0.82
    val n = (SAMPLE_RATE * dur).toInt()
    val rng = XorShift01(2725430981L)
    val tapTimes = mutableListOf<Double>()
    var tAcc = 0.052
    var ti = 0
    while (tAcc < dur) {
        tapTimes.add(tAcc)
        tAcc += 0.041 + (ti % 5) * 0.007 + rng.next01() * 0.024
        ti++
    }
    val tapW = DoubleArray(tapTimes.size) { 0.48 + rng.next01() * 0.52 }
    val noiseRng = XorShift01(998244353L)
    val twoPi = 2.0 * PI
    val out = FloatArray(n)
    for (i in 0 until n) {
        val t = i / SAMPLE_RATE.toDouble()
        val noise = _rngNextBipolar(noiseRng)
        val env = exp(-2.15 * t)
        var impulse = 0.0
        for (j in tapTimes.indices) {
            val dt = kotlin.math.abs(t - tapTimes[j])
            if (dt < 0.028) impulse += exp(-dt * 400.0) * tapW[j]
        }
        val body =
            0.5 + 0.5 * sin(twoPi * 28.0 * t) * sin(twoPi * 36.0 * t)
        var s =
            noise * body.toFloat() * env.toFloat() * 0.18f +
                noise * impulse.toFloat() * env.toFloat() * 0.52f
        val rim =
            (sin(twoPi * 480.0 * t + impulse * 12.0) * 0.06 * env).toFloat()
        out[i] = (s + rim).coerceIn(-1f, 1f)
    }
    onePoleSmooth(out, 0.58f)
    normalizePeak(out)
    return out
}

private fun synthDiceRoll(v: LocxxDiceRollSoundVariant): FloatArray = when (v) {
    LocxxDiceRollSoundVariant.ORIGINAL_FLUTTER -> synthDiceRollOriginalFlutter()
    LocxxDiceRollSoundVariant.RATTLE -> synthDiceRollRattle()
    LocxxDiceRollSoundVariant.CUP_SHAKE -> synthDiceRollCupShake()
    LocxxDiceRollSoundVariant.CRISP_CLICK -> synthDiceRollCrispClick()
    LocxxDiceRollSoundVariant.TABLE_TAP -> synthDiceRollTableTap()
}

private fun playDiceRollPcm(pcmF: FloatArray) {
    val pcm = floatsToPcm16(pcmF)
    val minBytes = AudioTrack.getMinBufferSize(
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
    val chunk = 4096
    while (offset < pcm.size) {
        val w = track.write(pcm, offset, minOf(chunk, pcm.size - offset))
        if (w <= 0) break
        offset += w
    }
    val ms = (pcm.size * 1000L) / SAMPLE_RATE + 80L
    Thread.sleep(ms)
    runCatching {
        track.stop()
        track.release()
    }
}

/** Preview any variant in Sound settings (does not change [LocxxDiceRollFlutterConfig]). */
fun playLocxxDiceRollSoundPreview(variant: LocxxDiceRollSoundVariant) {
    thread(name = "locxx-dice-roll-preview", priority = Thread.NORM_PRIORITY - 1) {
        playDiceRollPcm(synthDiceRoll(variant))
    }
}

/** Non-blocking: plays selected dice roll sound for one roll cycle. */
fun playLocxxDiceRollFlutter() {
    if (!LocxxDiceRollFlutterConfig.enabled) return
    thread(name = "locxx-dice-flutter", priority = Thread.NORM_PRIORITY - 1) {
        playDiceRollPcm(synthDiceRoll(LocxxDiceRollFlutterConfig.variant))
    }
}
