package com.example.eanki.ui.practice;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.example.eanki.R;
import com.example.eanki.data.CardExamples;
import com.example.eanki.data.WordRepository;
import com.example.eanki.data.settings.AppSettings;
import com.example.eanki.data.user.WordEntry;
import com.example.eanki.databinding.ActivityExtraPracticeBinding;
import com.example.eanki.databinding.ViewExampleBinding;
import com.example.eanki.review.Grade;
import com.example.eanki.tts.TtsManager;
import com.google.android.material.snackbar.Snackbar;

import java.util.List;

/**
 * 加強複習。SPEC 第 6.6 節。
 *
 * <p>翻卡與評分的手感跟一般複習一樣（共用 {@code view_grade_row.xml}），
 * 唯一的差別是評分只更新熟悉度、不動排程 —— 所以按鈕下方的預估間隔整排收掉，
 * 頂端改成常駐的「不影響原本的排程」。
 */
public class ExtraPracticeActivity extends AppCompatActivity {

    private static final String EXTRA_WORD_IDS = "word_ids";

    public static Intent intent(Context context, @NonNull List<Long> wordIds) {
        long[] ids = new long[wordIds.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = wordIds.get(i);
        }
        return new Intent(context, ExtraPracticeActivity.class)
                .putExtra(EXTRA_WORD_IDS, ids);
    }

    private ActivityExtraPracticeBinding binding;
    private ExtraPracticeViewModel viewModel;
    private AppSettings settings;
    private boolean wasFlipped;

    /** 這個 Activity 建立後的第一次 renderFace。重建時不該被當成「剛翻過去」。 */
    private boolean firstFaceRender = true;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityExtraPracticeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        viewModel = new ViewModelProvider(this).get(ExtraPracticeViewModel.class);
        settings = AppSettings.get(this);

        // 加強複習不改排程，顯示「4 天」會是謊話（SPEC 第 6.6 節）
        binding.gradeRow.intervalForgot.setVisibility(View.GONE);
        binding.gradeRow.intervalHard.setVisibility(View.GONE);
        binding.gradeRow.intervalGood.setVisibility(View.GONE);
        binding.gradeRow.intervalEasy.setVisibility(View.GONE);

        binding.card.setOnClickListener(v -> viewModel.flip());
        binding.btnShowAnswer.setOnClickListener(v -> viewModel.showAnswer());
        binding.btnSpeak.setOnClickListener(v -> speakCurrent());
        binding.btnSourcePhoto.setOnClickListener(v -> viewModel.togglePhoto());
        binding.btnDone.setOnClickListener(v -> finish());

        binding.gradeRow.btnForgot.setOnClickListener(v -> viewModel.grade(Grade.FORGOT));
        binding.gradeRow.btnHard.setOnClickListener(v -> viewModel.grade(Grade.HARD));
        binding.gradeRow.btnGood.setOnClickListener(v -> viewModel.grade(Grade.GOOD));
        binding.gradeRow.btnEasy.setOnClickListener(v -> viewModel.grade(Grade.EASY));

        viewModel.getStage().observe(this, this::renderStage);
        viewModel.getCurrentCard().observe(this, this::renderCard);
        viewModel.isFlipped().observe(this, this::renderFace);
        viewModel.getProgress().observe(this, this::renderProgress);
        viewModel.getExamples().observe(this, this::renderExamples);
        viewModel.getSourcePhoto().observe(this, this::renderSourcePhoto);
        viewModel.isPhotoExpanded().observe(this, this::renderPhotoExpanded);

        long[] ids = getIntent().getLongArrayExtra(EXTRA_WORD_IDS);
        if (ids == null || ids.length == 0) {
            finish();
            return;
        }
        viewModel.startIfNeeded(ids);
    }

    private void renderStage(ExtraPracticeViewModel.Stage stage) {
        boolean finished = stage == ExtraPracticeViewModel.Stage.FINISHED;
        binding.practising.setVisibility(finished ? View.GONE : View.VISIBLE);
        binding.finished.setVisibility(finished ? View.VISIBLE : View.GONE);

        if (finished) {
            binding.finishedSummary.setText(getString(
                    R.string.practice_done_summary, viewModel.getPractisedCount()));
        }
    }

    private void renderCard(@Nullable WordEntry card) {
        if (card == null) {
            return;
        }
        binding.headwordLarge.setText(card.headword);

        if (TextUtils.isEmpty(card.phonetic)) {
            binding.phonetic.setVisibility(View.GONE);
        } else {
            binding.phonetic.setVisibility(View.VISIBLE);
            binding.phonetic.setText("/" + card.phonetic + "/");
        }

        binding.translation.setText(TextUtils.isEmpty(card.translation)
                ? getString(R.string.no_translation) : card.translation);

        boolean hasPos = !TextUtils.isEmpty(card.pos);
        binding.posTag.setText(hasPos ? card.pos : "");
        binding.posTag.setTag(hasPos ? null : "empty");
    }

    private void renderFace(Boolean flippedValue) {
        boolean flipped = Boolean.TRUE.equals(flippedValue);

        // 翻到背面自動發音（設定頁，預設關閉）。只在「由正面翻到背面」時響一次。
        //
        // firstFaceRender 這一關不能省：摺疊或旋轉會重建 Activity，
        // ViewModel 裡的 flipped 還是 true，renderFace 會以背面狀態再跑一次。
        // 只看 wasFlipped 的話，每展開一次內螢幕就會突然唸一次單字。
        if (flipped && !wasFlipped && !firstFaceRender && settings.autoSpeakOnFlip()) {
            speakCurrent();
        }
        wasFlipped = flipped;
        firstFaceRender = false;

        binding.backDivider.setVisibility(flipped ? View.VISIBLE : View.GONE);
        binding.translation.setVisibility(flipped ? View.VISIBLE : View.GONE);
        binding.posTag.setVisibility(
                flipped && binding.posTag.getTag() == null ? View.VISIBLE : View.GONE);
        applyFaceToExamples();
        applyFaceToPhoto();

        binding.gradeRow.getRoot().setVisibility(flipped ? View.VISIBLE : View.GONE);
        binding.btnShowAnswer.setVisibility(flipped ? View.GONE : View.VISIBLE);

        binding.headwordLarge.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(flipped
                        ? R.dimen.text_headword : R.dimen.text_card_headword));
    }


    /**
     * 例句中英對照（SPEC 第 5 節「複習背面」）。
     *
     * <p>用 {@code addView} 手動堆，不用 RecyclerView —— 一張卡最多兩句，
     * 為兩個項目養一個 Adapter 只是多一層要維護的東西。
     */
    private void renderExamples(@Nullable CardExamples examples) {
        if (binding == null) {
            return;
        }
        binding.examples.removeAllViews();
        boolean has = examples != null && !examples.isEmpty();
        // visibility 由 renderFace 決定（例句只出現在背面），這裡只填內容
        binding.examples.setTag(has ? null : "empty");
        binding.exampleSource.setTag(has && examples.fromDictionary ? null : "empty");

        if (!has) {
            applyFaceToExamples();
            return;
        }
        for (com.example.eanki.data.dict.DictExample e : examples.examples) {
            ViewExampleBinding row = ViewExampleBinding.inflate(
                    getLayoutInflater(), binding.examples, false);
            row.exampleEn.setText(e.textEn);
            row.exampleEn.setVisibility(e.textEn.isEmpty() ? View.GONE : View.VISIBLE);
            row.exampleZh.setText(e.textZh);
            row.exampleZh.setVisibility(e.textZh.isEmpty() ? View.GONE : View.VISIBLE);
            binding.examples.addView(row.getRoot());
        }
        binding.exampleSource.setText(R.string.review_example_source_tatoeba);
        applyFaceToExamples();
    }

    /** 例句只在背面出現，而且沒有例句時整塊不佔位。 */
    private void applyFaceToExamples() {
        boolean flipped = Boolean.TRUE.equals(viewModel.isFlipped().getValue());
        binding.examples.setVisibility(
                flipped && binding.examples.getTag() == null ? View.VISIBLE : View.GONE);
        binding.exampleSource.setVisibility(
                flipped && binding.exampleSource.getTag() == null ? View.VISIBLE : View.GONE);
    }


    /**
     * 來源照片 chip（SPEC 第 5 節「複習背面」）。
     *
     * <p>只在這個字真的有掃描來源、而且檔案還在的時候出現 ——
     * 顯示一個點了沒反應的 chip 比不顯示糟。
     */
    private void renderSourcePhoto(@Nullable java.io.File file) {
        if (binding == null) {
            return;
        }
        binding.btnSourcePhoto.setTag(file == null ? "empty" : null);
        binding.sourcePhoto.setImageDrawable(null);
        applyFaceToPhoto();
    }

    private void renderPhotoExpanded(Boolean expanded) {
        if (binding == null) {
            return;
        }
        boolean open = Boolean.TRUE.equals(expanded);
        applyFaceToPhoto();
        if (!open) {
            binding.sourcePhoto.setImageDrawable(null);
            return;
        }
        java.io.File file = viewModel.getSourcePhoto().getValue();
        if (file == null) {
            return;
        }
        // 只有真的攤開才解碼。收著的時候不該為了一張看不到的圖付解碼成本。
        int maxWidth = binding.sourcePhoto.getWidth() > 0
                ? binding.sourcePhoto.getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        WordRepository.get(this).io().execute(() -> {
            android.graphics.Bitmap bitmap =
                    com.example.eanki.data.image.ScanImageStore.decodeScaled(file, maxWidth);
            runOnUiThread(() -> {
                if (binding == null) {
                    return;
                }
                if (bitmap == null) {
                    Snackbar.make(binding.root, R.string.review_source_photo_missing,
                            Snackbar.LENGTH_SHORT).show();
                    return;
                }
                binding.sourcePhoto.setImageBitmap(bitmap);
            });
        });
    }

    /** chip 只在背面出現；照片再多一層「有沒有被點開」。 */
    private void applyFaceToPhoto() {
        boolean flipped = Boolean.TRUE.equals(viewModel.isFlipped().getValue());
        boolean hasPhoto = binding.btnSourcePhoto.getTag() == null;
        binding.btnSourcePhoto.setVisibility(flipped && hasPhoto ? View.VISIBLE : View.GONE);
        boolean open = Boolean.TRUE.equals(viewModel.isPhotoExpanded().getValue());
        binding.sourcePhoto.setVisibility(
                flipped && hasPhoto && open ? View.VISIBLE : View.GONE);
    }

    private void renderProgress(int[] done) {
        int current = done[0];
        int total = done[1];
        binding.progressText.setText(
                getString(R.string.review_progress, Math.min(current + 1, total), total));
        binding.progressBar.setProgress(total == 0 ? 0 : current * 100 / total);
    }

    private void speakCurrent() {
        WordEntry card = viewModel.peekCurrent();
        if (card != null) {
            TtsManager.get().speak(card.headword);
        }
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
