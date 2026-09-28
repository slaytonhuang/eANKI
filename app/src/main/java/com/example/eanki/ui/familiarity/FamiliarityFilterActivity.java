package com.example.eanki.ui.familiarity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.eanki.R;
import com.example.eanki.data.user.ReviewStateDao.FamiliarityRow;
import com.example.eanki.databinding.ActivityFamiliarityFilterBinding;
import com.example.eanki.review.Grade;
import com.example.eanki.review.SortMode;
import com.example.eanki.ui.practice.ExtraPracticeActivity;
import com.google.android.material.chip.Chip;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 熟悉度篩選。SPEC 第 5 節。
 *
 * <p>從熟悉度頁的某一級點進來，可切換級別與排序，逐列勾選，
 * 底部「已選 N 個 → 開始加強複習」。
 *
 * <p>畫面本身不持有任何狀態，全在 {@link FamiliarityFilterViewModel}。
 */
public class FamiliarityFilterActivity extends AppCompatActivity {

    private static final String EXTRA_LEVEL = "level_code";

    /** @param level 進來時要看哪一級 */
    public static Intent intent(Context context, @NonNull Grade level) {
        return new Intent(context, FamiliarityFilterActivity.class)
                .putExtra(EXTRA_LEVEL, level.code());
    }

    private ActivityFamiliarityFilterBinding binding;
    private FamiliarityFilterViewModel viewModel;
    private FilterWordAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityFamiliarityFilterBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        viewModel = new ViewModelProvider(this).get(FamiliarityFilterViewModel.class);

        // 只在第一次進來套用；重建 Activity 時 ViewModel 已經有使用者切過的級別了
        if (savedInstanceState == null) {
            viewModel.setLevel(Grade.fromCode(
                    getIntent().getIntExtra(EXTRA_LEVEL, Grade.FORGOT.code())));
        }

        adapter = new FilterWordAdapter(viewModel::toggle);
        binding.list.setLayoutManager(new LinearLayoutManager(this));
        binding.list.setAdapter(adapter);

        buildLevelChips();
        binding.btnSort.setOnClickListener(this::showSortMenu);
        binding.btnSelectAll.setOnClickListener(v -> viewModel.toggleSelectAll());
        binding.btnStart.setOnClickListener(v -> startPractice());

        observe();
    }

    private void buildLevelChips() {
        LayoutInflater inflater = LayoutInflater.from(this);
        for (Grade g : Grade.values()) {
            Chip chip = (Chip) inflater.inflate(
                    R.layout.chip_level, binding.levelChips, false);
            chip.setId(View.generateViewId());
            chip.setTag(g);
            chip.setOnClickListener(v -> viewModel.setLevel(g));
            binding.levelChips.addView(chip);
        }
    }

    private void observe() {
        viewModel.getVisible().observe(this, this::renderList);
        viewModel.getCounts().observe(this, this::renderChipLabels);
        viewModel.getLevel().observe(this, this::renderChipChecked);
        viewModel.getSelected().observe(this, this::renderSelection);
        viewModel.getSort().observe(this, mode ->
                binding.btnSort.setText(sortLabel(mode)));
    }

    private void renderList(@Nullable List<FamiliarityRow> rows) {
        boolean empty = rows == null || rows.isEmpty();
        binding.list.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.emptyNote.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.btnSelectAll.setEnabled(!empty);
        adapter.submitList(rows);
    }

    private void renderChipLabels(@Nullable Map<Grade, Integer> counts) {
        if (counts == null) {
            return;
        }
        for (int i = 0; i < binding.levelChips.getChildCount(); i++) {
            Chip chip = (Chip) binding.levelChips.getChildAt(i);
            Grade g = (Grade) chip.getTag();
            Integer n = counts.get(g);
            chip.setText(getString(R.string.filter_level_chip,
                    getString(LevelPalette.name(g)), n == null ? 0 : n));
        }
    }

    private void renderChipChecked(@Nullable Grade level) {
        for (int i = 0; i < binding.levelChips.getChildCount(); i++) {
            Chip chip = (Chip) binding.levelChips.getChildAt(i);
            chip.setChecked(chip.getTag() == level);
        }
    }

    private void renderSelection(@Nullable Set<Long> selected) {
        int n = selected == null ? 0 : selected.size();
        adapter.setSelected(selected == null ? java.util.Collections.emptySet() : selected);
        binding.selectedCount.setText(getString(R.string.filter_selected, n));
        binding.btnStart.setEnabled(n > 0);

        List<FamiliarityRow> rows = viewModel.getVisible().getValue();
        boolean all = rows != null && !rows.isEmpty() && n == rows.size();
        binding.btnSelectAll.setText(all ? R.string.filter_clear : R.string.filter_select_all);
    }

    private void showSortMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 0, 0, sortLabel(SortMode.STALE_FIRST));
        menu.getMenu().add(0, 1, 1, sortLabel(SortMode.RECENTLY_ADDED));
        menu.getMenu().add(0, 2, 2, sortLabel(SortMode.ALPHABETICAL));
        // menu id 就是 SortMode 的宣告順序，上面三行是照著加的
        menu.setOnMenuItemClickListener(item -> {
            viewModel.setSort(SortMode.values()[item.getItemId()]);
            return true;
        });
        menu.show();
    }

    private String sortLabel(@Nullable SortMode mode) {
        if (mode == null) {
            return "";
        }
        switch (mode) {
            case RECENTLY_ADDED:
                return getString(R.string.sort_recently_added);
            case ALPHABETICAL:
                return getString(R.string.sort_alphabetical);
            case STALE_FIRST:
            default:
                return getString(R.string.sort_stale_first);
        }
    }

    private void startPractice() {
        List<Long> ids = viewModel.selectedInDisplayOrder();
        if (ids.isEmpty()) {
            return;
        }
        startActivity(ExtraPracticeActivity.intent(this, ids));
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
