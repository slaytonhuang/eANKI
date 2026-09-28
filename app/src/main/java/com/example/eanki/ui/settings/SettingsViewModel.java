package com.example.eanki.ui.settings;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.eanki.data.BackupRepository;
import com.example.eanki.data.ReviewRepository;
import com.example.eanki.data.WordRepository;
import com.example.eanki.data.dict.DictionaryRepository;
import com.example.eanki.data.settings.AppSettings;
import com.example.eanki.data.user.Direction;

/**
 * 設定頁需要跑背景的那幾件事：讀字典狀態、重建字典、算「今天會有幾張卡」。
 *
 * <p>設定值本身不放這裡 —— 它們在 SharedPreferences，讀寫都是同步且極快的，
 * 再包一層 LiveData 只會讓「誰是真相來源」變模糊。
 */
public class SettingsViewModel extends AndroidViewModel {

    public interface OnDone {
        void onDone(boolean success);
    }

    public interface OnExport {
        void onDone(@androidx.annotation.Nullable BackupRepository.ExportResult result,
                    @androidx.annotation.Nullable String error);
    }

    public interface OnRestore {
        void onDone(BackupRepository.RestoreResult result);
    }

    private final WordRepository words;
    private final ReviewRepository reviews;
    private final BackupRepository backups;
    private final AppSettings settings;
    private final android.content.ContentResolver resolver;

    private final MutableLiveData<DictionaryRepository.Status> dictStatus =
            new MutableLiveData<>();
    private final MutableLiveData<Integer> todayPreview = new MutableLiveData<>();

    public SettingsViewModel(@NonNull Application application) {
        super(application);
        words = WordRepository.get(application);
        reviews = ReviewRepository.get(application);
        backups = BackupRepository.get(application);
        settings = AppSettings.get(application);
        resolver = application.getContentResolver();
        refreshDictStatus();
        refreshTodayPreview();
    }

    /** 目前單字本有幾個字。還原前的確認對話框要講清楚會被取代掉多少。 */
    public LiveData<Integer> getTotalWords() {
        return words.observeCount();
    }

    public LiveData<DictionaryRepository.Status> getDictStatus() {
        return dictStatus;
    }

    /** 以目前的上限，今天實際會拿到幾張卡。 */
    public LiveData<Integer> getTodayPreview() {
        return todayPreview;
    }

    /**
     * 重算預覽。
     *
     * <p>走的是 {@link ReviewRepository#previewTodayCount}，<b>不是 startSession</b>——
     * 後者會放行新字。使用者只是在設定頁拖個滑桿，不該因此消耗掉今天的新字額度。
     */
    public void refreshTodayPreview() {
        reviews.io().execute(() -> todayPreview.postValue(
                reviews.previewTodayCount(Direction.EN_TO_ZH, settings.limits(),
                        System.currentTimeMillis())));
    }

    private void refreshDictStatus() {
        words.io().execute(() -> dictStatus.postValue(words.dictionaryStatus()));
    }

    // ---- 備份 ----

    /**
     * 匯出到使用者選的位置。
     *
     * <p>成功才記錄備份時間 —— 寫到一半失敗卻顯示「上次備份：剛剛」，
     * 會讓使用者以為手上有一份其實不存在的備份。
     */
    public void export(android.net.Uri target, String appVersion, OnExport callback) {
        backups.io().execute(() -> {
            String error = null;
            BackupRepository.ExportResult result = null;
            try (java.io.OutputStream out = resolver.openOutputStream(target, "wt")) {
                if (out == null) {
                    throw new java.io.IOException("開不了檔案");
                }
                result = backups.export(out, appVersion);
                settings.setLastBackupAt(System.currentTimeMillis());
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            postToMain(result, error, callback);
        });
    }

    private void postToMain(@androidx.annotation.Nullable BackupRepository.ExportResult result,
                            @androidx.annotation.Nullable String error, OnExport callback) {
        final BackupRepository.ExportResult r = result;
        final String e = error;
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> callback.onDone(r, e));
    }

    /** 只解析不寫入。畫面要先讓使用者看清楚會被取代成什麼。 */
    public void previewRestore(android.net.Uri source, OnRestore callback) {
        backups.io().execute(() -> {
            BackupRepository.RestoreResult result;
            try (java.io.InputStream in = resolver.openInputStream(source)) {
                result = in == null
                        ? failed("開不了檔案")
                        : backups.preview(in);
            } catch (Exception e) {
                result = failed(e.getMessage() == null ? e.toString() : e.getMessage());
            }
            postRestore(result, callback);
        });
    }

    public void restore(android.net.Uri source, OnRestore callback) {
        backups.io().execute(() -> {
            BackupRepository.RestoreResult result;
            try (java.io.InputStream in = resolver.openInputStream(source)) {
                result = in == null
                        ? failed("開不了檔案")
                        : backups.restore(in);
            } catch (Exception e) {
                result = failed(e.getMessage() == null ? e.toString() : e.getMessage());
            }
            refreshTodayPreview();
            postRestore(result, callback);
        });
    }

    private BackupRepository.RestoreResult failed(String message) {
        return BackupRepository.failedResult(message);
    }

    private void postRestore(BackupRepository.RestoreResult result, OnRestore callback) {
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> callback.onDone(result));
    }

    public long lastBackupAt() {
        return settings.lastBackupAt();
    }

    public void rebuildDictionary(OnDone callback) {
        words.io().execute(() -> {
            boolean ok = words.rebuildDictionary();
            dictStatus.postValue(words.dictionaryStatus());
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> callback.onDone(ok));
        });
    }
}
