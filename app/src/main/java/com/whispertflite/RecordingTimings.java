package com.whispertflite;

/**
 * Hold-to-talk maximum duration and UI progress mapping for the recording countdown bar.
 */
public final class RecordingTimings {
    private RecordingTimings() {}

    public static final long HOLD_TO_TALK_MAX_MS = 60_000L;

    /** Remaining-ms tick divisor so {@code l / this} stays in 0..100 over {@link #HOLD_TO_TALK_MAX_MS}. */
    public static final long COUNTDOWN_PROGRESS_DIVISOR_MS = HOLD_TO_TALK_MAX_MS / 100;
}
