package com.winlator.cmod.nstorm2;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
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

import com.winlator.cmod.ControlsEditorActivity;
import com.winlator.cmod.MainActivity;
import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contents.AssetContentsInstaller;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.inputcontrols.ControllerManager;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xenvironment.ImageFsInstaller;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry point of NStorm 2 Mobile: a single-game front end on top of the Winlator
 * Cmod runtime. It handles permissions, first-run system install, the game folder
 * (/storage/emulated/0/nstorm2/), save sync (/nstorm2/saves/), settings and launch.
 */
public class NStormLauncherActivity extends AppCompatActivity {
    private static final String TAG = "NStormLauncher";
    private static final int REQ_STORAGE = 41;

    private NStormPrefs prefs;
    private NStormLauncher launcher;
    private final PreloaderDialog preloader = new PreloaderDialog(this);
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private LinearLayout statusList;
    private TextView playHint;
    private boolean systemReady = false;
    private boolean systemInstalling = false;

    private final ActivityResultLauncher<Uri> folderPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), this::onFolderPicked);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.nstorm_launcher_activity);
        ControllerManager.getInstance().init(getApplicationContext());

        prefs = new NStormPrefs(this);
        launcher = new NStormLauncher(this);
        statusList = findViewById(R.id.NSStatusList);
        playHint = findViewById(R.id.NSPlayHint);

        if (getResources().getConfiguration().screenWidthDp > 760) {
            View content = findViewById(R.id.NSContent);
            ViewGroup.LayoutParams lp = content.getLayoutParams();
            lp.width = dp(720);
            content.setLayoutParams(lp);
        }

        findViewById(R.id.NSPlay).setOnClickListener(v -> onPlay());
        findViewById(R.id.NSSettings).setOnClickListener(v -> startActivity(new Intent(this, NStormSettingsActivity.class)));
        findViewById(R.id.NSControls).setOnClickListener(v -> openControlsEditor());
        findViewById(R.id.NSFolder).setOnClickListener(v -> showFolderDialog());
        findViewById(R.id.NSSync).setOnClickListener(v -> syncNow());
        findViewById(R.id.NSRunOther).setOnClickListener(v -> showRunOtherDialog());
        findViewById(R.id.NSAdvanced).setOnClickListener(v -> openAdvancedMode());
        findViewById(R.id.NSHelp).setOnClickListener(v -> showHelp());

        TextView footer = findViewById(R.id.NSFooter);
        footer.setText(getString(R.string.nstorm_build_info)
                + "\nBerbasis Winlator Cmod (coffincolors) · Winlator (brunodev85) · Wine/Proton · Box64 · FEX-Emu · DXVK · Mesa Turnip"
                + "\nGame tidak disertakan. Gunakan file game PC milik Anda sendiri.");

        if (hasStoragePermission()) startSystemInstall();
        refreshStatus();

        // Winlator's advanced-mode settings restart the app to a given menu page; forward it.
        int menuItem = getIntent().getIntExtra("selected_menu_item_id", 0);
        if (menuItem > 0 && savedInstanceState == null) {
            Intent advanced = new Intent(this, MainActivity.class);
            advanced.putExtra("selected_menu_item_id", menuItem);
            startActivity(advanced);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        if (systemReady && prefs.pendingSyncOut()) syncOutAfterGame();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    // ------------------------------------------------------------------ permissions & system

    private boolean hasStoragePermission() {
        boolean rw = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return rw || Environment.isExternalStorageManager();
        return rw;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            new AlertDialog.Builder(this)
                    .setTitle("Izin akses file")
                    .setMessage("NStorm 2 perlu membaca file game di /storage/emulated/0/nstorm2/ dan menulis save ke /nstorm2/saves/.\n\n"
                            + "Pilih \"Akses semua file\" (disarankan, juga untuk SD card/USB) atau izin penyimpanan biasa.")
                    .setPositiveButton("Akses semua file", (d, w) -> {
                        try {
                            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            i.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (ActivityNotFoundException e) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    })
                    .setNegativeButton("Izin biasa", (d, w) -> requestRuntimeStorage())
                    .show();
        } else {
            requestRuntimeStorage();
        }
    }

    private void requestRuntimeStorage() {
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            if (hasStoragePermission()) startSystemInstall();
            refreshStatus();
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        // returning from the "all files access" settings page
        if (!systemReady && !systemInstalling && hasStoragePermission()) startSystemInstall();
    }

    private void startSystemInstall() {
        if (systemReady || systemInstalling) return;
        systemInstalling = true;
        refreshStatus();
        ImageFsInstaller.installIfNeeded(this, () -> AssetContentsInstaller.installMissing(this, () -> {
            systemInstalling = false;
            systemReady = ImageFs.find(this).isValid();
            ensureFolders();
            refreshStatus();
            if (systemReady && prefs.pendingSyncOut()) syncOutAfterGame();
        }));
    }

    private void ensureFolders() {
        File root = NStormPrefs.defaultRootDir();
        if (!root.isDirectory()) root.mkdirs();
        File saves = NStormPrefs.savesDir();
        if (!saves.isDirectory()) saves.mkdirs();
        File howTo = new File(root, "TARUH-FILE-GAME-DI-SINI.txt");
        if (!howTo.exists()) {
            com.winlator.cmod.core.FileUtils.writeString(howTo,
                    "Salin isi folder instalasi PC \"NARUTO SHIPPUDEN Ultimate Ninja STORM 2\" ke folder ini,\n"
                            + "sehingga NSUNS2.exe berada di /storage/emulated/0/nstorm2/NSUNS2.exe\n"
                            + "(subfolder juga boleh, NStorm 2 akan mencarinya otomatis).\n\n"
                            + "Save game disimpan di /storage/emulated/0/nstorm2/saves/\n");
        }
    }

    // ------------------------------------------------------------------ status

    private File currentExe() {
        File override = prefs.exeOverride();
        if (override != null && override.isFile()) return override;
        return GameLocator.findGameExe(prefs.gameDir());
    }

    private void refreshStatus() {
        statusList.removeAllViews();
        boolean perm = hasStoragePermission();
        addStatus(R.string.nstorm_status_permission,
                perm ? getString(R.string.nstorm_ok_granted) : getString(R.string.nstorm_grant),
                perm ? Level.OK : Level.BAD, perm ? null : v -> requestStoragePermission());

        String sys = systemReady ? getString(R.string.nstorm_system_ready)
                : systemInstalling ? getString(R.string.nstorm_system_installing)
                : getString(R.string.nstorm_system_missing);
        addStatus(R.string.nstorm_status_system, sys,
                systemReady ? Level.OK : systemInstalling ? Level.WARN : Level.BAD, null);

        File dir = prefs.gameDir();
        boolean dirOk = perm && dir.isDirectory();
        addStatus(R.string.nstorm_status_folder,
                dir.getAbsolutePath() + (dirOk ? "" : "\n" + getString(R.string.nstorm_folder_missing)),
                dirOk ? Level.OK : Level.BAD, v -> showFolderDialog());

        File exe = perm ? currentExe() : null;
        String exeText;
        Level exeLevel;
        if (exe == null) {
            exeText = getString(R.string.nstorm_exe_missing);
            exeLevel = Level.BAD;
        } else {
            boolean steam = GameLocator.usesSteamApi(exe.getParentFile());
            exeText = exe.getAbsolutePath() + (steam ? "\n" + getString(R.string.nstorm_exe_steam_note) : "");
            exeLevel = steam && NStormPrefs.LAUNCH_DIRECT.equals(prefs.launchMode()) ? Level.WARN : Level.OK;
        }
        addStatus(R.string.nstorm_status_exe, exeText, exeLevel, v -> showRunOtherDialog());

        addStatus(R.string.nstorm_status_saves, NStormPrefs.savesDir().getAbsolutePath()
                        + (prefs.saveSync() ? " · sinkron otomatis aktif" : " · sinkron otomatis MATI"),
                prefs.saveSync() ? Level.OK : Level.WARN, null);

        String engine = prefs.isArm64EC() ? "ARM64EC + FEXCore" : "x86-64 + Box64";
        String gpu = "System".equals(prefs.gpuDriver()) ? "Driver Qualcomm" : prefs.gpuDriver().replace("turnip", "Turnip ").replace('_', ' ');
        addStatus(R.string.nstorm_status_engine, engine + " · " + gpu + " · DXVK " + prefs.resolvedDxvkVersion()
                + " · " + prefs.resolution().replace('x', '×') + " · " + ("0".equals(prefs.fpsLimit()) ? "FPS bebas" : prefs.fpsLimit() + " FPS"),
                Level.INFO, v -> startActivity(new Intent(this, NStormSettingsActivity.class)));

        boolean canPlay = systemReady && exe != null;
        findViewById(R.id.NSPlay).setEnabled(canPlay);
        findViewById(R.id.NSPlay).setAlpha(canPlay ? 1f : 0.5f);
        if (!perm) playHint.setText("Beri izin penyimpanan terlebih dahulu.");
        else if (!systemReady) playHint.setText(systemInstalling ? "Tunggu pemasangan sistem selesai." : "Sistem belum terpasang.");
        else if (exe == null) playHint.setText("Salin file game PC ke " + prefs.gameDir().getAbsolutePath());
        else playHint.setText("Ketuk 4 jari di layar saat bermain untuk membuka menu (keluar, keyboard, kontrol).");
    }

    private enum Level { OK, WARN, BAD, INFO }

    private void addStatus(int labelRes, String value, Level level, @Nullable View.OnClickListener click) {
        View row = LayoutInflater.from(this).inflate(R.layout.nstorm_status_row, statusList, false);
        ((TextView) row.findViewById(R.id.NSLabel)).setText(labelRes);
        ((TextView) row.findViewById(R.id.NSValue)).setText(value);
        View dot = row.findViewById(R.id.NSDot);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        int color;
        switch (level) {
            case OK: color = R.color.nstorm_ok; break;
            case WARN: color = R.color.nstorm_warn; break;
            case BAD: color = R.color.nstorm_bad; break;
            default: color = R.color.nstorm_accent; break;
        }
        circle.setColor(ColorStateList.valueOf(ContextCompat.getColor(this, color)));
        dot.setBackground(circle);
        if (click != null) row.setOnClickListener(click);
        else row.setBackground(null);
        statusList.addView(row);
    }

    // ------------------------------------------------------------------ play

    private void onPlay() {
        if (!systemReady) return;
        File exe = currentExe();
        if (exe == null) {
            refreshStatus();
            return;
        }
        if (NStormPrefs.LAUNCH_DIRECT.equals(prefs.launchMode())
                && GameLocator.usesSteamApi(exe.getParentFile()) && !prefs.steamNoticeShown()) {
            new AlertDialog.Builder(this)
                    .setTitle("Versi Steam terdeteksi")
                    .setMessage(Html.fromHtml(getString(R.string.nstorm_steam_notice), Html.FROM_HTML_MODE_LEGACY))
                    .setPositiveButton("Tetap jalankan", (d, w) -> {
                        prefs.setSteamNoticeShown();
                        launchGame(exe);
                    })
                    .setNeutralButton("Pengaturan", (d, w) -> {
                        prefs.setSteamNoticeShown();
                        startActivity(new Intent(this, NStormSettingsActivity.class));
                    })
                    .setNegativeButton("Batal", null)
                    .show();
            return;
        }
        launchGame(exe);
    }

    private void launchGame(File exe) {
        preloader.show(R.string.nstorm_preparing);
        launcher.ensureContainer((container, error) -> {
            if (container == null) {
                preloader.close();
                showError("Container gagal dibuat", error);
                return;
            }
            io.execute(() -> {
                try {
                    launcher.applySettings(container);
                    if (prefs.saveSync()) SaveSync.syncIn(container);
                    launcher.prepareSteamAppIdFile(exe);
                    boolean touch = launcher.prepareControls();
                    File shortcut = launcher.writeShortcut(container, exe, touch);
                    Intent intent = launcher.buildLaunchIntent(container, shortcut);
                    prefs.setPendingSyncOut(prefs.saveSync());
                    runOnUiThread(() -> {
                        preloader.close();
                        startActivity(intent);
                    });
                } catch (Exception e) {
                    Log.e(TAG, "launch", e);
                    runOnUiThread(() -> {
                        preloader.close();
                        showError("Gagal menyiapkan game", e.toString());
                    });
                }
            });
        });
    }

    private void launchTool(File exe) {
        preloader.show(R.string.nstorm_preparing);
        launcher.ensureContainer((container, error) -> {
            if (container == null) {
                preloader.close();
                showError("Container gagal dibuat", error);
                return;
            }
            io.execute(() -> {
                try {
                    launcher.applySettings(container);
                    // installers and tools are driven with the touchscreen as a mouse
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                            .edit().putInt("selected_profile_index", -1).apply();
                    File shortcut = launcher.writeToolShortcut(container, exe);
                    Intent intent = launcher.buildLaunchIntent(container, shortcut);
                    intent.putExtra("shortcut_name", exe.getName());
                    prefs.setPendingSyncOut(prefs.saveSync());
                    runOnUiThread(() -> {
                        preloader.close();
                        startActivity(intent);
                    });
                } catch (Exception e) {
                    Log.e(TAG, "tool", e);
                    runOnUiThread(() -> {
                        preloader.close();
                        showError("Gagal menjalankan program", e.toString());
                    });
                }
            });
        });
    }

    // ------------------------------------------------------------------ saves

    private void syncOutAfterGame() {
        prefs.setPendingSyncOut(false);
        io.execute(() -> {
            int copied = 0, failed = 0;
            try {
                ContainerManager manager = new ContainerManager(this);
                for (String engine : new String[]{NStormPrefs.ENGINE_ARM64EC, NStormPrefs.ENGINE_X86_64}) {
                    Container c = launcher.findContainer(manager, engine);
                    if (c == null) continue;
                    SaveSync.Result r = SaveSync.syncOut(c);
                    copied += r.copied;
                    failed += r.failed;
                }
            } catch (Exception e) {
                Log.e(TAG, "syncOut", e);
                failed++;
            }
            final int fc = copied, ff = failed;
            runOnUiThread(() -> {
                if (ff > 0) Toast.makeText(this, "Save: " + fc + " file disalin, " + ff + " gagal. Lihat /nstorm2/saves", Toast.LENGTH_LONG).show();
                else if (fc > 0) Toast.makeText(this, "Save tersimpan ke /nstorm2/saves (" + fc + " file)", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void syncNow() {
        if (!systemReady) return;
        preloader.show(R.string.nstorm_syncing);
        io.execute(() -> {
            int out = 0, in = 0, failed = 0;
            try {
                ContainerManager manager = new ContainerManager(this);
                for (String engine : new String[]{NStormPrefs.ENGINE_ARM64EC, NStormPrefs.ENGINE_X86_64}) {
                    Container c = launcher.findContainer(manager, engine);
                    if (c == null) continue;
                    SaveSync.Result r1 = SaveSync.syncOut(c);
                    SaveSync.Result r2 = SaveSync.syncIn(c);
                    out += r1.copied;
                    in += r2.copied;
                    failed += r1.failed + r2.failed;
                }
            } catch (Exception e) {
                Log.e(TAG, "syncNow", e);
                failed++;
            }
            final String msg = "Ke /nstorm2/saves: " + out + " file · ke game: " + in + " file" + (failed > 0 ? " · gagal: " + failed : "");
            runOnUiThread(() -> {
                preloader.close();
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            });
        });
    }

    // ------------------------------------------------------------------ folder

    private void showFolderDialog() {
        if (!hasStoragePermission()) {
            requestStoragePermission();
            return;
        }
        File def = NStormPrefs.defaultRootDir();
        new AlertDialog.Builder(this)
                .setTitle(R.string.nstorm_choose_folder)
                .setMessage("Folder saat ini:\n" + prefs.gameDir().getAbsolutePath()
                        + "\n\nFolder bawaan: " + def.getAbsolutePath()
                        + "\nSave selalu disimpan di: " + NStormPrefs.savesDir().getAbsolutePath())
                .setPositiveButton("Pilih folder lain…", (d, w) -> {
                    try {
                        folderPicker.launch(null);
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(this, "Pemilih folder tidak tersedia di perangkat ini.", Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton("Pakai bawaan", (d, w) -> {
                    if (!def.isDirectory()) def.mkdirs();
                    prefs.setGameDir(def);
                    refreshStatus();
                })
                .setNegativeButton("Tutup", null)
                .show();
    }

    private void onFolderPicked(@Nullable Uri tree) {
        if (tree == null) return;
        File dir = treeUriToFile(tree);
        if (dir == null || !dir.isDirectory()) {
            showError("Folder tidak didukung", "Pilih folder di penyimpanan internal, kartu SD, atau USB (bukan folder cloud).");
            return;
        }
        prefs.setGameDir(dir);
        refreshStatus();
        if (GameLocator.findGameExe(dir) == null) {
            Toast.makeText(this, "NSUNS2.exe tidak ditemukan di folder itu.", Toast.LENGTH_LONG).show();
        }
    }

    /** primary:nstorm2 -> /storage/emulated/0/nstorm2 ; 1A2B-3C4D:Games -> /storage/1A2B-3C4D/Games */
    @Nullable
    static File treeUriToFile(Uri tree) {
        if (!"com.android.externalstorage.documents".equals(tree.getAuthority())) return null;
        String docId = DocumentsContract.getTreeDocumentId(tree);
        int colon = docId.indexOf(':');
        if (colon < 0) return null;
        String volume = docId.substring(0, colon);
        String rel = docId.substring(colon + 1);
        File base = "primary".equalsIgnoreCase(volume)
                ? Environment.getExternalStorageDirectory()
                : new File("/storage/" + volume);
        return rel.isEmpty() ? base : new File(base, rel);
    }

    // ------------------------------------------------------------------ tools

    private void showRunOtherDialog() {
        if (!systemReady) return;
        File root = prefs.gameDir();
        List<File> exes = GameLocator.listExecutables(root);
        if (exes.isEmpty()) {
            showError("Tidak ada file .exe", "Taruh program Windows (mis. SteamSetup.exe) di " + root.getAbsolutePath());
            return;
        }
        String[] labels = new String[exes.size()];
        String rootPath = root.getAbsolutePath();
        for (int i = 0; i < exes.size(); i++) {
            String p = exes.get(i).getAbsolutePath();
            labels[i] = p.startsWith(rootPath) ? p.substring(rootPath.length() + 1) : p;
        }
        new AlertDialog.Builder(this)
                .setTitle("Pilih program")
                .setItems(labels, (d, which) -> {
                    File chosen = exes.get(which);
                    if (chosen.getName().equalsIgnoreCase(NStormPrefs.GAME_EXE)) {
                        prefs.setExeOverride(chosen);
                        refreshStatus();
                        launchGame(chosen);
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle(chosen.getName())
                                .setItems(new String[]{"Jalankan sekali (mis. installer/alat konfigurasi)", "Jadikan file game utama"}, (d2, w2) -> {
                                    if (w2 == 0) launchTool(chosen);
                                    else {
                                        prefs.setExeOverride(chosen);
                                        refreshStatus();
                                    }
                                })
                                .show();
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void openControlsEditor() {
        launcher.ensureTouchProfile();
        Intent intent = new Intent(this, ControlsEditorActivity.class);
        intent.putExtra("profile_id", NStormPrefs.TOUCH_PROFILE_ID);
        startActivity(intent);
    }

    private void openAdvancedMode() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.nstorm_advanced)
                .setMessage("Membuka antarmuka Winlator Cmod lengkap (container, driver Adrenotools, konten DXVK/FEX, profil kontrol). "
                        + "Container \"NStorm 2\" dibuat otomatis oleh peluncur ini; pengaturan di layar ini akan menimpa perubahan grafis pada container tersebut setiap kali game dijalankan.")
                .setPositiveButton("Buka", (d, w) -> startActivity(new Intent(this, MainActivity.class)))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void showHelp() {
        TextView tv = new TextView(this);
        int pad = dp(20);
        tv.setPadding(pad, pad / 2, pad, 0);
        tv.setTextColor(ContextCompat.getColor(this, R.color.nstorm_text));
        tv.setTextSize(14);
        tv.setText(Html.fromHtml(getString(R.string.nstorm_help_html), Html.FROM_HTML_MODE_LEGACY));
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(R.string.nstorm_help)
                .setView(sv)
                .setPositiveButton("Tutup", null)
                .show();
    }

    private void showError(String title, @Nullable String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message == null ? "" : message)
                .setPositiveButton("OK", null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
