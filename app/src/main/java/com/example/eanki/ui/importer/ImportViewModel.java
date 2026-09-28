package com.example.eanki.ui.importer;

import android.app.Application;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.eanki.data.ImportRepository;
import com.example.eanki.data.importer.CsvParser;
import com.example.eanki.data.importer.ImportRow;

import java.io.InputStream;
import java.util.List;

/**
 * 匯入的狀態。放 ViewModel 是因為 1464 筆要跑幾秒，
 * 中途轉螢幕或摺疊會重建 Activity（SPEC 第 7 節），匯入不能因此中斷或重跑。
 */
public class ImportViewModel extends AndroidViewModel {

    private static final String TAG = "ImportVM";

    public enum Stage { IDLE, READING, IMPORTING, DONE, FAILED }

    private final ImportRepository repository;

    private final MutableLiveData<Stage> stage = new MutableLiveData<>(Stage.IDLE);
    private final MutableLiveData<int[]> progress = new MutableLiveData<>(new int[]{0, 0});
    private final MutableLiveData<ImportRepository.Result> result = new MutableLiveData<>();
    private final MutableLiveData<String> error = new MutableLiveData<>();
    private final MutableLiveData<Integer> tagTotal = new MutableLiveData<>(0);

    private boolean running;

    public ImportViewModel(@NonNull Application application) {
        super(application);
        repository = ImportRepository.get(application);
    }

    public LiveData<Stage> getStage() {
        return stage;
    }

    public LiveData<int[]> getProgress() {
        return progress;
    }

    public LiveData<ImportRepository.Result> getResult() {
        return result;
    }

    public LiveData<String> getError() {
        return error;
    }

    public LiveData<Integer> getTagTotal() {
        return tagTotal;
    }

    /** 重複呼叫是安全的：跑到一半時不會再起一輪。 */
    public void start(@NonNull Uri uri, @Nullable String tagName) {
        if (running) {
            return;
        }
        running = true;
        stage.setValue(Stage.READING);

        repository.io().execute(() -> {
            try {
                List<ImportRow> rows;
                try (InputStream in = getApplication().getContentResolver()
                        .openInputStream(uri)) {
                    if (in == null) {
                        throw new IllegalStateException("開不了這個檔案");
                    }
                    rows = ImportRow.fromRows(CsvParser.parse(in));
                }

                if (rows.isEmpty()) {
                    running = false;
                    error.postValue(getApplication().getString(
                            com.example.eanki.R.string.import_empty));
                    stage.postValue(Stage.FAILED);
                    return;
                }

                stage.postValue(Stage.IMPORTING);
                progress.postValue(new int[]{0, rows.size()});

                ImportRepository.Result r = repository.importRows(rows, tagName,
                        (done, total) -> progress.postValue(new int[]{done, total}));

                if (r.tagName != null && !r.tagName.trim().isEmpty()) {
                    tagTotal.postValue(repository.countByTag(r.tagName.trim()));
                }
                result.postValue(r);
                stage.postValue(Stage.DONE);
            } catch (Exception e) {
                Log.e(TAG, "匯入失敗", e);
                error.postValue(String.valueOf(e.getMessage()));
                stage.postValue(Stage.FAILED);
            } finally {
                running = false;
            }
        });
    }
}
