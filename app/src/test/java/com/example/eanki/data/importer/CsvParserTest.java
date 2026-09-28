package com.example.eanki.data.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** CSV 解析。純 Java，跑在 JVM 上。 */
public class CsvParserTest {

    private static List<List<String>> parse(String csv) throws IOException {
        return CsvParser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void 基本的逗號分隔() throws Exception {
        List<List<String>> rows = parse("a,b,c\n1,2,3\n");
        assertEquals(2, rows.size());
        assertEquals(Arrays.asList("a", "b", "c"), rows.get(0));
        assertEquals(Arrays.asList("1", "2", "3"), rows.get(1));
    }

    /**
     * 這是不能用 split(",") 的原因：例句裡有逗號是常態，
     * split 會把一筆資料切成好幾欄，而且錯得很安靜。
     */
    @Test
    public void 引號裡的逗號不算分隔() throws Exception {
        List<List<String>> rows = parse("headword,example\nsay,\"He said, it works.\"\n");
        assertEquals(2, rows.get(1).size());
        assertEquals("say", rows.get(1).get(0));
        assertEquals("He said, it works.", rows.get(1).get(1));
    }

    @Test
    public void 引號裡的換行不算換列() throws Exception {
        List<List<String>> rows = parse("a,b\n1,\"line1\nline2\"\n");
        assertEquals(2, rows.size());
        assertEquals("line1\nline2", rows.get(1).get(1));
    }

    @Test
    public void 連續兩個雙引號代表一個字面雙引號() throws Exception {
        List<List<String>> rows = parse("a\n\"He said \"\"hi\"\".\"\n");
        assertEquals("He said \"hi\".", rows.get(1).get(0));
    }

    @Test
    public void CRLF與LF都能處理() throws Exception {
        assertEquals(2, parse("a,b\r\n1,2\r\n").size());
        assertEquals(2, parse("a,b\n1,2\n").size());
    }

    /** Excel 存出來的 UTF-8 常常帶 BOM，不吃掉的話第一個欄名會對不上。 */
    @Test
    public void 吃掉UTF8的BOM() throws Exception {
        List<List<String>> rows = parse("﻿headword,phonetic\nabandon,x\n");
        assertEquals("headword", rows.get(0).get(0));
    }

    @Test
    public void 最後一列沒有換行也算一列() throws Exception {
        List<List<String>> rows = parse("a,b\n1,2");
        assertEquals(2, rows.size());
        assertEquals(Arrays.asList("1", "2"), rows.get(1));
    }

    @Test
    public void 空欄位保留成空字串() throws Exception {
        List<List<String>> rows = parse("a,b,c\n1,,3\n");
        assertEquals(Arrays.asList("1", "", "3"), rows.get(1));
    }

    @Test
    public void 中文內容不會被破壞() throws Exception {
        List<List<String>> rows = parse("headword,meaning_zh\nabandon,v.拋棄，放棄\n");
        assertEquals("v.拋棄，放棄", rows.get(1).get(1));
    }

    /** 全形逗號「，」在中文釋義裡很常見，不可以被當成欄位分隔。 */
    @Test
    public void 全形逗號不是分隔符() throws Exception {
        List<List<String>> rows = parse("a,b\n因為貧窮，她拋棄了孩子。,x\n");
        assertEquals(2, rows.get(1).size());
        assertEquals("因為貧窮，她拋棄了孩子。", rows.get(1).get(0));
    }

    @Test
    public void 空輸入回傳空清單() throws Exception {
        assertTrue(parse("").isEmpty());
    }
}
