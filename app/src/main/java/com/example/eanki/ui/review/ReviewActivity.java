package com.example.eanki.ui.review;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import com.example.eanki.R;
import com.example.eanki.data.CardExamples;
import com.example.eanki.data.WordRepository;
import com.example.eanki.data.settings.AppSettings;
import com.example.eanki.data.user.Direction;
import com.example.eanki.data.user.WordEntry;
import com.example.eanki.databinding.ActivityReviewBinding;
import com.example.eanki.databinding.ViewExampleBinding;
import com.example.eanki.review.Grade;
import com.example.eanki.review.Sm2Scheduler;
import com.example.eanki.tts.TtsManager;
import com.google.android.material.snackbar.Snackbar;

import java.util.Locale;
import java.util.Map;

/**
 * 翻卡複習。SPEC 第 5 節「複習正面」「複習背面」。
 *
 * <p><b>這個 Activity 不持有任何進度狀態</b> —— 第幾張卡、是否翻面、佇列內容
 * 全在 {@link ReviewViewModel}。SPEC 第 7 節第 2 點：摺疊會重建 Activity，
 * 狀態放成員變數就會在展開內螢幕的瞬間掉光。
 */
public class ReviewActivity extends AppCompatActivity {

    private ActivityReviewBinding binding;
    private ReviewViewModel viewModel;
    private AppSettings settings;

    /** 上一次畫面是不是背面，用來判斷「剛剛翻過去」。 */
    private boolean wasFlipped;

    /** 這個 Activity 建立後的第一次 renderFace。重建時不該被當成「剛翻過去」。 */
    private boolean firstFaceRender = true;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityReviewBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        viewModel = new ViewModelProvider(this).get(ReviewViewModel.class);
        settings = AppSettings.get(this);

        binding.card.setOnClickListener(v -> viewModel.flip());
        binding.btnShowAnswer.setOnClickListener(v -> viewModel.showAnswer());
        binding.btnBackToList.setOnClickListener(v -> finish());
        binding.btnSpeak.setOnClickListener(v -> speakCurrent());
        binding.btnSourcePhoto.setOnClickListener(v -> viewModel.togglePhoto());
        binding.btnDirection.setOnClickListener(v -> {
            viewModel.toggleDirection();
            Snackbar.make(binding.root, R.string.review_dir_locked,
                    Snackbar.LENGTH_SHORT).show();
        });

        binding.gradeRow.btnForgot.setOnClickListener(v -> viewModel.grade(Grade.FORGOT));
        binding.gradeRow.btnHard.setOnClickListener(v -> viewModel.grade(Grade.HARD));
        binding.gradeRow.btnGood.setOnClickListener(v -> viewModel.grade(Grade.GOOD));
        binding.gradeRow.btnEasy.setOnClickListener(v -> viewModel.grade(Grade.EASY));

        observe();
        viewModel.startIfNeeded();
    }

    private void observe() {
        viewModel.getStage().observe(this, this::renderStage);
        viewModel.getCurrentCard().observe(this, this::renderCard);
        viewModel.isFlipped().observe(this, this::renderFace);
        viewModel.getProgress().observe(this, this::renderProgress);
        viewModel.getNextIntervals().observe(this, this::renderIntervals);
        viewModel.getExamples().observe(this, this::renderExamples);
        viewModel.getSourcePhoto().observe(this, this::renderSourcePhoto);
        viewModel.isPhotoExpanded().observe(this, this::renderPhotoExpanded);
        viewModel.getDirection().observe(this, d ->
                binding.directionLabel.setText(d == Direction.EN_TO_ZH
                        ? R.string.review_dir_en_zh : R.string.review_dir_zh_en));
    }

    private void renderStage(ReviewViewModel.Stage stage) {
        boolean finished = stage == ReviewViewModel.Stage.FINISHED;
        binding.reviewing.setVisibility(finished ? View.GONE : View.VISIBLE);
        binding.finished.setVisibility(finished ? View.VISIBLE : View.GONE);

        if (finished) {
            int reviewed = viewModel.getReviewedCount();
            binding.finishedSummary.setText(reviewed == 0
                    ? getString(R.string.review_done_nothing)
                    : getString(R.string.review_done_summary, reviewed));

            int deferred = viewModel.getDeferredCount();
            binding.deferredNote.setVisibility(deferred > 0 ? View.VISIBLE : View.GONE);
            if (deferred > 0) {
                binding.deferredNote.setText(getString(R.string.review_deferred, deferred));
            }
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

        String meta = joinMeta(card.pos, null);
        binding.posTag.setText(meta);
        binding.posTag.setTag(meta.isEmpty() ? "empty" : null);
    }

    /**
     * 正面只給單字、音標、發音鍵；背面才出釋義與評分鍵。
     *
     * <p><b>效能備註：</b>實機量測顯示翻卡與換卡的轉場幾乎每次會掉一幀
     * （69 幀中 13 幀 janky，50th 5ms 但 90th 13ms、99th 17ms，
     * 120Hz 的預算是 8.3ms）。推測瓶頸是大字級襯線字體
     * （{@code text_card_headword} 40sp，展開螢幕 64sp）在這裡改字級後
     * 觸發的文字排版與卡片重新量測。
     *
     * <p><b>這跟資料量無關</b>——單字本有 14 筆或 1,464 筆都一樣，
     * 因為複習畫面一次只渲染一張卡。之後若覺得翻卡手感不夠俐落，
     * 從這個方法與 {@code activity_review.xml} 的卡片層級查起。
     */
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
        applyFaceToExamples();
        applyFaceToPhoto();
        binding.posTag.setVisibility(
                flipped && binding.posTag.getTag() == null ? View.VISIBLE : View.GONE);

        binding.gradeRow.getRoot().setVisibility(flipped ? View.VISIBLE : View.GONE);
        binding.btnShowAnswer.setVisibility(flipped ? View.GONE : View.VISIBLE);

        // 翻面後單字縮小，讓出空間給釋義（SPEC 第 5 節「複習背面：單字（小）」）
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

    private void renderIntervals(@Nullable Map<Grade, Integer> intervals) {
        if (intervals == null) {
            return;
        }
        binding.gradeRow.intervalForgot.setText(formatInterval(intervals.get(Grade.FORGOT)));
        binding.gradeRow.intervalHard.setText(formatInterval(intervals.get(Grade.HARD)));
        binding.gradeRow.intervalGood.setText(formatInterval(intervals.get(Grade.GOOD)));
        binding.gradeRow.intervalEasy.setText(formatInterval(intervals.get(Grade.EASY)));
    }

    /**
     * SPEC 第 6.3 節：按鈕下方顯示預估間隔，
     * 讓評分從「主觀感覺」變成「我希望多久後再看到它」。
     */
    private String formatInterval(@Nullable Integer minutes) {
        if (minutes == null) {
            return "";
        }
        if (minutes < 60) {
            return getString(R.string.interval_minutes, minutes);
        }
        if (minutes < Sm2Scheduler.MINUTES_PER_DAY) {
            return getString(R.string.interval_hours, minutes / 60);
        }
        int days = minutes / Sm2Scheduler.MINUTES_PER_DAY;
        if (days < 60) {
            return getString(R.string.interval_days, days);
        }
        return String.format(Locale.getDefault(),
                getString(R.string.interval_months), days / 30.0f);
    }

    private void speakCurrent() {
        WordEntry card = viewModel.peekCurrent();
        if (card != null) {
            TtsManager.get().speak(card.headword);
        }
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

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
