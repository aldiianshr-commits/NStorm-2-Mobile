package com.nstorm2.mobile;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.nstorm2.engine.scene.SceneModel;
import com.nstorm2.engine.xfbin.NuccChunks;
import com.nstorm2.engine.xfbin.Xfbin;
import com.nstorm2.mobile.ui.RowAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shows the pages/chunks of one XFBIN and opens its models and textures. */
public class XfbinActivity extends AppCompatActivity {
    public static final String EXTRA_KEY = "asset_key";

    private NStormApp app;
    private String key;
    private RowAdapter adapter;
    private TextView info;

    private static final class Action {
        final Xfbin.Chunk clump;   // model action (null = all loose models)
        final Xfbin.Chunk chunk;   // chunk row

        Action(Xfbin.Chunk clump, Xfbin.Chunk chunk) {
            this.clump = clump;
            this.chunk = chunk;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        app = NStormApp.of(this);
        key = getIntent().getStringExtra(EXTRA_KEY);
        MaterialToolbar tb = findViewById(R.id.toolbar);
        String title = key == null ? "XFBIN" : key.substring(key.lastIndexOf('/') + 1);
        tb.setTitle(title);
        tb.setNavigationOnClickListener(v -> finish());
        findViewById(R.id.searchLayout).setVisibility(View.GONE);
        info = findViewById(R.id.info);
        info.setText("Memuat…");
        findViewById(R.id.progress).setVisibility(View.VISIBLE);

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RowAdapter(row -> onRow((Action) row.tag));
        list.setAdapter(adapter);

        app.io().execute(() -> {
            try {
                Xfbin x = XfbinCache.load(app, key);
                app.post(() -> show(x));
            } catch (Throwable t) {
                app.post(() -> {
                    findViewById(R.id.progress).setVisibility(View.GONE);
                    info.setText("Gagal membaca: " + t.getMessage());
                });
            }
        });
    }

    private void show(Xfbin x) {
        findViewById(R.id.progress).setVisibility(View.GONE);
        List<RowAdapter.Row> rows = new ArrayList<>();
        List<Xfbin.Chunk> clumps = SceneModel.clumps(x);
        for (Xfbin.Chunk c : clumps) {
            rows.add(new RowAdapter.Row("3D", "Lihat model: " + c.name, "Kerangka + semua mesh & tekstur clump ini", new Action(c, null)));
        }
        if (clumps.isEmpty() && !x.chunksOfType(NuccChunks.MODEL).isEmpty()) {
            rows.add(new RowAdapter.Row("3D", "Lihat semua mesh", "File tanpa clump — mesh ditampilkan tanpa kerangka", new Action(null, null)));
        }
        int textures = 0;
        for (Xfbin.Chunk c : x.chunks()) {
            if (c.type.equals("nuccChunkNull") || c.type.equals("nuccChunkPage")) continue;
            if (c.type.equals(NuccChunks.TEXTURE)) textures++;
            rows.add(new RowAdapter.Row(badge(c.type), c.name.isEmpty() ? "(tanpa nama)" : c.name,
                    c.type + " · " + Ui.size(c.size()) + (c.path.isEmpty() ? "" : " · " + c.path), new Action(null, c)));
        }
        adapter.setRows(rows);
        info.setText(String.format(Locale.ROOT, "XFBIN v0x%X · %d halaman · %d chunk · %d clump · %d tekstur",
                x.version, x.pages().size(), x.chunks().size(), clumps.size(), textures));
    }

    static String badge(String type) {
        String t = type.startsWith("nuccChunk") ? type.substring(9) : type;
        switch (t) {
            case "Texture": return "TEX";
            case "Material": return "MAT";
            case "Model": return "MESH";
            case "Clump": return "CLUMP";
            case "Coord": return "BONE";
            case "Anm": return "ANIM";
            case "Binary": return "BIN";
            default: return t.length() > 6 ? t.substring(0, 6).toUpperCase(Locale.ROOT) : t.toUpperCase(Locale.ROOT);
        }
    }

    private void onRow(Action a) {
        if (a.chunk == null) {
            Intent i = new Intent(this, ModelViewerActivity.class);
            i.putExtra(ModelViewerActivity.EXTRA_KEY, key);
            if (a.clump != null) i.putExtra(ModelViewerActivity.EXTRA_CLUMP, a.clump.name);
            startActivity(i);
            return;
        }
        Xfbin.Chunk c = a.chunk;
        if (NuccChunks.TEXTURE.equals(c.type)) {
            Intent i = new Intent(this, TextureActivity.class);
            i.putExtra(TextureActivity.EXTRA_KEY, key);
            i.putExtra(TextureActivity.EXTRA_MAP, c.mapIndex);
            startActivity(i);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(c.name.isEmpty() ? c.type : c.name)
                .setMessage("Tipe: " + c.type + "\nPath: " + c.path + "\nUkuran: " + Ui.size(c.size())
                        + "\nHalaman: " + c.page
                        + "\n\nTipe chunk ini dibaca tetapi belum divisualisasikan oleh engine (alpha).")
                .setPositiveButton("OK", null)
                .show();
    }
}
