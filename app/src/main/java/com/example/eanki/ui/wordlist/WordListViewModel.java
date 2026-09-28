package com.example.eanki.ui.wordlist;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.example.eanki.data.ReviewRepository;
import com.example.eanki.data.WordRepository;
import com.example.eanki.data.user.Direction;
import com.example.eanki.data.user.ReviewStateDao;
import com.example.eanki.data.user.TagDao;
import com.example.eanki.data.user.WordDao;
import com.example.eanki.data.user.WordEntry;
import com.example.eanki.search.SearchPattern;
import com.example.eanki.ui.Event;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 單字本分頁的狀態。
 *
 * <p>SPEC 第 7 節第 2 點：摺疊會重建 Activity，狀態一律放 ViewModel。
 * 這裡會被摺疊摔掉的有兩樣：「剛刪掉哪一筆、可不可以復原」，
 * 以及<b>目前的搜尋字串與選到的標籤</b> —— 打了半個關鍵字、一展開內螢幕
 * 就整個清空並跳回完整清單，會是這個畫面最惱人的 bug。
 */
public class WordListViewModel extends AndroidViewModel {

    /** 目前的篩選條件。搜尋字串與標籤是同一組狀態，一起換才不會查兩次。 */
    public static final class Filter {

        static final Filter NONE = new Filter("", WordDao.ANY_TAG);

        public final String query;
        public final long tagId;

        Filter(String query, long tagId) {
            this.query = query;
            this.tagId = tagId;
        }

        /** 有沒有任何篩選在作用中，決定要顯示「找不到」還是「單字本是空的」。 */
        public boolean isActive() {
            return !SearchPattern.isBlank(query) || tagId != WordDao.ANY_TAG;
        }

        Filter withQuery(String next) {
            return new Filter(next, tagId);
        }

        Filter withTag(long next) {
            return new Filter(query, next);
        }
    }

    private final WordRepository repository;

    private final MutableLiveData<Filter> filter = new MutableLiveData<>(Filter.NONE);
    private final LiveData<List<WordEntry>> words;
    private final LiveData<List<TagDao.TagCount>> tags;
    private final LiveData<Integer> totalCount;
    private final LiveData<Map<Long, ReviewStateDao.RowMeta>> rowMeta;
    private final MutableLiveData<Event<WordEntry>> deleted = new MutableLiveData<>();

    /** 剛刪掉的那筆，等使用者決定要不要復原。 */
    @Nullable
    private WordEntry pendingUndo;

    public WordListViewModel(@NonNull Application application) {
        super(application);
        repository = WordRepository.get(application);

        // 條件一變就換一條新的 Room 查詢；過濾在 SQL 做，不在記憶體裡篩
        words = Transformations.switchMap(filter,
                f -> repository.observeFiltered(f.query, f.tagId));
        tags = repository.observeTagCounts();
        totalCount = repository.observeCount();
        rowMeta = Transformations.map(
                ReviewRepository.get(application).observeRowMeta(Direction.EN_TO_ZH),
                rows -> {
                    Map<Long, ReviewStateDao.RowMeta> byId = new HashMap<>();
                    for (ReviewStateDao.RowMeta r : rows) {
                        byId.put(r.wordId, r);
                    }
                    return byId;
                });
    }

    public LiveData<List<WordEntry>> getWords() {
        return words;
    }

    /** 每列的熟悉度與到期時間，key 是 word_id。 */
    public LiveData<Map<Long, ReviewStateDao.RowMeta>> getRowMeta() {
        return rowMeta;
    }

    public LiveData<List<TagDao.TagCount>> getTags() {
        return tags;
    }

    /** 單字總數（不受篩選影響），給「篩選出 N / 共 M」的分母。 */
    public LiveData<Integer> getTotalCount() {
        return totalCount;
    }

    public LiveData<Filter> getFilter() {
        return filter;
    }

    @NonNull
    public Filter currentFilter() {
        Filter f = filter.getValue();
        return f == null ? Filter.NONE : f;
    }

    /** 打字時呼叫。內容沒變就不重查，否則每次 setText 都會多跑一趟。 */
    public void setQuery(String next) {
        Filter now = currentFilter();
        if (now.query.equals(next)) {
            return;
        }
        filter.setValue(now.withQuery(next));
    }

    public void setTag(long tagId) {
        Filter now = currentFilter();
        if (now.tagId == tagId) {
            return;
        }
        filter.setValue(now.withTag(tagId));
    }

    public void clearFilter() {
        if (currentFilter().isActive()) {
            filter.setValue(Filter.NONE);
        }
    }

    public LiveData<Event<WordEntry>> getDeleted() {
        return deleted;
    }

    public void delete(WordEntry word) {
        pendingUndo = word;
        repository.delete(word);
        deleted.setValue(new Event<>(word));
    }

    public void undoDelete() {
        if (pendingUndo != null) {
            repository.insertBack(pendingUndo);
            pendingUndo = null;
        }
    }
}
