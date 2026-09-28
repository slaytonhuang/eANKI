package com.example.eanki.ui.scan;

/** 一個辨識到的單字在字典裡的狀態。SPEC 第 6.1 節：掃描以字典即時校對。 */
public enum LookupState {
    /** 還沒查完。第一次看到這個字時的狀態，通常只維持一兩幀。 */
    PENDING,
    /** 字典查得到，可以點選加入。 */
    FOUND,
    /**
     * 字典查不到。SPEC 第 6.1 節要求<b>以磚紅外框標示</b>並讓使用者確認或修正 ——
     * 這通常代表 OCR 認錯了，等於用字典當 OCR 的即時校對。
     */
    NOT_FOUND
}
