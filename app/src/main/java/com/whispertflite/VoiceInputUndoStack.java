package com.whispertflite;

import android.text.Editable;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Shared undo stack for IME and main-screen voice input (max {@link #MAX_DEPTH}).
 * <p>
 * <b>When to push:</b> immediately before this app commits transcription-driven changes:
 * plain {@code commitText}, {@link ImeTextEditHelper#commitTranscriptPrefix}, or inserting a
 * voice-triggered newline. One push before a combined prefix+newline counts as one undo step.
 * <p>
 * <b>Undo phrase:</b> {@link ImeTextEditHelper#applyVoiceUndo(InputConnection)} /
 * {@link ImeTextEditHelper#applyVoiceUndoToEditText(EditText)} pop once; if the stack is empty,
 * fall back to deleting the last sentence (legacy behavior).
 */
public final class VoiceInputUndoStack {
    public static final int MAX_DEPTH = 20;

    private static final Deque<Snapshot> STACK = new ArrayDeque<>();
    private static final Object LOCK = new Object();

    public static final class Snapshot {
        public final String text;
        public final int selStart;
        public final int selEnd;

        public Snapshot(String text, int selStart, int selEnd) {
            this.text = text != null ? text : "";
            this.selStart = selStart;
            this.selEnd = selEnd;
        }
    }

    private VoiceInputUndoStack() {}

    /** Captures current field text and selection (best effort for {@link InputConnection}). */
    public static void pushFromInputConnection(InputConnection ic) {
        if (ic == null) return;
        Snapshot s = captureFromInputConnection(ic);
        if (s == null) return;
        synchronized (LOCK) {
            STACK.addFirst(s);
            while (STACK.size() > MAX_DEPTH) {
                STACK.removeLast();
            }
        }
    }

    public static void pushFromEditText(EditText et) {
        if (et == null) return;
        Editable ed = et.getText();
        if (ed == null) return;
        int a = et.getSelectionStart();
        int b = et.getSelectionEnd();
        if (a < 0) a = 0;
        if (b < 0) b = 0;
        Snapshot s = new Snapshot(ed.toString(), a, b);
        synchronized (LOCK) {
            STACK.addFirst(s);
            while (STACK.size() > MAX_DEPTH) {
                STACK.removeLast();
            }
        }
    }

    public static boolean popToInputConnection(InputConnection ic) {
        if (ic == null) return false;
        Snapshot s;
        synchronized (LOCK) {
            if (STACK.isEmpty()) return false;
            s = STACK.removeFirst();
        }
        return applySnapshotToInputConnection(ic, s);
    }

    public static boolean popToEditText(EditText et) {
        if (et == null) return false;
        Snapshot s;
        synchronized (LOCK) {
            if (STACK.isEmpty()) return false;
            s = STACK.removeFirst();
        }
        et.setText(s.text);
        int n = s.text.length();
        int a = clamp(s.selStart, 0, n);
        int b = clamp(s.selEnd, 0, n);
        et.setSelection(Math.min(a, b), Math.max(a, b));
        return true;
    }

    public static void clear() {
        synchronized (LOCK) {
            STACK.clear();
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(v, hi));
    }

    private static Snapshot captureFromInputConnection(InputConnection ic) {
        ExtractedTextRequest req = new ExtractedTextRequest();
        req.hintMaxChars = 100_000;
        req.hintMaxLines = 10_000;
        ExtractedText et = ic.getExtractedText(req, 0);
        if (et != null && et.text != null) {
            return new Snapshot(et.text.toString(), et.selectionStart, et.selectionEnd);
        }
        CharSequence bef = ic.getTextBeforeCursor(100_000, 0);
        CharSequence aft = ic.getTextAfterCursor(100_000, 0);
        if (bef == null || aft == null) return null;
        String full = bef.toString() + aft.toString();
        int sel = bef.length();
        return new Snapshot(full, sel, sel);
    }

    private static boolean applySnapshotToInputConnection(InputConnection ic, Snapshot snap) {
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
        } else {
            CharSequence bef = ic.getTextBeforeCursor(100_000, 0);
            CharSequence aft = ic.getTextAfterCursor(100_000, 0);
            if (bef == null || aft == null) return false;
            full = bef.toString() + aft.toString();
            selStart = selEnd = bef.length();
        }
        if (selStart < 0 || selStart > full.length()) return false;
        ic.beginBatchEdit();
        try {
            ic.deleteSurroundingText(selStart, full.length() - selStart);
            ic.commitText(snap.text, 1);
            int n = snap.text.length();
            int a = clamp(snap.selStart, 0, n);
            int b = clamp(snap.selEnd, 0, n);
            ic.setSelection(Math.min(a, b), Math.max(a, b));
        } finally {
            ic.endBatchEdit();
        }
        return true;
    }
}
