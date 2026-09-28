package com.example.eanki.ui.importer;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.example.eanki.R;
import com.example.eanki.data.ImportRepository;
import com.example.eanki.databinding.ActivityImportBinding;

/**
 * CSV 匯入畫面。SPEC 第 5 節空狀態的「匯入 CSV」。
 *
 * <p>用系統的檔案選擇器（SAF）取得檔案，<b>不需要儲存空間權限</b> ——
 * 使用者自己挑的那一個檔案才會被讀到，符合 SPEC 第 2 節的克制原則。
 */
public class ImportActivity extends AppCompatActivity {

    /** 預設標籤。使用者可以改，留空就不貼。 */
    private static final String DEFAULT_TAG = "多益";

    private ActivityImportBinding binding;
    private ImportViewModel viewModel;

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    startImport(uri);
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityImportBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        viewModel = new ViewModelProvider(this).get(ImportViewModel.class);

        if (savedInstanceState == null) {
            binding.tagInput.setText(DEFAULT_TAG);
        }

        binding.btnDone.setOnClickListener(v -> finish());
        binding.btnPick.setOnClickListener(v ->
                // 有些檔案管理器把 CSV 報成 text/comma-separated-values 或 application/octet-stream，
                // 限定太死會讓使用者在選擇器裡看到檔案是灰的
                picker.launch(new String[]{"text/csv", "text/comma-separated-values",
                        "text/plain", "application/octet-stream", "*/*"}));

        viewModel.getStage().observe(this, this::render);
        viewModel.getProgress().observe(this, this::renderProgress);
        viewModel.getResult().observe(this, this::renderResult);
        viewModel.getTagTotal().observe(this, total -> renderResult(viewModel.getResult().getValue()));
        viewModel.getError().observe(this, message -> {
            if (message != null) {
                binding.resultCard.setVisibility(View.VISIBLE);
                binding.resultTitle.setText(R.string.import_failed_title);
                binding.resultBody.setText(getString(R.string.import_failed, message));
            }
        });
    }

    private void startImport(Uri uri) {
        String tag = binding.tagInput.getText() == null
                ? "" : binding.tagInput.getText().toString();
        viewModel.start(uri, tag);
    }

    private void render(ImportViewModel.Stage stage) {
        boolean busy = stage == ImportViewModel.Stage.READING
                || stage == ImportViewModel.Stage.IMPORTING;

        binding.btnPick.setEnabled(!busy);
        binding.tagLayout.setEnabled(!busy);
        binding.progressBox.setVisibility(busy ? View.VISIBLE : View.GONE);

        if (stage == ImportViewModel.Stage.READING) {
            binding.progressText.setText(R.string.import_reading);
            binding.progressBar.setIndeterminate(true);
        } else if (stage == ImportViewModel.Stage.IMPORTING) {
            binding.progressBar.setIndeterminate(false);
        }
    }

    private void renderProgress(int[] value) {
        int done = value[0];
        int total = value[1];
        if (total <= 0) {
            return;
        }
        binding.progressText.setText(getString(R.string.import_progress, done, total));
        binding.progressBar.setProgress(done * 100 / total);
    }

    private void renderResult(@Nullable ImportRepository.Result result) {
        if (result == null) {
            return;
        }
        binding.resultCard.setVisibility(View.VISIBLE);
        binding.resultTitle.setText(R.string.import_result_title);

        StringBuilder body = new StringBuilder(
                getString(R.string.import_result_body, result.inserted, result.skipped));
        if (result.examples > 0) {
            body.append(getString(R.string.import_result_examples, result.examples));
        }
        if (result.tagName != null && !result.tagName.trim().isEmpty()) {
            Integer total = viewModel.getTagTotal().getValue();
            body.append('\n').append(getString(R.string.import_result_tag,
                    result.tagName.trim(), total == null ? result.inserted : total));
        }
        // 部首碼位一律在解析時還原；對照表沒涵蓋的要講出來，
        // 不然那些字肉眼看不出異常，使用者只會發現「搜尋搜不到」
        if (!result.unmappedRadicals.isEmpty()) {
            StringBuilder chars = new StringBuilder();
            for (Character c : result.unmappedRadicals) {
                if (chars.length() > 0) {
                    chars.append(' ');
                }
                chars.append(c).append(String.format(java.util.Locale.US, "(U+%04X)", (int) c));
            }
            body.append('\n').append(getString(R.string.import_result_unmapped, chars));
        }
        if (!result.simplifiedChars.isEmpty()) {
            StringBuilder chars = new StringBuilder();
            for (Character c : result.simplifiedChars) {
                if (chars.length() > 0) {
                    chars.append(' ');
                }
                chars.append(c);
            }
            body.append('\n').append(getString(R.string.import_result_simplified, chars));
        }
        binding.resultBody.setText(body.toString());
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
