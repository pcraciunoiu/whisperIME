package com.whispertflite;

import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Comma-separated voice command phrases (IME + main screen) stored in default SharedPreferences.
 */
public final class VoiceCommandPreferences {
    public static final String KEY_UNDO_PHRASES = "voice_undo_phrases";
    public static final String KEY_NEWLINE_PHRASES = "voice_newline_phrases";

    private VoiceCommandPreferences() {}

    public static Set<String> normalizedUndoPhrases(SharedPreferences sp) {
        String raw = sp.getString(KEY_UNDO_PHRASES, "").trim();
        if (raw.isEmpty()) {
            return defaultUndoPhrases();
        }
        Set<String> parsed = parseCommaSeparated(raw);
        return parsed.isEmpty() ? defaultUndoPhrases() : parsed;
    }

    public static Set<String> normalizedNewlinePhrases(SharedPreferences sp) {
        String raw = sp.getString(KEY_NEWLINE_PHRASES, "").trim();
        if (raw.isEmpty()) {
            return defaultNewlinePhrases();
        }
        Set<String> parsed = parseCommaSeparated(raw);
        return parsed.isEmpty() ? defaultNewlinePhrases() : parsed;
    }

    private static Set<String> defaultUndoPhrases() {
        Set<String> s = new LinkedHashSet<>();
        s.add(ImeTextEditHelper.normalizeVoiceCommandPhrase("scratch that"));
        s.add(ImeTextEditHelper.normalizeVoiceCommandPhrase("scratched that"));
        return s;
    }

    private static Set<String> defaultNewlinePhrases() {
        Set<String> s = new LinkedHashSet<>();
        s.add(ImeTextEditHelper.normalizeVoiceCommandPhrase("new line"));
        return s;
    }

    static Set<String> parseCommaSeparated(String csv) {
        Set<String> out = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String n = ImeTextEditHelper.normalizeVoiceCommandPhrase(part);
            if (!n.isEmpty()) {
                out.add(n);
            }
        }
        return out;
    }
}
