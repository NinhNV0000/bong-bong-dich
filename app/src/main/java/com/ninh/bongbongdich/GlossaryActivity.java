package com.ninh.bongbongdich;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.LinkedHashMap;
import java.util.Map;

public class GlossaryActivity extends AppCompatActivity {

    private EditText chineseInput;
    private EditText vietnameseInput;
    private LinearLayout glossaryList;
    private TextView emptyText;
    private TextView clearAllButton;
    private TextView countText;
    private TextView storageStatus;
    private TextView storageButton;
    private String editingOriginal;
    private boolean storagePromptShown;
    private ActivityResultLauncher<Uri> storageDirectoryPicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerStorageDirectoryPicker();
        setContentView(R.layout.activity_glossary);

        chineseInput = findViewById(R.id.input_chinese);
        vietnameseInput = findViewById(R.id.input_vietnamese);
        glossaryList = findViewById(R.id.glossary_list);
        emptyText = findViewById(R.id.empty_text);
        clearAllButton = findViewById(R.id.button_clear_all);
        countText = findViewById(R.id.glossary_count);
        storageStatus = findViewById(R.id.glossary_storage_status);
        storageButton = findViewById(R.id.button_connect_storage);

        findViewById(R.id.button_back).setOnClickListener(view -> finish());
        findViewById(R.id.button_save_term).setOnClickListener(view -> saveTerm());
        clearAllButton.setOnClickListener(view -> confirmClearAll());
        storageButton.setOnClickListener(view -> openStorageDirectoryPicker());

        renderEntries();
        updateStorageStatus();
    }

    private void registerStorageDirectoryPicker() {
        storageDirectoryPicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(),
                this::connectStorageDirectory
        );
    }

    private void openStorageDirectoryPicker() {
        storageDirectoryPicker.launch(null);
    }

    private void connectStorageDirectory(Uri uri) {
        if (uri == null) {
            return;
        }

        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
            GlossaryStore.ConnectionResult result =
                    GlossaryStore.connectExternalDirectory(this, uri);
            renderEntries();
            updateStorageStatus();

            String message = result.getImportedCount() > 0
                    ? getString(
                            R.string.storage_restored,
                            result.getImportedCount(),
                            result.getTotalCount()
                    )
                    : getString(R.string.storage_connected_success);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            updateStorageStatus();
            Toast.makeText(
                    this,
                    getString(R.string.storage_connect_failed),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void updateStorageStatus() {
        boolean connected = GlossaryStore.isExternalConnected(this);
        boolean syncOk = GlossaryStore.wasLastExternalSyncSuccessful(this);

        if (connected && syncOk) {
            storageStatus.setText(getString(
                    R.string.storage_connected_status,
                    GlossaryStore.getExternalLocationLabel()
            ));
            storageStatus.setTextColor(ContextCompat.getColor(
                    this,
                    R.color.success
            ));
            storageButton.setText(R.string.change_storage_folder);
        } else if (connected) {
            storageStatus.setText(R.string.storage_sync_failed_status);
            storageStatus.setTextColor(ContextCompat.getColor(
                    this,
                    R.color.danger
            ));
            storageButton.setText(R.string.reconnect_storage_folder);
        } else {
            storageStatus.setText(R.string.storage_not_connected_status);
            storageStatus.setTextColor(ContextCompat.getColor(
                    this,
                    R.color.text_secondary
            ));
            storageButton.setText(R.string.choose_storage_folder);
        }
    }

    private void saveTerm() {
        String chinese = chineseInput.getText().toString().trim();
        String vietnamese = vietnameseInput.getText().toString().trim();

        if (chinese.isEmpty()) {
            chineseInput.setError("Nhập từ hoặc câu tiếng Trung");
            chineseInput.requestFocus();
            return;
        }
        if (vietnamese.isEmpty()) {
            vietnameseInput.setError("Nhập cách dịch tiếng Việt");
            vietnameseInput.requestFocus();
            return;
        }

        if (editingOriginal != null && !editingOriginal.equals(chinese)) {
            GlossaryStore.remove(this, editingOriginal);
        }
        GlossaryStore.put(this, chinese, vietnamese);
        editingOriginal = null;

        View focusedView = getCurrentFocus();
        InputMethodManager keyboard =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null && focusedView != null) {
            keyboard.hideSoftInputFromWindow(
                    focusedView.getWindowToken(),
                    0
            );
        }

        chineseInput.setText("");
        vietnameseInput.setText("");
        chineseInput.clearFocus();
        vietnameseInput.clearFocus();

        renderEntries();
        updateStorageStatus();

        if (GlossaryStore.isExternalConnected(this)
                && !GlossaryStore.wasLastExternalSyncSuccessful(this)) {
            Toast.makeText(
                    this,
                    R.string.term_saved_but_backup_failed,
                    Toast.LENGTH_LONG
            ).show();
        } else {
            Toast.makeText(
                    this,
                    "Đã ghi nhớ: " + chinese + " → " + vietnamese,
                    Toast.LENGTH_SHORT
            ).show();
        }

        if (!GlossaryStore.isExternalConnected(this)
                && !storagePromptShown) {
            storagePromptShown = true;
            offerStorageConnection();
        }
    }

    private void offerStorageConnection() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.storage_prompt_title)
                .setMessage(R.string.storage_prompt_message)
                .setNegativeButton(R.string.storage_prompt_later, null)
                .setPositiveButton(
                        R.string.storage_prompt_choose,
                        (dialog, which) -> openStorageDirectoryPicker()
                )
                .show();
    }

    private void renderEntries() {
        LinkedHashMap<String, String> entries = GlossaryStore.load(this);
        glossaryList.removeAllViews();

        boolean isEmpty = entries.isEmpty();
        emptyText.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        clearAllButton.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        countText.setText(entries.size() + " thuật ngữ đã dạy");

        for (Map.Entry<String, String> entry : entries.entrySet()) {
            glossaryList.addView(createEntryCard(
                    entry.getKey(),
                    entry.getValue()
            ));
        }
    }

    private View createEntryCard(String chinese, String vietnamese) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(13), dp(10), dp(13));
        card.setBackground(roundedBackground(
                Color.WHITE,
                Color.parseColor("#DCEBFA"),
                18
        ));
        card.setElevation(dp(2));
        card.setOnClickListener(view -> beginEdit(chinese, vietnamese));

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);

        TextView chineseText = new TextView(this);
        chineseText.setText(chinese);
        chineseText.setTextColor(Color.parseColor("#102044"));
        chineseText.setTextSize(17);
        chineseText.setTypeface(
                Typeface.create("sans-serif-medium", Typeface.NORMAL)
        );

        TextView vietnameseText = new TextView(this);
        vietnameseText.setText("→  " + vietnamese);
        vietnameseText.setTextColor(Color.parseColor("#1689F5"));
        vietnameseText.setTextSize(14);
        vietnameseText.setPadding(0, dp(4), 0, 0);

        textColumn.addView(chineseText);
        textColumn.addView(vietnameseText);

        TextView deleteButton = new TextView(this);
        deleteButton.setText("Xóa");
        deleteButton.setTextColor(Color.parseColor("#EF476F"));
        deleteButton.setTextSize(13);
        deleteButton.setTypeface(Typeface.DEFAULT_BOLD);
        deleteButton.setGravity(Gravity.CENTER);
        deleteButton.setPadding(dp(12), dp(8), dp(12), dp(8));
        deleteButton.setBackground(roundedBackground(
                Color.parseColor("#FFF0F4"),
                Color.TRANSPARENT,
                14
        ));
        deleteButton.setOnClickListener(view -> {
            GlossaryStore.remove(this, chinese);
            if (chinese.equals(editingOriginal)) {
                editingOriginal = null;
                chineseInput.setText("");
                vietnameseInput.setText("");
            }
            renderEntries();
            updateStorageStatus();
        });

        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        card.addView(textColumn, textParams);
        card.addView(deleteButton);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dp(10);
        card.setLayoutParams(cardParams);
        return card;
    }

    private void beginEdit(String chinese, String vietnamese) {
        editingOriginal = chinese;
        chineseInput.setText(chinese);
        vietnameseInput.setText(vietnamese);
        vietnameseInput.requestFocus();
        vietnameseInput.setSelection(vietnameseInput.length());
        Toast.makeText(this, "Sửa xong rồi bấm Ghi nhớ", Toast.LENGTH_SHORT).show();
    }

    private void confirmClearAll() {
        new AlertDialog.Builder(this)
                .setTitle("Xóa toàn bộ từ đã dạy?")
                .setMessage("Các thuật ngữ mặc định của ứng dụng không bị ảnh hưởng.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Xóa hết", (dialog, which) -> {
                    GlossaryStore.clear(this);
                    editingOriginal = null;
                    chineseInput.setText("");
                    vietnameseInput.setText("");
                    renderEntries();
                    updateStorageStatus();
                })
                .show();
    }

    private GradientDrawable roundedBackground(
            int fillColor,
            int strokeColor,
            int radiusDp
    ) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(fillColor);
        background.setCornerRadius(dp(radiusDp));
        if (Color.alpha(strokeColor) > 0) {
            background.setStroke(dp(1), strokeColor);
        }
        return background;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
