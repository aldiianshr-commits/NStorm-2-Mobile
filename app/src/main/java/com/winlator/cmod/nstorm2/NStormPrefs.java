package com.winlator.cmod.nstorm2;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.File;

/**
 * All NStorm 2 Mobile settings, stored in their own SharedPreferences file
 * ("nstorm2") so they never collide with the Winlator preferences that the
 * advanced mode keeps using.
 *
 * Every value here maps onto a real Winlator container/shortcut field or an
 * environment variable that the bundled Wine/Box64/FEX/DXVK/Turnip stack reads;
 * see {@link NStormLauncher#applySettings}.
 */
public final class NStormPrefs {
    public static final String FILE = "nstorm2";

    /** The executable of the PC (Steam) release of Naruto Shippuden: Ultimate Ninja Storm 2. */
    public static final String GAME_EXE = "NSUNS2.exe";
    /** Steam app id of the PC release (store.steampowered.com/app/543870). */
    public static final String STEAM_APP_ID = "543870";
    /** Id of the bundled touch layout (assets/inputcontrols/profiles/controls-50.icp). */
    public static final int TOUCH_PROFILE_ID = 50;

    public static final String KEY_GAME_DIR = "game_dir";
    public static final String KEY_EXE_PATH = "exe_path";
    public static final String KEY_ENGINE = "engine";
    public static final String KEY_GPU_DRIVER = "gpu_driver";
    public static final String KEY_DXVK_VERSION = "dxvk_version";
    public static final String KEY_DXVK_ASYNC = "dxvk_async";
    public static final String KEY_RESOLUTION = "resolution";
    public static final String KEY_STRETCH = "stretch";
    public static final String KEY_FPS_LIMIT = "fps_limit";
    public static final String KEY_SHOW_FPS = "show_fps";
    public static final String KEY_VRAM = "vram_limit";
    public static final String KEY_TURNIP_SYSMEM = "turnip_sysmem";
    public static final String KEY_FRAME_SYNC = "frame_sync";
    public static final String KEY_CPU_CORES = "cpu_cores";
    public static final String KEY_BOX64_PRESET = "box64_preset";
    public static final String KEY_AUDIO_DRIVER = "audio_driver";
    public static final String KEY_CONTROL_MODE = "control_mode";
    public static final String KEY_OVERLAY_OPACITY = "overlay_opacity";
    public static final String KEY_LAUNCH_MODE = "launch_mode";
    public static final String KEY_STEAM_PATH = "steam_path";
    public static final String KEY_STEAM_DELAY = "steam_delay";
    public static final String KEY_STEAM_APPID_FILE = "steam_appid_file";
    public static final String KEY_SAVE_SYNC = "save_sync";
    public static final String KEY_EXTRA_ENV = "extra_env";
    public static final String KEY_PENDING_SYNC_OUT = "pending_sync_out";
    public static final String KEY_STEAM_NOTICE_SHOWN = "steam_notice_shown";

    public static final String ENGINE_ARM64EC = "arm64ec";
    public static final String ENGINE_X86_64 = "x86_64";

    public static final String CONTROL_AUTO = "auto";
    public static final String CONTROL_TOUCH = "touch";
    public static final String CONTROL_GAMEPAD = "gamepad";

    public static final String LAUNCH_DIRECT = "direct";
    public static final String LAUNCH_STEAM = "steam";

    /** /storage/emulated/0/nstorm2 */
    public static File defaultRootDir() {
        return new File(Environment.getExternalStorageDirectory(), "nstorm2");
    }

    /** /storage/emulated/0/nstorm2/saves - always here, even if the game folder is moved. */
    public static File savesDir() {
        return new File(defaultRootDir(), "saves");
    }

    private final SharedPreferences sp;

    public NStormPrefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public SharedPreferences raw() {
        return sp;
    }

    private String str(String key, String def) {
        String v = sp.getString(key, def);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }

    public File gameDir() { return new File(str(KEY_GAME_DIR, defaultRootDir().getAbsolutePath())); }
    public void setGameDir(File dir) { sp.edit().putString(KEY_GAME_DIR, dir.getAbsolutePath()).remove(KEY_EXE_PATH).apply(); }

    /** Executable chosen by the user, or null to auto-detect. */
    public File exeOverride() {
        String p = sp.getString(KEY_EXE_PATH, "");
        return p == null || p.isEmpty() ? null : new File(p);
    }
    public void setExeOverride(File exe) {
        sp.edit().putString(KEY_EXE_PATH, exe == null ? "" : exe.getAbsolutePath()).apply();
    }

    public String engine() { return str(KEY_ENGINE, ENGINE_ARM64EC); }
    public boolean isArm64EC() { return ENGINE_ARM64EC.equals(engine()); }

    /** Adrenotools driver id bundled in assets/graphics_driver, or "System" for the stock Qualcomm driver. */
    public String gpuDriver() { return str(KEY_GPU_DRIVER, "turnip25.3.0_R3_Auto"); }

    /** DXVK build; "auto" picks the right one for the engine (see {@link #resolvedDxvkVersion()}). */
    public String dxvkVersion() { return str(KEY_DXVK_VERSION, "auto"); }

    public String resolvedDxvkVersion() {
        String v = dxvkVersion();
        if (!"auto".equals(v)) return v;
        if ("System".equals(gpuDriver())) {
            // The stock Adreno 650 driver lacks several Vulkan 1.3 features DXVK 2.x needs.
            return "async-1.10.3";
        }
        return isArm64EC() ? "2.3.1-arm64ec-gplasync" : "2.4.1-gplasync";
    }

    public boolean dxvkAsync() { return sp.getBoolean(KEY_DXVK_ASYNC, true); }
    public String resolution() { return str(KEY_RESOLUTION, "1280x800"); }
    public boolean stretch() { return sp.getBoolean(KEY_STRETCH, false); }
    public String fpsLimit() { return str(KEY_FPS_LIMIT, "60"); }
    public boolean showFps() { return sp.getBoolean(KEY_SHOW_FPS, false); }
    public String vramLimit() { return str(KEY_VRAM, "0"); }
    public boolean turnipSysmem() { return sp.getBoolean(KEY_TURNIP_SYSMEM, false); }
    public String frameSync() { return str(KEY_FRAME_SYNC, "Normal"); }

    /**
     * CPU affinity. Snapdragon 870: cpu0-3 Cortex-A55 (little), cpu4-6 Cortex-A77 2.42 GHz,
     * cpu7 Cortex-A77 3.2 GHz. Keeping the game on the A77 cores avoids it being
     * scheduled on the slow cluster.
     */
    public String cpuCores() { return str(KEY_CPU_CORES, "4,5,6,7"); }

    public String box64Preset() { return str(KEY_BOX64_PRESET, "INTERMEDIATE"); }
    public String audioDriver() { return str(KEY_AUDIO_DRIVER, "alsa-reflector"); }
    public String controlMode() { return str(KEY_CONTROL_MODE, CONTROL_AUTO); }
    public int overlayOpacity() { return sp.getInt(KEY_OVERLAY_OPACITY, 45); }
    public String launchMode() { return str(KEY_LAUNCH_MODE, LAUNCH_DIRECT); }
    public String steamPath() { return str(KEY_STEAM_PATH, "C:\\Program Files (x86)\\Steam\\steam.exe"); }

    public int steamDelaySeconds() {
        try {
            return Math.max(5, Math.min(180, Integer.parseInt(str(KEY_STEAM_DELAY, "30"))));
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    public boolean writeSteamAppIdFile() { return sp.getBoolean(KEY_STEAM_APPID_FILE, true); }
    public boolean saveSync() { return sp.getBoolean(KEY_SAVE_SYNC, true); }
    public String extraEnv() { return sp.getString(KEY_EXTRA_ENV, "").trim(); }

    public boolean pendingSyncOut() { return sp.getBoolean(KEY_PENDING_SYNC_OUT, false); }
    public void setPendingSyncOut(boolean v) { sp.edit().putBoolean(KEY_PENDING_SYNC_OUT, v).apply(); }

    public int containerId(String engine) { return sp.getInt("container_id_" + engine, 0); }
    public void setContainerId(String engine, int id) { sp.edit().putInt("container_id_" + engine, id).apply(); }

    public boolean steamNoticeShown() { return sp.getBoolean(KEY_STEAM_NOTICE_SHOWN, false); }
    public void setSteamNoticeShown() { sp.edit().putBoolean(KEY_STEAM_NOTICE_SHOWN, true).apply(); }
}
