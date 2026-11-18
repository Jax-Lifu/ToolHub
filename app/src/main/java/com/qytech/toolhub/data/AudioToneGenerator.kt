package com.qytech.toolhub.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.annotation.StringRes
import com.qytech.toolhub.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

enum class ToneWaveform(@param:StringRes val labelRes: Int, val baseFreqHz: Double) {
    SINE_1K(R.string.tone_wave_1k, 1000.0),
    SINE_440(R.string.tone_wave_440, 440.0),
    SINE_100(R.string.tone_wave_100, 100.0),
    SINE_10K(R.string.tone_wave_10k, 10000.0),
    SWEEP(R.string.tone_wave_sweep, 1000.0),
    WHITE_NOISE(R.string.tone_wave_noise, 0.0),
}

enum class ToneChannelMode(@param:StringRes val labelRes: Int) {
    STEREO(R.string.tone_ch_stereo),
    LEFT_ONLY(R.string.tone_ch_left),
    RIGHT_ONLY(R.string.tone_ch_right),
    PHASE_INVERT(R.string.tone_ch_invert),
}

data class AudioToneState(
    val isPlaying: Boolean = false,
    val waveform: ToneWaveform = ToneWaveform.SINE_1K,
    val channelMode: ToneChannelMode = ToneChannelMode.STEREO,
    val sampleRateHz: Int = 48000,
    val attenuationDb: Int = -6,
)

object AudioToneGenerator {

    val SUPPORTED_SAMPLE_RATES = listOf(44100, 48000, 88200, 96000, 192000)
    val SUPPORTED_DB_LEVELS = listOf(0, -3, -6, -12, -20)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playJob: Job? = null

    private val _state = MutableStateFlow(AudioToneState())
    val state: StateFlow<AudioToneState> = _state.asStateFlow()

    fun updateConfig(
        waveform: ToneWaveform = _state.value.waveform,
        channelMode: ToneChannelMode = _state.value.channelMode,
        sampleRateHz: Int = _state.value.sampleRateHz,
        attenuationDb: Int = _state.value.attenuationDb,
    ) {
        val wasPlaying = _state.value.isPlaying
        val rateChanged = sampleRateHz != _state.value.sampleRateHz
        _state.update {
            it.copy(
                waveform = waveform,
                channelMode = channelMode,
                sampleRateHz = sampleRateHz,
                attenuationDb = attenuationDb,
            )
        }
        if (wasPlaying && rateChanged) {
            start()
        }
    }

    fun start() {
        stop()
        val cfg = _state.value
        _state.update { it.copy(isPlaying = true) }

        playJob = scope.launch {
            val sampleRate = cfg.sampleRateHz
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(sampleRate / 5 * 4)

            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull()

            if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                _state.update { it.copy(isPlaying = false) }
                track?.release()
                return@launch
            }

            try {
                track.play()
                val framesPerChunk = (sampleRate / 25).coerceAtLeast(256)
                val pcmBuffer = ShortArray(framesPerChunk * 2)
                var phase = 0.0
                var sweepSec = 0.0
                val dt = 1.0 / sampleRate

                while (isActive && _state.value.isPlaying) {
                    val live = _state.value
                    val linearGain = 10.0.pow(live.attenuationDb / 20.0).coerceIn(0.01, 1.0)
                    val maxAmp = (Short.MAX_VALUE * linearGain).toInt()

                    for (i in 0 until framesPerChunk) {
                        val sampleVal: Double = when (live.waveform) {
                            ToneWaveform.WHITE_NOISE -> Random.nextDouble(-1.0, 1.0)
                            ToneWaveform.SWEEP -> {
                                // 5秒周期 20Hz -> 20kHz 对数扫频
                                val norm = (sweepSec % 5.0) / 5.0
                                val instFreq = 20.0 * (1000.0).pow(norm)
                                sweepSec += dt
                                phase += 2.0 * PI * instFreq * dt
                                if (phase > 2.0 * PI) phase -= 2.0 * PI
                                sin(phase)
                            }

                            else -> {
                                val f = live.waveform.baseFreqHz
                                phase += 2.0 * PI * f * dt
                                if (phase > 2.0 * PI) phase -= 2.0 * PI
                                sin(phase)
                            }
                        }

                        val pcmShort = (sampleVal * maxAmp).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort()

                        val left: Short
                        val right: Short
                        when (live.channelMode) {
                            ToneChannelMode.STEREO -> {
                                left = pcmShort
                                right = pcmShort
                            }

                            ToneChannelMode.LEFT_ONLY -> {
                                left = pcmShort
                                right = 0
                            }

                            ToneChannelMode.RIGHT_ONLY -> {
                                left = 0
                                right = pcmShort
                            }

                            ToneChannelMode.PHASE_INVERT -> {
                                left = pcmShort
                                right = (-pcmShort.toInt()).coerceIn(
                                    Short.MIN_VALUE.toInt(),
                                    Short.MAX_VALUE.toInt()
                                ).toShort()
                            }
                        }

                        pcmBuffer[i * 2] = left
                        pcmBuffer[i * 2 + 1] = right
                    }

                    val written = track.write(pcmBuffer, 0, pcmBuffer.size)
                    if (written < 0) break
                }
            } finally {
                runCatching {
                    track.stop()
                    track.release()
                }
                _state.update { it.copy(isPlaying = false) }
            }
        }
    }

    fun stop() {
        _state.update { it.copy(isPlaying = false) }
        playJob?.cancel()
        playJob = null
    }
}
