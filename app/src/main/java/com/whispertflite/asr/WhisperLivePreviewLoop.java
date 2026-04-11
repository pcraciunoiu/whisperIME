package com.whispertflite.asr;

import android.os.Handler;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodically snapshots PCM from {@link Recorder} and runs {@link Whisper#transcribeLivePreview(byte[])}
 * on a background thread (throttled). Used for Whisper hold-to-talk live partials alongside Moonshine/Parakeet.
 */
public final class WhisperLivePreviewLoop {

    public interface PartialCallback {
        void onPartial(String text);
    }

    private final Handler mainHandler;
    private final Recorder recorder;
    private final Whisper whisper;
    private final PartialCallback callback;
    private final int minPcmBytes;
    private final long tickIntervalMs;
    private final long initialDelayMs;

    private final Runnable tick;
    private volatile boolean stopped;
    private final AtomicBoolean decodeBusy = new AtomicBoolean(false);

    public WhisperLivePreviewLoop(Handler mainHandler, Recorder recorder, Whisper whisper, PartialCallback callback) {
        this(mainHandler, recorder, whisper, 64_000, 2800L, 2000L, callback);
    }

    public WhisperLivePreviewLoop(Handler mainHandler, Recorder recorder, Whisper whisper,
            int minPcmBytes, long tickIntervalMs, long initialDelayMs, PartialCallback callback) {
        this.mainHandler = mainHandler;
        this.recorder = recorder;
        this.whisper = whisper;
        this.minPcmBytes = minPcmBytes;
        this.tickIntervalMs = tickIntervalMs;
        this.initialDelayMs = initialDelayMs;
        this.callback = callback;
        this.tick = this::runTick;
    }

    private void runTick() {
        if (stopped) {
            return;
        }
        if (!recorder.isInProgress()) {
            return;
        }
        if (decodeBusy.get()) {
            mainHandler.postDelayed(tick, 400);
            return;
        }
        byte[] snap = recorder.getLivePcmSnapshot();
        if (snap == null || snap.length < minPcmBytes) {
            mainHandler.postDelayed(tick, 500);
            return;
        }
        final byte[] copy = snap.clone();
        if (!decodeBusy.compareAndSet(false, true)) {
            mainHandler.postDelayed(tick, 400);
            return;
        }
        new Thread(() -> {
            try {
                WhisperResult wr = whisper.transcribeLivePreview(copy);
                if (wr != null) {
                    String t = wr.getResult();
                    if (t != null && !t.trim().isEmpty()) {
                        mainHandler.post(() -> {
                            if (!stopped) {
                                callback.onPartial(t);
                            }
                        });
                    }
                }
            } finally {
                decodeBusy.set(false);
                if (!stopped && recorder.isInProgress()) {
                    mainHandler.postDelayed(tick, tickIntervalMs);
                }
            }
        }, "WhisperLivePreview").start();
    }

    public void start() {
        stopped = false;
        decodeBusy.set(false);
        mainHandler.postDelayed(tick, initialDelayMs);
    }

    public void stop() {
        stopped = true;
        mainHandler.removeCallbacks(tick);
        decodeBusy.set(false);
    }
}
