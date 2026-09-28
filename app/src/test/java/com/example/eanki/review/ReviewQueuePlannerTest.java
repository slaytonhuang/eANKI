package com.example.eanki.review;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 每日佇列上限的單元測試。SPEC 第 6.4 節。 */
public class ReviewQueuePlannerTest {

    private static final DailyLimits DEFAULTS = DailyLimits.defaults();

    /** 產生 id 為 base..base+count-1 的清單。 */
    private static List<Long> ids(long base, int count) {
        List<Long> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(base + i);
        }
        return list;
    }

    private static List<Long> none() {
        return Collections.emptyList();
    }

    // ---- 新字上限 ----

    @Test
    public void 新字上限_一天最多放行十個() {
        ReviewQueuePlanner.Plan plan = ReviewQueuePlanner.plan(
                none(), ids(100, 50), 0, DEFAULTS);

        assertEquals("50 個新字只能放行 10 個", 10, plan.size());
        assertEquals(10, plan.newlyReleasedWordIds.size());
    }

    @Test
    public void 新字上限_扣掉今天已放行的() {
        ReviewQueuePlanner.Plan plan = ReviewQueuePlanner.plan(
                none(), ids(100, 50), 7, DEFAULTS);

        assertEquals("今天已放行 7 個，只剩 3 個額度", 3, plan.size());
    }

    @Test
    public void 新字上限_已用完就完全不放行() {
        ReviewQueuePlanner.Plan plan = ReviewQueuePlanner.plan(
                none(), ids(100, 50), 10, DEFAULTS);

        assertTrue(plan.isEmpty());
    }

    @Test
    public void 新字上限_已放行超過上限也不會變負數() {
        ReviewQueuePlanner.Plan plan = ReviewQueuePlanner.plan(
                none(), ids(100, 50), 15, DEFAULTS);

        assertTrue(plan.isEmpty());
    }

    @Test
    public void 新字不足上限時全部放行() {
        ReviewQueuePlanner.Plan plan = ReviewQueuePlanner.plan(
                none(), ids(100, 3), 0, DEFAULTS);

        assertEquals(3, plan.size());
    }

    /**
     * 呼叫端傳進來的前 N 個就是今天要放行的新字。
     *
     * <p>順序由呼叫端決定（DAO 那邊是 {@code ORDER BY RANDOM()}），
     * planner 只負責「從頭取 N 個」。所以這裡比的是集合不是順序。
     */
    @Test
    public void 新字從清單開頭取() {
        ReviewQueuePlanner.Plan plan = ReviewQueuePlanner.plan(
                none(), ids(100, 50), 0, DEFAULTS);

        assertEquals(new java.util.HashSet<>(ids(100, 10)),
                new java.util.HashSet<>(plan.wordIds));
        assertEquals(ids(100, 10), plan.newlyReleasedWordIds);
    }

    // ---- 複習上限 ----

    @Test
    public void 複習上限_超過六十張的順延() {
        ReviewQueuePlanner.Plan plan = ReviewQueuePlanner.plan(
                ids(1, 100), none(), 0, DEFAULTS);

        assertEquals(60, plan.size());
        assertEquals("剩下 40 張順延", 40, plan.deferredDueCount);
    }

    /**
     * SPEC 第 6.4 節：超過上限時「最久沒複習的優先」。
     *
     * <p>呼叫端已經把最久沒複習的排在前面，這裡驗證 planner 是<b>從頭取</b>
     * 而不是從尾取。佇列最後會被打散，所以比的是集合 ——
     * 打散只影響出現順序，不影響選中了誰。
     */
    @Test
    public void 複習上限_最久沒複習的優先() {
        List<Long> due = ids(1, 100);
        ReviewQueuePlanner.Plan plan = ReviewQueuePlanner.plan(
                due, none(), 0, DEFAULTS);

        assertEquals(new java.util.HashSet<>(ids(1, 60)),
                new java.util.HashSet<>(plan.wordIds));
    }

    @Test
    public void 沒超過上限就不順延() {
        ReviewQueuePlanner.Plan plan = ReviewQueuePlanner.plan(
                ids(1, 20), none(), 0, DEFAULTS);

        assertEquals(20, plan.size());
        assertEquals(0, plan.deferredDueCount);
    }

    // ---- 兩個上限互動：複習上限是「當天總量天花板」 ----

    @Test
    public void 複習上限涵蓋新字_不是另外加上去() {
        // 55 張到期 + 50 個新字：總量不可超過 60
        ReviewQueuePlanner.Plan plan = ReviewQueuePlanner.plan(
                ids(1, 55), ids(100, 50), 0, DEFAULTS);

        assertEquals("總量天花板是 60", 60, plan.size());
        assertEquals("只剩 5 個空位給新字", 5, plan.newlyReleasedWordIds.size());
    }

    @Test
    public void 到期卡塞滿時新字完全不放行() {
        ReviewQueuePlanner.Plan plan = ReviewQueuePlanner.plan(
                ids(1, 80), ids(100, 50), 0, DEFAULTS);

        assertEquals(60, plan.size());
        assertTrue("積壓太多時新字讓位", plan.newlyReleasedWordIds.isEmpty());
        assertEquals(20, plan.deferredDueCount);
    }

    @Test
    public void 空位多於新字額度時仍受新字上限約束() {
        // 只有 5 張到期，還有 55 個空位，但新字一天仍只能放 10 個
        ReviewQueuePlanner.Plan plan = ReviewQueuePlanner.plan(
                ids(1, 5), ids(100, 50), 0, DEFAULTS);

        assertEquals(15, plan.size());
        assertEquals(10, plan.newlyReleasedWordIds.size());
    }

    /**
     * 到期卡與新字要打散混在一起，不是到期卡整批排前面。
     *
     * <p>使用者的原話：「不然我會一直複習一樣的單字」——
     * 不打散的話，每天要先連做十幾張剛複習過的字才會碰到新字。
     */
    @Test
    public void 到期卡與新字打散混在一起() {
        ReviewQueuePlanner.Plan plan = ReviewQueuePlanner.plan(
                ids(1, 3), ids(100, 3), 0, DEFAULTS, new java.util.Random(42));

        assertEquals("選中的還是那六張", new java.util.HashSet<>(
                        java.util.Arrays.asList(1L, 2L, 3L, 100L, 101L, 102L)),
                new java.util.HashSet<>(plan.wordIds));
        assertEquals("放行的還是那三個新字",
                java.util.Arrays.asList(100L, 101L, 102L), plan.newlyReleasedWordIds);
        assertNotEquals("不該是到期卡整批排在前面",
                java.util.Arrays.asList(1L, 2L, 3L, 100L, 101L, 102L), plan.wordIds);
    }

    /**
     * 打散<b>不可以</b>改變選中的是哪些卡。
     *
     * <p>換不同亂數種子跑，選中的集合必須完全一樣 ——
     * 否則「最久沒複習的優先」會變成擲骰子。
     */
    @Test
    public void 打散不影響選中哪些卡() {
        java.util.Set<Long> first = null;
        for (long seed = 0; seed < 20; seed++) {
            ReviewQueuePlanner.Plan plan = ReviewQueuePlanner.plan(
                    ids(1, 100), ids(500, 50), 0, DEFAULTS, new java.util.Random(seed));
            java.util.Set<Long> chosen = new java.util.HashSet<>(plan.wordIds);
            assertEquals("總量天花板不變", 60, plan.wordIds.size());
            assertEquals("順延張數不變", 40, plan.deferredDueCount);
            if (first == null) {
                first = chosen;
            } else {
                assertEquals("不同種子選中的卡必須一樣", first, chosen);
            }
        }
    }

    /** 打散之後不可以有重複或遺漏。 */
    @Test
    public void 打散不會弄丟或重複任何一張卡() {
        ReviewQueuePlanner.Plan plan = ReviewQueuePlanner.plan(
                ids(1, 30), ids(100, 20), 0, DEFAULTS, new java.util.Random(7));

        assertEquals(40, plan.wordIds.size());
        assertEquals("有重複的卡", 40, new java.util.HashSet<>(plan.wordIds).size());
    }

    // ---- 邊界 ----

    @Test
    public void 什麼都沒有時佇列是空的() {
        ReviewQueuePlanner.Plan plan = ReviewQueuePlanner.plan(
                none(), none(), 0, DEFAULTS);

        assertTrue(plan.isEmpty());
        assertEquals(0, plan.deferredDueCount);
    }

    @Test
    public void 自訂上限() {
        ReviewQueuePlanner.Plan plan = ReviewQueuePlanner.plan(
                ids(1, 100), ids(100, 100), 0, new DailyLimits(2, 5));

        assertEquals(5, plan.size());
        assertTrue(plan.newlyReleasedWordIds.isEmpty());
    }

    @Test
    public void 上限為零時不排任何卡() {
        ReviewQueuePlanner.Plan plan = ReviewQueuePlanner.plan(
                ids(1, 100), ids(100, 100), 0, new DailyLimits(0, 0));

        assertTrue(plan.isEmpty());
        assertEquals(100, plan.deferredDueCount);
    }
}
