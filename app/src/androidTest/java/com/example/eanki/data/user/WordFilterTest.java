package com.example.eanki.data.user;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.eanki.search.SearchPattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 單字本的搜尋與標籤篩選。SPEC 第 5 節。
 *
 * <h2>為什麼是 instrumented test</h2>
 *
 * 樣式產生與跳脫已經有純 JVM 的 {@code SearchPatternTest} 蓋住，
 * 但真正會出事的是 SQL 那一半：{@code ESCAPE} 有沒有跟樣式對上、
 * 標籤的 {@code EXISTS} 子查詢有沒有寫反、排序有沒有把完全命中拉到前面。
 * 那些只有跑真正的 SQLite 才測得到。
 */
@RunWith(AndroidJUnit4.class)
public class WordFilterTest {

    private UserDatabase db;
    private WordDao wordDao;
    private TagDao tagDao;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, UserDatabase.class)
                .allowMainThreadQueries()
                .build();
        wordDao = db.wordDao();
        tagDao = db.tagDao();
    }

    @After
    public void tearDown() {
        db.close();
    }

    private long addWord(String headword, String translation) {
        WordEntry w = new WordEntry();
        w.headword = headword;
        w.translation = translation;
        w.source = Source.MANUAL;
        w.createdAt = System.currentTimeMillis();
        return wordDao.insert(w);
    }

    private long tag(String name, long... wordIds) {
        long tagId = tagDao.getOrCreate(name);
        for (long wordId : wordIds) {
            WordTag link = new WordTag();
            link.wordId = wordId;
            link.tagId = tagId;
            tagDao.link(link);
        }
        return tagId;
    }

    /** 查詢結果的 headword，依查詢回傳的順序。 */
    private List<String> search(String query, long tagId) throws Exception {
        LiveData<List<WordEntry>> live = wordDao.observeFiltered(
                SearchPattern.contains(query),
                SearchPattern.prefix(query),
                SearchPattern.exact(query),
                tagId);
        List<String> words = new ArrayList<>();
        for (WordEntry w : await(live)) {
            words.add(w.headword);
        }
        return words;
    }

    private List<String> search(String query) throws Exception {
        return search(query, WordDao.ANY_TAG);
    }

    @SuppressWarnings("unchecked")
    private <T> T await(LiveData<T> live) throws Exception {
        Object[] box = new Object[1];
        CountDownLatch latch = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                live.observeForever(new Observer<T>() {
                    @Override
                    public void onChanged(T value) {
                        if (value == null) {
                            return;
                        }
                        box[0] = value;
                        live.removeObserver(this);
                        latch.countDown();
                    }
                }));
        assertTrue("LiveData 逾時", latch.await(5, TimeUnit.SECONDS));
        return (T) box[0];
    }

    // ---------------------------------------------------------------

    @Test
    public void 沒有關鍵字也沒有標籤時回傳全部() throws Exception {
        addWord("run", "v.跑");
        addWord("walk", "v.走");

        assertEquals(2, search("").size());
    }

    @Test
    public void 搜英文命中單字() throws Exception {
        addWord("run", "v.跑");
        addWord("walk", "v.走");

        assertEquals(List.of("run"), search("run"));
    }

    /** 關鍵字同時比對釋義，所以打中文也找得到英文字。 */
    @Test
    public void 搜中文命中釋義() throws Exception {
        addWord("run", "v.跑，奔跑");
        addWord("walk", "v.走");

        assertEquals(List.of("run"), search("奔跑"));
    }

    /**
     * 搜 run 時 {@code run} 要在 {@code running} 之前，
     * 更要在「只有釋義提到跑」的 {@code sprint} 之前。
     */
    @Test
    public void 完全相同的排最前面_開頭相符次之() throws Exception {
        addWord("sprint", "v.衝刺，快run");   // 釋義裡才有 run
        addWord("running", "n.跑步");
        addWord("run", "v.跑");

        assertEquals(List.of("run", "running", "sprint"), search("run"));
    }

    @Test
    public void 大小寫不影響搜尋() throws Exception {
        addWord("run", "v.跑");

        assertEquals(List.of("run"), search("RUN"));
        assertEquals(List.of("run"), search("Run"));
    }

    /**
     * 不跳脫的話 {@code a_c} 會match到 abc、adc、a1c ——
     * 使用者會覺得搜尋壞掉了。
     */
    @Test
    public void 底線不可以被當成萬用字元() throws Exception {
        addWord("abc", "n.字母");
        addWord("a_c", "n.真的有底線");

        assertEquals(List.of("a_c"), search("a_c"));
    }

    @Test
    public void 百分比不可以match全部() throws Exception {
        addWord("abc", "n.字母");
        addWord("half", "n.50% 的意思");

        assertEquals(List.of("half"), search("50%"));
    }

    // ---- 標籤 ----

    @Test
    public void 標籤篩選只回傳該標籤底下的字() throws Exception {
        long run = addWord("run", "v.跑");
        addWord("walk", "v.走");
        long toeic = tag("多益", run);

        assertEquals(List.of("run"), search("", toeic));
    }

    @Test
    public void 標籤與關鍵字同時作用() throws Exception {
        long run = addWord("run", "v.跑");
        long runway = addWord("runway", "n.跑道");
        addWord("running", "n.跑步");          // 沒標籤
        long toeic = tag("多益", run, runway);

        assertEquals(List.of("run", "runway"), search("run", toeic));
    }

    @Test
    public void 不限標籤時連沒有標籤的字也要出現() throws Exception {
        long run = addWord("run", "v.跑");
        addWord("walk", "v.走");
        tag("多益", run);

        assertEquals(2, search("", WordDao.ANY_TAG).size());
    }

    /**
     * 標籤底下的字被刪光之後，chip 不該還留著 ——
     * 點下去永遠是空清單，比沒有 chip 更糟。
     */
    @Test
    public void 沒有字的標籤不會出現在chip清單() throws Exception {
        long run = addWord("run", "v.跑");
        tag("多益", run);
        tag("空標籤");

        List<TagDao.TagCount> counts = await(tagDao.observeTagCounts());
        assertEquals(1, counts.size());
        assertEquals("多益", counts.get(0).name);
        assertEquals(1, counts.get(0).count);
    }

    @Test
    public void 標籤chip帶出正確的字數() throws Exception {
        long a = addWord("run", "v.跑");
        long b = addWord("walk", "v.走");
        addWord("swim", "v.游");
        tag("多益", a, b);

        List<TagDao.TagCount> counts = await(tagDao.observeTagCounts());
        assertEquals(2, counts.get(0).count);
    }
}
