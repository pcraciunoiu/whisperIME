package com.whispertflite;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.util.Pair;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Adapter;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.text.Editable;
import android.widget.EditText;
import androidx.activity.OnBackPressedCallback;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.whispertflite.asr.LiveTranscribePreferences;
import com.whispertflite.asr.Recorder;
import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperLivePreviewLoop;
import com.whispertflite.asr.WhisperModelSelection;
import com.whispertflite.asr.WhisperResult;
import com.whispertflite.moonshine.MoonshineHoldRecorder;
import com.whispertflite.moonshine.MoonshineModelFiles;
import com.whispertflite.moonshine.MoonshinePocActivity;
import com.whispertflite.moonshine.MoonshinePreferences;
import com.whispertflite.sherpa.SherpaCatalogEntry;
import com.whispertflite.sherpa.SherpaPreferences;
import com.whispertflite.sherpa.SherpaStreamingRecorder;
import com.whispertflite.parakeet.ParakeetEnginePool;
import com.whispertflite.parakeet.ParakeetModelFiles;
import com.whispertflite.parakeet.ParakeetStreamingRecorder;
import com.whispertflite.utils.HapticFeedback;
import com.whispertflite.utils.InputLang;
import com.whispertflite.utils.LanguagePairAdapter;
import com.whispertflite.utils.ThemeUtils;

import org.woheller69.freeDroidWarn.FreeDroidWarn;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private Context mContext;

    // whisper-small.tflite works well for multi-lingual
    public static final String MULTI_LINGUAL_EU_MODEL_FAST = "whisper-base.EUROPEAN_UNION.tflite";
    public static final String MULTI_LINGUAL_TOP_WORLD_FAST = "whisper-base.TOP_WORLD.tflite";
    public static final String MULTI_LINGUAL_TOP_WORLD_SLOW = "whisper-small.TOP_WORLD.tflite";
    public static final String MULTI_LINGUAL_MODEL_FAST = "whisper-base.tflite";
    public static final String MULTI_LINGUAL_MODEL_SLOW = "whisper-small.tflite";
    public static final String ENGLISH_ONLY_MODEL = "whisper-tiny.en.tflite";
    // English only model ends with extension ".en.tflite"
    public static final String ENGLISH_ONLY_MODEL_EXTENSION = ".en.tflite";
    public static final String ENGLISH_ONLY_VOCAB_FILE = "filters_vocab_en.bin";
    public static final String MULTILINGUAL_VOCAB_FILE = "filters_vocab_multilingual.bin";


    private TextView tvStatus;
    private EditText tvResult;
    private FloatingActionButton fabUndo;
    private FloatingActionButton fabCopy;
    private ImageButton btnRecord;
    private LinearLayout layoutModeChinese;
    private LinearLayout layoutTTS;
    private CheckBox append;
    private CheckBox liveTranscribe;
    private CheckBox translate;
    private CheckBox modeSimpleChinese;
    private CheckBox modeTTS;
    private ProgressBar processingBar;
    private ImageButton btnInfo;

    private Recorder mRecorder = null;
    private Whisper mWhisper = null;
    private MoonshineHoldRecorder moonshineMainRecorder = null;
    private ParakeetStreamingRecorder parakeetMainRecorder = null;
    private SherpaStreamingRecorder sherpaMainRecorder = null;
    private WhisperLivePreviewLoop mainWhisperLiveLoop = null;
    /** Whisper hold used live partials for this utterance (cleared when final result is applied). */
    private boolean whisperSessionLiveTranscribe = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private File sdcardDataFolder = null;
    private File selectedTfliteFile = null;
    private SharedPreferences sp = null;
    private Spinner spnrAsrEngine;
    private String[] mAsrEngineValues;
    private LinearLayout layoutWhisperModels;
    private LinearLayout layoutSherpaModels;
    private LinearLayout layoutSherpaOptions;
    private Spinner spinnerTflite;
    private Spinner spnrSherpaModel;
    private CheckBox cbSherpaPunctEnhance;
    private CountDownTimer countDownTimer;
    private Spinner spinnerLanguage;
    /** Retained so {@link #refreshWhisperTfliteSpinner()} can update language rules after download. */
    private LanguagePairAdapter languagePairAdapter;
    private int langToken = -1;
    private long startTime = 0;
    private TextToSpeech tts;
    private EditText etVoiceUndoPhrases;
    private EditText etVoiceNewlinePhrases;
    /** Live partial already applied undo/newline; skip duplicate on finger-up. */
    private boolean mainVoiceCommandConsumed = false;
    /** Text in {@link #tvResult} before a live Moonshine/Parakeet hold when append mode is on. */
    private String mainLiveAppendPrefix = null;

    @Override
    protected void onDestroy() {
        if (moonshineMainRecorder != null) {
            moonshineMainRecorder.stop();
            moonshineMainRecorder = null;
        }
        if (parakeetMainRecorder != null) {
            parakeetMainRecorder.stop();
            parakeetMainRecorder = null;
        }
        if (sherpaMainRecorder != null) {
            sherpaMainRecorder.stop();
            sherpaMainRecorder = null;
        }
        deinitModel();
        deinitTTS();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshWhisperTfliteSpinner();
        syncAsrEngineSpinnerToPrefs();
    }

    private void syncAsrEngineSpinnerToPrefs() {
        if (spnrAsrEngine == null || mAsrEngineValues == null) {
            return;
        }
        String cur = AsrEnginePreferences.mainEngine(this);
        for (int i = 0; i < mAsrEngineValues.length; i++) {
            if (mAsrEngineValues[i].equals(cur)) {
                spnrAsrEngine.setSelection(i, false);
                break;
            }
        }
    }

    @Override
    protected void onPause() {
        saveVoiceCommandPhrasePrefs();
        stopProcessing();
        super.onPause();
    }

    private void saveVoiceCommandPhrasePrefs() {
        if (etVoiceUndoPhrases == null) return;
        sp.edit()
                .putString(VoiceCommandPreferences.KEY_UNDO_PHRASES, etVoiceUndoPhrases.getText().toString().trim())
                .putString(VoiceCommandPreferences.KEY_NEWLINE_PHRASES, etVoiceNewlinePhrases.getText().toString().trim())
                .apply();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = this;
        setContentView(R.layout.activity_main);
        ThemeUtils.setStatusBarAppearance(this);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        checkInputMethodEnabled();
        processingBar = findViewById(R.id.processing_bar);
        sp = PreferenceManager.getDefaultSharedPreferences(this);
        MoonshinePreferences.migrateFromParakeetKeys(this);
        append = findViewById(R.id.mode_append);
        liveTranscribe = findViewById(R.id.mode_live_transcribe);
        liveTranscribe.setChecked(LiveTranscribePreferences.isEnabled(sp));
        liveTranscribe.setOnCheckedChangeListener((buttonView, isChecked) ->
                sp.edit().putBoolean(LiveTranscribePreferences.PREFS_KEY, isChecked).apply());

        etVoiceUndoPhrases = findViewById(R.id.et_voice_undo_phrases);
        etVoiceNewlinePhrases = findViewById(R.id.et_voice_newline_phrases);
        {
            String savedUndo = sp.getString(VoiceCommandPreferences.KEY_UNDO_PHRASES, null);
            etVoiceUndoPhrases.setText(savedUndo != null ? savedUndo : getString(R.string.voice_undo_phrases_default));
            String savedNl = sp.getString(VoiceCommandPreferences.KEY_NEWLINE_PHRASES, null);
            etVoiceNewlinePhrases.setText(savedNl != null ? savedNl : getString(R.string.voice_newline_phrases_default));
        }

        layoutTTS = findViewById(R.id.layout_tts);
        modeTTS = findViewById(R.id.mode_tts);
        modeTTS.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            if (isChecked) {
                tts = new TextToSpeech(mContext, status -> {
                    if (status == TextToSpeech.SUCCESS) {
                        int result = tts.setLanguage(Locale.US);
                        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            runOnUiThread(() -> {
                                Toast.makeText(mContext, mContext.getString(R.string.tts_language_not_supported),Toast.LENGTH_SHORT).show();
                                modeTTS.setChecked(false);
                            });

                        }
                    } else {
                        runOnUiThread(() -> Toast.makeText(mContext, mContext.getString(R.string.tts_initialization_failed),Toast.LENGTH_SHORT).show());
                    }
                });
            } else {
                deinitTTS();
            }
        });

        translate = findViewById(R.id.mode_translate);
        translate.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            layoutTTS.setVisibility(isChecked ? View.VISIBLE:View.GONE);
            if (layoutTTS.getVisibility() == View.GONE) modeTTS.setChecked(false);
        });

        // Call the method to copy specific file types from assets to data folder
        sdcardDataFolder = this.getExternalFilesDir(null);

        layoutWhisperModels = findViewById(R.id.layout_whisper_models);
        layoutSherpaModels = findViewById(R.id.layout_sherpa_models);
        layoutSherpaOptions = findViewById(R.id.layout_sherpa_options);
        spnrSherpaModel = findViewById(R.id.spnrSherpaModel);
        bindSherpaModelSpinner();
        cbSherpaPunctEnhance = findViewById(R.id.cbSherpaPunctEnhance);
        cbSherpaPunctEnhance.setChecked(SherpaPreferences.isPunctuationEnhanceEnabled(this));
        cbSherpaPunctEnhance.setOnCheckedChangeListener((buttonView, isChecked) ->
                SherpaPreferences.setPunctuationEnhanceEnabled(MainActivity.this, isChecked));
        spnrAsrEngine = findViewById(R.id.spnrAsrEngine);
        ArrayAdapter<CharSequence> engineAdapter = ArrayAdapter.createFromResource(this,
                R.array.asr_engine_entries, android.R.layout.simple_spinner_item);
        engineAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnrAsrEngine.setAdapter(engineAdapter);
        mAsrEngineValues = getResources().getStringArray(R.array.asr_engine_entry_values);
        String currentEngine = AsrEnginePreferences.mainEngine(this);
        int engineSel = 0;
        for (int i = 0; i < mAsrEngineValues.length; i++) {
            if (mAsrEngineValues[i].equals(currentEngine)) {
                engineSel = i;
                break;
            }
        }
        spnrAsrEngine.setSelection(engineSel, false);

        ArrayList<File> tfliteFiles = getFilesWithExtension(sdcardDataFolder, ".tflite");
        maybeMigrateModelNameFromSentinel();

        if (AsrEnginePreferences.WHISPER.equals(currentEngine)) {
            initModel();
        } else {
            deinitModel();
        }
        applyEngineUiMode(mAsrEngineValues[engineSel]);
        maybePreheatParakeet();

        spnrAsrEngine.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String eng = mAsrEngineValues[position];
                AsrEnginePreferences.setMainEngine(MainActivity.this, eng);
                if (AsrEnginePreferences.WHISPER.equals(eng)) {
                    initModel();
                } else {
                    deinitModel();
                }
                applyEngineUiMode(eng);
                maybePreheatParakeet();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        btnInfo = findViewById(R.id.btnInfo);
        btnInfo.setOnClickListener(view -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/woheller69/whisperIME#Donate"))));
        btnInfo.setOnLongClickListener(v -> {
            startActivity(new Intent(this, MoonshinePocActivity.class));
            return true;
        });

        spinnerLanguage = findViewById(R.id.spnrLanguage);
        List<Pair<String, String>> languagePairs = LanguagePairAdapter.getLanguagePairs(this);
        languagePairAdapter = new LanguagePairAdapter(this, android.R.layout.simple_spinner_item, languagePairs);
        languagePairAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLanguage.setAdapter(languagePairAdapter);

        spinnerLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                langToken = InputLang.getIdForLanguage(InputLang.getLangList(),languagePairs.get(i).first);
                SharedPreferences.Editor editor = sp.edit();
                editor.putString("language",languagePairs.get(i).first);
                editor.apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });

        spinnerTflite = findViewById(R.id.spnrTfliteFiles);
        bindWhisperTfliteSpinnerListeners();
        applyWhisperTfliteSpinnerData();


        // Implementation of record button functionality
        btnRecord = findViewById(R.id.btnRecord);

        btnRecord.setOnTouchListener((v, event) -> {
            String eng = AsrEnginePreferences.mainEngine(MainActivity.this);
            if (AsrEnginePreferences.MOONSHINE.equals(eng)) {
                boolean moonshineLive = liveTranscribe.isChecked();
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    mainVoiceCommandConsumed = false;
                    runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                    if (!ensureEngineModelsReady()) return true;
                    HapticFeedback.vibrate(this);
                    final String liveAppendPrefix = (moonshineLive && append.isChecked())
                            ? tvResult.getText().toString()
                            : null;
                    mainLiveAppendPrefix = liveAppendPrefix;
                    if (!append.isChecked()) runOnUiThread(() -> tvResult.setText(""));
                    startTime = System.currentTimeMillis();
                    moonshineMainRecorder = new MoonshineHoldRecorder(mContext, mainHandler,
                            partial -> runOnUiThread(() -> {
                                if (!moonshineLive) return;
                                if (handleLivePartialVoiceCommand(partial, liveAppendPrefix)) return;
                                if (liveAppendPrefix != null) {
                                    tvResult.setText(joinLivePrefixWithPartial(liveAppendPrefix, partial));
                                } else {
                                    tvResult.setText(partial != null ? partial : "");
                                }
                                moveCursorToEnd(tvResult);
                            }), moonshineLive);
                    if (!moonshineMainRecorder.start()) {
                        Toast.makeText(this, R.string.moonshine_start_failed, Toast.LENGTH_SHORT).show();
                        moonshineMainRecorder = null;
                    }
                    runOnUiThread(() -> processingBar.setProgress(100));
                    countDownTimer = new CountDownTimer(RecordingTimings.HOLD_TO_TALK_MAX_MS, 1000) {
                        @Override
                        public void onTick(long l) {
                            runOnUiThread(() -> processingBar.setProgress((int) (l / RecordingTimings.COUNTDOWN_PROGRESS_DIVISOR_MS)));
                        }
                        @Override
                        public void onFinish() {}
                    };
                    countDownTimer.start();
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background));
                    if (countDownTimer != null) countDownTimer.cancel();
                    runOnUiThread(() -> processingBar.setProgress(0));
                    if (moonshineMainRecorder != null) {
                        String fin = moonshineMainRecorder.stop();
                        moonshineMainRecorder = null;
                        long elapsed = System.currentTimeMillis() - startTime;
                        boolean liveNow = liveTranscribe.isChecked();
                        runOnUiThread(() -> {
                            finishStreamingEngineHold(fin, liveNow, elapsed, getString(R.string.moonshine_asr_model));
                        });
                    }
                }
                return true;
            }
            if (AsrEnginePreferences.PARAKEET.equals(eng)) {
                boolean live = liveTranscribe.isChecked();
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    mainVoiceCommandConsumed = false;
                    runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                    if (!ensureEngineModelsReady()) return true;
                    HapticFeedback.vibrate(this);
                    final String liveAppendPrefix = (live && append.isChecked())
                            ? tvResult.getText().toString()
                            : null;
                    mainLiveAppendPrefix = liveAppendPrefix;
                    if (!append.isChecked()) runOnUiThread(() -> tvResult.setText(""));
                    startTime = System.currentTimeMillis();
                    parakeetMainRecorder = new ParakeetStreamingRecorder(mContext, sdcardDataFolder, mainHandler,
                            partial -> {
                                if (live) {
                                    runOnUiThread(() -> {
                                        if (handleLivePartialVoiceCommand(partial, liveAppendPrefix)) return;
                                        if (liveAppendPrefix != null) {
                                            tvResult.setText(joinLivePrefixWithPartial(liveAppendPrefix, partial));
                                        } else {
                                            tvResult.setText(partial != null ? partial : "");
                                        }
                                        moveCursorToEnd(tvResult);
                                    });
                                }
                            });
                    if (!parakeetMainRecorder.start()) {
                        Toast.makeText(this, R.string.parakeet_start_failed, Toast.LENGTH_SHORT).show();
                        parakeetMainRecorder = null;
                    }
                    runOnUiThread(() -> processingBar.setProgress(100));
                    countDownTimer = new CountDownTimer(RecordingTimings.HOLD_TO_TALK_MAX_MS, 1000) {
                        @Override
                        public void onTick(long l) {
                            runOnUiThread(() -> processingBar.setProgress((int) (l / RecordingTimings.COUNTDOWN_PROGRESS_DIVISOR_MS)));
                        }
                        @Override
                        public void onFinish() {}
                    };
                    countDownTimer.start();
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background));
                    if (countDownTimer != null) countDownTimer.cancel();
                    runOnUiThread(() -> processingBar.setProgress(0));
                    if (parakeetMainRecorder != null) {
                        String fin = parakeetMainRecorder.stop();
                        parakeetMainRecorder = null;
                        long elapsed = System.currentTimeMillis() - startTime;
                        boolean liveNow = liveTranscribe.isChecked();
                        runOnUiThread(() -> {
                            finishStreamingEngineHold(fin, liveNow, elapsed, "Parakeet (English)");
                        });
                    }
                }
                return true;
            }
            if (AsrEnginePreferences.SHERPA.equals(eng)) {
                boolean live = liveTranscribe.isChecked();
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    mainVoiceCommandConsumed = false;
                    runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                    if (!ensureEngineModelsReady()) return true;
                    HapticFeedback.vibrate(this);
                    final String liveAppendPrefix = (live && append.isChecked())
                            ? tvResult.getText().toString()
                            : null;
                    mainLiveAppendPrefix = liveAppendPrefix;
                    if (!append.isChecked()) runOnUiThread(() -> tvResult.setText(""));
                    startTime = System.currentTimeMillis();
                    sherpaMainRecorder = new SherpaStreamingRecorder(mContext, sdcardDataFolder, mainHandler,
                            partial -> {
                                if (live) {
                                    runOnUiThread(() -> {
                                        if (partial == null || partial.isEmpty()) {
                                            return;
                                        }
                                        if (handleLivePartialVoiceCommand(partial, liveAppendPrefix)) return;
                                        if (liveAppendPrefix != null) {
                                            tvResult.setText(joinLivePrefixWithPartial(liveAppendPrefix, partial));
                                        } else {
                                            tvResult.setText(partial);
                                        }
                                        moveCursorToEnd(tvResult);
                                    });
                                }
                            });
                    if (!sherpaMainRecorder.start()) {
                        Toast.makeText(this, R.string.sherpa_start_failed, Toast.LENGTH_SHORT).show();
                        sherpaMainRecorder = null;
                    }
                    runOnUiThread(() -> processingBar.setProgress(100));
                    countDownTimer = new CountDownTimer(RecordingTimings.HOLD_TO_TALK_MAX_MS, 1000) {
                        @Override
                        public void onTick(long l) {
                            runOnUiThread(() -> processingBar.setProgress((int) (l / RecordingTimings.COUNTDOWN_PROGRESS_DIVISOR_MS)));
                        }
                        @Override
                        public void onFinish() {}
                    };
                    countDownTimer.start();
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background));
                    if (countDownTimer != null) countDownTimer.cancel();
                    runOnUiThread(() -> processingBar.setProgress(0));
                    if (sherpaMainRecorder != null) {
                        String fin = sherpaMainRecorder.stop();
                        sherpaMainRecorder = null;
                        long elapsed = System.currentTimeMillis() - startTime;
                        boolean liveNow = liveTranscribe.isChecked();
                        runOnUiThread(() -> {
                            finishStreamingEngineHold(fin, liveNow, elapsed, getString(R.string.engine_sherpa));
                        });
                    }
                }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                // Pressed
                runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                if (!ensureEngineModelsReady()) return true;
                if (mWhisper == null) {
                    initModel();
                }
                if (mWhisper == null) {
                    Toast.makeText(this, R.string.whisper_model_missing, Toast.LENGTH_SHORT).show();
                    return true;
                }
                if (!mWhisper.isInProgress()) {
                    HapticFeedback.vibrate(this);
                    if (AsrEnginePreferences.WHISPER.equals(AsrEnginePreferences.mainEngine(this))) {
                        boolean wl = liveTranscribe.isChecked();
                        String liveAppendPrefix = (wl && append.isChecked())
                                ? tvResult.getText().toString()
                                : null;
                        mainLiveAppendPrefix = liveAppendPrefix;
                        mWhisper.setAction(translate.isChecked() ? Whisper.ACTION_TRANSLATE : Whisper.ACTION_TRANSCRIBE);
                        mWhisper.setLanguage(langToken);
                    }
                    startRecording();
                    runOnUiThread(() -> processingBar.setProgress(100));
                    countDownTimer = new CountDownTimer(RecordingTimings.HOLD_TO_TALK_MAX_MS, 1000) {
                        @Override
                        public void onTick(long l) {
                            runOnUiThread(() -> processingBar.setProgress((int) (l / RecordingTimings.COUNTDOWN_PROGRESS_DIVISOR_MS)));
                        }
                        @Override
                        public void onFinish() {}
                    };
                    countDownTimer.start();
                } else (Toast.makeText(this,getString(R.string.please_wait),Toast.LENGTH_SHORT)).show();

            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                // Released
                runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background));
                if (mRecorder != null && mRecorder.isInProgress()) {
                    stopRecording();
                }
            }
            return true;
        });

        layoutModeChinese = findViewById(R.id.layout_mode_chinese);
        modeSimpleChinese = findViewById(R.id.mode_simple_chinese);
        modeSimpleChinese.setChecked(sp.getBoolean("simpleChinese",false));  //default to traditional Chinese
        modeSimpleChinese.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            SharedPreferences.Editor editor = sp.edit();
            editor.putBoolean("simpleChinese", isChecked);
            editor.apply();
            tvResult.setText("");
        });

        tvStatus = findViewById(R.id.tvStatus);
        tvResult = findViewById(R.id.tvResult);
        tvResult.setOnClickListener(view -> tvResult.setCursorVisible(true));
        getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (tvResult.isCursorVisible()) tvResult.setCursorVisible(false);
                else finish();
            }
        });
        fabUndo = findViewById(R.id.fabUndo);
        fabUndo.setOnClickListener(v -> ImeTextEditHelper.applyUndoToEditText(tvResult));
        fabCopy = findViewById(R.id.fabCopy);
        fabCopy.setOnClickListener(v -> {
            // Get the text from tvResult
            String textToCopy = tvResult.getText().toString().trim();

            // Copy the text to the clipboard
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(getString(R.string.model_output), textToCopy);
            clipboard.setPrimaryClip(clip);
        });

        // Audio recording functionality
        mRecorder = new Recorder(this);
        mRecorder.setListener(new Recorder.RecorderListener() {
            @Override
            public void onUpdateReceived(String message) {
                if (message.equals(Recorder.MSG_RECORDING)) {
                    runOnUiThread(() -> tvStatus.setText(getString(R.string.record_button) +"…"));
                    if (!append.isChecked()) runOnUiThread(() -> tvResult.setText(""));
                    runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                    boolean whisperEngine = AsrEnginePreferences.WHISPER.equals(AsrEnginePreferences.mainEngine(MainActivity.this));
                    whisperSessionLiveTranscribe = whisperEngine && liveTranscribe.isChecked();
                    if (whisperSessionLiveTranscribe) {
                        startMainWhisperLivePreviewIfNeeded();
                    }
                } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                    stopMainWhisperLivePreview();
                    HapticFeedback.vibrate(mContext);
                    runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background));

                    if (translate.isChecked()) startProcessing(Whisper.ACTION_TRANSLATE);
                    else startProcessing(Whisper.ACTION_TRANSCRIBE);
                } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
                    stopMainWhisperLivePreview();
                    whisperSessionLiveTranscribe = false;
                    HapticFeedback.vibrate(mContext);
                    if (countDownTimer!=null) { countDownTimer.cancel();}
                    runOnUiThread(() -> {
                        btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
                        processingBar.setProgress(0);
                        tvStatus.setText(getString(R.string.error_no_input));
                    });
                }
            }

        });
        FreeDroidWarn.showWarningOnUpgrade(this, BuildConfig.VERSION_CODE);
        if (GithubStar.shouldShowStarDialog(this)) GithubStar.starDialog(this, "https://github.com/woheller69/whisperIME");
        // Assume this Activity is the current activity, check record permission
        checkPermissions();

    }

    private void checkInputMethodEnabled() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        List<InputMethodInfo> enabledInputMethodList = imm.getEnabledInputMethodList();

        String myInputMethodId = getPackageName() + "/" + WhisperInputMethodService.class.getName();
        boolean inputMethodEnabled = false;
        for (InputMethodInfo imi : enabledInputMethodList) {
            if (imi.getId().equals(myInputMethodId)) {
                inputMethodEnabled = true;
                break;
            }
        }
        if (!inputMethodEnabled) {
            Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
            startActivity(intent);
        }
    }

    private void appendTranscriptPrefixToEditText(EditText et, String prefix) {
        if (et == null) return;
        String p = prefix.trim();
        if (p.isEmpty()) return;
        Editable ed = et.getText();
        int pos = ed.length();
        et.setSelection(pos);
        ed.insert(pos, p.endsWith(" ") ? p : p + " ");
        moveCursorToEnd(et);
    }

    /** Collapses selection and places the caret after the last character (avoids voice newline replacing a selection). */
    private static void moveCursorToEnd(EditText et) {
        if (et == null) return;
        Editable ed = et.getText();
        if (ed == null) return;
        et.setSelection(ed.length());
    }

    /**
     * Joins text that was already in the field with a live partial; adds a space when both sides are
     * non-empty and neither already provides separating whitespace (matches IME-style spacing between sentences).
     */
    private static String joinLivePrefixWithPartial(String prefix, String partial) {
        if (prefix == null || prefix.isEmpty()) {
            return partial != null ? partial : "";
        }
        if (partial == null || partial.isEmpty()) {
            return prefix;
        }
        char plast = prefix.charAt(prefix.length() - 1);
        char pfirst = partial.charAt(0);
        if (Character.isWhitespace(plast) || Character.isWhitespace(pfirst)) {
            return prefix + partial;
        }
        return prefix + " " + partial;
    }

    private void startMainWhisperLivePreviewIfNeeded() {
        if (mRecorder == null || mWhisper == null) {
            return;
        }
        stopMainWhisperLivePreview();
        mainWhisperLiveLoop = new WhisperLivePreviewLoop(mainHandler, mRecorder, mWhisper,
                partial -> runOnUiThread(() -> {
                    if (!liveTranscribe.isChecked()) {
                        return;
                    }
                    if (handleLivePartialVoiceCommand(partial, mainLiveAppendPrefix)) {
                        return;
                    }
                    if (mainLiveAppendPrefix != null) {
                        tvResult.setText(joinLivePrefixWithPartial(mainLiveAppendPrefix, partial));
                    } else {
                        tvResult.setText(partial != null ? partial : "");
                    }
                    moveCursorToEnd(tvResult);
                }));
        mainWhisperLiveLoop.start();
    }

    private void stopMainWhisperLivePreview() {
        if (mainWhisperLiveLoop != null) {
            mainWhisperLiveLoop.stop();
            mainWhisperLiveLoop = null;
        }
    }

    /**
     * Final transcript after Whisper live partials (same rules as {@link #finishStreamingEngineHold} live branch).
     */
    private void finishMainActivityWhisperLiveHold(String fin) {
        Set<String> undo = VoiceCommandPreferences.normalizedUndoPhrases(sp);
        Set<String> nl = VoiceCommandPreferences.normalizedNewlinePhrases(sp);
        ImeTextEditHelper.VoiceCommandTail tail =
                ImeTextEditHelper.findTrailingVoiceCommand(fin, undo, nl);
        if (tail.hasCommand()) {
            if (!mainVoiceCommandConsumed) {
                String base = mainLiveAppendPrefix != null ? mainLiveAppendPrefix : "";
                if (tail.kind == ImeTextEditHelper.VoiceCommandKind.UNDO) {
                    if (!VoiceInputUndoStack.popToEditText(tvResult)) {
                        ImeTextEditHelper.applySentenceUndoFallbackToEditText(tvResult);
                    }
                    if (!tail.prefix.isEmpty()) {
                        appendTranscriptPrefixToEditText(tvResult, tail.prefix);
                    }
                    moveCursorToEnd(tvResult);
                } else {
                    VoiceInputUndoStack.pushFromEditText(tvResult);
                    tvResult.setText(joinLivePrefixWithPartial(base, tail.prefix));
                    moveCursorToEnd(tvResult);
                    ImeTextEditHelper.applyNewLineAtEndToEditText(tvResult);
                }
            }
            mainVoiceCommandConsumed = false;
        } else if (append.isChecked() && !fin.trim().isEmpty()) {
            CharSequence cur = tvResult.getText();
            if (cur.length() > 0 && cur.charAt(cur.length() - 1) != ' ') {
                tvResult.append(" ");
            }
            mainVoiceCommandConsumed = false;
        } else {
            mainVoiceCommandConsumed = false;
            if (!fin.trim().isEmpty() && tvResult.getText().toString().trim().isEmpty()) {
                tvResult.setText(fin);
                moveCursorToEnd(tvResult);
            }
        }
        mainLiveAppendPrefix = null;
    }

    /** Append mode: gap before chunk if needed, then append; caret at end. */
    private void appendTranscriptChunkWithGap(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        Editable ed = tvResult.getText();
        int len = ed.length();
        if (len > 0
                && !Character.isWhitespace(ed.charAt(len - 1))
                && !Character.isWhitespace(chunk.charAt(0))) {
            ed.append(' ');
        }
        ed.append(chunk);
        moveCursorToEnd(tvResult);
    }

    private boolean applyVoiceCommandToResultIfMatches(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.isEmpty()) return false;
        Set<String> undo = VoiceCommandPreferences.normalizedUndoPhrases(sp);
        Set<String> nl = VoiceCommandPreferences.normalizedNewlinePhrases(sp);
        ImeTextEditHelper.VoiceCommandTail tail =
                ImeTextEditHelper.findTrailingVoiceCommand(text, undo, nl);
        if (!tail.hasCommand()) {
            return false;
        }
        if (tail.kind == ImeTextEditHelper.VoiceCommandKind.UNDO) {
            return ImeTextEditHelper.applyUndoToEditText(tvResult);
        }
        VoiceInputUndoStack.pushFromEditText(tvResult);
        if (!tail.prefix.isEmpty()) {
            appendTranscriptPrefixToEditText(tvResult, tail.prefix);
        }
        return ImeTextEditHelper.applyNewLineAtEndToEditText(tvResult);
    }

    private boolean handleLivePartialVoiceCommand(String partial, String liveAppendPrefix) {
        if (partial == null) return false;
        Set<String> undo = VoiceCommandPreferences.normalizedUndoPhrases(sp);
        Set<String> nl = VoiceCommandPreferences.normalizedNewlinePhrases(sp);
        ImeTextEditHelper.VoiceCommandTail tail =
                ImeTextEditHelper.findTrailingVoiceCommand(partial, undo, nl);
        if (!tail.hasCommand()) {
            return false;
        }
        if (tail.kind == ImeTextEditHelper.VoiceCommandKind.UNDO) {
            if (!VoiceInputUndoStack.popToEditText(tvResult)) {
                ImeTextEditHelper.applySentenceUndoFallbackToEditText(tvResult);
            }
            if (!tail.prefix.isEmpty()) {
                appendTranscriptPrefixToEditText(tvResult, tail.prefix);
            }
            moveCursorToEnd(tvResult);
        } else {
            String base = liveAppendPrefix != null ? liveAppendPrefix : "";
            VoiceInputUndoStack.pushFromEditText(tvResult);
            tvResult.setText(joinLivePrefixWithPartial(base, tail.prefix));
            moveCursorToEnd(tvResult);
            ImeTextEditHelper.applyNewLineAtEndToEditText(tvResult);
        }
        mainVoiceCommandConsumed = true;
        return true;
    }

    private void finishStreamingEngineHold(String fin, boolean liveNow, long elapsed, String asrEngineLabel) {
        Set<String> undo = VoiceCommandPreferences.normalizedUndoPhrases(sp);
        Set<String> nl = VoiceCommandPreferences.normalizedNewlinePhrases(sp);
        if (!liveNow) {
            mainLiveAppendPrefix = null;
            if (!applyVoiceCommandToResultIfMatches(fin)) {
                VoiceInputUndoStack.pushFromEditText(tvResult);
                if (append.isChecked()) {
                    if (!fin.trim().isEmpty()) {
                        appendTranscriptChunkWithGap(fin + " ");
                    } else {
                        moveCursorToEnd(tvResult);
                    }
                } else {
                    tvResult.setText(fin);
                    moveCursorToEnd(tvResult);
                }
            }
        } else {
            ImeTextEditHelper.VoiceCommandTail tail =
                    ImeTextEditHelper.findTrailingVoiceCommand(fin, undo, nl);
            if (tail.hasCommand()) {
                if (!mainVoiceCommandConsumed) {
                    String base = mainLiveAppendPrefix != null ? mainLiveAppendPrefix : "";
                    if (tail.kind == ImeTextEditHelper.VoiceCommandKind.UNDO) {
                        if (!VoiceInputUndoStack.popToEditText(tvResult)) {
                            ImeTextEditHelper.applySentenceUndoFallbackToEditText(tvResult);
                        }
                        if (!tail.prefix.isEmpty()) {
                            appendTranscriptPrefixToEditText(tvResult, tail.prefix);
                        }
                        moveCursorToEnd(tvResult);
                    } else {
                        VoiceInputUndoStack.pushFromEditText(tvResult);
                        tvResult.setText(joinLivePrefixWithPartial(base, tail.prefix));
                        moveCursorToEnd(tvResult);
                        ImeTextEditHelper.applyNewLineAtEndToEditText(tvResult);
                    }
                }
                mainVoiceCommandConsumed = false;
            } else if (append.isChecked() && !fin.trim().isEmpty()) {
                CharSequence cur = tvResult.getText();
                if (cur.length() > 0 && cur.charAt(cur.length() - 1) != ' ') {
                    tvResult.append(" ");
                }
                mainVoiceCommandConsumed = false;
            } else {
                mainVoiceCommandConsumed = false;
                // Partials usually fill the editor during the hold; if they never did but the final
                // transcript is non-empty (e.g. first tokens only at stop), apply fin here.
                if (!fin.trim().isEmpty() && tvResult.getText().toString().trim().isEmpty()) {
                    tvResult.setText(fin);
                    moveCursorToEnd(tvResult);
                }
            }
            mainLiveAppendPrefix = null;
        }
        tvStatus.setText(getString(R.string.processing_done) + elapsed + "\u2009ms\n"
                + getString(R.string.language) + " " + asrEngineLabel);
    }

    // Model initialization
    private void initModel() {
        if (!AsrEnginePreferences.WHISPER.equals(AsrEnginePreferences.mainEngine(this))) {
            deinitModel();
            return;
        }
        File modelFile = WhisperModelSelection.tfliteFileForMainScreen(sdcardDataFolder, sp, MULTI_LINGUAL_TOP_WORLD_SLOW);
        boolean isMultilingualModel = !(modelFile.getName().endsWith(ENGLISH_ONLY_MODEL_EXTENSION));
        String vocabFileName = isMultilingualModel ? MULTILINGUAL_VOCAB_FILE : ENGLISH_ONLY_VOCAB_FILE;
        File vocabFile = new File(sdcardDataFolder, vocabFileName);

        mWhisper = new Whisper(this);
        mWhisper.loadModel(modelFile, vocabFile, isMultilingualModel);
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
                if (message.equals(Whisper.MSG_PROCESSING)) {
                    runOnUiThread(() -> tvStatus.setText(getString(R.string.processing)));
                    startTime = System.currentTimeMillis();
                    runOnUiThread(() -> spinnerTflite.setEnabled(false));
                } else if (message.equals(Whisper.MSG_PROCESSING_DONE)) {
                    runOnUiThread(() -> setWhisperModelSpinnerEnabledIfModelsPresent());
                } else if (message != null && (message.startsWith("Transcription failed")
                        || message.contains("Engine not initialized"))) {
                    runOnUiThread(() -> {
                        processingBar.setIndeterminate(false);
                        setWhisperModelSpinnerEnabledIfModelsPresent();
                    });
                }
            }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                long timeTaken = System.currentTimeMillis() - startTime;
                final boolean wasLive = whisperSessionLiveTranscribe;
                whisperSessionLiveTranscribe = false;

                boolean zh = whisperResult.getLanguage().equals("zh")
                        && whisperResult.getTask() == Whisper.Action.TRANSCRIBE;
                String raw = whisperResult.getResult();
                final String textForUi;
                if (zh) {
                    boolean simpleChinese = sp.getBoolean("simpleChinese", false);
                    textForUi = simpleChinese ? ZhConverterUtil.toSimple(raw) : ZhConverterUtil.toTraditional(raw);
                } else {
                    textForUi = raw;
                }

                runOnUiThread(() -> {
                    tvStatus.setText(getString(R.string.processing_done) + timeTaken + "\u2009ms" + "\n"
                            + getString(R.string.language) + " "
                            + new Locale(whisperResult.getLanguage()).getDisplayLanguage() + " "
                            + (whisperResult.getTask() == Whisper.Action.TRANSCRIBE
                            ? getString(R.string.mode_transcription) : getString(R.string.mode_translation)));
                    processingBar.setIndeterminate(false);
                    if (zh) {
                        layoutModeChinese.setVisibility(View.VISIBLE);
                    } else {
                        layoutModeChinese.setVisibility(View.GONE);
                    }

                    if (wasLive) {
                        if (!applyVoiceCommandToResultIfMatches(textForUi)) {
                            finishMainActivityWhisperLiveHold(textForUi);
                        }
                    } else {
                        if (!applyVoiceCommandToResultIfMatches(textForUi)) {
                            VoiceInputUndoStack.pushFromEditText(tvResult);
                            if (append.isChecked()) {
                                appendTranscriptChunkWithGap(textForUi);
                            } else {
                                tvResult.setText(textForUi);
                                moveCursorToEnd(tvResult);
                            }
                        }
                    }
                    spinnerTflite.setEnabled(true);
                    if (modeTTS.isChecked()) {
                        tts.speak(whisperResult.getResult(), TextToSpeech.QUEUE_FLUSH, null, null);
                    }
                });
            }
        });
    }

    private void deinitModel() {
        if (mWhisper != null) {
            mWhisper.unloadModel();
            mWhisper = null;
        }
    }

    private void applyLanguageSpinnerForModelChoice(LanguagePairAdapter languagePairAdapter) {
        String eng = AsrEnginePreferences.mainEngine(this);
        if (!AsrEnginePreferences.WHISPER.equals(eng)) {
            spinnerLanguage.setSelection(0);
            spinnerLanguage.setEnabled(false);
            return;
        }
        String name = selectedTfliteFile.getName();
        if (name.equals(ENGLISH_ONLY_MODEL)) {
            spinnerLanguage.setSelection(0);
            spinnerLanguage.setEnabled(false);
            return;
        }
        if (name.equals(MULTI_LINGUAL_EU_MODEL_FAST) || name.equals(MULTI_LINGUAL_TOP_WORLD_FAST) || name.equals(MULTI_LINGUAL_TOP_WORLD_SLOW)) {
            spinnerLanguage.setEnabled(true);
            String langCode = sp.getString("language", "auto");
            spinnerLanguage.setSelection(languagePairAdapter.getIndexByCode(langCode));
        } else {
            spinnerLanguage.setSelection(0);
            spinnerLanguage.setEnabled(false);
        }
    }

    private void maybeMigrateModelNameFromSentinel() {
        String mn = WhisperModelSelection.mainScreenModelBasename(sp, MULTI_LINGUAL_TOP_WORLD_SLOW);
        if (mn.contains("parakeet.streaming") || mn.contains("moonshine")) {
            sp.edit().putString(WhisperModelSelection.PREFS_KEY_MAIN_SCREEN, MULTI_LINGUAL_TOP_WORLD_SLOW).apply();
        }
    }

    private void bindWhisperTfliteSpinnerListeners() {
        spinnerTflite.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedTfliteFile = (File) parent.getItemAtPosition(position);
                SharedPreferences.Editor editor = sp.edit();
                editor.putString(WhisperModelSelection.PREFS_KEY_MAIN_SCREEN, selectedTfliteFile.getName());
                editor.apply();
                if (AsrEnginePreferences.WHISPER.equals(AsrEnginePreferences.mainEngine(MainActivity.this))) {
                    initModel();
                }
                applyLanguageSpinnerForModelChoice(languagePairAdapter);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /**
     * Rescans {@code getExternalFilesDir} for *.tflite (e.g. after download). Safe to call from {@link #onResume()}.
     */
    private void refreshWhisperTfliteSpinner() {
        applyWhisperTfliteSpinnerData();
    }

    private void applyWhisperTfliteSpinnerData() {
        if (spinnerTflite == null || sdcardDataFolder == null) {
            return;
        }
        maybeMigrateModelNameFromSentinel();
        ArrayList<File> tfliteFiles = getFilesWithExtension(sdcardDataFolder, ".tflite");
        selectedTfliteFile = WhisperModelSelection.tfliteFileForMainScreen(sdcardDataFolder, sp, MULTI_LINGUAL_TOP_WORLD_SLOW);
        ArrayAdapter<File> tfliteAdapter = getFileArrayAdapter(tfliteFiles);
        spinnerTflite.setAdapter(tfliteAdapter);
        int position = tfliteAdapter.getPosition(selectedTfliteFile);
        if (tfliteFiles.isEmpty()) {
            spinnerTflite.setEnabled(false);
        } else {
            spinnerTflite.setEnabled(true);
            spinnerTflite.setSelection(position >= 0 ? position : 0, false);
            if (position < 0) {
                selectedTfliteFile = tfliteAdapter.getItem(0);
                sp.edit().putString(WhisperModelSelection.PREFS_KEY_MAIN_SCREEN, selectedTfliteFile.getName()).apply();
            }
        }
        applyLanguageSpinnerForModelChoice(languagePairAdapter);

        if (!AsrEnginePreferences.WHISPER.equals(AsrEnginePreferences.mainEngine(this))) {
            return;
        }
        File want = WhisperModelSelection.tfliteFileForMainScreen(sdcardDataFolder, sp, MULTI_LINGUAL_TOP_WORLD_SLOW);
        if (!want.isFile()) {
            return;
        }
        String cur = mWhisper != null ? mWhisper.getCurrentModelPath() : "";
        if (mWhisper == null || cur == null || cur.isEmpty() || !want.getAbsolutePath().equals(cur)) {
            initModel();
        }
    }

    private void setWhisperModelSpinnerEnabledIfModelsPresent() {
        if (spinnerTflite == null) {
            return;
        }
        Adapter ad = spinnerTflite.getAdapter();
        int n = ad != null ? ad.getCount() : 0;
        spinnerTflite.setEnabled(n > 0);
    }

    private void applyEngineUiMode(String engine) {
        boolean whisper = AsrEnginePreferences.WHISPER.equals(engine);
        layoutWhisperModels.setVisibility(whisper ? View.VISIBLE : View.GONE);
        layoutSherpaModels.setVisibility(AsrEnginePreferences.SHERPA.equals(engine) ? View.VISIBLE : View.GONE);
        layoutSherpaOptions.setVisibility(AsrEnginePreferences.SHERPA.equals(engine) ? View.VISIBLE : View.GONE);
        boolean liveOk = AsrEnginePreferences.PARAKEET.equals(engine)
                || AsrEnginePreferences.MOONSHINE.equals(engine)
                || AsrEnginePreferences.SHERPA.equals(engine)
                || AsrEnginePreferences.WHISPER.equals(engine);
        liveTranscribe.setEnabled(liveOk);
        liveTranscribe.setAlpha(liveOk ? 1f : 0.45f);
    }

    private void bindSherpaModelSpinner() {
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (SherpaCatalogEntry e : SherpaCatalogEntry.ENTRIES) {
            labels.add(getString(e.getLabelRes()));
        }
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnrSherpaModel.setAdapter(ad);
        String curId = SherpaPreferences.selectedCatalogId(this);
        int sel = 0;
        java.util.List<SherpaCatalogEntry> list = SherpaCatalogEntry.ENTRIES;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId().equals(curId)) {
                sel = i;
                break;
            }
        }
        spnrSherpaModel.setSelection(sel, false);
        spnrSherpaModel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                SherpaPreferences.setSelectedCatalogId(MainActivity.this, list.get(position).getId());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void openDownloadForEngine(String engine) {
        Intent i = new Intent(this, DownloadActivity.class);
        i.putExtra(DownloadActivity.EXTRA_PREFERRED_ENGINE, engine);
        startActivity(i);
        Toast.makeText(this, R.string.models_missing_open_download, Toast.LENGTH_SHORT).show();
    }

    /** Loads Parakeet ONNX sessions in the background so the first hold skips multi-second init. */
    private void maybePreheatParakeet() {
        if (!AsrEnginePreferences.PARAKEET.equals(AsrEnginePreferences.mainEngine(this))) {
            return;
        }
        if (sdcardDataFolder == null || !ParakeetModelFiles.allOnnxPresent(sdcardDataFolder)) {
            return;
        }
        ParakeetEnginePool.warm(this, sdcardDataFolder);
    }

    private boolean ensureEngineModelsReady() {
        String eng = AsrEnginePreferences.mainEngine(this);
        if (AsrEnginePreferences.WHISPER.equals(eng)) {
            if (selectedTfliteFile == null || !selectedTfliteFile.isFile()) {
                Toast.makeText(this, R.string.whisper_model_missing, Toast.LENGTH_LONG).show();
                openDownloadForEngine(AsrEnginePreferences.WHISPER);
                return false;
            }
            return true;
        }
        if (AsrEnginePreferences.PARAKEET.equals(eng)) {
            if (sdcardDataFolder == null || !ParakeetModelFiles.allOnnxPresent(sdcardDataFolder)) {
                Toast.makeText(this, R.string.parakeet_models_missing, Toast.LENGTH_LONG).show();
                openDownloadForEngine(AsrEnginePreferences.PARAKEET);
                return false;
            }
            return true;
        }
        if (AsrEnginePreferences.MOONSHINE.equals(eng)) {
            if (!MoonshineModelFiles.hasArm64V8aAbi()) {
                Toast.makeText(this, R.string.moonshine_requires_arm64_device, Toast.LENGTH_LONG).show();
                return false;
            }
            if (!MoonshineModelFiles.loadMoonshineNativeLibraries()) {
                Toast.makeText(this, R.string.moonshine_native_libraries_failed, Toast.LENGTH_LONG).show();
                return false;
            }
            if (!MoonshineModelFiles.hasMoonshineBaseModelFilesOnDisk(mContext)) {
                Toast.makeText(this, R.string.moonshine_models_missing, Toast.LENGTH_LONG).show();
                openDownloadForEngine(AsrEnginePreferences.MOONSHINE);
                return false;
            }
            return true;
        }
        if (AsrEnginePreferences.SHERPA.equals(eng)) {
            if (sdcardDataFolder == null
                    || !com.whispertflite.sherpa.SherpaModelFiles.allFilesPresentForSelectedVariant(
                            sdcardDataFolder, this)) {
                Toast.makeText(this, R.string.sherpa_models_missing, Toast.LENGTH_LONG).show();
                openDownloadForEngine(AsrEnginePreferences.SHERPA);
                return false;
            }
            return true;
        }
        return true;
    }

    private void deinitTTS(){
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    private @NonNull ArrayAdapter<File> getFileArrayAdapter(ArrayList<File> tfliteFiles) {
        ArrayAdapter<File> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, tfliteFiles) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView textView = view.findViewById(android.R.id.text1);
                if ((getItem(position).getName()).equals(MULTI_LINGUAL_MODEL_SLOW))
                    textView.setText(R.string.multi_lingual_slow);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_TOP_WORLD_SLOW))
                    textView.setText(R.string.multi_lingual_slow);
                else if ((getItem(position).getName()).equals(ENGLISH_ONLY_MODEL))
                    textView.setText(R.string.english_only_fast);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_MODEL_FAST))
                    textView.setText(R.string.multi_lingual_fast);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_EU_MODEL_FAST))
                    textView.setText(R.string.multi_lingual_fast);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_TOP_WORLD_FAST))
                    textView.setText(R.string.multi_lingual_fast);
                else
                    textView.setText(getItem(position).getName().substring(0, getItem(position).getName().length() - ".tflite".length()));

                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                TextView textView = view.findViewById(android.R.id.text1);
                if ((getItem(position).getName()).equals(MULTI_LINGUAL_MODEL_SLOW))
                    textView.setText(R.string.multi_lingual_slow);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_TOP_WORLD_SLOW))
                    textView.setText(R.string.multi_lingual_slow);
                else if ((getItem(position).getName()).equals(ENGLISH_ONLY_MODEL))
                    textView.setText(R.string.english_only_fast);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_MODEL_FAST))
                    textView.setText(R.string.multi_lingual_fast);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_EU_MODEL_FAST))
                    textView.setText(R.string.multi_lingual_fast);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_TOP_WORLD_FAST))
                    textView.setText(R.string.multi_lingual_fast);
                else
                    textView.setText(getItem(position).getName().substring(0, getItem(position).getName().length() - ".tflite".length()));

                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private void checkPermissions() {
        List<String> perms = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO);
            Toast.makeText(this, getString(R.string.need_record_audio_permission), Toast.LENGTH_SHORT).show();
        }
        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) && (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)){
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!perms.isEmpty()) {
            requestPermissions(perms.toArray(new String[] {}), 0);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        } else {
        }
    }

    // Recording calls
    private void startRecording() {
        checkPermissions();
        mRecorder.start();
    }

    private void stopRecording() {
        mRecorder.stop();
    }

    // Transcription calls
    private void startProcessing(Whisper.Action action) {
        if (mWhisper == null) {
            Toast.makeText(this, R.string.whisper_model_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        if (countDownTimer!=null) { countDownTimer.cancel();}
        runOnUiThread(() -> {
            processingBar.setProgress(0);
            processingBar.setIndeterminate(true);
        });
        mWhisper.setAction(action);
        mWhisper.setLanguage(langToken);
        mWhisper.start();
    }

    private void stopProcessing() {
        stopMainWhisperLivePreview();
        processingBar.setIndeterminate(false);
        runOnUiThread(this::setWhisperModelSpinnerEnabledIfModelsPresent);
        if (moonshineMainRecorder != null) {
            moonshineMainRecorder.stop();
            moonshineMainRecorder = null;
        }
        if (parakeetMainRecorder != null) {
            parakeetMainRecorder.stop();
            parakeetMainRecorder = null;
        }
        if (sherpaMainRecorder != null) {
            sherpaMainRecorder.stop();
            sherpaMainRecorder = null;
        }
        if (mWhisper != null && mWhisper.isInProgress()) mWhisper.stop();
    }

    public ArrayList<File> getFilesWithExtension(File directory, String extension) {
        ArrayList<File> filteredFiles = new ArrayList<>();

        // Check if the directory is accessible
        if (directory != null && directory.exists()) {
            File[] files = directory.listFiles();

            // Filter files by the provided extension
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(extension)) {
                        filteredFiles.add(file);
                    }
                }
            }
        }

        return filteredFiles;
    }

}