package com.example.eanki.data;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import com.example.eanki.data.backup.BackupJson;
import com.example.eanki.data.backup.BackupSnapshot;
import com.example.eanki.data.image.ScanImageStore;
import com.example.eanki.data.user.Direction;
import com.example.eanki.data.user.ExampleDao;
import com.example.eanki.data.user.ExampleSentence;
import com.example.eanki.data.user.ReviewLog;
import com.example.eanki.data.user.ReviewLogDao;
import com.example.eanki.data.user.ReviewState;
import com.example.eanki.data.user.ReviewStateDao;
import com.example.eanki.data.user.Source;
import com.example.eanki.data.user.Tag;
import com.example.eanki.data.user.TagDao;
import com.example.eanki.data.user.UserDatabase;
import com.example.eanki.data.user.WordDao;
import com.example.eanki.data.user.WordEntry;
import com.example.eanki.data.user.WordTag;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 匯出與還原。SPEC 第 8 節 Phase 4「匯出 / 備份」。
 *
 * <h2>為什麼要有這個</h2>
 *
 * 使用者資料很小但無可取代（SPEC 第 3 節）。重新匯入原始 CSV 救不回來 ——
 * 那份檔案裡沒有複習排程、沒有熟悉度、沒有例句，救回來的是一本
 * 「看起來有字、但幾個月的複習進度全沒了」的單字本。
 *
 * <p>這件事實際發生過一次：instrumented test 對正式資料庫呼叫
 * {@code clearAllTables()}，把 1,464 筆單字連同排程一起清光。
 *
 * <h2>還原是整批取代，而且在同一個交易裡</h2>
 *
 * 「恢復成匯出當時的狀態」只有整批取代做得到 —— 合併會留下備份之後
 * 才刪掉的字。所以還原前會先清空，而<b>清空與寫回必須在同一個交易裡</b>：
 * 中途失敗若已經清掉舊資料，使用者就同時失去了原本的資料與備份的資料。
 */
public final class BackupRepository {

    private static final String TAG = "Backup";

    /** 單一圖檔的大小上限。超過就不放進備份，只保留檔名並回報。 */
    @VisibleForTesting
    static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;

    public static final class ExportResult {
        public final BackupSnapshot.Counts counts;
        public final int bytes;
        /** 太大而沒被放進備份的圖檔數。 */
        public final int skippedImages;

        ExportResult(BackupSnapshot.Counts counts, int bytes, int skippedImages) {
            this.counts = counts;
            this.bytes = bytes;
            this.skippedImages = skippedImages;
        }
    }

    public static final class RestoreResult {
        public final boolean success;
        public final BackupSnapshot.Counts counts;
        public final long exportedAt;
        public final int restoredImages;
        @Nullable
        public final String error;

        RestoreResult(boolean success, BackupSnapshot.Counts counts, long exportedAt,
                      int restoredImages, @Nullable String error) {
            this.success = success;
            this.counts = counts;
            this.exportedAt = exportedAt;
            this.restoredImages = restoredImages;
            this.error = error;
        }
    }

    /** 給呼叫端組一個「還沒開始就失敗」的結果。 */
    @NonNull
    public static RestoreResult failedResult(@Nullable String message) {
        return new RestoreResult(false, new BackupSnapshot().counts(), 0, 0, message);
    }

    private static volatile BackupRepository instance;

    private final UserDatabase db;
    private final WordDao wordDao;
    private final ExampleDao exampleDao;
    private final TagDao tagDao;
    private final ReviewStateDao reviewDao;
    private final ReviewLogDao logDao;
    private final ScanImageStore images;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private BackupRepository(Context context) {
        db = UserDatabase.get(context);
        wordDao = db.wordDao();
        exampleDao = db.exampleDao();
        tagDao = db.tagDao();
        reviewDao = db.reviewStateDao();
        logDao = db.reviewLogDao();
        images = new ScanImageStore(context);
    }

    public static BackupRepository get(Context context) {
        if (instance == null) {
            synchronized (BackupRepository.class) {
                if (instance == null) {
                    instance = new BackupRepository(context);
                }
            }
        }
        return instance;
    }

    @VisibleForTesting
    public static void resetForTest() {
        instance = null;
    }

    public ExecutorService io() {
        return io;
    }

    // ------------------------------------------------------------------
    // 匯出
    // ------------------------------------------------------------------

    /** 讀出整個使用者庫。 */
    @WorkerThread
    @NonNull
    public BackupSnapshot snapshot(@Nullable String appVersion) {
        BackupSnapshot snapshot = new BackupSnapshot();
        snapshot.exportedAt = System.currentTimeMillis();
        snapshot.appVersion = appVersion;

        for (Tag t : tagDao.findAll()) {
            snapshot.tags.add(new BackupSnapshot.Tag(t.id, t.name));
        }

        // 先把附屬資料依 word_id 分組，避免每個字各查一次資料庫 ——
        // 1,464 個字就是 1,464 次查詢，匯出會慢到像當掉
        Map<Long, List<ExampleSentence>> examplesByWord = new HashMap<>();
        for (ExampleSentence e : exampleDao.findAll()) {
            examplesByWord.computeIfAbsent(e.wordId, k -> new ArrayList<>()).add(e);
        }
        Map<Long, List<Long>> tagsByWord = new HashMap<>();
        for (WordTag link : tagDao.findAllLinks()) {
            tagsByWord.computeIfAbsent(link.wordId, k -> new ArrayList<>()).add(link.tagId);
        }
        Map<Long, List<ReviewState>> reviewsByWord = new HashMap<>();
        for (ReviewState r : reviewDao.findAll()) {
            reviewsByWord.computeIfAbsent(r.wordId, k -> new ArrayList<>()).add(r);
        }

        for (WordEntry w : wordDao.findAll()) {
            BackupSnapshot.Word out = new BackupSnapshot.Word();
            out.id = w.id;
            out.headword = w.headword;
            out.phonetic = w.phonetic;
            out.translation = w.translation;
            out.pos = w.pos;
            out.source = w.source.name();
            out.createdAt = w.createdAt;
            out.imageName = w.imageUri;
            out.imageBase64 = readImage(w.imageUri);

            List<Long> tagIds = tagsByWord.get(w.id);
            if (tagIds != null) {
                out.tagIds.addAll(tagIds);
            }
            List<ExampleSentence> examples = examplesByWord.get(w.id);
            if (examples != null) {
                for (ExampleSentence e : examples) {
                    BackupSnapshot.Example eo = new BackupSnapshot.Example();
                    eo.textEn = e.textEn;
                    eo.textZh = e.textZh;
                    eo.custom = e.isCustom;
                    out.examples.add(eo);
                }
            }
            List<ReviewState> reviews = reviewsByWord.get(w.id);
            if (reviews != null) {
                for (ReviewState r : reviews) {
                    BackupSnapshot.Review ro = new BackupSnapshot.Review();
                    ro.direction = r.direction.name();
                    ro.dueDate = r.dueDate;
                    ro.releasedAt = r.releasedAt;
                    ro.interval = r.interval;
                    ro.easeFactor = r.easeFactor;
                    ro.repetitions = r.repetitions;
                    ro.lapses = r.lapses;
                    ro.lastReviewed = r.lastReviewed;
                    ro.grades[0] = r.grade1;
                    ro.grades[1] = r.grade2;
                    ro.grades[2] = r.grade3;
                    out.reviews.add(ro);
                }
            }
            snapshot.words.add(out);
        }

        for (ReviewLog l : logDao.findAll()) {
            BackupSnapshot.LogEntry e = new BackupSnapshot.LogEntry();
            e.wordId = l.wordId;
            e.direction = l.direction.name();
            e.reviewedAt = l.reviewedAt;
            e.grade = l.grade;
            snapshot.reviewLog.add(e);
        }
        return snapshot;
    }

    @WorkerThread
    @NonNull
    public ExportResult export(@NonNull OutputStream out, @Nullable String appVersion)
            throws IOException {
        BackupSnapshot snapshot = snapshot(appVersion);
        int skipped = 0;
        for (BackupSnapshot.Word w : snapshot.words) {
            if (w.imageName != null && w.imageBase64 == null) {
                skipped++;
            }
        }

        String json;
        try {
            json = BackupJson.write(snapshot);
        } catch (Exception e) {
            throw new IOException("產生備份內容失敗", e);
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        out.write(bytes);
        out.flush();

        BackupSnapshot.Counts c = snapshot.counts();
        Log.i(TAG, "匯出完成：" + c.words + " 字 / " + c.examples + " 例句 / "
                + c.reviews + " 排程 / " + c.logEntries + " 流水 / "
                + c.images + " 圖，共 " + bytes.length + " bytes");
        return new ExportResult(c, bytes.length, skipped);
    }

    @Nullable
    private String readImage(@Nullable String name) {
        File file = images.resolve(name);
        if (file == null) {
            return null;
        }
        if (file.length() > MAX_IMAGE_BYTES) {
            Log.w(TAG, "圖檔太大，不放進備份：" + name + " (" + file.length() + " bytes)");
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream((int) file.length());
            byte[] chunk = new byte[16 * 1024];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP);
        } catch (IOException e) {
            Log.w(TAG, "讀不到圖檔 " + name, e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 還原
    // ------------------------------------------------------------------

    /** 先只解析，不寫任何東西。畫面要先把「會還原成什麼」給使用者確認。 */
    @WorkerThread
    @NonNull
    public RestoreResult preview(@NonNull InputStream in) {
        try {
            BackupSnapshot snapshot = BackupJson.read(readAll(in));
            return new RestoreResult(true, snapshot.counts(), snapshot.exportedAt, 0, null);
        } catch (BackupJson.FormatException e) {
            return new RestoreResult(false, new BackupSnapshot().counts(), 0, 0,
                    e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "備份檔解析失敗", e);
            return new RestoreResult(false, new BackupSnapshot().counts(), 0, 0,
                    "檔案讀不開或格式不對");
        }
    }

    /**
     * 整批取代。
     *
     * <p><b>解析先做完，才碰資料庫。</b>先清空再發現檔案是壞的，
     * 使用者就同時失去原本的資料與備份的資料。
     */
    @WorkerThread
    @NonNull
    public RestoreResult restore(@NonNull InputStream in) {
        BackupSnapshot snapshot;
        try {
            snapshot = BackupJson.read(readAll(in));
        } catch (BackupJson.FormatException e) {
            return new RestoreResult(false, new BackupSnapshot().counts(), 0, 0,
                    e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "備份檔解析失敗", e);
            return new RestoreResult(false, new BackupSnapshot().counts(), 0, 0,
                    "檔案讀不開或格式不對");
        }
        return restore(snapshot);
    }

    @WorkerThread
    @NonNull
    public RestoreResult restore(@NonNull BackupSnapshot snapshot) {
        try {
            db.runInTransaction(() -> {
                // word_entry 的外鍵是 CASCADE，刪掉單字就會帶走 example、
                // word_tag、review_state、review_log。tag 沒有外鍵指向它，要自己清。
                wordDao.deleteAll();
                tagDao.deleteAllTags();

                List<Tag> tags = new ArrayList<>(snapshot.tags.size());
                for (BackupSnapshot.Tag t : snapshot.tags) {
                    Tag tag = new Tag();
                    tag.id = t.id;
                    tag.name = t.name;
                    tags.add(tag);
                }
                tagDao.insertAllWithIds(tags);

                List<WordEntry> words = new ArrayList<>(snapshot.words.size());
                for (BackupSnapshot.Word w : snapshot.words) {
                    WordEntry e = new WordEntry();
                    e.id = w.id;
                    e.headword = w.headword;
                    e.phonetic = w.phonetic;
                    e.translation = w.translation;
                    e.pos = w.pos;
                    e.source = parseSource(w.source);
                    e.imageUri = w.imageName;
                    e.createdAt = w.createdAt;
                    words.add(e);
                }
                wordDao.insertAllWithIds(words);

                List<ExampleSentence> examples = new ArrayList<>();
                List<WordTag> links = new ArrayList<>();
                List<ReviewState> states = new ArrayList<>();
                for (BackupSnapshot.Word w : snapshot.words) {
                    for (BackupSnapshot.Example e : w.examples) {
                        ExampleSentence s = new ExampleSentence();
                        s.wordId = w.id;
                        s.textEn = e.textEn;
                        s.textZh = e.textZh;
                        s.isCustom = e.custom;
                        examples.add(s);
                    }
                    for (Long tagId : w.tagIds) {
                        WordTag link = new WordTag();
                        link.wordId = w.id;
                        link.tagId = tagId;
                        links.add(link);
                    }
                    for (BackupSnapshot.Review r : w.reviews) {
                        ReviewState s = new ReviewState();
                        s.wordId = w.id;
                        s.direction = parseDirection(r.direction);
                        s.dueDate = r.dueDate;
                        s.releasedAt = r.releasedAt;
                        s.interval = r.interval;
                        s.easeFactor = r.easeFactor;
                        s.repetitions = r.repetitions;
                        s.lapses = r.lapses;
                        s.lastReviewed = r.lastReviewed;
                        s.grade1 = r.grades[0];
                        s.grade2 = r.grades[1];
                        s.grade3 = r.grades[2];
                        states.add(s);
                    }
                }
                exampleDao.insertAll(examples);
                tagDao.linkAll(links);
                reviewDao.upsertAll(states);

                List<ReviewLog> logs = new ArrayList<>(snapshot.reviewLog.size());
                for (BackupSnapshot.LogEntry e : snapshot.reviewLog) {
                    ReviewLog l = new ReviewLog();
                    l.wordId = e.wordId;
                    l.direction = parseDirection(e.direction);
                    l.reviewedAt = e.reviewedAt;
                    l.grade = e.grade;
                    logs.add(l);
                }
                logDao.insertAll(logs);
            });
        } catch (Exception e) {
            Log.e(TAG, "還原失敗，交易已回滾", e);
            return new RestoreResult(false, snapshot.counts(), snapshot.exportedAt, 0,
                    "寫入失敗：" + e.getMessage());
        }

        // 圖檔在交易外寫 —— 檔案系統本來就沒有交易，
        // 而且圖檔缺了只是看不到來源照片，資料本身仍然完整
        int restoredImages = restoreImages(snapshot);

        BackupSnapshot.Counts c = snapshot.counts();
        Log.i(TAG, "還原完成：" + c.words + " 字 / " + c.examples + " 例句 / "
                + c.reviews + " 排程 / " + c.logEntries + " 流水 / " + restoredImages + " 圖");
        return new RestoreResult(true, c, snapshot.exportedAt, restoredImages, null);
    }

    private int restoreImages(BackupSnapshot snapshot) {
        int written = 0;
        for (BackupSnapshot.Word w : snapshot.words) {
            if (w.imageName == null || w.imageBase64 == null) {
                continue;
            }
            File target = images.fileFor(w.imageName);
            if (target == null) {
                continue;
            }
            try (OutputStream out = new FileOutputStream(target)) {
                out.write(Base64.decode(w.imageBase64, Base64.NO_WRAP));
                written++;
            } catch (Exception e) {
                Log.w(TAG, "寫不回圖檔 " + w.imageName, e);
            }
        }
        return written;
    }

    private static Source parseSource(@Nullable String name) {
        if (name != null) {
            for (Source s : Source.values()) {
                if (s.name().equals(name)) {
                    return s;
                }
            }
        }
        return Source.MANUAL;
    }

    private static Direction parseDirection(@Nullable String name) {
        if (name != null) {
            for (Direction d : Direction.values()) {
                if (d.name().equals(name)) {
                    return d;
                }
            }
        }
        return Direction.EN_TO_ZH;
    }

    @NonNull
    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[32 * 1024];
        int read;
        while ((read = in.read(chunk)) != -1) {
            out.write(chunk, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
