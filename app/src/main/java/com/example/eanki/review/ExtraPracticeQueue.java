package com.example.eanki.review;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 加強複習要練哪些字、練幾個。SPEC 第 6.6 節。
 *
 * <p>純 Java，不依賴 Android 或 Room。
 *
 * <p><b>這裡只決定「練什麼」，不決定「之後什麼時候再看到」。</b>
 * 加強複習完全不進 {@link Sm2Scheduler}，評分的落點在
 * {@link com.example.eanki.data.user.ReviewStateMapper#applyExtraPractice}。
 */
public final class ExtraPracticeQueue {

    /**
     * 「只挑 20」的 20。
     *
     * <p>SPEC 第 6.6 節：99 張卡一次刷完不實際，中途放棄比沒開始更挫折。
     */
    public static final int SAMPLE_SIZE = 20;

    /** 不設上限。 */
    public static final int NO_LIMIT = 0;

    /**
     * 「不太熟的字」的加強佇列：忘了在前、有點難在後。
     *
     * <p><b>順序不是隨便的。</b>挑 20 個時要挑到最該練的 20 個，
     * 所以先把「忘了」整批放進去，額度還有剩才輪到「有點難」。
     * 隨機抽樣看起來公平，但會讓使用者練 20 個字卻半數是已經算記得住的。
     */
    @NonNull
    public static List<Long> struggling(@NonNull FamiliarityStats stats, int limit) {
        List<Long> ordered = new ArrayList<>(stats.wordIds(Grade.FORGOT));
        ordered.addAll(stats.wordIds(Grade.HARD));
        return take(ordered, limit);
    }

    /**
     * 依給定順序取前 {@code limit} 個。熟悉度篩選畫面手動勾選的結果走這條，
     * 順序就是使用者看到的順序。
     */
    @NonNull
    public static List<Long> take(@NonNull List<Long> ordered, int limit) {
        if (limit <= NO_LIMIT || ordered.size() <= limit) {
            return new ArrayList<>(ordered);
        }
        return new ArrayList<>(ordered.subList(0, limit));
    }

    private ExtraPracticeQueue() {
    }
}
