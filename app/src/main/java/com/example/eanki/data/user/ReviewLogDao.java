package com.example.eanki.data.user;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

/** 複習流水紀錄。目前只給「過去七天熱力圖」用。 */
@Dao
public interface ReviewLogDao {

    @Insert
    void insert(ReviewLog log);

    // ---- 備份／還原 ----

    @Query("SELECT * FROM review_log ORDER BY id")
    List<ReviewLog> findAll();

    @Insert
    void insertAll(List<ReviewLog> logs);

    /**
     * 依<b>當地日期</b>分組計算每天複習了幾張。
     *
     * <p>用 {@code 'localtime'} 而不是 UTC：使用者眼中的「今天」是當地時間的今天。
     * 用 UTC 分組的話，台灣時間早上 8 點前的複習會被算到前一天。
     */
    @Query("SELECT strftime('%Y-%m-%d', reviewed_at / 1000, 'unixepoch', 'localtime') AS day,"
            + " COUNT(*) AS count"
            + " FROM review_log WHERE reviewed_at >= :since"
            + " GROUP BY day")
    LiveData<List<DailyCount>> observeDailyCounts(long since);

    /** 目前總筆數。測試用：釘住「加強複習不寫流水紀錄」。 */
    @androidx.annotation.VisibleForTesting
    @Query("SELECT COUNT(*) FROM review_log")
    int countAll();

    /** 一天的複習張數。 */
    class DailyCount {
        public String day;
        public int count;
    }
}
