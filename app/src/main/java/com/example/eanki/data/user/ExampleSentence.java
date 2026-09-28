package com.example.eanki.data.user;

import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 例句。<b>Phase 1 只建結構，不寫邏輯</b>（SPEC 第 8 節 Phase 4）。
 *
 * <p>{@code isCustom} 區分「從 Tatoeba 語料庫帶出來的」與「使用者自己寫的」：
 * 前者在字典整包更新時可以重建，後者絕對不能動。
 */
@Entity(
        tableName = "example",
        foreignKeys = @ForeignKey(
                entity = WordEntry.class,
                parentColumns = "id",
                childColumns = "word_id",
                onDelete = ForeignKey.CASCADE
        ),
        indices = {@Index("word_id")}
)
public class ExampleSentence {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "word_id")
    public long wordId;

    @Nullable
    @ColumnInfo(name = "text_en")
    public String textEn;

    @Nullable
    @ColumnInfo(name = "text_zh")
    public String textZh;

    @ColumnInfo(name = "is_custom")
    public boolean isCustom;

    public ExampleSentence() {
    }
}
