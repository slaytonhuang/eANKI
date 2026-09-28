package com.example.eanki.ui.practice;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.eanki.data.ReviewRepository;
import com.example.eanki.data.user.Direction;
import com.example.eanki.data.CardExamples;
import com.example.eanki.data.WordRepository;
import com.example.eanki.data.user.WordEntry;
import com.example.eanki.review.Grade;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次加強複習的全部狀態。SPEC 第 6.6 節。
 *
 * <p>結構跟 {@link com.example.eanki.ui.review.ReviewViewModel} 很像，但<b>刻意不共用</b>：
 * 那一個會呼叫 {@code startSession()} 放行新字、會算預估間隔、會寫回 {@code due_date}。
 * 加強複習三件都不做，繼承或共用只會讓「絕不動排程」這條線變模糊。
 *
 * <p>進度與目前第幾張放這裡而不是 Activity —— SPEC 第 7 節第 2 點，摺疊會重建 Activity。
 */
public class ExtraPracticeViewModel extends AndroidViewModel {

    public enum Stage { LOADING, PRACTISING, FINISHED }

    private final ReviewRepository repository;
    private final WordRepository words;

    private final MutableLiveData<Stage> stage = new MutableLiveData<>(Stage.LOADING);
    private final MutableLiveData<WordEntry> currentCard = new MutableLiveData<>();
    private final MutableLiveData<Boolean> flipped = new MutableLiveData<>(false);
    private final MutableLiveData<CardExamples> examples =
            new MutableLiveData<>(CardExamples.NONE);
    private final MutableLiveData<java.io.File> sourcePhoto = new MutableLiveData<>();
    private final MutableLiveData<Boolean> photoExpanded = new MutableLiveData<>(false);
    private final MutableLiveData<int[]> progress = new MutableLiveData<>(new int[]{0, 0});

    private List<WordEntry> queue = new ArrayList<>();
    private int index;
    private int practisedCount;
    private boolean started;

    public ExtraPracticeViewModel(@NonNull Application application) {
        super(application);
        repository = ReviewRepository.get(application);
        words = WordRepository.get(application);
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

    public LiveData<int[]> getProgress() {
        return progress;
    }

    public int getPractisedCount() {
        return practisedCount;
    }

    /**
     * 佇列在開始時就定案，之後不重組。
     *
     * <p>只會真的跑一次：Activity 重建時重跑會把進度歸零。
     */
    public void startIfNeeded(@NonNull long[] wordIds) {
        if (started) {
            return;
        }
        started = true;

        List<Long> ids = new ArrayList<>(wordIds.length);
        for (long id : wordIds) {
            ids.add(id);
        }

        repository.io().execute(() -> {
            queue = repository.loadOrdered(ids);
            index = 0;
            practisedCount = 0;
            publishCurrent(true);
        });
    }

    public void flip() {
        if (stage.getValue() != Stage.PRACTISING) {
            return;
        }
        flipped.setValue(Boolean.TRUE.equals(flipped.getValue()) ? Boolean.FALSE : Boolean.TRUE);
    }

    public void showAnswer() {
        if (stage.getValue() == Stage.PRACTISING) {
            flipped.setValue(Boolean.TRUE);
        }
    }

    /**
     * 評分並前進。
     *
     * <p>走的是 {@link ReviewRepository#gradeExtraPractice}，<b>不是</b> {@code grade()} ——
     * 差別就是會不會動 {@code due_date}。SPEC 第 6.6 節的整節都在講這件事。
     */
    public void grade(Grade grade) {
        WordEntry card = currentCard.getValue();
        if (card == null || stage.getValue() != Stage.PRACTISING) {
            return;
        }

        index++;
        practisedCount++;
        flipped.setValue(false);
        publishCurrent(false);

        repository.io().execute(() ->
                repository.gradeExtraPractice(card.id, Direction.EN_TO_ZH, grade));
    }

    /** @param background true 代表現在在 IO 執行緒上，要用 postValue */
    private void publishCurrent(boolean background) {
        if (index >= queue.size()) {
            set(stage, Stage.FINISHED, background);
            set(progress, new int[]{queue.size(), queue.size()}, background);
            set(currentCard, null, background);
            set(examples, CardExamples.NONE, background);
            return;
        }
        WordEntry card = queue.get(index);
        set(stage, Stage.PRACTISING, background);
        set(currentCard, card, background);
        loadExamples(card);
        loadSourcePhoto(card);
        set(flipped, Boolean.FALSE, background);
        set(progress, new int[]{index, queue.size()}, background);
    }

    private static <T> void set(MutableLiveData<T> live, @Nullable T value, boolean background) {
        if (background) {
            live.postValue(value);
        } else {
            live.setValue(value);
        }
    }

    @Nullable
    public WordEntry peekCurrent() {
        return currentCard.getValue();
    }
}
