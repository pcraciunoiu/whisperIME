> Google has announced that, starting in 2026/2027, all apps on certified Android devices will require the developer to submit personal identity details directly to Google. Since the developers of this app do not agree to this requirement, this app will no longer work on certified Android devices after that time.

# SpeechToText — voice recognition (Whisper, Parakeet, Moonshine)

**SpeechToText** (`org.speechtotext.input`) is an input method editor (IME) with on-device speech recognition. Use it as a **standalone app** (settings + microphone on the main screen) or as an **IME** (e.g. microphone button in [HeliBoard](https://f-droid.org/packages/helium314.keyboard/)). It can also be selected as system-wide **voice input** (`RecognitionService`) and supports `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`.

Choose one of three engines (same choice applies across IME, standalone, and voice input where applicable):

| Engine | Notes |
|--------|--------|
| **Whisper** (TFLite) | Multilingual models; in the standalone app you can **translate** recognized speech to English. With **Transcribe live**, partial text updates while you hold (throttled on-device decode). |
| **Parakeet** (ONNX) | English; **live** streaming partial transcripts while you hold the button. |
| **Moonshine Base** | English on-device model; **live** streaming partials while you hold. |

On first use, the app **downloads** the models for the engine you pick (Whisper from Hugging Face is a large download). Internet is required for downloads; after that, recognition runs on the device.

## Screenshots

**Main screen — engine and model**

Pick Whisper, Parakeet, or Moonshine; download progress appears when models are missing.

<img src="docs/readme/initial-screen-options.png" width="320" alt="Main screen showing speech engine dropdown: Whisper, Parakeet, Moonshine"/>

**Options and voice commands**

Use **Append** to keep adding text across recordings, **Transcribe live** for partial results while holding the button (all three engines; Whisper uses periodic on-device previews), and **Translate to English** with Whisper in the standalone app. Configure comma-separated phrases for **Undo last voice edit** (reverts recent voice-driven text, with sentence fallback) and **New line**.

<img src="docs/readme/settings-features.png" width="320" alt="Main screen with append, live transcribe, translate, and voice command phrase fields"/>

**Store / F-Droid previews**

<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01.png" width="150" alt=""/> <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/02.png" width="150" alt=""/>

## Initial setup

For **voice input** (not the IME), use Android settings (**System → Languages → Speech → Voice input**), enable this app, then open its settings to pick the engine and model.

If SpeechToText does not appear as voice input (only Google/Samsung, etc.):

- enable USB debugging
- run (release / F-Droid package `org.speechtotext.input`):  
  `adb shell settings put secure voice_recognition_service org.speechtotext.input/com.whispertflite.WhisperRecognitionService`
- run (**debug** build from Android Studio / `./gradlew assembleDebug`, package `org.speechtotext.input.debug`):  
  `adb shell settings put secure voice_recognition_service org.speechtotext.input.debug/com.whispertflite.WhisperRecognitionService`

Debug and release install as **separate apps** so local development does not replace the store build.

## Using SpeechToText

- Press and **hold** the microphone button while speaking (or watch **live** partial text where enabled).
- Pause briefly before you start speaking.
- Speak clearly, at a moderate pace.
- Hold-to-talk is limited to about **60 seconds** per recording.

## Donate

<pre>Send a coffee to 
woheller69@t-online.de 
<a href= "https://www.paypal.com/signin"><img  align="left" src="https://www.paypalobjects.com/webstatic/de_DE/i/de-pp-logo-150px.png"></a>

  
Or via this link (with fees)
<a href="https://www.paypal.com/donate?hosted_button_id=XVXQ54LBLZ4AA"><img  align="left" src="https://img.shields.io/badge/Donate%20with%20Debit%20or%20Credit%20Card-002991?style=plastic"></a></pre>

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" height="75">](https://f-droid.org/packages/org.speechtotext.input/)

## Contribute

For translations use https://toolate.othing.xyz/projects/whisperime/

# License

This work is licensed under MIT license, © woheller69

- This app is based on the [Whisper-Android project](https://github.com/vilassn/whisper_android), published under MIT license
- It uses [OpenAI Whisper](https://github.com/openai/whisper) published under MIT license. Details on Whisper are found [here](https://arxiv.org/abs/2212.04356).
- It uses [Android VAD](https://github.com/gkonovalov/android-vad), which is published under MIT license
- It uses [Opencc4j](https://github.com/houbb/opencc4j), for Chinese conversions, published under Apache-2.0 license
- At first start it downloads the Whisper TFLite models from [Hugging Face](https://huggingface.co/DocWolle/whisper_tflite_models), which is published under MIT license

# OTHER APPS

| **RadarWeather** | **Gas Prices** | **Smart Eggtimer** |
|:---:|:---:|:--:|
| [<img src="https://github.com/woheller69/weather/blob/main/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.weather/) | [<img src="https://github.com/woheller69/spritpreise/blob/main/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.spritpreise/) | [<img src="https://github.com/woheller69/eggtimer/blob/main/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.eggtimer/) |
| **Bubble** | **hEARtest** | **GPS Cockpit** |
| [<img src="https://github.com/woheller69/Level/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.level/) | [<img src="https://github.com/woheller69/audiometry/blob/new/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.audiometry/) | [<img src="https://github.com/woheller69/gpscockpit/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.gpscockpit/) |
| **Audio Analyzer** | **LavSeeker** | **TimeLapseCam** |
| [<img src="https://github.com/woheller69/audio-analyzer-for-android/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.audio_analyzer_for_android/) |[<img src="https://github.com/woheller69/lavatories/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.lavatories/) | [<img src="https://github.com/woheller69/TimeLapseCamera/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.TimeLapseCam/) |
| **Arity** | **Cirrus** | **solXpect** |
| [<img src="https://github.com/woheller69/arity/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.arity/) | [<img src="https://github.com/woheller69/omweather/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.omweather/) | [<img src="https://github.com/woheller69/solXpect/blob/main/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.solxpect/) |
| **gptAssist** | **dumpSeeker** | **huggingAssist** |
| [<img src="https://github.com/woheller69/gptassist/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.gptassist/) | [<img src="https://github.com/woheller69/dumpseeker/blob/main/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.dumpseeker/) | [<img src="https://github.com/woheller69/huggingassist/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.hugassist/) |
| **FREE Browser** | **whoBIRD** | **PeakOrama** |
| [<img src="https://github.com/woheller69/browser/blob/newmaster/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.browser/) | [<img src="https://github.com/woheller69/whoBIRD/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.whobird/) | [<img src="https://github.com/woheller69/PeakOrama/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.PeakOrama/) |
| **SpeechToText** | **Seamless** | **SherpaTTS** |
| [<img src="https://github.com/woheller69/whisperIME/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.speechtotext.input/) | [<img src="https://github.com/woheller69/seamless/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.seemless/) | [<img src="https://github.com/woheller69/ttsengine/blob/master/fastlane/metadata/android/en-US/images/icon.png" width="50">](https://f-droid.org/packages/org.woheller69.ttsengine/) |
