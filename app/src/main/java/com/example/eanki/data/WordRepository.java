package com.example.eanki.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;

import com.example.eanki.data.dict.DictEntry;
import com.example.eanki.data.dict.DictExample;
import com.example.eanki.data.dict.DictionaryRepository;
import com.example.eanki.data.dict.WordNormalizer;
import com.example.eanki.data.image.ScanImageStore;
import com.example.eanki.data.user.Source;
import com.example.eanki.data.user.TagDao;
import com.example.eanki.data.user.UserDatabase;
import com.example.eanki.data.user.WordDao;
import com.example.eanki.data.user.WordEntry;
import com.example.eanki.search.SearchPattern;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ViewModel 唯一會碰到的資料入口。
 *
 * <p>這裡是兩個資料庫交會的地方，也是唯一該交會的地方：
 * 從{@link DictionaryRepository 字典庫}查到值，<b>複製</b>進
 * {@link UserDatabase 使用者庫}。兩邊各自獨立，沒有外鍵、沒有 join。
 *
 * <p>SPEC 第 2 節：寫入一律丟到 {@code Executors.newSingleThreadExecutor()}，
 * 讀取讓 DAO 回傳 LiveData 由 Room 自己在背景查。
 */
public final class WordRepository {

    /** 寫入結果。UI 需要區分「成功」「重複」「查無此字」。 */
    public enum SaveResult {
        SAVED,
        /** headword 唯一索引擋下來的，代表單字本裡已經有了。 */
        DUPLICATE,
        FAILED
    }

    private static volatile WordRepository instance;

    /** 備援例句最多顯示幾句。卡片上塞更多就讀不完了。 */
    private static final int MAX_FALLBACK_EXAMPLES = 2;

    private final WordDao wordDao;
    private final com.example.eanki.data.user.ExampleDao exampleDao;
    private final TagDao tagDao;
    private final DictionaryRepository dictionary;
    private final ScanImageStore imageStore;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private WordRepository(Context context) {
        this.wordDao = UserDatabase.get(context).wordDao();
        this.tagDao = UserDatabase.get(context).tagDao();
        this.exampleDao = UserDatabase.get(context).exampleDao();
        this.dictionary = DictionaryRepository.get(context);
        this.imageStore = new ScanImageStore(context);
    }

    /**
     * <b>測試專用</b>：丟掉快取的單例，下次 {@code get()} 會重新依當前的
     * {@link com.example.eanki.data.user.UserDatabase} 建立。
     * 搭配 {@code UserDatabase.overrideForTest} 讓測試跑在 in-memory 資料庫上。
     */
    @VisibleForTesting
    public static void resetForTest() {
        instance = null;
    }

    public static WordRepository get(Context context) {
        if (instance == null) {
            synchronized (WordRepository.class) {
                if (instance == null) {
                    instance = new WordRepository(context);
                }
            }
        }
        return instance;
    }

    public ExecutorService io() {
        return io;
    }

    public ScanImageStore images() {
        return imageStore;
    }

    /**
     * 清掉沒有任何單字指向的來源圖檔。App 啟動時跑一次。
     *
     * <p>刪除單字時<b>不會立刻刪圖</b>，因為刪除有「復原」；
     * 立刻刪的話復原回來那筆就指向一個不存在的檔案。
     * 改成下次啟動再清，順便也收拾當機留下的殘檔。
     */
    @WorkerThread
    public int sweepOrphanImages() {
        return imageStore.sweepOrphans(wordDao.findAllImageNames());
    }

    // ---- 讀取：Room 自己在背景查 ----

    /**
     * 單字本的搜尋 + 標籤篩選。SPEC 第 5 節。
     *
     * @param tagId {@link WordDao#ANY_TAG} 代表不限標籤
     */
    public LiveData<List<WordEntry>> observeFiltered(@Nullable String query, long tagId) {
        return wordDao.observeFiltered(
                SearchPattern.contains(query),
                SearchPattern.prefix(query),
                SearchPattern.exact(query),
                tagId);
    }

    /** 單字本標籤 chip 的資料（只含底下還有字的標籤）。 */
    public LiveData<List<TagDao.TagCount>> observeTagCounts() {
        return tagDao.observeTagCounts();
    }

    /** 單字總數，給「篩選出 N / 共 M」的分母。 */
    public LiveData<Integer> observeCount() {
        return wordDao.observeCount();
    }

    public LiveData<List<WordEntry>> observeAll() {
        return wordDao.observeAll();
    }

    /** 最近加入的幾筆，給今日頁用（SPEC 第 5 節「最近加入三筆」）。 */
    public LiveData<List<WordEntry>> observeRecent(int limit) {
        return wordDao.observeRecent(limit);
    }

    // ---- 字典 ----

    /** 在背景執行緒呼叫。 */
    @Nullable
    public DictEntry lookUp(String raw) {
        return dictionary.lookUp(raw);
    }

    /**
     * 一張複習卡要顯示的例句。SPEC 第 5 節「複習背面：例句中英對照」。
     *
     * <p><b>使用者自己的永遠優先</b>，沒有才拿字典的 Tatoeba 備援補位
     * （SPEC 第 8 節 Phase 4 就是這樣定位這個功能的）。
     * 備援不寫回 {@code example} 表 —— 那是字典的附屬資料，
     * 會隨字典整包更新而改變，複製一份進使用者庫只會讓備份變大、
     * 而且換字典之後留下對不上的舊句子。
     */
    @WorkerThread
    @NonNull
    public CardExamples examplesFor(long wordId, @Nullable String headword) {
        List<com.example.eanki.data.user.ExampleSentence> own = exampleDao.findByWord(wordId);
        if (!own.isEmpty()) {
            List<DictExample> mine = new java.util.ArrayList<>(own.size());
            for (com.example.eanki.data.user.ExampleSentence e : own) {
                mine.add(new DictExample(
                        e.textEn == null ? "" : e.textEn,
                        e.textZh == null ? "" : e.textZh));
            }
            return CardExamples.own(mine);
        }
        return CardExamples.fallback(dictionary.findExamples(headword, MAX_FALLBACK_EXAMPLES));
    }

    /**
     * 這個字的來源照片（SPEC 第 5 節「複習背面：來源照片 chip」）。
     *
     * <p>檔案可能不在 —— 還原備份時圖檔是在交易外寫的，也可能被
     * {@code sweepOrphanImages} 收掉過。找不到就回 null，畫面不顯示 chip，
     * 不是顯示一個點了沒反應的 chip。
     */
    @WorkerThread
    @Nullable
    public java.io.File sourcePhotoFor(@Nullable String imageName) {
        return imageStore.resolve(imageName);
    }

    /** 設定頁的「離線字典狀態」。 */
    @WorkerThread
    public com.example.eanki.data.dict.DictionaryRepository.Status dictionaryStatus() {
        return dictionary.status();
    }

    /** 重建本機字典檔。設定頁的修復動作。 */
    @WorkerThread
    public boolean rebuildDictionary() {
        return dictionary.rebuild();
    }

    public boolean isDictionaryReady() {
        return dictionary.isReady();
    }

    // ---- 寫入：一律在背景執行緒 ----

    /**
     * 把字典查到的結果存成一筆單字。{@code entry} 為 null 表示字典查不到，
     * 仍然可以存（SPEC 第 6.1 節：查不到的字要讓使用者確認或自己補），
     * 只是音標與釋義留空。
     */
    public SaveResult save(String rawHeadword, @Nullable DictEntry entry, Source source) {
        return save(rawHeadword, entry, source, null);
    }

    public SaveResult save(String rawHeadword, @Nullable DictEntry entry, Source source,
                           @Nullable String imageName) {
        String headword = WordNormalizer.normalize(rawHeadword);
        if (headword.isEmpty()) {
            return SaveResult.FAILED;
        }

        WordEntry word = new WordEntry();
        word.headword = headword;
        word.source = source;
        word.imageUri = imageName;
        word.createdAt = System.currentTimeMillis();
        if (entry != null) {
            word.phonetic = entry.phonetic;
            word.translation = entry.translation;
            word.pos = entry.pos;
        }

        try {
            wordDao.insert(word);
            return SaveResult.SAVED;
        } catch (android.database.sqlite.SQLiteConstraintException e) {
            return SaveResult.DUPLICATE;
        } catch (Exception e) {
            return SaveResult.FAILED;
        }
    }

    /** 一個等著寫入的單字，含它的來源小圖檔名（可為 null）。 */
    public static final class PendingWord {
        public final String word;
        @Nullable
        public final DictEntry entry;
        @Nullable
        public final String imageName;

        public PendingWord(String word, @Nullable DictEntry entry, @Nullable String imageName) {
            this.word = word;
            this.entry = entry;
            this.imageName = imageName;
        }
    }

    /** 批次寫入的結果。SPEC 第 5 節掃描流程的「加入 N 個單字」要能回報實際加了幾個。 */
    public static final class BatchResult {
        public final int saved;
        public final int duplicate;
        public final int failed;

        BatchResult(int saved, int duplicate, int failed) {
            this.saved = saved;
            this.duplicate = duplicate;
            this.failed = failed;
        }

        public int total() {
            return saved + duplicate + failed;
        }
    }

    /**
     * 掃描後一次寫入多個字。在背景執行緒呼叫。
     *
     * <p>逐筆寫而不是一個 transaction：其中一個字重複時不該讓整批都失敗，
     * 使用者掃了 20 個字，其中 3 個已經在單字本裡是很正常的事。
     */
    public BatchResult saveAll(java.util.List<PendingWord> pending, Source source) {
        int saved = 0;
        int duplicate = 0;
        int failed = 0;
        for (PendingWord p : pending) {
            switch (save(p.word, p.entry, source, p.imageName)) {
                case SAVED:
                    saved++;
                    break;
                case DUPLICATE:
                    duplicate++;
                    break;
                default:
                    failed++;
                    break;
            }
        }
        return new BatchResult(saved, duplicate, failed);
    }

    public void delete(WordEntry word) {
        io.execute(() -> wordDao.delete(word));
    }

    public void insertBack(WordEntry word) {
        io.execute(() -> {
            try {
                wordDao.insert(word);
            } catch (Exception ignored) {
                // 復原用，失敗就算了
            }
        });
    }
}
