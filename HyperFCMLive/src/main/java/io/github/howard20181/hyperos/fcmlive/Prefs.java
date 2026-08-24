package io.github.howard20181.hyperos.fcmlive;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Shared module configuration, stored in libxposed cross-process remote
 * preferences so both the settings UI and system_server hooks see the same
 * values.
 */
public final class Prefs {
    public static final String MODULE_PKG = "io.github.howard20181.hyperos.fcmlive";
    public static final String GROUP_CONFIG = "config";
    public static final String KEY_ALLOWLIST = "allowlist";
    public static final String KEY_KEEP_NOTIFICATIONS = "keep_notifications";

    /**
     * Historical name kept for compatibility. The broadcast now means that any
     * shared config value may have changed, not just the allowlist.
     */
    public static final String ACTION_ALLOWLIST_CHANGED = MODULE_PKG + ".ALLOWLIST_CHANGED";

    private Prefs() {
    }

    /** Package names the user allows FCM to wake / auto-launch. */
    public static Set<String> readAllowlist(SharedPreferences remotePrefs) {
        Set<String> set = remotePrefs.getStringSet(KEY_ALLOWLIST, Collections.emptySet());
        return set != null ? new HashSet<>(set) : new HashSet<>();
    }

    public static boolean readKeepNotifications(SharedPreferences remotePrefs) {
        return remotePrefs.getBoolean(KEY_KEEP_NOTIFICATIONS, false);
    }

    public static boolean writeAllowlist(Context context, SharedPreferences remotePrefs,
                                         Set<String> allowlist) {
        boolean saved = remotePrefs.edit()
                .putStringSet(KEY_ALLOWLIST, new HashSet<>(allowlist))
                .commit();
        if (saved) {
            notifyConfigChanged(context);
        }
        return saved;
    }

    public static boolean writeKeepNotifications(Context context, SharedPreferences remotePrefs,
                                                 boolean enabled) {
        boolean saved = remotePrefs.edit()
                .putBoolean(KEY_KEEP_NOTIFICATIONS, enabled)
                .commit();
        if (saved) {
            notifyConfigChanged(context);
        }
        return saved;
    }

    private static void notifyConfigChanged(Context context) {
        context.sendBroadcast(new Intent(ACTION_ALLOWLIST_CHANGED));
    }
}
