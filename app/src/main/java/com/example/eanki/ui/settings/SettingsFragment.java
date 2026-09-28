package com.example.eanki.ui.settings;

import android.app.TimePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.app.NotificationManagerCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.example.eanki.R;
import com.example.eanki.data.settings.AppSettings;
import com.example.eanki.databinding.FragmentSettingsBinding;
import com.example.eanki.databinding.ViewSettingRowBinding;
import com.example.eanki.databinding.ViewSettingSwitchBinding;
import com.example.eanki.reminder.ReminderScheduler;
import com.example.eanki.review.DailyLimits;
import com.example.eanki.tts.TtsManager;
import com.google.android.material.snackbar.Snackbar;

import java.util.Locale;

/**
 * 設定。SPEC 第 5 節。
 *
 * <p><b>每一項都是即時生效的，沒有「儲存」鍵。</b>設定頁只有八個選項，
 * 多一個儲存步驟只會製造「改了但忘了存」。
 *
 * <p>改動之後要主動同步的有兩處：提醒相關的改動要重排 WorkManager
 * （不然要等明天那次 Worker 跑完才套用），語速要立刻推給 TTS 引擎。
 * 每日上限不需要 —— 複習佇列在 session 開始時讀一次就定案，
 * 中途改設定不會動到正在進行的佇列（SPEC 第三原則）。
 */
public class SettingsFragment extends Fragment {

    /**
     * 匯出／還原都走系統的檔案選擇器。
     *
     * <p>備份寫到 App 私有目錄沒有意義 —— 解除安裝或清除資料時會一起消失，
     * 而那正是最需要備份的兩個時刻。交給 SAF，使用者可以存到雲端硬碟或下載資料夾。
     */
    private final androidx.activity.result.ActivityResultLauncher<String> exportPicker =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts
                            .CreateDocument("application/json"),
                    this::onExportTarget);

    private final androidx.activity.result.ActivityResultLauncher<String[]> restorePicker =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    this::onRestoreSource);

    private FragmentSettingsBinding binding;
    private SettingsViewModel viewModel;
    private AppSettings settings;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        settings = AppSettings.get(requireContext());
        viewModel = new ViewModelProvider(this).get(SettingsViewModel.class);

        bindVolume();
        bindReminder();
        bindSpeech();
        bindBackup();
        bindDictionary();

        viewModel.getDictStatus().observe(getViewLifecycleOwner(), this::renderDictStatus);
        viewModel.getTodayPreview().observe(getViewLifecycleOwner(), this::renderVolumePreview);
    }

    @Override
    public void onResume() {
        super.onResume();
        // 使用者可能剛從系統設定回來（通知權限、電池最佳化），狀態要重讀
        renderPermissionRows();
        viewModel.refreshTodayPreview();
    }

    // ---------------- 每日份量 ----------------

    private void bindVolume() {
        DailyLimits limits = settings.limits();

        binding.newPerDaySlider.setValueFrom(DailyLimits.MIN_NEW_PER_DAY);
        binding.newPerDaySlider.setValueTo(DailyLimits.MAX_NEW_PER_DAY);
        setSliderValue(binding.newPerDaySlider, limits.newPerDay);
        binding.newPerDayValue.setText(
                String.valueOf((int) binding.newPerDaySlider.getValue()));

        binding.reviewsPerDaySlider.setValueFrom(DailyLimits.MIN_REVIEWS_PER_DAY);
        binding.reviewsPerDaySlider.setValueTo(DailyLimits.MAX_REVIEWS_PER_DAY);
        setSliderValue(binding.reviewsPerDaySlider, limits.reviewsPerDay);
        binding.reviewsPerDayValue.setText(
                String.valueOf((int) binding.reviewsPerDaySlider.getValue()));

        // 拖曳過程只更新數字，放開才寫入 —— 拖一次會經過幾十個值，
        // 每個都寫 SharedPreferences 又重算預覽是白費的
        binding.newPerDaySlider.addOnChangeListener((slider, value, fromUser) ->
                binding.newPerDayValue.setText(String.valueOf((int) value)));
        binding.newPerDaySlider.addOnSliderTouchListener(
                new com.google.android.material.slider.Slider.OnSliderTouchListener() {
                    @Override
                    public void onStartTrackingTouch(
                            @NonNull com.google.android.material.slider.Slider slider) {
                    }

                    @Override
                    public void onStopTrackingTouch(
                            @NonNull com.google.android.material.slider.Slider slider) {
                        settings.setNewPerDay((int) slider.getValue());
                        viewModel.refreshTodayPreview();
                    }
                });

        binding.reviewsPerDaySlider.addOnChangeListener((slider, value, fromUser) ->
                binding.reviewsPerDayValue.setText(String.valueOf((int) value)));
        binding.reviewsPerDaySlider.addOnSliderTouchListener(
                new com.google.android.material.slider.Slider.OnSliderTouchListener() {
                    @Override
                    public void onStartTrackingTouch(
                            @NonNull com.google.android.material.slider.Slider slider) {
                    }

                    @Override
                    public void onStopTrackingTouch(
                            @NonNull com.google.android.material.slider.Slider slider) {
                        settings.setReviewsPerDay((int) slider.getValue());
                        viewModel.refreshTodayPreview();
                    }
                });
    }

    private void renderVolumePreview(@Nullable Integer count) {
        if (binding == null || count == null) {
            return;
        }
        binding.volumePreview.setText(count == 0
                ? getString(R.string.settings_volume_preview_none)
                : getString(R.string.settings_volume_preview, count));
    }

    // ---------------- 提醒 ----------------

    private void bindReminder() {
        bindSwitch(binding.rowReminderEnabled,
                R.string.settings_reminder_enabled,
                R.string.settings_reminder_enabled_note,
                settings.reminderEnabled(),
                checked -> {
                    settings.setReminderEnabled(checked);
                    // 立刻重排／取消，不然要等明天 Worker 跑完才生效
                    ReminderScheduler.schedule(requireContext());
                    renderReminderTime();
                    renderPermissionRows();
                });

        binding.rowReminderTime.getRoot().setOnClickListener(v -> pickTime());
        renderReminderTime();

        bindSwitch(binding.rowSkipEmpty,
                R.string.settings_skip_empty,
                R.string.settings_skip_empty_note,
                settings.skipEmptyReminder(),
                settings::setSkipEmptyReminder);

        binding.rowBattery.getRoot().setOnClickListener(v -> openBatterySettings());
        binding.rowNotificationPermission.getRoot()
                .setOnClickListener(v -> openAppNotificationSettings());
        renderPermissionRows();
    }

    private void renderReminderTime() {
        boolean enabled = settings.reminderEnabled();
        ViewSettingRowBinding row = binding.rowReminderTime;
        row.rowTitle.setText(R.string.settings_reminder_time);
        row.rowSubtitle.setVisibility(View.VISIBLE);
        row.rowSubtitle.setText(R.string.settings_reminder_time_note);
        row.rowValue.setText(String.format(Locale.US, "%02d:%02d",
                settings.reminderHour(), settings.reminderMinute()));
        row.getRoot().setEnabled(enabled);
        row.getRoot().setAlpha(enabled ? 1f : 0.45f);
    }

    private void pickTime() {
        new TimePickerDialog(requireContext(), (picker, hour, minute) -> {
            settings.setReminderTime(hour, minute);
            ReminderScheduler.schedule(requireContext());
            renderReminderTime();
        }, settings.reminderHour(), settings.reminderMinute(), true).show();
    }

    /**
     * 兩個「App 管不到、只能帶去系統設定」的項目。
     *
     * <p>沒有通知權限的話，每日提醒會被系統靜靜丟掉 —— Worker 有跑、
     * 通知沒出現，使用者只會覺得功能壞了。電池最佳化則會讓 Doze 把提醒
     * 往後推一兩個小時。兩者都要把現況顯示出來，不是藏著。
     */
    private void renderPermissionRows() {
        if (binding == null) {
            return;
        }
        boolean notificationsOn = NotificationManagerCompat.from(requireContext())
                .areNotificationsEnabled();
        ViewSettingRowBinding perm = binding.rowNotificationPermission;
        perm.rowTitle.setText(R.string.settings_notification_permission);
        perm.rowSubtitle.setVisibility(View.VISIBLE);
        perm.rowSubtitle.setText(R.string.settings_notification_permission_note);
        perm.rowValue.setText(notificationsOn
                ? R.string.settings_state_on : R.string.settings_state_off);
        perm.rowValue.setTextColor(requireContext().getColor(
                notificationsOn ? R.color.mint : R.color.brick_text));

        ViewSettingRowBinding battery = binding.rowBattery;
        battery.rowTitle.setText(R.string.settings_battery);
        battery.rowSubtitle.setVisibility(View.VISIBLE);
        battery.rowSubtitle.setText(R.string.settings_battery_note);
        Boolean unrestricted = isIgnoringBatteryOptimizations();
        if (unrestricted == null) {
            battery.rowValue.setText(R.string.settings_state_unknown);
            battery.rowValue.setTextColor(requireContext().getColor(R.color.text_secondary));
        } else {
            battery.rowValue.setText(unrestricted
                    ? R.string.settings_state_unrestricted : R.string.settings_state_restricted);
            battery.rowValue.setTextColor(requireContext().getColor(
                    unrestricted ? R.color.mint : R.color.amber_text));
        }
    }

    /** @return null 代表查不到（沒有 PowerManager） */
    @Nullable
    private Boolean isIgnoringBatteryOptimizations() {
        PowerManager pm = (PowerManager)
                requireContext().getSystemService(android.content.Context.POWER_SERVICE);
        if (pm == null) {
            return null;
        }
        return pm.isIgnoringBatteryOptimizations(requireContext().getPackageName());
    }

    /**
     * 帶到系統的電池設定。
     *
     * <p><b>刻意不用 {@code ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS}</b>：
     * 那個直接跳白名單對話框的 intent 需要 {@code REQUEST_IGNORE_BATTERY_OPTIMIZATIONS}
     * 權限，而 Google Play 對它的用途審查很嚴，一個單字 App 不在允許的類別裡。
     * 帶使用者到設定頁自己調，是這個 App 該用的做法。
     */
    private void openBatterySettings() {
        Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
        if (intent.resolveActivity(requireContext().getPackageManager()) == null) {
            intent = appDetailsIntent();
        }
        startActivity(intent);
    }

    private void openAppNotificationSettings() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().getPackageName());
        } else {
            intent = appDetailsIntent();
        }
        startActivity(intent);
    }

    private Intent appDetailsIntent() {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", requireContext().getPackageName(), null));
    }

    // ---------------- 發音 ----------------

    private void bindSpeech() {
        float rate = settings.speechRate();
        binding.speechRateSlider.setValueFrom(AppSettings.MIN_SPEECH_RATE);
        binding.speechRateSlider.setValueTo(AppSettings.MAX_SPEECH_RATE);
        setSliderValue(binding.speechRateSlider, rate);
        renderSpeechRate(binding.speechRateSlider.getValue());

        binding.speechRateSlider.addOnChangeListener((slider, value, fromUser) -> {
            renderSpeechRate(value);
            // 語速即時推給引擎，按「試聽」才聽得到剛調的速度
            settings.setSpeechRate(value);
            TtsManager.get().setSpeechRate(value);
        });

        binding.btnPreviewSpeech.setOnClickListener(v -> {
            if (TtsManager.get().isReady()) {
                TtsManager.get().speak(getString(R.string.settings_speech_sample));
            } else {
                Snackbar.make(binding.getRoot(), R.string.settings_speech_not_ready,
                        Snackbar.LENGTH_SHORT).show();
            }
        });

        bindSwitch(binding.rowAutoSpeak,
                R.string.settings_auto_speak,
                R.string.settings_auto_speak_note,
                settings.autoSpeakOnFlip(),
                settings::setAutoSpeakOnFlip);
    }

    private void renderSpeechRate(float rate) {
        binding.speechRateValue.setText(String.format(Locale.US, "%.1f×", rate));
    }

    // ---------------- 資料備份 ----------------

    private void bindBackup() {
        binding.btnExport.setOnClickListener(v -> exportPicker.launch(suggestedFileName()));
        binding.btnRestore.setOnClickListener(v ->
                restorePicker.launch(new String[]{"application/json", "text/plain", "*/*"}));
        renderBackupStatus();
    }

    /** 檔名帶日期時間，同一天匯出兩次不會互相覆蓋，排序也剛好是時間序。 */
    private String suggestedFileName() {
        return "eanki-backup-" + new java.text.SimpleDateFormat(
                "yyyyMMdd-HHmm", Locale.US).format(new java.util.Date()) + ".json";
    }

    private void renderBackupStatus() {
        long at = viewModel.lastBackupAt();
        binding.backupStatus.setText(at == 0L
                ? getString(R.string.settings_backup_never)
                : getString(R.string.settings_backup_last, formatTime(at)));
        binding.backupStatus.setTextColor(requireContext().getColor(
                at == 0L ? R.color.amber_text : R.color.text_primary));
    }

    private static String formatTime(long millis) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(new java.util.Date(millis));
    }

    private void onExportTarget(@Nullable android.net.Uri uri) {
        if (uri == null) {
            return;   // 使用者取消了
        }
        binding.btnExport.setEnabled(false);
        binding.backupStatus.setText(R.string.settings_backup_exporting);
        String appVersion = appVersionName();
        viewModel.export(uri, appVersion, (result, error) -> {
            if (binding == null) {
                return;
            }
            binding.btnExport.setEnabled(true);
            renderBackupStatus();
            if (result == null) {
                Snackbar.make(binding.getRoot(),
                        getString(R.string.settings_backup_export_failed, error),
                        Snackbar.LENGTH_LONG).show();
                return;
            }
            Snackbar.make(binding.getRoot(),
                    getString(R.string.settings_backup_exported,
                            result.counts.words, formatSize(result.bytes)),
                    Snackbar.LENGTH_LONG).show();
            if (result.skippedImages > 0) {
                binding.getRoot().postDelayed(() -> {
                    if (binding != null) {
                        Snackbar.make(binding.getRoot(),
                                getString(R.string.settings_backup_skipped_images,
                                        result.skippedImages),
                                Snackbar.LENGTH_LONG).show();
                    }
                }, 3500L);
            }
        });
    }

    /** 寫進備份檔當作註記，之後看檔案時知道是哪一版產生的。 */
    @Nullable
    private String appVersionName() {
        try {
            return requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static String formatSize(int bytes) {
        return bytes < 1024 * 1024
                ? String.format(Locale.US, "%.0f KB", bytes / 1024.0)
                : String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /**
     * 還原分兩步：先解析、給使用者看清楚會被取代成什麼，確認了才寫。
     *
     * <p>這是無法復原的整批取代，不該是按一下就發生的事。
     */
    private void onRestoreSource(@Nullable android.net.Uri uri) {
        if (uri == null) {
            return;
        }
        viewModel.previewRestore(uri, preview -> {
            if (binding == null) {
                return;
            }
            if (!preview.success) {
                Snackbar.make(binding.getRoot(),
                        getString(R.string.settings_backup_bad_file, preview.error),
                        Snackbar.LENGTH_LONG).show();
                return;
            }
            confirmRestore(uri, preview);
        });
    }

    private void confirmRestore(android.net.Uri uri,
                                com.example.eanki.data.BackupRepository.RestoreResult preview) {
        Integer current = viewModel.getTotalWords().getValue();
        CharSequence body = androidx.core.text.HtmlCompat.fromHtml(
                getString(R.string.settings_restore_body,
                        formatTime(preview.exportedAt),
                        preview.counts.words, preview.counts.examples,
                        preview.counts.reviews, preview.counts.logEntries,
                        current == null ? 0 : current),
                androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY);

        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_restore_title)
                .setMessage(body)
                .setNegativeButton(R.string.settings_restore_cancel, null)
                .setPositiveButton(R.string.settings_restore_confirm, (d, which) -> doRestore(uri))
                .show();
    }

    private void doRestore(android.net.Uri uri) {
        binding.btnRestore.setEnabled(false);
        binding.backupStatus.setText(R.string.settings_restoring);
        viewModel.restore(uri, result -> {
            if (binding == null) {
                return;
            }
            binding.btnRestore.setEnabled(true);
            renderBackupStatus();
            Snackbar.make(binding.getRoot(),
                    result.success
                            ? getString(R.string.settings_restored,
                                    result.counts.words, result.counts.reviews)
                            : getString(R.string.settings_restore_failed, result.error),
                    Snackbar.LENGTH_LONG).show();
        });
    }

    // ---------------- 離線字典 ----------------

    private void bindDictionary() {
        binding.btnRebuildDict.setOnClickListener(v -> {
            binding.btnRebuildDict.setEnabled(false);
            binding.dictStatus.setText(R.string.settings_dict_rebuilding);
            viewModel.rebuildDictionary(ok -> {
                if (binding == null) {
                    return;
                }
                binding.btnRebuildDict.setEnabled(true);
                Snackbar.make(binding.getRoot(),
                        ok ? R.string.settings_dict_rebuilt : R.string.settings_dict_rebuild_failed,
                        Snackbar.LENGTH_LONG).show();
            });
        });
    }

    private void renderDictStatus(
            @Nullable com.example.eanki.data.dict.DictionaryRepository.Status status) {
        if (binding == null || status == null) {
            return;
        }
        binding.dictStatus.setText(status.ready
                ? R.string.settings_dict_ready : R.string.settings_dict_missing);
        binding.dictStatus.setTextColor(requireContext().getColor(
                status.ready ? R.color.text_primary : R.color.brick_text));
        binding.dictDetail.setVisibility(status.ready ? View.VISIBLE : View.GONE);
        if (status.ready) {
            binding.dictDetail.setText(getString(R.string.settings_dict_detail,
                    status.entryCount, status.formCount, status.exampleCount,
                    status.fileSizeBytes / (1024.0 * 1024.0)));
        }
    }

    // ---------------- 共用 ----------------

    /**
     * 設定 Slider 的值，先對齊到 stepSize 的倍數。
     *
     * <p><b>Slider.setValue 對「不是 valueFrom + n×stepSize」的數字會直接丟例外。</b>
     * 設定值可能來自備份還原或舊版本（複習上限 63、語速 0.85），
     * 照原樣塞進去會在進設定頁的瞬間當掉。夾限擋得住範圍，擋不住步進。
     */
    private static void setSliderValue(
            @NonNull com.google.android.material.slider.Slider slider, float value) {
        float from = slider.getValueFrom();
        float to = slider.getValueTo();
        float step = slider.getStepSize();
        float clamped = Math.max(from, Math.min(to, value));
        float snapped = step <= 0f
                ? clamped
                : from + Math.round((clamped - from) / step) * step;
        slider.setValue(Math.max(from, Math.min(to, snapped)));
    }

    private interface OnToggle {
        void onToggle(boolean checked);
    }

    /** 整列可點，不必準確戳到開關本身（SPEC 第 4 節：觸控目標至少 48dp）。 */
    private void bindSwitch(@NonNull ViewSettingSwitchBinding row,
                            @StringRes int title, @StringRes int note,
                            boolean initial, OnToggle onToggle) {
        row.switchTitle.setText(title);
        row.switchSubtitle.setVisibility(View.VISIBLE);
        row.switchSubtitle.setText(note);
        row.switchToggle.setChecked(initial);
        row.getRoot().setOnClickListener(v -> {
            boolean next = !row.switchToggle.isChecked();
            row.switchToggle.setChecked(next);
            onToggle.onToggle(next);
        });
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
