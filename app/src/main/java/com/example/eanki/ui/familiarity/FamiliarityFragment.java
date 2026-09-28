package com.example.eanki.ui.familiarity;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.eanki.R;
import com.example.eanki.databinding.FragmentFamiliarityBinding;
import com.example.eanki.databinding.ItemFamiliarityLevelBinding;
import com.example.eanki.review.ExtraPracticeQueue;
import com.example.eanki.review.FamiliarityStats;
import com.example.eanki.review.Grade;
import com.example.eanki.ui.practice.ExtraPracticeActivity;
import com.google.android.material.snackbar.Snackbar;

import java.util.List;

/**
 * 熟悉度分布。SPEC 第 5 節。
 *
 * <p>顏色照 SPEC 第 4 節的語意：磚紅＝需要處理、琥珀＝中間狀態、薄荷＝正向。
 * 「尚未開始」用中性灰，因為它不屬於那三個語意的任何一個。
 */
public class FamiliarityFragment extends Fragment {

    private FragmentFamiliarityBinding binding;
    private FamiliarityViewModel viewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentFamiliarityBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        viewModel = new ViewModelProvider(this).get(FamiliarityViewModel.class);

        // 四級的列點進去挑字；磚紅卡片的兩顆按鈕直接開練（SPEC 第 6.6 節）
        binding.rowForgot.getRoot().setOnClickListener(v -> openFilter(Grade.FORGOT));
        binding.rowHard.getRoot().setOnClickListener(v -> openFilter(Grade.HARD));
        binding.rowGood.getRoot().setOnClickListener(v -> openFilter(Grade.GOOD));
        binding.rowEasy.getRoot().setOnClickListener(v -> openFilter(Grade.EASY));

        binding.btnPracticeAll.setOnClickListener(
                v -> startPractice(ExtraPracticeQueue.NO_LIMIT));
        binding.btnPracticeTwenty.setOnClickListener(
                v -> startPractice(ExtraPracticeQueue.SAMPLE_SIZE));

        viewModel.getStats().observe(getViewLifecycleOwner(), this::render);
    }

    private void render(@Nullable FamiliarityStats stats) {
        if (stats == null || binding == null) {
            return;
        }

        boolean anyGraded = stats.gradedCount() > 0;
        binding.emptyNote.setVisibility(anyGraded ? View.GONE : View.VISIBLE);
        binding.stackBar.setVisibility(anyGraded ? View.VISIBLE : View.GONE);
        binding.strugglingCard.setVisibility(anyGraded ? View.VISIBLE : View.GONE);

        binding.gradedSummary.setText(
                getString(R.string.familiarity_graded, stats.gradedCount()));

        // 堆疊條：四段寬度就是四級在「已評分的字」裡的比例。
        // 分母刻意不含尚未開始，否則 1,338 比 126 會把四段全部壓成看不見。
        boolean hasForgot = weight(binding.barForgot, stats.ratio(Grade.FORGOT));
        boolean hasHard = weight(binding.barHard, stats.ratio(Grade.HARD));
        boolean hasGood = weight(binding.barGood, stats.ratio(Grade.GOOD));
        boolean hasEasy = weight(binding.barEasy, stats.ratio(Grade.EASY));

        // 某一段是 0 的時候，它旁邊的間隔也要收掉，否則會出現雙倍空隙
        binding.gapForgotHard.setVisibility(hasForgot && hasHard ? View.VISIBLE : View.GONE);
        binding.gapHardGood.setVisibility(
                (hasForgot || hasHard) && hasGood ? View.VISIBLE : View.GONE);
        binding.gapGoodEasy.setVisibility(
                (hasForgot || hasHard || hasGood) && hasEasy ? View.VISIBLE : View.GONE);

        bindLevel(binding.rowForgot, Grade.FORGOT, stats.count(Grade.FORGOT));
        bindLevel(binding.rowHard, Grade.HARD, stats.count(Grade.HARD));
        bindLevel(binding.rowGood, Grade.GOOD, stats.count(Grade.GOOD));
        bindLevel(binding.rowEasy, Grade.EASY, stats.count(Grade.EASY));

        // 尚未開始不是一個「級」，不可點、用中性灰
        bindRow(binding.rowNotStarted, R.color.text_muted, R.string.familiarity_not_started,
                stats.notStartedCount());

        int struggling = stats.strugglingCount();
        binding.strugglingTitle.setText(
                getString(R.string.familiarity_struggling, struggling));
        // 一個不熟的字都沒有時，兩顆按鈕按下去會開一個空畫面，直接停用
        binding.btnPracticeAll.setEnabled(struggling > 0);
        binding.btnPracticeTwenty.setEnabled(struggling > 0);
    }

    /**
     * 某一段為 0 時要真的收成 0 寬，不能留殘影。
     * {@code layout_weight} 為 0 且寬度也是 0 的 View 不會佔位。
     */
    private static boolean weight(View segment, float ratio) {
        LinearLayout.LayoutParams params =
                (LinearLayout.LayoutParams) segment.getLayoutParams();
        params.weight = ratio;
        segment.setLayoutParams(params);
        boolean visible = ratio > 0f;
        segment.setVisibility(visible ? View.VISIBLE : View.GONE);
        return visible;
    }

    /** 四級的列：色點走 {@link LevelPalette}，整列可點進篩選畫面。 */
    private void bindLevel(@NonNull ItemFamiliarityLevelBinding row, Grade level, int count) {
        bindRow(row, LevelPalette.color(level), LevelPalette.name(level), count);
        row.getRoot().setEnabled(count > 0);
        row.getRoot().setAlpha(count > 0 ? 1f : 0.45f);
    }

    private void openFilter(Grade level) {
        startActivity(FamiliarityFilterActivity.intent(requireContext(), level));
    }

    /**
     * 「不太熟的字」的加強複習。忘了在前、有點難在後，
     * 挑 20 個時才會挑到最該練的（{@link ExtraPracticeQueue#struggling}）。
     */
    private void startPractice(int limit) {
        FamiliarityStats stats = viewModel.getStats().getValue();
        if (stats == null) {
            return;
        }
        List<Long> ids = ExtraPracticeQueue.struggling(stats, limit);
        if (ids.isEmpty()) {
            Snackbar.make(binding.getRoot(), R.string.practice_nothing,
                    Snackbar.LENGTH_SHORT).show();
            return;
        }
        startActivity(ExtraPracticeActivity.intent(requireContext(), ids));
    }

    /** ViewBinding 會把 {@code <include>} 直接綁成子 binding，不必再 bind 一次。 */
    private void bindRow(@NonNull ItemFamiliarityLevelBinding row, @ColorRes int color,
                         @StringRes int name, int count) {
        row.levelDot.setBackgroundColor(requireContext().getColor(color));
        row.levelName.setText(name);
        row.levelCount.setText(String.valueOf(count));
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
