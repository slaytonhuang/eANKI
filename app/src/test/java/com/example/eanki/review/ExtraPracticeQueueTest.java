package com.example.eanki.review;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.example.eanki.review.FamiliarityStats.GradeTriple;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** 加強複習挑哪些字。SPEC 第 6.6 節。 */
public class ExtraPracticeQueueTest {

    private static GradeTriple triple(long id, Grade g) {
        return new GradeTriple(id, g.code(), g.code(), g.code());
    }

    /** 忘了 3 個（id 1–3）、有點難 3 個（id 11–13）、記得 2 個（id 21–22）。 */
    private static FamiliarityStats stats() {
        List<GradeTriple> rows = new ArrayList<>();
        for (long i = 1; i <= 3; i++) {
            rows.add(triple(i, Grade.FORGOT));
        }
        for (long i = 11; i <= 13; i++) {
            rows.add(triple(i, Grade.HARD));
        }
        for (long i = 21; i <= 22; i++) {
            rows.add(triple(i, Grade.GOOD));
        }
        return FamiliarityStats.of(rows, 100);
    }

    @Test
    public void 不設上限時忘了在前有點難在後() {
        assertEquals(List.of(1L, 2L, 3L, 11L, 12L, 13L),
                ExtraPracticeQueue.struggling(stats(), ExtraPracticeQueue.NO_LIMIT));
    }

    /**
     * 這是「只挑 20」真正的重點：額度不夠時要先塞滿「忘了」。
     * 隨機抽樣看起來公平，但使用者會練 20 個字卻半數是已經算記得住的。
     */
    @Test
    public void 額度不夠時先給忘了的字() {
        assertEquals(List.of(1L, 2L, 3L, 11L),
                ExtraPracticeQueue.struggling(stats(), 4));
    }

    @Test
    public void 記得與太簡單不會進加強佇列() {
        List<Long> queue = ExtraPracticeQueue.struggling(stats(), ExtraPracticeQueue.NO_LIMIT);
        assertTrue("記得的字不該被拖進加強複習", !queue.contains(21L) && !queue.contains(22L));
    }

    @Test
    public void 上限大於總數時全部都給() {
        assertEquals(6, ExtraPracticeQueue.struggling(stats(), 20).size());
    }

    @Test
    public void 手動勾選的順序原封不動() {
        assertEquals(List.of(9L, 4L, 7L),
                ExtraPracticeQueue.take(List.of(9L, 4L, 7L), ExtraPracticeQueue.NO_LIMIT));
        assertEquals(List.of(9L, 4L),
                ExtraPracticeQueue.take(List.of(9L, 4L, 7L), 2));
    }
}
