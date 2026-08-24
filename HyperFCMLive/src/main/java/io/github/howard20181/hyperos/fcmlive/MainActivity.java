package io.github.howard20181.hyperos.fcmlive;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * Settings screen: lets the user pick which apps FCM is allowed to wake /
 * auto-launch. By default only apps with detectable FCM/GCM integration are
 * shown; manually allowlisted apps are always kept visible as a fallback.
 */
public class MainActivity extends AppCompatActivity implements SearchView.OnQueryTextListener {

    private static final String FIREBASE_MESSAGING_EVENT = "com.google.firebase.MESSAGING_EVENT";
    private static final String C2DM_RECEIVE_ACTION = "com.google.android.c2dm.intent.RECEIVE";
    private static final String C2DM_RECEIVE_PERMISSION = "com.google.android.c2dm.permission.RECEIVE";

    private final List<AppListAdapter.AppEntry> allApps = new ArrayList<>();
    private final List<AppListAdapter.AppEntry> filteredApps = new ArrayList<>();
    private Set<String> allowlist = new HashSet<>();
    private AppListAdapter adapter;
    private SearchView searchView;
    private boolean showSystemApps = false;
    private boolean showNonFcmApps = false;
    private XposedService xposedService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initXposedService();

        adapter = new AppListAdapter(this, filteredApps, (pkg, checked) -> {
            if (checked) {
                allowlist.add(pkg);
            } else {
                allowlist.remove(pkg);
            }
            updateAllowlist();
            for (AppListAdapter.AppEntry app : allApps) {
                if (app.packageName.equals(pkg)) {
                    app.checked = checked;
                    break;
                }
            }
            sortApps();
            filterApps(searchView.getQuery().toString());
        });
        ((android.widget.ListView) findViewById(R.id.app_list)).setAdapter(adapter);

        searchView = findViewById(R.id.search_view);
        searchView.setOnQueryTextListener(this);

        MaterialCheckBox cbSystemApps = findViewById(R.id.cb_system_apps);
        cbSystemApps.setChecked(showSystemApps);
        cbSystemApps.setOnCheckedChangeListener((buttonView, isChecked) -> {
            showSystemApps = isChecked;
            loadApps();
        });

        MaterialCheckBox cbNonFcmApps = findViewById(R.id.cb_non_fcm_apps);
        cbNonFcmApps.setChecked(showNonFcmApps);
        cbNonFcmApps.setOnCheckedChangeListener((buttonView, isChecked) -> {
            showNonFcmApps = isChecked;
            loadApps();
        });

        MaterialButton selectDetected = findViewById(R.id.btn_select_all);
        MaterialButton clearAll = findViewById(R.id.btn_clear_all);
        selectDetected.setOnClickListener(v -> selectDetectedFcmApps());
        clearAll.setOnClickListener(v -> clearAll());

        loadApps();
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        return false;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        filterApps(newText);
        return true;
    }

    private void filterApps(String query) {
        filteredApps.clear();
        if (TextUtils.isEmpty(query)) {
            filteredApps.addAll(allApps);
        } else {
            String lower = query.toLowerCase();
            for (AppListAdapter.AppEntry app : allApps) {
                if (app.label.toLowerCase().contains(lower)
                        || app.packageName.toLowerCase().contains(lower)) {
                    filteredApps.add(app);
                }
            }
        }
        adapter.notifyDataSetChanged();
    }

    /** Select only apps for which FCM/GCM integration was actually detected. */
    private void selectDetectedFcmApps() {
        for (AppListAdapter.AppEntry app : allApps) {
            if (app.fcmDetected) {
                app.checked = true;
                allowlist.add(app.packageName);
            }
        }
        updateAllowlist();
        sortApps();
        filterApps(searchView != null ? searchView.getQuery().toString() : "");
    }

    private void clearAll() {
        allowlist.clear();
        for (AppListAdapter.AppEntry app : allApps) {
            app.checked = false;
        }
        updateAllowlist();
        sortApps();
        filterApps(searchView != null ? searchView.getQuery().toString() : "");
    }

    private void sortApps() {
        allApps.sort(MainActivity::compareEntries);
    }

    /** Checked apps first, then detected FCM apps, then alphabetically. */
    private static int compareEntries(AppListAdapter.AppEntry a, AppListAdapter.AppEntry b) {
        if (a.checked != b.checked) {
            return a.checked ? -1 : 1;
        }
        if (a.fcmDetected != b.fcmDetected) {
            return a.fcmDetected ? -1 : 1;
        }
        int c = a.label.compareToIgnoreCase(b.label);
        return c != 0 ? c : a.packageName.compareTo(b.packageName);
    }

    private boolean isSystemApp(ApplicationInfo ai) {
        return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
    }

    /**
     * Detect common Firebase/GCM integration signals. This intentionally uses
     * several signals because there is no public PackageManager API that says
     * "this app uses FCM" with perfect accuracy.
     */
    private boolean hasFcmCapability(PackageManager pm, PackageInfo pi) {
        String packageName = pi.packageName;

        if (pi.requestedPermissions != null) {
            for (String permission : pi.requestedPermissions) {
                if (C2DM_RECEIVE_PERMISSION.equals(permission)) {
                    return true;
                }
            }
        }

        int matchFlags = PackageManager.MATCH_DISABLED_COMPONENTS;

        try {
            Intent firebaseMessaging = new Intent(FIREBASE_MESSAGING_EVENT).setPackage(packageName);
            if (!pm.queryIntentServices(firebaseMessaging, matchFlags).isEmpty()) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        try {
            Intent c2dmReceive = new Intent(C2DM_RECEIVE_ACTION).setPackage(packageName);
            if (!pm.queryBroadcastReceivers(c2dmReceive, matchFlags).isEmpty()) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private void initXposedService() {
        try {
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(@NonNull XposedService service) {
                    xposedService = service;
                    runOnUiThread(() -> {
                        reloadAllowlist();
                        if (adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                    });
                }

                @Override
                public void onServiceDied(@NonNull XposedService service) {
                    if (xposedService == service) {
                        xposedService = null;
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private SharedPreferences remotePrefs() {
        if (xposedService == null) {
            return null;
        }
        try {
            return xposedService.getRemotePreferences(Prefs.GROUP_CONFIG);
        } catch (Throwable e) {
            return null;
        }
    }

    private void reloadAllowlist() {
        SharedPreferences prefs = remotePrefs();
        if (prefs == null) {
            return;
        }
        allowlist = Prefs.readAllowlist(prefs);
        for (AppListAdapter.AppEntry app : allApps) {
            app.checked = allowlist.contains(app.packageName);
        }
        sortApps();
        filterApps(searchView != null ? searchView.getQuery().toString() : "");
    }

    private void updateAllowlist() {
        SharedPreferences prefs = remotePrefs();
        if (prefs == null) {
            return;
        }
        Prefs.writeAllowlist(this, prefs, allowlist);
    }

    private void loadApps() {
        final boolean showSys = showSystemApps;
        final boolean showNonFcm = showNonFcmApps;
        final Set<String> allowSnapshot = new HashSet<>(allowlist);

        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<PackageInfo> installed = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
            List<AppListAdapter.AppEntry> result = new ArrayList<>();

            for (PackageInfo pi : installed) {
                ApplicationInfo ai = pi.applicationInfo;
                if (ai == null || ai.packageName.equals(getPackageName())) {
                    continue;
                }
                if (!showSys && isSystemApp(ai)) {
                    continue;
                }

                boolean fcmDetected = hasFcmCapability(pm, pi);
                boolean manuallyAllowed = allowSnapshot.contains(ai.packageName);

                // Default view: only detected FCM clients. Keep manual overrides
                // visible even when detection misses them, so users never lose a
                // previously configured entry.
                if (!showNonFcm && !fcmDetected && !manuallyAllowed) {
                    continue;
                }

                AppListAdapter.AppEntry entry = new AppListAdapter.AppEntry(
                        ai.packageName,
                        ai.loadLabel(pm).toString(),
                        fcmDetected);
                entry.checked = manuallyAllowed;
                result.add(entry);
            }

            result.sort(MainActivity::compareEntries);
            runOnUiThread(() -> {
                allApps.clear();
                allApps.addAll(result);
                filterApps(searchView != null ? searchView.getQuery().toString() : "");
                adapter.notifyDataSetChanged();
            });
        }).start();
    }
}
