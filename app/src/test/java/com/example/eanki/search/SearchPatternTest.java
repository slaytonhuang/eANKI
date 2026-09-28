package com.example.eanki.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 搜尋樣式與跳脫。SPEC 第 5 節單字本的搜尋。 */
public class SearchPatternTest {

    @Test
    public void 一般關鍵字前後包萬用字元() {
        assertEquals("%run%", SearchPattern.contains("run"));
        assertEquals("run%", SearchPattern.prefix("run"));
        assertEquals("run", SearchPattern.exact("run"));
    }

    @Test
    public void 空白與null都當作沒有關鍵字() {
        for (String raw : new String[]{null, "", "   ", "\t"}) {
            assertEquals(SearchPattern.MATCH_ALL, SearchPattern.contains(raw));
            assertEquals(SearchPattern.MATCH_ALL, SearchPattern.prefix(raw));
            assertEquals("", SearchPattern.exact(raw));
            assertTrue(SearchPattern.isBlank(raw));
        }
        assertFalse(SearchPattern.isBlank(" run "));
    }

    /**
     * 這是不跳脫就會壞掉的地方：{@code _} 在 LIKE 裡代表任一字元，
     * 使用者打一個底線就會match到所有字，看起來像「搜尋壞掉了」。
     */
    @Test
    public void 底線要跳脫否則會變成萬用字元() {
        assertEquals("%a\\_c%", SearchPattern.contains("a_c"));
    }

    @Test
    public void 百分比要跳脫否則會match全部() {
        assertEquals("%50\\%%", SearchPattern.contains("50%"));
    }

    /** 跳脫字元本身也要跳脫，否則使用者打反斜線會把後面那個字吃掉。 */
    @Test
    public void 反斜線本身也要跳脫() {
        assertEquals("%a\\\\b%", SearchPattern.contains("a\\b"));
    }

    @Test
    public void 前後空白會被去掉() {
        assertEquals("%run%", SearchPattern.contains("  run  "));
        assertEquals("run", SearchPattern.exact("  run  "));
    }

    /**
     * SQLite 的 {@code =} 有分大小寫（{@code LIKE} 沒有），
     * 而 headword 一律小寫存。使用者打 Run 也該被當成完全命中。
     */
    @Test
    public void 完全比對用的值要轉小寫() {
        assertEquals("run", SearchPattern.exact("Run"));
        assertEquals("run", SearchPattern.exact("RUN"));
    }

    /** 中文不含萬用字元，原樣包起來即可。 */
    @Test
    public void 中文關鍵字原樣包起來() {
        assertEquals("%拋棄%", SearchPattern.contains("拋棄"));
    }
}
