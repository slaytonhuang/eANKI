package com.example.eanki.review;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 決定今天要複習哪些卡。SPEC 第 6.4 節。
 *
 * <p>純 Java，不依賴 Android 或 Room —— 跟 {@link Sm2Scheduler} 一樣可以單獨測試。
 * 呼叫端負責把資料從資料庫撈出來並排好序，這裡只套上限。
 *
 * <h2>兩個上限怎麼互動</h2>
 *
 * SPEC 把複習上限定義為「<b>當天總量天花板</b>」，所以它涵蓋新字，不是另外加上去的：
 *
 * <ol>
 *   <li>先放到期的複習卡，<b>最久沒複習的優先</b>，最多放到複習上限</li>
 *   <li>剩下的空位才給新字，且新字另外受「今天還能放行幾個」限制</li>
 * </ol>
 *
 * 到期卡優先於新字，是因為 SPEC 的第二原則是「每天的份量固定」——
 * 積壓的複習沒清完就灌新字，只會讓隔天更崩。
 * 代價是backlog 很深的時候新字會完全不放行，這是刻意的。
 *
 * <h2>選哪些卡 vs 用什麼順序出現</h2>
 *
 * 上面那個優先順序決定的是<b>哪些卡進得了今天的佇列</b>，
 * 跟它們出現的先後是兩回事。選完之後整份佇列會<b>打散</b>：
 * 不打散的話，到期卡會整批排在前面，使用者要先連做十幾張剛複習過的字
 * 才會碰到新字 —— 感覺像「一直在複習一樣的單字」。
 *
 * <p>打散<b>不會</b>改變選中的是哪些卡，所以「最久沒複習的優先」
 * 在超過上限時砍誰這件事上完全不受影響。
 */
public final class ReviewQueuePlanner {

    /** 規劃結果。 */
    public static final class Plan {

        /** 今天要複習的卡。到期卡與新字已經打散混在一起。 */
        public final List<Long> wordIds;

        /** 其中屬於「今天新放行」的字，呼叫端要為它們建立 review_state。 */
        public final List<Long> newlyReleasedWordIds;

        /** 到期但因為超過複習上限而順延的張數，給 UI 顯示用。 */
        public final int deferredDueCount;

        Plan(List<Long> wordIds, List<Long> newlyReleasedWordIds, int deferredDueCount) {
            this.wordIds = wordIds;
            this.newlyReleasedWordIds = newlyReleasedWordIds;
            this.deferredDueCount = deferredDueCount;
        }

        public int size() {
            return wordIds.size();
        }

        public boolean isEmpty() {
            return wordIds.isEmpty();
        }
    }

    /**
     * @param dueWordIds        已在複習流程中、今天到期的字，
     *                          <b>必須由呼叫端依「最久沒複習」排好序</b>
     * @param unreleasedWordIds 還沒進入複習流程的新字，<b>已由呼叫端隨機排序</b>——
     *                          單字表多半按字母排，照加入順序放行會讓連續好幾天
     *                          都在背 a 開頭的字
     * @param releasedToday     今天已經放行過幾個新字
     * @param limits            每日上限
     */
    public static Plan plan(List<Long> dueWordIds,
                            List<Long> unreleasedWordIds,
                            int releasedToday,
                            DailyLimits limits) {
        return plan(dueWordIds, unreleasedWordIds, releasedToday, limits, new Random());
    }

    /**
     * 同上，但可以指定亂數來源。
     *
     * <p>存在的理由只有一個：讓打散這件事測得了。
     * 正式程式碼走上面那個版本。
     */
    public static Plan plan(List<Long> dueWordIds,
                            List<Long> unreleasedWordIds,
                            int releasedToday,
                            DailyLimits limits,
                            Random random) {

        List<Long> queue = new ArrayList<>();
        List<Long> released = new ArrayList<>();

        // 1. 到期的複習卡，最久沒複習的優先
        int dueTaken = Math.min(dueWordIds.size(), limits.reviewsPerDay);
        queue.addAll(dueWordIds.subList(0, dueTaken));
        int deferred = dueWordIds.size() - dueTaken;

        // 2. 剩下的空位給新字，同時受新字上限約束
        int roomLeft = limits.reviewsPerDay - dueTaken;
        int newAllowance = Math.max(0, limits.newPerDay - releasedToday);
        int newTaken = Math.min(Math.min(roomLeft, newAllowance), unreleasedWordIds.size());

        for (int i = 0; i < newTaken; i++) {
            Long id = unreleasedWordIds.get(i);
            queue.add(id);
            released.add(id);
        }

        // 選完才打散。順序變了，但選中的是哪些卡沒變 ——
        // 「最久沒複習的優先」管的是超過上限時砍誰，那一步已經做完了。
        Collections.shuffle(queue, random);

        return new Plan(queue, released, deferred);
    }

    private ReviewQueuePlanner() {
    }
}
