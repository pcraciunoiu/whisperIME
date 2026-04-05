package com.whispertflite;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static com.whispertflite.MainActivity.ENGLISH_ONLY_VOCAB_FILE;
import static com.whispertflite.MainActivity.MULTILINGUAL_VOCAB_FILE;
import static com.whispertflite.MainActivity.MULTI_LINGUAL_TOP_WORLD_SLOW;
import static com.whispertflite.MainActivity.ENGLISH_ONLY_MODEL_EXTENSION;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;

import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.whispertflite.asr.Recorder;
import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperResult;
import com.whispertflite.moonshine.MoonshineHoldRecorder;
import com.whispertflite.moonshine.MoonshineModelFiles;
import com.whispertflite.moonshine.MoonshinePreferences;
import com.whispertflite.parakeet.ParakeetModelFiles;
import com.whispertflite.parakeet.ParakeetStreamingRecorder;
import com.whispertflite.utils.HapticFeedback;
import com.whispertflite.utils.InputLang;

import java.io.File;
import java.util.Set;

public class WhisperInputMethodService extends InputMethodService {
    private static final String TAG = "WhisperInputMethodService";
    private ImageButton btnRecord;
    private ImageButton btnKeyboard;
    private ImageButton btnTranslate;
    private ImageButton btnModeAuto;
    private ImageButton btnEnter;
    private ImageButton btnDel;
    private TextView tvStatus;
    private Recorder mRecorder = null;
    private Whisper mWhisper = null;
    private File sdcardDataFolder = null;
    private File selectedTfliteFile = null;
    private ProgressBar processingBar = null;
    private SharedPreferences sp = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Context mContext;
    private CountDownTimer countDownTimer;
    private static boolean translate = false;
    private boolean modeAuto = false;
    private LinearLayout layoutButtons;
    private MoonshineHoldRecorder imeMoonshineRecorder = null;
    private ParakeetStreamingRecorder imeParakeetRecorder = null;
    /** Live partial already applied undo/newline; skip duplicate apply on finger-up. */
    private boolean imeVoiceCommandConsumed = false;

    private boolean useMoonshineImeNow() {
        return AsrEnginePreferences.MOONSHINE.equals(AsrEnginePreferences.mainEngine(this))
                && MoonshineModelFiles.allModelFilesPresent(this);
    }

    private boolean useParakeetImeNow() {
        return AsrEnginePreferences.PARAKEET.equals(AsrEnginePreferences.mainEngine(this))
                && sdcardDataFolder != null
                && ParakeetModelFiles.allOnnxPresent(sdcardDataFolder);
    }

    @Override
    public void onCreate() {
        mContext = this;
        MoonshinePreferences.migrateFromParakeetKeys(this);
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        if (imeMoonshineRecorder != null) {
            imeMoonshineRecorder.stop();
            imeMoonshineRecorder = null;
        }
        if (imeParakeetRecorder != null) {
            imeParakeetRecorder.stop();
            imeParakeetRecorder = null;
        }
        deinitModel();
        if (mRecorder != null && mRecorder.isInProgress()) {
            mRecorder.stop();
        }
        super.onDestroy();
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        if (attribute.inputType == EditorInfo.TYPE_NULL) {
            Log.d(TAG, "onStartInput TYPE_NULL package=" + attribute.packageName + " — stop recording only (keep model loaded)");
            if (mRecorder != null && mRecorder.isInProgress()) {
                mRecorder.stop();
            }
            if (imeMoonshineRecorder != null) {
                imeMoonshineRecorder.stop();
                imeMoonshineRecorder = null;
            }
            if (imeParakeetRecorder != null) {
                imeParakeetRecorder.stop();
                imeParakeetRecorder = null;
            }
        }
    }

    @Override
    public void onStartInputView(EditorInfo attribute, boolean restarting){
        MoonshinePreferences.migrateFromParakeetKeys(this);
        selectedTfliteFile = new File(sdcardDataFolder, sp.getString("modelName", MULTI_LINGUAL_TOP_WORLD_SLOW));
        String eng = AsrEnginePreferences.mainEngine(this);

        if (AsrEnginePreferences.MOONSHINE.equals(eng)) {
            deinitModel();
            if (!MoonshineModelFiles.allModelFilesPresent(this)) {
                switchToPreviousInputMethod();
                Intent intent = new Intent(this, DownloadActivity.class);
                intent.putExtra(DownloadActivity.EXTRA_PREFERRED_ENGINE, AsrEnginePreferences.MOONSHINE);
                intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
            return;
        }
        if (AsrEnginePreferences.PARAKEET.equals(eng)) {
            deinitModel();
            if (sdcardDataFolder == null || !ParakeetModelFiles.allOnnxPresent(sdcardDataFolder)) {
                switchToPreviousInputMethod();
                Intent intent = new Intent(this, DownloadActivity.class);
                intent.putExtra(DownloadActivity.EXTRA_PREFERRED_ENGINE, AsrEnginePreferences.PARAKEET);
                intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
            return;
        }

        if (!selectedTfliteFile.exists()) {
            switchToPreviousInputMethod();
            Intent intent = new Intent(this, DownloadActivity.class);
            intent.putExtra(DownloadActivity.EXTRA_PREFERRED_ENGINE, AsrEnginePreferences.WHISPER);
            intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else {
            if (mWhisper == null) {
                initModel(selectedTfliteFile);
            } else {
                if (!mWhisper.getCurrentModelPath().equals(selectedTfliteFile.getAbsolutePath())) {
                    deinitModel();
                    initModel(selectedTfliteFile);
                }
            }
        }
    }


    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View onCreateInputView() {  //runs before onStartInputView
        sp = PreferenceManager.getDefaultSharedPreferences(this);
        View view = getLayoutInflater().inflate(R.layout.voice_service, null);
        btnRecord = view.findViewById(R.id.btnRecord);
        btnKeyboard = view.findViewById(R.id.btnKeyboard);
        btnTranslate = view.findViewById(R.id.btnTranslate);
        btnModeAuto = view.findViewById(R.id.btnModeAuto);
        btnEnter = view.findViewById(R.id.btnEnter);
        btnDel = view.findViewById(R.id.btnDel);
        processingBar = view.findViewById(R.id.processing_bar);
        tvStatus = view.findViewById(R.id.tv_status);
        sdcardDataFolder = this.getExternalFilesDir(null);
        MoonshinePreferences.migrateFromParakeetKeys(this);
        selectedTfliteFile = new File(sdcardDataFolder, sp.getString("modelName", MULTI_LINGUAL_TOP_WORLD_SLOW));
        if (AsrEnginePreferences.WHISPER.equals(AsrEnginePreferences.mainEngine(this))
                && selectedTfliteFile.isFile() && mWhisper == null) {
            initModel(selectedTfliteFile);
        }
        btnTranslate.setImageResource(translate ? R.drawable.ic_english_on_36dp : R.drawable.ic_english_off_36dp);
        modeAuto = sp.getBoolean("imeModeAuto",false);
        btnModeAuto.setImageResource(modeAuto ? R.drawable.ic_auto_on_36dp : R.drawable.ic_auto_off_36dp);
        layoutButtons = view.findViewById(R.id.layout_buttons);
        checkRecordPermission();

        // Audio recording functionality
        mRecorder = new Recorder(this);
        mRecorder.setListener(new Recorder.RecorderListener() {
            @Override
            public void onUpdateReceived(String message) {
                if (message.equals(Recorder.MSG_RECORDING)) {
                    handler.post(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                    HapticFeedback.vibrate(mContext);
                    handler.post(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background));
                    startTranscription();
                } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
                    HapticFeedback.vibrate(mContext);
                    if (countDownTimer!=null) { countDownTimer.cancel();}
                    handler.post(() -> {
                        btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
                        tvStatus.setText(getString(R.string.error_no_input));
                        tvStatus.setVisibility(View.VISIBLE);
                        processingBar.setProgress(0);
                    });
                }
            }

        });

        if (modeAuto && AsrEnginePreferences.WHISPER.equals(AsrEnginePreferences.mainEngine(this))) {
            layoutButtons.setVisibility(View.GONE);
            HapticFeedback.vibrate(this);
            startRecording();
            handler.post(() -> processingBar.setProgress(100));
            countDownTimer = new CountDownTimer(RecordingTimings.HOLD_TO_TALK_MAX_MS, 1000) {
                @Override
                public void onTick(long l) {
                    handler.post(() -> processingBar.setProgress((int) (l / RecordingTimings.COUNTDOWN_PROGRESS_DIVISOR_MS)));
                }
                @Override
                public void onFinish() {}
            };
            countDownTimer.start();
            handler.post(() -> {
                tvStatus.setText("");
                tvStatus.setVisibility(View.GONE);
            });
        }

        btnDel.setOnTouchListener(new View.OnTouchListener() {
            private Runnable initialDeleteRunnable;
            private Runnable repeatDeleteRunnable;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    InputConnection icDel = getCurrentInputConnection();
                    ImeTextEditHelper.deleteLastWord(icDel);
                    // Post the initial delay of 500ms
                    initialDeleteRunnable = new Runnable() {
                        @Override
                        public void run() {
                            InputConnection ic0 = getCurrentInputConnection();
                            ImeTextEditHelper.deleteLastWord(ic0);
                            // Start repeating every 100ms
                            repeatDeleteRunnable = new Runnable() {
                                @Override
                                public void run() {
                                    InputConnection ic1 = getCurrentInputConnection();
                                    ImeTextEditHelper.deleteLastWord(ic1);
                                    handler.postDelayed(this, 100);
                                }
                            };
                            handler.postDelayed(repeatDeleteRunnable, 100);
                        }
                    };
                    handler.postDelayed(initialDeleteRunnable, 500);
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    // Remove both callbacks
                    if (initialDeleteRunnable != null) {
                        handler.removeCallbacks(initialDeleteRunnable);
                    }
                    if (repeatDeleteRunnable != null) {
                        handler.removeCallbacks(repeatDeleteRunnable);
                    }
                    initialDeleteRunnable = null;
                    repeatDeleteRunnable = null;
                }
                return true;
            }
        });

        btnRecord.setOnTouchListener((v, event) -> {
            boolean liveImePartials = sp.getBoolean("liveTranscribePartials", false);
            if (useMoonshineImeNow()) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    imeVoiceCommandConsumed = false;
                    handler.post(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                    if (checkRecordPermission()) {
                        HapticFeedback.vibrate(this);
                        imeMoonshineRecorder = new MoonshineHoldRecorder(this, handler,
                                partial -> handler.post(() -> applyLiveImePartial(partial, liveImePartials)),
                                liveImePartials);
                        if (!imeMoonshineRecorder.start()) {
                            imeMoonshineRecorder = null;
                        }
                        handler.post(() -> processingBar.setProgress(100));
                        countDownTimer = new CountDownTimer(RecordingTimings.HOLD_TO_TALK_MAX_MS, 1000) {
                            @Override
                            public void onTick(long l) {
                                handler.post(() -> processingBar.setProgress((int) (l / RecordingTimings.COUNTDOWN_PROGRESS_DIVISOR_MS)));
                            }
                            @Override
                            public void onFinish() {}
                        };
                        countDownTimer.start();
                        handler.post(() -> {
                            tvStatus.setText("");
                            tvStatus.setVisibility(View.GONE);
                        });
                    }
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    handler.post(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background));
                    if (countDownTimer != null) countDownTimer.cancel();
                    handler.post(() -> processingBar.setProgress(0));
                    if (imeMoonshineRecorder != null) {
                        String fin = imeMoonshineRecorder.stop();
                        imeMoonshineRecorder = null;
                        handler.post(() -> commitHoldTranscription(getCurrentInputConnection(), fin, liveImePartials));
                    }
                }
                return true;
            }
            if (useParakeetImeNow()) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    imeVoiceCommandConsumed = false;
                    handler.post(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                    if (checkRecordPermission()) {
                        HapticFeedback.vibrate(this);
                        imeParakeetRecorder = new ParakeetStreamingRecorder(this, sdcardDataFolder, handler,
                                partial -> handler.post(() -> applyLiveImePartial(partial, liveImePartials)));
                        if (!imeParakeetRecorder.start()) {
                            imeParakeetRecorder = null;
                        }
                        handler.post(() -> processingBar.setProgress(100));
                        countDownTimer = new CountDownTimer(RecordingTimings.HOLD_TO_TALK_MAX_MS, 1000) {
                            @Override
                            public void onTick(long l) {
                                handler.post(() -> processingBar.setProgress((int) (l / RecordingTimings.COUNTDOWN_PROGRESS_DIVISOR_MS)));
                            }
                            @Override
                            public void onFinish() {}
                        };
                        countDownTimer.start();
                        handler.post(() -> {
                            tvStatus.setText("");
                            tvStatus.setVisibility(View.GONE);
                        });
                    }
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    handler.post(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background));
                    if (countDownTimer != null) countDownTimer.cancel();
                    handler.post(() -> processingBar.setProgress(0));
                    if (imeParakeetRecorder != null) {
                        String fin = imeParakeetRecorder.stop();
                        imeParakeetRecorder = null;
                        handler.post(() -> commitHoldTranscription(getCurrentInputConnection(), fin, liveImePartials));
                    }
                }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                // Pressed
                imeVoiceCommandConsumed = false;
                handler.post(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                if (checkRecordPermission()){
                    if (mWhisper == null && selectedTfliteFile != null && selectedTfliteFile.isFile()) {
                        initModel(selectedTfliteFile);
                    }
                    if (mWhisper == null) {
                        handler.post(() -> {
                            tvStatus.setText(getString(R.string.whisper_model_missing));
                            tvStatus.setVisibility(View.VISIBLE);
                        });
                        return true;
                    }
                    if (!mWhisper.isInProgress()) {
                        HapticFeedback.vibrate(this);
                        startRecording();
                        handler.post(() -> processingBar.setProgress(100));
                        countDownTimer = new CountDownTimer(RecordingTimings.HOLD_TO_TALK_MAX_MS, 1000) {
                            @Override
                            public void onTick(long l) {
                                handler.post(() -> processingBar.setProgress((int) (l / RecordingTimings.COUNTDOWN_PROGRESS_DIVISOR_MS)));
                            }
                            @Override
                            public void onFinish() {}
                        };
                        countDownTimer.start();
                        handler.post(() -> {
                            tvStatus.setText("");
                            tvStatus.setVisibility(View.GONE);
                        });
                    } else {
                        handler.post(() -> {
                            tvStatus.setText(getString(R.string.please_wait));
                            tvStatus.setVisibility(View.VISIBLE);
                        });
                    }
                }
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                // Released
                handler.post(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background));
                if (mRecorder != null && mRecorder.isInProgress()) {
                    mRecorder.stop();
                }
            }
            return true;
        });

        btnKeyboard.setOnClickListener(v -> {
            if (imeMoonshineRecorder != null) {
                imeMoonshineRecorder.stop();
                imeMoonshineRecorder = null;
            }
            if (imeParakeetRecorder != null) {
                imeParakeetRecorder.stop();
                imeParakeetRecorder = null;
            }
            if (mWhisper != null) stopTranscription();
            switchToPreviousInputMethod();
        });

        btnTranslate.setOnClickListener(v -> {
            translate = !translate;
            btnTranslate.setImageResource(translate ? R.drawable.ic_english_on_36dp : R.drawable.ic_english_off_36dp);
        });

        btnEnter.setOnClickListener(v -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            }
        });

        btnModeAuto.setOnClickListener(v -> {
            modeAuto = !modeAuto;
            SharedPreferences.Editor editor = sp.edit();
            editor.putBoolean("imeModeAuto", modeAuto);
            editor.apply();
            layoutButtons.setVisibility(modeAuto ? View.GONE : View.VISIBLE);
            btnModeAuto.setImageResource(modeAuto ? R.drawable.ic_auto_on_36dp : R.drawable.ic_auto_off_36dp);
            switchToPreviousInputMethod();
        });
        return view;
    }

    private void startRecording() {
        if (modeAuto) mRecorder.initVad();
        mRecorder.start();
    }

    // Model initialization
    private void initModel(File modelFile) {
        boolean isMultilingualModel = !(modelFile.getName().endsWith(ENGLISH_ONLY_MODEL_EXTENSION));
        String vocabFileName = isMultilingualModel ? MULTILINGUAL_VOCAB_FILE : ENGLISH_ONLY_VOCAB_FILE;
        File vocabFile = new File(sdcardDataFolder, vocabFileName);

        mWhisper = new Whisper(this);
        mWhisper.loadModel(modelFile, vocabFile, isMultilingualModel);
        Log.d(TAG, "Initialized: " + modelFile.getName());
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
            }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                // Whisper invokes this on a worker thread; InputConnection must be used on the main thread.
                String raw = whisperResult.getResult();
                if (whisperResult.getLanguage().equals("zh")) {
                    boolean simpleChinese = sp.getBoolean("simpleChinese", false);
                    raw = simpleChinese ? ZhConverterUtil.toSimple(raw) : ZhConverterUtil.toTraditional(raw);
                }
                final String result = raw;
                handler.post(() -> {
                    processingBar.setIndeterminate(false);
                    tvStatus.setText("");
                    tvStatus.setVisibility(View.GONE);
                    InputConnection ic = getCurrentInputConnection();
                    boolean commitSuccess = false;
                    if (ic != null && result.trim().length() > 0) {
                        Set<String> undo = VoiceCommandPreferences.normalizedUndoPhrases(sp);
                        Set<String> nl = VoiceCommandPreferences.normalizedNewlinePhrases(sp);
                        if (ImeTextEditHelper.matchesUndoCommand(result, undo)) {
                            Log.d(TAG, "voice undo: Whisper command=\"" + summarizeForLog(result) + "\"");
                            commitSuccess = ImeTextEditHelper.applyScratchThat(ic);
                        } else if (ImeTextEditHelper.matchesNewLineCommand(result, nl)) {
                            Log.d(TAG, "voice newline: Whisper command=\"" + summarizeForLog(result) + "\"");
                            commitSuccess = ImeTextEditHelper.applyNewLine(ic);
                        } else {
                            commitSuccess = ic.commitText(result.trim() + " ", 1);
                        }
                    }
                    if (modeAuto && commitSuccess) {
                        handler.postDelayed(() -> switchToPreviousInputMethod(), 100);
                    }
                });
            }
        });
    }

    private void startTranscription() {
        if (countDownTimer!=null) { countDownTimer.cancel();}
        handler.post(() -> processingBar.setProgress(0));
        handler.post(() -> processingBar.setIndeterminate(true));
        if (mWhisper!=null){
            if (translate) mWhisper.setAction(Whisper.ACTION_TRANSLATE);
            else mWhisper.setAction(Whisper.ACTION_TRANSCRIBE);

            String langCode = sp.getString("language", "auto");
            int langToken = InputLang.getIdForLanguage(InputLang.getLangList(),langCode);
            Log.d("WhisperIME","default langToken " + langToken);
            mWhisper.setLanguage(langToken);
            mWhisper.start();
        }
    }

    private void stopTranscription() {
        handler.post(() -> processingBar.setIndeterminate(false));
        if (mWhisper != null) mWhisper.stop();
    }

    private boolean checkRecordPermission() {
        int permission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            tvStatus.setVisibility(View.VISIBLE);
            tvStatus.setText(getString(R.string.need_record_audio_permission));
        }
        return (permission == PackageManager.PERMISSION_GRANTED);
    }

    /**
     * After hold-to-talk, insert final text. With live partials we only use {@link InputConnection#commitText}
     * so the composing span is replaced once (finishComposingText + commitText duplicated the phrase).
     */
    private void applyLiveImePartial(String partial, boolean liveImePartials) {
        if (!liveImePartials) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            Log.d(TAG, "scratch/live partial: InputConnection null (field may have lost focus)");
            return;
        }
        Set<String> undo = VoiceCommandPreferences.normalizedUndoPhrases(sp);
        Set<String> nl = VoiceCommandPreferences.normalizedNewlinePhrases(sp);
        if (ImeTextEditHelper.matchesUndoCommand(partial, undo)) {
            Log.d(TAG, "voice undo: live composing command=\"" + summarizeForLog(partial) + "\"");
            ic.setComposingText("", 1);
            ImeTextEditHelper.applyScratchThat(ic);
            imeVoiceCommandConsumed = true;
            return;
        }
        if (ImeTextEditHelper.matchesNewLineCommand(partial, nl)) {
            Log.d(TAG, "voice newline: live composing command=\"" + summarizeForLog(partial) + "\"");
            ic.setComposingText("", 1);
            ImeTextEditHelper.applyNewLine(ic);
            imeVoiceCommandConsumed = true;
            return;
        }
        ic.setComposingText(partial, 1);
    }

    private static String summarizeForLog(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > 80 ? t.substring(0, 80) + "…" : t;
    }

    private void commitHoldTranscription(InputConnection ic, String fin, boolean hadLivePartials) {
        if (ic == null) return;
        String t = fin.trim();
        Set<String> undo = VoiceCommandPreferences.normalizedUndoPhrases(sp);
        Set<String> nl = VoiceCommandPreferences.normalizedNewlinePhrases(sp);
        if (ImeTextEditHelper.matchesUndoCommand(fin, undo)) {
            Log.d(TAG, "voice undo: hold release command=\"" + summarizeForLog(fin) + "\" hadLivePartials="
                    + hadLivePartials + " alreadyConsumedPartial=" + imeVoiceCommandConsumed);
            if (hadLivePartials) {
                ic.setComposingText("", 1);
            }
            if (!imeVoiceCommandConsumed) {
                ImeTextEditHelper.applyScratchThat(ic);
            }
            imeVoiceCommandConsumed = false;
            return;
        }
        if (ImeTextEditHelper.matchesNewLineCommand(fin, nl)) {
            Log.d(TAG, "voice newline: hold release command=\"" + summarizeForLog(fin) + "\" hadLivePartials="
                    + hadLivePartials + " alreadyConsumedPartial=" + imeVoiceCommandConsumed);
            if (hadLivePartials) {
                ic.setComposingText("", 1);
            }
            if (!imeVoiceCommandConsumed) {
                ImeTextEditHelper.applyNewLine(ic);
            }
            imeVoiceCommandConsumed = false;
            return;
        }
        if (t.length() > 0) {
            ic.commitText(t + " ", 1);
        } else if (hadLivePartials) {
            ic.finishComposingText();
        }
    }

    private void deinitModel() {
        if (mWhisper != null) {
            mWhisper.unloadModel();
            mWhisper = null;
        }
    }
}