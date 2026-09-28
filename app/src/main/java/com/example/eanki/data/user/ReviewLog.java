package com.example.eanki.data.user;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 每一次複習的流水紀錄。
 *
 * <p><b>SPEC 第 3 節的表格沒有這張表，是做第 5 節「過去七天熱力圖」時補的。</b>
 * {@code review_state.last_reviewed} 只保留<b>最後一次</b>複習時間：
 * 一個字昨天複習過、今天又複習，昨天那筆就被覆蓋掉了。
 * 用它畫七天熱力圖會系統性低估較早的日子 —— 越舊的格子錯得越離譜。
 *
 * <p>一天 60 張的上限下，一年約兩萬筆，體積可忽略。
 * Phase 4 的熟悉度統計也會用到這張表。
 */
@Entity(
        tableName = "review_log",
        foreignKeys = @ForeignKey(
                entity = WordEntry.class,
                parentColumns = "id",
                childColumns = "word_id",
                onDelete = ForeignKey.CASCADE
        ),
        indices = {@Index("word_id"), @Index("reviewed_at")}
)
public class ReviewLog {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "word_id")
    public long wordId;

    @NonNull
    @ColumnInfo(name = "direction")
    public Direction direction = Direction.EN_TO_ZH;

    /** 複習發生的時間，epoch millis。 */
    @ColumnInfo(name = "reviewed_at")
    public long reviewedAt;

    /** 當次評分，存 {@link com.example.eanki.review.Grade#code()}。 */
    @ColumnInfo(name = "grade")
    public int grade;

    public ReviewLog() {
    }
}
