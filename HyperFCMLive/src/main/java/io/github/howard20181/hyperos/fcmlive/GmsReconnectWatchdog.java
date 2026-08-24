package io.github.howard20181.hyperos.fcmlive;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedModule;

/**
 * Conservative watchdog for Google Play services' own GCM connection timer.
 *
 * This intentionally does NOT change heartbeat intervals, reconnect backoff, or
 * timer delays. It only observes GMS's GCM_CONN_ALARM and requests a reconnect
 * when the timer's own recorded deadline is still more than one minute overdue.
 *
 * The target discovery is adapted from kooritea/fcmfix's ReconnectManagerFix,
 * but all interval overrides, diagnostics UI injection and persistent target
 * cache have been removed.
 */
public class GmsReconnectWatchdog extends XposedModule {
    private static final String TAG = "HyperGreeze";
    private static final String GMS_PACKAGE = "com.google.android.gms";
    private static final String GCM_RECONNECT_ACTION =
            "com.google.android.intent.action.GCM_RECONNECT";
    private static final String GCM_CONN_ALARM = "GCM_CONN_ALARM";

    private static final long OVERDUE_GRACE_MS = 60_000L;
    private static final long DEADLINE_WINDOW_MS = TimeUnit.DAYS.toMillis(30);

    private final AtomicLong generation = new AtomicLong();
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "FCMLive-GmsReconnectWatchdog");
                thread.setDaemon(true);
                return thread;
            });

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.isFirstPackage() || !GMS_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        try {
            hookReconnectTimer(param.getClassLoader());
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to install GMS reconnect watchdog", t);
        }
    }

    private void hookReconnectTimer(ClassLoader classLoader) throws Exception {
        Class<?> heartbeatAlarmClass = classLoader.loadClass(
                "com.google.android.gms.gcm.connection.HeartbeatChimeraAlarm");

        Class<?> timerClass = findTimerClass(heartbeatAlarmClass);
        Method setTimeout = findSetTimeoutMethod(timerClass);
        setTimeout.setAccessible(true);

        hook(setTimeout).intercept(chain -> {
            long startedAt = SystemClock.elapsedRealtime();
            long requestedDelay = chain.getArg(0) instanceof Long value ? value : 0L;

            Object result = chain.proceed();

            Object timer = chain.getThisObject();
            if (!containsAlarmType(timer, GCM_CONN_ALARM)) {
                return result;
            }

            long expectedDeadline = safeAdd(startedAt, Math.max(0L, requestedDelay));
            long recordedDeadline = findRecordedDeadline(timer, expectedDeadline);
            if (recordedDeadline <= 0L) {
                log(Log.WARN, TAG,
                        "GCM_CONN_ALARM observed but its deadline field could not be identified");
                return result;
            }

            long watchGeneration = generation.incrementAndGet();
            scheduleDeadlineCheck(timer, recordedDeadline, watchGeneration);
            return result;
        });
        deoptimize(setTimeout);

        log(Log.INFO, TAG,
                "Installed GMS reconnect watchdog on "
                        + setTimeout.getDeclaringClass().getName()
                        + "#" + setTimeout.getName());
    }

    /**
     * HeartbeatChimeraAlarm receives GMS's timer object as its fourth constructor
     * argument. Prefer the constructor with the most parameters, matching the
     * discovery strategy used by fcmfix, but verify that a fourth parameter is
     * actually present before accepting it.
     */
    private static Class<?> findTimerClass(Class<?> heartbeatAlarmClass)
            throws NoSuchMethodException {
        Constructor<?> selected = null;
        for (Constructor<?> constructor : heartbeatAlarmClass.getConstructors()) {
            if (constructor.getParameterCount() < 4) {
                continue;
            }
            if (selected == null
                    || constructor.getParameterCount() > selected.getParameterCount()) {
                selected = constructor;
            }
        }
        if (selected == null) {
            throw new NoSuchMethodException(
                    "HeartbeatChimeraAlarm constructor with timer argument");
        }

        Class<?> timerClass = selected.getParameterTypes()[3];
        if (timerClass.getDeclaredMethods().length == 0
                && timerClass.getSuperclass() != null) {
            timerClass = timerClass.getSuperclass();
        }
        return timerClass;
    }

    /** Find GMS's public final setTimeout(long) method without hard-coding names. */
    private static Method findSetTimeoutMethod(Class<?> timerClass)
            throws NoSuchMethodException {
        for (Class<?> current = timerClass;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 1
                        && params[0] == long.class
                        && Modifier.isPublic(method.getModifiers())
                        && Modifier.isFinal(method.getModifiers())) {
                    return method;
                }
            }
        }
        throw new NoSuchMethodException("GMS timer public final method(long)");
    }

    /**
     * Locate the stable alarm label by value rather than by obfuscated field
     * names. Current GMS stores the label one object deep from the timer.
     */
    private static boolean containsAlarmType(Object timer, String wanted) {
        if (timer == null) {
            return false;
        }
        if (objectContainsString(timer, wanted)) {
            return true;
        }

        for (Field field : allFields(timer.getClass())) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object nested = field.get(timer);
                if (nested != null && objectContainsString(nested, wanted)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean objectContainsString(Object object, String wanted) {
        for (Field field : allFields(object.getClass())) {
            if (field.getType() != String.class || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                if (wanted.equals(field.get(object))) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * GMS keeps an elapsedRealtime()-based absolute deadline in the timer. Pick
     * the long field closest to the expected deadline while rejecting wall-clock
     * timestamps and obviously unrelated counters.
     */
    private static long findRecordedDeadline(Object timer, long expectedDeadline) {
        long now = SystemClock.elapsedRealtime();
        long min = Math.max(0L, now - DEADLINE_WINDOW_MS);
        long max = safeAdd(now, DEADLINE_WINDOW_MS);

        long bestValue = -1L;
        long bestDistance = Long.MAX_VALUE;

        for (Field field : allFields(timer.getClass())) {
            if (field.getType() != long.class || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                long value = field.getLong(timer);
                if (value < min || value > max) {
                    continue;
                }
                long distance = absDiff(value, expectedDeadline);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestValue = value;
                }
            } catch (Throwable ignored) {
            }
        }
        return bestValue;
    }

    private void scheduleDeadlineCheck(Object timer, long recordedDeadline, long watchGeneration) {
        long now = SystemClock.elapsedRealtime();
        long delay = Math.max(OVERDUE_GRACE_MS,
                recordedDeadline - now + OVERDUE_GRACE_MS);

        executor.schedule(() -> {
            if (generation.get() != watchGeneration) {
                return; // a newer GCM_CONN_ALARM replaced this one
            }

            try {
                long currentDeadline = findRecordedDeadline(timer, recordedDeadline);
                long currentTime = SystemClock.elapsedRealtime();

                // Successful firing normally clears or advances the timer. Only
                // recover when the same timer still carries an actually stale
                // elapsedRealtime deadline for more than our grace period.
                if (currentDeadline <= 0L
                        || currentDeadline > currentTime - OVERDUE_GRACE_MS) {
                    return;
                }

                Context context = currentApplicationContext();
                if (context == null) {
                    log(Log.WARN, TAG,
                            "GCM_CONN_ALARM overdue but GMS application context is unavailable");
                    return;
                }

                context.sendBroadcast(
                        new Intent(GCM_RECONNECT_ACTION).setPackage(GMS_PACKAGE));
                log(Log.WARN, TAG,
                        "GCM_CONN_ALARM overdue by "
                                + (currentTime - currentDeadline)
                                + " ms; requested GMS reconnect");
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "GMS reconnect watchdog check failed", t);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private static Context currentApplicationContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object app = currentApplication.invoke(null);
            return app instanceof Application application ? application : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field[] allFields(Class<?> clazz) {
        java.util.ArrayList<Field> fields = new java.util.ArrayList<>();
        for (Class<?> current = clazz;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            java.util.Collections.addAll(fields, current.getDeclaredFields());
        }
        return fields.toArray(new Field[0]);
    }

    private static long safeAdd(long a, long b) {
        if (b > 0L && a > Long.MAX_VALUE - b) {
            return Long.MAX_VALUE;
        }
        if (b < 0L && a < Long.MIN_VALUE - b) {
            return Long.MIN_VALUE;
        }
        return a + b;
    }

    private static long absDiff(long a, long b) {
        if (a >= b) {
            return a - b;
        }
        return b - a;
    }
}
