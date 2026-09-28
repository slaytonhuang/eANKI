package com.example.eanki.phase0;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.Locale;

/**
 * Demo 2：用系統內建 TextToSpeech 念出 "apple"。
 *
 * <p>除了「有沒有聲音」，這個 demo 更想回答的是 SPEC 要求的離線性：
 * 印出選到的 voice 是否 {@code isNetworkConnectionRequired()}。
 * 若為 true，代表這台裝置需要先下載離線語音資料，複習頁點擊發音會卡網路。
 */
final class TtsDemo {

    interface Callback {
        void onLog(String line);

        void onError(String message);
    }

    private static final String UTTERANCE_ID = "phase0-apple";

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ready;

    void init(Context context, Callback callback) {
        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS) {
                main.post(() -> callback.onError("TTS 引擎初始化失敗，status=" + status));
                return;
            }

            // 全部丟回 main thread 再碰 tts：onInit 可能在建構子回傳前就被呼叫。
            main.post(() -> {
                int result = tts.setLanguage(Locale.US);
                switch (result) {
                    case TextToSpeech.LANG_MISSING_DATA:
                        callback.onError("en-US 語音資料未安裝，需要到系統設定下載");
                        return;
                    case TextToSpeech.LANG_NOT_SUPPORTED:
                        callback.onError("此引擎不支援 en-US");
                        return;
                    default:
                        break;
                }

                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        main.post(() -> callback.onLog("開始發音：" + utteranceId));
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        main.post(() -> callback.onLog("發音結束：" + utteranceId));
                    }

                    @Override
                    public void onError(String utteranceId) {
                        main.post(() -> callback.onError("發音失敗：" + utteranceId));
                    }
                });

                ready = true;
                callback.onLog("TTS 就緒，引擎 = " + tts.getDefaultEngine());
                describeVoice(callback);
            });
        });
    }

    /** 這段的重點是 networkRequired —— 它決定發音能不能離線。 */
    private void describeVoice(Callback callback) {
        Voice voice = tts.getVoice();
        if (voice == null) {
            callback.onLog("取不到目前的 voice");
            return;
        }
        callback.onLog(String.format(Locale.US,
                "voice=%s locale=%s networkRequired=%b quality=%d latency=%d",
                voice.getName(),
                voice.getLocale(),
                voice.isNetworkConnectionRequired(),
                voice.getQuality(),
                voice.getLatency()));
        if (voice.isNetworkConnectionRequired()) {
            callback.onLog("警告：目前 voice 需要網路，離線發音會失敗（需安裝離線語音包）");
        }
    }

    void speakApple(Callback callback) {
        if (!ready) {
            callback.onError("TTS 還沒就緒");
            return;
        }
        int result = tts.speak("apple", TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID);
        if (result == TextToSpeech.ERROR) {
            callback.onError("speak() 回傳 ERROR");
        } else {
            callback.onLog("speak(\"apple\") 已排入佇列");
        }
    }

    void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            ready = false;
        }
    }
}
