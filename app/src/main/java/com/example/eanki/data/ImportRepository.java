package com.example.eanki.data;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.example.eanki.data.importer.ImportRow;
import com.example.eanki.data.user.ExampleDao;
import com.example.eanki.data.user.ExampleSentence;
import com.example.eanki.data.user.Source;
import com.example.eanki.data.user.TagDao;
import com.example.eanki.data.user.UserDatabase;
import com.example.eanki.data.user.WordDao;
import com.example.eanki.data.user.WordEntry;
import com.example.eanki.data.user.WordTag;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CSV 匯入。SPEC 第 5 節空狀態的「匯入 CSV」。
 *
 * <p><b>不走查字典補全。</b>這種來源的資料本身就完整（音標、釋義、例句都有），
 * 而且使用者自己整理的釋義通常比通用字典更貼近他的教材。
 * 字典是備援 —— 只有掃描與手動輸入才需要它。
 */
public final class ImportRepository {

    private static final String TAG = "ImportRepo";

    /**
     * 一次寫入多少列就結算一次 transaction。
     *
     * <p>逐筆各自 commit 的話，1464 筆要跑上千次 fsync，在手機上要好幾十秒。
     * 全部包成一個 transaction 又會讓進度條整段不動、中途失敗全部白做。
     * 分批是兩者的折衷，順便給進度回報一個自然的節奏。
     */
    private static final int CHUNK = 200;

    public interface ProgressListener {
        /** 在背景執行緒被呼叫。 */
        void onProgress(int done, int total);
    }

    /** 匯入結果。SPEC 要求的摘要：新增 N 個、已存在跳過 M 個。 */
    public static final class Result {
        public final int inserted;
        public final int skipped;
        public final int examples;
        public final String tagName;
        /**
         * 匯入的文字裡出現、但部首對照表沒涵蓋的字元。
         *
         * <p>通常是空的。不是空的就代表這份 CSV 帶著我們沒預期過的
         * 部首碼位 —— 那些字肉眼看不出異常，但搜尋永遠搜不到，
         * 必須讓使用者知道，而不是猜一個對應默默存進去。
         */
        public final java.util.List<Character> unmappedRadicals;
        /**
         * 匯入的中文裡確定是簡體的字。
         *
         * <p>部首還原之後才看得出來 —— 使用者的轉檔工具看到的是部首碼位，
         * 所以那些字沒被轉成繁體。只回報不自動轉，轉錯會改掉釋義的意思。
         */
        public final java.util.List<Character> simplifiedChars;

        Result(int inserted, int skipped, int examples, String tagName,
               java.util.List<Character> unmappedRadicals,
               java.util.List<Character> simplifiedChars) {
            this.unmappedRadicals = unmappedRadicals;
            this.simplifiedChars = simplifiedChars;
            this.inserted = inserted;
            this.skipped = skipped;
            this.examples = examples;
            this.tagName = tagName;
        }

        public int total() {
            return inserted + skipped;
        }
    }

    private static volatile ImportRepository instance;

    private final UserDatabase db;
    private final WordDao wordDao;
    private final ExampleDao exampleDao;
    private final TagDao tagDao;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private ImportRepository(Context context) {
        db = UserDatabase.get(context);
        wordDao = db.wordDao();
        exampleDao = db.exampleDao();
        tagDao = db.tagDao();
    }

    public static ImportRepository get(Context context) {
        if (instance == null) {
            synchronized (ImportRepository.class) {
                if (instance == null) {
                    instance = new ImportRepository(context);
                }
            }
        }
        return instance;
    }

    public ExecutorService io() {
        return io;
    }

    /**
     * 把資料列寫進使用者庫並統一打上標籤。
     *
     * <p>逐筆處理：已存在的字（以 {@code headword} 判斷）跳過，
     * <b>不讓個別失敗中斷整批</b>。
     *
     * @param tagName 統一貼的標籤，例如「多益」；null 或空字串則不貼
     */
    @WorkerThread
    @NonNull
    public Result importRows(@NonNull List<ImportRow> rows,
                             @Nullable String tagName,
                             @Nullable ProgressListener listener) {
        long tagId = -1L;
        if (tagName != null && !tagName.trim().isEmpty()) {
            tagId = tagDao.getOrCreate(tagName.trim());
        }

        int inserted = 0;
        int skipped = 0;
        int examples = 0;
        long now = System.currentTimeMillis();

        // 部首碼位已經在 ImportRow 解析時還原掉了，這裡只收集「還原不了的」
        List<Character> unmapped = new java.util.ArrayList<>();
        List<Character> simplified = new java.util.ArrayList<>();
        for (ImportRow row : rows) {
            for (Character c : row.unmappedRadicals()) {
                if (!unmapped.contains(c)) {
                    unmapped.add(c);
                }
            }
            for (Character c : row.simplifiedChars()) {
                if (!simplified.contains(c)) {
                    simplified.add(c);
                }
            }
        }
        if (!unmapped.isEmpty()) {
            Log.w(TAG, "匯入檔含未對應的部首碼位：" + unmapped);
        }
        if (!simplified.isEmpty()) {
            Log.w(TAG, "匯入檔含簡體字：" + simplified);
        }

        for (int start = 0; start < rows.size(); start += CHUNK) {
            int end = Math.min(rows.size(), start + CHUNK);
            List<ImportRow> chunk = rows.subList(start, end);
            int[] counts = new int[3];   // inserted, skipped, examples
            final long finalTagId = tagId;
            final long baseTime = now;
            final int offset = start;

            db.runInTransaction(() -> {
                for (int i = 0; i < chunk.size(); i++) {
                    ImportRow row = chunk.get(i);
                    WordEntry word = new WordEntry();
                    word.headword = row.headword;
                    word.phonetic = row.phonetic;
                    word.translation = row.meaningZh;
                    word.source = Source.IMPORT;
                    // 讓匯入的順序＝單字本的順序，否則同一毫秒進來的一千多筆
                    // 在「最近加入」裡會是隨機排列
                    word.createdAt = baseTime + offset + i;

                    long id = wordDao.insertIgnore(word);
                    if (id == -1L) {
                        counts[1]++;      // headword 已存在
                        continue;
                    }
                    counts[0]++;

                    if (row.hasExample()) {
                        ExampleSentence example = new ExampleSentence();
                        example.wordId = id;
                        example.textEn = row.exampleEn;
                        example.textZh = row.exampleZh;
                        // 使用者自己整理的例句，不是 Tatoeba 撈來的，
                        // 字典整包更新時不可以被重建掉
                        example.isCustom = true;
                        exampleDao.insertAll(java.util.Collections.singletonList(example));
                        counts[2]++;
                    }

                    if (finalTagId != -1L) {
                        WordTag link = new WordTag();
                        link.wordId = id;
                        link.tagId = finalTagId;
                        tagDao.link(link);
                    }
                }
            });

            inserted += counts[0];
            skipped += counts[1];
            examples += counts[2];

            if (listener != null) {
                listener.onProgress(end, rows.size());
            }
        }

        Log.i(TAG, "匯入完成：新增 " + inserted + "，跳過 " + skipped
                + "，例句 " + examples + "，標籤 " + tagName);
        return new Result(inserted, skipped, examples, tagName, unmapped, simplified);
    }

    /** 某個標籤底下有幾個字，給匯入後的摘要與之後的單字本篩選用。 */
    @WorkerThread
    public int countByTag(String tagName) {
        com.example.eanki.data.user.Tag tag = tagDao.findByName(tagName);
        return tag == null ? 0 : tagDao.countWords(tag.id);
    }

    /** 給測試與除錯用。 */
    @WorkerThread
    public List<String> debugSample(int limit) {
        List<String> out = new ArrayList<>();
        for (WordEntry w : wordDao.findByIds(new ArrayList<>())) {
            out.add(w.headword);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }
}
