package com.example.eanki.review;

import java.util.Comparator;

/**
 * 熟悉度篩選畫面的排序。SPEC 第 5 節「可切換級別、排序」。
 *
 * <p>純 Java，不依賴 Android 或 Room。排序的對象用 {@link Sortable} 抽象出來，
 * Room 的查詢列實作它就能直接排，測試則用假物件。
 */
public enum SortMode {

    /**
     * 最久沒複習的排前面。
     *
     * <p>這是預設值，而且刻意跟 SPEC 第 6.4 節「超過上限時最久沒複習的優先」
     * 同一個方向 —— 使用者按「只挑 20」時拿到的會是最該練的 20 個，
     * 而不是最近剛練過的那 20 個。
     */
    STALE_FIRST,

    /** 最近加入的排前面，跟單字本的預設順序一致。 */
    RECENTLY_ADDED,

    /** 字母順序，大小寫不計。要在一堆字裡找特定那個字時用。 */
    ALPHABETICAL;

    /** 可被排序的一列。 */
    public interface Sortable {
        String sortWord();

        /** 上次複習時間 epoch millis，從沒複習過為 0。 */
        long sortLastReviewed();

        /** 加入單字本的時間 epoch millis。 */
        long sortCreatedAt();
    }

    public <T extends Sortable> Comparator<T> comparator() {
        switch (this) {
            case RECENTLY_ADDED:
                return (a, b) -> Long.compare(b.sortCreatedAt(), a.sortCreatedAt());
            case ALPHABETICAL:
                return (a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                        a.sortWord(), b.sortWord());
            case STALE_FIRST:
            default:
                // 沒複習過的 lastReviewed 是 0，自然排到最前面 —— 這是對的，
                // 它們確實是「最久沒複習」的一群
                return (a, b) -> {
                    int byTime = Long.compare(a.sortLastReviewed(), b.sortLastReviewed());
                    // 同一批匯入的字時間會完全相同，再用字母排，順序才穩定
                    return byTime != 0 ? byTime
                            : String.CASE_INSENSITIVE_ORDER.compare(a.sortWord(), b.sortWord());
                };
        }
    }
}
