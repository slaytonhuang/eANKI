package com.example.eanki.review;

/**
 * 一張卡在某個方向上的排程狀態，不可變。
 *
 * <p>純 Java，不依賴 Android 或 Room —— 這是能脫離 UI 單獨測試 {@link Sm2Scheduler} 的前提。
 * 與資料庫的轉換在 {@code data.user.ReviewStateMapper}。
 *
 * <p>時間一律用 epoch millis，間隔一律用分鐘（見 {@link #intervalMinutes}）。
 */
public final class Schedule {

    /**
     * 目前間隔，<b>單位為分鐘</b>。
     * SPEC 第 6.3 節的「忘了 → 10 分鐘」用整數天表達不了，所以用分鐘。
     */
    public final int intervalMinutes;

    /** SM-2 的 ease factor，越大代表越容易、間隔拉得越快。下限 1.3。 */
    public final double easeFactor;

    /** 連續答對次數。答錯會歸零。 */
    public final int repetitions;

    /** 累計遺忘次數。只增不減，用來辨識「一直記不起來」的字。 */
    public final int lapses;

    /** 下次到期時間，epoch millis。 */
    public final long dueAt;

    /** 上次複習時間，epoch millis。0 表示從未複習。 */
    public final long lastReviewedAt;

    public Schedule(int intervalMinutes, double easeFactor, int repetitions,
                    int lapses, long dueAt, long lastReviewedAt) {
        this.intervalMinutes = intervalMinutes;
        this.easeFactor = easeFactor;
        this.repetitions = repetitions;
        this.lapses = lapses;
        this.dueAt = dueAt;
        this.lastReviewedAt = lastReviewedAt;
    }

    /**
     * 一張還沒複習過的新卡。
     *
     * @param availableAt 這張卡開始可以複習的時間（新字放行的時間點，見 SPEC 第 6.4 節）
     */
    public static Schedule newCard(long availableAt) {
        return new Schedule(0, Sm2Scheduler.DEFAULT_EASE_FACTOR, 0, 0, availableAt, 0L);
    }

    public boolean isNew() {
        return lastReviewedAt == 0L;
    }

    public boolean isDue(long now) {
        return now >= dueAt;
    }

    /** 間隔換算成天，給 UI 顯示「預估間隔」用（SPEC 第 6.3 節）。 */
    public double intervalDays() {
        return intervalMinutes / (double) Sm2Scheduler.MINUTES_PER_DAY;
    }

    @Override
    public String toString() {
        return String.format(
                "Schedule{interval=%d min (%.2f d), ef=%.2f, reps=%d, lapses=%d}",
                intervalMinutes, intervalDays(), easeFactor, repetitions, lapses);
    }
}
