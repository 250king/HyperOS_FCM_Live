package io.github.howard20181.hyperos.fcmlive;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * Small system_server-only extras kept separate from the main Xiaomi hook set
 * while plan-b is being tested.
 *
 * Notification preservation follows the behavior of kooritea/fcmfix's
 * KeepNotification: only allowlisted apps are protected, and only the
 * package-changed bulk-cancel path is suppressed.
 */
@SuppressLint("PrivateApi")
public class SystemServerExtras extends XposedModule {
    private static final String TAG = "HyperGreeze";
    private static final String GMS_PACKAGE_NAME = "com.google.android.gms";
    private static final String MILLET_NO_RESTRICT_APP = "MILLET_NO_RESTRICT_APP";

    private volatile Set<String> allowlist = Collections.emptySet();
    private volatile boolean keepNotifications = false;
    private volatile boolean systemReady = false;
    private Context systemContext;
    private boolean receiverRegistered = false;

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        ClassLoader classLoader = param.getClassLoader();
        loadConfig();

        try {
            hookSystemReady(classLoader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook systemReady for plan-b extras", t);
        }

        try {
            hookKeepNotification(classLoader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook notification preservation", t);
        }
    }

    private void loadConfig() {
        try {
            var prefs = getRemotePreferences(Prefs.GROUP_CONFIG);
            Set<String> set = prefs.getStringSet(Prefs.KEY_ALLOWLIST, Collections.emptySet());
            allowlist = set != null ? new HashSet<>(set) : new HashSet<>();
            keepNotifications = prefs.getBoolean(Prefs.KEY_KEEP_NOTIFICATIONS, false);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to load plan-b extra config", t);
        }
    }

    private void hookSystemReady(ClassLoader classLoader) throws Exception {
        Class<?> amsClass = classLoader.loadClass("com.android.server.am.ActivityManagerService");
        Field contextField = amsClass.getDeclaredField("mContext");
        contextField.setAccessible(true);

        Method target = null;
        for (Method method : amsClass.getDeclaredMethods()) {
            if (!"systemReady".equals(method.getName())) {
                continue;
            }
            if (target == null || method.getParameterCount() > target.getParameterCount()) {
                target = method;
            }
        }
        if (target == null) {
            throw new NoSuchMethodException("ActivityManagerService#systemReady");
        }

        Method finalTarget = target;
        hook(finalTarget).intercept(chain -> {
            Object result = chain.proceed();
            try {
                Object rawContext = contextField.get(chain.getThisObject());
                if (rawContext instanceof Context context) {
                    systemContext = context;
                    systemReady = true;
                    loadConfig();
                    ensureConfigReceiver();
                    ensureMilletNoRestrictContainsGms();
                }
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "Failed to initialize plan-b extras after systemReady", t);
            }
            return result;
        });
        deoptimize(finalTarget);
    }

    private void ensureConfigReceiver() {
        if (receiverRegistered || systemContext == null) {
            return;
        }
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Prefs.ACTION_ALLOWLIST_CHANGED.equals(intent.getAction())) {
                        loadConfig();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(Prefs.ACTION_ALLOWLIST_CHANGED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                systemContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                systemContext.registerReceiver(receiver, filter);
            }
            receiverRegistered = true;
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to register plan-b config receiver", t);
        }
    }

    /**
     * The producer hook in Hooker prevents future PowerKeeper regenerations from
     * dropping GMS. This one-time write fixes an already-generated setting value
     * so `settings get system MILLET_NO_RESTRICT_APP` reflects the exemption
     * immediately after boot as well.
     */
    private void ensureMilletNoRestrictContainsGms() {
        if (systemContext == null) {
            return;
        }
        try {
            String current = Settings.System.getString(
                    systemContext.getContentResolver(), MILLET_NO_RESTRICT_APP);

            if (containsPackage(current, GMS_PACKAGE_NAME)) {
                return;
            }

            String fixed;
            if (current == null || current.trim().isEmpty()) {
                fixed = GMS_PACKAGE_NAME;
            } else {
                fixed = current.trim() + ", " + GMS_PACKAGE_NAME;
            }

            if (Settings.System.putString(
                    systemContext.getContentResolver(), MILLET_NO_RESTRICT_APP, fixed)) {
                log(Log.INFO, TAG, "Self-healed MILLET_NO_RESTRICT_APP with GMS");
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to self-heal MILLET_NO_RESTRICT_APP", t);
        }
    }

    private static boolean containsPackage(String list, String packageName) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        for (String item : list.split(",")) {
            if (packageName.equals(item.trim())) {
                return true;
            }
        }
        return false;
    }

    private void hookKeepNotification(ClassLoader classLoader) throws Exception {
        Class<?> nmsClass = classLoader.loadClass(
                "com.android.server.notification.NotificationManagerService");

        Method target = null;
        for (Method method : nmsClass.getDeclaredMethods()) {
            if (!"cancelAllNotificationsInt".equals(method.getName())) {
                continue;
            }
            if (target == null || method.getParameterCount() > target.getParameterCount()) {
                target = method;
            }
        }
        if (target == null) {
            throw new NoSuchMethodException(
                    "NotificationManagerService#cancelAllNotificationsInt");
        }

        int packageArgIndex;
        int reasonArgIndex;
        if (Build.VERSION.SDK_INT >= 30 && Build.VERSION.SDK_INT <= 33) {
            packageArgIndex = 2;
            reasonArgIndex = 8;
        } else if (Build.VERSION.SDK_INT == 34) {
            packageArgIndex = 2;
            reasonArgIndex = target.getParameterCount() == 10 ? 8 : 7;
        } else if (Build.VERSION.SDK_INT >= 35) {
            packageArgIndex = 2;
            reasonArgIndex = 7;
        } else {
            // HyperOS devices targeted by this module are newer; do not guess an
            // old NotificationManagerService signature.
            return;
        }

        if (target.getParameterCount() <= Math.max(packageArgIndex, reasonArgIndex)) {
            throw new NoSuchMethodException(
                    "Unexpected cancelAllNotificationsInt signature: "
                            + target.getParameterCount());
        }

        final int pkgIndex = packageArgIndex;
        final int reasonIndex = reasonArgIndex;
        Method finalTarget = target;

        hook(finalTarget).intercept(chain -> {
            if (systemReady
                    && keepNotifications
                    && chain.getArg(pkgIndex) instanceof String packageName
                    && allowlist.contains(packageName)
                    && chain.getArg(reasonIndex) instanceof Integer reason
                    && reason == NotificationListenerService.REASON_PACKAGE_CHANGED) {
                log(Log.INFO, TAG,
                        "Preserved notifications while stopping " + packageName);
                return null;
            }
            return chain.proceed();
        });
        deoptimize(finalTarget);
    }
}
