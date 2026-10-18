package com.nstorm2.mobile;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.nstorm2.engine.vfs.GameLibrary;
import com.nstorm2.mobile.ui.RowAdapter;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Lists every file inside the game's CPK archives (and loose XFBINs) with search and type filters. */
public class AssetBrowserActivity extends AppCompatActivity {
    private static final int MAX_ROWS = 4000;

    private NStormApp app;
    private RowAdapter adapter;
    private TextView info;
    private String query = "";
    private String filter = "xfbin";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        app = NStormApp.of(this);
        if (app.library() == null) {
            finish();
            return;
        }
        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setTitle("Jelajahi Aset");
        tb.setNavigationOnClickListener(v -> finish());
        info = findViewById(R.id.info);

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RowAdapter(row -> open((GameLibrary.Asset) row.tag));
        list.setAdapter(adapter);

        LinearLayout filters = findViewById(R.id.filters);
        String[][] options = {{"xfbin", "XFBIN"}, {"all", "Semua"}, {"other", "Lainnya"}};
        List<MaterialButton> buttons = new ArrayList<>();
        for (String[] o : options) {
            MaterialButton b = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setText(o[1]);
            b.setAllCaps(false);
            b.setOnClickListener(v -> {
                filter = o[0];
                for (MaterialButton other : buttons) other.setAlpha(other == b ? 1f : 0.55f);
                apply();
            });
            b.setAlpha(o[0].equals(filter) ? 1f : 0.55f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(Ui.dp(this, 8));
            filters.addView(b, lp);
            buttons.add(b);
        }

        EditText search = findViewById(R.id.search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                apply();
            }
        });
        apply();
    }

    private void apply() {
        GameLibrary lib = app.library();
        if (lib == null) return;
        List<RowAdapter.Row> rows = new ArrayList<>();
        int matched = 0;
        for (GameLibrary.Asset a : lib.assets()) {
            boolean x = a.isXfbin();
            if (filter.equals("xfbin") && !x) continue;
            if (filter.equals("other") && x) continue;
            if (!query.isEmpty() && !a.displayPath.toLowerCase(Locale.ROOT).contains(query)) continue;
            matched++;
            if (rows.size() < MAX_ROWS) {
                String ext = a.extension().isEmpty() ? "FILE" : a.extension().toUpperCase(Locale.ROOT);
                rows.add(new RowAdapter.Row(ext.length() > 6 ? ext.substring(0, 6) : ext, a.name(),
                        parent(a.displayPath) + " · " + Ui.size(a.size), a));
            }
        }
        adapter.setRows(rows);
        info.setText(matched > MAX_ROWS
                ? matched + " file cocok — menampilkan " + MAX_ROWS + " pertama, persempit pencarian"
                : matched + " file");
    }

    private static String parent(String path) {
        int s = path.lastIndexOf('/');
        return s < 0 ? "" : path.substring(0, s);
    }

    private void open(GameLibrary.Asset a) {
        if (a.isXfbin()) {
            Intent i = new Intent(this, XfbinActivity.class);
            i.putExtra(XfbinActivity.EXTRA_KEY, a.key(app.library().root()));
            startActivity(i);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(a.name())
                .setMessage(a.displayPath + "\n" + Ui.size(a.size)
                        + "\n\nFormat ini belum ditampilkan oleh engine. Anda bisa mengekspor file mentahnya (sudah didekompresi).")
                .setPositiveButton("Ekspor", (d, w) -> exportRaw(a))
                .setNegativeButton("Tutup", null)
                .show();
    }

    private void exportRaw(GameLibrary.Asset a) {
        app.io().execute(() -> {
            String msg;
            try {
                byte[] data = app.library().read(a);
                File out = new File(app.exportDir(), a.name());
                try (FileOutputStream fo = new FileOutputStream(out)) {
                    fo.write(data);
                }
                msg = "Disimpan ke " + out.getAbsolutePath();
            } catch (Exception e) {
                msg = "Gagal: " + e.getMessage();
            }
            final String m = msg;
            app.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        });
    }
}
