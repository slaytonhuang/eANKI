package com.example.eanki.data.user;

/**
 * 複習方向。SPEC 第 6.2 節：兩個方向的排程各自獨立。
 *
 * <p>Phase 2 只會實作 {@link #EN_TO_ZH}，但 {@code review_state} 的複合主鍵
 * 已經把 direction 算進去了，未來開雙向卡不必動資料庫。
 */
public enum Direction {
    /** 英→中，「認得」。預設方向。 */
    EN_TO_ZH,
    /** 中→英，「用得出來」。 */
    ZH_TO_EN
}
