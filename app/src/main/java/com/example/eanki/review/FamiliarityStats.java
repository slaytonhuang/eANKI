package com.example.eanki.review;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 熟悉度分布。SPEC 第 5 節「熟悉度」畫面的四級堆疊條與四列數量。
 *
 * <p>純 Java，不依賴 Android 或 Room。
 *
 * <h2>「尚未開始」不算進四級</h2>
 *
 * 四級（忘了／有點難／記得／太簡單）<b>是由評分定義的</b>，
 * 一個從沒複習過的字沒有評分，硬塞進任何一級都是錯的 ——
 * 放「忘了」等於說使用者忘記了他根本沒見過的字。
 *
 * <p>而且新字每天只放行 10 個（SPEC 第 6.4 節），未複習的池子會維持好幾個月的
 * 絕對多數；放進堆疊條會讓它永遠被同一個顏色佔滿，四級的比例完全讀不出來，
 * 而那條的用途正是「我已經在學的字掌握得如何」。
 *
 * <p>所以獨立成一個數字，顏色也刻意用中性灰而非 SPEC 第 4 節那三個語意色。
 */
public final class FamiliarityStats {

    /** 一個字的最近三次評分。null 代表該次還沒有紀錄。 */
    public static final class GradeTriple {
        public final long wordId;
        @Nullable
        public final Integer grade1;
        @Nullable
        public final Integer grade2;
        @Nullable
        public final Integer grade3;

        public GradeTriple(long wordId, @Nullable Integer grade1,
                           @Nullable Integer grade2, @Nullable Integer grade3) {
            this.wordId = wordId;
            this.grade1 = grade1;
            this.grade2 = grade2;
            this.grade3 = grade3;
        }
    }

    private final Map<Grade, Integer> counts = new EnumMap<>(Grade.class);
    private final Map<Grade, List<Long>> wordsByLevel = new EnumMap<>(Grade.class);
    private final List<Long> notStartedIds = new ArrayList<>();
    private final int totalWords;

    private FamiliarityStats(int totalWords) {
        this.totalWords = totalWords;
        for (Grade g : Grade.values()) {
            counts.put(g, 0);
            wordsByLevel.put(g, new ArrayList<>());
        }
    }

    /**
     * @param triples    所有<b>已放行</b>的字的最近三次評分
     * @param totalWords 單字本裡的字數總和
     */
    public static FamiliarityStats of(@NonNull List<GradeTriple> triples, int totalWords) {
        FamiliarityStats stats = new FamiliarityStats(totalWords);
        for (GradeTriple t : triples) {
            Grade level = FamiliarityRule.ofCodes(t.grade1, t.grade2, t.grade3);
            if (level == null) {
                // 已放行但還沒評過分，跟「完全沒放行」一樣算尚未開始 ——
                // 對使用者而言兩者都是「還沒評過分」，今日頁已經在管今天要學什麼了
                stats.notStartedIds.add(t.wordId);
                continue;
            }
            stats.counts.put(level, stats.counts.get(level) + 1);
            stats.wordsByLevel.get(level).add(t.wordId);
        }
        return stats;
    }

    public int count(Grade level) {
        Integer n = counts.get(level);
        return n == null ? 0 : n;
    }

    /** 某一級底下的字，給熟悉度篩選畫面用。 */
    @NonNull
    public List<Long> wordIds(Grade level) {
        List<Long> ids = wordsByLevel.get(level);
        return ids == null ? new ArrayList<>() : ids;
    }

    /** 已經有評分的字數，也就是堆疊條的總長度。 */
    public int gradedCount() {
        int sum = 0;
        for (Grade g : Grade.values()) {
            sum += count(g);
        }
        return sum;
    }

    /**
     * 尚未開始的字數 ＝ 單字本總數 − 已評分的字數。
     *
     * <p>用減法而不是數 {@code notStartedIds}：還沒放行的字根本沒有
     * {@code review_state} 列，不會出現在 {@code triples} 裡。
     */
    public int notStartedCount() {
        return Math.max(0, totalWords - gradedCount());
    }

    public int totalWords() {
        return totalWords;
    }

    /**
     * 「不太熟的字」＝ 忘了 + 有點難。SPEC 第 5 節的磚紅卡片與第 6.6 節的加強複習佇列。
     *
     * <p><b>不包含尚未開始的字。</b>加強複習是為了鞏固你掙扎過的字，
     * 不是拿來學新字的 —— 把沒見過的字拖進來會直接違反第 6.4 節的每日份量控制。
     */
    public int strugglingCount() {
        return count(Grade.FORGOT) + count(Grade.HARD);
    }

    @NonNull
    public List<Long> strugglingWordIds() {
        List<Long> ids = new ArrayList<>(wordIds(Grade.FORGOT));
        ids.addAll(wordIds(Grade.HARD));
        return ids;
    }

    /** 某一級在堆疊條裡佔的比例（0–1）。分母是已評分的字，不含尚未開始。 */
    public float ratio(Grade level) {
        int graded = gradedCount();
        return graded == 0 ? 0f : count(level) / (float) graded;
    }
}
