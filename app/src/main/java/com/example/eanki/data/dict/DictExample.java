package com.example.eanki.data.dict;

import androidx.annotation.NonNull;

/**
 * 字典庫裡的一句中英對照例句（Tatoeba）。
 *
 * <p>純值物件。跟使用者自己的 {@link com.example.eanki.data.user.ExampleSentence}
 * 分開是刻意的：這一份是<b>字典的附屬資料</b>，可以隨字典整包更新而改變，
 * 不屬於使用者，也不進備份。
 */
public final class DictExample {

    public final String textEn;
    public final String textZh;

    public DictExample(@NonNull String textEn, @NonNull String textZh) {
        this.textEn = textEn;
        this.textZh = textZh;
    }
}
