/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package com.igalia.wolvic.sigil;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

/**
 * Generates binaural beats and/or isochronic tones while the sigil is showing.
 *
 * Binaural: the left ear hears (carrier - beat/2) Hz and the right ear (carrier + beat/2) Hz;
 * the brain perceives the difference as a beat. Needs stereo, so headphones or the Quest's own
 * speakers work, a single external speaker does not.
 * Isochronic: one tone at the carrier, switched on and off beat times per second with soft
 * edges so it pulses without clicking. Works on any speaker.
 */
public class SigilTone {
    private static final String LOGTAG = "SigilTone";
    private static final int SAMPLE_RATE = 48000;
    private static final int CHUNK_FRAMES = 480;          // 10 ms per write keeps start-up quick
    private static final float FADE_OUT_SECONDS = 0.25f;   // short, so dismissing is immediate but click-free

    public static final int SOUND_OFF = 0;
    public static final int SOUND_BINAURAL = 1;
    public static final int SOUND_ISOCHRONIC = 2;
    public static final int SOUND_BOTH = 3;

    // Each playback gets its own stop flag, so a tone that is still fading out can't be
    // revived by the next start().
    private java.util.concurrent.atomic.AtomicBoolean mStopFlag;

    /** Starts the tone with a fade-in. Does nothing if the sound mode is off. */
    public synchronized void start(int aMode, float aCarrierHz, float aBeatHz, float aVolume, float aFadeInSeconds) {
        stop();
        if (aMode == SOUND_OFF || aVolume <= 0f) {
            return;
        }
        final int mode = aMode;
        final float carrier = clamp(aCarrierHz, 20f, 2000f);
        final float beat = clamp(aBeatHz, 0.1f, 100f);
        final float volume = clamp(aVolume, 0f, 1f) * 0.5f;   // keep full scale well below clipping
        final float fadeIn = clamp(aFadeInSeconds, 0f, 60f);
        final java.util.concurrent.atomic.AtomicBoolean flag = new java.util.concurrent.atomic.AtomicBoolean(false);
        mStopFlag = flag;
        Thread thread = new Thread(() -> run(flag, mode, carrier, beat, volume, fadeIn), "SigilTone");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    /** Fades out quickly and stops. Safe to call when nothing is playing. */
    public synchronized void stop() {
        if (mStopFlag != null) {
            mStopFlag.set(true);
            mStopFlag = null;
        }
    }

    private void run(java.util.concurrent.atomic.AtomicBoolean aStop, int aMode, float aCarrier, float aBeat, float aVolume, float aFadeIn) {
        AudioTrack track;
        try {
            int minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBytes, CHUNK_FRAMES * 2 * 4 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
        } catch (Exception e) {
            Log.e(LOGTAG, "Could not open audio output: " + e);
            return;
        }

        final boolean binaural = aMode == SOUND_BINAURAL || aMode == SOUND_BOTH;
        final boolean isochronic = aMode == SOUND_ISOCHRONIC || aMode == SOUND_BOTH;
        final double twoPi = Math.PI * 2.0;
        final double stepL = twoPi * (aCarrier - aBeat / 2.0) / SAMPLE_RATE;
        final double stepR = twoPi * (aCarrier + aBeat / 2.0) / SAMPLE_RATE;
        final double stepIso = twoPi * aCarrier / SAMPLE_RATE;
        final double stepPulse = (double) aBeat / SAMPLE_RATE;   // pulse cycles per sample
        final float mix = (binaural && isochronic) ? 0.5f : 1f;
        final long fadeInFrames = (long) (aFadeIn * SAMPLE_RATE);
        final long fadeOutFrames = (long) (FADE_OUT_SECONDS * SAMPLE_RATE);

        double phaseL = 0, phaseR = 0, phaseIso = 0, pulse = 0;
        long frame = 0;
        long fadeOutStart = -1;
        float gainAtStop = 1f;
        float[] buffer = new float[CHUNK_FRAMES * 2];

        track.play();
        try {
            while (true) {
                if (aStop.get() && fadeOutStart < 0) {
                    fadeOutStart = frame;
                    gainAtStop = fadeInFrames > 0 ? Math.min(1f, (float) frame / fadeInFrames) : 1f;
                }
                for (int i = 0; i < CHUNK_FRAMES; i++, frame++) {
                    float gain;
                    if (fadeOutStart >= 0) {
                        gain = gainAtStop * Math.max(0f, 1f - (float) (frame - fadeOutStart) / fadeOutFrames);
                    } else {
                        gain = fadeInFrames > 0 ? Math.min(1f, (float) frame / fadeInFrames) : 1f;
                    }
                    // Equal-power-ish curve so the fade sounds even rather than sudden at the end.
                    gain = gain * gain * aVolume;

                    float left = 0f, right = 0f;
                    if (binaural) {
                        left += (float) Math.sin(phaseL) * mix;
                        right += (float) Math.sin(phaseR) * mix;
                    }
                    if (isochronic) {
                        // Pulse envelope: on for half the cycle with raised-cosine edges (no clicks).
                        double p = pulse - Math.floor(pulse);
                        double env;
                        if (p < 0.1) env = 0.5 - 0.5 * Math.cos(Math.PI * p / 0.1);
                        else if (p < 0.4) env = 1.0;
                        else if (p < 0.5) env = 0.5 + 0.5 * Math.cos(Math.PI * (p - 0.4) / 0.1);
                        else env = 0.0;
                        float s = (float) (Math.sin(phaseIso) * env) * mix;
                        left += s;
                        right += s;
                    }
                    buffer[2 * i] = left * gain;
                    buffer[2 * i + 1] = right * gain;

                    phaseL += stepL; if (phaseL > twoPi) phaseL -= twoPi;
                    phaseR += stepR; if (phaseR > twoPi) phaseR -= twoPi;
                    phaseIso += stepIso; if (phaseIso > twoPi) phaseIso -= twoPi;
                    pulse += stepPulse;
                }
                track.write(buffer, 0, buffer.length, AudioTrack.WRITE_BLOCKING);
                if (fadeOutStart >= 0 && frame - fadeOutStart >= fadeOutFrames) {
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(LOGTAG, "Tone playback stopped: " + e);
        } finally {
            try { track.stop(); } catch (Exception ignored) {}
            track.release();
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Float.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }
}
