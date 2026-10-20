package com.reelbreaker.blocker

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * V3-2 — Acoustic Dopamine Severing.
 * ==================================
 * Autoplay reel audio is a major conditioned stimulus in the scroll loop
 * (continuous auditory reward + novelty jingles = variable-ratio audio
 * reinforcement). Visual friction alone leaves the audio channel untouched,
 * so the user can keep "consuming" behind an overlay. This manager severs
 * that channel with Android audio focus:
 *
 *  Tier 1 late-stage / FORAGING detected:
 *    AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> compliant hosts (IG/TikTok/YT)
 *    automatically duck their media volume ~60-80%. The reward value of the
 *    stimulus drops WITHOUT blocking anything (sub-threshold devaluation,
 *    mirrors the visual dimming of SensoryDampener).
 *
 *  Tier 2 speedbump & Tier 3 block:
 *    AUDIOFOCUS_GAIN_TRANSIENT -> hosts must pause playback entirely for the
 *    duration of the System-2 task. Silence during the cognitive interrupt
 *    removes the background hedonic stream that normally sustains the urge.
 *
 * Focus is abandoned cleanly and immediately when the user consciously
 * unlocks, so we never leave the host app stuck muted.
 */
class AudioInterventionManager(context: Context) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val duckListener = AudioManager.OnAudioFocusChangeListener { /* consumed by request */ }
    private val hardListener = AudioManager.OnAudioFocusChangeListener { /* consumed by request */ }

    private val duckRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setOnAudioFocusChangeListener(duckListener)
        .build()

    private val hardRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAcceptsDelayedFocusGain(false)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(hardListener)
        .build()

    enum class Level { NONE, DUCK, HARD }
    var current: Level = Level.NONE
        private set

    /** Tier 1 late-stage: duck the feed's audio (~60-80%). Idempotent. */
    fun duck() {
        if (current == Level.DUCK || current == Level.HARD) return
        runCatching { am.requestAudioFocus(duckRequest) }
        current = Level.DUCK
    }

    /** Tier 2 / Tier 3: force the host to stop its audio completely. */
    fun hardSever() {
        if (current == Level.HARD) return
        if (current == Level.DUCK) runCatching { am.abandonAudioFocusRequest(duckRequest) }
        runCatching { am.requestAudioFocus(hardRequest) }
        current = Level.HARD
    }

    /** Restore the feed's audio exactly once, on clean unlock / session end. */
    fun release() {
        when (current) {
            Level.DUCK -> runCatching { am.abandonAudioFocusRequest(duckRequest) }
            Level.HARD -> runCatching { am.abandonAudioFocusRequest(hardRequest) }
            Level.NONE -> {}
        }
        current = Level.NONE
    }
}
