package com.example.eanki.data.user;

/** 單字的來源。SPEC 第 3 節：SCAN / MANUAL / IMPORT。 */
public enum Source {
    /** Phase 3 的 OCR 掃描。 */
    SCAN,
    /** Phase 1 的手動輸入。 */
    MANUAL,
    /** Phase 4 的 CSV 匯入。 */
    IMPORT
}
