package com.example.eanki.data.user;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 使用者自己的單字，SPEC 第 3 節 B。
 *
 * <p><b>音標、釋義、詞性是從字典「複製」過來的值，不是外鍵。</b>
 * 字典庫是唯讀且可整包替換的（SPEC 第 3 節 A），若這裡存字典的 id，
 * 換一版字典就會整批對不上。複製一份的代價是重複儲存，
 * 換來的是使用者資料完全獨立於字典版本。
 */
@Entity(
        tableName = "word_entry",
        indices = {@Index(value = "headword", unique = true)}
)
public class WordEntry {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** 單字本身，已正規化為小寫去標點。唯一索引擋重複加入。 */
    @NonNull
    @ColumnInfo(name = "headword")
    public String headword = "";

    @Nullable
    @ColumnInfo(name = "phonetic")
    public String phonetic;

    @Nullable
    @ColumnInfo(name = "translation")
    public String translation;

    @Nullable
    @ColumnInfo(name = "pos")
    public String pos;

    @NonNull
    @ColumnInfo(name = "source")
    public Source source = Source.MANUAL;

    /** 來源照片，Phase 3 掃描才會填。 */
    @Nullable
    @ColumnInfo(name = "image_uri")
    public String imageUri;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    /**
     * 單行顯示用的釋義，把詞性之間的換行換成「 / 」。
     *
     * <p>{@code @Ignore} 代表 Room 不會把它當欄位，也就不進資料庫。
     * 存的原始值保留換行，因為<b>複習卡片是刻意多行顯示</b>的；
     * 只有單字本的列表需要壓成一行（每列只給兩行高度）。
     *
     * <p>算一次就留著：RecyclerView 捲動時同一個物件會被反覆 bind，
     * 每次都重算等於在捲動中不斷配置字串。
     */
    @Ignore
    private String singleLineTranslation;

    /** 單字本列表用的單行釋義。沒有釋義時回傳 null。 */
    @Ignore
    @Nullable
    public String singleLineTranslation() {
        if (translation == null) {
            return null;
        }
        if (singleLineTranslation == null) {
            singleLineTranslation = translation.replace("\n", " / ");
        }
        return singleLineTranslation;
    }

    public WordEntry() {
    }
}
