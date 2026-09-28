package com.example.eanki.data;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;

import com.example.eanki.data.user.Direction;
import com.example.eanki.data.user.ReviewLog;
import com.example.eanki.data.user.ReviewLogDao;
import com.example.eanki.data.user.ReviewState;
import com.example.eanki.data.user.ReviewStateDao;
import com.example.eanki.data.user.ReviewStateMapper;
import com.example.eanki.data.user.UserDatabase;
import com.example.eanki.data.user.WordDao;
import com.example.eanki.data.user.WordEntry;
import com.example.eanki.review.DailyLimits;
import com.example.eanki.review.Grade;
import com.example.eanki.review.ReviewQueuePlanner;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 複習流程的資料入口。
 *
 * <p>把「從資料庫撈資料」與「套每日上限」分開：上限邏輯全在純 Java 的
 * {@link ReviewQueuePlanner}，這裡只負責 I/O 與把結果轉成 UI 要的物件。
 */
public final class ReviewRepository {

    private static final String TAG = "ReviewQueue";

    /** 一次複習 session 的內容。佇列在開始時就定案，中途不會變動。 */
    public static final class Session {

        public final List<WordEntry> cards;
        /** 到期但超過複習上限、順延到明天的張數。 */
        public final int deferredCount;

        Session(List<WordEntry> cards, int deferredCount) {
            this.cards = cards;
            this.deferredCount = deferredCount;
        }
    }

    private static volatile ReviewRepository instance;

    private final WordDao wordDao;
    private final ReviewStateDao reviewDao;
    private final ReviewLogDao logDao;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private ReviewRepository(Context context) {
        UserDatabase db = UserDatabase.get(context);
        this.wordDao = db.wordDao();
        this.reviewDao = db.reviewStateDao();
        this.logDao = db.reviewLogDao();
    }

    /**
     * <b>測試專用</b>：丟掉快取的單例，下次 {@code get()} 會重新依當前的
     * {@link com.example.eanki.data.user.UserDatabase} 建立。
     * 搭配 {@code UserDatabase.overrideForTest} 讓測試跑在 in-memory 資料庫上。
     */
    @VisibleForTesting
    public static void resetForTest() {
        instance = null;
    }

    public static ReviewRepository get(Context context) {
        if (instance == null) {
            synchronized (ReviewRepository.class) {
                if (instance == null) {
                    instance = new ReviewRepository(context);
                }
            }
        }
        return instance;
    }

    public ExecutorService io() {
        return io;
    }

    public LiveData<Integer> observeDueCount(Direction direction, long now) {
        return reviewDao.observeDueCount(direction, now);
    }

    public LiveData<Integer> observeUnreleasedCount(Direction direction) {
        return reviewDao.observeUnreleasedCount(direction);
    }

    public LiveData<Integer> observeReleasedToday(Direction direction, long now) {
        return reviewDao.observeReleasedSince(direction, startOfDay(now));
    }

    /**
     * 組出今天的複習佇列，並把新放行的字寫進 review_state。
     *
     * <p><b>佇列是快照，不是 LiveData。</b>SPEC 第三原則是「複習節奏不能被打斷」，
     * 複習到一半佇列在腳下重排會讓進度條跳動、卡片順序改變。
     * 數量的即時更新走 {@link #observeDueCount}，那是給單字本看的。
     */
    @WorkerThread
    public Session startSession(Direction direction, DailyLimits limits, long now) {
        List<Long> due = reviewDao.findDueWordIds(direction, now);
        List<Long> unreleased = reviewDao.findUnreleasedWordIds(direction);
        int releasedToday = reviewDao.countReleasedSince(direction, startOfDay(now));

        ReviewQueuePlanner.Plan plan =
                ReviewQueuePlanner.plan(due, unreleased, releasedToday, limits);

        // 新放行的字要立刻建立 review_state，否則關掉 App 再開會重新放行一批
        if (!plan.newlyReleasedWordIds.isEmpty()) {
            List<ReviewState> fresh = new ArrayList<>(plan.newlyReleasedWordIds.size());
            for (Long wordId : plan.newlyReleasedWordIds) {
                ReviewState state = new ReviewState();
                state.wordId = wordId;
                state.direction = direction;
                state.dueDate = now;
                state.releasedAt = now;
                fresh.add(state);
            }
            reviewDao.upsertAll(fresh);
        }

        Log.i(TAG, String.format(java.util.Locale.US,
                "到期 %d / 未放行新字 %d / 今日已放行 %d"
                        + " → 佇列 %d（複習 %d + 新字 %d），順延 %d"
                        + "（上限：新字 %d、總量 %d）",
                due.size(), unreleased.size(), releasedToday,
                plan.size(), plan.size() - plan.newlyReleasedWordIds.size(),
                plan.newlyReleasedWordIds.size(), plan.deferredDueCount,
                limits.newPerDay, limits.reviewsPerDay));

        return new Session(loadInOrder(plan.wordIds), plan.deferredDueCount);
    }

    /**
     * 只算「今天該複習幾張」，<b>不寫入任何東西</b>。
     *
     * <p>每日提醒的 Worker 用這個。它絕對不能走 {@link #startSession}，
     * 因為那會把新字放行（寫 review_state）—— 使用者根本還沒打開 App，
     * 卻已經消耗掉今天的新字額度，等他晚上真的來複習時反而沒新字了。
     */
    @WorkerThread
    public int previewTodayCount(Direction direction, DailyLimits limits, long now) {
        List<Long> due = reviewDao.findDueWordIds(direction, now);
        List<Long> unreleased = reviewDao.findUnreleasedWordIds(direction);
        int releasedToday = reviewDao.countReleasedSince(direction, startOfDay(now));
        return ReviewQueuePlanner.plan(due, unreleased, releasedToday, limits).size();
    }

    /** 依 id 清單的順序取回單字，順序就是複習順序。 */
    @WorkerThread
    private List<WordEntry> loadInOrder(List<Long> wordIds) {
        if (wordIds.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, WordEntry> byId = new LinkedHashMap<>();
        for (WordEntry w : wordDao.findByIds(wordIds)) {
            byId.put(w.id, w);
        }
        List<WordEntry> ordered = new ArrayList<>(wordIds.size());
        for (Long id : wordIds) {
            WordEntry w = byId.get(id);
            if (w != null) {
                ordered.add(w);
            }
        }
        return ordered;
    }

    /**
     * 記錄一次評分：跑 SM-2、更新 review_state。
     *
     * <p>排程的算術全在已測試過的 {@link com.example.eanki.review.Sm2Scheduler}，
     * 這裡只負責讀出、套用、寫回。
     */
    @WorkerThread
    public void grade(long wordId, Direction direction, Grade grade, long now) {
        ReviewState state = reviewDao.find(wordId, direction);
        if (state == null) {
            // 理論上 startSession 已經建好了，防禦性補一筆
            state = new ReviewState();
            state.wordId = wordId;
            state.direction = direction;
            state.releasedAt = now;
        }
        ReviewStateMapper.applyReview(state, grade, now);
        reviewDao.upsert(state);

        // 另外留一筆流水紀錄。review_state 只保留最後一次複習時間，
        // 畫不出「過去七天每天做了多少」。
        ReviewLog log = new ReviewLog();
        log.wordId = wordId;
        log.direction = direction;
        log.reviewedAt = now;
        log.grade = grade.code();
        logDao.insert(log);
    }

    /**
     * 加強複習的一次評分。<b>只滾動熟悉度紀錄，完全不碰排程。</b>
     *
     * <p>SPEC 第 6.6 節用粗體點名這件事：寫回 {@code due_date} 會讓這批字
     * 每練一次就往前擠，間隔越練越短，最後每天都在複習同一批字。
     * 「絕不動排程」由 {@link ReviewStateMapper#applyExtraPractice} 負責，
     * 並有單元測試釘住六個排程欄位。
     *
     * <p><b>刻意不寫 {@code review_log}。</b>那張表餵的是今日頁的熱力圖，
     * 而熱力圖回答的是「我有沒有維持每天的固定份量」（SPEC 第二原則）。
     * 加強複習明確站在每日份量系統之外，混進去的話，某天只做了加強複習、
     * 排程佇列一張沒動，熱力圖卻會顯示滿格，那個數字就不能用來看節奏了。
     */
    @WorkerThread
    public void gradeExtraPractice(long wordId, Direction direction, Grade grade) {
        ReviewState state = reviewDao.find(wordId, direction);
        if (state == null) {
            // 沒放行過的字不該進加強複習；真的來了就略過，不要憑空建排程狀態
            Log.w(TAG, "加強複習略過未放行的字 wordId=" + wordId);
            return;
        }
        ReviewStateMapper.applyExtraPractice(state, grade);
        reviewDao.update(state);
    }

    /** 依 id 清單的順序取回單字，給加強複習的佇列用。 */
    @WorkerThread
    public List<WordEntry> loadOrdered(List<Long> wordIds) {
        return loadInOrder(wordIds);
    }

    /** 單字本列表每列的熟悉度與到期時間。 */
    public LiveData<List<ReviewStateDao.RowMeta>> observeRowMeta(Direction direction) {
        return reviewDao.observeRowMeta(direction);
    }

    /** 熟悉度篩選畫面的資料來源。 */
    public LiveData<List<ReviewStateDao.FamiliarityRow>> observeFamiliarityRows(
            Direction direction) {
        return reviewDao.observeFamiliarityRows(direction);
    }

    /** 所有已放行的字的最近三次評分，給熟悉度統計用。 */
    public LiveData<List<ReviewStateDao.GradeRow>> observeGrades(Direction direction) {
        return reviewDao.observeGrades(direction);
    }

    /** 過去七天每天的複習張數，給今日頁的熱力圖用。 */
    public LiveData<List<ReviewLogDao.DailyCount>> observeRecentDailyCounts(long now) {
        long since = startOfDay(now) - 6L * 86_400_000L;
        return logDao.observeDailyCounts(since);
    }

    @WorkerThread
    @Nullable
    public ReviewState findState(long wordId, Direction direction) {
        return reviewDao.find(wordId, direction);
    }

    /** 當地時區的今天零時。新字上限是「每天」，所以要用當地日界線而不是 UTC。 */
    static long startOfDay(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
