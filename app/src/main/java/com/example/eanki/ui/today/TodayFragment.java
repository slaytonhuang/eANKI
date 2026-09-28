package com.example.eanki.ui.today;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.eanki.R;
import com.example.eanki.data.WordRepository;
import com.example.eanki.data.user.Direction;
import com.example.eanki.data.user.WordEntry;
import com.example.eanki.databinding.FragmentTodayBinding;
import com.example.eanki.databinding.ItemTodayRecentBinding;
import com.example.eanki.review.SevenDayWindow;
import com.example.eanki.ui.addword.AddWordActivity;
import com.example.eanki.ui.importer.ImportActivity;
import com.example.eanki.ui.review.ReviewActivity;
import com.example.eanki.ui.scan.ScanActivity;

import java.util.Calendar;
import java.util.List;

/** 今日頁。SPEC 第 5 節。 */
public class TodayFragment extends Fragment {

    private static final String[] WEEKDAY = {"", "日", "一", "二", "三", "四", "五", "六"};

    private FragmentTodayBinding binding;
    private TodayViewModel viewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentTodayBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        viewModel = new ViewModelProvider(this).get(TodayViewModel.class);

        binding.btnStartReview.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), ReviewActivity.class)));
        binding.btnEmptyScan.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), ScanActivity.class)));
        binding.btnEmptyManual.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), AddWordActivity.class)));
        binding.btnEmptyPhoto.setOnClickListener(v ->
                startActivity(ScanActivity.pickPhotoIntent(requireContext())));
        binding.btnEmptyImport.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), ImportActivity.class)));
        binding.btnDirection.setOnClickListener(v -> viewModel.toggleDirection());

        buildHeatCells();

        viewModel.getAllWords().observe(getViewLifecycleOwner(), this::renderEmptyState);
        viewModel.getCounts().observe(getViewLifecycleOwner(), this::renderCounts);
        viewModel.getRecent().observe(getViewLifecycleOwner(), this::renderRecent);
        viewModel.getHeatBuckets().observe(getViewLifecycleOwner(), this::renderHeat);
        viewModel.getDirection().observe(getViewLifecycleOwner(), d ->
                binding.btnDirection.setText(d == Direction.EN_TO_ZH
                        ? R.string.review_dir_en_zh : R.string.review_dir_zh_en));

        showDictionaryStatus();
    }

    /**
     * SPEC 第 5 節空狀態：單字本完全是空的時候顯示引導，
     * 而且<b>不顯示空的熱力圖</b> —— 七格全灰對第一次開 App 的人沒有意義。
     */
    private void renderEmptyState(@Nullable List<WordEntry> words) {
        boolean empty = words == null || words.isEmpty();
        binding.emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.content.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void renderCounts(int[] counts) {
        int due = counts[0];
        int newAvailable = counts[1];
        int total = due + newAvailable;

        binding.dueCount.setText(String.valueOf(total));
        binding.newCount.setText(getString(R.string.today_new_label, newAvailable));

        boolean hasWork = total > 0;
        binding.btnStartReview.setEnabled(hasWork);
        binding.dueLabel.setText(hasWork
                ? getString(R.string.today_due_label)
                : getString(R.string.today_nothing_due));
        binding.btnStartReview.setText(hasWork
                ? getString(R.string.review_start)
                : getString(R.string.today_nothing_due_sub));
    }

    private void renderRecent(@Nullable List<WordEntry> words) {
        binding.recentList.removeAllViews();
        boolean any = words != null && !words.isEmpty();
        binding.recentTitle.setVisibility(any ? View.VISIBLE : View.GONE);
        if (!any) {
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (WordEntry word : words) {
            ItemTodayRecentBinding row =
                    ItemTodayRecentBinding.inflate(inflater, binding.recentList, false);
            row.recentHeadword.setText(word.headword);
            row.recentTranslation.setText(TextUtils.isEmpty(word.translation)
                    ? getString(R.string.no_translation)
                    : word.translation.replace("\n", " / "));
            binding.recentList.addView(row.getRoot());
        }
    }

    // ---- 熱力圖 ----

    /** 先把七格建出來，之後只改顏色與數字，不重建 View。 */
    private void buildHeatCells() {
        binding.heatRow.removeAllViews();
        int[] dayOfWeek = SevenDayWindow.dayOfWeek(System.currentTimeMillis());
        int cell = getResources().getDimensionPixelSize(R.dimen.heat_cell);
        int gap = getResources().getDimensionPixelSize(R.dimen.spacing_xs);

        for (int i = 0; i < SevenDayWindow.DAYS; i++) {
            LinearLayout column = new LinearLayout(requireContext());
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams columnParams =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            columnParams.setMarginEnd(i == SevenDayWindow.DAYS - 1 ? 0 : gap);
            column.setLayoutParams(columnParams);

            View box = new View(requireContext());
            box.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, cell));
            box.setTag("box");
            column.addView(box);

            TextView label = new TextView(requireContext());
            label.setText(WEEKDAY[dayOfWeek[i]]);
            label.setTextSize(11f);
            label.setPadding(0, gap, 0, 0);
            label.setTextColor(requireContext().getColor(R.color.text_muted));
            column.addView(label);

            binding.heatRow.addView(column);
        }
    }

    /**
     * 五階固定刻度上色。SPEC 第 4 節：主色薄荷代表「正向、已掌握」，
     * 所以複習得越多顏色越接近主色；當天沒複習的用 surface2 而不是純黑，
     * 避免看起來像缺格。
     */
    private void renderHeat(int[] buckets) {
        int radius = getResources().getDimensionPixelSize(R.dimen.radius_s) / 2;
        int reviewedDays = 0;

        for (int i = 0; i < SevenDayWindow.DAYS && i < binding.heatRow.getChildCount(); i++) {
            LinearLayout column = (LinearLayout) binding.heatRow.getChildAt(i);
            View box = column.findViewWithTag("box");
            int count = buckets[i];
            if (count > 0) {
                reviewedDays++;
            }

            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(radius);
            shape.setColor(heatColor(SevenDayWindow.intensity(count)));
            box.setBackground(shape);
            box.setContentDescription(getString(R.string.today_heatmap_title) + " " + count);
        }

        binding.heatEmptyNote.setVisibility(reviewedDays == 0 ? View.VISIBLE : View.GONE);
    }

    private int heatColor(int intensity) {
        int color;
        switch (intensity) {
            case 1:
                color = R.color.mint_card;
                break;
            case 2:
                color = R.color.mint_dark;
                break;
            case 3:
                color = R.color.mint_2;
                break;
            case 4:
                color = R.color.mint;
                break;
            default:
                color = R.color.surface2;
                break;
        }
        return requireContext().getColor(color);
    }

    private void showDictionaryStatus() {
        WordRepository repo = WordRepository.get(requireContext());
        repo.io().execute(() -> {
            boolean ready = repo.isDictionaryReady();
            View root = getView();
            if (root == null) {
                return;
            }
            root.post(() -> {
                if (binding == null) {
                    return;
                }
                binding.dictStatus.setText(
                        ready ? R.string.empty_dict_ready : R.string.empty_dict_loading);
                binding.dictStatus.setTextColor(requireContext().getColor(
                        ready ? R.color.mint : R.color.text_muted));
            });
        });
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
