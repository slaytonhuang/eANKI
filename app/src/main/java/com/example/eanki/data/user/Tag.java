package com.example.eanki.data.user;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 標籤。<b>Phase 1 只建結構，不寫邏輯</b>（單字本的標籤 chip 見 SPEC 第 5 節）。
 * 先建表是為了避免之後補做時要寫 Room migration。
 */
@Entity(
        tableName = "tag",
        indices = {@Index(value = "name", unique = true)}
)
public class Tag {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    @ColumnInfo(name = "name")
    public String name = "";

    public Tag() {
    }
}
