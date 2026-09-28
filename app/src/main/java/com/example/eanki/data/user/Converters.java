package com.example.eanki.data.user;

import androidx.annotation.Nullable;
import androidx.room.TypeConverter;

/**
 * 列舉以字串存放而非序數。
 * 序數會在 enum 調整順序時整批錯位，而且匯出 CSV / 備份時看不懂。
 */
public final class Converters {

    @TypeConverter
    @Nullable
    public static String fromSource(@Nullable Source source) {
        return source == null ? null : source.name();
    }

    @TypeConverter
    @Nullable
    public static Source toSource(@Nullable String value) {
        return value == null ? null : Source.valueOf(value);
    }

    @TypeConverter
    @Nullable
    public static String fromDirection(@Nullable Direction direction) {
        return direction == null ? null : direction.name();
    }

    @TypeConverter
    @Nullable
    public static Direction toDirection(@Nullable String value) {
        return value == null ? null : Direction.valueOf(value);
    }

    private Converters() {
    }
}
