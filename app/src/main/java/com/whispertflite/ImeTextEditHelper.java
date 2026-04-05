package com.whispertflite;

import android.util.Log;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

/**
 * Text operations for {@link WhisperInputMethodService}: word-wise delete and voice "scratch that".
 */
public final class ImeTextEditHelper {
    private static final String TAG = "WhisperIME";

    private ImeTextEditHelper() {}

    /**
     * Deletes the last word before the cursor and any whitespace between that word and the cursor,
     * but not whitespace before the word.
     */
    public static boolean deleteLastWord(InputConnection ic) {
        if (ic == null) return false;
        CharSequence before = ic.getTextBeforeCursor(8192, 0);
        if (before == null || before.length() == 0) return false;
        String s = before.toString();
        int end = s.length();
        int i = end - 1;
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) {
            i--;
        }
        if (i < 0) {
            ic.deleteSurroundingText(end, 0);
            return true;
        }
        while (i >= 0 && !Character.isWhitespace(s.charAt(i))) {
            i--;
        }
        int wordStart = i + 1;
        int deleteLen = end - wordStart;
        if (deleteLen <= 0) return false;
        ic.deleteSurroundingText(deleteLen, 0);
        return true;
    }

    /**
     * True if the transcript is only an English undo phrase (optional trailing punctuation).
     */
    public static boolean matchesScratchThatCommand(String transcript) {
        if (transcript == null) return false;
        String normalized = normalizeScratchPhrase(transcript.trim().toLowerCase());
        return "scratch that".equals(normalized) || "scratched that".equals(normalized);
    }

    /** Lowercase trim plus strip trailing . ! ? */
    private static String normalizeScratchPhrase(String t) {
        if (t.isEmpty()) return t;
        String s = t;
        while (s.length() > 0) {
            char c = s.charAt(s.length() - 1);
            if (c == '.' || c == '!' || c == '?') {
                s = s.substring(0, s.length() - 1).trim();
            } else {
                break;
            }
        }
        return s;
    }

    /**
     * Removes the last sentence (by . ! ? or common CJK marks) before the cursor; leaves text after
     * the cursor unchanged. Requires a collapsed selection.
     *
     * @return true if the field was updated
     */
    public static boolean applyScratchThat(InputConnection ic) {
        if (ic == null) {
            Log.w(TAG, "scratch: apply failed — InputConnection null");
            return false;
        }
        ExtractedTextRequest req = new ExtractedTextRequest();
        req.hintMaxChars = 100_000;
        req.hintMaxLines = 10_000;
        ExtractedText et = ic.getExtractedText(req, 0);

        String full;
        int selStart;
        int selEnd;

        if (et != null && et.text != null) {
            full = et.text.toString();
            selStart = et.selectionStart;
            selEnd = et.selectionEnd;
            Log.d(TAG, "scratch: using getExtractedText fullLen=" + full.length() + " sel=" + selStart + "," + selEnd);
        } else {
            CharSequence bef = ic.getTextBeforeCursor(50_000, 0);
            CharSequence aft = ic.getTextAfterCursor(50_000, 0);
            if (bef == null || aft == null) {
                Log.w(TAG, "scratch: getExtractedText was null and before/after cursor text is null (host may not expose text)");
                return false;
            }
            full = bef.toString() + aft.toString();
            selStart = bef.length();
            selEnd = selStart;
            Log.d(TAG, "scratch: fallback before+after len=" + full.length() + " sel=" + selStart);
        }

        if (selStart != selEnd) {
            Log.d(TAG, "scratch: skip — non-collapsed selection " + selStart + "-" + selEnd);
            return false;
        }
        if (selStart < 0 || selStart > full.length()) {
            Log.w(TAG, "scratch: skip — bad selStart=" + selStart + " for len=" + full.length());
            return false;
        }
        String before = full.substring(0, selStart);
        String after = full.substring(selStart);
        String newBefore = withoutLastSentence(before);
        if (newBefore.equals(before)) {
            Log.d(TAG, "scratch: no change after withoutLastSentence (beforeLen=" + before.length()
                    + " may lack . ! ? 。！？ or empty)");
            return false;
        }
        ic.beginBatchEdit();
        try {
            ic.deleteSurroundingText(selStart, full.length() - selStart);
            ic.commitText(newBefore + after, 1);
        } finally {
            ic.endBatchEdit();
        }
        Log.d(TAG, "scratch: applied — beforeLen " + before.length() + " -> " + newBefore.length());
        return true;
    }

    private static boolean isSentenceEnd(char c) {
        return c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？';
    }

    /**
     * Drops the last sentence in {@code s} (text before cursor). If there is no sentence
     * terminator, the whole string is treated as one sentence and removed.
     */
    static String withoutLastSentence(String s) {
        if (s == null || s.isEmpty()) return s;
        int end = s.length();
        int i = end - 1;
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) {
            i--;
        }
        if (i < 0) {
            return "";
        }
        int punctPos = -1;
        for (int j = i; j >= 0; j--) {
            if (isSentenceEnd(s.charAt(j))) {
                punctPos = j;
                break;
            }
        }
        if (punctPos < 0) {
            return "";
        }
        int sentStart = 0;
        for (int j = punctPos - 1; j >= 0; j--) {
            if (isSentenceEnd(s.charAt(j))) {
                sentStart = j + 1;
                break;
            }
        }
        while (sentStart < punctPos && Character.isWhitespace(s.charAt(sentStart))) {
            sentStart++;
        }
        String kept = s.substring(0, sentStart);
        int k = kept.length() - 1;
        while (k >= 0 && Character.isWhitespace(kept.charAt(k))) {
            kept = kept.substring(0, k);
            k--;
        }
        return kept;
    }
}
