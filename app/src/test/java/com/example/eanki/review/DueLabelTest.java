package com.example.eanki.review;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/** 單字本列表的「下次複習」。 */
public class DueLabelTest {

    /** 固定在今天中午，避免測試在午夜前後跑到時結果會變。 */
    private static long noonToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 12);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void 還沒放行的字() {
        assertEquals(DueLabel.Kind.NOT_STARTED, DueLabel.of(null, noonToday()).kind);
    }

    @Test
    public void 已經到期() {
        long now = noonToday();
        assertEquals(DueLabel.Kind.DUE_NOW, DueLabel.of(now - 1000, now).kind);
        assertEquals(DueLabel.Kind.DUE_NOW, DueLabel.of(now, now).kind);
    }

    /** 「忘了」之後的間隔是 10 分鐘，那是今天稍後，不是 0 天後。 */
    @Test
    public void 今天稍後() {
        long now = noonToday();
        assertEquals(DueLabel.Kind.LATER_TODAY,
                DueLabel.of(now + TimeUnit.MINUTES.toMillis(10), now).kind);
        assertEquals(DueLabel.Kind.LATER_TODAY,
                DueLabel.of(now + TimeUnit.HOURS.toMillis(6), now).kind);
    }

    @Test
    public void 幾天後() {
        long now = noonToday();
        DueLabel tomorrow = DueLabel.of(now + TimeUnit.DAYS.toMillis(1), now);
        assertEquals(DueLabel.Kind.IN_DAYS, tomorrow.kind);
        assertEquals(1, tomorrow.days);

        assertEquals(4, DueLabel.of(now + TimeUnit.DAYS.toMillis(4), now).days);
        assertEquals(10, DueLabel.of(now + TimeUnit.DAYS.toMillis(10), now).days);
    }

    /**
     * 用日界線算，不是用相隔幾小時。
     *
     * <p>晚上 11 點看到明天早上 8 點到期的字，那是「1 天後」——
     * 只隔 9 小時，但確實是明天的事。用小時算會顯示成「今天稍後」，
     * 使用者會以為今晚還要再複習一次。
     */
    @Test
    public void 跨日界線就算一天而不是看相隔幾小時() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 23);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long lateTonight = c.getTimeInMillis();

        DueLabel label = DueLabel.of(lateTonight + TimeUnit.HOURS.toMillis(9), lateTonight);
        assertEquals(DueLabel.Kind.IN_DAYS, label.kind);
        assertEquals(1, label.days);
    }
}
