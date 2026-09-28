package com.example.eanki.data.user;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * SPEC 第 2 節：Java 沒有 coroutine，讀取讓 DAO 回傳 {@code LiveData}，
 * 由 Room 自動在背景查詢；寫入是同步方法，呼叫端負責丟到 executor。
 */
@Dao
public interface WordDao {

    @Query("SELECT * FROM word_entry ORDER BY created_at DESC")
    LiveData<List<WordEntry>> observeAll();

    @Query("SELECT * FROM word_entry ORDER BY created_at DESC LIMIT :limit")
    LiveData<List<WordEntry>> observeRecent(int limit);

    @Query("SELECT * FROM word_entry WHERE headword = :headword LIMIT 1")
    @Nullable
    WordEntry findByHeadword(String headword);

    @Query("SELECT COUNT(*) FROM word_entry")
    LiveData<Integer> observeCount();

    /** {@link #observeFiltered} 的 tagId 傳這個值代表「不限標籤」。tag.id 由 1 起跳。 */
    long ANY_TAG = 0L;

    /**
     * 單字本的搜尋 + 標籤篩選。SPEC 第 5 節。
     *
     * <p><b>過濾在 SQL 做，不是撈全部再用 Java 篩。</b>第 9 節已經記著
     * 這份清單之後要換 Paging 3，屆時記憶體裡不會有完整清單可篩；
     * 現在就把過濾放在資料層，換 Paging 時只要改回傳型別。
     *
     * <p>關鍵字同時比對 {@code headword} 與 {@code translation}，
     * 所以打「跑」也找得到 run。樣式與跳脫由
     * {@link com.example.eanki.search.SearchPattern} 產生，
     * {@code ESCAPE '\'} 必須跟那邊的跳脫字元一致。
     *
     * <p>排序把「完全相同」與「開頭相符」拉到前面：搜 run 時
     * {@code run} 應該在 {@code running} 之前，更在「釋義裡有跑」的字之前。
     * 沒有關鍵字時三個條件都退化成match全部，順序就回到加入時間由新到舊。
     */
    @Query("SELECT w.* FROM word_entry w"
            + " WHERE (:tagId = 0 OR EXISTS (SELECT 1 FROM word_tag wt"
            + "   WHERE wt.word_id = w.id AND wt.tag_id = :tagId))"
            + " AND (w.headword LIKE :contains ESCAPE '\\'"
            + "   OR IFNULL(w.translation, '') LIKE :contains ESCAPE '\\')"
            + " ORDER BY CASE WHEN w.headword = :exact THEN 0"
            + "   WHEN w.headword LIKE :prefix ESCAPE '\\' THEN 1 ELSE 2 END,"
            + " w.created_at DESC, w.id DESC")
    LiveData<List<WordEntry>> observeFiltered(
            String contains, String prefix, String exact, long tagId);

    /** 目前所有還被指向的來源圖檔名，給孤兒清理用。 */
    @Query("SELECT image_uri FROM word_entry WHERE image_uri IS NOT NULL AND image_uri <> ''")
    List<String> findAllImageNames();

    /** 依 id 批次取回。回傳順序不保證，呼叫端自己排。 */
    @Query("SELECT * FROM word_entry WHERE id IN (:ids)")
    List<WordEntry> findByIds(List<Long> ids);

    /**
     * headword 有唯一索引，重複加入會丟 SQLiteConstraintException。
     * 用 ABORT 而不是 IGNORE/REPLACE：使用者應該被告知「這個字已經在單字本裡了」，
     * 而不是默默沒反應或把既有資料蓋掉。
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    long insert(WordEntry word);

    /**
     * 匯入專用：已存在就跳過，回傳 -1。
     *
     * <p>手動輸入走的是 ABORT 版本，因為使用者需要被告知「這個字已經在單字本裡了」。
     * 匯入一千多筆時重複是常態，用例外來表達會慢很多，而且在單一 transaction
     * 裡丟例外還要擔心整批被回滾。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insertIgnore(WordEntry word);

    @Update
    void update(WordEntry word);

    @Delete
    void delete(WordEntry word);

    // ---- 備份／還原 ----

    /** 整本讀出，給匯出用。順序固定，讓兩次匯出的檔案可以直接 diff。 */
    @Query("SELECT * FROM word_entry ORDER BY id")
    List<WordEntry> findAll();

    /**
     * 還原用：連 id 一起寫回。
     *
     * <p><b>id 必須保留</b>——example、word_tag、review_state、review_log
     * 全都用 word_id 指回來，重新編號會讓它們全部對不上。
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertAllWithIds(List<WordEntry> words);

    @Query("DELETE FROM word_entry")
    void deleteAll();
}
