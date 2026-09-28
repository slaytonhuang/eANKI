package com.example.eanki;

import android.app.Application;

import com.example.eanki.data.WordRepository;
import com.example.eanki.data.settings.AppSettings;
import com.example.eanki.reminder.ReminderScheduler;
import com.example.eanki.tts.TtsManager;

public class EankiApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // TTS 引擎連線要一點時間，提早開始，使用者按到發音鍵時通常已經就緒。
        // 語速要在 init 之前設好，引擎就緒的那一刻就會套用。
        AppSettings settings = AppSettings.get(this);
        TtsManager.get().setSpeechRate(settings.speechRate());
        TtsManager.get().init(this);

        // 字典首次啟動要從 assets 複製 10 MB（Phase 0 實測 60 ms）。
        // 丟到背景先做掉，使用者第一次查字時就不用等。
        WordRepository repo = WordRepository.get(this);
        repo.io().execute(() -> {
            repo.isDictionaryReady();
            // 掃描來源圖的孤兒清理。刪單字時不立刻刪圖（刪除有「復原」），
            // 所以在這裡補收，順便收拾當機留下的殘檔。
            repo.sweepOrphanImages();
        });

        // 每日提醒。Worker 跑完會自己排下一次，這裡只負責「App 還沒排過就排一次」。
        ReminderScheduler.schedule(this);
    }
}
