package com.example.peciwearables.integration.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue

class AudioPipeline {

    companion object {
        private const val TAG = "AudioPipeline"
        private const val DEFAULT_SAMPLE_RATE = 16_000
        private const val FADE_DURATION_S = 0.01 // 10ms, igual ao SDK JS

        fun applyFade(samples: ShortArray) {
            val fadeSamples = (FADE_DURATION_S * DEFAULT_SAMPLE_RATE).toInt()
            if (samples.size < fadeSamples * 2) return
            // fade-in: i=0 → factor=0, i=fadeSamples-1 → factor≈1
            for (i in 0 until fadeSamples) {
                samples[i] = (samples[i] * i / fadeSamples.toFloat()).toInt().toShort()
            }
            // fade-out: i=size-fadeSamples → factor≈1, i=size-1 → factor=0
            for (i in samples.size - fadeSamples until samples.size) {
                val pos = samples.size - 1 - i
                samples[i] = (samples[i] * pos / fadeSamples.toFloat()).toInt().toShort()
            }
        }
    }

    private data class Chunk(val samples: ShortArray, val sampleRateHz: Int)

    private val queue = LinkedBlockingQueue<Chunk>()
    private val lock = Any()

    private var isRunning = false
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var currentTrackSampleRate = DEFAULT_SAMPLE_RATE

    var chunksEnqueued = 0
        private set
    private var chunksPlayed = 0

    fun start(scope: CoroutineScope) {
        synchronized(lock) {
            if (isRunning) return
            isRunning = true
            ensureAudioTrackLocked(DEFAULT_SAMPLE_RATE)
        }
        playbackJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val chunk = queue.poll()
                if (chunk != null) {
                    synchronized(lock) { ensureAudioTrackLocked(chunk.sampleRateHz) }
                    writeSamples(chunk.samples)
                    chunksPlayed++
                } else {
                    writeSamples(buildSilenceChunk())
                }
            }
        }
        Log.d(TAG, "AudioPipeline started")
    }

    fun stop() {
        val job: Job?
        val track: AudioTrack?
        synchronized(lock) {
            isRunning = false
            queue.clear()
            chunksEnqueued = 0
            chunksPlayed = 0
            job = playbackJob
            playbackJob = null
            track = audioTrack
            audioTrack = null
        }
        job?.cancel()
        if (track != null) {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        Log.d(TAG, "AudioPipeline stopped")
    }

    fun enqueueChunk(samples: ShortArray, sampleRateHz: Int = DEFAULT_SAMPLE_RATE) {
        applyFade(samples)
        queue.offer(Chunk(samples, sampleRateHz))
        chunksEnqueued++
    }

    fun clearQueue() {
        queue.clear()
        val track = synchronized(lock) { audioTrack }
        runCatching {
            track?.pause()
            track?.flush()
            track?.play()
        }
    }

    fun getStats(): String =
        "Audio: enqueued=$chunksEnqueued, played=$chunksPlayed, queued=${queue.size}"

    private fun buildSilenceChunk(): ShortArray {
        val rate = synchronized(lock) { currentTrackSampleRate }
        return ShortArray((rate * 0.020).toInt())
    }

    private fun writeSamples(samples: ShortArray) {
        if (samples.isEmpty()) return
        val track = synchronized(lock) { audioTrack } ?: return
        runCatching {
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        }.onFailure {
            Log.w(TAG, "AudioTrack write failed: ${it.message}")
        }
    }

    private fun ensureAudioTrackLocked(sampleRateHz: Int) {
        val safeRate = sampleRateHz.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
        if (audioTrack != null && currentTrackSampleRate == safeRate) return
        releaseAudioTrackLocked()
        val minBuf = AudioTrack.getMinBufferSize(
            safeRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(1)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(safeRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf * 2)
            .build()
            .also { it.play() }
        currentTrackSampleRate = safeRate
    }

    private fun releaseAudioTrackLocked() {
        val track = audioTrack ?: return
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        runCatching { track.release() }
        audioTrack = null
    }
}
