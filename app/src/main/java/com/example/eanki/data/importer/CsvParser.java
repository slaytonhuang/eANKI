package com.example.eanki.data.importer;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 最小可用的 CSV 解析器，遵循 RFC 4180 的常見子集。
 *
 * <p>純 Java，不依賴 Android，可以單獨測試。
 *
 * <p><b>為什麼不用 split(",")：</b>匯入的是中文釋義與英文例句，
 * 例句裡有逗號是常態（{@code "He said, it works."}），
 * 用 split 會把一筆資料切成好幾欄，而且錯得很安靜 ——
 * 匯入一千多筆之後才發現釋義被截斷，比直接失敗還糟。
 *
 * <p>處理的情況：雙引號包住的欄位、欄位內的逗號與換行、
 * 連續兩個雙引號代表一個字面雙引號、CRLF 與 LF 混用、UTF-8 BOM。
 */
public final class CsvParser {

    private static final char QUOTE = '"';
    private static final char COMMA = ',';

    /** 解析整份 CSV，回傳每一列的欄位。第一列通常是標題。 */
    @NonNull
    public static List<List<String>> parse(@NonNull InputStream input) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean fieldStarted = false;
        boolean firstChar = true;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            int read;
            while ((read = reader.read()) != -1) {
                char c = (char) read;

                // Excel 存出來的 UTF-8 常常帶 BOM，不吃掉的話第一個欄名會對不上
                if (firstChar) {
                    firstChar = false;
                    if (c == '﻿') {
                        continue;
                    }
                }

                if (inQuotes) {
                    if (c == QUOTE) {
                        reader.mark(1);
                        int next = reader.read();
                        if (next == QUOTE) {
                            field.append(QUOTE);      // "" 代表一個字面雙引號
                        } else {
                            inQuotes = false;
                            if (next != -1) {
                                reader.reset();
                            }
                        }
                    } else {
                        field.append(c);
                    }
                    continue;
                }

                switch (c) {
                    case QUOTE:
                        inQuotes = true;
                        fieldStarted = true;
                        break;
                    case COMMA:
                        current.add(field.toString());
                        field.setLength(0);
                        fieldStarted = false;
                        break;
                    case '\r':
                        break;                        // CRLF 的 CR 直接略過
                    case '\n':
                        current.add(field.toString());
                        field.setLength(0);
                        fieldStarted = false;
                        rows.add(current);
                        current = new ArrayList<>();
                        break;
                    default:
                        field.append(c);
                        fieldStarted = true;
                        break;
                }
            }
        }

        // 最後一列可能沒有換行結尾
        if (fieldStarted || field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            rows.add(current);
        }
        return rows;
    }

    private CsvParser() {
    }
}
