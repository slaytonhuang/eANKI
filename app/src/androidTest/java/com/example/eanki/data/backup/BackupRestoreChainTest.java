package com.example.eanki.data.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.eanki.data.BackupRepository;
import com.example.eanki.data.ReviewRepository;
import com.example.eanki.data.WordRepository;
import com.example.eanki.data.user.Direction;
import com.example.eanki.data.user.ExampleSentence;
import com.example.eanki.data.user.ReviewLog;
import com.example.eanki.data.user.ReviewState;
import com.example.eanki.data.user.Source;
import com.example.eanki.data.user.UserDatabase;
import com.example.eanki.data.user.WordEntry;
import com.example.eanki.data.user.WordTag;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 整條備份鏈：匯出 → 資料被清空 → 還原 → 確認回到原樣。
 *
 * <p><b>這條測試模擬的是實際發生過的事故</b>：instrumented test 對正式資料庫
 * 呼叫 {@code clearAllTables()}，1,464 筆單字連同幾個月的複習排程一起消失。
 * 當時只能重新匯入原始 CSV，救回來的是一本沒有排程也沒有熟悉度的單字本。
 *
 * <p>所以驗的不只是「字還在」，而是<b>排程與熟悉度是不是一模一樣</b>——
 * 那才是 CSV 救不回來的部分。
 */
@RunWith(AndroidJUnit4.class)
public class BackupRestoreChainTest {

    private UserDatabase db;
    private BackupRepository backups;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, UserDatabase.class)
                .allowMainThreadQueries()
                .build();
        UserDatabase.overrideForTest(db);
        ReviewRepository.resetForTest();
        WordRepository.resetForTest();
        BackupRepository.resetForTest();
        backups = BackupRepository.get(context);
    }

    @After
    public void tearDown() {
        UserDatabase.overrideForTest(null);
        ReviewRepository.resetForTest();
        WordRepository.resetForTest();
        BackupRepository.resetForTest();
        db.close();
    }

    // ---- 佈置一份有排程、有熟悉度、有例句、有標籤的資料 ----

    private long addWord(String headword, String translation) {
        WordEntry w = new WordEntry();
        w.headword = headword;
        w.translation = translation;
        w.phonetic = "ˈtest";
        w.pos = "n:100";
        w.source = Source.IMPORT;
        w.createdAt = 1_690_000_000_000L + headword.hashCode();
        return db.wordDao().insert(w);
    }

    private void addSchedule(long wordId, int interval, Integer g1, Integer g2, Integer g3) {
        ReviewState s = new ReviewState();
        s.wordId = wordId;
        s.direction = Direction.EN_TO_ZH;
        s.dueDate = 1_790_492_763_994L;
        s.releasedAt = 1_790_060_763_994L;
        s.interval = interval;
        s.easeFactor = 2.36d;
        s.repetitions = 3;
        s.lapses = 1;
        s.lastReviewed = 1_790_060_763_994L;
        s.grade1 = g1;
        s.grade2 = g2;
        s.grade3 = g3;
        db.reviewStateDao().upsert(s);
    }

    private void seed() {
        long toeic = db.tagDao().getOrCreate("多益");

        long a = addWord("account", "v.把…視為\nn.描述");
        long b = addWord("abandon", "v.拋棄，放棄");
        long c = addWord("fresh", "a.新鮮的");      // 沒排程，代表還沒開始

        addSchedule(a, 5760, 2, 0, 1);
        addSchedule(b, 10, 0, 0, null);              // 只評過兩次

        ExampleSentence e = new ExampleSentence();
        e.wordId = a;
        e.textEn = "He opened an account.";
        e.textZh = "他開了一個帳戶。";
        e.isCustom = true;
        db.exampleDao().insertAll(java.util.Collections.singletonList(e));

        for (long id : new long[]{a, b, c}) {
            WordTag link = new WordTag();
            link.wordId = id;
            link.tagId = toeic;
            db.tagDao().link(link);
        }

        ReviewLog log = new ReviewLog();
        log.wordId = a;
        log.direction = Direction.EN_TO_ZH;
        log.reviewedAt = 1_790_060_763_994L;
        log.grade = 2;
        db.reviewLogDao().insert(log);
    }

    private String exportToString() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        backups.export(out, "test");
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private BackupRepository.RestoreResult restoreFrom(String json) {
        return backups.restore(new ByteArrayInputStream(
                json.getBytes(StandardCharsets.UTF_8)));
    }

    /** 模擬事故：把使用者資料整個清掉。 */
    private void wipeEverything() {
        db.clearAllTables();
        assertEquals("清空後應該一個字都不剩", 0, db.wordDao().findAll().size());
    }

    // ---------------------------------------------------------------

    @Test
    public void 匯出_清空_還原_排程與熟悉度都要回來() throws Exception {
        seed();
        String backup = exportToString();

        wipeEverything();

        BackupRepository.RestoreResult result = restoreFrom(backup);
        assertTrue("還原應該成功：" + result.error, result.success);
        assertEquals(3, result.counts.words);

        List<WordEntry> words = db.wordDao().findAll();
        assertEquals(3, words.size());

        WordEntry account = db.wordDao().findByHeadword("account");
        assertNotNull(account);
        assertEquals("v.把…視為\nn.描述", account.translation);
        assertEquals("ˈtest", account.phonetic);
        assertEquals("n:100", account.pos);
        assertEquals(Source.IMPORT, account.source);

        // 這幾個欄位正是重新匯入 CSV 救不回來的東西
        ReviewState s = db.reviewStateDao().find(account.id, Direction.EN_TO_ZH);
        assertNotNull("排程必須跟著回來", s);
        assertEquals(1_790_492_763_994L, s.dueDate);
        assertEquals(1_790_060_763_994L, s.releasedAt);
        assertEquals(5760, s.interval);
        assertEquals(2.36d, s.easeFactor, 0.00001d);
        assertEquals(3, s.repetitions);
        assertEquals(1, s.lapses);
        assertEquals(1_790_060_763_994L, s.lastReviewed);
        assertEquals(Integer.valueOf(2), s.grade1);
        assertEquals(Integer.valueOf(0), s.grade2);
        assertEquals(Integer.valueOf(1), s.grade3);

        // 只評過兩次的那個字，第三格必須還是 null 而不是 0（0 是「忘了」）
        WordEntry abandon = db.wordDao().findByHeadword("abandon");
        ReviewState partial = db.reviewStateDao().find(abandon.id, Direction.EN_TO_ZH);
        assertEquals(Integer.valueOf(0), partial.grade1);
        assertEquals(Integer.valueOf(0), partial.grade2);
        assertNull("沒評過的那一次不可以變成「忘了」", partial.grade3);

        // 從沒放行過的字，還原後也不該憑空多出排程
        WordEntry fresh = db.wordDao().findByHeadword("fresh");
        assertNull(db.reviewStateDao().find(fresh.id, Direction.EN_TO_ZH));

        assertEquals(1, db.exampleDao().findByWord(account.id).size());
        assertEquals("他開了一個帳戶。", db.exampleDao().findByWord(account.id).get(0).textZh);

        assertEquals(1, db.tagDao().findAll().size());
        assertEquals("多益", db.tagDao().findAll().get(0).name);
        assertEquals(3, db.tagDao().findAllLinks().size());

        assertEquals("熱力圖的流水紀錄也要回來", 1, db.reviewLogDao().countAll());
    }

    /**
     * 單字 id 必須原樣保留。
     *
     * <p>重新編號的話 example / word_tag / review_state 會全部指到別的字上，
     * 而那種錯誤在畫面上只會顯示成「例句串到隔壁的單字去了」。
     */
    @Test
    public void 還原後單字id不變() throws Exception {
        seed();
        long before = db.wordDao().findByHeadword("account").id;
        String backup = exportToString();

        wipeEverything();
        restoreFrom(backup);

        assertEquals(before, db.wordDao().findByHeadword("account").id);
    }

    /** 還原是整批取代：備份之後新增的字不該留下來。 */
    @Test
    public void 還原是整批取代_不是合併() throws Exception {
        seed();
        String backup = exportToString();

        addWord("afterwards", "之後才加的字");
        assertEquals(4, db.wordDao().findAll().size());

        restoreFrom(backup);

        assertEquals(3, db.wordDao().findAll().size());
        assertNull(db.wordDao().findByHeadword("afterwards"));
    }

    /**
     * 壞掉的備份檔<b>不可以先把現有資料清掉</b>。
     *
     * <p>先清空再發現檔案讀不開，使用者就同時失去了原本的資料與備份的資料。
     */
    @Test
    public void 備份檔壞掉時現有資料必須完好() {
        seed();
        int before = db.wordDao().findAll().size();

        for (String junk : new String[]{"", "not json at all", "{}",
                "{\"format\":\"eanki-backup\",\"version\":99}"}) {
            BackupRepository.RestoreResult r = restoreFrom(junk);
            org.junit.Assert.assertFalse("應該失敗：" + junk, r.success);
            assertEquals("失敗時資料必須原封不動", before, db.wordDao().findAll().size());
        }
    }

    @Test
    public void 先預覽不會動到任何東西() throws Exception {
        seed();
        String backup = exportToString();
        wipeEverything();

        BackupRepository.RestoreResult preview = backups.preview(
                new ByteArrayInputStream(backup.getBytes(StandardCharsets.UTF_8)));

        assertTrue(preview.success);
        assertEquals(3, preview.counts.words);
        assertEquals(2, preview.counts.reviews);
        assertEquals("預覽不可以寫入", 0, db.wordDao().findAll().size());
    }

    /** 空的單字本匯出再還原不該爆掉。 */
    @Test
    public void 空的單字本也能匯出與還原() throws Exception {
        String backup = exportToString();
        BackupRepository.RestoreResult r = restoreFrom(backup);
        assertTrue(r.success);
        assertEquals(0, db.wordDao().findAll().size());
    }

    /** 同一份資料連續還原兩次要得到一樣的結果（不會因為唯一索引而炸掉）。 */
    @Test
    public void 連續還原兩次結果一致() throws Exception {
        seed();
        String backup = exportToString();

        assertTrue(restoreFrom(backup).success);
        assertTrue(restoreFrom(backup).success);
        assertEquals(3, db.wordDao().findAll().size());
        assertEquals(1, db.tagDao().findAll().size());
        assertEquals(3, db.tagDao().findAllLinks().size());
    }
}
