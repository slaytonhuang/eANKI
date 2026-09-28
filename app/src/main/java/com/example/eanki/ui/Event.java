package com.example.eanki.ui;

import androidx.annotation.Nullable;

/**
 * 一次性事件的包裝。
 *
 * <p>LiveData 會在觀察者重新訂閱時重播最後一筆值，對「顯示一則 Snackbar」
 * 這種事件而言是錯的 —— <b>摺疊裝置每次展開/闔上都會重建 Activity</b>
 * （SPEC 第 7 節），沒有這層包裝的話每摺一次就會重新跳一次提示。
 */
public final class Event<T> {

    private final T content;
    private boolean handled;

    public Event(T content) {
        this.content = content;
    }

    /** 第一次呼叫回傳內容，之後回傳 null。 */
    @Nullable
    public T consume() {
        if (handled) {
            return null;
        }
        handled = true;
        return content;
    }
}
