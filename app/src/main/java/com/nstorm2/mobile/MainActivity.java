package com.nstorm2.mobile;

import android.Manifest;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ConfigurationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.nstorm2.engine.vfs.GameLibrary;

import java.io.File;
import java.util.Locale;

/** Dashboard: permissions, game folder, data scan and entry points into the engine tools. */
public class MainActivity extends AppCompatActivity {
    private static final int REQ_STORAGE = 7;

    private NStormApp app;
    private LinearLayout statusList;
    private TextView scanHint;
    private boolean scanning;
    private String scanProgress = "";

    private final ActivityResultLauncher<Uri> folderPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), this::onFolderPicked);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        app = NStormApp.of(this);
        statusList = findViewById(R.id.statusList);
        scanHint = findViewById(R.id.scanHint);

        if (getResources().getConfiguration().screenWidthDp > 760) {
            View content = findViewById(R.id.content);
            ViewGroup.LayoutParams lp = content.getLayoutParams();
            lp.width = Ui.dp(this, 720);
            content.setLayoutParams(lp);
        }

        findViewById(R.id.btnScan).setOnClickListener(v -> startScan());
        findViewById(R.id.btnGallery).setOnClickListener(v -> openIfScanned(ModelGalleryActivity.class));
        findViewById(R.id.btnBrowse).setOnClickListener(v -> openIfScanned(AssetBrowserActivity.class));
        findViewById(R.id.btnFolder).setOnClickListener(v -> showFolderDialog());
        findViewById(R.id.btnSettings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnHelp).setOnClickListener(v -> showHelp());

        String version;
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            version = "?";
        }
        ((TextView) findViewById(R.id.footer)).setText("NStorm 2 Mobile " + version
                + " · native (Java + OpenGL ES 3), tanpa emulator\nData game tidak disertakan — gunakan file PC milik Anda sendiri.");

        if (hasStorage() && app.library() == null && app.gameDir().isDirectory()) startScan();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ---------------------------------------------------------------- permissions

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return Environment.isExternalStorageManager();
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            new AlertDialog.Builder(this)
                    .setTitle("Izin akses file")
                    .setMessage("NStorm 2 membaca data game di " + app.gameDir().getAbsolutePath()
                            + " dan menulis data engine ke " + new File(NStormApp.defaultGameDir(), "saves").getAbsolutePath()
                            + ".\n\nAktifkan \"Izinkan akses untuk mengelola semua file\" di layar berikutnya.")
                    .setPositiveButton("Buka pengaturan", (d, w) -> {
                        try {
                            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            i.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (ActivityNotFoundException e) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    })
                    .setNegativeButton("Batal", null)
                    .show();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    // ---------------------------------------------------------------- scan

    private void startScan() {
        if (scanning) return;
        if (!hasStorage()) {
            requestStorage();
            return;
        }
        File dir = app.gameDir();
        if (!dir.isDirectory()) {
            dir.mkdirs();
            Ui.alert(this, "Folder game kosong", "Salin isi folder instalasi PC Naruto Shippuden: Ultimate Ninja Storm 2 ke:\n\n"
                    + dir.getAbsolutePath() + "\n\nlalu ketuk Pindai Data Game lagi.");
            refresh();
            return;
        }
        app.savesDir();
        scanning = true;
        scanProgress = "Memulai…";
        refresh();
        app.io().execute(() -> {
            String error = null;
            try {
                app.scanLibrary(msg -> app.post(() -> {
                    scanProgress = msg;
                    refresh();
                }));
            } catch (Throwable t) {
                error = t.toString();
            }
            final String err = error;
            app.post(() -> {
                scanning = false;
                refresh();
                if (err != null) Ui.alert(this, "Pemindaian gagal", err);
                else if (app.library() != null && app.library().assets().isEmpty()) {
                    Ui.alert(this, "Data tidak ditemukan", "Tidak ada arsip CPK atau file XFBIN di " + app.gameDir().getAbsolutePath()
                            + ".\nPastikan seluruh folder instalasi PC (termasuk folder data) sudah disalin.");
                }
            });
        });
    }

    private void openIfScanned(Class<?> activity) {
        if (app.library() == null) {
            if (!scanning) startScan();
            Toast.makeText(this, "Pindai data game terlebih dahulu.", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, activity));
    }

    // ---------------------------------------------------------------- status

    private void refresh() {
        statusList.removeAllViews();
        boolean perm = hasStorage();
        addRow("Izin penyimpanan", perm ? "Diberikan" : "Ketuk untuk memberi izin akses semua file",
                perm ? R.color.ns_ok : R.color.ns_bad, perm ? null : v -> requestStorage());

        File dir = app.gameDir();
        boolean dirOk = perm && dir.isDirectory();
        addRow("Folder game", dir.getAbsolutePath() + (dirOk ? "" : "\nBelum ada — salin file game PC ke sini"),
                dirOk ? R.color.ns_ok : R.color.ns_bad, v -> showFolderDialog());

        GameLibrary lib = app.library();
        String data;
        int color;
        if (scanning) {
            data = "Memindai: " + scanProgress;
            color = R.color.ns_warn;
        } else if (lib == null) {
            data = "Belum dipindai";
            color = R.color.ns_warn;
        } else {
            int xf = 0;
            for (GameLibrary.Asset a : lib.assets()) if (a.isXfbin()) xf++;
            int bad = 0;
            for (GameLibrary.Archive a : lib.archives()) if (a.error != null) bad++;
            data = String.format(Locale.ROOT, "%d arsip CPK · %d file · %d XFBIN%s", lib.archives().size(),
                    lib.assets().size(), xf, bad > 0 ? " · " + bad + " arsip gagal dibaca" : "");
            color = lib.assets().isEmpty() ? R.color.ns_bad : (bad > 0 ? R.color.ns_warn : R.color.ns_ok);
        }
        addRow("Data game", data, color, lib != null ? v -> showArchives() : null);

        addRow("Folder save & data engine", new File(NStormApp.defaultGameDir(), "saves").getAbsolutePath(),
                R.color.ns_ok, null);

        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ConfigurationInfo ci = am.getDeviceConfigurationInfo();
        int gles = ci.reqGlEsVersion;
        String glText = String.format(Locale.ROOT, "%s · OpenGL ES %d.%d · %d inti CPU", Build.MODEL, gles >> 16, gles & 0xFFFF,
                Runtime.getRuntime().availableProcessors());
        addRow("Perangkat", glText, gles >= 0x30000 ? R.color.ns_ok : R.color.ns_bad, null);

        scanHint.setText(scanning ? "Membaca daftar isi arsip…" : lib == null
                ? "Data game hanya dibaca, tidak pernah diubah."
                : "Buka Galeri Model untuk melihat karakter dan stage dalam 3D.");
        findViewById(R.id.btnScan).setEnabled(!scanning);
    }

    private void addRow(String label, String value, int colorRes, @Nullable View.OnClickListener click) {
        View row = LayoutInflater.from(this).inflate(R.layout.row_status, statusList, false);
        ((TextView) row.findViewById(R.id.label)).setText(label);
        ((TextView) row.findViewById(R.id.value)).setText(value);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(ContextCompat.getColor(this, colorRes));
        row.findViewById(R.id.dot).setBackground(dot);
        if (click != null) row.setOnClickListener(click);
        else row.setBackground(null);
        statusList.addView(row);
    }

    private void showArchives() {
        GameLibrary lib = app.library();
        if (lib == null) return;
        StringBuilder sb = new StringBuilder();
        for (GameLibrary.Archive a : lib.archives()) {
            sb.append(a.relativePath).append(" — ");
            sb.append(a.error == null ? a.entryCount + " file" : "GAGAL: " + a.error).append('\n');
        }
        if (sb.length() == 0) sb.append("Tidak ada arsip CPK.");
        Ui.alert(this, "Arsip CPK", sb.toString());
    }

    // ---------------------------------------------------------------- folder

    private void showFolderDialog() {
        if (!hasStorage()) {
            requestStorage();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Folder game")
                .setMessage("Saat ini: " + app.gameDir().getAbsolutePath()
                        + "\nBawaan: " + NStormApp.defaultGameDir().getAbsolutePath()
                        + "\n\nSave & data engine selalu di /storage/emulated/0/nstorm2/saves/")
                .setPositiveButton("Pilih folder lain…", (d, w) -> {
                    try {
                        folderPicker.launch(null);
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(this, "Pemilih folder tidak tersedia.", Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton("Pakai bawaan", (d, w) -> {
                    app.setGameDir(NStormApp.defaultGameDir());
                    refresh();
                })
                .setNegativeButton("Tutup", null)
                .show();
    }

    private void onFolderPicked(@Nullable Uri tree) {
        if (tree == null) return;
        File dir = treeToFile(tree);
        if (dir == null || !dir.isDirectory()) {
            Ui.alert(this, "Folder tidak didukung", "Pilih folder di penyimpanan internal, kartu SD atau USB.");
            return;
        }
        app.setGameDir(dir);
        refresh();
        startScan();
    }

    @Nullable
    static File treeToFile(Uri tree) {
        if (!"com.android.externalstorage.documents".equals(tree.getAuthority())) return null;
        String id = DocumentsContract.getTreeDocumentId(tree);
        int c = id.indexOf(':');
        if (c < 0) return null;
        String vol = id.substring(0, c), rel = id.substring(c + 1);
        File base = "primary".equalsIgnoreCase(vol) ? Environment.getExternalStorageDirectory() : new File("/storage/" + vol);
        return rel.isEmpty() ? base : new File(base, rel);
    }

    private void showHelp() {
        TextView tv = new TextView(this);
        int pad = Ui.dp(this, 20);
        tv.setPadding(pad, pad / 2, pad, pad / 2);
        tv.setTextColor(ContextCompat.getColor(this, R.color.ns_text));
        tv.setTextSize(14);
        tv.setText(Html.fromHtml(getString(R.string.help_html), Html.FROM_HTML_MODE_LEGACY));
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this).setTitle("Bantuan").setView(sv).setPositiveButton("Tutup", null).show();
    }
}
