package com.example.eanki.data.user;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

/**
 * SM-2 排程狀態。<b>Phase 1 只建結構，不寫任何邏輯</b>（SPEC 第 8 節 Phase 2）。
 *
 * <p><b>主鍵是 {@code (word_id, direction)} 複合鍵</b>，這是 SPEC 第 3 節用粗體
 * 點名的要求：同一個字在英→中和中→英的難度差很多，共用一組 interval
 * 會在切換方向時破壞排程。
 *
 * <p>最近三次評分用三個欄位滾動儲存（SPEC 第 6.5 節），不另開表。
 * 熟悉度取三次的眾數、平手時取較差的那個 —— 這段邏輯 Phase 2 才寫。
 */
@Entity(
        tableName = "review_state",
        primaryKeys = {"word_id", "direction"},
        foreignKeys = @ForeignKey(
                entity = WordEntry.class,
                parentColumns = "id",
                childColumns = "word_id",
                onDelete = ForeignKey.CASCADE
        ),
        indices = {@Index("word_id")}
)
public class ReviewState {

    @ColumnInfo(name = "word_id")
    public long wordId;

    @NonNull
    @ColumnInfo(name = "direction")
    public Direction direction = Direction.EN_TO_ZH;

    /** 下次到期時間，epoch millis。 */
    @ColumnInfo(name = "due_date")
    public long dueDate;

    /**
     * 這張卡「放行」進入複習流程的時間，epoch millis。
     *
     * <p><b>SPEC 第 3 節的表格沒有這一欄，是實作第 6.4 節的新字上限時補上的。</b>
     * 「每天只放行 10 個新字」必須知道今天已經放行幾個，
     * 而 {@code last_reviewed} 做不到 —— 使用者當天就把新字複習完的話，
     * {@code last_reviewed} 會被填上，今天放行過的紀錄就消失，
     * 於是又能再放行 10 個，上限形同虛設。
     */
    @ColumnInfo(name = "released_at")
    public long releasedAt;

    /**
     * 目前間隔，<b>單位為分鐘</b>。
     *
     * <p>不用「天」是因為 SPEC 第 6.3 節的「忘了 → 10 分鐘」用整數天表達不了。
     * 1 天 = 1440，4 天 = 5760，10 天 = 14400。
     */
    @ColumnInfo(name = "interval")
    public int interval;

    /** SM-2 的 EF，初始 2.5。 */
    @ColumnInfo(name = "ease_factor")
    public double easeFactor = 2.5d;

    @ColumnInfo(name = "repetitions")
    public int repetitions;

    @ColumnInfo(name = "lapses")
    public int lapses;

    @ColumnInfo(name = "last_reviewed")
    public long lastReviewed;

    /**
     * 最近第 1 次評分（最新），存 {@link com.example.eanki.review.Grade#code()}。
     * null 表示還沒複習過。
     */
    @Nullable
    @ColumnInfo(name = "grade_1")
    public Integer grade1;

    @Nullable
    @ColumnInfo(name = "grade_2")
    public Integer grade2;

    @Nullable
    @ColumnInfo(name = "grade_3")
    public Integer grade3;

    public ReviewState() {
    }
}
