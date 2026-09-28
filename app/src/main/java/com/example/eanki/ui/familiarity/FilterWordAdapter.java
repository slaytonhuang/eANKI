package com.example.eanki.ui.familiarity;

import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.example.eanki.R;
import com.example.eanki.data.user.ReviewStateDao.FamiliarityRow;
import com.example.eanki.databinding.ItemFilterWordBinding;
import com.example.eanki.review.Grade;

import java.util.Collections;
import java.util.Set;

/**
 * 熟悉度篩選的清單。SPEC 第 5 節：checkbox 多選 + 最近三次評分小點。
 *
 * <p>勾選狀態不存在這裡，只從 {@link #selected} 讀 —— 真正的來源是
 * {@link FamiliarityFilterViewModel}，摺疊重建 Activity 時才不會掉。
 */
public class FilterWordAdapter extends ListAdapter<FamiliarityRow, FilterWordAdapter.Holder> {

    public interface OnToggle {
        void onToggle(long wordId);
    }

    private static final DiffUtil.ItemCallback<FamiliarityRow> DIFF =
            new DiffUtil.ItemCallback<FamiliarityRow>() {
                @Override
                public boolean areItemsTheSame(@NonNull FamiliarityRow a,
                                               @NonNull FamiliarityRow b) {
                    return a.wordId == b.wordId;
                }

                @Override
                public boolean areContentsTheSame(@NonNull FamiliarityRow a,
                                                  @NonNull FamiliarityRow b) {
                    // 勾選狀態不在 row 裡，所以這裡只比資料本身；
                    // 勾選變動由 notifyItemChanged 另外推
                    return TextUtils.equals(a.headword, b.headword)
                            && TextUtils.equals(a.translation, b.translation)
                            && same(a.grade1, b.grade1)
                            && same(a.grade2, b.grade2)
                            && same(a.grade3, b.grade3);
                }

                private boolean same(@Nullable Integer a, @Nullable Integer b) {
                    return a == null ? b == null : a.equals(b);
                }
            };

    private final OnToggle onToggle;
    private Set<Long> selected = Collections.emptySet();

    public FilterWordAdapter(OnToggle onToggle) {
        super(DIFF);
        this.onToggle = onToggle;
    }

    public void setSelected(@NonNull Set<Long> next) {
        this.selected = next;
        notifyItemRangeChanged(0, getItemCount(), Boolean.TRUE);
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemFilterWordBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(getItem(position));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position,
                                 @NonNull java.util.List<Object> payloads) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads);
            return;
        }
        // 只有勾選變了：不要重綁文字與小點，1,464 筆時全量重綁會掉幀
        holder.bindChecked(getItem(position).wordId);
    }

    class Holder extends RecyclerView.ViewHolder {

        private final ItemFilterWordBinding binding;

        Holder(ItemFilterWordBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            binding.getRoot().setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) {
                    onToggle.onToggle(getItem(pos).wordId);
                }
            });
        }

        void bind(FamiliarityRow row) {
            binding.headword.setText(row.headword);
            binding.translation.setText(TextUtils.isEmpty(row.translation)
                    ? binding.getRoot().getContext().getString(R.string.no_translation)
                    : row.translation.replace("\n", " / "));

            // 由左到右是舊 → 新，最右邊是最近一次
            dot(binding.dot3, row.grade3);
            dot(binding.dot2, row.grade2);
            dot(binding.dot1, row.grade1);

            bindChecked(row.wordId);
        }

        void bindChecked(long wordId) {
            binding.check.setChecked(selected.contains(wordId));
        }

        private void dot(View view, @Nullable Integer code) {
            Grade g = code == null ? null : Grade.fromCode(code);
            view.setBackgroundTintList(ColorStateList.valueOf(
                    view.getContext().getColor(LevelPalette.color(g))));
        }
    }
}
