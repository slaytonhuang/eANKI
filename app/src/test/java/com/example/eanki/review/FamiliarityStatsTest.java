package com.example.eanki.review;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 熟悉度分布。SPEC 第 5 節、第 6.5 節、第 6.6 節。 */
public class FamiliarityStatsTest {

    private static FamiliarityStats.GradeTriple t(long id, Integer g1, Integer g2, Integer g3) {
        return new FamiliarityStats.GradeTriple(id, g1, g2, g3);
    }

    private static List<FamiliarityStats.GradeTriple> repeat(
            long startId, int count, Integer g1, Integer g2, Integer g3) {
        List<FamiliarityStats.GradeTriple> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(t(startId + i, g1, g2, g3));
        }
        return out;
    }

    @Test
    public void 四級各自計數() {
        List<FamiliarityStats.GradeTriple> triples = new ArrayList<>();
        triples.addAll(repeat(1, 3, 0, 0, 0));
        triples.addAll(repeat(10, 5, 1, 1, 1));
        triples.addAll(repeat(20, 7, 2, 2, 2));
        triples.addAll(repeat(30, 2, 3, 3, 3));

        FamiliarityStats stats = FamiliarityStats.of(triples, 100);

        assertEquals(3, stats.count(Grade.FORGOT));
        assertEquals(5, stats.count(Grade.HARD));
        assertEquals(7, stats.count(Grade.GOOD));
        assertEquals(2, stats.count(Grade.EASY));
        assertEquals(17, stats.gradedCount());
    }

    /** SPEC 第 6.5 節：三次全不同時取最差的。 */
    @Test
    public void 平手取較差() {
        FamiliarityStats stats = FamiliarityStats.of(Arrays.asList(
                t(1, 3, 2, 0),        // 三次全不同 → 忘了
                t(2, 3, 2, 1),        // 三次全不同 → 有點難
                t(3, 3, 1, null),     // 兩次打平   → 有點難
                t(4, 2, 2, 0)         // 眾數是記得
        ), 10);

        assertEquals(1, stats.count(Grade.FORGOT));
        assertEquals(2, stats.count(Grade.HARD));
        assertEquals(1, stats.count(Grade.GOOD));
        assertEquals(0, stats.count(Grade.EASY));
    }

    // ---- 尚未開始 ----

    /** 已放行但還沒評分的字，算尚未開始而不是任何一級。 */
    @Test
    public void 已放行但沒評分算尚未開始() {
        FamiliarityStats stats = FamiliarityStats.of(Arrays.asList(
                t(1, null, null, null),
                t(2, null, null, null),
                t(3, 2, 2, 2)
        ), 50);

        assertEquals(1, stats.gradedCount());
        assertEquals("50 個字裡只有 1 個評過分", 49, stats.notStartedCount());
    }

    /** 還沒放行的字根本沒有 review_state 列，要靠總數相減才算得到。 */
    @Test
    public void 沒放行的字也算尚未開始() {
        FamiliarityStats stats = FamiliarityStats.of(
                repeat(1, 126, 2, 2, 2), 1464);

        assertEquals(126, stats.gradedCount());
        assertEquals(1338, stats.notStartedCount());
    }

    @Test
    public void 全部都沒複習過() {
        FamiliarityStats stats = FamiliarityStats.of(
                Collections.<FamiliarityStats.GradeTriple>emptyList(), 1464);

        assertEquals(0, stats.gradedCount());
        assertEquals(1464, stats.notStartedCount());
        for (Grade g : Grade.values()) {
            assertEquals(0, stats.count(g));
        }
    }

    // ---- 不太熟的字 ----

    /** SPEC 第 6.6 節：不太熟 = 忘了 + 有點難。 */
    @Test
    public void 不太熟的字是忘了加有點難() {
        List<FamiliarityStats.GradeTriple> triples = new ArrayList<>();
        triples.addAll(repeat(1, 4, 0, 0, 0));
        triples.addAll(repeat(10, 6, 1, 1, 1));
        triples.addAll(repeat(20, 9, 2, 2, 2));
        triples.addAll(repeat(30, 3, 3, 3, 3));

        FamiliarityStats stats = FamiliarityStats.of(triples, 100);

        assertEquals(10, stats.strugglingCount());
        assertEquals(10, stats.strugglingWordIds().size());
    }

    /**
     * 尚未開始的字<b>絕對不能</b>算進不太熟。
     * 否則按「全部」會把幾千個沒見過的字拖進加強複習，
     * 直接違反 SPEC 第 6.4 節的每日份量控制。
     */
    @Test
    public void 尚未開始的字不算不太熟() {
        List<FamiliarityStats.GradeTriple> triples = new ArrayList<>();
        triples.addAll(repeat(1, 2, 0, 0, 0));
        triples.addAll(repeat(10, 20, null, null, null));   // 已放行未評分

        FamiliarityStats stats = FamiliarityStats.of(triples, 1464);

        assertEquals("只有真的評過『忘了』的才算", 2, stats.strugglingCount());
        assertEquals(1462, stats.notStartedCount());
    }

    // ---- 堆疊條比例 ----

    @Test
    public void 比例的分母是已評分的字不含尚未開始() {
        List<FamiliarityStats.GradeTriple> triples = new ArrayList<>();
        triples.addAll(repeat(1, 25, 0, 0, 0));
        triples.addAll(repeat(100, 25, 1, 1, 1));
        triples.addAll(repeat(200, 25, 2, 2, 2));
        triples.addAll(repeat(300, 25, 3, 3, 3));

        FamiliarityStats stats = FamiliarityStats.of(triples, 10000);

        for (Grade g : Grade.values()) {
            assertEquals("四級各佔四分之一", 0.25f, stats.ratio(g), 0.0001f);
        }
    }

    @Test
    public void 沒有任何評分時比例是零不會除以零() {
        FamiliarityStats stats = FamiliarityStats.of(
                Collections.<FamiliarityStats.GradeTriple>emptyList(), 100);
        for (Grade g : Grade.values()) {
            assertEquals(0f, stats.ratio(g), 0.0001f);
        }
    }

    // ---- 各級的字 ----

    @Test
    public void 可以取出某一級底下的字() {
        FamiliarityStats stats = FamiliarityStats.of(Arrays.asList(
                t(11, 0, 0, 0), t(22, 0, 0, 0), t(33, 3, 3, 3)
        ), 10);

        assertEquals(Arrays.asList(11L, 22L), stats.wordIds(Grade.FORGOT));
        assertEquals(Collections.singletonList(33L), stats.wordIds(Grade.EASY));
        assertTrue(stats.wordIds(Grade.GOOD).isEmpty());
    }
}
