package com.example.eanki.data.user;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * 標籤。SPEC 第 3 節的 {@code tag} / {@code word_tag} 多對多。
 *
 * <p>Phase 1 只建了表結構，邏輯留到需要的時候 —— 匯入功能就是第一個需要的地方：
 * 一次進來一千多個字，沒有標籤的話單字本會變成無法分辨來源的一大坨。
 */
@Dao
public interface TagDao {

    @Query("SELECT * FROM tag WHERE name = :name LIMIT 1")
    @Nullable
    Tag findByName(String name);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(Tag tag);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void link(WordTag link);

    @Query("SELECT * FROM tag ORDER BY name")
    LiveData<List<Tag>> observeAll();

    // ---- 備份／還原 ----

    @Query("SELECT * FROM tag ORDER BY id")
    List<Tag> findAll();

    @Query("SELECT * FROM word_tag ORDER BY word_id, tag_id")
    List<WordTag> findAllLinks();

    /** 還原用：連 id 一起寫回，word_tag 才指得回來。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertAllWithIds(List<Tag> tags);

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void linkAll(List<WordTag> links);

    @Query("DELETE FROM tag")
    void deleteAllTags();

    @Query("SELECT COUNT(*) FROM word_tag WHERE tag_id = :tagId")
    int countWords(long tagId);

    /**
     * 單字本標籤 chip 的資料。
     *
     * <p>{@code HAVING count > 0} 把空標籤濾掉 —— 標籤底下的字被刪光之後，
     * chip 還留著但點下去永遠是空清單，那比沒有 chip 更糟。
     */
    @Query("SELECT t.id AS id, t.name AS name, COUNT(wt.word_id) AS count"
            + " FROM tag t LEFT JOIN word_tag wt ON wt.tag_id = t.id"
            + " GROUP BY t.id, t.name HAVING count > 0 ORDER BY t.name")
    LiveData<List<TagCount>> observeTagCounts();

    /** 一個標籤與它底下的字數。 */
    class TagCount {
        public long id;
        public String name;
        public int count;
    }

    /**
     * 取得標籤，沒有就建一個。
     *
     * <p>{@code name} 有唯一索引，所以 IGNORE 的 insert 在已存在時回傳 -1，
     * 這時再查一次即可 —— 不必先查再寫，省掉一次來回也避開競態。
     */
    default long getOrCreate(String name) {
        Tag tag = new Tag();
        tag.name = name;
        long id = insert(tag);
        if (id != -1L) {
            return id;
        }
        Tag existing = findByName(name);
        return existing == null ? -1L : existing.id;
    }
}
