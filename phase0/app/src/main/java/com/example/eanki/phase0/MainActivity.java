package com.example.eanki.phase0;

import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.eanki.phase0.databinding.ActivityMainBinding;

/**
 * Phase 0 的唯一畫面。三個 demo 互不相干，結果同時寫進 Logcat（tag = PHASE0）
 * 與畫面上的 TextView，方便沒接電腦時也看得到。
 */
public class MainActivity extends AppCompatActivity {

    static final String TAG = "PHASE0";

    private ActivityMainBinding binding;
    private final OcrDemo ocrDemo = new OcrDemo();
    private final TtsDemo ttsDemo = new TtsDemo();
    private final DictDemo dictDemo = new DictDemo();

    private final StringBuilder transcript = new StringBuilder();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnOcr.setOnClickListener(v -> runOcr());
        binding.btnTts.setOnClickListener(v -> ttsDemo.speakApple(ttsCallback));
        binding.btnDict.setOnClickListener(v -> runDict());

        ttsDemo.init(this, ttsCallback);
        runOcr();
    }

    private void runDict() {
        log("--- Demo 3：ECDICT 查詢 ---");
        dictDemo.run(this, "apple", new DictDemo.Callback() {
            @Override
            public void onLog(String line) {
                log("[DICT] " + line);
            }

            @Override
            public void onError(String message) {
                error("[DICT] " + message);
            }
        });
    }

    private void runOcr() {
        log("--- Demo 1：ML Kit Text Recognition ---");
        ocrDemo.run(this, new OcrDemo.Callback() {
            @Override
            public void onLog(String line) {
                log(line);
            }

            @Override
            public void onFinished(int wordCount) {
                log("--- Demo 1 結束，共 " + wordCount + " 個單字 ---");
            }

            @Override
            public void onError(String message) {
                error(message);
            }
        });
    }

    private final TtsDemo.Callback ttsCallback = new TtsDemo.Callback() {
        @Override
        public void onLog(String line) {
            log("[TTS] " + line);
        }

        @Override
        public void onError(String message) {
            error("[TTS] " + message);
        }
    };

    private void log(String line) {
        Log.i(TAG, line);
        append(line);
    }

    private void error(String line) {
        Log.e(TAG, line);
        append("ERROR: " + line);
    }

    private void append(String line) {
        transcript.append(line).append('\n');
        // 非同步回呼可能在 onDestroy 之後才到。
        if (binding != null) {
            binding.tvStatus.setText(transcript);
        }
    }

    @Override
    protected void onDestroy() {
        ttsDemo.shutdown();
        ocrDemo.shutdown();
        dictDemo.shutdown();
        binding = null;
        super.onDestroy();
    }
}
