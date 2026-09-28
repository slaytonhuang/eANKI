package com.example.eanki.tts;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Log;

import java.util.Locale;

/**
 * 發音。SPEC 第 2 節：系統內建 TextToSpeech，可離線。
 *
 * <p>Application 層級的單例：TTS 引擎的連線成本不低，
 * 而且摺疊裝置每次展開/闔上都會重建 Activity（SPEC 第 7 節），
 * 綁在 Activity 上會一直重連。
 *
 * <p>Phase 0 在 Galaxy Z Fold 5 實測 {@code networkRequired=false}，發音可離線。
 */
public final class TtsManager {

    private static final String TAG = "TtsManager";

    private static volatile TtsManager instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private volatile boolean ready;

    /**
     * 目前語速。記在這裡而不是每次都去讀設定：
     * {@code speak()} 在複習時會被頻繁呼叫，不該每次都碰 SharedPreferences。
     * 設定頁改完會直接呼叫 {@link #setSpeechRate}。
     */
    private volatile float speechRate = 1.0f;

    private TtsManager() {
    }

    public static TtsManager get() {
        if (instance == null) {
            synchronized (TtsManager.class) {
                if (instance == null) {
                    instance = new TtsManager();
                }
            }
        }
        return instance;
    }

    public void init(Context context) {
        if (tts != null) {
            return;
        }
        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            // onInit 可能在建構子回傳前就被呼叫，統一丟回 main thread 再碰 tts
            main.post(() -> {
                if (status != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "TTS 初始化失敗 status=" + status);
                    return;
                }
                int result = tts.setLanguage(Locale.US);
                if (result == TextToSpeech.LANG_MISSING_DATA
                        || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "en-US 不可用 result=" + result);
                    return;
                }
                tts.setSpeechRate(speechRate);
                ready = true;
                logVoice();
            });
        });
    }

    /** 離線性檢查：若 networkRequired 為 true，發音會卡網路。 */
    private void logVoice() {
        Voice voice = tts.getVoice();
        if (voice != null) {
            Log.i(TAG, "voice=" + voice.getName()
                    + " networkRequired=" + voice.isNetworkConnectionRequired());
        }
    }

    public boolean isReady() {
        return ready;
    }

    /**
     * 設定語速。引擎還沒就緒也會記住，初始化完成時再套用 ——
     * 否則 App 冷啟動後第一次發音會用預設速度，跟設定不符。
     */
    public void setSpeechRate(float rate) {
        speechRate = rate;
        if (ready && tts != null) {
            tts.setSpeechRate(rate);
        }
    }

    public void speak(String word) {
        if (!ready || word == null || word.isEmpty()) {
            return;
        }
        Log.d(TAG, "speak \"" + word + "\" rate=" + speechRate);
        tts.speak(word, TextToSpeech.QUEUE_FLUSH, null, "eanki-" + word);
    }

    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            ready = false;
        }
    }
}
