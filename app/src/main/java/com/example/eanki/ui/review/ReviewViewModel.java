package com.example.eanki.ui.review;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.eanki.data.ReviewRepository;
import com.example.eanki.data.settings.AppSettings;
import com.example.eanki.data.user.Direction;
import com.example.eanki.data.user.ReviewState;
import com.example.eanki.data.user.ReviewStateMapper;
import com.example.eanki.data.CardExamples;
import com.example.eanki.data.WordRepository;
import com.example.eanki.data.user.WordEntry;
import com.example.eanki.review.Grade;
import com.example.eanki.review.Schedule;
import com.example.eanki.review.Sm2Scheduler;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 一次複習 session 的全部狀態。
 *
 * <p><b>SPEC 第 7 節第 2 點：摺疊會重建 Activity。</b>
 * 複習進度、目前第幾張卡、是否已翻面，全部放在這裡，
 * 不可放 Activity 的成員變數 —— 這是最容易漏、使用者最有感的 bug。
 * 螢幕旋轉、展開內螢幕、深色模式切換都會重建 Activity，但 ViewModel 會活下來。
 */
public class ReviewViewModel extends AndroidViewModel {

    /** 畫面目前該顯示什麼。 */
    public enum Stage {
        LOADING,
        /** 有卡可複習。 */
        REVIEWING,
        /** 今天的份量做完了。 */
        FINISHED
    }

    private final ReviewRepository repository;
    private final WordRepository words;
    private final AppSettings settings;

    private final MutableLiveData<Stage> stage = new MutableLiveData<>(Stage.LOADING);
    private final MutableLiveData<WordEntry> currentCard = new MutableLiveData<>();
    private final MutableLiveData<Boolean> flipped = new MutableLiveData<>(false);
    private final MutableLiveData<CardExamples> examples =
            new MutableLiveData<>(CardExamples.NONE);
    private final MutableLiveData<java.io.File> sourcePhoto = new MutableLiveData<>();
    private final MutableLiveData<Boolean> photoExpanded = new MutableLiveData<>(false);
    private final MutableLiveData<int[]> progress = new MutableLiveData<>(new int[]{0, 0});
    private final MutableLiveData<Map<Grade, Integer>> nextIntervals = new MutableLiveData<>();
    private final MutableLiveData<Direction> direction =
            new MutableLiveData<>(Direction.EN_TO_ZH);

    private List<WordEntry> queue = new ArrayList<>();
    private int index;
    private int deferredCount;
    private int reviewedCount;
    private boolean started;

    public ReviewViewModel(@NonNull Application application) {
        super(application);
        repository = ReviewRepository.get(application);
        words = WordRepository.get(application);
        settings = AppSettings.get(application);
    }

    public LiveData<Stage> getStage() {
        return stage;
    }

    public LiveData<WordEntry> getCurrentCard() {
        return currentCard;
    }

    public LiveData<Boolean> isFlipped() {
        return flipped;
    }

    /**
     * 目前這張卡的例句。
     *
     * <p>非同步查，不擋換卡 —— 例句只出現在背面，使用者按下「顯示答案」
     * 之前有的是時間。查詢本身是字典庫的索引查找，實測等級是微秒。
     */
    public LiveData<CardExamples> getExamples() {
        return examples;
    }


    /**
     * 目前這張卡的來源照片檔。沒有掃描來源就是 null。
     *
     * <p>跟例句一樣非同步查，不擋換卡。
     */
    public LiveData<java.io.File> getSourcePhoto() {
        return sourcePhoto;
    }

    /**
     * 照片是否已攤開。
     *
     * <p>放 ViewModel 而不是 Activity：SPEC 第 7 節第 2 點，摺疊會重建 Activity。
     * 攤開照片看到一半展開內螢幕就自己收起來，在摺疊機上會很常發生。
     */
    public LiveData<Boolean> isPhotoExpanded() {
        return photoExpanded;
    }

    public void togglePhoto() {
        photoExpanded.setValue(!Boolean.TRUE.equals(photoExpanded.getValue()));
    }

    private void loadSourcePhoto(@Nullable WordEntry card) {
        photoExpanded.postValue(false);      // 換卡就收起來
        if (card == null || card.imageUri == null || card.imageUri.isEmpty()) {
            sourcePhoto.postValue(null);
            return;
        }
        String name = card.imageUri;
        words.io().execute(() -> sourcePhoto.postValue(words.sourcePhotoFor(name)));
    }

    private void loadExamples(@Nullable WordEntry card) {
        if (card == null) {
            examples.postValue(CardExamples.NONE);
            return;
        }
        examples.postValue(CardExamples.NONE);   // 先清掉上一張的，避免短暫串卡
        long wordId = card.id;
        String headword = card.headword;
        words.io().execute(() ->
                examples.postValue(words.examplesFor(wordId, headword)));
    }

    /** {@code [已完成, 總數]}，給進度條與「7 / 23」用。 */
    public LiveData<int[]> getProgress() {
        return progress;
    }

    /** 四顆按鈕各自的預估間隔（分鐘），SPEC 第 6.3 節要求顯示在按鈕下方。 */
    public LiveData<Map<Grade, Integer>> getNextIntervals() {
        return nextIntervals;
    }

    public LiveData<Direction> getDirection() {
        return direction;
    }

    public int getDeferredCount() {
        return deferredCount;
    }

    public int getReviewedCount() {
        return reviewedCount;
    }

    /** 只會真的跑一次：Activity 重建時不重新組佇列，否則進度會被重設。 */
    public void startIfNeeded() {
        if (started) {
            return;
        }
        started = true;

        repository.io().execute(() -> {
            long now = System.currentTimeMillis();
            // 上限在 session 開始時讀一次就定案。中途改設定不會動到正在進行的佇列 ——
            // SPEC 第三原則：複習節奏不能被打斷。
            ReviewRepository.Session session = repository.startSession(
                    currentDirection(), settings.limits(), now);

            queue = session.cards;
            deferredCount = session.deferredCount;
            index = 0;
            reviewedCount = 0;

            publishCurrent();
        });
    }

    private Direction currentDirection() {
        Direction d = direction.getValue();
        return d == null ? Direction.EN_TO_ZH : d;
    }

    /**
     * SPEC 第 6.2 節的方向切換鍵。
     *
     * <p>Phase 2 只實作 EN_TO_ZH 的排程（SPEC 第 3 節：schema 已留好複合主鍵，
     * 未來開雙向卡不必動資料庫）。這裡只切換顯示狀態，
     * <b>排程一律用 EN_TO_ZH</b>，等真的有雙向卡需求時再把 currentDirection() 接上。
     */
    public void toggleDirection() {
        Direction next = currentDirection() == Direction.EN_TO_ZH
                ? Direction.ZH_TO_EN : Direction.EN_TO_ZH;
        direction.setValue(next);
    }

    public void flip() {
        if (stage.getValue() != Stage.REVIEWING) {
            return;
        }
        flipped.setValue(Boolean.TRUE.equals(flipped.getValue()) ? Boolean.FALSE : Boolean.TRUE);
    }

    public void showAnswer() {
        if (stage.getValue() == Stage.REVIEWING) {
            flipped.setValue(Boolean.TRUE);
        }
    }

    /** 評分並前進到下一張。 */
    public void grade(Grade grade) {
        WordEntry card = currentCard.getValue();
        if (card == null || stage.getValue() != Stage.REVIEWING) {
            return;
        }

        // 先在 UI 執行緒前進，複習節奏不能等資料庫（SPEC 第三原則）
        index++;
        reviewedCount++;
        flipped.setValue(false);
        publishCurrentOnMain();

        long now = System.currentTimeMillis();
        repository.io().execute(() ->
                repository.grade(card.id, Direction.EN_TO_ZH, grade, now));
    }

    private void publishCurrent() {
        // 背景執行緒版本
        if (index >= queue.size()) {
            stage.postValue(Stage.FINISHED);
            progress.postValue(new int[]{queue.size(), queue.size()});
            currentCard.postValue(null);
            examples.postValue(CardExamples.NONE);
            return;
        }
        WordEntry card = queue.get(index);
        stage.postValue(Stage.REVIEWING);
        currentCard.postValue(card);
        loadExamples(card);
        loadSourcePhoto(card);
        flipped.postValue(false);
        progress.postValue(new int[]{index, queue.size()});
        nextIntervals.postValue(estimateIntervals(card));
    }

    private void publishCurrentOnMain() {
        if (index >= queue.size()) {
            stage.setValue(Stage.FINISHED);
            progress.setValue(new int[]{queue.size(), queue.size()});
            currentCard.setValue(null);
            examples.setValue(CardExamples.NONE);
            return;
        }
        WordEntry card = queue.get(index);
        stage.setValue(Stage.REVIEWING);
        currentCard.setValue(card);
        loadExamples(card);
        loadSourcePhoto(card);
        progress.setValue(new int[]{index, queue.size()});
        // 預估間隔要讀資料庫的排程狀態，丟背景
        repository.io().execute(() -> nextIntervals.postValue(estimateIntervals(card)));
    }

    /**
     * 算出四顆按鈕各自按下去會排到多久之後。
     *
     * <p>用的是已測試過的 {@link Sm2Scheduler}，所以顯示的數字跟真正會發生的事一致，
     * 不是 SPEC 表格裡的靜態值 —— 複習過幾次的卡，間隔會比表格長。
     */
    private Map<Grade, Integer> estimateIntervals(WordEntry card) {
        ReviewState state = repository.findState(card.id, Direction.EN_TO_ZH);
        Schedule schedule = state == null
                ? Schedule.newCard(System.currentTimeMillis())
                : ReviewStateMapper.toSchedule(state);

        long now = System.currentTimeMillis();
        Map<Grade, Integer> result = new EnumMap<>(Grade.class);
        for (Grade g : Grade.values()) {
            result.put(g, Sm2Scheduler.next(schedule, g, now).intervalMinutes);
        }
        return result;
    }

    @Nullable
    public WordEntry peekCurrent() {
        return currentCard.getValue();
    }
}
