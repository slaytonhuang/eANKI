package com.example.eanki.data.backup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * {@link BackupSnapshot} 與 JSON 之間的轉換。純 Java，不依賴 Android 或 Room。
 *
 * <h2>為什麼是 JSON 而不是 CSV</h2>
 *
 * 備份要能<b>原樣還原</b>，而單字底下有一對多的例句、多對多的標籤、
 * 還有每個方向一份的排程狀態。攤平成 CSV 就得自己發明分隔規則，
 * 而那正是最容易在還原時出錯的地方。
 *
 * <p>「可讀」這件事沒有因此放棄：輸出有縮排，用一般文字編輯器打開
 * 就看得懂哪句例句屬於哪個字。
 *
 * <h2>讀進來的東西一律當成不可信</h2>
 *
 * 備份檔會被使用者搬到別的地方、被雲端同步改動、被手動編輯。
 * 缺欄位一律退回預設值，型別不對就讓它丟 {@link JSONException}——
 * <b>絕不半途寫進資料庫</b>。還原的交易在呼叫端，這裡只負責解析。
 */
public final class BackupJson {

    private static final String K_FORMAT = "format";
    private static final String K_VERSION = "version";
    private static final String K_EXPORTED_AT = "exportedAt";
    private static final String K_APP_VERSION = "appVersion";
    private static final String K_COUNTS = "counts";
    private static final String K_TAGS = "tags";
    private static final String K_WORDS = "words";
    private static final String K_REVIEW_LOG = "reviewLog";

    /** 縮排兩格。檔案大一點，但使用者打開時看得懂。 */
    private static final int INDENT = 2;

    public static final class FormatException extends Exception {
        public FormatException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------
    // 寫出
    // ------------------------------------------------------------------

    @NonNull
    public static String write(@NonNull BackupSnapshot snapshot) throws JSONException {
        JSONObject root = new JSONObject();
        root.put(K_FORMAT, BackupSnapshot.FORMAT);
        root.put(K_VERSION, BackupSnapshot.VERSION);
        root.put(K_EXPORTED_AT, snapshot.exportedAt);
        if (snapshot.appVersion != null) {
            root.put(K_APP_VERSION, snapshot.appVersion);
        }

        // 摘要只是給人看的，還原時不採信（見 read）
        BackupSnapshot.Counts c = snapshot.counts();
        JSONObject counts = new JSONObject();
        counts.put("words", c.words);
        counts.put("examples", c.examples);
        counts.put("tags", c.tags);
        counts.put("reviews", c.reviews);
        counts.put("logEntries", c.logEntries);
        counts.put("images", c.images);
        root.put(K_COUNTS, counts);

        JSONArray tags = new JSONArray();
        for (BackupSnapshot.Tag t : snapshot.tags) {
            JSONObject o = new JSONObject();
            o.put("id", t.id);
            o.put("name", t.name);
            tags.put(o);
        }
        root.put(K_TAGS, tags);

        JSONArray words = new JSONArray();
        for (BackupSnapshot.Word w : snapshot.words) {
            words.put(writeWord(w));
        }
        root.put(K_WORDS, words);

        JSONArray log = new JSONArray();
        for (BackupSnapshot.LogEntry e : snapshot.reviewLog) {
            JSONObject o = new JSONObject();
            o.put("wordId", e.wordId);
            o.put("direction", e.direction);
            o.put("reviewedAt", e.reviewedAt);
            o.put("grade", e.grade);
            log.put(o);
        }
        root.put(K_REVIEW_LOG, log);

        return root.toString(INDENT);
    }

    private static JSONObject writeWord(BackupSnapshot.Word w) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", w.id);
        o.put("headword", w.headword);
        putIfPresent(o, "phonetic", w.phonetic);
        putIfPresent(o, "translation", w.translation);
        putIfPresent(o, "pos", w.pos);
        o.put("source", w.source);
        o.put("createdAt", w.createdAt);
        putIfPresent(o, "imageName", w.imageName);
        putIfPresent(o, "imageBase64", w.imageBase64);

        JSONArray tagIds = new JSONArray();
        for (Long id : w.tagIds) {
            tagIds.put(id.longValue());
        }
        o.put("tagIds", tagIds);

        JSONArray examples = new JSONArray();
        for (BackupSnapshot.Example e : w.examples) {
            JSONObject eo = new JSONObject();
            putIfPresent(eo, "textEn", e.textEn);
            putIfPresent(eo, "textZh", e.textZh);
            eo.put("custom", e.custom);
            examples.put(eo);
        }
        o.put("examples", examples);

        JSONArray reviews = new JSONArray();
        for (BackupSnapshot.Review r : w.reviews) {
            JSONObject ro = new JSONObject();
            ro.put("direction", r.direction);
            ro.put("dueDate", r.dueDate);
            ro.put("releasedAt", r.releasedAt);
            ro.put("interval", r.interval);
            ro.put("easeFactor", r.easeFactor);
            ro.put("repetitions", r.repetitions);
            ro.put("lapses", r.lapses);
            ro.put("lastReviewed", r.lastReviewed);
            JSONArray grades = new JSONArray();
            for (Integer g : r.grades) {
                grades.put(g == null ? JSONObject.NULL : g);
            }
            ro.put("grades", grades);
            reviews.put(ro);
        }
        o.put("reviews", reviews);
        return o;
    }

    private static void putIfPresent(JSONObject o, String key, @Nullable String value)
            throws JSONException {
        if (value != null) {
            o.put(key, value);
        }
    }

    // ------------------------------------------------------------------
    // 讀回
    // ------------------------------------------------------------------

    @NonNull
    public static BackupSnapshot read(@NonNull String json)
            throws JSONException, FormatException {
        JSONObject root = new JSONObject(json);

        String format = root.optString(K_FORMAT, "");
        if (!BackupSnapshot.FORMAT.equals(format)) {
            throw new FormatException("不是 eanki 的備份檔（format=" + format + "）");
        }
        int version = root.optInt(K_VERSION, -1);
        if (version < 1 || version > BackupSnapshot.VERSION) {
            // 版本比這個 App 新，代表是更新版寫的檔案。硬讀會安靜地漏掉新欄位，
            // 使用者會以為還原成功了。
            throw new FormatException("備份檔版本 " + version
                    + " 不支援（這個版本最多讀到 " + BackupSnapshot.VERSION + "）");
        }

        BackupSnapshot snapshot = new BackupSnapshot();
        snapshot.version = version;
        snapshot.exportedAt = root.optLong(K_EXPORTED_AT, 0L);
        snapshot.appVersion = root.isNull(K_APP_VERSION)
                ? null : root.optString(K_APP_VERSION, null);

        JSONArray tags = root.optJSONArray(K_TAGS);
        if (tags != null) {
            for (int i = 0; i < tags.length(); i++) {
                JSONObject o = tags.getJSONObject(i);
                snapshot.tags.add(new BackupSnapshot.Tag(
                        o.getLong("id"), o.getString("name")));
            }
        }

        JSONArray words = root.optJSONArray(K_WORDS);
        if (words != null) {
            for (int i = 0; i < words.length(); i++) {
                snapshot.words.add(readWord(words.getJSONObject(i)));
            }
        }

        JSONArray log = root.optJSONArray(K_REVIEW_LOG);
        if (log != null) {
            for (int i = 0; i < log.length(); i++) {
                JSONObject o = log.getJSONObject(i);
                BackupSnapshot.LogEntry e = new BackupSnapshot.LogEntry();
                e.wordId = o.getLong("wordId");
                e.direction = o.optString("direction", "EN_TO_ZH");
                e.reviewedAt = o.optLong("reviewedAt", 0L);
                e.grade = o.optInt("grade", 0);
                snapshot.reviewLog.add(e);
            }
        }
        return snapshot;
    }

    private static BackupSnapshot.Word readWord(JSONObject o) throws JSONException {
        BackupSnapshot.Word w = new BackupSnapshot.Word();
        w.id = o.getLong("id");
        w.headword = o.getString("headword");
        w.phonetic = optString(o, "phonetic");
        w.translation = optString(o, "translation");
        w.pos = optString(o, "pos");
        w.source = o.optString("source", "MANUAL");
        w.createdAt = o.optLong("createdAt", 0L);
        w.imageName = optString(o, "imageName");
        w.imageBase64 = optString(o, "imageBase64");

        JSONArray tagIds = o.optJSONArray("tagIds");
        if (tagIds != null) {
            for (int i = 0; i < tagIds.length(); i++) {
                w.tagIds.add(tagIds.getLong(i));
            }
        }

        JSONArray examples = o.optJSONArray("examples");
        if (examples != null) {
            for (int i = 0; i < examples.length(); i++) {
                JSONObject eo = examples.getJSONObject(i);
                BackupSnapshot.Example e = new BackupSnapshot.Example();
                e.textEn = optString(eo, "textEn");
                e.textZh = optString(eo, "textZh");
                e.custom = eo.optBoolean("custom", true);
                w.examples.add(e);
            }
        }

        JSONArray reviews = o.optJSONArray("reviews");
        if (reviews != null) {
            for (int i = 0; i < reviews.length(); i++) {
                JSONObject ro = reviews.getJSONObject(i);
                BackupSnapshot.Review r = new BackupSnapshot.Review();
                r.direction = ro.optString("direction", "EN_TO_ZH");
                r.dueDate = ro.optLong("dueDate", 0L);
                r.releasedAt = ro.optLong("releasedAt", 0L);
                r.interval = ro.optInt("interval", 0);
                r.easeFactor = ro.optDouble("easeFactor", 2.5d);
                r.repetitions = ro.optInt("repetitions", 0);
                r.lapses = ro.optInt("lapses", 0);
                r.lastReviewed = ro.optLong("lastReviewed", 0L);
                JSONArray grades = ro.optJSONArray("grades");
                if (grades != null) {
                    for (int g = 0; g < Math.min(3, grades.length()); g++) {
                        r.grades[g] = grades.isNull(g) ? null : grades.getInt(g);
                    }
                }
                w.reviews.add(r);
            }
        }
        return w;
    }

    /** {@code optString} 對 JSON null 會回傳字串 "null"，所以要自己擋。 */
    @Nullable
    private static String optString(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) {
            return null;
        }
        return o.optString(key, null);
    }

    private BackupJson() {
    }
}
