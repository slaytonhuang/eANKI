package com.example.eanki.ui.placeholder;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.eanki.databinding.FragmentPlaceholderBinding;

/**
 * 熟悉度與設定分頁的預留畫面。
 *
 * <p>兩頁的內容都排在 Phase 4（SPEC 第 8 節：熟悉度統計、加強複習、設定頁完善）。
 * 這裡先讓四個分頁都點得動，導覽骨架才算立起來。
 */
public class PlaceholderFragment extends Fragment {

    private static final String ARG_TITLE = "title";
    private static final String ARG_BODY = "body";

    public static PlaceholderFragment create(String title, String body) {
        PlaceholderFragment fragment = new PlaceholderFragment();
        Bundle args = new Bundle();
        args.putString(ARG_TITLE, title);
        args.putString(ARG_BODY, body);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        FragmentPlaceholderBinding binding =
                FragmentPlaceholderBinding.inflate(inflater, container, false);

        Bundle args = getArguments();
        if (args != null) {
            binding.placeholderTitle.setText(args.getString(ARG_TITLE, ""));
            binding.placeholderBody.setText(args.getString(ARG_BODY, ""));
        }
        return binding.getRoot();
    }
}
