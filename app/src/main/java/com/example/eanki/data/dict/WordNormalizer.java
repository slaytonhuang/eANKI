package com.example.eanki.data.dict;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * 查字典前的清洗。
 *
 * <p><b>這支類別是 Phase 0 實測逼出來的。</b>ML Kit 回傳的 element 會把標點
 * 黏在單字上（{@code apple,} {@code dog.} {@code Vocabulary:}），而且會改動大小寫
 * （原圖 {@code over the lazy dog.} 被辨識成 {@code Over the Lazy dog.}）。
 * 手動輸入同樣會有多餘空白與大小寫不一致。
 *
 * <p>Phase 1 的手動輸入與 Phase 3 的掃描共用這一條清洗管線，
 * 才不會出現「同一個字掃進來和打進來變成兩筆」。
 *
 * <p>詞形還原（plurals / 動詞變化）還沒做 —— ECDICT 的 {@code exchange} 欄位
 * 存的就是詞形變化表，等換上真實資料再接。
 */
public final class WordNormalizer {

    /**
     * 去掉前後非字母字元、轉小寫。字中間的連字號與撇號保留
     * （{@code well-known}、{@code don't} 是合法詞條）。
     */
    @NonNull
    public static String normalize(@NonNull String raw) {
        String s = raw.trim().toLowerCase(Locale.US);

        int start = 0;
        int end = s.length();
        while (start < end && !isWordChar(s.charAt(start))) {
            start++;
        }
        while (end > start && !isWordChar(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }

    private WordNormalizer() {
    }
}
