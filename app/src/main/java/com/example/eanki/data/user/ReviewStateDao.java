package com.example.eanki.data.user;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/** 複習排程的存取。佇列的上限邏輯在 {@link com.example.eanki.review.ReviewQueuePlanner}。 */
@Dao
public interface ReviewStateDao {

    @Query("SELECT * FROM review_state WHERE word_id = :wordId AND direction = :direction")
    @Nullable
    ReviewState find(long wordId, Direction direction);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(ReviewState state);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<ReviewState> states);

    @Update
    void update(ReviewState state);

    /** 整表讀出，給匯出用。 */
    @Query("SELECT * FROM review_state ORDER BY word_id, direction")
    List<ReviewState> findAll();

    /**
     * 今天到期、已在複習流程中的字。
     *
     * <p><b>排序就是 SPEC 第 6.4 節「最久沒複習的優先」。</b>
     * 超過複習上限時是從這個順序的尾端砍掉，所以排序錯了上限就會砍錯人。
     * {@code last_reviewed} 相同時（例如同批匯入）再用 due_date 決定。
     */
    @Query("SELECT word_id FROM review_state"
            + " WHERE direction = :direction AND due_date <= :now"
            + " ORDER BY last_reviewed ASC, due_date ASC, word_id ASC")
    List<Long> findDueWordIds(Direction direction, long now);

    /**
     * 還沒放行進複習流程的新字，<b>隨機順序</b>。
     *
     * <p>原本是依加入時間由舊到新，實際用起來是錯的：匯入 CSV 時
     * {@code created_at} 是照檔案的列順序給的，而單字表通常是按字母排的，
     * 於是每天放行的 10 個新字全是 a 開頭，接下來好幾天都困在 ab、ac。
     * 那不像在學一本單字書，像在背字典。
     *
     * <p>隨機抽的代價是「掃描進來的字要等很久才會輪到」——
     * 但按加入時間排也一樣要等，新掃的字排在 1,300 多個匯入單字的後面。
     * 兩種順序都沒有解決那件事，所以選讀起來比較像在學習的那個。
     *
     * <p>放行後會立刻寫入 {@code review_state}，所以每次呼叫重新亂數排序
     * 不會讓已經放行的字又被抽一次。
     */
    @Query("SELECT w.id FROM word_entry w"
            + " LEFT JOIN review_state r ON r.word_id = w.id AND r.direction = :direction"
            + " WHERE r.word_id IS NULL"
            + " ORDER BY RANDOM()")
    List<Long> findUnreleasedWordIds(Direction direction);

    /** 今天已經放行幾個新字。SPEC 第 6.4 節的新字上限靠這個數字。 */
    @Query("SELECT COUNT(*) FROM review_state"
            + " WHERE direction = :direction AND released_at >= :dayStartMillis")
    int countReleasedSince(Direction direction, long dayStartMillis);

    /**
     * 所有已放行的字的最近三次評分，給熟悉度統計用。
     *
     * <p>分類邏輯（眾數、平手取較差）在純 Java 的
     * {@link com.example.eanki.review.FamiliarityRule}，用 SQL 寫又醜又測不了。
     * 每天只放行 10 個新字，這張表一年也才三千多列，全撈出來很便宜。
     */
    @Query("SELECT word_id AS wordId, grade_1 AS grade1, grade_2 AS grade2,"
            + " grade_3 AS grade3 FROM review_state WHERE direction = :direction")
    LiveData<List<GradeRow>> observeGrades(Direction direction);

    /**
     * 熟悉度篩選畫面要的一列：單字本體 + 最近三次評分 + 兩個排序用的時間。
     *
     * <p>只 JOIN 已放行的字（INNER JOIN）。沒放行過的字沒有評分，
     * 不屬於任何一級，篩選畫面本來就不該列出它們。
     *
     * <p>分級與排序都在純 Java 做（{@link com.example.eanki.review.FamiliarityRule}、
     * {@link com.example.eanki.review.SortMode}），SQL 只負責把資料撈出來。
     * 每天只放行 10 個新字，這張表一年也才三千多列。
     */
    @Query("SELECT w.id AS wordId, w.headword AS headword, w.phonetic AS phonetic,"
            + " w.translation AS translation, w.created_at AS createdAt,"
            + " r.last_reviewed AS lastReviewed, r.grade_1 AS grade1,"
            + " r.grade_2 AS grade2, r.grade_3 AS grade3"
            + " FROM word_entry w"
            + " JOIN review_state r ON r.word_id = w.id AND r.direction = :direction")
    LiveData<List<FamiliarityRow>> observeFamiliarityRows(Direction direction);

    /**
     * 單字本列表每一列要的附加資訊：熟悉度色點與「下次複習」。
     *
     * <p>跟單字清單分開查再在 ViewModel 合併，不做 JOIN ——
     * 清單本身走的是可切換條件的篩選查詢（{@code observeFiltered}），
     * 再塞一個 LEFT JOIN 進去會讓那條 SQL 難讀又難改。
     * 已放行的字一年也才三千多筆，整批撈出來很便宜。
     */
    @Query("SELECT word_id AS wordId, due_date AS dueDate, grade_1 AS grade1,"
            + " grade_2 AS grade2, grade_3 AS grade3"
            + " FROM review_state WHERE direction = :direction")
    LiveData<List<RowMeta>> observeRowMeta(Direction direction);

    /** 單字本一列的附加資訊。 */
    class RowMeta {
        public long wordId;
        public long dueDate;
        @Nullable
        public Integer grade1;
        @Nullable
        public Integer grade2;
        @Nullable
        public Integer grade3;
    }

    /** 最近三次評分的原始列。 */
    class GradeRow {
        public long wordId;
        @Nullable
        public Integer grade1;
        @Nullable
        public Integer grade2;
        @Nullable
        public Integer grade3;
    }

    /** 熟悉度篩選畫面的一列。 */
    class FamiliarityRow implements com.example.eanki.review.SortMode.Sortable {
        public long wordId;
        public String headword;
        @Nullable
        public String phonetic;
        @Nullable
        public String translation;
        public long createdAt;
        public long lastReviewed;
        @Nullable
        public Integer grade1;
        @Nullable
        public Integer grade2;
        @Nullable
        public Integer grade3;

        @Override
        public String sortWord() {
            return headword == null ? "" : headword;
        }

        @Override
        public long sortLastReviewed() {
            return lastReviewed;
        }

        @Override
        public long sortCreatedAt() {
            return createdAt;
        }
    }

    // ---- 給 UI 的即時數量 ----

    @Query("SELECT COUNT(*) FROM review_state"
            + " WHERE direction = :direction AND due_date <= :now")
    LiveData<Integer> observeDueCount(Direction direction, long now);

    @Query("SELECT COUNT(*) FROM word_entry w"
            + " LEFT JOIN review_state r ON r.word_id = w.id AND r.direction = :direction"
            + " WHERE r.word_id IS NULL")
    LiveData<Integer> observeUnreleasedCount(Direction direction);

    /**
     * 今天已放行幾個新字，給單字本的「還能放行幾個」用。
     *
     * <p>必須跟 {@link #countReleasedSince} 用同一個條件，否則單字本顯示的數字
     * 會跟實際進入佇列的張數對不上 —— 這正是第一版踩到的錯。
     */
    @Query("SELECT COUNT(*) FROM review_state"
            + " WHERE direction = :direction AND released_at >= :dayStartMillis")
    LiveData<Integer> observeReleasedSince(Direction direction, long dayStartMillis);
}
