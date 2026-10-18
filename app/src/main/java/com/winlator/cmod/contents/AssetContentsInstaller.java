package com.winlator.cmod.contents;

import android.app.Activity;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import com.winlator.cmod.R;
import com.winlator.cmod.core.PreloaderDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Installs the *.wcp content bundles shipped in assets/contents/ (extra DXVK,
 * VKD3D and FEXCore builds) that are not unpacked yet.
 *
 * Moved out of upstream MainActivity so that the NStorm 2 launcher can run it
 * too. Matching rules are unchanged from Winlator Cmod:
 *   - folder name may be "type-verDir-code" (duplicated type prefix)
 *   - a .wcp may carry a leading 'v' in the version that the folder omits
 *   - '-' and '_' are equivalent, matching is case-insensitive
 */
public final class AssetContentsInstaller {
    private static final String TAG = "AssetContentsInstaller";

    private AssetContentsInstaller() {}

    public static void installMissing(final Activity activity, @Nullable final Runnable onCompletion) {
        Executors.newSingleThreadExecutor().execute(() -> {
            PreloaderDialog spinner = new PreloaderDialog(activity);
            boolean spinnerShown = false;

            try {
                Map<String, Set<String>> installed = new HashMap<>();
                File root = ContentsManager.getContentDir(activity);

                File[] typeDirs = root.listFiles();
                if (typeDirs != null) {
                    for (File typeDir : typeDirs) {
                        if (!typeDir.isDirectory()) continue;
                        String typeKey = typeDir.getName().toLowerCase();
                        Set<String> vers = new HashSet<>();
                        File[] verDirs = typeDir.listFiles();
                        if (verDirs != null) {
                            for (File verDir : verDirs) {
                                if (!verDir.isDirectory()) continue;
                                String cleaned = verDir.getName().toLowerCase().replace('_', '-');
                                String dupPrefix = typeKey + "-";
                                if (cleaned.startsWith(dupPrefix)) cleaned = cleaned.substring(dupPrefix.length());
                                vers.add(cleaned);
                            }
                        }
                        installed.put(typeKey, vers);
                    }
                }

                String[] assetNames = activity.getAssets().list("contents");
                if (assetNames == null) assetNames = new String[0];

                List<String> toInstall = new ArrayList<>();
                for (String asset : assetNames) {
                    if (!asset.endsWith(".wcp")) continue;
                    String base = asset.substring(0, asset.length() - 4);
                    int dash = base.indexOf('-'), under = base.indexOf('_');
                    int sep = Math.min(dash == -1 ? Integer.MAX_VALUE : dash, under == -1 ? Integer.MAX_VALUE : under);
                    if (sep == Integer.MAX_VALUE) continue;

                    String typeKey = base.substring(0, sep).toLowerCase();
                    String verNorm = base.substring(sep + 1).toLowerCase().replace('_', '-');
                    String verNoV = verNorm.startsWith("v") ? verNorm.substring(1) : verNorm;

                    Set<String> vers = installed.get(typeKey);
                    boolean exists = false;
                    if (vers != null) {
                        for (String dir : vers) {
                            if (dir.equals(verNorm) || dir.equals(verNoV)
                                    || dir.startsWith(verNorm + "-") || dir.startsWith(verNoV + "-")) {
                                exists = true;
                                break;
                            }
                        }
                    }
                    if (!exists) toInstall.add(asset);
                }

                if (!toInstall.isEmpty()) {
                    ContentsManager cm = new ContentsManager(activity);
                    File tmpDir = new File(activity.getCacheDir(), "wcp_asset_tmp");
                    if (!tmpDir.exists()) tmpDir.mkdirs();

                    for (String asset : toInstall) {
                        if (!spinnerShown) {
                            spinnerShown = true;
                            activity.runOnUiThread(() -> spinner.show(R.string.installing_contents));
                        }

                        File tmp = new File(tmpDir, asset);
                        try (InputStream in = activity.getAssets().open("contents/" + asset);
                             OutputStream out = new FileOutputStream(tmp)) {
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        }

                        ContentsManager.OnInstallFinishedCallback cb = new ContentsManager.OnInstallFinishedCallback() {
                            private boolean first = true;

                            @Override
                            public void onSucceed(ContentProfile p) {
                                if (first) {
                                    first = false;
                                    cm.finishInstallContent(p, this);
                                }
                            }

                            @Override
                            public void onFailed(ContentsManager.InstallFailedReason r, Exception e) {
                                Log.e(TAG, "Install failed for " + asset + " : " + r, e);
                            }
                        };
                        cm.extraContentFile(Uri.fromFile(tmp), cb);
                        tmp.delete();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Asset-content install error", e);
            } finally {
                final boolean shown = spinnerShown;
                activity.runOnUiThread(() -> {
                    if (shown) spinner.close();
                    if (onCompletion != null) onCompletion.run();
                });
            }
        });
    }
}
