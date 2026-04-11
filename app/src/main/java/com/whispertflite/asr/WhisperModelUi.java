package com.whispertflite.asr;

import android.content.Context;

import com.whispertflite.MainActivity;
import com.whispertflite.R;

import java.io.File;

/** Shared spinner labels for TFLite and GGML Whisper model files. */
public final class WhisperModelUi {

    private WhisperModelUi() {}

    public static String spinnerLabel(Context ctx, File modelFile) {
        String name = modelFile.getName();
        if (MainActivity.MULTI_LINGUAL_MODEL_SLOW.equals(name)
                || MainActivity.MULTI_LINGUAL_TOP_WORLD_SLOW.equals(name)) {
            return ctx.getString(R.string.multi_lingual_slow);
        }
        if (MainActivity.ENGLISH_ONLY_MODEL.equals(name)) {
            return ctx.getString(R.string.english_only_fast);
        }
        if (MainActivity.MULTI_LINGUAL_MODEL_FAST.equals(name)
                || MainActivity.MULTI_LINGUAL_EU_MODEL_FAST.equals(name)
                || MainActivity.MULTI_LINGUAL_TOP_WORLD_FAST.equals(name)) {
            return ctx.getString(R.string.multi_lingual_fast);
        }
        if (WhisperGgmlModels.GGML_TINY_EN_Q5_1.equals(name)) {
            return ctx.getString(R.string.whisper_ggml_tiny_en_q5);
        }
        return stripExtension(name);
    }

    public static String stripExtension(String filename) {
        int d = filename.lastIndexOf('.');
        return d > 0 ? filename.substring(0, d) : filename;
    }
}
