package com.example.eanki.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.eanki.data.user.Direction;
import com.example.eanki.data.user.ReviewState;
import com.example.eanki.data.user.ReviewStateDao;
import com.example.eanki.data.user.Source;
import com.example.eanki.data.user.UserDatabase;
import com.example.eanki.data.user.WordDao;
import com.example.eanki.data.user.WordEntry;
import com.example.eanki.review.Grade;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

/**
 * 加強複習<b>絕不動排程</b>的迴歸測試。SPEC 第 6.6 節。
 *
 * <h2>為什麼這條測試要存在</h2>
 *
 * 純 JVM 那邊已經有一條釘住 {@code ReviewStateMapper.applyExtraPractice} 本身，
 * 但真正會出事的是中間那段路：{@code find → apply → update}。
 * 只要有人把 {@code update()} 手滑寫成 {@code grade()}、或是日後在
 * {@link ReviewRepository#gradeExtraPractice} 裡「順手」補一行
 * {@code state.dueDate = now}，SPEC 第 6.6 節警告的事就會發生 ——
 * 這批字每練一次排程就往前擠，間隔越練越短，最後每天都在複習同一批字。
 *
 * <p>那種退化在畫面上完全看不出來（熟悉度確實有更新），
 * 要等好幾天後使用者發現「怎麼每天都是這些字」才會察覺。
 *
 * <h2>資料安全</h2>
 *
 * 用 in-memory 資料庫，並在 {@code @After} 還原 override ——
 * 直接對真正的 {@code UserDatabase} 做測試曾經把使用者匯入的 1,464 筆單字清光。
 */
@RunWith(AndroidJUnit4.class)
public class ExtraPracticeRepositoryTest {

    private UserDatabase db;
    private WordDao wordDao;
    private ReviewStateDao reviewDao;
    private ReviewRepository repository;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, UserDatabase.class)
                .allowMainThreadQueries()
                .build();
        UserDatabase.overrideForTest(db);
        ReviewRepository.resetForTest();
        WordRepository.resetForTest();

        wordDao = db.wordDao();
        reviewDao = db.reviewStateDao();
        repository = ReviewRepository.get(context);
    }

    @After
    public void tearDown() {
        UserDatabase.overrideForTest(null);
        ReviewRepository.resetForTest();
        WordRepository.resetForTest();
        db.close();
    }

    private long addWord(String headword) {
        WordEntry w = new WordEntry();
        w.headword = headword;
        w.source = Source.MANUAL;
        w.createdAt = System.currentTimeMillis();
        return wordDao.insert(w);
    }

    /** 一張已經複習過幾輪、排程排到四天後的卡。 */
    private ReviewState settledCard(long wordId, long dueIn4Days, long lastReviewed) {
        ReviewState state = new ReviewState();
        state.wordId = wordId;
        state.direction = Direction.EN_TO_ZH;
        state.dueDate = dueIn4Days;
        state.releasedAt = lastReviewed;
        state.lastReviewed = lastReviewed;
        state.interval = 5760;          // 4 天
        state.easeFactor = 2.36d;
        state.repetitions = 3;
        state.lapses = 1;
        state.grade1 = Grade.HARD.code();
        state.grade2 = Grade.FORGOT.code();
        state.grade3 = Grade.HARD.code();
        reviewDao.upsert(state);
        return state;
    }

    // ---------------------------------------------------------------

    @Test
    public void 加強複習評分後_六個排程欄位一個都不能變() {
        long now = System.currentTimeMillis();
        long due = now + TimeUnit.DAYS.toMillis(4);
        long lastReviewed = now - TimeUnit.DAYS.toMillis(1);

        long wordId = addWord("acquisition");
        ReviewState before = settledCard(wordId, due, lastReviewed);

        repository.gradeExtraPractice(wordId, Direction.EN_TO_ZH, Grade.EASY);

        ReviewState after = reviewDao.find(wordId, Direction.EN_TO_ZH);
        assertEquals("due_date 被動了 —— 這正是 SPEC 第 6.6 節禁止的事",
                before.dueDate, after.dueDate);
        assertEquals("interval 被動了", before.interval, after.interval);
        assertEquals("ease_factor 被動了", before.easeFactor, after.easeFactor, 0.0001d);
        assertEquals("repetitions 被動了", before.repetitions, after.repetitions);
        assertEquals("lapses 被動了", before.lapses, after.lapses);
        assertEquals("last_reviewed 被動了", before.lastReviewed, after.lastReviewed);
    }

    @Test
    public void 加強複習評分後_熟悉度三格要往後滾一格() {
        long now = System.currentTimeMillis();
        long wordId = addWord("acquisition");
        settledCard(wordId, now + TimeUnit.DAYS.toMillis(4), now);

        // 原本是 HARD / FORGOT / HARD
        repository.gradeExtraPractice(wordId, Direction.EN_TO_ZH, Grade.GOOD);

        ReviewState after = reviewDao.find(wordId, Direction.EN_TO_ZH);
        assertEquals(Integer.valueOf(Grade.GOOD.code()), after.grade1);
        assertEquals(Integer.valueOf(Grade.HARD.code()), after.grade2);
        assertEquals(Integer.valueOf(Grade.FORGOT.code()), after.grade3);
    }

    /**
     * 連練十輪也不能推進排程。
     *
     * <p>單輪不動很容易做到，會出事的是「每輪推一點」——
     * 一輪看不出來，十輪之後間隔就崩了。
     */
    @Test
    public void 連練十輪之後排程依然原地不動() {
        long now = System.currentTimeMillis();
        long due = now + TimeUnit.DAYS.toMillis(4);
        long wordId = addWord("acquisition");
        settledCard(wordId, due, now);

        for (int i = 0; i < 10; i++) {
            repository.gradeExtraPractice(wordId, Direction.EN_TO_ZH, Grade.EASY);
        }

        ReviewState after = reviewDao.find(wordId, Direction.EN_TO_ZH);
        assertEquals(due, after.dueDate);
        assertEquals(5760, after.interval);
        assertEquals(3, after.repetitions);
    }

    /**
     * 熱力圖餵的 {@code review_log} 不可以被加強複習寫入。
     *
     * <p>熱力圖回答的是「我有沒有維持每天的固定份量」（SPEC 第二原則），
     * 而加強複習明確站在每日份量系統之外。混進去的話，某天只做了加強複習、
     * 排程佇列一張沒動，熱力圖卻會顯示滿格。
     */
    @Test
    public void 加強複習不寫複習流水紀錄() {
        long now = System.currentTimeMillis();
        long wordId = addWord("acquisition");
        settledCard(wordId, now + TimeUnit.DAYS.toMillis(4), now);

        repository.gradeExtraPractice(wordId, Direction.EN_TO_ZH, Grade.GOOD);

        assertEquals(0, db.reviewLogDao().countAll());
    }

    /** 沒放行過的字（沒有 review_state）不該被憑空建出排程狀態。 */
    @Test
    public void 未放行的字進到加強複習只會被略過() {
        long wordId = addWord("neverreleased");

        repository.gradeExtraPractice(wordId, Direction.EN_TO_ZH, Grade.GOOD);

        assertNull(reviewDao.find(wordId, Direction.EN_TO_ZH));
    }
}
