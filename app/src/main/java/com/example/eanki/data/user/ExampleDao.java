package com.example.eanki.data.user;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

/** <b>Phase 1 不使用。</b>例句功能排在 Phase 4（SPEC 第 8 節）。 */
@Dao
public interface ExampleDao {

    @Query("SELECT * FROM example WHERE word_id = :wordId")
    List<ExampleSentence> findByWord(long wordId);

    @Insert
    void insertAll(List<ExampleSentence> examples);

    /** 整表讀出，給匯出用。 */
    @Query("SELECT * FROM example ORDER BY word_id, id")
    List<ExampleSentence> findAll();
}
