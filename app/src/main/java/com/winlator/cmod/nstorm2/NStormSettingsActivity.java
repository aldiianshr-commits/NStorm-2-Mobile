package com.winlator.cmod.nstorm2;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.winlator.cmod.R;

/** Graphics, performance, controls and Steam settings for NStorm 2 (stored in "nstorm2" prefs). */
public class NStormSettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.nstorm_settings_activity);
        MaterialToolbar toolbar = findViewById(R.id.NSToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.NSSettingsContainer, new SettingsFragment())
                    .commit();
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
            getPreferenceManager().setSharedPreferencesName(NStormPrefs.FILE);
            setPreferencesFromResource(R.xml.nstorm2_preferences, rootKey);

            EditTextPreference delay = findPreference(NStormPrefs.KEY_STEAM_DELAY);
            if (delay != null) {
                delay.setOnBindEditTextListener(et -> et.setInputType(InputType.TYPE_CLASS_NUMBER));
            }
        }

        @Override
        public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            ViewGroup list = getListView();
            if (list != null) list.setClipToPadding(false);
        }
    }
}
