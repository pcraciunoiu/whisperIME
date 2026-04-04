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
import android.util.Log;
import android.util.Pair;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.EditText;
import androidx.activity.OnBackPressedCallback;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.whispertflite.asr.Recorder;
import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperResult;
import com.whispertflite.moonshine.MoonshineHoldRecorder;
import com.whispertflite.moonshine.MoonshineModelFiles;
import com.whispertflite.moonshine.MoonshinePocActivity;
import com.whispertflite.moonshine.MoonshinePreferences;
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

public class MainActivity extends AppCompatActivity {
    private Context mContext;
    private static final String TAG = "MainActivity";

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
    private FloatingActionButton fabCopy;
    private ImageButton btnRecord;
    private LinearLayout layoutModeChinese;
    private LinearLayout layoutTTS;
    private CheckBox append;
    private CheckBox translate;
    private CheckBox modeSimpleChinese;
    private CheckBox modeTTS;
    private ProgressBar processingBar;
    private ImageButton btnInfo;

    private Recorder mRecorder = null;
    private Whisper mWhisper = null;
    private MoonshineHoldRecorder moonshineMainRecorder = null;
    private ParakeetStreamingRecorder parakeetMainRecorder = null;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private File sdcardDataFolder = null;
    private File selectedTfliteFile = null;
    private SharedPreferences sp = null;
    private Spinner spnrAsrEngine;
    private LinearLayout layoutWhisperModels;
    private Spinner spinnerTflite;
    private CountDownTimer countDownTimer;
    private Spinner spinnerLanguage;
    private int langToken = -1;
    private long startTime = 0;
    private TextToSpeech tts;

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
        deinitModel();
        deinitTTS();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        stopProcessing();
        super.onPause();
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
        spnrAsrEngine = findViewById(R.id.spnrAsrEngine);
        ArrayAdapter<CharSequence> engineAdapter = ArrayAdapter.createFromResource(this,
                R.array.asr_engine_entries, android.R.layout.simple_spinner_item);
        engineAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnrAsrEngine.setAdapter(engineAdapter);
        String[] engineValues = getResources().getStringArray(R.array.asr_engine_entry_values);
        String currentEngine = AsrEnginePreferences.mainEngine(this);
        int engineSel = 0;
        for (int i = 0; i < engineValues.length; i++) {
            if (engineValues[i].equals(currentEngine)) {
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
        applyEngineUiMode(engineValues[engineSel]);

        spnrAsrEngine.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String eng = engineValues[position];
                AsrEnginePreferences.setMainEngine(MainActivity.this, eng);
                if (AsrEnginePreferences.WHISPER.equals(eng)) {
                    initModel();
                } else {
                    deinitModel();
                }
                applyEngineUiMode(eng);
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
        LanguagePairAdapter languagePairAdapter = new LanguagePairAdapter(this, android.R.layout.simple_spinner_item, languagePairs);
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

        selectedTfliteFile = new File(sdcardDataFolder, sp.getString("modelName", MULTI_LINGUAL_TOP_WORLD_SLOW));
        ArrayAdapter<File> tfliteAdapter = getFileArrayAdapter(tfliteFiles);
        int position = tfliteAdapter.getPosition(selectedTfliteFile);
        spinnerTflite = findViewById(R.id.spnrTfliteFiles);
        spinnerTflite.setAdapter(tfliteAdapter);
        if (tfliteFiles.isEmpty()) {
            spinnerTflite.setEnabled(false);
        } else {
            spinnerTflite.setEnabled(true);
            spinnerTflite.setSelection(position >= 0 ? position : 0, false);
            if (position < 0) {
                selectedTfliteFile = tfliteAdapter.getItem(0);
                sp.edit().putString("modelName", selectedTfliteFile.getName()).apply();
            }
        }
        applyLanguageSpinnerForModelChoice(languagePairAdapter);
        spinnerTflite.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedTfliteFile = (File) parent.getItemAtPosition(position);
                SharedPreferences.Editor editor = sp.edit();
                editor.putString("modelName",selectedTfliteFile.getName());
                editor.apply();
                if (AsrEnginePreferences.WHISPER.equals(AsrEnginePreferences.mainEngine(MainActivity.this))) {
                    initModel();
                }
                applyLanguageSpinnerForModelChoice(languagePairAdapter);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Handle case when nothing is selected, if needed
            }
        });


        // Implementation of record button functionality
        btnRecord = findViewById(R.id.btnRecord);

        btnRecord.setOnTouchListener((v, event) -> {
            String eng = AsrEnginePreferences.mainEngine(MainActivity.this);
            if (AsrEnginePreferences.MOONSHINE.equals(eng)) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                    if (!ensureEngineModelsReady()) return true;
                    HapticFeedback.vibrate(this);
                    if (!append.isChecked()) runOnUiThread(() -> tvResult.setText(""));
                    startTime = System.currentTimeMillis();
                    moonshineMainRecorder = new MoonshineHoldRecorder(mContext, mainHandler,
                            partial -> runOnUiThread(() -> tvResult.setText(partial)));
                    if (!moonshineMainRecorder.start()) {
                        Toast.makeText(this, R.string.moonshine_start_failed, Toast.LENGTH_SHORT).show();
                        moonshineMainRecorder = null;
                    }
                    runOnUiThread(() -> processingBar.setProgress(100));
                    countDownTimer = new CountDownTimer(30000, 1000) {
                        @Override
                        public void onTick(long l) {
                            runOnUiThread(() -> processingBar.setProgress((int) (l / 300)));
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
                        runOnUiThread(() -> {
                            if (append.isChecked()) tvResult.append(fin + " ");
                            else tvResult.setText(fin);
                            tvStatus.setText(getString(R.string.processing_done) + elapsed + "\u2009ms\n"
                                    + getString(R.string.language) + " " + getString(R.string.moonshine_asr_model));
                        });
                    }
                }
                return true;
            }
            if (AsrEnginePreferences.PARAKEET.equals(eng)) {
                boolean live = sp.getBoolean("liveTranscribePartials", false);
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                    if (!ensureEngineModelsReady()) return true;
                    HapticFeedback.vibrate(this);
                    if (!append.isChecked()) runOnUiThread(() -> tvResult.setText(""));
                    startTime = System.currentTimeMillis();
                    parakeetMainRecorder = new ParakeetStreamingRecorder(mContext, sdcardDataFolder, mainHandler,
                            partial -> {
                                if (live) {
                                    runOnUiThread(() -> tvResult.setText(partial));
                                }
                            });
                    if (!parakeetMainRecorder.start()) {
                        Toast.makeText(this, R.string.parakeet_start_failed, Toast.LENGTH_SHORT).show();
                        parakeetMainRecorder = null;
                    }
                    runOnUiThread(() -> processingBar.setProgress(100));
                    countDownTimer = new CountDownTimer(30000, 1000) {
                        @Override
                        public void onTick(long l) {
                            runOnUiThread(() -> processingBar.setProgress((int) (l / 300)));
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
                        runOnUiThread(() -> {
                            if (!live) {
                                if (append.isChecked()) tvResult.append(fin + " ");
                                else tvResult.setText(fin);
                            } else if (append.isChecked() && fin.trim().length() > 0) {
                                tvResult.append(" ");
                            }
                            tvStatus.setText(getString(R.string.processing_done) + elapsed + "\u2009ms\n"
                                    + getString(R.string.language) + " Parakeet (English)");
                        });
                    }
                }
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                // Pressed
                runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                Log.d(TAG, "Start recording...");
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
                    startRecording();
                    runOnUiThread(() -> processingBar.setProgress(100));
                    countDownTimer = new CountDownTimer(30000, 1000) {
                        @Override
                        public void onTick(long l) {
                            runOnUiThread(() -> processingBar.setProgress((int) (l / 300)));
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
                    Log.d(TAG, "Recording is in progress... stopping...");
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
                Log.d(TAG, "Update is received, Message: " + message);
                if (message.equals(Recorder.MSG_RECORDING)) {
                    runOnUiThread(() -> tvStatus.setText(getString(R.string.record_button) +"…"));
                    if (!append.isChecked()) runOnUiThread(() -> tvResult.setText(""));
                    runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                    HapticFeedback.vibrate(mContext);
                    runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background));

                    if (translate.isChecked()) startProcessing(Whisper.ACTION_TRANSLATE);
                    else startProcessing(Whisper.ACTION_TRANSCRIBE);
                } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
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

    // Model initialization
    private void initModel() {
        if (!AsrEnginePreferences.WHISPER.equals(AsrEnginePreferences.mainEngine(this))) {
            deinitModel();
            return;
        }
        File modelFile = new File(sdcardDataFolder, sp.getString("modelName", MULTI_LINGUAL_TOP_WORLD_SLOW));
        boolean isMultilingualModel = !(modelFile.getName().endsWith(ENGLISH_ONLY_MODEL_EXTENSION));
        String vocabFileName = isMultilingualModel ? MULTILINGUAL_VOCAB_FILE : ENGLISH_ONLY_VOCAB_FILE;
        File vocabFile = new File(sdcardDataFolder, vocabFileName);

        mWhisper = new Whisper(this);
        mWhisper.loadModel(modelFile, vocabFile, isMultilingualModel);
        Log.d(TAG, "Initialized: " + modelFile.getName());
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
                Log.d(TAG, "Update is received, Message: " + message);

                if (message.equals(Whisper.MSG_PROCESSING)) {
                    runOnUiThread(() -> tvStatus.setText(getString(R.string.processing)));
                    startTime = System.currentTimeMillis();
                    runOnUiThread(() -> spinnerTflite.setEnabled(false));
                }
            }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                long timeTaken = System.currentTimeMillis() - startTime;
                runOnUiThread(() -> tvStatus.setText(getString(R.string.processing_done) + timeTaken + "\u2009ms" + "\n"+ getString(R.string.language) + " " + new Locale(whisperResult.getLanguage()).getDisplayLanguage() + " " + (whisperResult.getTask() == Whisper.Action.TRANSCRIBE ? getString(R.string.mode_transcription) : getString(R.string.mode_translation))));
                runOnUiThread(() -> processingBar.setIndeterminate(false));
                Log.d(TAG, "Result: " + whisperResult.getResult() + " " + whisperResult.getLanguage() + " " + (whisperResult.getTask() == Whisper.Action.TRANSCRIBE ? "transcribing" : "translating"));
                if ((whisperResult.getLanguage().equals("zh")) && (whisperResult.getTask() == Whisper.Action.TRANSCRIBE)){
                    runOnUiThread(() -> layoutModeChinese.setVisibility(View.VISIBLE));
                    boolean simpleChinese = sp.getBoolean("simpleChinese",false);  //convert to desired Chinese mode
                    String result = simpleChinese ? ZhConverterUtil.toSimple(whisperResult.getResult()) : ZhConverterUtil.toTraditional(whisperResult.getResult());
                    runOnUiThread(() -> tvResult.append(result));
                } else {
                    runOnUiThread(() -> layoutModeChinese.setVisibility(View.GONE));
                    runOnUiThread(() -> tvResult.append(whisperResult.getResult()));
                }
                runOnUiThread(() -> spinnerTflite.setEnabled(true));
                if (modeTTS.isChecked()){
                    tts.speak(whisperResult.getResult(), TextToSpeech.QUEUE_FLUSH, null, null);
                }
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
        String mn = sp.getString("modelName", MULTI_LINGUAL_TOP_WORLD_SLOW);
        if (mn.contains("parakeet.streaming") || mn.contains("moonshine")) {
            sp.edit().putString("modelName", MULTI_LINGUAL_TOP_WORLD_SLOW).apply();
        }
    }

    private void applyEngineUiMode(String engine) {
        boolean whisper = AsrEnginePreferences.WHISPER.equals(engine);
        layoutWhisperModels.setVisibility(whisper ? View.VISIBLE : View.GONE);
    }

    private void openDownloadForEngine(String engine) {
        Intent i = new Intent(this, DownloadActivity.class);
        i.putExtra(DownloadActivity.EXTRA_PREFERRED_ENGINE, engine);
        startActivity(i);
        Toast.makeText(this, R.string.models_missing_open_download, Toast.LENGTH_SHORT).show();
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
            if (!MoonshineModelFiles.isDeviceSupported(mContext)) {
                Toast.makeText(this, R.string.moonshine_arm64_only, Toast.LENGTH_LONG).show();
                return false;
            }
            if (!MoonshineModelFiles.allModelFilesPresent(mContext)) {
                Toast.makeText(this, R.string.moonshine_models_missing, Toast.LENGTH_LONG).show();
                openDownloadForEngine(AsrEnginePreferences.MOONSHINE);
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
            Log.d(TAG, "Record permission is granted");
        } else {
            Log.d(TAG, "Record permission is not granted");
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
        processingBar.setIndeterminate(false);
        if (moonshineMainRecorder != null) {
            moonshineMainRecorder.stop();
            moonshineMainRecorder = null;
        }
        if (parakeetMainRecorder != null) {
            parakeetMainRecorder.stop();
            parakeetMainRecorder = null;
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