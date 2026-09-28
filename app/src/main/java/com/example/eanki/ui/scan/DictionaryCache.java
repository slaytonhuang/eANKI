package com.example.eanki.ui.scan;

import androidx.annotation.Nullable;

import com.example.eanki.data.dict.DictEntry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 掃描畫面的字典查詢快取。
 *
 * <p>即時辨識每秒約 8 幀、每幀十幾個字，等於每秒要查上百次。
 * 但<b>連續的幀看到的是同一批字</b>，所以只有第一次需要真的打資料庫，
 * 之後全部是記憶體命中。
 *
 * <p>查詢本身丟到背景執行緒：{@code MlKitAnalyzer} 的回呼跑在主執行緒上，
 * 在那裡碰資料庫會直接卡住畫面。查完之前該字的狀態是
 * {@link LookupState#PENDING}，下一幀就會拿到結果 —— 使用者看到的是
 * 框先出現、顏色慢一兩幀補上，比整個畫面卡住好得多。
 */
public final class DictionaryCache {

    /** 實際去查字典的動作。抽成介面是為了讓測試不必碰 Android 或 SQLite。 */
    public interface Loader {
        @Nullable
        DictEntry lookUp(String normalizedWord);
    }

    /** 快取一筆。{@code entry} 為 null 表示查過但字典沒有。 */
    public static final class Result {
        public final LookupState state;
        @Nullable
        public final DictEntry entry;

        Result(LookupState state, @Nullable DictEntry entry) {
            this.state = state;
            this.entry = entry;
        }
    }

    private static final Result PENDING = new Result(LookupState.PENDING, null);

    /**
     * 上限。掃一整頁課本大約幾百個不重複的字，設 2000 綽綽有餘；
     * 設上限只是避免長時間掃描時無限成長。
     */
    private static final int MAX_ENTRIES = 2000;

    private final Map<String, Result> cache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> inFlight = new ConcurrentHashMap<>();
    private final Loader loader;
    private final Executor executor;

    public DictionaryCache(Loader loader, Executor executor) {
        this.loader = loader;
        this.executor = executor;
    }

    /**
     * 查一個已正規化的字。<b>不會阻塞。</b>
     * 還沒查完就回傳 PENDING，並在背景排一次查詢。
     */
    public Result get(String normalizedWord) {
        if (normalizedWord == null || normalizedWord.isEmpty()) {
            return PENDING;
        }
        Result cached = cache.get(normalizedWord);
        if (cached != null) {
            return cached;
        }
        // 同一個字在查完之前可能被很多幀要到，只排一次
        if (inFlight.putIfAbsent(normalizedWord, Boolean.TRUE) == null) {
            executor.execute(() -> {
                try {
                    DictEntry entry = loader.lookUp(normalizedWord);
                    if (cache.size() < MAX_ENTRIES) {
                        cache.put(normalizedWord, new Result(
                                entry != null ? LookupState.FOUND : LookupState.NOT_FOUND,
                                entry));
                    }
                } finally {
                    inFlight.remove(normalizedWord);
                }
            });
        }
        return PENDING;
    }

    /**
     * <b>同步</b>把這批字查完填進快取。必須在背景執行緒呼叫。
     *
     * <p>凍結模式要用這個而不是 {@link #get}：凍結後的框<b>只算一次</b>，
     * 不像即時預覽每幀重算。用非同步的 get 會讓大部分字永遠停在
     * {@link LookupState#PENDING}，畫面上是中性框、而且點不下去 ——
     * 從相簿選一張沒預覽過的圖時特別明顯，因為快取裡一個字都沒有。
     */
    public void warm(java.util.Collection<String> words) {
        for (String word : words) {
            if (word == null || word.isEmpty() || cache.containsKey(word)) {
                continue;
            }
            DictEntry entry = loader.lookUp(word);
            if (cache.size() < MAX_ENTRIES) {
                cache.put(word, new Result(
                        entry != null ? LookupState.FOUND : LookupState.NOT_FOUND, entry));
            }
        }
    }

    /** 已經查完並快取的字數，給測試與除錯用。 */
    public int size() {
        return cache.size();
    }

    public void clear() {
        cache.clear();
        inFlight.clear();
    }
}
