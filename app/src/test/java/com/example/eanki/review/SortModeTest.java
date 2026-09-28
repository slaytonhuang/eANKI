package com.example.eanki.review;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 熟悉度篩選的排序。SPEC 第 5 節。 */
public class SortModeTest {

    private static final class Row implements SortMode.Sortable {
        final String word;
        final long lastReviewed;
        final long createdAt;

        Row(String word, long lastReviewed, long createdAt) {
            this.word = word;
            this.lastReviewed = lastReviewed;
            this.createdAt = createdAt;
        }

        @Override
        public String sortWord() {
            return word;
        }

        @Override
        public long sortLastReviewed() {
            return lastReviewed;
        }

        @Override
        public long sortCreatedAt() {
            return createdAt;
        }
    }

    private static List<String> sorted(SortMode mode, Row... rows) {
        List<Row> list = new ArrayList<>();
        Collections.addAll(list, rows);
        Collections.sort(list, mode.<Row>comparator());
        List<String> words = new ArrayList<>();
        for (Row r : list) {
            words.add(r.word);
        }
        return words;
    }

    @Test
    public void 最久沒複習的排最前面() {
        assertEquals(List.of("stale", "mid", "fresh"), sorted(SortMode.STALE_FIRST,
                new Row("fresh", 3000L, 0L),
                new Row("stale", 1000L, 0L),
                new Row("mid", 2000L, 0L)));
    }

    /**
     * 1,464 筆是同一次匯入進來的，last_reviewed 完全相同。
     * 沒有次要排序鍵的話，每次重新查詢的順序都可能不一樣，
     * 使用者勾到一半列表就自己跳掉了。
     */
    @Test
    public void 上次複習時間相同時用字母決定順序才穩定() {
        assertEquals(List.of("alpha", "beta", "gamma"), sorted(SortMode.STALE_FIRST,
                new Row("gamma", 1000L, 0L),
                new Row("alpha", 1000L, 0L),
                new Row("beta", 1000L, 0L)));
    }

    /** 沒複習過的 lastReviewed 是 0，排最前面 —— 它確實最久沒複習。 */
    @Test
    public void 從沒複習過的排在有複習過的前面() {
        assertEquals(List.of("never", "once"), sorted(SortMode.STALE_FIRST,
                new Row("once", 5000L, 0L),
                new Row("never", 0L, 0L)));
    }

    @Test
    public void 最近加入的排最前面() {
        assertEquals(List.of("newest", "older", "oldest"), sorted(SortMode.RECENTLY_ADDED,
                new Row("oldest", 0L, 100L),
                new Row("newest", 0L, 300L),
                new Row("older", 0L, 200L)));
    }

    @Test
    public void 字母排序不分大小寫() {
        assertEquals(List.of("apple", "Banana", "cherry"), sorted(SortMode.ALPHABETICAL,
                new Row("cherry", 0L, 0L),
                new Row("apple", 0L, 0L),
                new Row("Banana", 0L, 0L)));
    }
}
