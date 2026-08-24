package io.github.howard20181.hyperos.fcmlive;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * Settings screen: lets the user pick which apps FCM is allowed to wake /
 * auto-launch. By default only apps with detectable FCM/GCM messaging
 * components are shown; manually allowlisted apps are always kept visible as a
 * fallback.
 */
public class MainActivity extends AppCompatActivity {

    private static final String FIREBASE_MESSAGING_EVENT = "com.google.firebase.MESSAGING_EVENT";
    private static final String C2DM_RECEIVE_ACTION = "com.google.android.c2dm.intent.RECEIVE";
    private static final String C2DM_SEND_PERMISSION = "com.google.android.c2dm.permission.SEND";

    private static final String GMS_PACKAGE = "com.google.android.gms";
    private static final String GMS_DIAGNOSTICS_ACTIVITY = "com.google.android.gms.gcm.GcmDiagnostics";

    // MIUI 13 / HyperOS adds this runtime gate on top of QUERY_ALL_PACKAGES.
    // It only exists on ROMs whose permission owner is com.lbe.security.miui.
    private static final String GET_INSTALLED_APPS_PERMISSION =
            "com.android.permission.GET_INSTALLED_APPS";
    private static final String MIUI_SECURITY_PACKAGE = "com.lbe.security.miui";
    private static final int REQUEST_GET_INSTALLED_APPS = 1001;

    private final List<AppListAdapter.AppEntry> allApps = new ArrayList<>();
    private final List<AppListAdapter.AppEntry> filteredApps = new ArrayList<>();
    private Set<String> allowlist = new HashSet<>();
    private AppListAdapter adapter;
    private TextInputEditText searchInput;
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
            filterApps(currentSearchQuery());
        });
        ((android.widget.ListView) findViewById(R.id.app_list)).setAdapter(adapter);

        setupToolbar();
        setupSearch();

        // Do not query installed packages until HyperOS has had a chance to grant
        // its extra app-list permission. Non-MIUI ROMs skip this path entirely.
        if (!requestInstalledAppsPermissionIfNeeded()) {
            loadApps();
        }
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.top_app_bar);
        toolbar.setTitle(R.string.appbar_title);
        toolbar.getMenu().findItem(R.id.action_show_non_fcm_apps).setChecked(showNonFcmApps);
        toolbar.getMenu().findItem(R.id.action_show_system_apps).setChecked(showSystemApps);
        toolbar.setOnMenuItemClickListener(this::onToolbarMenuItemClick);
    }

    private boolean onToolbarMenuItemClick(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_show_non_fcm_apps) {
            showNonFcmApps = !showNonFcmApps;
            item.setChecked(showNonFcmApps);
            loadApps();
            return true;
        }
        if (id == R.id.action_show_system_apps) {
            showSystemApps = !showSystemApps;
            item.setChecked(showSystemApps);
            loadApps();
            return true;
        }
        if (id == R.id.action_select_detected) {
            selectDetectedFcmApps();
            return true;
        }
        if (id == R.id.action_clear_selection) {
            clearAll();
            return true;
        }
        if (id == R.id.action_fcm_diagnostics) {
            openFcmDiagnostics();
            return true;
        }
        return false;
    }

    /**
     * GcmDiagnostics is an internal Google Play services activity rather than a
     * public SDK contract, so opening it is intentionally best-effort.
     */
    private void openFcmDiagnostics() {
        Intent intent = new Intent();
        intent.setClassName(GMS_PACKAGE, GMS_DIAGNOSTICS_ACTIVITY);
        try {
            startActivity(intent);
        } catch (Throwable ignored) {
            Snackbar.make(
                    findViewById(android.R.id.content),
                    R.string.fcm_diagnostics_unavailable,
                    Snackbar.LENGTH_LONG)
                    .show();
        }
    }

    private void setupSearch() {
        searchInput = findViewById(R.id.search_input);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterApps(s != null ? s.toString() : "");
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private String currentSearchQuery() {
        if (searchInput == null || searchInput.getText() == null) {
            return "";
        }
        return searchInput.getText().toString();
    }

    /**
     * Returns true when a runtime permission request was launched and loading
     * should wait for onRequestPermissionsResult().
     */
    private boolean requestInstalledAppsPermissionIfNeeded() {
        PackageManager pm = getPackageManager();
        try {
            PermissionInfo permissionInfo = pm.getPermissionInfo(GET_INSTALLED_APPS_PERMISSION, 0);
            if (permissionInfo == null
                    || !MIUI_SECURITY_PACKAGE.equals(permissionInfo.packageName)) {
                return false;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // AOSP and other ROMs do not expose Xiaomi's extra permission.
            return false;
        }

        if (checkSelfPermission(GET_INSTALLED_APPS_PERMISSION)
                == PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        requestPermissions(
                new String[]{GET_INSTALLED_APPS_PERMISSION},
                REQUEST_GET_INSTALLED_APPS);
        return true;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQUEST_GET_INSTALLED_APPS) {
            return;
        }

        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            Snackbar.make(
                    findViewById(android.R.id.content),
                    R.string.installed_apps_permission_denied,
                    Snackbar.LENGTH_LONG)
                    .show();
        }

        // Even when denied, load what the ROM allows so the screen remains usable.
        loadApps();
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
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    /** Select only apps for which an FCM/GCM message entry component was detected. */
    private void selectDetectedFcmApps() {
        for (AppListAdapter.AppEntry app : allApps) {
            if (app.fcmDetected) {
                app.checked = true;
                allowlist.add(app.packageName);
            }
        }
        updateAllowlist();
        sortApps();
        filterApps(currentSearchQuery());
    }

    private void clearAll() {
        allowlist.clear();
        for (AppListAdapter.AppEntry app : allApps) {
            app.checked = false;
        }
        updateAllowlist();
        sortApps();
        filterApps(currentSearchQuery());
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
                || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
    }

    /**
     * Detect the message-entry components registered by Firebase Messaging and
     * legacy GCM clients. A bare c2dm RECEIVE permission is deliberately not a
     * positive signal: the current Firebase SDK still declares that permission
     * for compatibility with older Google Play services IID token creation.
     */
    private boolean hasFcmCapability(PackageManager pm, PackageInfo pi) {
        String packageName = pi.packageName;
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
            for (ResolveInfo receiver : pm.queryBroadcastReceivers(c2dmReceive, matchFlags)) {
                if (receiver.activityInfo != null
                        && C2DM_SEND_PERMISSION.equals(receiver.activityInfo.permission)) {
                    return true;
                }
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
        filterApps(currentSearchQuery());
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

                // Default view: only detected FCM/GCM clients. Keep manual
                // overrides visible even when detection misses them, so users
                // never lose a previously configured entry.
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
                filterApps(currentSearchQuery());
                adapter.notifyDataSetChanged();
            });
        }).start();
    }
}
