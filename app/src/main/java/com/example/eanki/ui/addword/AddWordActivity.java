package com.example.eanki.ui.addword;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.example.eanki.R;
import com.example.eanki.data.WordRepository;
import com.example.eanki.data.dict.DictEntry;
import com.example.eanki.databinding.ActivityAddWordBinding;
import com.google.android.material.snackbar.Snackbar;

/**
 * 手動輸入單字：輸入 → 查字典 → 自動帶出音標與中文 → 存進 Room。
 *
 * <p>查詢結果與輸入內容都在 {@link AddWordViewModel}，
 * 摺疊裝置展開/闔上重建 Activity 時不會掉（SPEC 第 7 節第 2 點）。
 */
public class AddWordActivity extends AppCompatActivity {

    private ActivityAddWordBinding binding;
    private AddWordViewModel viewModel;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAddWordBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        viewModel = new ViewModelProvider(this).get(AddWordViewModel.class);

        binding.btnLookup.setOnClickListener(v -> lookUp());
        binding.input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                lookUp();
                return true;
            }
            return false;
        });
        binding.btnSave.setOnClickListener(v -> viewModel.save());

        viewModel.isSearching().observe(this, searching ->
                binding.progress.setVisibility(
                        Boolean.TRUE.equals(searching) ? View.VISIBLE : View.INVISIBLE));

        viewModel.getLookup().observe(this, this::renderLookup);

        viewModel.getSaved().observe(this, event -> {
            WordRepository.SaveResult result = event.consume();
            if (result != null) {
                handleSaveResult(result);
            }
        });
    }

    private void lookUp() {
        String raw = binding.input.getText() == null
                ? "" : binding.input.getText().toString();
        if (!viewModel.lookUp(raw)) {
            binding.inputLayout.setError(getString(R.string.add_empty_input));
            return;
        }
        binding.inputLayout.setError(null);
    }

    private void renderLookup(@Nullable AddWordViewModel.Lookup lookup) {
        if (lookup == null) {
            binding.resultCard.setVisibility(View.GONE);
            binding.btnSave.setVisibility(View.GONE);
            return;
        }

        binding.resultCard.setVisibility(View.VISIBLE);
        binding.btnSave.setVisibility(View.VISIBLE);
        binding.resultHeadword.setText(lookup.word);

        if (lookup.found()) {
            renderFound(lookup.entry);
        } else {
            renderNotFound(lookup.word);
        }
    }

    private void renderFound(DictEntry entry) {
        // 卡片回到一般外框
        binding.resultCard.setStrokeColor(getColor(R.color.border));
        binding.notFoundHint.setVisibility(View.GONE);

        // 標題一律顯示使用者輸入的形式，不換成原形 ——
        // 存進單字本的也是這個形式，使用者要認得出這是他自己打的字
        binding.resultHeadword.setText(entry.displayWord());
        if (entry.viaInflection()) {
            binding.resultInflection.setVisibility(View.VISIBLE);
            binding.resultInflection.setText(getString(R.string.inflection_hint, entry.word));
        } else {
            binding.resultInflection.setVisibility(View.GONE);
        }

        if (TextUtils.isEmpty(entry.phonetic)) {
            binding.resultPhonetic.setVisibility(View.GONE);
        } else {
            binding.resultPhonetic.setVisibility(View.VISIBLE);
            binding.resultPhonetic.setText("/" + entry.phonetic + "/");
        }

        binding.resultTranslation.setVisibility(View.VISIBLE);
        binding.resultTranslation.setTextColor(getColor(R.color.text_primary));
        binding.resultTranslation.setText(
                TextUtils.isEmpty(entry.translation)
                        ? getString(R.string.no_translation)
                        : entry.translation);

        String meta = joinMeta(entry.pos, entry.tag);
        binding.resultMeta.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);
        binding.resultMeta.setText(meta);
    }

    /**
     * SPEC 第 6.1 節：查不到的字以磚紅外框標示並提示，
     * 但不擋住 —— 使用者仍可加入，音標與釋義留空。
     */
    private void renderNotFound(String word) {
        binding.resultCard.setStrokeColor(getColor(R.color.brick_outline));
        binding.resultInflection.setVisibility(View.GONE);
        binding.resultPhonetic.setVisibility(View.GONE);
        binding.resultMeta.setVisibility(View.GONE);

        binding.resultTranslation.setVisibility(View.VISIBLE);
        binding.resultTranslation.setTextColor(getColor(R.color.brick_text));
        binding.resultTranslation.setText(getString(R.string.add_not_found, word));

        binding.notFoundHint.setVisibility(View.VISIBLE);
    }

    private static String joinMeta(@Nullable String pos, @Nullable String tag) {
        boolean hasPos = !TextUtils.isEmpty(pos);
        boolean hasTag = !TextUtils.isEmpty(tag);
        if (hasPos && hasTag) {
            return pos + " · " + tag;
        }
        if (hasPos) {
            return pos;
        }
        return hasTag ? tag : "";
    }

    private void handleSaveResult(WordRepository.SaveResult result) {
        String word = binding.resultHeadword.getText().toString();
        switch (result) {
            case SAVED:
                // 存完就回單字本，讓使用者看到結果
                Snackbar.make(binding.root, getString(R.string.add_saved, word),
                        Snackbar.LENGTH_SHORT).show();
                binding.root.postDelayed(this::finish, 600L);
                break;
            case DUPLICATE:
                Snackbar.make(binding.root, getString(R.string.add_duplicate, word),
                        Snackbar.LENGTH_LONG).show();
                break;
            case FAILED:
            default:
                Snackbar.make(binding.root, R.string.add_failed,
                        Snackbar.LENGTH_LONG).show();
                break;
        }
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
