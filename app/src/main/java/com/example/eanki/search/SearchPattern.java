package com.example.eanki.search;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * 把使用者打的字轉成 SQL {@code LIKE} 的樣式。SPEC 第 5 節單字本的搜尋。
 *
 * <p>純 Java，不依賴 Android 或 Room。
 *
 * <h2>為什麼要跳脫</h2>
 *
 * {@code %} 與 {@code _} 在 {@code LIKE} 裡是萬用字元。不處理的話，
 * 使用者打一個底線就會match到所有字（{@code _} 代表任一字元），
 * 打 {@code %} 更是直接match全部 —— 看起來像「搜尋壞掉了」。
 *
 * <p>跳脫字元用反斜線，查詢端必須配 {@code ESCAPE '\'}，兩邊要一致。
 */
public final class SearchPattern {

    /** 配合 SQL 的 {@code ESCAPE '\'}。 */
    public static final char ESCAPE = '\\';

    /** 使用者沒打字時的樣式：match全部。 */
    public static final String MATCH_ALL = "%";

    /** {@code %關鍵字%}，用來比對單字與釋義。 */
    @NonNull
    public static String contains(@Nullable String raw) {
        String q = normalize(raw);
        return q.isEmpty() ? MATCH_ALL : "%" + escape(q) + "%";
    }

    /** {@code 關鍵字%}，用來把「開頭就是這個字」的結果排前面。 */
    @NonNull
    public static String prefix(@Nullable String raw) {
        String q = normalize(raw);
        return q.isEmpty() ? MATCH_ALL : escape(q) + "%";
    }

    /**
     * 完全相同的比對值。
     *
     * <p>轉小寫是必要的：SQLite 的 {@code =} 有分大小寫（{@code LIKE} 沒有），
     * 而 {@code headword} 一律以小寫存。使用者打 {@code Run} 也該命中 {@code run}。
     */
    @NonNull
    public static String exact(@Nullable String raw) {
        return normalize(raw).toLowerCase(Locale.ROOT);
    }

    public static boolean isBlank(@Nullable String raw) {
        return normalize(raw).isEmpty();
    }

    @NonNull
    private static String normalize(@Nullable String raw) {
        return raw == null ? "" : raw.trim();
    }

    @NonNull
    private static String escape(@NonNull String q) {
        StringBuilder out = new StringBuilder(q.length() + 4);
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (c == '%' || c == '_' || c == ESCAPE) {
                out.append(ESCAPE);
            }
            out.append(c);
        }
        return out.toString();
    }

    private SearchPattern() {
    }
}
