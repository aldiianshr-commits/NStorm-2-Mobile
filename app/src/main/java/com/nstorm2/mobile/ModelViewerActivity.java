package com.nstorm2.mobile;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;
import com.nstorm2.engine.scene.SceneModel;
import com.nstorm2.engine.texture.NutTexture;
import com.nstorm2.engine.xfbin.NuccChunks;
import com.nstorm2.engine.xfbin.Xfbin;
import com.nstorm2.mobile.viewer.ModelRenderer;
import com.nstorm2.mobile.viewer.MsaaConfigChooser;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Native 3D viewer for Storm 2 models (clump = skeleton + meshes + materials + textures).
 * Touch: 1 finger orbit, pinch zoom, 2 fingers pan, double tap reset.
 * Gamepad: left stick orbit, right stick pan, triggers/L1/R1 zoom, A wireframe, X lighting, Y reset, B back.
 */
public class ModelViewerActivity extends AppCompatActivity {
    public static final String EXTRA_KEY = "asset_key";
    public static final String EXTRA_CLUMP = "clump";

    private NStormApp app;
    private GLSurfaceView glView;
    private final ModelRenderer renderer = new ModelRenderer();
    private TextView stats, loadingText;
    private View loading;
    private boolean showStats;
    private float touchSensitivity = 1f;
    private float deadzone = 0.15f;
    private volatile String header = "";

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private float lastX, lastY, lastFocusX, lastFocusY;
    private int lastPointers;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowInsetsControllerCompat wic = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        wic.hide(WindowInsetsCompat.Type.systemBars());
        wic.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        app = NStormApp.of(this);
        SharedPreferences p = app.prefs();
        showStats = p.getBoolean("show_stats", true);
        touchSensitivity = p.getInt("touch_sensitivity", 100) / 100f;
        deadzone = p.getInt("pad_deadzone", 15) / 100f;
        renderer.padSensitivity = p.getInt("pad_sensitivity", 100) / 100f;
        renderer.invertY = p.getBoolean("invert_y", false);
        renderer.fpsCap = parseInt(p.getString("fps_cap", "60"), 60);
        renderer.lighting = p.getBoolean("lighting", true);
        renderer.textureFilter = p.getString("texture_filter", "trilinear");
        int msaa = parseInt(p.getString("msaa", "4"), 4);
        float scale = parseInt(p.getString("render_scale", "100"), 100) / 100f;

        stats = findViewById(R.id.stats);
        stats.setVisibility(showStats ? View.VISIBLE : View.GONE);
        loading = findViewById(R.id.loading);
        loadingText = findViewById(R.id.loadingText);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(3);
        glView.setEGLConfigChooser(new MsaaConfigChooser(msaa));
        glView.setPreserveEGLContextOnPause(true);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        FrameLayout holder = findViewById(R.id.glHolder);
        holder.addView(glView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        if (scale < 0.99f) {
            glView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                int w = Math.max(1, Math.round((r - l) * scale)), h = Math.max(1, Math.round((b - t) * scale));
                glView.getHolder().setFixedSize(w, h);
            });
        }

        renderer.setStatsListener((fps, tris, calls) -> {
            if (!showStats) return;
            String text = String.format(Locale.ROOT, "%s\n%.0f FPS · %,d segitiga · %d draw call", header, fps, tris, calls);
            runOnUiThread(() -> stats.setText(text));
        });

        setupTouch();
        setupHud();
        load(getIntent().getStringExtra(EXTRA_KEY), getIntent().getStringExtra(EXTRA_CLUMP),
                parseInt(p.getString("decode_threads", "4"), 4), parseInt(p.getString("max_texture", "2048"), 2048));
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    // ------------------------------------------------------------------ loading

    private void load(String key, @Nullable String clumpName, int threads, int maxTexture) {
        app.io().execute(() -> {
            try {
                progress("Membaca arsip…");
                Xfbin x = XfbinCache.load(app, key);
                Xfbin.Chunk clump = null;
                List<Xfbin.Chunk> clumps = SceneModel.clumps(x);
                for (Xfbin.Chunk c : clumps) if (c.name.equals(clumpName)) clump = c;
                if (clump == null && !clumps.isEmpty()) clump = clumps.get(0);
                progress("Menyusun kerangka & mesh…");
                SceneModel model = SceneModel.build(x, clump);
                if (model.parts.isEmpty()) {
                    throw new IllegalStateException("Tidak ada mesh yang bisa ditampilkan."
                            + (model.warnings.isEmpty() ? "" : "\n" + String.join("\n", model.warnings)));
                }
                Set<Xfbin.Chunk> needed = new LinkedHashSet<>();
                for (SceneModel.Part part : model.parts) if (part.texture != null) needed.add(part.texture);
                progress("Mendekode " + needed.size() + " tekstur…");
                Map<Xfbin.Chunk, Bitmap> bitmaps = decodeTextures(needed, threads, maxTexture);
                header = model.name + " · " + model.parts.size() + " bagian · " + model.bones.size() + " tulang · "
                        + bitmaps.size() + "/" + needed.size() + " tekstur";
                renderer.setScene(new ModelRenderer.SceneData(model, bitmaps));
                List<String> warnings = new ArrayList<>(model.warnings);
                if (bitmaps.size() < needed.size()) warnings.add((needed.size() - bitmaps.size()) + " tekstur tidak dapat didekode");
                app.post(() -> {
                    loading.setVisibility(View.GONE);
                    if (!warnings.isEmpty()) {
                        Ui.alert(this, "Catatan", String.join("\n", warnings.subList(0, Math.min(12, warnings.size()))));
                    }
                });
            } catch (Throwable t) {
                app.post(() -> {
                    findViewById(R.id.spinner).setVisibility(View.GONE);
                    loadingText.setText("Gagal memuat model:\n" + t.getMessage());
                });
            }
        });
    }

    private void progress(String s) {
        app.post(() -> loadingText.setText(s));
    }

    private Map<Xfbin.Chunk, Bitmap> decodeTextures(Set<Xfbin.Chunk> chunks, int threads, int maxTexture) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads));
        Map<Xfbin.Chunk, Future<Bitmap>> futures = new IdentityHashMap<>();
        for (Xfbin.Chunk c : chunks) {
            futures.put(c, pool.submit(() -> {
                List<NutTexture> nut = NutTexture.parse(NuccChunks.textureNut(c));
                if (nut.isEmpty() || !nut.get(0).isSupported()) return null;
                NutTexture t = nut.get(0);
                Bitmap b = Bitmap.createBitmap(t.decodeArgb(), t.width, t.height, Bitmap.Config.ARGB_8888);
                int longest = Math.max(t.width, t.height);
                if (longest > maxTexture) {
                    float f = maxTexture / (float) longest;
                    Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(t.width * f)), Math.max(1, Math.round(t.height * f)), true);
                    b.recycle();
                    b = s;
                }
                return b;
            }));
        }
        Map<Xfbin.Chunk, Bitmap> out = new IdentityHashMap<>();
        for (Map.Entry<Xfbin.Chunk, Future<Bitmap>> e : futures.entrySet()) {
            try {
                Bitmap b = e.getValue().get();
                if (b != null) out.put(e.getKey(), b);
            } catch (java.util.concurrent.ExecutionException ignored) {
                // unsupported / corrupt texture: the part renders untextured
            }
        }
        pool.shutdown();
        return out;
    }

    // ------------------------------------------------------------------ HUD

    private void setupHud() {
        MaterialButton wire = findViewById(R.id.btnWire), light = findViewById(R.id.btnLight),
                alpha = findViewById(R.id.btnAlpha), axis = findViewById(R.id.btnAxis);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        wire.setOnClickListener(v -> toggleWire(wire));
        light.setOnClickListener(v -> toggleLight(light));
        alpha.setOnClickListener(v -> {
            renderer.alphaTest = !renderer.alphaTest;
            alpha.setAlpha(renderer.alphaTest ? 1f : 0.55f);
        });
        axis.setOnClickListener(v -> {
            renderer.zUp = !renderer.zUp;
            axis.setText(renderer.zUp ? "Sumbu: Z" : "Sumbu: Y");
            renderer.resetCamera();
        });
        findViewById(R.id.btnParts).setOnClickListener(v -> showParts());
        findViewById(R.id.btnReset).setOnClickListener(v -> renderer.resetCamera());
        wire.setAlpha(0.55f);
        light.setAlpha(renderer.lighting ? 1f : 0.55f);
    }

    private void toggleWire(MaterialButton b) {
        renderer.wireframe = !renderer.wireframe;
        b.setAlpha(renderer.wireframe ? 1f : 0.55f);
    }

    private void toggleLight(MaterialButton b) {
        renderer.lighting = !renderer.lighting;
        b.setAlpha(renderer.lighting ? 1f : 0.55f);
    }

    private void showParts() {
        ModelRenderer.SceneData s = renderer.scene();
        boolean[] vis = renderer.visible;
        if (s == null || vis == null) return;
        String[] names = new String[s.model.parts.size()];
        for (int i = 0; i < names.length; i++) {
            SceneModel.Part p = s.model.parts.get(i);
            names[i] = p.name + " (" + p.triangleCount() + " seg)";
        }
        boolean[] copy = vis.clone();
        new AlertDialog.Builder(this)
                .setTitle("Bagian model")
                .setMultiChoiceItems(names, copy, (d, which, checked) -> copy[which] = checked)
                .setPositiveButton("Terapkan", (d, w) -> renderer.visible = copy)
                .setNeutralButton("Semua", (d, w) -> {
                    boolean[] all = new boolean[copy.length];
                    java.util.Arrays.fill(all, true);
                    renderer.visible = all;
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    // ------------------------------------------------------------------ touch

    private void setupTouch() {
        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                renderer.zoom(1f / d.getScaleFactor());
                return true;
            }
        });
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                renderer.resetCamera();
                return true;
            }
        });
        glView.setOnTouchListener((v, e) -> {
            scaleDetector.onTouchEvent(e);
            gestureDetector.onTouchEvent(e);
            int n = e.getPointerCount();
            float fx = 0, fy = 0;
            for (int i = 0; i < n; i++) {
                fx += e.getX(i);
                fy += e.getY(i);
            }
            fx /= n;
            fy /= n;
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
                    || action == MotionEvent.ACTION_POINTER_UP || n != lastPointers) {
                lastX = e.getX();
                lastY = e.getY();
                lastFocusX = fx;
                lastFocusY = fy;
                lastPointers = n;
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                float w = Math.max(1, v.getWidth()), h = Math.max(1, v.getHeight());
                if (n == 1) {
                    float dx = e.getX() - lastX, dy = e.getY() - lastY;
                    float invert = app.prefs().getBoolean("invert_y", false) ? -1 : 1;
                    renderer.rotate(-dx / w * 220f * touchSensitivity, dy / h * 160f * touchSensitivity * invert);
                    lastX = e.getX();
                    lastY = e.getY();
                } else if (!scaleDetector.isInProgress() || n >= 2) {
                    renderer.pan((fx - lastFocusX) / w, (fy - lastFocusY) / h);
                    lastFocusX = fx;
                    lastFocusY = fy;
                }
            }
            return true;
        });
    }

    // ------------------------------------------------------------------ gamepad

    private float axis(MotionEvent e, int a) {
        InputDevice d = e.getDevice();
        if (d == null) return 0;
        InputDevice.MotionRange r = d.getMotionRange(a, e.getSource());
        if (r == null) return 0;
        float v = e.getAxisValue(a);
        float dz = Math.max(deadzone, r.getFlat());
        return Math.abs(v) > dz ? v : 0;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && e.getAction() == MotionEvent.ACTION_MOVE) {
            renderer.axisRotX = axis(e, MotionEvent.AXIS_X);
            renderer.axisRotY = axis(e, MotionEvent.AXIS_Y);
            renderer.axisPanX = axis(e, MotionEvent.AXIS_Z);
            renderer.axisPanY = axis(e, MotionEvent.AXIS_RZ);
            float lt = Math.max(axis(e, MotionEvent.AXIS_LTRIGGER), axis(e, MotionEvent.AXIS_BRAKE));
            float rt = Math.max(axis(e, MotionEvent.AXIS_RTRIGGER), axis(e, MotionEvent.AXIS_GAS));
            renderer.axisZoom = lt - rt; // RT zooms in
            float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (hx != 0 || hy != 0) renderer.rotate(-hx * 15f, -hy * 10f);
            return true;
        }
        return super.onGenericMotionEvent(e);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                toggleWire(findViewById(R.id.btnWire));
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                toggleLight(findViewById(R.id.btnLight));
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                renderer.resetCamera();
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
                finish();
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
                renderer.zoom(1.15f);
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
                renderer.zoom(1f / 1.15f);
                return true;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                showParts();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                renderer.rotate(15f, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                renderer.rotate(-15f, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                renderer.rotate(0, 10f);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                renderer.rotate(0, -10f);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        glView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
    }
}
