package com.example.eanki.ui.wordlist;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.eanki.R;
import com.example.eanki.data.user.TagDao;
import com.example.eanki.data.user.WordDao;
import com.example.eanki.data.user.WordEntry;
import com.example.eanki.databinding.FragmentWordListBinding;
import com.example.eanki.data.WordRepository;
import com.example.eanki.search.SearchPattern;
import com.example.eanki.tts.TtsManager;
import com.example.eanki.ui.addword.AddWordActivity;
import com.example.eanki.ui.importer.ImportActivity;
import com.google.android.material.chip.Chip;
import com.google.android.material.snackbar.Snackbar;

import java.util.List;

/**
 * 單字本分頁。SPEC 第 5 節：搜尋、標籤 chip、列表。
 *
 * <p>原本是獨立的 Activity，加上底部四分頁導覽之後改成 Fragment。
 * 版面有 {@code layout/} 與 {@code layout-sw600dp/} 兩份，id 完全一致，
 * 所以這裡不需要判斷螢幕寬度（SPEC 第 7 節：用斷點，不要判斷機型）。
 */
public class WordListFragment extends Fragment implements WordAdapter.Listener {

    private FragmentWordListBinding binding;
    private WordListViewModel viewModel;
    private WordAdapter adapter;

    /** 最近一次收到的清單。篩選條件或總數變動時要重畫文案，但清單本身沒變。 */
    @Nullable
    private List<WordEntry> latestWords;

    /** 有沒有任何標籤。決定 chip 列要不要出現，與清單是否為空無關。 */
    private boolean hasTags;

    /** 上一次畫出來時用的篩選條件，用來判斷清單要不要捲回頂端。 */
    @Nullable
    private String lastFilterQuery;
    private long lastFilterTagId = WordDao.ANY_TAG;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentWordListBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        viewModel = new ViewModelProvider(this).get(WordListViewModel.class);

        adapter = new WordAdapter(this);
        binding.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.list.setAdapter(adapter);

        binding.fabAdd.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), AddWordActivity.class)));
        binding.btnImport.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), ImportActivity.class)));
        // 空狀態的主動作是掃描（SPEC 第 5 節），不是打字
        binding.btnEmptyScan.setOnClickListener(v -> startActivity(
                new Intent(requireContext(), com.example.eanki.ui.scan.ScanActivity.class)));

        setUpSearch();
        binding.btnClearFilter.setOnClickListener(v -> {
            binding.search.setText("");
            viewModel.clearFilter();
        });

        // 只有清單本身變動才重算空狀態；篩選條件與總數變動只重寫文字。
        // 兩者混在一起的話，按「清除篩選」的瞬間會拿舊清單（0 筆）配新條件（無篩選）
        // 判成「單字本是空的」，把搜尋列與標籤列收掉 —— 而且再也不會打開。
        viewModel.getWords().observe(getViewLifecycleOwner(), this::renderWords);
        viewModel.getRowMeta().observe(getViewLifecycleOwner(), adapter::setMeta);
        viewModel.getTags().observe(getViewLifecycleOwner(), this::renderTagChips);
        viewModel.getTotalCount().observe(getViewLifecycleOwner(), n -> renderTexts());
        viewModel.getFilter().observe(getViewLifecycleOwner(), f -> renderTexts());
        viewModel.getDeleted().observe(getViewLifecycleOwner(), event -> {
            WordEntry word = event.consume();
            if (word != null) {
                showUndo(word);
            }
        });
    }

    /**
     * 搜尋輸入。ViewModel 才是真正的來源 —— 摺疊重建 Fragment 後要把字補回來，
     * 而且要在掛 TextWatcher 之前補，否則會多觸發一次查詢。
     */
    private void setUpSearch() {
        String restored = viewModel.currentFilter().query;
        if (!restored.isEmpty()) {
            binding.search.setText(restored);
            binding.search.setSelection(restored.length());
        }
        binding.search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                viewModel.setQuery(s == null ? "" : s.toString());
            }
        });
        binding.search.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                return true;
            }
            return false;
        });
    }

    /**
     * 標籤 chip（SPEC 第 5 節）。第一顆固定是「全部」。
     *
     * <p>整列在沒有任何標籤時收掉 —— 只有一顆「全部」的 chip 列不提供任何選擇，
     * 純粹佔掉清單的高度。
     */
    private void renderTagChips(@Nullable List<TagDao.TagCount> tags) {
        hasTags = tags != null && !tags.isEmpty();
        binding.tagChips.removeAllViews();
        if (!hasTags) {
            // 標籤被刪光時，正在作用中的標籤篩選也要跟著解除，
            // 否則清單會永遠是空的而且沒有 chip 可以按回來
            binding.tagScroll.setVisibility(View.GONE);
            viewModel.setTag(WordDao.ANY_TAG);
            return;
        }

        addTagChip(WordDao.ANY_TAG, getString(R.string.wordlist_tag_all), -1);
        for (TagDao.TagCount tag : tags) {
            addTagChip(tag.id, tag.name, tag.count);
        }
        highlightTagChip(viewModel.currentFilter().tagId);
    }

    private void addTagChip(long tagId, String name, int count) {
        Chip chip = (Chip) getLayoutInflater()
                .inflate(R.layout.chip_level, binding.tagChips, false);
        chip.setId(View.generateViewId());
        chip.setTag(tagId);
        chip.setText(count < 0 ? name : getString(R.string.filter_level_chip, name, count));
        chip.setOnClickListener(v -> {
            viewModel.setTag(tagId);
            highlightTagChip(tagId);
        });
        binding.tagChips.addView(chip);
    }

    private void highlightTagChip(long tagId) {
        for (int i = 0; i < binding.tagChips.getChildCount(); i++) {
            Chip chip = (Chip) binding.tagChips.getChildAt(i);
            chip.setChecked(Long.valueOf(tagId).equals(chip.getTag()));
        }
    }

    /**
     * 清單本身變動。空狀態的判斷只在這裡做 —— 這是唯一知道「現在真的有幾筆」的地方。
     */
    private void renderWords(@Nullable List<WordEntry> words) {
        if (binding == null) {
            return;
        }
        latestWords = words;

        // 換了篩選條件就回到頂端。不回的話，打完關鍵字看到的會是清單中段，
        // 最相關的那幾筆（完全相同、開頭相符）反而被捲到看不見的上方。
        // 刪除單字之類的清單變動不算換條件，捲動位置要留著。
        WordListViewModel.Filter filter = viewModel.currentFilter();
        boolean filterChanged = lastFilterQuery == null
                || !lastFilterQuery.equals(filter.query)
                || lastFilterTagId != filter.tagId;
        lastFilterQuery = filter.query;
        lastFilterTagId = filter.tagId;

        if (filterChanged) {
            // scrollToPositionWithOffset 而不是 scrollToPosition：
            // 後者在目標項目已經部分可見時不會調整偏移，第一列會被切掉上半截。
            adapter.submitList(words, () -> {
                if (binding == null) {
                    return;
                }
                RecyclerView.LayoutManager lm = binding.list.getLayoutManager();
                if (lm instanceof LinearLayoutManager) {
                    ((LinearLayoutManager) lm).scrollToPositionWithOffset(0, 0);
                }
            });
        } else {
            adapter.submitList(words);
        }

        int shownCount = words == null ? 0 : words.size();
        // 「單字本是空的」以總數為準，不以這份清單為準：
        // 篩選沒中的時候清單也是 0 筆，但那要顯示完全不同的文案。
        // 總數還沒回來時（兩條 LiveData 誰先到不保證）才退回用清單判斷，
        // 否則首次載入會閃一下空狀態。
        Integer total = viewModel.getTotalCount().getValue();
        boolean libraryEmpty = total == null
                ? shownCount == 0 && !filter.isActive()
                : total == 0;
        boolean filteredOut = !libraryEmpty && shownCount == 0;

        binding.emptyState.setVisibility(libraryEmpty ? View.VISIBLE : View.GONE);
        if (libraryEmpty) {
            renderDictReady();
        }
        binding.noResults.setVisibility(filteredOut ? View.VISIBLE : View.GONE);
        binding.list.setVisibility(shownCount == 0 ? View.GONE : View.VISIBLE);

        // 篩選沒中時搜尋列與標籤列要留著 —— 使用者得看得到自己打了什麼才改得掉
        binding.title.setVisibility(libraryEmpty ? View.GONE : View.VISIBLE);
        binding.subtitle.setVisibility(libraryEmpty ? View.GONE : View.VISIBLE);
        binding.searchLayout.setVisibility(libraryEmpty ? View.GONE : View.VISIBLE);
        binding.tagScroll.setVisibility(hasTags && !libraryEmpty ? View.VISIBLE : View.GONE);
        // 匯入鍵不跟著空狀態隱藏 —— 單字本空的時候正是最需要匯入的時候
        binding.btnImport.setVisibility(View.VISIBLE);

        renderTexts();
    }

    /**
     * 「離線字典已就緒」（SPEC 第 5 節空狀態）。
     *
     * <p>第一次打開 App 時最想知道的是「這東西要不要連網、要不要先下載」。
     * 字典還在複製的時候不顯示 —— 顯示了又不能用比沒顯示糟。
     */
    private void renderDictReady() {
        WordRepository repo = WordRepository.get(requireContext());
        repo.io().execute(() -> {
            boolean ready = repo.isDictionaryReady();
            if (binding == null) {
                return;
            }
            binding.emptyDictReady.post(() -> {
                if (binding != null) {
                    binding.emptyDictReady.setVisibility(ready ? View.VISIBLE : View.GONE);
                }
            });
        });
    }

    /** 只重寫文字，不碰任何 visibility。篩選條件或總數變動時走這條。 */
    private void renderTexts() {
        if (binding == null) {
            return;
        }
        WordListViewModel.Filter filter = viewModel.currentFilter();
        Integer total = viewModel.getTotalCount().getValue();
        int shownCount = latestWords == null ? 0 : latestWords.size();

        binding.subtitle.setText(filter.isActive() && total != null
                ? getString(R.string.wordlist_count_filtered, shownCount, total)
                : getString(R.string.wordlist_count, shownCount));

        binding.noResultsText.setText(SearchPattern.isBlank(filter.query)
                ? getString(R.string.wordlist_no_results_tag)
                : getString(R.string.wordlist_no_results, filter.query.trim()));
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager)
                requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(binding.search.getWindowToken(), 0);
        }
    }

    @Override
    public void onSpeak(WordEntry word) {
        TtsManager.get().speak(word.headword);
    }

    @Override
    public void onDelete(WordEntry word) {
        // 刪除會連帶刪掉 review_state 與 review_log（FK CASCADE），值得先問一聲
        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.delete_confirm_title, word.headword))
                .setMessage(R.string.delete_confirm_body)
                .setNegativeButton(R.string.delete_cancel, null)
                .setPositiveButton(R.string.delete_confirm_ok,
                        (d, which) -> viewModel.delete(word))
                .show();
    }

    private void showUndo(WordEntry word) {
        Snackbar.make(binding.getRoot(),
                        getString(R.string.deleted_undo, word.headword),
                        Snackbar.LENGTH_LONG)
                .setAnchorView(binding.fabAdd)
                .setAction(R.string.undo, v -> viewModel.undoDelete())
                .show();
    }

    @Override
    public void onDestroyView() {
        latestWords = null;
        hasTags = false;
        lastFilterQuery = null;
        binding = null;
        super.onDestroyView();
    }
}
