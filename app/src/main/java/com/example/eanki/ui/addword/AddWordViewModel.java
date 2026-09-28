package com.example.eanki.ui.addword;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.eanki.data.WordRepository;
import com.example.eanki.data.dict.DictEntry;
import com.example.eanki.data.dict.WordNormalizer;
import com.example.eanki.data.user.Source;
import com.example.eanki.ui.Event;

/**
 * 手動輸入的流程：輸入 → 查字典 → 帶出音標與中文 → 存進 Room。
 *
 * <p>查字典與寫入都在背景執行緒（SPEC 第 2 節：Java 沒有 coroutine，
 * 一律丟到 executor）。查詢結果與輸入的字都放 ViewModel，
 * 摺疊重建 Activity 時不會掉（SPEC 第 7 節）。
 */
public class AddWordViewModel extends AndroidViewModel {

    /** 查字典的結果。 */
    public static final class Lookup {
        /** 使用者輸入、已正規化的字。 */
        public final String word;
        /** 查不到時為 null —— SPEC 第 6.1 節要求以磚紅標示。 */
        @Nullable
        public final DictEntry entry;

        Lookup(String word, @Nullable DictEntry entry) {
            this.word = word;
            this.entry = entry;
        }

        public boolean found() {
            return entry != null;
        }
    }

    private final WordRepository repository;

    private final MutableLiveData<Boolean> searching = new MutableLiveData<>(false);
    private final MutableLiveData<Lookup> lookup = new MutableLiveData<>();
    private final MutableLiveData<Event<WordRepository.SaveResult>> saved = new MutableLiveData<>();

    public AddWordViewModel(@NonNull Application application) {
        super(application);
        repository = WordRepository.get(application);
    }

    public LiveData<Boolean> isSearching() {
        return searching;
    }

    public LiveData<Lookup> getLookup() {
        return lookup;
    }

    public LiveData<Event<WordRepository.SaveResult>> getSaved() {
        return saved;
    }

    /** 回傳 false 表示輸入是空的，UI 要提示而不是發查詢。 */
    public boolean lookUp(String raw) {
        String word = WordNormalizer.normalize(raw);
        if (word.isEmpty()) {
            return false;
        }

        searching.setValue(true);
        repository.io().execute(() -> {
            DictEntry entry = repository.lookUp(word);
            lookup.postValue(new Lookup(word, entry));
            searching.postValue(false);
        });
        return true;
    }

    public void clear() {
        lookup.setValue(null);
    }

    /**
     * 存進使用者庫。查不到的字也允許存（SPEC 第 6.1 節：
     * 查不到要讓使用者確認或修正，而不是擋住），音標與釋義留空。
     */
    public void save() {
        Lookup current = lookup.getValue();
        if (current == null) {
            return;
        }
        repository.io().execute(() -> {
            WordRepository.SaveResult result =
                    repository.save(current.word, current.entry, Source.MANUAL);
            saved.postValue(new Event<>(result));
        });
    }
}
