package com.example.eanki.review;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 每日上限的範圍與夾限。SPEC 第 6.4 節。 */
public class DailyLimitsTest {

    @Test
    public void 預設值就是SPEC寫的十與六十() {
        DailyLimits d = DailyLimits.defaults();
        assertEquals(10, d.newPerDay);
        assertEquals(60, d.reviewsPerDay);
    }

    @Test
    public void 範圍內的值原樣通過() {
        DailyLimits d = DailyLimits.clamped(25, 120);
        assertEquals(25, d.newPerDay);
        assertEquals(120, d.reviewsPerDay);
    }

    /** 新字上限可以是 0：「先把手上的消化完，暫時不要再進新字」。 */
    @Test
    public void 新字上限可以設成零() {
        assertEquals(0, DailyLimits.clamped(0, 60).newPerDay);
    }

    /**
     * 複習上限的下限是 5 不是 0。
     *
     * <p>0 會讓複習佇列永遠是空的，畫面看起來像壞掉，
     * 而且會靜靜地把 SPEC 第三原則整個關掉。
     */
    @Test
    public void 複習上限不可以被設成零() {
        assertEquals(DailyLimits.MIN_REVIEWS_PER_DAY, DailyLimits.clamped(10, 0).reviewsPerDay);
    }

    /**
     * SharedPreferences 是可以被寫壞的（備份還原、adb、舊版本），
     * 讀出來的值不夾限就會直接反映到每日份量上。
     */
    @Test
    public void 超出範圍的值會被夾回來() {
        DailyLimits tooBig = DailyLimits.clamped(9999, 9999);
        assertEquals(DailyLimits.MAX_NEW_PER_DAY, tooBig.newPerDay);
        assertEquals(DailyLimits.MAX_REVIEWS_PER_DAY, tooBig.reviewsPerDay);

        DailyLimits negative = DailyLimits.clamped(-5, -5);
        assertEquals(DailyLimits.MIN_NEW_PER_DAY, negative.newPerDay);
        assertEquals(DailyLimits.MIN_REVIEWS_PER_DAY, negative.reviewsPerDay);
    }
}
