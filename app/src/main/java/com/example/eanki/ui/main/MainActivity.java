package com.example.eanki.ui.main;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.eanki.R;
import com.example.eanki.databinding.ActivityMainBinding;
import com.example.eanki.ui.familiarity.FamiliarityFragment;
import com.example.eanki.ui.settings.SettingsFragment;
import com.example.eanki.ui.scan.ScanActivity;
import com.example.eanki.ui.today.TodayFragment;
import com.example.eanki.ui.wordlist.WordListFragment;
import com.google.android.material.snackbar.Snackbar;

/**
 * 導覽骨架。SPEC 第 5 節的底部四分頁 + 浮動相機鍵。
 *
 * <p>四個分頁共用這一個 Activity。分頁本身不持有狀態，
 * 各自的進度與資料都在自己的 ViewModel，摺疊重建也不會掉（SPEC 第 7 節）。
 */
public class MainActivity extends AppCompatActivity {

    private static final String KEY_TAB = "selected_tab";

    private ActivityMainBinding binding;

    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted && binding != null) {
                    Snackbar.make(binding.root, R.string.notif_permission_rationale,
                            Snackbar.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.bottomNav.setOnItemSelectedListener(item -> {
            showTab(item.getItemId());
            return true;
        });
        // 重選同一個分頁不要重建 Fragment，否則會把捲動位置歸零
        binding.bottomNav.setOnItemReselectedListener(item -> { });

        binding.fabScan.setOnClickListener(v ->
                startActivity(new Intent(this, ScanActivity.class)));

        int tab = savedInstanceState == null
                ? R.id.tab_today
                : savedInstanceState.getInt(KEY_TAB, R.id.tab_today);

        // BottomNavigationView 預設就已經選中第一個項目，所以對「今日」呼叫
        // setSelectedItemId 只會觸發 reselect（我們刻意設成不做事），select 不會來，
        // Fragment 就永遠不會被加進去。初次進來一律自己掛一次。
        if (savedInstanceState == null) {
            showTab(tab);
        }
        binding.bottomNav.setSelectedItemId(tab);

        askForNotificationPermission();
    }

    /** 今日頁的「開始複習」之外，單字本也可能想把使用者帶回今日頁。 */
    public void selectTab(int menuItemId) {
        binding.bottomNav.setSelectedItemId(menuItemId);
    }

    private void showTab(int itemId) {
        // 設定頁不顯示相機鍵。
        //
        // SPEC 第 5 節把相機鍵列為導覽骨架的一部分，但它是浮在內容上的，
        // 在設定頁會剛好蓋住某一列的開關 —— 使用者看不到那個設定目前是開是關。
        // 設定頁是全 App 唯一「不會想在這裡加字」的畫面，而且相機鍵只差一個分頁，
        // 第一原則（加字不用打字）並沒有被犧牲。
        binding.fabScan.setVisibility(
                itemId == R.id.tab_settings ? View.GONE : View.VISIBLE);

        Fragment fragment = createFragment(itemId);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.container, fragment, String.valueOf(itemId))
                .commit();
    }

    private Fragment createFragment(int itemId) {
        if (itemId == R.id.tab_words) {
            return new WordListFragment();
        }
        if (itemId == R.id.tab_familiarity) {
            return new FamiliarityFragment();
        }
        if (itemId == R.id.tab_settings) {
            return new SettingsFragment();
        }
        return new TodayFragment();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_TAB, binding.bottomNav.getSelectedItemId());
    }

    /** Android 13 起發通知要授權，沒授權每日提醒會被系統靜靜丟掉。只問一次。 */
    private void askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    @Override
    protected void onDestroy() {
        binding = null;
        super.onDestroy();
    }
}
