package com.example.eanki.data.user;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

/** 單字與標籤的多對多關聯表。<b>Phase 1 只建結構。</b> */
@Entity(
        tableName = "word_tag",
        primaryKeys = {"word_id", "tag_id"},
        foreignKeys = {
                @ForeignKey(
                        entity = WordEntry.class,
                        parentColumns = "id",
                        childColumns = "word_id",
                        onDelete = ForeignKey.CASCADE
                ),
                @ForeignKey(
                        entity = Tag.class,
                        parentColumns = "id",
                        childColumns = "tag_id",
                        onDelete = ForeignKey.CASCADE
                )
        },
        indices = {@Index("word_id"), @Index("tag_id")}
)
public class WordTag {

    @ColumnInfo(name = "word_id")
    public long wordId;

    @ColumnInfo(name = "tag_id")
    public long tagId;

    public WordTag() {
    }
}
