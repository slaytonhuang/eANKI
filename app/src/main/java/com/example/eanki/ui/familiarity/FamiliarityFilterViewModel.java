package com.example.eanki.ui.familiarity;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.eanki.data.ReviewRepository;
import com.example.eanki.data.user.Direction;
import com.example.eanki.data.user.ReviewStateDao.FamiliarityRow;
import com.example.eanki.review.FamiliarityRule;
import com.example.eanki.review.Grade;
import com.example.eanki.review.SortMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 熟悉度篩選畫面的全部狀態。SPEC 第 5 節。
 *
 * <p><b>勾選結果放在這裡，不放 Activity。</b>SPEC 第 7 節第 2 點：
 * 摺疊會重建 Activity。使用者從 53 個字裡挑了 18 個，一展開內螢幕全部清空，
 * 是這個畫面最容易踩、最惱人的 bug。
 */
public class FamiliarityFilterViewModel extends AndroidViewModel {

    private final LiveData<List<FamiliarityRow>> allRows;

    private final MutableLiveData<Grade> level = new MutableLiveData<>(Grade.FORGOT);
    private final MutableLiveData<SortMode> sort =
            new MutableLiveData<>(SortMode.STALE_FIRST);
    private final MutableLiveData<Set<Long>> selected =
            new MutableLiveData<>(new LinkedHashSet<>());

    private final MediatorLiveData<List<FamiliarityRow>> visible = new MediatorLiveData<>();
    private final MediatorLiveData<Map<Grade, Integer>> counts = new MediatorLiveData<>();

    public FamiliarityFilterViewModel(@NonNull Application application) {
        super(application);
        allRows = ReviewRepository.get(application)
                .observeFamiliarityRows(Direction.EN_TO_ZH);

        visible.addSource(allRows, rows -> recompute());
        visible.addSource(level, g -> recompute());
        visible.addSource(sort, s -> recompute());
        counts.addSource(allRows, rows -> recount());
    }

    public LiveData<List<FamiliarityRow>> getVisible() {
        return visible;
    }

    /** 四級各有幾個字，給上方的級別切換鍵顯示數量。 */
    public LiveData<Map<Grade, Integer>> getCounts() {
        return counts;
    }

    public LiveData<Grade> getLevel() {
        return level;
    }

    public LiveData<SortMode> getSort() {
        return sort;
    }

    public LiveData<Set<Long>> getSelected() {
        return selected;
    }

    /**
     * 切換級別時<b>清空勾選</b>。
     *
     * <p>跨級別累積勾選會讓底部「已選 18 個」指向使用者當下看不到的字，
     * 按下去才發現練到別級的字。要練多級就分兩趟。
     */
    public void setLevel(Grade next) {
        if (next == level.getValue()) {
            return;
        }
        selected.setValue(new LinkedHashSet<>());
        level.setValue(next);
    }

    public void setSort(SortMode next) {
        sort.setValue(next);
    }

    public void toggle(long wordId) {
        Set<Long> now = new LinkedHashSet<>(currentSelection());
        if (!now.remove(wordId)) {
            now.add(wordId);
        }
        selected.setValue(now);
    }

    public boolean isSelected(long wordId) {
        return currentSelection().contains(wordId);
    }

    /** 全選目前這一級可見的字；已經全選時改成全部取消。 */
    public void toggleSelectAll() {
        List<FamiliarityRow> rows = visible.getValue();
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Set<Long> now = new LinkedHashSet<>();
        if (currentSelection().size() < rows.size()) {
            for (FamiliarityRow row : rows) {
                now.add(row.wordId);
            }
        }
        selected.setValue(now);
    }

    /**
     * 勾選的字，<b>順序照畫面上看到的順序</b>。
     *
     * <p>{@code Set} 的迭代順序是「按下的先後」，不是畫面順序 ——
     * 使用者從下往上勾的話，開始練的順序就會跟他看到的相反。
     */
    @NonNull
    public List<Long> selectedInDisplayOrder() {
        Set<Long> chosen = currentSelection();
        List<FamiliarityRow> rows = visible.getValue();
        if (rows == null) {
            return new ArrayList<>(chosen);
        }
        List<Long> ordered = new ArrayList<>(chosen.size());
        for (FamiliarityRow row : rows) {
            if (chosen.contains(row.wordId)) {
                ordered.add(row.wordId);
            }
        }
        return ordered;
    }

    @NonNull
    private Set<Long> currentSelection() {
        Set<Long> s = selected.getValue();
        return s == null ? Collections.emptySet() : s;
    }

    private void recompute() {
        List<FamiliarityRow> rows = allRows.getValue();
        Grade target = level.getValue();
        if (rows == null || target == null) {
            return;
        }
        List<FamiliarityRow> kept = new ArrayList<>();
        for (FamiliarityRow row : rows) {
            if (FamiliarityRule.ofCodes(row.grade1, row.grade2, row.grade3) == target) {
                kept.add(row);
            }
        }
        SortMode mode = sort.getValue();
        Collections.sort(kept, (mode == null ? SortMode.STALE_FIRST : mode).comparator());
        visible.setValue(kept);
    }

    private void recount() {
        List<FamiliarityRow> rows = allRows.getValue();
        if (rows == null) {
            return;
        }
        Map<Grade, Integer> tally = new EnumMap<>(Grade.class);
        for (Grade g : Grade.values()) {
            tally.put(g, 0);
        }
        for (FamiliarityRow row : rows) {
            Grade g = FamiliarityRule.ofCodes(row.grade1, row.grade2, row.grade3);
            if (g != null) {
                tally.put(g, tally.get(g) + 1);
            }
        }
        counts.setValue(tally);
    }

    @Nullable
    public Grade currentLevel() {
        return level.getValue();
    }
}
