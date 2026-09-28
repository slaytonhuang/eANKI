package com.example.eanki.ui.wordlist;

import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.example.eanki.R;
import com.example.eanki.data.user.ReviewStateDao;
import com.example.eanki.data.user.WordEntry;
import com.example.eanki.review.DueLabel;
import com.example.eanki.review.FamiliarityRule;
import com.example.eanki.review.Grade;
import com.example.eanki.ui.familiarity.LevelPalette;
import com.example.eanki.databinding.ItemWordBinding;

import java.util.Collections;
import java.util.Map;

/**
 * SPEC 第 2 節指定 RecyclerView。用 ListAdapter + DiffUtil，
 * 刪除時才會有正確的動畫，而不是整份重畫。
 */
public class WordAdapter extends ListAdapter<WordEntry, WordAdapter.WordViewHolder> {

    public interface Listener {
        void onSpeak(WordEntry word);

        void onDelete(WordEntry word);
    }

    private static final DiffUtil.ItemCallback<WordEntry> DIFF =
            new DiffUtil.ItemCallback<WordEntry>() {
                @Override
                public boolean areItemsTheSame(@NonNull WordEntry a, @NonNull WordEntry b) {
                    return a.id == b.id;
                }

                @Override
                public boolean areContentsTheSame(@NonNull WordEntry a, @NonNull WordEntry b) {
                    return a.headword.equals(b.headword)
                            && TextUtils.equals(a.phonetic, b.phonetic)
                            && TextUtils.equals(a.translation, b.translation);
                }
            };

    private final Listener listener;

    /**
     * 每一列的熟悉度與到期時間，key 是 word_id。
     *
     * <p>跟清單分開來：清單走的是可切換條件的篩選查詢，
     * 再塞 JOIN 進去會讓那條 SQL 難讀。已放行的字一年也才三千多筆。
     */
    private Map<Long, ReviewStateDao.RowMeta> meta = Collections.emptyMap();

    public WordAdapter(Listener listener) {
        super(DIFF);
        this.listener = listener;
    }

    public void setMeta(@NonNull Map<Long, ReviewStateDao.RowMeta> next) {
        this.meta = next;
        notifyItemRangeChanged(0, getItemCount(), Boolean.TRUE);
    }

    @NonNull
    @Override
    public WordViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemWordBinding binding = ItemWordBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new WordViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull WordViewHolder holder, int position) {
        WordEntry word = getItem(position);
        holder.bind(word, meta.get(word.id), listener);
    }

    @Override
    public void onBindViewHolder(@NonNull WordViewHolder holder, int position,
                                 @NonNull java.util.List<Object> payloads) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads);
            return;
        }
        // 只有排程資料到齊：不重綁文字，1,464 筆時全量重綁會掉幀
        holder.bindMeta(meta.get(getItem(position).id));
    }

    static class WordViewHolder extends RecyclerView.ViewHolder {

        private final ItemWordBinding binding;

        WordViewHolder(ItemWordBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(WordEntry word, @Nullable ReviewStateDao.RowMeta rowMeta, Listener listener) {
            binding.headword.setText(word.headword);

            if (TextUtils.isEmpty(word.phonetic)) {
                binding.phonetic.setVisibility(android.view.View.GONE);
            } else {
                binding.phonetic.setVisibility(android.view.View.VISIBLE);
                binding.phonetic.setText("/" + word.phonetic + "/");
            }

            if (TextUtils.isEmpty(word.translation)) {
                binding.translation.setText(R.string.no_translation);
            } else {
                // 換行分隔的多個詞性壓成一行。結果快取在 WordEntry 上，
                // 捲動時同一個物件被反覆 bind 不會每次重算。
                binding.translation.setText(word.singleLineTranslation());
            }

            binding.btnSpeak.setOnClickListener(v -> listener.onSpeak(word));
            binding.btnDelete.setOnClickListener(v -> listener.onDelete(word));
            bindMeta(rowMeta);
        }

        /** 熟悉度色點與「下次複習」（SPEC 第 5 節）。 */
        void bindMeta(@Nullable ReviewStateDao.RowMeta rowMeta) {
            Grade level = rowMeta == null
                    ? null
                    : FamiliarityRule.ofCodes(rowMeta.grade1, rowMeta.grade2, rowMeta.grade3);
            binding.familiarityDot.setBackgroundTintList(ColorStateList.valueOf(
                    binding.getRoot().getContext().getColor(LevelPalette.color(level))));

            DueLabel due = DueLabel.of(
                    rowMeta == null ? null : rowMeta.dueDate, System.currentTimeMillis());
            binding.nextReview.setText(label(due));
            binding.nextReview.setTextColor(binding.getRoot().getContext().getColor(
                    due.kind == DueLabel.Kind.DUE_NOW ? R.color.mint : R.color.text_muted));
        }

        private String label(DueLabel due) {
            android.content.Context context = binding.getRoot().getContext();
            switch (due.kind) {
                case DUE_NOW:
                    return context.getString(R.string.due_now);
                case LATER_TODAY:
                    return context.getString(R.string.due_later_today);
                case IN_DAYS:
                    return context.getString(R.string.due_in_days, due.days);
                case NOT_STARTED:
                default:
                    return context.getString(R.string.due_not_started);
            }
        }
    }
}
