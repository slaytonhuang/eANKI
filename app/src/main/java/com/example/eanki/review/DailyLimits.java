package com.example.eanki.review;

/**
 * SPEC 第 6.4 節的兩個每日上限。
 *
 * <p><b>這兩個上限不可合併。</b>沒有新字節流機制的話，
 * 使用者拍一頁課本進 30 個字，隔天佇列就崩了。
 *
 * <p>純 Java，不依賴 Android。數值來自設定頁
 * （{@code data.settings.AppSettings}），這裡只負責定義範圍與夾限。
 */
public final class DailyLimits {

    /**
     * 新字上限：每天最多放行幾個新字進入複習流程。
     * SPEC 預設 10。
     */
    public static final int DEFAULT_NEW_PER_DAY = 10;

    /**
     * 複習上限：<b>當天總量天花板</b>，含當天放行的新字。
     * 超過的順延，最久沒複習的優先。SPEC 預設 60。
     */
    public static final int DEFAULT_REVIEWS_PER_DAY = 60;

    /**
     * 新字上限可以調到 0：代表「暫時不要再進新字了，先把手上的消化完」。
     * 這是合理的使用情境，不該被擋掉。
     */
    public static final int MIN_NEW_PER_DAY = 0;
    public static final int MAX_NEW_PER_DAY = 50;

    /**
     * 複習上限的下限是 5，不是 0。
     *
     * <p>0 會讓複習佇列永遠是空的 —— 畫面上看起來像壞掉，
     * 而且會靜靜地把 SPEC 第三原則（複習節奏不能被打斷）整個關掉。
     * 真的不想複習就是不打開 App，不需要用設定達成。
     */
    public static final int MIN_REVIEWS_PER_DAY = 5;
    public static final int MAX_REVIEWS_PER_DAY = 200;

    public final int newPerDay;
    public final int reviewsPerDay;

    public DailyLimits(int newPerDay, int reviewsPerDay) {
        this.newPerDay = newPerDay;
        this.reviewsPerDay = reviewsPerDay;
    }

    public static DailyLimits defaults() {
        return new DailyLimits(DEFAULT_NEW_PER_DAY, DEFAULT_REVIEWS_PER_DAY);
    }

    /**
     * 夾到合法範圍內。
     *
     * <p>讀設定時一律經過這裡：SharedPreferences 是可以被外部改壞的
     * （備份還原、adb、舊版本留下的值），拿到超出範圍的數字就直接
     * 反映到每日份量上，而那正是 SPEC 第二原則要守住的東西。
     */
    public static DailyLimits clamped(int newPerDay, int reviewsPerDay) {
        return new DailyLimits(
                clamp(newPerDay, MIN_NEW_PER_DAY, MAX_NEW_PER_DAY),
                clamp(reviewsPerDay, MIN_REVIEWS_PER_DAY, MAX_REVIEWS_PER_DAY));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
