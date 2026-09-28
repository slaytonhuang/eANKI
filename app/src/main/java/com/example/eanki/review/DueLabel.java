package com.example.eanki.review;

import androidx.annotation.Nullable;

import java.util.Calendar;

/**
 * 「下次複習」要顯示什麼。SPEC 第 5 節單字本列表。
 *
 * <p>純 Java，不依賴 Android。回傳的是語意，文字由 UI 去查字串資源 ——
 * 這一層不該知道任何一句中文。
 */
public final class DueLabel {

    public enum Kind {
        /** 還沒放行進複習流程。 */
        NOT_STARTED,
        /** 已經到期，今天該複習。 */
        DUE_NOW,
        /** 今天稍晚（例如「忘了」之後的 10 分鐘間隔）。 */
        LATER_TODAY,
        /** N 天後。 */
        IN_DAYS
    }

    public final Kind kind;
    /** 只有 {@link Kind#IN_DAYS} 有意義。 */
    public final int days;

    private DueLabel(Kind kind, int days) {
        this.kind = kind;
        this.days = days;
    }

    /**
     * @param dueAt 到期時間，null 代表還沒放行
     */
    public static DueLabel of(@Nullable Long dueAt, long now) {
        if (dueAt == null) {
            return new DueLabel(Kind.NOT_STARTED, 0);
        }
        if (dueAt <= now) {
            return new DueLabel(Kind.DUE_NOW, 0);
        }
        int days = daysBetween(now, dueAt);
        // 用「日界線」而不是「相隔幾小時」算天數：
        // 晚上 11 點看到明天早上 8 點到期的字，那是「1 天後」不是「今天稍後」。
        return days <= 0
                ? new DueLabel(Kind.LATER_TODAY, 0)
                : new DueLabel(Kind.IN_DAYS, days);
    }

    /** 兩個時間點相隔幾個當地日界線。 */
    private static int daysBetween(long from, long to) {
        Calendar a = startOfDay(from);
        Calendar b = startOfDay(to);
        long diff = b.getTimeInMillis() - a.getTimeInMillis();
        return (int) Math.round(diff / 86_400_000.0);
    }

    private static Calendar startOfDay(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }
}
