package com.example.eanki.review;

import static com.example.eanki.review.Sm2Scheduler.MINUTES_PER_DAY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * SM-2 排程的單元測試。
 *
 * <p>SPEC 第 8 節 Phase 2 要求「先寫單元測試再寫 UI」，所以這裡完全不碰 Android：
 * 不需要模擬器，{@code gradle :app:testDebugUnitTest} 直接跑在 JVM 上。
 *
 * <p>時間由測試固定，排程器不讀時鐘。
 */
public class Sm2SchedulerTest {

    /** 2026-01-01 09:00:00 UTC，任意固定值。 */
    private static final long T0 = 1767258000000L;

    private static final double EPS = 1e-9;

    private static long minutes(long m) {
        return m * 60_000L;
    }

    // ---------------------------------------------------------------
    // SPEC 第 6.3 節的四個間隔
    // ---------------------------------------------------------------

    @Test
    public void 記得_新卡給四天() {
        Schedule result = Sm2Scheduler.next(Schedule.newCard(T0), Grade.GOOD, T0);

        assertEquals(4 * MINUTES_PER_DAY, result.intervalMinutes);
        assertEquals(4.0d, result.intervalDays(), EPS);
        assertEquals(T0 + minutes(4 * MINUTES_PER_DAY), result.dueAt);
        assertEquals(1, result.repetitions);
        assertEquals(0, result.lapses);
        // 「記得」q=4，EF 不變
        assertEquals(2.5d, result.easeFactor, EPS);
    }

    @Test
    public void 太簡單_新卡給十天() {
        Schedule result = Sm2Scheduler.next(Schedule.newCard(T0), Grade.EASY, T0);

        assertEquals(10 * MINUTES_PER_DAY, result.intervalMinutes);
        assertEquals(10.0d, result.intervalDays(), EPS);
        assertEquals(T0 + minutes(10 * MINUTES_PER_DAY), result.dueAt);
        assertEquals(1, result.repetitions);
        // 「太簡單」q=5，EF +0.1
        assertEquals(2.6d, result.easeFactor, EPS);
    }

    @Test
    public void 有點難_新卡給一天() {
        Schedule result = Sm2Scheduler.next(Schedule.newCard(T0), Grade.HARD, T0);

        assertEquals(MINUTES_PER_DAY, result.intervalMinutes);
        assertEquals(1.0d, result.intervalDays(), EPS);
        assertEquals(T0 + minutes(MINUTES_PER_DAY), result.dueAt);
        assertEquals(1, result.repetitions);
        // 「有點難」q=3，EF -0.14
        assertEquals(2.36d, result.easeFactor, EPS);
    }

    @Test
    public void 忘了_給十分鐘並重置repetitions() {
        // 先讓卡片累積幾次成功，確認真的會被重置
        Schedule s = Schedule.newCard(T0);
        s = Sm2Scheduler.next(s, Grade.GOOD, T0);
        s = Sm2Scheduler.next(s, Grade.GOOD, T0);
        s = Sm2Scheduler.next(s, Grade.GOOD, T0);
        assertEquals(3, s.repetitions);
        assertTrue(s.intervalMinutes > 10 * MINUTES_PER_DAY);

        Schedule result = Sm2Scheduler.next(s, Grade.FORGOT, T0);

        assertEquals(10, result.intervalMinutes);
        assertEquals(T0 + minutes(10), result.dueAt);
        assertEquals("忘了必須重置 repetitions", 0, result.repetitions);
        assertEquals("忘了必須累加 lapses", 1, result.lapses);
        assertEquals("忘了扣 0.2 EF", 2.3d, result.easeFactor, EPS);
    }

    // ---------------------------------------------------------------
    // 連續答對，間隔要拉長
    // ---------------------------------------------------------------

    @Test
    public void 連續記得_間隔逐次拉長() {
        Schedule s = Schedule.newCard(T0);

        s = Sm2Scheduler.next(s, Grade.GOOD, T0);
        assertEquals(4 * MINUTES_PER_DAY, s.intervalMinutes);      // 4 天

        s = Sm2Scheduler.next(s, Grade.GOOD, T0);
        assertEquals(10 * MINUTES_PER_DAY, s.intervalMinutes);     // 4 × 2.5 = 10 天

        s = Sm2Scheduler.next(s, Grade.GOOD, T0);
        assertEquals(25 * MINUTES_PER_DAY, s.intervalMinutes);     // 10 × 2.5 = 25 天

        s = Sm2Scheduler.next(s, Grade.GOOD, T0);
        assertEquals(62.5d, s.intervalDays(), EPS);                // 25 × 2.5

        assertEquals(4, s.repetitions);
        assertEquals(2.5d, s.easeFactor, EPS);
    }

    @Test
    public void 間隔嚴格遞增直到觸及上限() {
        Schedule s = Schedule.newCard(T0);
        int previous = 0;
        int cap = 365 * MINUTES_PER_DAY;

        // 連續「記得」在第 6 次會撞到一年上限：4 → 10 → 25 → 62.5 → 156.25 → 365 天
        for (int i = 0; i < 5; i++) {
            s = Sm2Scheduler.next(s, Grade.GOOD, T0);
            assertTrue("第 " + (i + 1) + " 次應該比前一次長，"
                            + "但 " + s.intervalMinutes + " <= " + previous,
                    s.intervalMinutes > previous);
            assertTrue("這五次都還不該觸及上限", s.intervalMinutes < cap);
            previous = s.intervalMinutes;
        }

        s = Sm2Scheduler.next(s, Grade.GOOD, T0);
        assertEquals("第 6 次觸及一年上限", cap, s.intervalMinutes);

        // 之後停在上限不再成長，這是刻意的
        s = Sm2Scheduler.next(s, Grade.GOOD, T0);
        assertEquals(cap, s.intervalMinutes);
    }

    @Test
    public void 連續太簡單_比連續記得拉得更快() {
        Schedule easy = Schedule.newCard(T0);
        Schedule good = Schedule.newCard(T0);

        for (int i = 0; i < 3; i++) {
            easy = Sm2Scheduler.next(easy, Grade.EASY, T0);
            good = Sm2Scheduler.next(good, Grade.GOOD, T0);
        }
        assertTrue("太簡單的間隔應該明顯長於記得，"
                        + easy.intervalDays() + " vs " + good.intervalDays(),
                easy.intervalMinutes > good.intervalMinutes);
    }

    @Test
    public void 連續有點難_間隔成長得慢() {
        Schedule s = Schedule.newCard(T0);

        s = Sm2Scheduler.next(s, Grade.HARD, T0);
        assertEquals(MINUTES_PER_DAY, s.intervalMinutes);          // 1 天

        s = Sm2Scheduler.next(s, Grade.HARD, T0);
        assertEquals(1728, s.intervalMinutes);                     // 1440 × 1.2
        assertEquals(1.2d, s.intervalDays(), EPS);

        s = Sm2Scheduler.next(s, Grade.HARD, T0);
        // 1728 × 1.2 = 2073.6，間隔是整數分鐘所以進位成 2074（= 1.4403 天，不是剛好 1.44）
        assertEquals(2074, s.intervalMinutes);
        assertEquals(1.44d, s.intervalDays(), 0.001d);

        // 三次「有點難」之後 EF 掉到接近下限，但還沒觸底
        assertEquals(2.5d - 3 * 0.14d, s.easeFactor, EPS);
    }

    // ---------------------------------------------------------------
    // 遺忘後的重新學習
    // ---------------------------------------------------------------

    @Test
    public void 忘了之後再答對_重新從四天起算而不是接續原本的長間隔() {
        Schedule s = Schedule.newCard(T0);
        for (int i = 0; i < 4; i++) {
            s = Sm2Scheduler.next(s, Grade.GOOD, T0);
        }
        int beforeLapse = s.intervalMinutes;
        assertTrue(beforeLapse > 60 * MINUTES_PER_DAY);

        s = Sm2Scheduler.next(s, Grade.FORGOT, T0);
        s = Sm2Scheduler.next(s, Grade.GOOD, T0);

        assertEquals("重新學起來要回到首次成功的 4 天",
                4 * MINUTES_PER_DAY, s.intervalMinutes);
        assertEquals(1, s.repetitions);
        assertEquals(1, s.lapses);
    }

    @Test
    public void 連續遺忘_lapses累加且EF有下限() {
        Schedule s = Schedule.newCard(T0);

        for (int i = 0; i < 20; i++) {
            s = Sm2Scheduler.next(s, Grade.FORGOT, T0);
        }

        assertEquals(20, s.lapses);
        assertEquals(10, s.intervalMinutes);
        assertEquals("EF 不可低於 1.3", 1.3d, s.easeFactor, EPS);
    }

    // ---------------------------------------------------------------
    // 邊界
    // ---------------------------------------------------------------

    @Test
    public void EF觸底時間隔仍然會成長() {
        Schedule s = new Schedule(
                5 * MINUTES_PER_DAY, Sm2Scheduler.MIN_EASE_FACTOR, 3, 5, T0, T0);

        Schedule result = Sm2Scheduler.next(s, Grade.GOOD, T0);

        assertEquals(1.3d, result.easeFactor, EPS);
        assertTrue("EF 觸底也不能讓間隔停滯", result.intervalMinutes > s.intervalMinutes);
        assertEquals(6.5d, result.intervalDays(), EPS);            // 5 × 1.3
    }

    @Test
    public void 間隔有上限一年() {
        Schedule s = new Schedule(
                300 * MINUTES_PER_DAY, 2.5d, 10, 0, T0, T0);

        Schedule result = Sm2Scheduler.next(s, Grade.EASY, T0);

        assertEquals(365 * MINUTES_PER_DAY, result.intervalMinutes);
    }

    @Test
    public void 新卡的狀態() {
        Schedule card = Schedule.newCard(T0);

        assertTrue(card.isNew());
        assertTrue(card.isDue(T0));
        assertEquals(0, card.intervalMinutes);
        assertEquals(2.5d, card.easeFactor, EPS);

        Schedule after = Sm2Scheduler.next(card, Grade.GOOD, T0);
        assertTrue("複習過就不再是新卡", !after.isNew());
        assertTrue("剛複習完不應該到期", !after.isDue(T0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void 評分不可為null() {
        Sm2Scheduler.next(Schedule.newCard(T0), null, T0);
    }
}
