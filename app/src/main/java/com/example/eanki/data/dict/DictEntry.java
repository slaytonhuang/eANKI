package com.example.eanki.data.dict;

import androidx.annotation.Nullable;

/**
 * 字典查詢結果。
 *
 * <p>這是唯讀字典的資料，不是 Room entity —— 它不會被存進使用者庫。
 * 使用者按下「加入」時，是把這裡的值<b>複製</b>到
 * {@link com.example.eanki.data.user.WordEntry} 的欄位裡。
 */
public final class DictEntry {

    /** 字典裡的詞條，也就是<b>原形</b>。例如查 {@code jumps} 時這裡是 {@code jump}。 */
    public final String word;
    @Nullable
    public final String phonetic;
    @Nullable
    public final String translation;
    @Nullable
    public final String definition;
    @Nullable
    public final String pos;
    /** 考試標籤，如 {@code "zk gk cet4"}。 */
    @Nullable
    public final String tag;
    /** 詞頻，數字越小越常用。 */
    public final int frq;

    /**
     * 使用者實際查的那個形式。<b>直接命中時為 null</b>，
     * 只有靠詞形還原才找到時才有值（查 {@code jumps} → 這裡是 {@code jumps}、
     * {@link #word} 是 {@code jump}）。
     *
     * <p>存在的理由：SPEC 要求存進單字本的是<b>使用者掃到的形式</b>，
     * 畫面上顯示的也該是他掃到的字，不能偷偷換成原形 ——
     * 否則使用者認不出這是自己掃的那個字。
     */
    @Nullable
    public final String matchedForm;

    public DictEntry(String word, @Nullable String phonetic, @Nullable String translation,
                     @Nullable String definition, @Nullable String pos,
                     @Nullable String tag, int frq) {
        this(word, phonetic, translation, definition, pos, tag, frq, null);
    }

    public DictEntry(String word, @Nullable String phonetic, @Nullable String translation,
                     @Nullable String definition, @Nullable String pos,
                     @Nullable String tag, int frq, @Nullable String matchedForm) {
        this.word = word;
        this.phonetic = phonetic;
        this.translation = translation;
        this.definition = definition;
        this.pos = pos;
        this.tag = tag;
        this.frq = frq;
        this.matchedForm = matchedForm;
    }

    /** 是否靠詞形還原才查到的。 */
    public boolean viaInflection() {
        return matchedForm != null && !matchedForm.equalsIgnoreCase(word);
    }

    /** 應該顯示給使用者看的字：他查的那個形式。 */
    public String displayWord() {
        return matchedForm != null ? matchedForm : word;
    }

    /** 同一筆資料，但換成由某個變化形查到的。 */
    public DictEntry asMatchOf(String form) {
        return new DictEntry(word, phonetic, translation, definition, pos, tag, frq, form);
    }
}
