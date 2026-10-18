package com.winlator.cmod.nstorm2;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.box86_64.Box86_64Preset;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.AppPaths;
import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.WineThemeManager;
import com.winlator.cmod.inputcontrols.ControllerManager;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.winhandler.WinHandler;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Turns the NStorm 2 settings into a ready-to-run Winlator container + shortcut
 * and starts {@link XServerDisplayActivity} on it.
 *
 * Two containers can exist, one per engine, because the Wine build is a
 * container-level property in Winlator:
 *   arm64ec : Proton 9.0 ARM64EC, x86-64 code translated by FEXCore (default, fastest on SD870)
 *   x86_64  : Proton 9.0 x86-64 running entirely under Box64 (compatibility fallback)
 */
public final class NStormLauncher {
    private static final String TAG = "NStormLauncher";
    public static final String SHORTCUT_FILE = "NStorm2.desktop";
    public static final String SHORTCUT_NAME = "NARUTO SHIPPUDEN Ultimate Ninja STORM 2";
    /** Windows drive letter the game folder is mapped to. */
    public static final char GAME_DRIVE = 'D';

    private final Context context;
    private final NStormPrefs prefs;

    public NStormLauncher(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = new NStormPrefs(context);
    }

    public interface ReadyCallback {
        void onReady(@Nullable Container container, @Nullable String error);
    }

    // ------------------------------------------------------------------ containers

    public static String wineVersionFor(String engine) {
        return NStormPrefs.ENGINE_X86_64.equals(engine) ? "proton-9.0-x86_64" : "proton-9.0-arm64ec";
    }

    @Nullable
    public Container findContainer(ContainerManager manager, String engine) {
        int id = prefs.containerId(engine);
        Container c = id > 0 ? manager.getContainerById(id) : null;
        if (c != null && wineVersionFor(engine).equals(c.getWineVersion())) return c;
        return null;
    }

    /** Finds or creates the container for the selected engine. Callback runs on the main thread. */
    public void ensureContainer(ReadyCallback callback) {
        final String engine = prefs.engine();
        final Handler main = new Handler(Looper.getMainLooper());
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                ContainerManager manager = new ContainerManager(context);
                Container existing = findContainer(manager, engine);
                if (existing != null) {
                    main.post(() -> callback.onReady(existing, null));
                    return;
                }
                ContentsManager contentsManager = new ContentsManager(context);
                contentsManager.syncContents();
                JSONObject data = buildContainerData(engine);
                main.post(() -> manager.createContainerAsync(data, contentsManager, (Callback<Container>) container -> {
                    if (container == null) {
                        callback.onReady(null, "Gagal membuat container Wine (" + wineVersionFor(engine) + ").");
                        return;
                    }
                    prefs.setContainerId(engine, container.id);
                    callback.onReady(container, null);
                }));
            } catch (Exception e) {
                Log.e(TAG, "ensureContainer", e);
                main.post(() -> callback.onReady(null, e.getMessage()));
            }
        });
    }

    private JSONObject buildContainerData(String engine) throws JSONException {
        boolean arm64ec = NStormPrefs.ENGINE_ARM64EC.equals(engine);
        JSONObject data = new JSONObject();
        data.put("name", arm64ec ? "NStorm 2 (ARM64EC + FEX)" : "NStorm 2 (x86-64 + Box64)");
        data.put("screenSize", prefs.resolution());
        data.put("envVars", buildEnvVars());
        data.put("cpuList", prefs.cpuCores());
        data.put("graphicsDriver", Container.DEFAULT_GRAPHICS_DRIVER);
        data.put("graphicsDriverConfig", buildGraphicsDriverConfig());
        data.put("dxwrapper", "dxvk");
        data.put("ddrawrapper", Container.DEFAULT_DDRAWRAPPER);
        data.put("dxwrapperConfig", buildDxwrapperConfig());
        data.put("audioDriver", prefs.audioDriver());
        data.put("emulator", arm64ec ? "FEXCore" : "Box64");
        data.put("wincomponents", Container.DEFAULT_WINCOMPONENTS);
        data.put("drives", buildDrives());
        data.put("showFPS", prefs.showFps());
        data.put("fullscreenStretched", prefs.stretch());
        data.put("inputType", WinHandler.FLAG_INPUT_TYPE_XINPUT);
        data.put("wow64Mode", true);
        data.put("startupSelection", Container.STARTUP_SELECTION_ESSENTIAL);
        data.put("box64Version", DefaultVersion.BOX64);
        data.put("box64Preset", prefs.box64Preset());
        data.put("fexcoreVersion", DefaultVersion.FEXCORE);
        data.put("desktopTheme", WineThemeManager.DEFAULT_DESKTOP_THEME);
        data.put("rcfileId", 0);
        data.put("wineVersion", wineVersionFor(engine));
        data.put("midiSoundFont", "");
        data.put("lc_all", "");
        data.put("primaryController", 1);
        data.put("gstreamerWorkaround", false);
        return data;
    }

    // ------------------------------------------------------------------ settings

    String buildEnvVars() {
        StringBuilder sb = new StringBuilder();
        sb.append("WINEESYNC=1");
        sb.append(" MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB");
        sb.append(" mesa_glthread=true");
        // Turnip on Adreno 650: GMEM (tiled) rendering is the fast path; sysmem is a stability fallback.
        sb.append(" TU_DEBUG=noconform").append(prefs.turnipSysmem() ? ",sysmem" : "");
        sb.append(" MANGOHUD=0");
        String extra = prefs.extraEnv();
        if (!extra.isEmpty()) sb.append(' ').append(extra);
        return sb.toString();
    }

    String buildGraphicsDriverConfig() {
        return "version=" + prefs.gpuDriver()
                + ";blacklistedExtensions="
                + ";maxDeviceMemory=" + prefs.vramLimit()
                + ";adrenotoolsTurnip=1"
                + ";frameSync=" + prefs.frameSync();
    }

    String buildDxwrapperConfig() {
        String async = prefs.dxvkAsync() ? "1" : "0";
        return "version=" + prefs.resolvedDxvkVersion()
                + ",framerate=" + prefs.fpsLimit()
                + ",maxDeviceMemory=" + prefs.vramLimit()
                + ",async=" + async
                + ",asyncCache=" + async
                + ",vkd3dVersion=" + DefaultVersion.VKD3D
                + ",vkd3dLevel=12_1";
    }

    String buildDrives() {
        return GAME_DRIVE + ":" + prefs.gameDir().getAbsolutePath() + "E:" + AppPaths.STORAGE_DIR;
    }

    /** Pushes the current NStorm settings into the container (cheap; done before every launch). */
    public void applySettings(Container container) {
        boolean arm64ec = NStormPrefs.ENGINE_ARM64EC.equals(prefs.engine());
        container.setScreenSize(prefs.resolution());
        container.setEnvVars(buildEnvVars());
        container.setCPUList(prefs.cpuCores());
        container.setGraphicsDriver(Container.DEFAULT_GRAPHICS_DRIVER);
        container.setGraphicsDriverConfig(buildGraphicsDriverConfig());
        container.setDXWrapper("dxvk");
        container.setDXWrapperConfig(buildDxwrapperConfig());
        container.setAudioDriver(prefs.audioDriver());
        container.setEmulator(arm64ec ? "FEXCore" : "Box64");
        container.setDrives(buildDrives());
        container.setShowFPS(prefs.showFps());
        container.setFullscreenStretched(prefs.stretch());
        container.setInputType(WinHandler.FLAG_INPUT_TYPE_XINPUT);
        container.setBox64Preset(validBox64Preset(prefs.box64Preset()));
        container.saveData();
    }

    private static String validBox64Preset(String p) {
        switch (p) {
            case Box86_64Preset.STABILITY:
            case Box86_64Preset.COMPATIBILITY:
            case Box86_64Preset.INTERMEDIATE:
            case Box86_64Preset.PERFORMANCE:
                return p;
            default:
                return Box86_64Preset.INTERMEDIATE;
        }
    }

    // ------------------------------------------------------------------ shortcut

    /** Escapes a Windows path the way upstream .desktop files expect (see StringUtils.unescape). */
    static String escapeForDesktopExec(String windowsPath) {
        return windowsPath.replace("\\", "\\\\\\\\");
    }

    /**
     * Writes the .desktop shortcut XServerDisplayActivity runs. In Steam mode the
     * target is a batch file that starts the Steam client, waits, then starts the
     * game from its own folder.
     */
    public File writeShortcut(Container container, File exe, boolean touchControls) throws IOException {
        String target;
        String steamMode = prefs.launchMode();
        File gameRoot = prefs.gameDir();
        String exeWin = GameLocator.toWindowsPath(gameRoot, exe, GAME_DRIVE);

        if (NStormPrefs.LAUNCH_STEAM.equals(steamMode)) {
            File batDir = new File(container.getRootDir(), ".wine/drive_c/nstorm2");
            if (!batDir.isDirectory() && !batDir.mkdirs()) throw new IOException("cannot create " + batDir);
            File bat = new File(batDir, "launch_steam.bat");
            String exeDirWin = exeWin.substring(0, exeWin.lastIndexOf('\\'));
            String steam = prefs.steamPath();
            String steamDir = steam.contains("\\") ? steam.substring(0, steam.lastIndexOf('\\')) : "C:\\";
            int delay = prefs.steamDelaySeconds();
            String script = "@echo off\r\n"
                    + "rem Generated by NStorm 2 Mobile\r\n"
                    + "start \"\" /d \"" + steamDir + "\" \"" + steam + "\" -silent -no-browser -cef-disable-gpu -cef-disable-sandbox -noreactlogin\r\n"
                    + "timeout /t " + delay + " /nobreak >nul 2>&1 || ping -n " + (delay + 1) + " 127.0.0.1 >nul\r\n"
                    + "cd /d \"" + exeDirWin + "\"\r\n"
                    + "start \"\" /d \"" + exeDirWin + "\" \"" + exe.getName() + "\"\r\n";
            FileUtils.writeString(bat, script);
            target = "C:\\nstorm2\\launch_steam.bat";
        } else {
            target = exeWin;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[Desktop Entry]\n");
        sb.append("Name=").append(SHORTCUT_NAME).append('\n');
        sb.append("Exec=env WINEPREFIX=\"/home/xuser/.wine\" wine ").append(escapeForDesktopExec(target)).append('\n');
        sb.append("Type=Application\n");
        sb.append("StartupNotify=true\n");
        sb.append("Icon=\n");
        sb.append("StartupWMClass=").append(exe.getName().toLowerCase()).append('\n');
        sb.append('\n');
        sb.append("[Extra Data]\n");
        sb.append("container_id=").append(container.id).append('\n');
        if (touchControls) sb.append("controlsProfile=").append(NStormPrefs.TOUCH_PROFILE_ID).append('\n');
        sb.append("fullscreenStretched=").append(prefs.stretch() ? "1" : "0").append('\n');
        sb.append("cpuList=").append(prefs.cpuCores()).append('\n');

        File desktopDir = container.getDesktopDir();
        if (!desktopDir.isDirectory() && !desktopDir.mkdirs()) throw new IOException("cannot create " + desktopDir);
        File file = new File(desktopDir, SHORTCUT_FILE);
        if (!FileUtils.writeString(file, sb.toString())) throw new IOException("cannot write " + file);
        return file;
    }

    /** One-off shortcut for running another program (e.g. SteamSetup.exe) inside the same container. */
    public File writeToolShortcut(Container container, File exe) throws IOException {
        String exeWin = GameLocator.toWindowsPath(prefs.gameDir(), exe, GAME_DRIVE);
        String content = "[Desktop Entry]\n"
                + "Name=" + exe.getName() + "\n"
                + "Exec=env WINEPREFIX=\"/home/xuser/.wine\" wine " + escapeForDesktopExec(exeWin) + "\n"
                + "Type=Application\nStartupNotify=true\nIcon=\n"
                + "StartupWMClass=" + exe.getName().toLowerCase() + "\n\n"
                + "[Extra Data]\ncontainer_id=" + container.id + "\n";
        File desktopDir = container.getDesktopDir();
        if (!desktopDir.isDirectory() && !desktopDir.mkdirs()) throw new IOException("cannot create " + desktopDir);
        File file = new File(desktopDir, "NStorm2-tool.desktop");
        if (!FileUtils.writeString(file, content)) throw new IOException("cannot write " + file);
        return file;
    }

    /** steam_appid.txt next to the exe: Valve's documented way to stop SteamAPI_RestartAppIfNecessary
     *  from relaunching through Steam when the game is started by hand. Ownership is still checked
     *  by the running, logged-in Steam client. */
    public void prepareSteamAppIdFile(File exe) {
        File f = new File(exe.getParentFile(), "steam_appid.txt");
        boolean wanted = NStormPrefs.LAUNCH_STEAM.equals(prefs.launchMode()) && prefs.writeSteamAppIdFile();
        if (wanted && !f.exists()) FileUtils.writeString(f, NStormPrefs.STEAM_APP_ID);
    }

    // ------------------------------------------------------------------ controls

    /** Copies the bundled touch layout into the profiles dir if it is missing. */
    public void ensureTouchProfile() {
        File target = ControlsProfile.getProfileFile(context, NStormPrefs.TOUCH_PROFILE_ID);
        if (target.isFile()) return;
        try (InputStream in = context.getAssets().open("inputcontrols/profiles/controls-" + NStormPrefs.TOUCH_PROFILE_ID + ".icp");
             OutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } catch (IOException e) {
            Log.e(TAG, "ensureTouchProfile", e);
        }
        // make the in-memory profile list pick it up next time
        new InputControlsManager(context).loadProfiles(false);
    }

    @Nullable
    public static InputDevice firstPhysicalGamepad() {
        ControllerManager cm = ControllerManager.getInstance();
        cm.scanForDevices();
        List<InputDevice> devices = cm.getDetectedDevices();
        return devices.isEmpty() ? null : devices.get(0);
    }

    /** Decides touch vs. gamepad for this session and prepares the controller slots. */
    public boolean prepareControls() {
        String mode = prefs.controlMode();
        InputDevice pad = firstPhysicalGamepad();
        boolean touch;
        if (NStormPrefs.CONTROL_TOUCH.equals(mode)) touch = true;
        else if (NStormPrefs.CONTROL_GAMEPAD.equals(mode)) touch = false;
        else touch = pad == null;

        SharedPreferences winPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor ed = winPrefs.edit();
        ed.putFloat("overlay_opacity", prefs.overlayOpacity() / 100f);
        ControllerManager cm = ControllerManager.getInstance();
        if (touch) {
            ensureTouchProfile();
        } else {
            // no on-screen layout: Winlator would otherwise fall back to the last profile used in advanced mode
            ed.putInt("selected_profile_index", -1);
            cm.setSlotEnabled(0, true);
            if (pad != null) cm.assignDeviceToSlot(0, pad);
        }
        ed.apply();
        cm.saveAssignments();
        return touch;
    }

    // ------------------------------------------------------------------ launch

    public Intent buildLaunchIntent(Container container, File shortcut) {
        Intent intent = new Intent(context, XServerDisplayActivity.class);
        intent.putExtra("container_id", container.id);
        intent.putExtra("shortcut_path", shortcut.getAbsolutePath());
        intent.putExtra("shortcut_name", SHORTCUT_NAME);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
}
