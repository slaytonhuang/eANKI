package com.example.eanki.reminder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.work.ListenableWorker;
import androidx.work.testing.TestWorkerBuilder;

import com.example.eanki.data.ReviewRepository;
import com.example.eanki.data.user.Direction;
import com.example.eanki.data.user.ReviewState;
import com.example.eanki.data.user.ReviewStateDao;
import com.example.eanki.data.user.Source;
import com.example.eanki.data.user.UserDatabase;
import com.example.eanki.data.user.WordDao;
import com.example.eanki.data.user.WordEntry;
import com.example.eanki.review.DailyLimits;

import com.example.eanki.data.WordRepository;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 每日提醒 Worker 的行為。
 *
 * <p>重點是 SPEC 第 6.7 節那條：<b>今天沒有待複習的字就不發通知。</b>
 * 這裡驗的是「決定要不要發」的那個數字，因為通知本身是否真的出現在
 * 系統列上取決於使用者的權限設定，不適合當斷言。
 */
@RunWith(AndroidJUnit4.class)
public class ReminderWorkerTest {

    private Context context;
    private Executor executor;
    private UserDatabase db;
    private WordDao wordDao;
    private ReviewStateDao reviewDao;

    /**
     * <b>用 in-memory 資料庫，不碰正式資料。</b>
     *
     * <p>這裡原本直接對正式資料庫呼叫 {@code clearAllTables()}，
     * 結果每跑一次測試就把使用者匯入的單字清光 —— 真的發生過一次。
     * Worker 內部走 {@code ReviewRepository} 單例，所以要先把
     * {@code UserDatabase} 的單例換掉、再重置 repository，它才會接到 in-memory 那個。
     */
    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        executor = Executors.newSingleThreadExecutor();

        db = Room.inMemoryDatabaseBuilder(context, UserDatabase.class)
                .allowMainThreadQueries()
                .build();
        UserDatabase.overrideForTest(db);
        ReviewRepository.resetForTest();
        WordRepository.resetForTest();

        wordDao = db.wordDao();
        reviewDao = db.reviewStateDao();
    }

    @After
    public void tearDown() {
        // 還原，否則之後的測試與 App 本身會繼續用這個已關閉的 in-memory 資料庫
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

    private void addDueCard(String headword) {
        long id = addWord(headword);
        ReviewState state = new ReviewState();
        state.wordId = id;
        state.direction = Direction.EN_TO_ZH;
        state.releasedAt = System.currentTimeMillis() - 86400_000L * 5;
        state.dueDate = System.currentTimeMillis() - 1000;
        state.lastReviewed = System.currentTimeMillis() - 86400_000L * 2;
        state.repetitions = 1;
        state.interval = 5760;
        reviewDao.upsert(state);
    }

    private ListenableWorker.Result runWorker() {
        ReminderWorker worker = TestWorkerBuilder.from(context, ReminderWorker.class, executor)
                .build();
        return worker.doWork();
    }

    private int previewCount() {
        return ReviewRepository.get(context).previewTodayCount(
                Direction.EN_TO_ZH, DailyLimits.defaults(), System.currentTimeMillis());
    }

    // ---------------------------------------------------------------

    /** SPEC 第 6.7 節：完全沒有單字時不該有東西可提醒。 */
    @Test
    public void 單字本是空的_不發通知() {
        assertEquals("沒有任何字，待複習數必須是 0", 0, previewCount());
        assertTrue(runWorker() instanceof ListenableWorker.Result.Success);
    }

    /** 有字，但今天都不到期、而且新字額度已用完 → 一樣不該提醒。 */
    @Test
    public void 有單字但今天沒有到期的_不發通知() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < 12; i++) {
            long id = addWord("done" + i);
            ReviewState state = new ReviewState();
            state.wordId = id;
            state.direction = Direction.EN_TO_ZH;
            state.releasedAt = now - 3600_000L;      // 今天放行
            state.lastReviewed = now - 1800_000L;    // 今天已複習
            state.dueDate = now + 86400_000L * 4;    // 排到 4 天後
            state.repetitions = 1;
            state.interval = 5760;
            reviewDao.upsert(state);
        }

        assertEquals("今天的份量做完了，不該再提醒", 0, previewCount());
        assertTrue(runWorker() instanceof ListenableWorker.Result.Success);
    }

    @Test
    public void 有到期的字_數量要正確() {
        for (int i = 0; i < 7; i++) {
            addDueCard("due" + i);
        }
        assertEquals(7, previewCount());
        assertTrue(runWorker() instanceof ListenableWorker.Result.Success);
    }

    /** 還沒放行的新字也算今天的份量，但要受新字上限約束。 */
    @Test
    public void 只有新字時_數量受新字上限約束() {
        for (int i = 0; i < 25; i++) {
            addWord("new" + i);
        }
        assertEquals("25 個新字，今天只放行 10 個", 10, previewCount());
    }

    /**
     * <b>Worker 絕對不可以寫資料。</b>
     *
     * <p>如果提醒去呼叫 startSession，新字會在半夜被背景任務放行掉，
     * 使用者早上打開 App 反而沒有新字可學 —— 額度被通知吃掉了。
     */
    @Test
    public void 跑完通知不可以放行任何新字() {
        for (int i = 0; i < 25; i++) {
            addWord("new" + i);
        }
        int before = reviewDao.findUnreleasedWordIds(Direction.EN_TO_ZH).size();
        assertEquals(25, before);

        runWorker();

        assertEquals("Worker 不該建立任何 review_state",
                before, reviewDao.findUnreleasedWordIds(Direction.EN_TO_ZH).size());
        assertEquals("今天放行數必須還是 0", 0,
                reviewDao.countReleasedSince(Direction.EN_TO_ZH,
                        System.currentTimeMillis() - 86400_000L));
    }

    @Test
    public void worker永遠回報成功_失敗也不該讓系統停掉排程() {
        assertTrue(runWorker() instanceof ListenableWorker.Result.Success);
    }
}
