package com.example.eanki.reminder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/**
 * 提醒時間的算術。純 Java，跑在 JVM 上。
 *
 * <p>這段之所以值得測，是因為它是「提醒會不會漂走」的關鍵：
 * 每次都重新算出下一個 21:00，而不是從上次執行時間加 24 小時。
 */
public class ReminderSchedulerTest {

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(year, month - 1, day, hour, minute, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void 同一天稍早_排到今天的二十一點() {
        long now = at(2026, 9, 22, 8, 30);
        long delay = ReminderScheduler.millisUntilNext(now, 21, 0);

        assertEquals(TimeUnit.HOURS.toMillis(12) + TimeUnit.MINUTES.toMillis(30), delay);
        assertEquals(at(2026, 9, 22, 21, 0), now + delay);
    }

    @Test
    public void 已經過了二十一點_排到明天() {
        long now = at(2026, 9, 22, 22, 15);
        long delay = ReminderScheduler.millisUntilNext(now, 21, 0);

        assertEquals(at(2026, 9, 23, 21, 0), now + delay);
        assertTrue(delay > 0);
    }

    @Test
    public void 剛好在二十一點整_排到明天而不是立刻再觸發一次() {
        long now = at(2026, 9, 22, 21, 0);
        long delay = ReminderScheduler.millisUntilNext(now, 21, 0);

        assertEquals(TimeUnit.DAYS.toMillis(1), delay);
    }

    /**
     * 執行被延後時不可累積漂移。
     *
     * <p>PeriodicWorkRequest 的下一輪是從實際執行時間起算，
     * 延後 20 分鐘就變 21:20，再下一輪 21:40……幾週後漂到半夜。
     * 這裡每次重新算絕對時間，所以延後多久都還是回到 21:00。
     */
    @Test
    public void 執行延後也不會漂移() {
        long late = at(2026, 9, 22, 21, 37);   // 這輪晚了 37 分鐘才跑
        long delay = ReminderScheduler.millisUntilNext(late, 21, 0);

        assertEquals("下一次仍然準確落在明天 21:00",
                at(2026, 9, 23, 21, 0), late + delay);
    }

    @Test
    public void 延遲永遠是正數且不超過一天() {
        for (int hour = 0; hour < 24; hour++) {
            for (int minute : new int[]{0, 1, 30, 59}) {
                long now = at(2026, 9, 22, hour, minute);
                long delay = ReminderScheduler.millisUntilNext(now, 21, 0);
                assertTrue("hour=" + hour + " minute=" + minute, delay > 0);
                assertTrue("hour=" + hour + " minute=" + minute,
                        delay <= TimeUnit.DAYS.toMillis(1));
            }
        }
    }
}
