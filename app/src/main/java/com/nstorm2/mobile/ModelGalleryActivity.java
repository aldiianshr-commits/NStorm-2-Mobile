package com.nstorm2.mobile;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.nstorm2.engine.scene.SceneModel;
import com.nstorm2.engine.vfs.GameLibrary;
import com.nstorm2.engine.xfbin.NuccChunks;
import com.nstorm2.engine.xfbin.Xfbin;
import com.nstorm2.mobile.ui.RowAdapter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds every XFBIN that contains a clump (characters, stages, props) and lists them.
 * The index is cached in /storage/emulated/0/nstorm2/saves/model_index.tsv.
 */
public class ModelGalleryActivity extends AppCompatActivity {
    private static final String INDEX = "model_index.tsv";

    private static final class Entry {
        final String key, clump, path;

        Entry(String key, String clump, String path) {
            this.key = key;
            this.clump = clump;
            this.path = path;
        }
    }

    private NStormApp app;
    private RowAdapter adapter;
    private TextView info;
    private LinearProgressIndicator progress;
    private final List<Entry> entries = new ArrayList<>();
    private String query = "";
    private volatile boolean cancelled;
    private boolean scanning;

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
        tb.setTitle("Galeri Model 3D");
        tb.setNavigationOnClickListener(v -> finish());
        info = findViewById(R.id.info);
        progress = findViewById(R.id.progress);
        ((com.google.android.material.textfield.TextInputLayout) findViewById(R.id.searchLayout)).setHint("Cari karakter / stage…");

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RowAdapter(row -> {
            Entry e = (Entry) row.tag;
            Intent i = new Intent(this, ModelViewerActivity.class);
            i.putExtra(ModelViewerActivity.EXTRA_KEY, e.key);
            i.putExtra(ModelViewerActivity.EXTRA_CLUMP, e.clump);
            startActivity(i);
        });
        list.setAdapter(adapter);

        LinearLayout filters = findViewById(R.id.filters);
        MaterialButton rescan = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        rescan.setText("Pindai ulang");
        rescan.setAllCaps(false);
        rescan.setOnClickListener(v -> startIndexing());
        filters.addView(rescan);

        EditText search = findViewById(R.id.search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                apply();
            }
        });

        if (!loadIndex()) startIndexing();
        else apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelled = true;
    }

    private File indexFile() {
        return new File(app.savesDir(), INDEX);
    }

    private boolean loadIndex() {
        File f = indexFile();
        if (!f.isFile()) return false;
        String root = app.library().root().getAbsolutePath();
        entries.clear();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String first = r.readLine();
            if (first == null || !first.equals("#root\t" + root)) return false;
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length >= 3) entries.add(new Entry(p[0], p[1], p[2]));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void saveIndex(List<Entry> list) {
        try (PrintWriter w = new PrintWriter(new FileWriter(indexFile()))) {
            w.println("#root\t" + app.library().root().getAbsolutePath());
            for (Entry e : list) w.println(e.key + "\t" + e.clump + "\t" + e.path);
        } catch (Exception ignored) {}
    }

    private void startIndexing() {
        if (scanning) return;
        scanning = true;
        cancelled = false;
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(false);
        GameLibrary lib = app.library();
        List<GameLibrary.Asset> xfbins = new ArrayList<>();
        for (GameLibrary.Asset a : lib.assets()) if (a.isXfbin()) xfbins.add(a);
        progress.setMax(Math.max(1, xfbins.size()));
        app.io().execute(() -> {
            List<Entry> found = new ArrayList<>();
            int done = 0, failed = 0;
            for (GameLibrary.Asset a : xfbins) {
                if (cancelled) break;
                try {
                    byte[] data = lib.read(a);
                    if (Xfbin.declaresType(data, NuccChunks.CLUMP)) {
                        Xfbin x = Xfbin.parse(data);
                        for (Xfbin.Chunk c : SceneModel.clumps(x)) found.add(new Entry(a.key(lib.root()), c.name, a.displayPath));
                    }
                } catch (Throwable t) {
                    failed++;
                }
                done++;
                if (done % 10 == 0 || done == xfbins.size()) {
                    final int d = done, n = found.size(), f = failed;
                    app.post(() -> {
                        progress.setProgress(d);
                        info.setText(String.format(Locale.ROOT, "Memindai %d/%d XFBIN · %d model ditemukan%s", d, xfbins.size(), n,
                                f > 0 ? " · " + f + " gagal" : ""));
                    });
                }
            }
            if (!cancelled) saveIndex(found);
            final int f = failed;
            app.post(() -> {
                scanning = false;
                progress.setVisibility(View.GONE);
                entries.clear();
                entries.addAll(found);
                apply();
                if (f > 0) info.append(" · " + f + " file tidak terbaca");
            });
        });
    }

    private void apply() {
        List<RowAdapter.Row> rows = new ArrayList<>();
        for (Entry e : entries) {
            if (!query.isEmpty() && !e.clump.toLowerCase(Locale.ROOT).contains(query) && !e.path.toLowerCase(Locale.ROOT).contains(query)) continue;
            rows.add(new RowAdapter.Row("3D", e.clump, e.path, e));
        }
        adapter.setRows(rows);
        if (!scanning) {
            info.setText(entries.isEmpty()
                    ? "Belum ada model ditemukan. Pastikan folder data game lengkap, lalu Pindai ulang."
                    : rows.size() + " model · indeks disimpan di /nstorm2/saves/" + INDEX);
        }
    }
}
