package com.example.eanki.ui.scan;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.mlkit.vision.MlKitAnalyzer;
import androidx.camera.view.CameraController;
import androidx.camera.view.LifecycleCameraController;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;

import com.example.eanki.R;
import com.example.eanki.data.WordRepository;
import com.example.eanki.data.dict.DictEntry;
import com.example.eanki.data.dict.WordNormalizer;
import com.example.eanki.data.image.ScanImageStore;
import com.example.eanki.data.user.Source;
import com.example.eanki.databinding.ActivityScanBinding;
import com.google.android.material.chip.Chip;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.io.InputStream;
import java.util.concurrent.Executors;

/**
 * 掃描取字。SPEC 第 5 節「掃描」。
 *
 * <h2>為什麼要先凍結再點選</h2>
 *
 * SPEC 的流程是「即時預覽浮現框 → 快門後凍結 → 點字選取」，這不只是手感問題，
 * 是<b>正確性問題</b>：即時預覽下框每秒重建約 8 次、而且比畫面慢約 190ms，
 * 手一晃或重新對焦，點下去的位置可能已經跟眼睛看到的字對不上了。
 *
 * <p>所以這裡分成兩個模式：
 * <ul>
 *   <li>{@link Mode#LIVE}：框只是<b>引導</b>，告訴使用者拿多近才對得到焦，不能點</li>
 *   <li>{@link Mode#FROZEN}：畫面是一張靜態圖，框是<b>從那張圖本身</b>算出來的，
 *       圖跟框來自同一幀，座標必然對得上</li>
 * </ul>
 *
 * <p>凍結用的是全解析度的拍照，不是即時分析那張 640×480 —— 辨識準確度更高，
 * 而且只跑一次，慢一點沒關係。
 */
public class ScanActivity extends AppCompatActivity {

    private static final String TAG = "ScanActivity";

    private enum Mode { LIVE, CAPTURING, FROZEN }

    /**
     * 即時分析的解析度。實測 1920×1440（MlKitAnalyzer 的預設）→ 640×480
     * 讓端到端延遲從 230–360ms 降到 177–214ms，準確度沒有可見損失。
     * 這條串流只負責「框浮現與否」的引導，不需要高解析度。
     */
    private static final Size ANALYSIS_RESOLUTION = new Size(640, 480);

    /**
     * 快門拍照的解析度。這張才是真正要辨識的圖，用得起較高的解析度 ——
     * 它只跑一次，不像即時串流每秒要跑八次。
     */
    private static final Size CAPTURE_RESOLUTION = new Size(1920, 1440);

    /**
     * 相簿照片解碼後的最長邊上限。
     *
     * <p>手機相簿裡動輒是 4000×3000 的照片，整張載進來既慢又有 OOM 風險，
     * 而辨識用不到那麼高的解析度。降到與快門同一個量級，
     * 後面的管線（辨識、裁圖、存檔）就完全共用。
     */
    private static final int MAX_GALLERY_EDGE = 1920;

    /** 從別的畫面直接進來就開相簿，不必先看到相機預覽。 */
    public static final String EXTRA_PICK_PHOTO = "pick_photo";

    /**
     * 即時預覽時，太小的框多半是雜訊或遠處的字，畫出來只會讓畫面糊掉。
     * <b>這個門檻用畫面座標判斷，只作用於即時預覽。</b>
     */
    private static final float MIN_LIVE_BOX_HEIGHT_DP = 10f;

    /**
     * 凍結後改用<b>原圖像素</b>判斷雜訊，不能用畫面座標。
     *
     * <p>整頁縮進螢幕後每個字只剩十幾個畫面像素，用畫面座標的門檻會把
     * 整張圖的字全部濾光 —— 實測相簿選一張整頁的圖時就是這樣變成 0 個字。
     * 而且凍結後的框是要給人點的，濾掉就等於不能選；
     * 太小不好點的問題由 {@link TextOverlayView} 的 48dp 命中範圍處理。
     */
    private static final int MIN_FROZEN_BOX_PX = 8;

    private ActivityScanBinding binding;
    private ExecutorService analysisExecutor;
    private TextRecognizer recognizer;
    private LifecycleCameraController controller;
    private DictionaryCache dictionary;
    private WordRepository words;

    private final ScanStats stats = new ScanStats();
    private Mode mode = Mode.LIVE;
    private float minBoxHeightPx;
    private long lastLogAt;
    private boolean resolutionLogged;

    @Nullable
    private Bitmap frozenBitmap;
    private List<TextOverlayView.Box> frozenBoxes = Collections.emptyList();

    /**
     * 已選的字，存<b>正規化後的字</b>。
     * 凍結後框不再移動，但用字當識別仍然比較穩 —— 重拍之後選取會清空，
     * 而同一張圖上同一個字出現兩次時兩個框會一起亮起來，這是對的行為。
     */
    private final Set<String> selected = new LinkedHashSet<>();

    /**
     * 系統相片選擇器。<b>不需要任何儲存空間權限</b> ——
     * 只有使用者自己挑的那一張會被傳進來，符合 SPEC 第 2 節的克制原則。
     */
    private final ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest>
            photoPicker = registerForActivityResult(
                    new ActivityResultContracts.PickVisualMedia(), uri -> {
                        if (uri != null) {
                            loadFromGallery(uri);
                        } else if (mode == Mode.CAPTURING) {
                            applyMode(Mode.LIVE);   // 使用者按了取消
                        }
                    });

    private final ActivityResultLauncher<String> cameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    showPermissionState(true);
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityScanBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        minBoxHeightPx = MIN_LIVE_BOX_HEIGHT_DP * getResources().getDisplayMetrics().density;

        analysisExecutor = Executors.newSingleThreadExecutor();
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        words = WordRepository.get(this);
        dictionary = new DictionaryCache(words::lookUp, words.io());

        binding.btnClose.setOnClickListener(v -> finish());
        binding.btnGrant.setOnClickListener(v ->
                cameraPermission.launch(Manifest.permission.CAMERA));
        binding.btnShutter.setOnClickListener(v -> capture());
        binding.btnGallery.setOnClickListener(v -> pickPhoto());
        binding.btnGalleryNoPermission.setOnClickListener(v -> pickPhoto());
        binding.btnRetake.setOnClickListener(v -> backToLive());
        binding.btnAdd.setOnClickListener(v -> addSelected());

        binding.overlay.setOnTouchListener((v, event) -> {
            // LIVE 模式不吃觸控，讓事件穿透到 PreviewView 觸發點擊對焦。
            // 之前一律回傳 true，等於把 CameraX 內建的 tap-to-focus 整個擋掉了，
            // 使用者想對焦哪裡都沒反應。
            if (mode != Mode.FROZEN) {
                return false;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                v.performClick();
                onOverlayTap(event.getX(), event.getY());
            }
            return true;
        });

        applyMode(Mode.LIVE);

        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_PICK_PHOTO, false)
                && savedInstanceState == null) {
            // 從「從相簿選圖」進來的，直接開選擇器
            pickPhoto();
        }

        if (hasCameraPermission()) {
            startCamera();
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    /** 給其他畫面用的入口：直接開相簿，不先停在相機預覽。 */
    public static Intent pickPhotoIntent(android.content.Context context) {
        Intent intent = new Intent(context, ScanActivity.class);
        intent.putExtra(EXTRA_PICK_PHOTO, true);
        return intent;
    }

    private void pickPhoto() {
        applyMode(Mode.CAPTURING);
        binding.stats.setText(R.string.scan_loading_photo);
        photoPicker.launch(new androidx.activity.result.PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    /**
     * 相簿照片走的是<b>跟快門完全一樣的下游管線</b>：
     * 解碼成 Bitmap → {@link #recognizeStill} → 凍結畫面 → 點字選取 →
     * 查字典 → 裁圖存檔 → 批次寫入。
     *
     * <p>「相片」和「剛拍的照片」對這條管線而言是同一件事：
     * 一張已經凍結、不會再變動的影像。所以這裡只負責把 URI 變成 Bitmap，
     * 沒有任何一行選取或存檔邏輯是為相簿另外寫的。
     */
    private void loadFromGallery(Uri uri) {
        applyMode(Mode.CAPTURING);
        binding.stats.setText(R.string.scan_loading_photo);

        analysisExecutor.execute(() -> {
            try {
                Bitmap bitmap = decodeScaled(uri);
                runOnUiThread(() -> {
                    if (binding != null) {
                        recognizeStill(bitmap);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "讀取相片失敗", e);
                runOnUiThread(() -> {
                    if (binding == null) {
                        return;
                    }
                    Toast.makeText(this,
                            getString(R.string.scan_photo_failed, String.valueOf(e.getMessage())),
                            Toast.LENGTH_LONG).show();
                    applyMode(Mode.LIVE);
                });
            }
        });
    }

    /** 先讀尺寸決定取樣率再解碼，避免把 4000×3000 的照片整張載進記憶體。 */
    private Bitmap decodeScaled(Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }

        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (longest / (sample * 2) >= MAX_GALLERY_EDGE) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap decoded;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            decoded = BitmapFactory.decodeStream(in, null, options);
        }
        if (decoded == null) {
            throw new IllegalStateException("解碼失敗");
        }

        // 相機拍的照片多半不旋轉像素，只在 EXIF 標方向。
        // 不套用的話送進辨識的是躺著的字，ML Kit 幾乎認不到。
        int degrees;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            degrees = in == null ? 0 : exifDegrees(new ExifInterface(in));
        }
        return rotate(decoded, degrees);
    }

    private static int exifDegrees(ExifInterface exif) {
        switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL)) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                return 90;
            case ExifInterface.ORIENTATION_ROTATE_180:
                return 180;
            case ExifInterface.ORIENTATION_ROTATE_270:
                return 270;
            default:
                return 0;
        }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void showPermissionState(boolean show) {
        binding.permissionState.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------
    // 相機
    // ---------------------------------------------------------------

    private void startCamera() {
        showPermissionState(false);
        try {
            controller = new LifecycleCameraController(this);
            controller.setEnabledUseCases(
                    CameraController.IMAGE_ANALYSIS | CameraController.IMAGE_CAPTURE);
            controller.setImageAnalysisBackpressureStrategy(
                    ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST);
            controller.setImageAnalysisResolutionSelector(resolution(ANALYSIS_RESOLUTION));
            controller.setImageCaptureResolutionSelector(resolution(CAPTURE_RESOLUTION));
            // 拍文字一定要等對焦收斂。MINIMIZE_LATENCY 會在 3A 還沒穩定時就按下去，
            // 拍出來是糊的、辨識直接歸零 —— 實測就踩到這個。
            // 快門是一次性動作，多等兩三百毫秒換一張清楚的圖非常划算。
            controller.setImageCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY);

            MlKitAnalyzer mlKit = new MlKitAnalyzer(
                    Collections.<com.google.mlkit.vision.interfaces.Detector<?>>singletonList(
                            recognizer),
                    CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
                    ContextCompat.getMainExecutor(this),
                    this::onLiveResult);
            controller.setImageAnalysisAnalyzer(analysisExecutor, logging(mlKit));

            controller.bindToLifecycle(this);
            // COMPATIBLE 走 TextureView，adb screencap 才抓得到畫面（SurfaceView 是硬體疊層）
            binding.preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
            binding.preview.setController(controller);
        } catch (Exception e) {
            Log.e(TAG, "相機啟動失敗", e);
            binding.stats.setText(getString(R.string.scan_camera_failed, String.valueOf(e)));
        }
    }

    private static ResolutionSelector resolution(Size size) {
        return new ResolutionSelector.Builder()
                .setResolutionStrategy(new ResolutionStrategy(
                        size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build();
    }

    /**
     * 包一層只為了印出實際的分析解析度。
     * <b>其餘三個方法一定要原封不動轉發</b>：{@code getTargetCoordinateSystem()} 與
     * {@code updateTransform()} 正是 CameraX 換算座標的機制，漏掉框就會整個錯位。
     */
    private ImageAnalysis.Analyzer logging(ImageAnalysis.Analyzer delegate) {
        return new ImageAnalysis.Analyzer() {
            @Override
            public void analyze(@NonNull ImageProxy image) {
                if (!resolutionLogged) {
                    resolutionLogged = true;
                    Log.i(TAG, "即時分析解析度 " + image.getWidth() + " x " + image.getHeight());
                }
                delegate.analyze(image);
            }

            @Override
            public Size getDefaultTargetResolution() {
                return delegate.getDefaultTargetResolution();
            }

            @Override
            public int getTargetCoordinateSystem() {
                return delegate.getTargetCoordinateSystem();
            }

            @Override
            public void updateTransform(@Nullable Matrix matrix) {
                delegate.updateTransform(matrix);
            }
        };
    }

    // ---------------------------------------------------------------
    // LIVE：框只是引導
    // ---------------------------------------------------------------

    private void onLiveResult(MlKitAnalyzer.Result result) {
        if (binding == null || result == null || mode != Mode.LIVE) {
            return;
        }
        long now = System.currentTimeMillis();
        long latency = (SystemClock.elapsedRealtimeNanos() - result.getTimestamp()) / 1_000_000L;
        if (latency < 0 || latency > 10_000L) {
            latency = 0;
        }

        Text text = result.getValue(recognizer);
        List<TextOverlayView.Box> boxes = toBoxes(text, null);
        binding.overlay.setBoxes(boxes);

        stats.record(latency, now);
        binding.stats.setText(stats.format(boxes.size()));

        if (now - lastLogAt >= 1000L) {
            lastLogAt = now;
            Log.i(TAG, stats.format(boxes.size()));
        }
    }

    // ---------------------------------------------------------------
    // 快門
    // ---------------------------------------------------------------

    private void capture() {
        if (controller == null || mode != Mode.LIVE) {
            return;
        }
        applyMode(Mode.CAPTURING);

        controller.takePicture(ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(@NonNull ImageProxy image) {
                        int rotation = image.getImageInfo().getRotationDegrees();
                        Bitmap raw = image.toBitmap();
                        image.close();
                        recognizeStill(rotate(raw, rotation));
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException e) {
                        Log.e(TAG, "拍照失敗", e);
                        Toast.makeText(ScanActivity.this,
                                getString(R.string.scan_capture_failed, e.getMessage()),
                                Toast.LENGTH_LONG).show();
                        applyMode(Mode.LIVE);
                    }
                });
    }

    private static Bitmap rotate(Bitmap source, int degrees) {
        if (degrees == 0) {
            return source;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(
                source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        if (rotated != source) {
            source.recycle();
        }
        return rotated;
    }

    /**
     * 對凍結的靜態圖重跑一次辨識。
     *
     * <p><b>不沿用即時串流最後一次的結果</b>：那是另一張影像、另一個時間點，
     * 沿用就會回到「框跟畫面對不上」的老問題。這裡的框百分之百來自眼前這張圖。
     */
    private void recognizeStill(Bitmap bitmap) {
        long startedAt = SystemClock.elapsedRealtime();
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(text -> {
                    if (binding == null) {
                        return;
                    }
                    frozenBitmap = bitmap;
                    binding.frozen.setImageBitmap(bitmap);
                    selected.clear();

                    // 凍結的框只算一次，所以字典必須先查完再畫。
                    // 用非同步的 get 會讓沒被快取過的字永遠停在 PENDING、點不下去。
                    analysisExecutor.execute(() -> {
                        dictionary.warm(normalizedWordsOf(text));
                        runOnUiThread(() -> {
                            if (binding != null) {
                                renderFrozen(text, bitmap, startedAt);
                            }
                        });
                    });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "靜態圖辨識失敗", e);
                    Toast.makeText(this,
                            getString(R.string.scan_capture_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                    applyMode(Mode.LIVE);
                });
    }

    /** 這張圖上所有正規化後的字，給字典預先查詢用。 */
    private java.util.Set<String> normalizedWordsOf(@Nullable Text text) {
        java.util.Set<String> words = new LinkedHashSet<>();
        if (text == null) {
            return words;
        }
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                for (Text.Element element : line.getElements()) {
                    if (!looksLikeWord(element.getText())) {
                        continue;
                    }
                    String word = WordNormalizer.normalize(element.getText());
                    if (!word.isEmpty()) {
                        words.add(word);
                    }
                }
            }
        }
        return words;
    }

    /**
     * 把辨識結果畫到凍結的畫面上。字典已經在背景查完，
     * 所以這裡產生的框一開始就有正確的顏色與可點狀態。
     */
    private void renderFrozen(Text text, Bitmap bitmap, long startedAt) {
        binding.frozen.post(() -> {
            if (binding == null) {
                return;
            }
            // 自己算 fitCenter 的轉換，不讀 ImageView.getImageMatrix()：
            // 那個要等 View layout 過才有值，而這裡 ImageView 還是 GONE。
            // overlay 跟 frozen 被約束成同樣大小，而且一直是 VISIBLE，
            // 所以它的尺寸隨時可用。
            frozenBoxes = toBoxes(text, FitCenter.matrix(
                    bitmap.getWidth(), bitmap.getHeight(),
                    binding.overlay.getWidth(), binding.overlay.getHeight()));
            binding.overlay.setBoxes(frozenBoxes);
            applyMode(Mode.FROZEN);

            int found = 0;
            for (TextOverlayView.Box box : frozenBoxes) {
                if (box.state == LookupState.FOUND) {
                    found++;
                }
            }
            Log.i(TAG, String.format(java.util.Locale.US,
                    "凍結辨識：%d x %d，耗時 %d ms，%d 個字，字典命中 %d",
                    bitmap.getWidth(), bitmap.getHeight(),
                    SystemClock.elapsedRealtime() - startedAt,
                    frozenBoxes.size(), found));
            binding.stats.setText(getString(
                    R.string.scan_frozen_summary, frozenBoxes.size(), found));

            if (frozenBoxes.isEmpty()) {
                Toast.makeText(ScanActivity.this,
                        R.string.scan_nothing_recognized, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void backToLive() {
        selected.clear();
        frozenBoxes = Collections.emptyList();
        binding.overlay.setBoxes(frozenBoxes);
        binding.frozen.setImageDrawable(null);
        // 不 recycle：批次寫入是在背景執行緒讀這張 bitmap 裁圖，
        // 這裡回收會讓它讀到已釋放的記憶體。交給 GC 與下一次 capture 覆蓋。
        frozenBitmap = null;
        stats.reset();
        refreshChips();
        applyMode(Mode.LIVE);
    }

    // ---------------------------------------------------------------
    // 選字
    // ---------------------------------------------------------------

    /**
     * 點一下切換選取。<b>只有凍結後才能點</b>。
     *
     * <p>SPEC 第 6.1 節：查不到的字要讓使用者確認或修正，
     * 所以點到磚紅框時給提示，而不是默默沒反應。
     */
    private void onOverlayTap(float x, float y) {
        if (mode != Mode.FROZEN) {
            return;
        }
        TextOverlayView.Box box = binding.overlay.findBoxAt(x, y);
        if (box == null) {
            return;
        }
        if (box.state == LookupState.NOT_FOUND) {
            Toast.makeText(this,
                    getString(R.string.scan_not_found_toast, box.text),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (box.state == LookupState.PENDING) {
            return;
        }

        if (selected.remove(box.word)) {
            binding.wordCard.setVisibility(View.GONE);
        } else {
            selected.add(box.word);
            showMeaning(box.word);
        }
        // 同一個字可能在圖上出現多次，全部一起更新
        for (TextOverlayView.Box other : frozenBoxes) {
            other.selected = selected.contains(other.word);
        }
        binding.overlay.invalidate();
        refreshChips();
        updateAddButton();
    }

    private void showMeaning(String word) {
        DictEntry entry = dictionary.get(word).entry;
        if (entry == null) {
            binding.wordCard.setVisibility(View.GONE);
            return;
        }
        binding.wordCard.setVisibility(View.VISIBLE);
        // 顯示掃到的形式而不是原形；存進單字本的也是掃到的形式
        binding.cardHeadword.setText(word);
        if (entry.viaInflection()) {
            binding.cardInflection.setVisibility(View.VISIBLE);
            binding.cardInflection.setText(getString(R.string.inflection_hint, entry.word));
        } else {
            binding.cardInflection.setVisibility(View.GONE);
        }

        if (TextUtils.isEmpty(entry.phonetic)) {
            binding.cardPhonetic.setVisibility(View.GONE);
        } else {
            binding.cardPhonetic.setVisibility(View.VISIBLE);
            binding.cardPhonetic.setText("/" + entry.phonetic + "/");
        }
        binding.cardTranslation.setText(TextUtils.isEmpty(entry.translation)
                ? getString(R.string.no_translation) : entry.translation);
    }

    /**
     * 重建已選 chip 列。SPEC 第 5 節「已選 chip 列」。
     *
     * <p>選的字一多，框可能捲出畫面或被擋住，chip 列是唯一能一眼看完
     * 「我選了什麼」的地方，也是比戳小方框更容易的取消入口。
     */
    private void refreshChips() {
        binding.chips.removeAllViews();
        binding.chipScroll.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);

        for (String word : selected) {
            Chip chip = new Chip(this, null, com.google.android.material.R.attr.chipStyle);
            chip.setChipBackgroundColorResource(R.color.mint_card);
            chip.setChipStrokeColorResource(R.color.mint_card_border);
            chip.setChipStrokeWidth(getResources().getDisplayMetrics().density);
            chip.setTextColor(getColor(R.color.text_primary));
            chip.setText(word);
            chip.setCloseIconVisible(true);
            chip.setCloseIconTint(android.content.res.ColorStateList.valueOf(
                    getColor(R.color.mint)));
            chip.setCheckable(false);
            // chip 與方框是同一份選取狀態的兩個入口，點哪邊都要一起更新
            chip.setOnCloseIconClickListener(v -> deselect(word));
            chip.setOnClickListener(v -> deselect(word));
            binding.chips.addView(chip);
        }
        // 新加的 chip 在最右邊，捲過去讓使用者看到剛剛選了什麼
        binding.chipScroll.post(() ->
                binding.chipScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private void deselect(String word) {
        if (!selected.remove(word)) {
            return;
        }
        for (TextOverlayView.Box box : frozenBoxes) {
            if (box.word.equals(word)) {
                box.selected = false;
            }
        }
        binding.overlay.invalidate();
        binding.wordCard.setVisibility(View.GONE);
        refreshChips();
        updateAddButton();
    }

    private void updateAddButton() {
        boolean any = !selected.isEmpty();
        binding.btnAdd.setEnabled(any);
        binding.btnAdd.setText(any
                ? getString(R.string.scan_add_n, selected.size())
                : getString(R.string.scan_add_none));
    }

    /** SPEC 第 5 節：「加入 N 個單字」一次批次寫入 Room。 */
    private void addSelected() {
        if (selected.isEmpty()) {
            return;
        }
        // 先在主執行緒把「字 → 它在原圖裡的位置」收好，
        // 因為 frozenBoxes 與 frozenBitmap 隨時可能因為重拍被換掉
        List<String> order = new ArrayList<>(selected);
        Map<String, Rect> crops = new LinkedHashMap<>();
        for (TextOverlayView.Box box : frozenBoxes) {
            if (selected.contains(box.word) && box.sourceRect != null
                    && !crops.containsKey(box.word)) {
                crops.put(box.word, box.sourceRect);
            }
        }
        Bitmap still = frozenBitmap;
        binding.btnAdd.setEnabled(false);

        words.io().execute(() -> {
            ScanImageStore store = words.images();
            List<WordRepository.PendingWord> pending = new ArrayList<>(order.size());
            for (String word : order) {
                String imageName = null;
                Rect crop = crops.get(word);
                if (still != null && !still.isRecycled() && crop != null) {
                    // SPEC 第 3 節 image_uri：只裁單字周圍一小塊，不存整張
                    imageName = store.save(still, crop);
                }
                pending.add(new WordRepository.PendingWord(
                        word, dictionary.get(word).entry, imageName));
            }

            WordRepository.BatchResult result = words.saveAll(pending, Source.SCAN);
            runOnUiThread(() -> {
                if (binding == null) {
                    return;
                }
                Toast.makeText(this, summaryOf(result), Toast.LENGTH_LONG).show();
                Log.i(TAG, "批次寫入：新增 " + result.saved
                        + "，重複 " + result.duplicate + "，失敗 " + result.failed);
                backToLive();
            });
        });
    }

    private String summaryOf(WordRepository.BatchResult result) {
        if (result.saved == 0) {
            return getString(R.string.scan_added_none);
        }
        if (result.duplicate > 0) {
            return getString(R.string.scan_added_with_dup, result.saved, result.duplicate);
        }
        return getString(R.string.scan_added, result.saved);
    }

    // ---------------------------------------------------------------
    // 共用
    // ---------------------------------------------------------------

    /**
     * 把辨識結果攤成一個個單字框。
     *
     * @param toView 凍結模式要傳 ImageView 的 image matrix，把 bitmap 座標換算到
     *               view 座標；即時模式傳 null，因為 MlKitAnalyzer 已經換算好了
     */
    private List<TextOverlayView.Box> toBoxes(@Nullable Text text, @Nullable Matrix toView) {
        if (text == null) {
            return Collections.emptyList();
        }
        List<TextOverlayView.Box> boxes = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                for (Text.Element element : line.getElements()) {
                    Rect rect = element.getBoundingBox();
                    if (rect == null || !looksLikeWord(element.getText())) {
                        continue;
                    }
                    RectF mapped = new RectF(rect);
                    if (toView != null) {
                        toView.mapRect(mapped);
                    }
                    // 凍結模式才有原圖可裁；即時預覽沒有留下那一幀
                    Rect source = toView != null ? new Rect(rect) : null;
                    // 即時預覽看畫面尺寸（濾掉遠處雜訊）；
                    // 凍結後看原圖尺寸（畫面上再小也要能點）
                    if (toView == null) {
                        if (mapped.height() < minBoxHeightPx) {
                            continue;
                        }
                    } else if (rect.height() < MIN_FROZEN_BOX_PX) {
                        continue;
                    }
                    // Phase 0 實測：ML Kit 會把標點黏在單字上（apple,）也會改動
                    // 大小寫（Over），不正規化一定查不到字典
                    String word = WordNormalizer.normalize(element.getText());
                    if (word.isEmpty()) {
                        continue;
                    }
                    TextOverlayView.Box box = new TextOverlayView.Box(
                            mapped, source, element.getText(), word);
                    box.state = dictionary.get(word).state;
                    box.selected = selected.contains(word);
                    boxes.add(box);
                }
            }
        }
        return boxes;
    }

    private static boolean looksLikeWord(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        for (int i = 0; i < raw.length(); i++) {
            if (Character.isLetter(raw.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private void applyMode(Mode next) {
        mode = next;
        boolean live = next == Mode.LIVE;
        boolean frozen = next == Mode.FROZEN;
        boolean capturing = next == Mode.CAPTURING;

        binding.preview.setVisibility(live || capturing ? View.VISIBLE : View.GONE);
        binding.frozen.setVisibility(frozen ? View.VISIBLE : View.GONE);
        binding.busy.setVisibility(capturing ? View.VISIBLE : View.GONE);

        binding.btnShutter.setVisibility(live ? View.VISIBLE : View.GONE);
        binding.btnGallery.setVisibility(live ? View.VISIBLE : View.GONE);
        binding.galleryGap.setVisibility(live ? View.VISIBLE : View.GONE);
        binding.btnRetake.setVisibility(frozen ? View.VISIBLE : View.GONE);
        binding.actionGap.setVisibility(frozen ? View.VISIBLE : View.GONE);
        binding.btnAdd.setVisibility(frozen ? View.VISIBLE : View.GONE);
        binding.btnClose.setVisibility(capturing ? View.GONE : View.VISIBLE);

        binding.hint.setText(frozen ? R.string.scan_hint_frozen : R.string.scan_hint_live);
        if (!frozen) {
            binding.wordCard.setVisibility(View.GONE);
            binding.chipScroll.setVisibility(View.GONE);
        }
        if (capturing) {
            binding.stats.setText(R.string.scan_capturing);
        }
        updateAddButton();
    }

    @Override
    protected void onDestroy() {
        if (controller != null) {
            controller.clearImageAnalysisAnalyzer();
            controller.unbind();
            controller = null;
        }
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
        if (analysisExecutor != null) {
            analysisExecutor.shutdown();
            analysisExecutor = null;
        }
        frozenBitmap = null;
        binding = null;
        super.onDestroy();
    }
}
