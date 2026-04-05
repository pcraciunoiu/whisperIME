package com.whispertflite;

import android.text.Editable;
import android.util.Log;
import android.widget.EditText;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import java.util.Locale;
import java.util.Set;

/**
 * Text operations for {@link WhisperInputMethodService}: word-wise delete and configurable voice commands
 * (undo last sentence, new line).
 */
public final class ImeTextEditHelper {
    private static final String TAG = "WhisperIME";

    public enum VoiceCommandKind {
        NONE,
        UNDO,
        NEWLINE,
    }

    /** Result of {@link #findTrailingVoiceCommand(String, Set, Set)}. */
    public static final class VoiceCommandTail {
        public final VoiceCommandKind kind;
        /** Text before the trailing command phrase (original casing), trimmed. */
        public final String prefix;

        public VoiceCommandTail(VoiceCommandKind kind, String prefix) {
            this.kind = kind;
            this.prefix = prefix != null ? prefix : "";
        }

        public boolean hasCommand() {
            return kind != VoiceCommandKind.NONE;
        }
    }

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
     * Detects a configured undo or newline phrase at the end of the transcript (after the same
     * normalization as {@link #normalizeVoiceCommandPhrase}). Handles multi-sentence live text
     * like {@code First part. New line}.
     *
     * <p>If both an undo and newline phrase could match, the longer phrase wins; on a length tie,
     * undo wins.
     */
    public static VoiceCommandTail findTrailingVoiceCommand(
            String transcript,
            Set<String> normalizedUndoPhrases,
            Set<String> normalizedNewlinePhrases) {
        if (transcript == null) {
            return new VoiceCommandTail(VoiceCommandKind.NONE, "");
        }
        String t = transcript.trim();
        if (t.isEmpty()) {
            return new VoiceCommandTail(VoiceCommandKind.NONE, "");
        }
        String lower = t.toLowerCase(Locale.ROOT);
        String s = normalizeVoiceCommandPhrase(lower);
        if (s.isEmpty()) {
            return new VoiceCommandTail(VoiceCommandKind.NONE, "");
        }

        int bestPhraseLen = -1;
        VoiceCommandKind bestKind = VoiceCommandKind.NONE;
        int bestPhraseStart = 0;

        if (normalizedUndoPhrases != null) {
            for (String phrase : normalizedUndoPhrases) {
                Integer start = phraseEndIndexInNormalized(s, phrase);
                if (start != null && voiceCommandMatchBeats(phrase.length(), VoiceCommandKind.UNDO, bestPhraseLen, bestKind)) {
                    bestPhraseLen = phrase.length();
                    bestKind = VoiceCommandKind.UNDO;
                    bestPhraseStart = start;
                }
            }
        }
        if (normalizedNewlinePhrases != null) {
            for (String phrase : normalizedNewlinePhrases) {
                Integer start = phraseEndIndexInNormalized(s, phrase);
                if (start != null && voiceCommandMatchBeats(phrase.length(), VoiceCommandKind.NEWLINE, bestPhraseLen, bestKind)) {
                    bestPhraseLen = phrase.length();
                    bestKind = VoiceCommandKind.NEWLINE;
                    bestPhraseStart = start;
                }
            }
        }

        if (bestKind == VoiceCommandKind.NONE) {
            return new VoiceCommandTail(VoiceCommandKind.NONE, "");
        }

        if (bestPhraseStart > t.length()) {
            return new VoiceCommandTail(VoiceCommandKind.NONE, "");
        }
        String prefix = t.substring(0, bestPhraseStart).trim();
        return new VoiceCommandTail(bestKind, prefix);
    }

    /** Longer phrase wins; on equal length, {@link VoiceCommandKind#UNDO} beats {@link VoiceCommandKind#NEWLINE}. */
    private static boolean voiceCommandMatchBeats(
            int phraseLen, VoiceCommandKind phraseKind, int bestLen, VoiceCommandKind bestKind) {
        if (phraseLen > bestLen) return true;
        if (phraseLen < bestLen) return false;
        return phraseKind == VoiceCommandKind.UNDO && bestKind == VoiceCommandKind.NEWLINE;
    }

    /**
     * @return start index of {@code phrase} at the end of {@code normalizedTranscript}, or null.
     */
    private static Integer phraseEndIndexInNormalized(String normalizedTranscript, String phrase) {
        if (phrase == null || phrase.isEmpty()) return null;
        if (normalizedTranscript.length() < phrase.length()) return null;
        if (!normalizedTranscript.endsWith(phrase)) return null;
        int start = normalizedTranscript.length() - phrase.length();
        if (start > 0) {
            char before = normalizedTranscript.charAt(start - 1);
            if (!Character.isWhitespace(before) && !isSentenceEnd(before)) {
                return null;
            }
        }
        return start;
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

    /** Commits spoken text that precedes a trailing voice command (adds a trailing space when needed). */
    public static void commitTranscriptPrefix(InputConnection ic, String prefixTrimmed) {
        if (ic == null) return;
        String p = prefixTrimmed.trim();
        if (p.isEmpty()) return;
        ic.commitText(p.endsWith(" ") ? p : p + " ", 1);
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
     * Pops {@link VoiceInputUndoStack} if possible; otherwise removes the last sentence before the cursor.
     */
    public static boolean applyUndoToEditText(EditText et) {
        if (et == null) return false;
        if (VoiceInputUndoStack.popToEditText(et)) {
            Log.d(TAG, "voice undo: stack pop applied to EditText");
            return true;
        }
        return applySentenceUndoFallbackToEditText(et);
    }

    static boolean applySentenceUndoFallbackToEditText(EditText et) {
        if (et == null) return false;
        String full = et.getText().toString();
        int selStart = et.getSelectionStart();
        int selEnd = et.getSelectionEnd();
        String newFull = computeUndoReplacementFullText(full, selStart, selEnd);
        if (newFull == null) return false;
        int newSel = computeUndoNewCursor(full, selStart, selEnd, newFull);
        et.setText(newFull);
        et.setSelection(Math.min(newSel, newFull.length()));
        Log.d(TAG, "voice undo: sentence fallback applied to EditText");
        return true;
    }

    /**
     * Pops {@link VoiceInputUndoStack} if possible; otherwise removes the last sentence before the cursor.
     */
    public static boolean applyScratchThat(InputConnection ic) {
        if (ic == null) {
            Log.w(TAG, "scratch: apply failed — InputConnection null");
            return false;
        }
        if (VoiceInputUndoStack.popToInputConnection(ic)) {
            Log.d(TAG, "voice undo: stack pop applied");
            return true;
        }
        return applySentenceUndoFallback(ic);
    }

    /**
     * Sentence-based removal when the undo stack is empty (legacy behavior).
     */
    public static boolean applySentenceUndoFallback(InputConnection ic) {
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
        Log.d(TAG, "scratch: sentence fallback applied");
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
