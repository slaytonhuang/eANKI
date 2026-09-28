package com.example.eanki.data.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** CSV 列 → 匯入資料列的對應。 */
public class ImportRowTest {

    private static List<List<String>> rows(String... lines) {
        List<List<String>> out = new ArrayList<>();
        for (String line : lines) {
            out.add(Arrays.asList(line.split(",", -1)));
        }
        return out;
    }

    @Test
    public void 五個標準欄位都對得上() {
        List<ImportRow> parsed = ImportRow.fromRows(rows(
                "headword,phonetic,meaning_zh,example_en,example_zh",
                "abandon,ə'bændən,v.拋棄,She abandoned it.,她拋棄了它。"));

        assertEquals(1, parsed.size());
        ImportRow row = parsed.get(0);
        assertEquals("abandon", row.headword);
        assertEquals("ə'bændən", row.phonetic);
        assertEquals("v.拋棄", row.meaningZh);
        assertEquals("She abandoned it.", row.exampleEn);
        assertEquals("她拋棄了它。", row.exampleZh);
        assertTrue(row.hasExample());
    }

    /** 照欄名對應而不是欄位順序，否則順序一變就會把音標塞進釋義欄。 */
    @Test
    public void 欄位順序不同也能正確對應() {
        List<ImportRow> parsed = ImportRow.fromRows(rows(
                "meaning_zh,headword,phonetic",
                "v.拋棄,abandon,ə'bændən"));

        ImportRow row = parsed.get(0);
        assertEquals("abandon", row.headword);
        assertEquals("v.拋棄", row.meaningZh);
        assertEquals("ə'bændən", row.phonetic);
    }

    @Test
    public void 欄名大小寫與前後空白不影響對應() {
        List<ImportRow> parsed = ImportRow.fromRows(rows(
                " HeadWord , Meaning_ZH ",
                "abandon,v.拋棄"));
        assertEquals("abandon", parsed.get(0).headword);
        assertEquals("v.拋棄", parsed.get(0).meaningZh);
    }

    /** 這份資料裡就有 1 筆 example_en 是空的，屬正常情況不該報錯。 */
    @Test
    public void 例句欄位留空是允許的() {
        List<ImportRow> parsed = ImportRow.fromRows(rows(
                "headword,phonetic,meaning_zh,example_en,example_zh",
                "bogus,'boʊgəs,a.偽造的,,"));

        ImportRow row = parsed.get(0);
        assertEquals("bogus", row.headword);
        assertNull(row.exampleEn);
        assertNull(row.exampleZh);
        assertFalse("沒有例句就不該建 example 記錄", row.hasExample());
    }

    @Test
    public void 只有英文例句沒有中文翻譯也算有例句() {
        List<ImportRow> parsed = ImportRow.fromRows(rows(
                "headword,example_en,example_zh",
                "test,Only English.,"));
        assertTrue(parsed.get(0).hasExample());
    }

    @Test
    public void 缺少的欄位一律當空值() {
        List<ImportRow> parsed = ImportRow.fromRows(rows(
                "headword",
                "abandon"));

        ImportRow row = parsed.get(0);
        assertEquals("abandon", row.headword);
        assertNull(row.phonetic);
        assertNull(row.meaningZh);
        assertFalse(row.hasExample());
    }

    @Test
    public void headword為空的列直接跳過() {
        List<ImportRow> parsed = ImportRow.fromRows(rows(
                "headword,meaning_zh",
                "abandon,v.拋棄",
                ",孤兒列",
                "  ,只有空白"));
        assertEquals(1, parsed.size());
    }

    /** 沒有 headword 欄的檔案無法匯入，要能明確判斷而不是產生一堆垃圾。 */
    @Test
    public void 標題列沒有headword就回傳空清單() {
        assertTrue(ImportRow.fromRows(rows("word,meaning", "abandon,v.拋棄")).isEmpty());
    }

    @Test
    public void 完全空的輸入() {
        assertTrue(ImportRow.fromRows(Collections.<List<String>>emptyList()).isEmpty());
    }

    @Test
    public void 只有標題列沒有資料() {
        assertTrue(ImportRow.fromRows(rows("headword,phonetic")).isEmpty());
    }

    @Test
    public void 欄位數比標題少也不會爆() {
        List<ImportRow> parsed = ImportRow.fromRows(rows(
                "headword,phonetic,meaning_zh,example_en,example_zh",
                "abandon,ə'bændən"));
        assertEquals("abandon", parsed.get(0).headword);
        assertNull(parsed.get(0).meaningZh);
    }
}
