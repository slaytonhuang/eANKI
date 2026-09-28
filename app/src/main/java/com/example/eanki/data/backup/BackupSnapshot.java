package com.example.eanki.data.backup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次備份的全部內容。純 Java，不依賴 Android 或 Room。
 *
 * <p><b>結構刻意是巢狀的</b>（例句、標籤、排程掛在單字底下），
 * 不是資料庫那種平的外鍵表。理由有兩個：
 * 人打開檔案時一眼看得懂哪句例句屬於哪個字；
 * 還原時不必自己維護 id 對應，一個單字連同它的附屬資料一起寫進去。
 *
 * <p>單字的 {@code id} 還是保留下來的 —— {@code review_log} 是唯一
 * 掛不進單字底下的東西（它是流水帳，不屬於任何一個字的狀態），
 * 靠 id 指回去。
 */
public final class BackupSnapshot {

    /** 檔案格式識別字。讀到別的值就不是這個 App 的備份。 */
    public static final String FORMAT = "eanki-backup";

    /**
     * 格式版本。
     *
     * <p>改欄位就要 +1，並在 {@code BackupJson} 決定舊版怎麼讀。
     * 沒有版號的備份格式，等到第一次改結構時就只能整批丟掉。
     */
    public static final int VERSION = 1;

    public static final class Tag {
        public long id;
        public String name;

        public Tag() {
        }

        public Tag(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public static final class Example {
        @Nullable
        public String textEn;
        @Nullable
        public String textZh;
        public boolean custom;
    }

    /** 一個方向的 SM-2 排程狀態 + 最近三次評分。 */
    public static final class Review {
        public String direction = "EN_TO_ZH";
        public long dueDate;
        public long releasedAt;
        public int interval;
        public double easeFactor = 2.5d;
        public int repetitions;
        public int lapses;
        public long lastReviewed;
        /** 最近三次評分，新到舊。沒有的那一格是 null。 */
        public final Integer[] grades = new Integer[3];
    }

    public static final class Word {
        public long id;
        public String headword = "";
        @Nullable
        public String phonetic;
        @Nullable
        public String translation;
        @Nullable
        public String pos;
        public String source = "MANUAL";
        public long createdAt;

        /** 來源照片的檔名。圖檔本身放在 {@link #imageBase64}。 */
        @Nullable
        public String imageName;
        /**
         * 來源照片的內容（Base64）。
         *
         * <p>圖也要進備份 —— 少了它，還原之後那些單字會指向不存在的檔案，
         * 而使用者要到點開來源照片時才會發現「備份其實不完整」。
         */
        @Nullable
        public String imageBase64;

        public final List<Long> tagIds = new ArrayList<>();
        public final List<Example> examples = new ArrayList<>();
        public final List<Review> reviews = new ArrayList<>();
    }

    /** 複習流水紀錄。熱力圖靠它，不屬於任何單一單字的狀態，所以平放。 */
    public static final class LogEntry {
        public long wordId;
        public String direction = "EN_TO_ZH";
        public long reviewedAt;
        public int grade;
    }

    public int version = VERSION;
    public long exportedAt;
    @Nullable
    public String appVersion;

    public final List<Tag> tags = new ArrayList<>();
    public final List<Word> words = new ArrayList<>();
    public final List<LogEntry> reviewLog = new ArrayList<>();

    /** 給畫面與 log 用的摘要。 */
    @NonNull
    public Counts counts() {
        int examples = 0;
        int reviews = 0;
        int images = 0;
        for (Word w : words) {
            examples += w.examples.size();
            reviews += w.reviews.size();
            if (w.imageBase64 != null) {
                images++;
            }
        }
        return new Counts(words.size(), examples, tags.size(), reviews,
                reviewLog.size(), images);
    }

    public static final class Counts {
        public final int words;
        public final int examples;
        public final int tags;
        public final int reviews;
        public final int logEntries;
        public final int images;

        Counts(int words, int examples, int tags, int reviews, int logEntries, int images) {
            this.words = words;
            this.examples = examples;
            this.tags = tags;
            this.reviews = reviews;
            this.logEntries = logEntries;
            this.images = images;
        }
    }
}
