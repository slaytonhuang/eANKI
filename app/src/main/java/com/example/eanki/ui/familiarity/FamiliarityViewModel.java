package com.example.eanki.ui.familiarity;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;

import com.example.eanki.data.ReviewRepository;
import com.example.eanki.data.WordRepository;
import com.example.eanki.data.user.Direction;
import com.example.eanki.data.user.ReviewStateDao;
import com.example.eanki.data.user.WordEntry;
import com.example.eanki.review.FamiliarityStats;

import java.util.ArrayList;
import java.util.List;

/**
 * 熟悉度分布。SPEC 第 5 節。
 *
 * <p>兩個來源都是 Room 的 LiveData，所以<b>複習時評分一按下去，
 * 這裡的數字就會跟著動</b>，不需要手動刷新。
 */
public class FamiliarityViewModel extends AndroidViewModel {

    private final LiveData<List<ReviewStateDao.GradeRow>> grades;
    private final LiveData<List<WordEntry>> allWords;
    private final MediatorLiveData<FamiliarityStats> stats = new MediatorLiveData<>();

    public FamiliarityViewModel(@NonNull Application application) {
        super(application);

        grades = ReviewRepository.get(application).observeGrades(Direction.EN_TO_ZH);
        allWords = WordRepository.get(application).observeAll();

        stats.addSource(grades, v -> recompute());
        stats.addSource(allWords, v -> recompute());
    }

    private void recompute() {
        List<ReviewStateDao.GradeRow> rows = grades.getValue();
        List<WordEntry> words = allWords.getValue();
        if (rows == null || words == null) {
            return;
        }
        List<FamiliarityStats.GradeTriple> triples = new ArrayList<>(rows.size());
        for (ReviewStateDao.GradeRow row : rows) {
            triples.add(new FamiliarityStats.GradeTriple(
                    row.wordId, row.grade1, row.grade2, row.grade3));
        }
        stats.setValue(FamiliarityStats.of(triples, words.size()));
    }

    public LiveData<FamiliarityStats> getStats() {
        return stats;
    }

    @Nullable
    public FamiliarityStats peek() {
        return stats.getValue();
    }
}
