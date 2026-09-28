package com.example.eanki.ui.today;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import com.example.eanki.data.ReviewRepository;
import com.example.eanki.data.WordRepository;
import com.example.eanki.data.user.Direction;
import com.example.eanki.data.user.ReviewLogDao;
import com.example.eanki.data.user.WordEntry;
import com.example.eanki.data.settings.AppSettings;
import com.example.eanki.review.SevenDayWindow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 今日頁的資料。全部走 Room 的 LiveData，加字、刪字、評分都會即時反映。
 */
public class TodayViewModel extends AndroidViewModel {

    private final LiveData<List<WordEntry>> allWords;
    private final LiveData<List<WordEntry>> recent;
    private final LiveData<List<ReviewLogDao.DailyCount>> dailyCounts;

    private final LiveData<Integer> dueCount;
    private final LiveData<Integer> unreleasedCount;
    private final LiveData<Integer> releasedToday;

    /** {@code [待複習, 今日還能放行的新字]} */
    private final MediatorLiveData<int[]> counts = new MediatorLiveData<>();

    private final AppSettings settings;

    private final MutableLiveData<Direction> direction =
            new MutableLiveData<>(Direction.EN_TO_ZH);

    public TodayViewModel(@NonNull Application application) {
        super(application);
        settings = AppSettings.get(application);

        long now = System.currentTimeMillis();
        WordRepository words = WordRepository.get(application);
        ReviewRepository reviews = ReviewRepository.get(application);

        allWords = words.observeAll();
        recent = words.observeRecent(3);
        dailyCounts = reviews.observeRecentDailyCounts(now);

        dueCount = reviews.observeDueCount(Direction.EN_TO_ZH, now);
        unreleasedCount = reviews.observeUnreleasedCount(Direction.EN_TO_ZH);
        releasedToday = reviews.observeReleasedToday(Direction.EN_TO_ZH, now);

        counts.setValue(new int[]{0, 0});
        counts.addSource(dueCount, v -> recompute());
        counts.addSource(unreleasedCount, v -> recompute());
        counts.addSource(releasedToday, v -> recompute());
    }

    private static int valueOf(@Nullable LiveData<Integer> source) {
        Integer v = source == null ? null : source.getValue();
        return v == null ? 0 : v;
    }

    private void recompute() {
        // 每次都重讀設定：使用者可能剛從設定頁調完上限切回今日頁，
        // 這裡顯示的「今天還能學幾個新字」必須馬上跟著變
        int remainingAllowance = Math.max(0,
                settings.limits().newPerDay - valueOf(releasedToday));
        counts.setValue(new int[]{
                valueOf(dueCount),
                Math.min(valueOf(unreleasedCount), remainingAllowance)});
    }

    public LiveData<List<WordEntry>> getAllWords() {
        return allWords;
    }

    public LiveData<List<WordEntry>> getRecent() {
        return recent;
    }

    public LiveData<int[]> getCounts() {
        return counts;
    }

    public LiveData<Direction> getDirection() {
        return direction;
    }

    /**
     * 過去七天每天複習了幾張，索引 0 是最舊、6 是今天。
     *
     * <p>SPEC 第 6.7 節：滾動七天而非日曆週，週一打開才不會只看到一格。
     */
    public LiveData<int[]> getHeatBuckets() {
        MediatorLiveData<int[]> buckets = new MediatorLiveData<>();
        buckets.addSource(dailyCounts, list -> {
            Map<String, Integer> byDay = new HashMap<>();
            if (list != null) {
                for (ReviewLogDao.DailyCount c : list) {
                    byDay.put(c.day, c.count);
                }
            }
            buckets.setValue(SevenDayWindow.toBuckets(byDay, System.currentTimeMillis()));
        });
        return buckets;
    }

    /**
     * SPEC 第 6.2 節的方向切換。Phase 2 已決定排程一律走 EN_TO_ZH，
     * 這裡只切換顯示，等真的有雙向卡需求時再把排程接上。
     */
    public void toggleDirection() {
        Direction current = direction.getValue();
        direction.setValue(current == Direction.EN_TO_ZH
                ? Direction.ZH_TO_EN : Direction.EN_TO_ZH);
    }
}
