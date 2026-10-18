package com.nstorm2.mobile;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.nstorm2.engine.texture.NutTexture;
import com.nstorm2.engine.xfbin.NuccChunks;
import com.nstorm2.engine.xfbin.Xfbin;
import com.nstorm2.mobile.ui.ZoomImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Locale;

/** Decodes one nuccChunkTexture (NTP3) on the CPU and shows / exports it. */
public class TextureActivity extends AppCompatActivity {
    public static final String EXTRA_KEY = "asset_key";
    public static final String EXTRA_MAP = "map_index";

    private NStormApp app;
    private Bitmap withAlpha, opaque;
    private boolean showAlpha = true;
    private String chunkName = "texture";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_texture);
        app = NStormApp.of(this);
        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());
        tb.setTitle("Tekstur");
        TextView info = findViewById(R.id.info);
        ZoomImageView image = findViewById(R.id.image);
        MaterialButton alphaBtn = findViewById(R.id.btnAlpha);
        alphaBtn.setOnClickListener(v -> {
            showAlpha = !showAlpha;
            alphaBtn.setText(showAlpha ? "Alpha: tampil" : "Alpha: abaikan");
            image.setBitmap(showAlpha ? withAlpha : opaque);
        });
        findViewById(R.id.btnExport).setOnClickListener(v -> export());

        String key = getIntent().getStringExtra(EXTRA_KEY);
        int map = getIntent().getIntExtra(EXTRA_MAP, -1);
        info.setText("Mendekode…");
        app.io().execute(() -> {
            try {
                Xfbin x = XfbinCache.load(app, key);
                Xfbin.Chunk chunk = null;
                for (Xfbin.Chunk c : x.chunksOfType(NuccChunks.TEXTURE)) if (c.mapIndex == map) chunk = c;
                if (chunk == null) throw new IllegalStateException("chunk tekstur tidak ditemukan");
                List<NutTexture> nut = NutTexture.parse(NuccChunks.textureNut(chunk));
                if (nut.isEmpty()) throw new IllegalStateException("NUT kosong");
                NutTexture t = nut.get(0);
                long start = System.nanoTime();
                int[] argb = t.decodeArgb();
                long ms = (System.nanoTime() - start) / 1_000_000;
                Bitmap b = Bitmap.createBitmap(argb, t.width, t.height, Bitmap.Config.ARGB_8888);
                int[] solid = argb.clone();
                for (int i = 0; i < solid.length; i++) solid[i] |= 0xFF000000;
                Bitmap o = Bitmap.createBitmap(solid, t.width, t.height, Bitmap.Config.ARGB_8888);
                final String name = chunk.name;
                final String text = String.format(Locale.ROOT, "%s · %d×%d · %s · %d mip%s · dekode %d ms (CPU)%s",
                        chunk.name, t.width, t.height, t.formatName(), t.mipCount, t.cubemap ? " · cubemap (sisi 1)" : "",
                        ms, nut.size() > 1 ? " · " + nut.size() + " tekstur dalam NUT" : "");
                app.post(() -> {
                    chunkName = name;
                    tb.setTitle(name);
                    withAlpha = b;
                    opaque = o;
                    image.setBitmap(b);
                    info.setText(text);
                });
            } catch (Throwable e) {
                app.post(() -> info.setText("Gagal: " + e.getMessage()));
            }
        });
    }

    private void export() {
        Bitmap b = showAlpha ? withAlpha : opaque;
        if (b == null) return;
        app.io().execute(() -> {
            String msg;
            File out = new File(app.exportDir(), chunkName.replaceAll("[^A-Za-z0-9_.-]", "_") + ".png");
            try (FileOutputStream fo = new FileOutputStream(out)) {
                b.compress(Bitmap.CompressFormat.PNG, 100, fo);
                msg = "Disimpan: " + out.getAbsolutePath();
            } catch (Exception e) {
                msg = "Gagal menyimpan: " + e.getMessage();
            }
            final String m = msg;
            app.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        });
    }
}
