package com.example.eanki.ui.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.example.eanki.data.dict.DictEntry;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 掃描時的字典查詢快取。
 *
 * <p>這支類別存在的理由是效能：每秒 8 幀 × 十幾個字 = 上百次查詢，
 * 而連續的幀看到的是同一批字。測試的重點就是
 * <b>同一個字只會真的去查一次</b>。
 */
public class DictionaryCacheTest {

    /** 立刻在同一條執行緒上跑，讓測試不必處理非同步。 */
    private static final Executor DIRECT = Runnable::run;

    /** 把排進來的工作收著，手動決定什麼時候跑，用來模擬「還在查」。 */
    private static final class ManualExecutor implements Executor {
        private final List<Runnable> queued = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            queued.add(command);
        }

        void runAll() {
            List<Runnable> copy = new ArrayList<>(queued);
            queued.clear();
            for (Runnable r : copy) {
                r.run();
            }
        }

        int pending() {
            return queued.size();
        }
    }

    private static DictEntry entry(String word) {
        return new DictEntry(word, "'test", "n. 測試", "a test", "n:100", "cet4", 100);
    }

    @Test
    public void 查得到的字狀態是FOUND並帶回釋義() {
        DictionaryCache cache = new DictionaryCache(w -> entry(w), DIRECT);

        // 第一次是 PENDING（工作剛排完），第二次才拿得到結果
        assertEquals(LookupState.PENDING, cache.get("apple").state);

        DictionaryCache.Result result = cache.get("apple");
        assertEquals(LookupState.FOUND, result.state);
        assertNotNull(result.entry);
        assertEquals("apple", result.entry.word);
    }

    @Test
    public void 查不到的字狀態是NOT_FOUND() {
        DictionaryCache cache = new DictionaryCache(w -> null, DIRECT);

        cache.get("zzzz");
        DictionaryCache.Result result = cache.get("zzzz");

        assertEquals(LookupState.NOT_FOUND, result.state);
        assertNull(result.entry);
    }

    /** 這是整個類別存在的理由。 */
    @Test
    public void 同一個字只會真的查一次() {
        AtomicInteger calls = new AtomicInteger();
        DictionaryCache cache = new DictionaryCache(w -> {
            calls.incrementAndGet();
            return entry(w);
        }, DIRECT);

        // 模擬 100 幀都看到同一個字
        for (int i = 0; i < 100; i++) {
            cache.get("dictionary");
        }

        assertEquals("100 幀只該打一次資料庫", 1, calls.get());
    }

    /** 查不到的字也要快取，否則每一幀都會重查一次，比查得到的還慢。 */
    @Test
    public void 查不到的結果也會被快取() {
        AtomicInteger calls = new AtomicInteger();
        DictionaryCache cache = new DictionaryCache(w -> {
            calls.incrementAndGet();
            return null;
        }, DIRECT);

        for (int i = 0; i < 50; i++) {
            cache.get("qqqq");
        }
        assertEquals(1, calls.get());
    }

    /** 查詢還沒回來之前，同一個字被很多幀要到也只該排一次工作。 */
    @Test
    public void 查詢還沒回來時不會重複排工作() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger calls = new AtomicInteger();
        DictionaryCache cache = new DictionaryCache(w -> {
            calls.incrementAndGet();
            return entry(w);
        }, executor);

        for (int i = 0; i < 20; i++) {
            assertEquals(LookupState.PENDING, cache.get("pending").state);
        }
        assertEquals("只排了一次", 1, executor.pending());

        executor.runAll();
        assertEquals(1, calls.get());
        assertEquals(LookupState.FOUND, cache.get("pending").state);
    }

    @Test
    public void 不同的字分別快取() {
        AtomicInteger calls = new AtomicInteger();
        DictionaryCache cache = new DictionaryCache(w -> {
            calls.incrementAndGet();
            return "apple".equals(w) ? entry(w) : null;
        }, DIRECT);

        cache.get("apple");
        cache.get("banana");
        cache.get("apple");
        cache.get("banana");

        assertEquals(2, calls.get());
        assertEquals(LookupState.FOUND, cache.get("apple").state);
        assertEquals(LookupState.NOT_FOUND, cache.get("banana").state);
        assertEquals(2, cache.size());
    }

    @Test
    public void 空字串不查() {
        AtomicInteger calls = new AtomicInteger();
        DictionaryCache cache = new DictionaryCache(w -> {
            calls.incrementAndGet();
            return null;
        }, DIRECT);

        assertEquals(LookupState.PENDING, cache.get("").state);
        assertEquals(LookupState.PENDING, cache.get(null).state);
        assertEquals(0, calls.get());
    }

    @Test
    public void 清空之後會重查() {
        AtomicInteger calls = new AtomicInteger();
        DictionaryCache cache = new DictionaryCache(w -> {
            calls.incrementAndGet();
            return entry(w);
        }, DIRECT);

        cache.get("word");
        cache.get("word");
        assertEquals(1, calls.get());

        cache.clear();
        assertEquals(0, cache.size());

        cache.get("word");
        assertEquals(2, calls.get());
    }
}
