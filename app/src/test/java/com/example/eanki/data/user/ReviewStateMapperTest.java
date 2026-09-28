package com.example.eanki.data.user;

import static com.example.eanki.review.Sm2Scheduler.MINUTES_PER_DAY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.example.eanki.review.Grade;

import org.junit.Test;

/**
 * entity ↔ 排程邏輯的轉換，以及最近三次評分的滾動儲存。
 *
 * <p>{@link ReviewState} 雖然帶 Room 註解，但註解只在編譯期作用，
 * 這個類別本身就是普通的 Java 物件，可以直接在 JVM 測試裡 new 出來。
 */
public class ReviewStateMapperTest {

    private static final long T0 = 1767258000000L;

    private static ReviewState newState() {
        ReviewState state = new ReviewState();
        state.wordId = 42L;
        state.direction = Direction.EN_TO_ZH;
        return state;
    }

    @Test
    public void 套用一次複習會寫回所有排程欄位() {
        ReviewState state = newState();

        ReviewStateMapper.applyReview(state, Grade.GOOD, T0);

        assertEquals(4 * MINUTES_PER_DAY, state.interval);
        assertEquals(T0 + 4L * MINUTES_PER_DAY * 60_000L, state.dueDate);
        assertEquals(T0, state.lastReviewed);
        assertEquals(1, state.repetitions);
        assertEquals(0, state.lapses);
        assertEquals(2.5d, state.easeFactor, 1e-9);
    }

    @Test
    public void 評分滾動儲存_最新的在grade1() {
        ReviewState state = newState();

        ReviewStateMapper.applyReview(state, Grade.FORGOT, T0);
        assertEquals(Integer.valueOf(Grade.FORGOT.code()), state.grade1);
        assertNull(state.grade2);
        assertNull(state.grade3);

        ReviewStateMapper.applyReview(state, Grade.HARD, T0);
        assertEquals(Integer.valueOf(Grade.HARD.code()), state.grade1);
        assertEquals(Integer.valueOf(Grade.FORGOT.code()), state.grade2);
        assertNull(state.grade3);

        ReviewStateMapper.applyReview(state, Grade.GOOD, T0);
        assertEquals(Integer.valueOf(Grade.GOOD.code()), state.grade1);
        assertEquals(Integer.valueOf(Grade.HARD.code()), state.grade2);
        assertEquals(Integer.valueOf(Grade.FORGOT.code()), state.grade3);

        // 第四次，最舊的那筆要被擠掉
        ReviewStateMapper.applyReview(state, Grade.EASY, T0);
        assertEquals(Integer.valueOf(Grade.EASY.code()), state.grade1);
        assertEquals(Integer.valueOf(Grade.GOOD.code()), state.grade2);
        assertEquals(Integer.valueOf(Grade.HARD.code()), state.grade3);
    }

    @Test
    public void 熟悉度由最近三次算出() {
        ReviewState state = newState();
        assertNull("從未複習過沒有熟悉度", ReviewStateMapper.familiarity(state));

        ReviewStateMapper.applyReview(state, Grade.GOOD, T0);
        ReviewStateMapper.applyReview(state, Grade.GOOD, T0);
        ReviewStateMapper.applyReview(state, Grade.FORGOT, T0);

        assertEquals("兩次記得一次忘了，眾數是記得",
                Grade.GOOD, ReviewStateMapper.familiarity(state));
    }

    /** SPEC 第 6.6 節用粗體點名的那件事。 */
    @Test
    public void 加強複習只動熟悉度_絕不動排程() {
        ReviewState state = newState();
        ReviewStateMapper.applyReview(state, Grade.GOOD, T0);

        int interval = state.interval;
        long due = state.dueDate;
        long lastReviewed = state.lastReviewed;
        double ease = state.easeFactor;
        int repetitions = state.repetitions;
        int lapses = state.lapses;

        ReviewStateMapper.applyExtraPractice(state, Grade.FORGOT);

        assertEquals("加強複習不可改 interval", interval, state.interval);
        assertEquals("加強複習不可改 due_date", due, state.dueDate);
        assertEquals("加強複習不可改 last_reviewed", lastReviewed, state.lastReviewed);
        assertEquals("加強複習不可改 ease_factor", ease, state.easeFactor, 1e-9);
        assertEquals("加強複習不可改 repetitions", repetitions, state.repetitions);
        assertEquals("加強複習不可改 lapses", lapses, state.lapses);

        assertEquals("但熟悉度紀錄要更新",
                Integer.valueOf(Grade.FORGOT.code()), state.grade1);
    }
}
