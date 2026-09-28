package com.example.eanki.data.importer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.eanki.data.text.HanRadicalNormalizer;
import com.example.eanki.data.text.SimplifiedDetector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 匯入檔的一列。
 *
 * <p>欄位對應（SPEC 第 3 節）：
 * <ul>
 *   <li>{@code headword}   → {@code word_entry.headword}</li>
 *   <li>{@code phonetic}   → {@code word_entry.phonetic}</li>
 *   <li>{@code meaning_zh} → {@code word_entry.translation}</li>
 *   <li>{@code example_en} / {@code example_zh} → {@code example} 表各一筆</li>
 * </ul>
 *
 * <p>這種來源的資料本身就完整，<b>不走查字典補全</b>：
 * 字典是備援，不是必要。使用者自己整理的釋義通常比通用字典更貼近他的教材。
 */
public final class ImportRow {

    public static final String COL_HEADWORD = "headword";
    public static final String COL_PHONETIC = "phonetic";
    public static final String COL_MEANING = "meaning_zh";
    public static final String COL_EXAMPLE_EN = "example_en";
    public static final String COL_EXAMPLE_ZH = "example_zh";

    public final String headword;
    @Nullable
    public final String phonetic;
    @Nullable
    public final String meaningZh;
    @Nullable
    public final String exampleEn;
    @Nullable
    public final String exampleZh;

    public ImportRow(String headword, @Nullable String phonetic, @Nullable String meaningZh,
                     @Nullable String exampleEn, @Nullable String exampleZh) {
        this.headword = headword;
        this.phonetic = phonetic;
        this.meaningZh = meaningZh;
        this.exampleEn = exampleEn;
        this.exampleZh = exampleZh;
    }

    public boolean hasExample() {
        return notBlank(exampleEn) || notBlank(exampleZh);
    }

    private static boolean notBlank(@Nullable String s) {
        return s != null && !s.trim().isEmpty();
    }

    /**
     * 去頭尾空白，空字串當成沒有值，<b>並且還原部首碼位</b>。
     *
     * <p>還原放在這裡是因為這是所有匯入文字唯一的入口 ——
     * headword、音標、釋義、例句全部經過這一個方法。放在更下游的地方
     * 就得記得每個欄位都呼叫一次，漏一個就又帶進去了。
     *
     * <p>要修的是 PDF 抽取文字的字型編碼陷阱：長得一模一樣、但碼位是
     * 部首而非一般漢字。第一次匯入的 1,464 筆有 594 筆中招，
     * 中文搜尋對那些字完全失效。細節見 {@link HanRadicalNormalizer}。
     */
    @Nullable
    private static String trimToNull(@Nullable String s) {
        if (s == null) {
            return null;
        }
        String t = HanRadicalNormalizer.normalize(s.trim());
        return t == null || t.isEmpty() ? null : t;
    }

    /**
     * 這一列裡確定是簡體的字。
     *
     * <p>與部首還原是兩件事，但常常一起出現：使用者的 PDF 轉檔工具
     * 看到的是部首碼位而不是簡體字，那些字因此逃過了繁簡轉換，
     * 要等 {@link HanRadicalNormalizer} 還原之後才現形。
     *
     * <p>只回報不自動轉換 —— 理由見 {@link SimplifiedDetector}。
     */
    @NonNull
    public List<Character> simplifiedChars() {
        List<Character> found = new java.util.ArrayList<>();
        for (String field : new String[]{meaningZh, exampleZh}) {
            for (Character c : SimplifiedDetector.find(field)) {
                if (!found.contains(c)) {
                    found.add(c);
                }
            }
        }
        return found;
    }

    /**
     * 這一列裡對照表沒涵蓋的部首字元。匯入摘要用來提醒使用者。
     *
     * <p>沒對應就原樣留著，不猜 —— 但要說出來，否則又是一批
     * 肉眼看不出、搜尋卻搜不到的字默默存進資料庫。
     */
    @NonNull
    public List<Character> unmappedRadicals() {
        List<Character> found = new java.util.ArrayList<>();
        for (String field : new String[]{headword, phonetic, meaningZh, exampleEn, exampleZh}) {
            for (Character c : HanRadicalNormalizer.unmappedRadicals(field)) {
                if (!found.contains(c)) {
                    found.add(c);
                }
            }
        }
        return found;
    }

    /**
     * 依標題列的欄名把整份 CSV 轉成資料列。
     *
     * <p><b>用欄名對應而不是欄位順序</b>：使用者自己整理的檔案欄位順序可能不同，
     * 照順序讀會安靜地把音標塞進釋義欄。缺少的欄位一律當成空值，
     * 只有 {@code headword} 是必要的。
     *
     * @return 有效的資料列；標題缺 headword 欄時回傳空清單
     */
    @NonNull
    public static List<ImportRow> fromRows(@NonNull List<List<String>> rows) {
        List<ImportRow> result = new java.util.ArrayList<>();
        if (rows.isEmpty()) {
            return result;
        }

        Map<String, Integer> index = new HashMap<>();
        List<String> header = rows.get(0);
        for (int i = 0; i < header.size(); i++) {
            index.put(header.get(i).trim().toLowerCase(java.util.Locale.US), i);
        }
        Integer headwordAt = index.get(COL_HEADWORD);
        if (headwordAt == null) {
            return result;
        }

        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String headword = trimToNull(at(row, headwordAt));
            if (headword == null) {
                continue;   // 空白列直接跳過，不算失敗
            }
            result.add(new ImportRow(
                    headword,
                    trimToNull(at(row, index.get(COL_PHONETIC))),
                    trimToNull(at(row, index.get(COL_MEANING))),
                    trimToNull(at(row, index.get(COL_EXAMPLE_EN))),
                    trimToNull(at(row, index.get(COL_EXAMPLE_ZH)))));
        }
        return result;
    }

    @Nullable
    private static String at(List<String> row, @Nullable Integer position) {
        if (position == null || position < 0 || position >= row.size()) {
            return null;
        }
        return row.get(position);
    }
}
