package com.example.eanki.review;

import static com.example.eanki.review.Grade.EASY;
import static com.example.eanki.review.Grade.FORGOT;
import static com.example.eanki.review.Grade.GOOD;
import static com.example.eanki.review.Grade.HARD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * 熟悉度判定的單元測試。SPEC 第 6.5 節：
 * 取最近三次評分的眾數，平手時取較差的那個。
 */
public class FamiliarityRuleTest {

    // ---- 有明確眾數 ----

    @Test
    public void 三次相同() {
        assertEquals(GOOD, FamiliarityRule.of(GOOD, GOOD, GOOD));
        assertEquals(FORGOT, FamiliarityRule.of(FORGOT, FORGOT, FORGOT));
    }

    @Test
    public void 兩次相同就是眾數_不論位置() {
        assertEquals(GOOD, FamiliarityRule.of(GOOD, GOOD, FORGOT));
        assertEquals(GOOD, FamiliarityRule.of(GOOD, FORGOT, GOOD));
        assertEquals(GOOD, FamiliarityRule.of(FORGOT, GOOD, GOOD));
    }

    @Test
    public void 單次手滑不會拉低熟悉度() {
        // SPEC：「不要存最後一次評分，一次手滑就變紅點」
        assertEquals("最新一次答錯，但前兩次都記得，仍應判定為記得",
                GOOD, FamiliarityRule.of(FORGOT, GOOD, GOOD));
    }

    @Test
    public void 單次僥倖不會高估熟悉度() {
        assertEquals("最新一次答對，但前兩次都忘了，仍應判定為忘了",
                FORGOT, FamiliarityRule.of(EASY, FORGOT, FORGOT));
    }

    // ---- 平手取較差 ----

    @Test
    public void 三次全不同_取最差() {
        assertEquals(FORGOT, FamiliarityRule.of(EASY, GOOD, FORGOT));
        assertEquals(FORGOT, FamiliarityRule.of(FORGOT, GOOD, EASY));
        assertEquals(HARD, FamiliarityRule.of(EASY, GOOD, HARD));
    }

    @Test
    public void 只有兩次且不同_取較差() {
        assertEquals(HARD, FamiliarityRule.of(EASY, HARD, null));
        assertEquals(HARD, FamiliarityRule.of(HARD, EASY, null));
        assertEquals(FORGOT, FamiliarityRule.of(GOOD, FORGOT, null));
    }

    // ---- 紀錄不足三次 ----

    @Test
    public void 只複習過一次() {
        assertEquals(EASY, FamiliarityRule.of(EASY, null, null));
        assertEquals(FORGOT, FamiliarityRule.of(FORGOT, null, null));
    }

    @Test
    public void 只複習過兩次且相同() {
        assertEquals(GOOD, FamiliarityRule.of(GOOD, GOOD, null));
    }

    @Test
    public void 從未複習_回傳null() {
        assertNull(FamiliarityRule.of(null, null, null));
    }

    @Test
    public void 中間有空洞也要能算() {
        // 理論上不該發生（滾動儲存會把值往後推），但不該因此壞掉
        assertEquals(GOOD, FamiliarityRule.of(null, GOOD, null));
        assertEquals(HARD, FamiliarityRule.of(null, null, HARD));
    }

    // ---- 從資料庫的 code 判定 ----

    @Test
    public void 由資料庫欄位判定() {
        assertEquals(GOOD, FamiliarityRule.ofCodes(
                GOOD.code(), GOOD.code(), FORGOT.code()));
        assertNull(FamiliarityRule.ofCodes(null, null, null));
        assertEquals(FORGOT, FamiliarityRule.ofCodes(FORGOT.code(), null, null));
    }

    // ---- Grade 本身 ----

    @Test
    public void code與enum可互轉() {
        for (Grade g : Grade.values()) {
            assertEquals(g, Grade.fromCode(g.code()));
        }
    }

    @Test
    public void 取較差的() {
        assertEquals(FORGOT, Grade.worse(FORGOT, EASY));
        assertEquals(FORGOT, Grade.worse(EASY, FORGOT));
        assertEquals(HARD, Grade.worse(HARD, GOOD));
        assertEquals(GOOD, Grade.worse(GOOD, GOOD));
    }

    @Test(expected = IllegalArgumentException.class)
    public void 未知的code要丟例外() {
        Grade.fromCode(99);
    }
}
