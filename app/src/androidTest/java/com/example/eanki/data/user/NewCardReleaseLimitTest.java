package com.example.eanki.data.user;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.eanki.review.DailyLimits;
import com.example.eanki.review.Grade;
import com.example.eanki.review.ReviewQueuePlanner;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 新字每日上限的迴歸測試。<b>這條測試是為了釘死一個踩過的坑。</b>
 *
 * <h2>坑長什麼樣</h2>
 *
 * SPEC 第 6.4 節要求「每天只放行 10 個新字」。要做到這件事，必須知道
 * 「今天已經放行幾個」。第一版想用 {@code last_reviewed == 0} 當作
 * 「今天放行、還沒複習」的代理指標，但這是錯的：
 *
 * <p><b>使用者當天就把放行的新字複習完的話，{@code last_reviewed} 會被填上，
 * 那 10 張卡就從計數裡消失，於是今天又能再放行 10 個，上限形同虛設。</b>
 * 一個勤奮的使用者可以在一天內無限放行新字，隔天佇列直接崩掉 ——
 * 正是 SPEC 第 6.4 節想避免的事。
 *
 * <p>所以 {@code review_state} 多了 {@code released_at} 欄位，
 * 計數改看放行時間而不是複習狀態。
 *
 * <h2>為什麼是 instrumented test</h2>
 *
 * 這個坑在 DAO 的 SQL 裡，不在 Java 邏輯裡，所以純 JVM 測試抓不到。
 * 這裡用 Room 的 in-memory 資料庫跑真正的 SQLite。
 */
@RunWith(AndroidJUnit4.class)
public class NewCardReleaseLimitTest {

    private static final long DAY = TimeUnit.DAYS.toMillis(1);

    private UserDatabase db;
    private WordDao wordDao;
    private ReviewStateDao reviewDao;

    /** 固定「現在」為當地時間今天的正午，避免測試在午夜前後跑到時炸掉。 */
    private long now;
    private long startOfDay;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, UserDatabase.class)
                .allowMainThreadQueries()
                .build();
        wordDao = db.wordDao();
        reviewDao = db.reviewStateDao();

        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, 12);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        now = c.getTimeInMillis();
        startOfDay = now - TimeUnit.HOURS.toMillis(12);
    }

    @After
    public void tearDown() {
        db.close();
    }

    private long addWord(String headword, long createdAt) {
        WordEntry w = new WordEntry();
        w.headword = headword;
        w.source = Source.MANUAL;
        w.createdAt = createdAt;
        return wordDao.insert(w);
    }

    /** 放行一張新字：建立 review_state，尚未複習。 */
    private ReviewState release(long wordId, long releasedAt) {
        ReviewState state = new ReviewState();
        state.wordId = wordId;
        state.direction = Direction.EN_TO_ZH;
        state.releasedAt = releasedAt;
        state.dueDate = releasedAt;
        reviewDao.upsert(state);
        return state;
    }

    // ---------------------------------------------------------------
    // 核心迴歸測試
    // ---------------------------------------------------------------

    /**
     * 今天放行 10 個新字，而且使用者把這 10 個字全部複習完一輪。
     * 這時候「今天還能放行幾個」必須是 0。
     */
    @Test
    public void 今天放行十個並全部複習完之後_不可以再放行() {
        // 10 個今天放行的新字
        for (int i = 0; i < 10; i++) {
            long id = addWord("released" + i, now - 2 * DAY);
            ReviewState state = release(id, now - TimeUnit.HOURS.toMillis(2));

            // 使用者當天就把它複習完了 —— last_reviewed 被填上
            ReviewStateMapper.applyReview(state, Grade.GOOD, now - TimeUnit.HOURS.toMillis(1));
            reviewDao.upsert(state);
        }

        // 另外還有 15 個沒放行的新字等著，確保「有東西可以放行」
        for (int i = 0; i < 15; i++) {
            addWord("waiting" + i, now);
        }

        // 前提確認：這 10 張的 last_reviewed 都已經有值了
        assertEquals("前提：10 張卡都已經複習過", 10, countReviewed());

        // 關鍵斷言：放行計數看的是 released_at，不是複習狀態
        int releasedToday = reviewDao.countReleasedSince(Direction.EN_TO_ZH, startOfDay);
        assertEquals("今天已放行的張數不可因為『複習完了』而歸零",
                10, releasedToday);

        // 而且沒有任何一張到期（都排到 4 天後了）
        List<Long> due = reviewDao.findDueWordIds(Direction.EN_TO_ZH, now);
        assertTrue("這些卡已排到 4 天後，今天不該到期", due.isEmpty());

        List<Long> unreleased = reviewDao.findUnreleasedWordIds(Direction.EN_TO_ZH);
        assertEquals("還有 15 個新字在等", 15, unreleased.size());

        // 端到端：佇列必須是空的
        ReviewQueuePlanner.Plan plan = ReviewQueuePlanner.plan(
                due, unreleased, releasedToday, DailyLimits.defaults());

        assertEquals("今天的額度用完了，不可以再放行新字",
                0, plan.newlyReleasedWordIds.size());
        assertTrue("佇列應該是空的", plan.isEmpty());
    }

    /**
     * 上面那條的對照組：如果計數用的是 {@code last_reviewed == 0}
     * （也就是修正前的錯誤做法），會得到什麼結果。
     *
     * <p>這條測試不是在測產品程式碼，而是把「為什麼不能那樣做」寫下來 ——
     * 讓之後想「簡化掉 released_at」的人先看到這個數字。
     */
    @Test
    public void 對照組_用last_reviewed當指標會讓上限失效() {
        for (int i = 0; i < 10; i++) {
            long id = addWord("released" + i, now - 2 * DAY);
            ReviewState state = release(id, now - TimeUnit.HOURS.toMillis(2));
            ReviewStateMapper.applyReview(state, Grade.GOOD, now - TimeUnit.HOURS.toMillis(1));
            reviewDao.upsert(state);
        }

        int byReleasedAt = reviewDao.countReleasedSince(Direction.EN_TO_ZH, startOfDay);
        int byLastReviewed = countNeverReviewed();

        assertEquals("正確做法：算得出今天放行了 10 個", 10, byReleasedAt);
        assertEquals("錯誤做法：全部被複習過，看起來一個都沒放行", 0, byLastReviewed);
    }

    // ---------------------------------------------------------------
    // 周邊行為
    // ---------------------------------------------------------------

    @Test
    public void 昨天放行的不算今天的額度() {
        for (int i = 0; i < 10; i++) {
            long id = addWord("yesterday" + i, now - 3 * DAY);
            release(id, now - DAY - TimeUnit.HOURS.toMillis(2));
        }
        for (int i = 0; i < 15; i++) {
            addWord("waiting" + i, now);
        }

        int releasedToday = reviewDao.countReleasedSince(Direction.EN_TO_ZH, startOfDay);
        assertEquals("昨天放行的不占今天的額度", 0, releasedToday);

        ReviewQueuePlanner.Plan plan = ReviewQueuePlanner.plan(
                reviewDao.findDueWordIds(Direction.EN_TO_ZH, now),
                reviewDao.findUnreleasedWordIds(Direction.EN_TO_ZH),
                releasedToday,
                DailyLimits.defaults());

        assertEquals("今天仍可放行 10 個新字", 10, plan.newlyReleasedWordIds.size());
    }

    @Test
    public void 今天放行一半之後只剩一半額度() {
        for (int i = 0; i < 4; i++) {
            long id = addWord("released" + i, now - 2 * DAY);
            ReviewState state = release(id, now - TimeUnit.HOURS.toMillis(3));
            ReviewStateMapper.applyReview(state, Grade.EASY, now - TimeUnit.HOURS.toMillis(2));
            reviewDao.upsert(state);
        }
        for (int i = 0; i < 15; i++) {
            addWord("waiting" + i, now);
        }

        int releasedToday = reviewDao.countReleasedSince(Direction.EN_TO_ZH, startOfDay);
        assertEquals(4, releasedToday);

        ReviewQueuePlanner.Plan plan = ReviewQueuePlanner.plan(
                reviewDao.findDueWordIds(Direction.EN_TO_ZH, now),
                reviewDao.findUnreleasedWordIds(Direction.EN_TO_ZH),
                releasedToday,
                DailyLimits.defaults());

        assertEquals("10 - 4 = 6", 6, plan.newlyReleasedWordIds.size());
    }

    /** 已放行的字不可以再被當成「未放行的新字」撈出來重複放行。 */
    @Test
    public void 已放行的字不會再出現在未放行清單() {
        long a = addWord("alpha", now - DAY);
        addWord("beta", now);

        assertEquals(2, reviewDao.findUnreleasedWordIds(Direction.EN_TO_ZH).size());

        release(a, now);

        List<Long> unreleased = reviewDao.findUnreleasedWordIds(Direction.EN_TO_ZH);
        assertEquals(1, unreleased.size());
        assertTrue("剩下的應該是還沒放行的那個", !unreleased.contains(a));
    }

    /**
     * 新字要<b>隨機</b>放行，不可以照加入順序。
     *
     * <p>踩過的坑：匯入 CSV 時 {@code created_at} 是照檔案列順序給的，
     * 而單字表通常按字母排。照加入順序放行的結果是每天的 10 個新字全是
     * a 開頭，接下來好幾天困在 ab、ac —— 使用者的原話是
     * 「我發現單字可能是按照順序來，因為都是 a 開頭的字」。
     */
    @Test
    public void 新字放行順序是隨機的() {
        for (int i = 0; i < 40; i++) {
            // created_at 遞增，模擬「按字母排的 CSV 依序匯入」
            addWord(String.format(java.util.Locale.US, "word%02d", i), now - DAY + i);
        }

        List<Long> byCreation = new java.util.ArrayList<>(
                reviewDao.findUnreleasedWordIds(Direction.EN_TO_ZH));

        // 連抽五次，只要有一次的第一個不是「加入時間最早的那個」就成立。
        // 真的照加入順序排的話，五次都會一模一樣。
        boolean varied = false;
        for (int attempt = 0; attempt < 5 && !varied; attempt++) {
            List<Long> again = reviewDao.findUnreleasedWordIds(Direction.EN_TO_ZH);
            assertEquals("每次都要撈到全部 40 個", 40, again.size());
            assertEquals("不可以有重複或漏掉",
                    new java.util.HashSet<>(byCreation), new java.util.HashSet<>(again));
            if (!again.get(0).equals(byCreation.get(0))
                    || !again.subList(0, 10).equals(byCreation.subList(0, 10))) {
                varied = true;
            }
        }
        assertTrue("放行順序看起來是固定的，隨機排序沒有生效", varied);
    }

    /** 已放行的字不會因為隨機排序又被抽一次。 */
    @Test
    public void 隨機排序不會讓已放行的字重新出現() {
        for (int i = 0; i < 20; i++) {
            addWord("w" + i, now - DAY + i);
        }
        List<Long> first = reviewDao.findUnreleasedWordIds(Direction.EN_TO_ZH);
        for (int i = 0; i < 10; i++) {
            release(first.get(i), now);
        }

        List<Long> rest = reviewDao.findUnreleasedWordIds(Direction.EN_TO_ZH);
        assertEquals(10, rest.size());
        for (int i = 0; i < 10; i++) {
            assertTrue("已放行的字不該再出現", !rest.contains(first.get(i)));
        }
    }

    /** SPEC 第 6.4 節：超過複習上限時「最久沒複習的優先」。這條排序在 SQL 裡。 */
    @Test
    public void 到期清單依最久沒複習排序() {
        long recent = addWord("recent", now - DAY);
        long oldest = addWord("oldest", now - DAY);
        long middle = addWord("middle", now - DAY);

        ReviewState r1 = release(recent, now - 5 * DAY);
        r1.lastReviewed = now - DAY;
        r1.dueDate = now - 1000;
        reviewDao.upsert(r1);

        ReviewState r2 = release(oldest, now - 5 * DAY);
        r2.lastReviewed = now - 30 * DAY;
        r2.dueDate = now - 1000;
        reviewDao.upsert(r2);

        ReviewState r3 = release(middle, now - 5 * DAY);
        r3.lastReviewed = now - 10 * DAY;
        r3.dueDate = now - 1000;
        reviewDao.upsert(r3);

        List<Long> due = reviewDao.findDueWordIds(Direction.EN_TO_ZH, now);
        assertEquals(java.util.Arrays.asList(oldest, middle, recent), due);
    }

    // ---- 小工具 ----

    private int countReviewed() {
        int n = 0;
        for (Long id : reviewDao.findUnreleasedWordIds(Direction.EN_TO_ZH)) {
            // 只是為了觸發查詢，不使用
            if (id == null) {
                n++;
            }
        }
        n = 0;
        for (long wordId : allReviewStateWordIds()) {
            ReviewState s = reviewDao.find(wordId, Direction.EN_TO_ZH);
            if (s != null && s.lastReviewed > 0) {
                n++;
            }
        }
        return n;
    }

    private int countNeverReviewed() {
        int n = 0;
        for (long wordId : allReviewStateWordIds()) {
            ReviewState s = reviewDao.find(wordId, Direction.EN_TO_ZH);
            if (s != null && s.lastReviewed == 0) {
                n++;
            }
        }
        return n;
    }

    /** in-memory 測試庫很小，直接掃 word_entry 找出有排程的。 */
    private List<Long> allReviewStateWordIds() {
        List<Long> all = new java.util.ArrayList<>();
        for (WordEntry w : wordDao.findByIds(allWordIds())) {
            if (reviewDao.find(w.id, Direction.EN_TO_ZH) != null) {
                all.add(w.id);
            }
        }
        return all;
    }

    private List<Long> allWordIds() {
        List<Long> ids = new java.util.ArrayList<>();
        for (long i = 1; i <= 200; i++) {
            ids.add(i);
        }
        return ids;
    }
}
