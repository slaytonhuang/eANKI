package com.example.eanki.review;

/**
 * 熟悉度判定。SPEC 第 6.5 節：<b>取最近三次評分的眾數，平手時取較差的那個。</b>
 *
 * <p>純 Java，不依賴 Android 或 Room。
 *
 * <p>SPEC 明確寫「<b>不要存最後一次評分</b>，一次手滑就變紅點」。
 * 三次取眾數可以吸收單次誤按；平手取較差則是刻意偏保守 ——
 * 寧可高估生疏程度，也不要讓一個其實不熟的字被標成已掌握而消失在複習流程裡。
 *
 * <p>熟悉度等級就是四個 {@link Grade} 本身，對應 SPEC 第 5 節熟悉度頁的四級。
 */
public final class FamiliarityRule {

    /**
     * 由最近三次評分算出熟悉度。
     *
     * @param g1 最近第 1 次（最新），可為 null
     * @param g2 最近第 2 次，可為 null
     * @param g3 最近第 3 次（最舊），可為 null
     * @return 熟悉度等級；三個都是 null（從未複習）時回傳 null
     */
    public static Grade of(Grade g1, Grade g2, Grade g3) {
        // 壓成沒有 null 的陣列。中間出現 null 通常代表複習次數還不足三次。
        Grade[] grades = new Grade[3];
        int n = 0;
        if (g1 != null) {
            grades[n++] = g1;
        }
        if (g2 != null) {
            grades[n++] = g2;
        }
        if (g3 != null) {
            grades[n++] = g3;
        }

        if (n == 0) {
            return null;
        }

        // 數每個等級出現幾次
        int[] counts = new int[Grade.values().length];
        for (int i = 0; i < n; i++) {
            counts[grades[i].code()]++;
        }

        // 找出現次數最多的；次數相同時取較差的（code 較小的）。
        // 由小到大掃，只在「嚴格更多次」時才換人，自然就達成平手取較差。
        Grade best = null;
        int bestCount = 0;
        for (Grade candidate : Grade.values()) {
            int count = counts[candidate.code()];
            if (count > bestCount) {
                best = candidate;
                bestCount = count;
            }
        }
        return best;
    }

    /** 從資料庫的 code 直接判定，null 表示該次還沒有紀錄。 */
    public static Grade ofCodes(Integer c1, Integer c2, Integer c3) {
        return of(toGrade(c1), toGrade(c2), toGrade(c3));
    }

    private static Grade toGrade(Integer code) {
        return code == null ? null : Grade.fromCode(code);
    }

    private FamiliarityRule() {
    }
}
