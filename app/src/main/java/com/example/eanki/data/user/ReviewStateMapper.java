package com.example.eanki.data.user;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.eanki.review.FamiliarityRule;
import com.example.eanki.review.Grade;
import com.example.eanki.review.Schedule;
import com.example.eanki.review.Sm2Scheduler;

/**
 * Room 的 {@link ReviewState} 與純 Java 的 {@link Schedule} 之間的轉換。
 *
 * <p>這一層存在的理由：讓 {@code com.example.eanki.review} 裡的排程邏輯完全不認識 Room，
 * 才能用一般 JVM 單元測試跑（SPEC 第 8 節 Phase 2：先寫單元測試再寫 UI）。
 */
public final class ReviewStateMapper {

    public static Schedule toSchedule(ReviewState state) {
        return new Schedule(
                state.interval,
                state.easeFactor,
                state.repetitions,
                state.lapses,
                state.dueDate,
                state.lastReviewed);
    }

    /**
     * 套用一次複習：算出新排程寫回 entity，並把這次評分滾進最近三次紀錄。
     *
     * @return 傳入的同一個 {@code state}，已就地更新
     */
    public static ReviewState applyReview(
            @NonNull ReviewState state, @NonNull Grade grade, long reviewedAt) {

        Schedule next = Sm2Scheduler.next(toSchedule(state), grade, reviewedAt);

        state.interval = next.intervalMinutes;
        state.easeFactor = next.easeFactor;
        state.repetitions = next.repetitions;
        state.lapses = next.lapses;
        state.dueDate = next.dueAt;
        state.lastReviewed = next.lastReviewedAt;

        rollGrades(state, grade);
        return state;
    }

    /** 三個欄位往後推一格，最新的放 grade_1（SPEC 第 3 節：滾動儲存，不另開表）。 */
    private static void rollGrades(ReviewState state, Grade grade) {
        state.grade3 = state.grade2;
        state.grade2 = state.grade1;
        state.grade1 = grade.code();
    }

    /** 目前的熟悉度等級，從未複習過回傳 null。SPEC 第 6.5 節。 */
    @Nullable
    public static Grade familiarity(ReviewState state) {
        return FamiliarityRule.ofCodes(state.grade1, state.grade2, state.grade3);
    }

    /**
     * 加強複習專用：<b>只更新熟悉度紀錄，完全不動排程。</b>
     *
     * <p>SPEC 第 6.6 節用粗體點名這件事 —— 加強複習的結果若寫回 {@code due_date}，
     * 每練一次就把這些字的排程往前擠，間隔越練越短，最後每天都在複習同一批字。
     */
    public static ReviewState applyExtraPractice(
            @NonNull ReviewState state, @NonNull Grade grade) {
        rollGrades(state, grade);
        return state;
    }

    private ReviewStateMapper() {
    }
}
