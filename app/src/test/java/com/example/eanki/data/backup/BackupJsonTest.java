package com.example.eanki.data.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** 備份檔的寫出與讀回。這一層決定「還原得回來」是不是真的。 */
public class BackupJsonTest {

    private static BackupSnapshot sample() {
        BackupSnapshot s = new BackupSnapshot();
        s.exportedAt = 1_700_000_000_000L;
        s.appVersion = "1.0";
        s.tags.add(new BackupSnapshot.Tag(1L, "多益"));

        BackupSnapshot.Word w = new BackupSnapshot.Word();
        w.id = 42L;
        w.headword = "account";
        w.phonetic = "ə'kaʊnt";
        w.translation = "v.把…視為\nn.描述";
        w.pos = "n:80/v:20";
        w.source = "IMPORT";
        w.createdAt = 1_690_000_000_000L;
        w.tagIds.add(1L);

        BackupSnapshot.Example e = new BackupSnapshot.Example();
        e.textEn = "He opened an account.";
        e.textZh = "他開了一個帳戶。";
        e.custom = true;
        w.examples.add(e);

        BackupSnapshot.Review r = new BackupSnapshot.Review();
        r.direction = "EN_TO_ZH";
        r.dueDate = 1_790_492_763_994L;
        r.releasedAt = 1_790_060_763_994L;
        r.interval = 5760;
        r.easeFactor = 2.36d;
        r.repetitions = 3;
        r.lapses = 1;
        r.lastReviewed = 1_790_060_763_994L;
        r.grades[0] = 2;
        r.grades[1] = 0;
        r.grades[2] = null;
        w.reviews.add(r);
        s.words.add(w);

        BackupSnapshot.LogEntry log = new BackupSnapshot.LogEntry();
        log.wordId = 42L;
        log.direction = "EN_TO_ZH";
        log.reviewedAt = 1_790_060_763_994L;
        log.grade = 2;
        s.reviewLog.add(log);
        return s;
    }

    @Test
    public void 寫出再讀回_每一個欄位都要一樣() throws Exception {
        BackupSnapshot before = sample();
        BackupSnapshot after = BackupJson.read(BackupJson.write(before));

        assertEquals(1, after.tags.size());
        assertEquals("多益", after.tags.get(0).name);
        assertEquals(1L, after.tags.get(0).id);
        assertEquals(before.exportedAt, after.exportedAt);

        BackupSnapshot.Word w = after.words.get(0);
        assertEquals(42L, w.id);
        assertEquals("account", w.headword);
        assertEquals("ə'kaʊnt", w.phonetic);
        assertEquals("v.把…視為\nn.描述", w.translation);   // 換行不可以被吃掉
        assertEquals("n:80/v:20", w.pos);
        assertEquals("IMPORT", w.source);
        assertEquals(1_690_000_000_000L, w.createdAt);
        assertEquals(java.util.Collections.singletonList(1L), w.tagIds);

        assertEquals("他開了一個帳戶。", w.examples.get(0).textZh);
        assertTrue(w.examples.get(0).custom);

        BackupSnapshot.Review r = w.reviews.get(0);
        assertEquals(1_790_492_763_994L, r.dueDate);
        assertEquals(1_790_060_763_994L, r.releasedAt);
        assertEquals(5760, r.interval);
        assertEquals(2.36d, r.easeFactor, 0.00001d);
        assertEquals(3, r.repetitions);
        assertEquals(1, r.lapses);
        assertEquals(1_790_060_763_994L, r.lastReviewed);

        assertEquals(Integer.valueOf(2), r.grades[0]);
        assertEquals(Integer.valueOf(0), r.grades[1]);
        assertNull("沒評過的那一格必須還是 null，不可以變成 0", r.grades[2]);

        assertEquals(1, after.reviewLog.size());
        assertEquals(42L, after.reviewLog.get(0).wordId);
        assertEquals(2, after.reviewLog.get(0).grade);
    }

    /**
     * 「還沒評過分」與「評了 0（忘了）」是不同的事。
     *
     * <p>JSON 裡把 null 寫成 0，還原後熟悉度就整批變成「忘了」——
     * 使用者會看到一個從沒複習過的字被標成紅的。
     */
    @Test
    public void 沒有評分的格子在檔案裡是null不是0() throws Exception {
        BackupSnapshot s = new BackupSnapshot();
        BackupSnapshot.Word w = new BackupSnapshot.Word();
        w.headword = "fresh";
        BackupSnapshot.Review r = new BackupSnapshot.Review();
        w.reviews.add(r);
        s.words.add(w);

        String json = BackupJson.write(s);
        assertTrue("grades 應該寫成 null", json.contains("null"));

        BackupSnapshot.Review back = BackupJson.read(json).words.get(0).reviews.get(0);
        assertNull(back.grades[0]);
        assertNull(back.grades[1]);
        assertNull(back.grades[2]);
    }

    @Test
    public void 空的單字本也能正常往返() throws Exception {
        BackupSnapshot after = BackupJson.read(BackupJson.write(new BackupSnapshot()));
        assertTrue(after.words.isEmpty());
        assertTrue(after.tags.isEmpty());
        assertTrue(after.reviewLog.isEmpty());
    }

    @Test
    public void 摘要數字算得對() {
        BackupSnapshot.Counts c = sample().counts();
        assertEquals(1, c.words);
        assertEquals(1, c.examples);
        assertEquals(1, c.tags);
        assertEquals(1, c.reviews);
        assertEquals(1, c.logEntries);
        assertEquals(0, c.images);
    }

    // ---- 讀進來的東西一律當成不可信 ----

    @Test
    public void 不是備份檔就要擋下來() {
        for (String junk : new String[]{
                "{}",
                "{\"format\":\"something-else\",\"version\":1}",
                "{\"format\":\"eanki-backup\"}"}) {
            try {
                BackupJson.read(junk);
                fail("應該被擋下來：" + junk);
            } catch (BackupJson.FormatException expected) {
                // 正確
            } catch (Exception e) {
                fail("應該丟 FormatException 而不是 " + e.getClass().getSimpleName());
            }
        }
    }

    /**
     * 版本比這個 App 新就直接拒絕。
     *
     * <p>硬讀會安靜地漏掉新版才有的欄位，使用者會以為還原成功了，
     * 實際上少了一部分資料 —— 而那時原本的資料已經被覆蓋掉。
     */
    @Test
    public void 版本比自己新的備份檔要拒絕() {
        try {
            BackupJson.read("{\"format\":\"eanki-backup\",\"version\":99,\"words\":[]}");
            fail("應該拒絕未來版本");
        } catch (BackupJson.FormatException expected) {
            assertTrue(expected.getMessage().contains("99"));
        } catch (Exception e) {
            fail("應該丟 FormatException");
        }
    }

    @Test
    public void 缺少可選欄位時退回預設值() throws Exception {
        String json = "{\"format\":\"eanki-backup\",\"version\":1,\"words\":["
                + "{\"id\":7,\"headword\":\"bare\"}]}";
        BackupSnapshot.Word w = BackupJson.read(json).words.get(0);
        assertEquals(7L, w.id);
        assertEquals("bare", w.headword);
        assertNull(w.phonetic);
        assertNull(w.translation);
        assertNull(w.imageBase64);
        assertEquals("MANUAL", w.source);
        assertTrue(w.examples.isEmpty());
        assertTrue(w.reviews.isEmpty());
    }

    /** JSONObject.optString 對 JSON null 會回傳字串 "null"，這是踩過的坑。 */
    @Test
    public void JSON的null不可以變成字串null() throws Exception {
        String json = "{\"format\":\"eanki-backup\",\"version\":1,\"words\":["
                + "{\"id\":1,\"headword\":\"x\",\"phonetic\":null}]}";
        assertNull(BackupJson.read(json).words.get(0).phonetic);
    }

    @Test
    public void 輸出要有縮排_人打得開() throws Exception {
        String json = BackupJson.write(sample());
        assertTrue("應該是多行有縮排的", json.contains("\n  "));
        assertTrue(json.contains("\"headword\""));
    }
}
