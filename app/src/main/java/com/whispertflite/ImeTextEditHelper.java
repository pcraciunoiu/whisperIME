package com.whispertflite;

import android.text.Editable;
import android.util.Log;
import android.widget.EditText;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import java.util.Set;

/**
 * Text operations for {@link WhisperInputMethodService}: word-wise delete and configurable voice commands
 * (undo last sentence, new line).
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

    /** Lowercase trim plus strip trailing . ! ? */
    public static String normalizeVoiceCommandPhrase(String t) {
        if (t == null) return "";
        t = t.trim().toLowerCase();
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

    public static boolean matchesUndoCommand(String transcript, Set<String> normalizedPhrases) {
        if (transcript == null || normalizedPhrases == null || normalizedPhrases.isEmpty()) return false;
        String normalized = normalizeVoiceCommandPhrase(transcript);
        return !normalized.isEmpty() && normalizedPhrases.contains(normalized);
    }

    public static boolean matchesNewLineCommand(String transcript, Set<String> normalizedPhrases) {
        if (transcript == null || normalizedPhrases == null || normalizedPhrases.isEmpty()) return false;
        String normalized = normalizeVoiceCommandPhrase(transcript);
        return !normalized.isEmpty() && normalizedPhrases.contains(normalized);
    }

    /**
     * Inserts a newline at the cursor (replaces selection if any).
     */
    public static boolean applyNewLine(InputConnection ic) {
        if (ic == null) return false;
        ic.beginBatchEdit();
        try {
            ic.commitText("\n", 1);
        } finally {
            ic.endBatchEdit();
        }
        return true;
    }

    public static boolean applyNewLineToEditText(EditText et) {
        if (et == null) return false;
        int start = et.getSelectionStart();
        int end = et.getSelectionEnd();
        Editable ed = et.getText();
        if (ed == null) return false;
        if (start < 0 || end < 0) {
            start = end = ed.length();
        }
        int a = Math.min(start, end);
        int b = Math.max(start, end);
        ed.replace(a, b, "\n");
        et.setSelection(a + 1);
        return true;
    }

    /**
     * Removes the last sentence before the cursor in an {@link EditText}.
     */
    public static boolean applyUndoToEditText(EditText et) {
        if (et == null) return false;
        String full = et.getText().toString();
        int selStart = et.getSelectionStart();
        int selEnd = et.getSelectionEnd();
        String newFull = computeUndoReplacementFullText(full, selStart, selEnd);
        if (newFull == null) return false;
        int newSel = computeUndoNewCursor(full, selStart, selEnd, newFull);
        et.setText(newFull);
        et.setSelection(Math.min(newSel, newFull.length()));
        Log.d(TAG, "voice undo: applied to EditText");
        return true;
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

        String newFull = computeUndoReplacementFullText(full, selStart, selEnd);
        if (newFull == null) {
            Log.d(TAG, "scratch: no change (empty or no sentence to drop)");
            return false;
        }
        ic.beginBatchEdit();
        try {
            ic.deleteSurroundingText(selStart, full.length() - selStart);
            ic.commitText(newFull, 1);
        } finally {
            ic.endBatchEdit();
        }
        Log.d(TAG, "scratch: applied");
        return true;
    }

    /**
     * @return full document text after undo, or null if nothing to change
     */
    static String computeUndoReplacementFullText(String full, int selStart, int selEnd) {
        if (full == null) return null;
        if (selStart != selEnd) {
            Log.d(TAG, "scratch: skip — non-collapsed selection " + selStart + "-" + selEnd);
            return null;
        }
        if (selStart < 0 || selStart > full.length()) {
            Log.w(TAG, "scratch: skip — bad selStart=" + selStart + " for len=" + full.length());
            return null;
        }
        String before = full.substring(0, selStart);
        String after = full.substring(selStart);
        String newBefore = withoutLastSentence(before);
        if (newBefore.equals(before)) {
            Log.d(TAG, "scratch: no change after withoutLastSentence (beforeLen=" + before.length()
                    + " may lack . ! ? 。！？ or empty)");
            return null;
        }
        return newBefore + after;
    }

    private static int computeUndoNewCursor(String full, int selStart, int selEnd, String newFull) {
        if (selStart != selEnd) return newFull.length();
        String before = full.substring(0, selStart);
        String newBefore = withoutLastSentence(before);
        return newBefore.length();
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
