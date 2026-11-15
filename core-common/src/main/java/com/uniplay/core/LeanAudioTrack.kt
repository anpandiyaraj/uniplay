package com.uniplay.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Audio routing preferences for projection playback.
 * On consumer phones, forcing [AudioManager.STREAM_MUSIC] prevents navigation/assistant
 * channels from routing to the phone's earpiece instead of the speaker or Bluetooth audio.
 */
object AudioSettings {
    private const val PREFS = "audio"
    private const val KEY_FORCE_MEDIA_STREAM = "force_media_stream"

    fun isForceMediaStream(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_FORCE_MEDIA_STREAM, false)

    fun setForceMediaStream(context: Context, force: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FORCE_MEDIA_STREAM, force)
            .apply()
    }
}

/**
 * Lean AudioTrack wrapper for low-RAM automotive playback.
 * Uses small buffers to minimize latency (< 40ms) and keep memory < 2MB.
 */
class LeanAudioTrack(
    val channel: AudioChannel,
    val sampleRate: Int = 48000,
    val channelCount: Int = 2,
    val forceMediaStream: Boolean = false
) {
    companion object {
        private const val TAG = "LeanAudioTrack"

        /** About a second of 10 ms packets; beyond that the oldest audio is dropped. */
        private const val QUEUE_CAPACITY = 100
    }

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false

    // AudioTrack.write blocks once its buffer is full. Called straight from the
    // transport thread that stalled video, touch and acks behind every audio packet,
    // so writes are queued here and played by a thread of their own.
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private var writerThread: Thread? = null

    @Synchronized
    fun start() {
        if (isPlaying) return

        val channelConfig = if (channelCount == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }

        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

        val audioAttributes = buildAudioAttributes()

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .setEncoding(encoding)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
            }
            .build()
        audioTrack = track
        track.play()
        isPlaying = true

        queue.clear()
        writerThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            writeLoop(track)
        }, "UniplayAudio-$channel").apply { start() }
        Log.i(TAG, "AudioTrack started for $channel ($sampleRate Hz, forceMediaStream=$forceMediaStream, buffer: ${bufferSize}b)")
    }

    @Suppress("DEPRECATION")
    private fun buildAudioAttributes(): AudioAttributes {
        if (forceMediaStream) {
            return AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                .build()
        }

        val usage = when (channel) {
            AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            AudioChannel.SPEECH_SIRI_ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
            AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        }
        val contentType = if (channel == AudioChannel.MEDIA) {
            AudioAttributes.CONTENT_TYPE_MUSIC
        } else {
            AudioAttributes.CONTENT_TYPE_SPEECH
        }
        return AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .build()
    }

    /** Queues [pcmData] for playback and returns at once. The array must not be reused. */
    fun write(pcmData: ByteArray) {
        if (!isPlaying) start()
        if (!queue.offer(pcmData)) {
            // Playback fell behind; keep the audio current rather than late.
            queue.poll()
            queue.offer(pcmData)
        }
    }

    private fun writeLoop(track: AudioTrack) {
        try {
            while (true) {
                val pcm = queue.poll(200, TimeUnit.MILLISECONDS)
                if (pcm == null) {
                    if (audioTrack !== track) return
                    continue
                }
                if (track.write(pcm, 0, pcm.size) < 0) return
            }
        } catch (_: Exception) {
            // Track was stopped or released.
        }
    }

    @Synchronized
    fun stop() {
        val track = audioTrack
        audioTrack = null
        writerThread = null
        queue.clear()
        try {
            track?.stop()
            track?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioTrack", e)
        } finally {
            isPlaying = false
        }
    }
}
