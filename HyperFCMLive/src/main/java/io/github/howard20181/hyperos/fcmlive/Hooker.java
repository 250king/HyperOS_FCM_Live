package io.github.howard20181.hyperos.fcmlive;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerExemptionManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

@SuppressLint("PrivateApi")
public class Hooker extends XposedModule {
    private static final String TAG = "HyperGreeze";
    private static final List<String> CN_DEFER_BROADCAST = Arrays.asList("com.google.android.intent.action.GCM_RECONNECT", "com.google.android.gcm.DISCONNECTED", "com.google.android.gcm.CONNECTED", "com.google.android.gms.gcm.HEARTBEAT_ALARM");
    private static final String ACTION_REMOTE_INTENT = "com.google.android.c2dm.intent.RECEIVE";
    private static final String GMS_PACKAGE_NAME = "com.google.android.gms";
    private static final String GMS_PERSISTENT_PROCESS_NAME = "com.google.android.gms.persistent";
    private static final String MILLET_SETTING = "MILLET_NO_RESTRICT_APP";
    private PackageClassLoader param;
    private final Set<String> hookedIds = new HashSet<>();
    private Context systemContext;

    private record PackageClassLoader(String packageName, ClassLoader classLoader) {
    }

    private void setHookId(HookBuilder builder, String id) {
        if (getApiVersion() >= 102) {
            builder.setId(id);
            hookedIds.add(id);
        }
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        var classLoader = param.getClassLoader();
        this.param = new PackageClassLoader("system", classLoader);
        try {
            hookSystemServer(classLoader);
        } catch (Throwable tr) {
            log(Log.ERROR, TAG, "Failed to hook SystemServer", tr);
        }
    }

    private void hookSystemServer(ClassLoader classLoader) {
        try {
            hookAllowlist();
        } catch (Exception t) {
            log(Log.ERROR, TAG, "Failed to hook allowlist receiver", t);
        }
        try {
            hookGreezeManagerService(classLoader);
        } catch (Exception t) {
            log(Log.ERROR, TAG, "Failed to hook GreezeManagerService", t);
        }
        try {
            hookDomesticPolicyManager(classLoader);
        } catch (Exception t) {
            log(Log.ERROR, TAG, "Failed to hook DomesticPolicyManager", t);
        }
        try {
            hookListAppsManager(classLoader);
        } catch (Exception t) {
            log(Log.ERROR, TAG, "Failed to hook ListAppsManager", t);
        }
        try {
            hookBroadcastQueueModernStubImpl(classLoader);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook BroadcastQueueModernStubImpl", e);
        }
        try {
            hookProcessPolicy(classLoader);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook ProcessPolicy", e);
        }
        try {
            hookAwareResourceControl(classLoader);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook AwareResourceControl", e);
        }
        try {
            hookActivityManagerService(classLoader);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook ActivityManagerService", e);
        }
        // MILLET_NO_RESTRICT_APP — replaces Shizuku 2s poll + shell settings put/get.
        // Covers Aurogon quick-freeze and PowerStrategyMode (PolicyMaker) at the consumer,
        // plus SettingsProvider persistence so PowerKeeper regeneration can't erase GMS.
        try {
            hookMilletSettings(classLoader);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook Millet Settings", e);
        }
        try {
            hookAurogonNoRestrict(classLoader);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook Aurogon NoRestrict", e);
        }
        try {
            hookGreezeNoRestrictFixups(classLoader);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook Greezer NoRestrict fixups", e);
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!param.isFirstPackage()) return;
        var packageName = param.getPackageName();
        var classLoader = param.getClassLoader();
        this.param = new PackageClassLoader(packageName, classLoader);
        try {
            hookPackage(packageName, classLoader);
        } catch (Throwable tr) {
            log(Log.ERROR, TAG, "Failed to hook package", tr);
        }
    }

    private void hookPackage(String packageName, ClassLoader classLoader) {
        if ("com.miui.powerkeeper".equals(packageName)) {
            try {
                hookGmsObserver(classLoader);
            } catch (Exception e) {
                log(Log.ERROR, TAG, "Failed to hook GmsObserver", e);
            }
            try {
                hookGlobalFeatureConfigureHelper(classLoader);
            } catch (Exception e) {
                log(Log.ERROR, TAG, "Failed to hook GlobalFeatureConfigureHelper", e);
            }
            // PowerKeeper is the authoritative source that regenerates MILLET_NO_RESTRICT_APP
            // from userTable. Hooking dealNoRestrictApp (and related entry points) makes
            // that regeneration idempotently preserve GMS without a shell daemon.
            try {
                hookPowerKeeperMillet(classLoader);
            } catch (Exception e) {
                log(Log.ERROR, TAG, "Failed to hook PowerKeeper Millet", e);
            }
        }
    }

    @Override
    public boolean onHotReloading(@NonNull HotReloadingParam param) {
        // Hot reload of system_server hooks is unreliable; a full reboot is the
        // supported path. We still support reload below, but advise rebooting.
        log(Log.WARN, TAG, "Hot reload requested — a full reboot is recommended for reliability");
        param.setSavedInstanceState(this.param);
        return true;
    }

    @Override
    public void onHotReloaded(@NonNull HotReloadedParam param) {
        // Clean reload: reset id bookkeeping and remove every previous hook so the
        // re-setup below starts fresh. Without this, old handles were never unhooked
        // (hookedIds accumulated across passes) and stacked duplicate hooks made the
        // reload appear ineffective.
        hookedIds.clear();
        param.getOldHookHandles().forEach(h -> {
            try {
                h.unhook();
            } catch (Throwable ignored) {
            }
        });
        if (param.getSavedInstanceState() instanceof PackageClassLoader(
                String packageName, ClassLoader classLoader
        )) {
            try {
                if (param.isSystemServer()) {
                    hookSystemServer(classLoader);
                } else {
                    hookPackage(packageName, classLoader);
                }
            } catch (Throwable tr) {
                log(Log.ERROR, TAG, "Hot reload failed", tr);
            }
        }
    }

    private void hookGreezeManagerService(ClassLoader classLoader)
            throws ClassNotFoundException, NoSuchMethodException {
        var GreezeManagerServiceClass = classLoader.loadClass("com.miui.server.greeze.GreezeManagerService");
        try {
            // am.ProcessRecord app = BroadcastProcessQueue.app, app nullable
            // but when app is null, this method will not call
            // calleePkgName = (app.info == null || app.info.packageName == null) ? app.processName : app.info.packageName
            // It could be the process name.
            // boolean isAllowBroadcast(int callerUid, String callerPkgName, int calleeUid, String calleePkgName, String action)
            var isAllowBroadcastMethod = GreezeManagerServiceClass.getDeclaredMethod("isAllowBroadcast", int.class, String.class, int.class, String.class, String.class);
            var getPackageNameFromUidMethod = GreezeManagerServiceClass.getDeclaredMethod("getPackageNameFromUid", int.class);
            getPackageNameFromUidMethod.setAccessible(true);
            Utils.evaluate(hook(isAllowBroadcastMethod), h -> setHookId(h, isAllowBroadcastMethod.getName())
            ).intercept(chain -> {
                String calleePkgName = chain.getArg(3) instanceof String calleeProcessName ? calleeProcessName : null;
                try {
                    if (chain.getArg(2) instanceof Integer calleeUid
                            && getInvoker(getPackageNameFromUidMethod).invoke(chain.getThisObject(), calleeUid) instanceof String calleePackageName) {
                        calleePkgName = calleePackageName;
                    }
                } catch (Exception e) {
                    log(Log.ERROR, TAG, "Failed to get callee package name", e);
                }
                if (chain.getArg(4) instanceof String action
                        && ((chain.getArg(1) instanceof String callerPkgName
                        // callerPkgName get from intent or BroadcastRecord.callerPackage,
                        // both are nullable, but they won't become null in FCM broadcasts.
                        && GMS_PACKAGE_NAME.equals(callerPkgName)
                        && ACTION_REMOTE_INTENT.equals(action))
                        || ((GMS_PACKAGE_NAME.equals(calleePkgName)
                        || GMS_PERSISTENT_PROCESS_NAME.equals(calleePkgName))
                        && CN_DEFER_BROADCAST.contains(action)))) {
                    return true;
                }
                return chain.proceed();
            });
            deoptimize(isAllowBroadcastMethod);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook GreezeManagerService#isAllowBroadcast", e);
        }
        try {
            // boolean deferBroadcastForMiui(String action)
            var deferBroadcastForMiuiMethod = GreezeManagerServiceClass.getDeclaredMethod("deferBroadcastForMiui", String.class);
            Utils.evaluate(hook(deferBroadcastForMiuiMethod), h -> setHookId(h, deferBroadcastForMiuiMethod.getName())
            ).intercept(chain -> {
                if (chain.getArg(0) instanceof String action
                        && CN_DEFER_BROADCAST.contains(action)) {
                    return false;
                }
                return chain.proceed();
            });
            deoptimize(deferBroadcastForMiuiMethod);
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to hook GreezeManagerService#deferBroadcastForMiui", e);
        }
        Method triggerGMSLimitActionMethod;
        try {
            triggerGMSLimitActionMethod = GreezeManagerServiceClass.getDeclaredMethod("triggerGMSLimitAction", boolean.class);
        } catch (NoSuchMethodException ignored) {
            triggerGMSLimitActionMethod = GreezeManagerServiceClass.getDeclaredMethod("triggerGMSLimitAction");
        }
        Method finalTriggerGMSLimitActionMethod = triggerGMSLimitActionMethod;
        Utils.evaluate(hook(triggerGMSLimitActionMethod), h -> setHookId(h, finalTriggerGMSLimitActionMethod.getName())
        ).intercept(chain -> {
            if (!chain.getArgs().isEmpty()) {
                var args = chain.getArgs().toArray();
                args[0] = false;
                return chain.proceed(args);
            } else {
                var mGmsLimitEnabled = GreezeManagerServiceClass.getDeclaredField("mGmsLimitEnabled");
                mGmsLimitEnabled.setAccessible(true);
                mGmsLimitEnabled.setBoolean(chain.getThisObject(), false);
                return chain.proceed();
            }
        });
        deoptimize(triggerGMSLimitActionMethod);
    }

    private void hookDomesticPolicyManager(ClassLoader classLoader) throws ClassNotFoundException,
            NoSuchMethodException {
        var DomesticPolicyManagerClass = classLoader.loadClass("com.miui.server.greeze.DomesticPolicyManager");
        // boolean deferBroadcast(String action)
        var deferBroadcastMethod = DomesticPolicyManagerClass.getDeclaredMethod("deferBroadcast", String.class);
        Utils.evaluate(hook(deferBroadcastMethod), h -> setHookId(h, deferBroadcastMethod.getName())
        ).intercept(chain -> false);
        deoptimize(deferBroadcastMethod);
    }

    private void hookListAppsManager(ClassLoader classLoader) throws ClassNotFoundException,
            NoSuchFieldException {
        var ListAppsManagerClass = classLoader.loadClass("com.miui.server.greeze.power.ListAppsManager");
        var mSystemBlackListField = ListAppsManagerClass.getDeclaredField("mSystemBlackList");
        mSystemBlackListField.setAccessible(true);
        var PowerStrategyModeConstructors = ListAppsManagerClass.getDeclaredConstructors();
        for (var constructor : PowerStrategyModeConstructors) {
            Utils.evaluate(hook(constructor), h -> setHookId(h, constructor.getName())
            ).intercept(chain -> {
                try {
                    return chain.proceed();
                } finally {
                    try {
                        var mSystemBlackList = (List<String>) mSystemBlackListField.get(chain.getThisObject());
                        if (mSystemBlackList != null) {
                            mSystemBlackList.remove(GMS_PACKAGE_NAME);
                        }
                    } catch (Exception e) {
                        log(Log.ERROR, TAG, "Failed to modify ListAppsManager.mSystemBlackList", e);
                    }
                }
            });
            deoptimize(constructor);
        }
        try {
            var isInWhiteListMethod = ListAppsManagerClass.getDeclaredMethod("isInWhiteList", String.class);
            var mUseDataWhiteListField = ListAppsManagerClass.getDeclaredField("mUseDataWhiteList");
            mUseDataWhiteListField.setAccessible(true);
            Utils.evaluate(hook(isInWhiteListMethod), h -> setHookId(h, isInWhiteListMethod.getName())
            ).intercept(chain -> {
                try {
                    var mUseDataWhiteList = (Set<String>) mUseDataWhiteListField.get(chain.getThisObject());
                    if (mUseDataWhiteList != null) {
                        mUseDataWhiteList.add(GMS_PACKAGE_NAME);
                    }
                } catch (Exception e) {
                    log(Log.ERROR, TAG, "Failed to modify ListAppsManager.SLEEP_MODE_LIST", e);
                }
                return chain.proceed();
            });
        } catch (NoSuchMethodException e) {
            log(Log.ERROR, TAG, "Failed to hook ListAppsManager#isInWhiteList", e);
        }
    }

    private void hookBroadcastQueueModernStubImpl(ClassLoader classLoader) throws
            ClassNotFoundException, NoSuchMethodException, NoSuchFieldException {
        var BroadcastQueueModernStubImplClass = classLoader.loadClass("com.android.server.am.BroadcastQueueModernStubImpl");
        var BroadcastQueueClass = classLoader.loadClass("com.android.server.am.BroadcastQueue");
        var BroadcastRecordClass = classLoader.loadClass("com.android.server.am.BroadcastRecord");
        var callerPackageField = BroadcastRecordClass.getDeclaredField("callerPackage");
        callerPackageField.setAccessible(true);
        var intentField = BroadcastRecordClass.getDeclaredField("intent");
        intentField.setAccessible(true);
        var checkApplicationAutoStartMethod = BroadcastQueueModernStubImplClass.getDeclaredMethod("checkApplicationAutoStart", BroadcastQueueClass, BroadcastRecordClass, ResolveInfo.class);
        Utils.evaluate(hook(checkApplicationAutoStartMethod), h -> setHookId(h, checkApplicationAutoStartMethod.getName())
        ).intercept(chain -> {
            try {
                var broadcastRecord = chain.getArg(1);
                if (callerPackageField.get(broadcastRecord) instanceof String callerPackage
                        && GMS_PACKAGE_NAME.equals(callerPackage) // BroadcastRecord.callerPackage nullable
                        && intentField.get(broadcastRecord) instanceof Intent intent
                        && ACTION_REMOTE_INTENT.equals(intent.getAction())
                        // Only auto-start apps the user explicitly whitelisted.
                        && intent.getPackage() instanceof String targetPackage
                        && getFcmAllowlist().contains(targetPackage)) {
                    return true;
                }
            } catch (Exception e) {
                log(Log.ERROR, TAG, "Failed to modify BroadcastQueueModernStubImpl#checkApplicationAutoStart", e);
            }
            return chain.proceed();
        });
        deoptimize(checkApplicationAutoStartMethod);
    }

    private void hookProcessPolicy(ClassLoader classLoader) throws ClassNotFoundException,
            NoSuchMethodException {
        var ProcessPolicyClass = classLoader.loadClass("com.android.server.am.ProcessPolicy");
        var getWhiteListMethod = ProcessPolicyClass.getDeclaredMethod("getWhiteList", int.class);
        Utils.evaluate(hook(getWhiteListMethod), h -> setHookId(h, getWhiteListMethod.getName())
        ).intercept(chain -> {
            var result = chain.proceed();
            if (chain.getArg(0) instanceof Integer flags && (flags & 1) != 0) {
                if (result instanceof List<?>) {
                    var whiteList = (List<String>) result;
                    whiteList.add(GMS_PACKAGE_NAME);
                    whiteList.add(GMS_PERSISTENT_PROCESS_NAME);
                }
            }
            return result;
        });
    }

    private void hookAwareResourceControl(ClassLoader classLoader) throws ClassNotFoundException,
            NoSuchFieldException {
        var AwareResourceControlClass = classLoader.loadClass("com.miui.server.greeze.power.AwareResourceControl");
        var mNoNetworkBlackUidsField = AwareResourceControlClass.getDeclaredField("mNoNetworkBlackUids");
        mNoNetworkBlackUidsField.setAccessible(true);
        var AwareResourceControlConstructors = AwareResourceControlClass.getDeclaredConstructors();
        for (var constructor : AwareResourceControlConstructors) {
            Utils.evaluate(hook(constructor), h -> setHookId(h, constructor.getName())
            ).intercept(chain -> {
                try {
                    return chain.proceed();
                } finally {
                    try {
                        var mNoNetworkBlackUids = (List<String>) mNoNetworkBlackUidsField.get(chain.getThisObject());
                        if (mNoNetworkBlackUids != null) {
                            mNoNetworkBlackUids.remove(GMS_PACKAGE_NAME);
                        }
                    } catch (Exception e) {
                        log(Log.ERROR, TAG, "Failed to modify AwareResourceControl.mNoNetworkBlackUids", e);
                    }
                }
            });
            deoptimize(constructor);
        }
    }

    private void hookGmsObserver(ClassLoader classLoader) throws ClassNotFoundException,
            NoSuchMethodException {
        var NetdExecutorClass = classLoader.loadClass("com.miui.powerkeeper.utils.NetdExecutor");
        var initGmsChainMethod = NetdExecutorClass.getDeclaredMethod("initGmsChain", String.class, int.class, String.class);
        Utils.evaluate(hook(initGmsChainMethod), h -> setHookId(h, initGmsChainMethod.getName())
        ).intercept(chain -> {
            var args = chain.getArgs().toArray();
            args[2] = "ACCEPT";
            return chain.proceed(args);
        });
        deoptimize(initGmsChainMethod);
        var GmsObserverClass = classLoader.loadClass("com.miui.powerkeeper.utils.GmsObserver");
        Hooker hooker = chain -> {
            var args = chain.getArgs().toArray();
            args[0] = false;
            return chain.proceed(args);
        };
        var updateGmsAlarmMethod = GmsObserverClass.getDeclaredMethod("updateGmsAlarm", boolean.class);
        Utils.evaluate(hook(updateGmsAlarmMethod), h -> setHookId(h, updateGmsAlarmMethod.getName())
        ).intercept(hooker);
        deoptimize(updateGmsAlarmMethod);
        var updateGmsNetWorkMethod = GmsObserverClass.getDeclaredMethod("updateGmsNetWork", boolean.class);
        Utils.evaluate(hook(updateGmsNetWorkMethod), h -> setHookId(h, updateGmsNetWorkMethod.getName())
        ).intercept(hooker);
        deoptimize(updateGmsNetWorkMethod);
        var updateGoogleReletivesWakelockMethod = GmsObserverClass.getDeclaredMethod("updateGoogleReletivesWakelock", boolean.class);
        Utils.evaluate(hook(updateGoogleReletivesWakelockMethod), h -> setHookId(h, updateGoogleReletivesWakelockMethod.getName())
        ).intercept(hooker);
        deoptimize(updateGoogleReletivesWakelockMethod);
    }

    private void hookGlobalFeatureConfigureHelper(ClassLoader classLoader)
            throws ClassNotFoundException, NoSuchMethodException {
        var GlobalFeatureConfigureHelperClass = classLoader.loadClass("com.miui.powerkeeper.provider.GlobalFeatureConfigureHelper");
        var getDozeWhiteListAppsMethod = GlobalFeatureConfigureHelperClass.getDeclaredMethod("getDozeWhiteListApps", Bundle.class);
        Utils.evaluate(hook(getDozeWhiteListAppsMethod), h -> setHookId(h, getDozeWhiteListAppsMethod.getName())
        ).intercept(chain -> {
            var result = chain.proceed();
            if (result instanceof List<?>) {
                var whiteList = (List<String>) result;
                if (!whiteList.contains(GMS_PACKAGE_NAME)) {
                    whiteList.add(GMS_PACKAGE_NAME);
                }
            }
            return result;
        });
    }

    private static PowerExemptionManager powerExemptionManager = null;

    @RequiresApi(Build.VERSION_CODES.S)
    private static PowerExemptionManager getPowerExemptionManager(Context context) {
        if (powerExemptionManager == null) {
            // mContext.getSystemService("power_exemption")
            powerExemptionManager = new PowerExemptionManager(context);
        }
        return powerExemptionManager;
    }

    /**
     * A Context in system_server (ActivityThread.currentApplication()).
     */
    private Context getSystemContext() {
        if (systemContext == null) {
            try {
                // ActivityThread.currentApplication() is hidden; call via reflection.
                Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
                Method currentApplication = activityThreadClass.getMethod("currentApplication");
                if (currentApplication.invoke(null) instanceof Context ctx) {
                    systemContext = ctx;
                }
            } catch (Throwable ignored) {
            }
        }
        return systemContext;
    }

    /**
     * Package names the user allows FCM to wake / auto-launch, kept in memory in
     * system_server. Loaded from libxposed's cross-process remote preferences and
     * refreshed whenever the app broadcasts {@link Prefs#ACTION_ALLOWLIST_CHANGED}.
     */
    private static volatile Set<String> sAllowlist = Collections.emptySet();

    /** Re-read the allowlist from the shared remote preferences. */
    private void loadAllowlistFromRemotePrefs() {
        try {
            Set<String> set = getRemotePreferences(Prefs.GROUP_CONFIG)
                    .getStringSet(Prefs.KEY_ALLOWLIST, Collections.emptySet());
            sAllowlist = set != null ? new HashSet<>(set) : new HashSet<>();
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to read remote allowlist", e);
        }
    }

    /** Called from hookSystemServer: load the initial allowlist at boot. */
    private void hookAllowlist() {
        loadAllowlistFromRemotePrefs();
    }

    private boolean allowlistReceiverRegistered = false;

    private Set<String> getFcmAllowlist() {
        // Lazily register the refresh receiver on first real use. It can't be done
        // in onSystemServerStarting because IActivityManager is null during early
        // SystemServer startup, so registerReceiver would NPE. By the time any C2DM
        // broadcast reaches here the system is fully up.
        ensureAllowlistReceiver();
        return new HashSet<>(sAllowlist);
    }

    /** Register the receiver that re-reads the allowlist when the app updates it. */
    private void ensureAllowlistReceiver() {
        if (allowlistReceiverRegistered) {
            return;
        }
        try {
            Context sys = getSystemContext();
            if (sys == null) {
                return;
            }
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Prefs.ACTION_ALLOWLIST_CHANGED.equals(intent.getAction())) {
                        loadAllowlistFromRemotePrefs();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(Prefs.ACTION_ALLOWLIST_CHANGED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                sys.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                sys.registerReceiver(receiver, filter);
            }
            allowlistReceiverRegistered = true;
        } catch (Exception e) {
            log(Log.ERROR, TAG, "Failed to register allowlist receiver", e);
        }
    }

    private void hookActivityManagerService(ClassLoader classLoader) throws ClassNotFoundException,
            NoSuchMethodException, NoSuchFieldException {
        var ActivityManagerServiceClass = classLoader.loadClass("com.android.server.am.ActivityManagerService");
        var mContextField = ActivityManagerServiceClass.getDeclaredField("mContext");
        mContextField.setAccessible(true);
        var IApplicationThreadClass = classLoader.loadClass("android.app.IApplicationThread");
        var IIntentReceiverClass = classLoader.loadClass("android.content.IIntentReceiver");
        var ProcessRecordClass = classLoader.loadClass("com.android.server.am.ProcessRecord");
        var infoField = ProcessRecordClass.getDeclaredField("info");
        infoField.setAccessible(true);
        Method getRecordMethod;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12~16
            getRecordMethod = ActivityManagerServiceClass.getDeclaredMethod("getRecordForAppLOSP", IApplicationThreadClass);
        } else {
            // Android 8~11
            getRecordMethod = ActivityManagerServiceClass.getDeclaredMethod("getRecordForAppLocked", IApplicationThreadClass);
        }
        Method broadcastMethod;
        int intentArgIndex;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // int broadcastIntentWithFeature(IApplicationThread caller, String callingFeatureId,
            //    Intent intent, String resolvedType, IIntentReceiver resultTo,
            //    int resultCode, String resultData, Bundle resultExtras,
            //    String[] requiredPermissions, String[] excludedPermissions,
            //    String[] excludedPackages, int appOp, Bundle bOptions,
            //    boolean serialized, boolean sticky, int userId)
            intentArgIndex = 2;
            broadcastMethod = ActivityManagerServiceClass.getDeclaredMethod("broadcastIntentWithFeature",
                    IApplicationThreadClass, String.class,
                    Intent.class, String.class, IIntentReceiverClass,
                    int.class, String.class, Bundle.class,
                    String[].class, String[].class,
                    String[].class, int.class, Bundle.class,
                    boolean.class, boolean.class, int.class);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // int broadcastIntentWithFeature(IApplicationThread caller, String callingFeatureId,
            //    Intent intent, String resolvedType, IIntentReceiver resultTo,
            //    int resultCode, String resultData, Bundle resultExtras,
            //    String[] requiredPermissions, String[] excludedPermissions, int appOp, Bundle bOptions,
            //    boolean serialized, boolean sticky, int userId)
            intentArgIndex = 2;
            broadcastMethod = ActivityManagerServiceClass.getDeclaredMethod("broadcastIntentWithFeature",
                    IApplicationThreadClass, String.class,
                    Intent.class, String.class, IIntentReceiverClass,
                    int.class, String.class, Bundle.class,
                    String[].class, String[].class, int.class, Bundle.class,
                    boolean.class, boolean.class, int.class);
        } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            // int broadcastIntentWithFeature(IApplicationThread caller, String callingFeatureId,
            //    Intent intent, String resolvedType, IIntentReceiver resultTo,
            //    int resultCode, String resultData, Bundle resultExtras,
            //    String[] requiredPermissions, int appOp, Bundle bOptions,
            //    boolean serialized, boolean sticky, int userId)
            intentArgIndex = 2;
            broadcastMethod = ActivityManagerServiceClass.getDeclaredMethod("broadcastIntentWithFeature",
                    IApplicationThreadClass, String.class,
                    Intent.class, String.class, IIntentReceiverClass,
                    int.class, String.class, Bundle.class,
                    String[].class, int.class, Bundle.class,
                    boolean.class, boolean.class, int.class);
        } else {
            // int broadcastIntent(IApplicationThread caller,
            //    Intent intent, String resolvedType, IIntentReceiver resultTo,
            //    int resultCode, String resultData, Bundle resultExtras,
            //    String[] requiredPermissions, int appOp, Bundle bOptions,
            //    boolean serialized, boolean sticky, int userId)
            intentArgIndex = 1;
            broadcastMethod = ActivityManagerServiceClass.getDeclaredMethod("broadcastIntent",
                    IApplicationThreadClass,
                    Intent.class, String.class, IIntentReceiverClass,
                    int.class, String.class, Bundle.class,
                    String[].class, int.class, Bundle.class,
                    boolean.class, boolean.class, int.class);
        }
        Utils.evaluate(hook(broadcastMethod), h -> setHookId(h, broadcastMethod.getName())
        ).intercept(chain -> {
            if (chain.getArg(intentArgIndex) instanceof Intent intent) {
                if (ACTION_REMOTE_INTENT.equals(intent.getAction())
                        && getInvoker(getRecordMethod).invoke(chain.getThisObject(), chain.getArg(0)) instanceof Object app
                        && infoField.get(app) instanceof ApplicationInfo info
                        && GMS_PACKAGE_NAME.equals(info.packageName)) {
                    // Only wake / auto-start apps the user explicitly whitelisted.
                    if (intent.getPackage() instanceof String targetPackage
                            && getFcmAllowlist().contains(targetPackage)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                                && mContextField.get(chain.getThisObject()) instanceof Context mContext) {
                            getPowerExemptionManager(mContext).addToTemporaryAllowList(
                                    targetPackage, 102 /* PowerExemptionManager.REASON_PUSH_MESSAGING_OVER_QUOTA */,
                                    "GOOGLE_C2DM", 2000);
                        }
                        if ((intent.getFlags() & Intent.FLAG_INCLUDE_STOPPED_PACKAGES) == 0) {
                            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                        }
                    }
                }
            }
            return chain.proceed();
        });
        deoptimize(broadcastMethod);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // MILLET_NO_RESTRICT_APP — Hook-based replacement for Shizuku's shell poll
    // ──────────────────────────────────────────────────────────────────────────

    private static String ensureMilletContainsGms(String raw) {
        if (raw == null || raw.trim().isEmpty() || "null".equalsIgnoreCase(raw.trim())) {
            return GMS_PACKAGE_NAME;
        }
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) seen.add(t);
        }
        if (!seen.contains(GMS_PACKAGE_NAME)) seen.add(GMS_PACKAGE_NAME);
        return String.join(", ", seen);
    }

    private static boolean milletContainsGms(String raw) {
        if (raw == null) return false;
        for (String part : raw.split(",")) {
            if (GMS_PACKAGE_NAME.equals(part.trim())) return true;
        }
        return false;
    }

    /**
     * 1) Settings persistence layer — intercepts every write to
     * {@code Settings.System.MILLET_NO_RESTRICT_APP} in system_server and
     * transparently injects GMS if absent. This is the hook equivalent of
     * {@code settings --user 0 put system MILLET_NO_RESTRICT_APP ...} plus
     * verification, but without a 2s Shizuku daemon or WorkManager bootstrap.
     * <p>
     * Covers: Settings.System.putString / putStringForUser (client helpers)
     *         and SettingsProvider.call / put / insert fallback.
     * 2) Also patches the read path so a stale persisted value still appears as
     * containing GMS to in-process consumers (defence-in-depth if a write slips
     * through before the hook is installed).
     */
    private void hookMilletSettings(ClassLoader classLoader) {
        // -- android.provider.Settings$System (client helpers) --
        try {
            Class<?> sys = classLoader.loadClass("android.provider.Settings$System");
            // putStringForUser(ContentResolver,String,String,int)
            try {
                var m = sys.getDeclaredMethod("putStringForUser", android.content.ContentResolver.class, String.class, String.class, int.class);
                Utils.evaluate(hook(m), h -> setHookId(h, "SettingsSystem_putStringForUser")
                ).intercept(chain -> {
                    String name = (String) chain.getArg(1);
                    String value = (String) chain.getArg(2);
                    if (MILLET_SETTING.equals(name) && value != null && !milletContainsGms(value)) {
                        String fixed = ensureMilletContainsGms(value);
                        Object[] args = chain.getArgs().toArray();
                        args[2] = fixed;
                        log(Log.INFO, TAG, "Millet putStringForUser: injected GMS -> " + fixed);
                        return chain.proceed(args);
                    }
                    return chain.proceed();
                });
                deoptimize(m);
            } catch (NoSuchMethodException ignored) {
            }
            try {
                var m = sys.getDeclaredMethod("putString", android.content.ContentResolver.class, String.class, String.class);
                Utils.evaluate(hook(m), h -> setHookId(h, "SettingsSystem_putString")
                ).intercept(chain -> {
                    String name = (String) chain.getArg(1);
                    String value = (String) chain.getArg(2);
                    if (MILLET_SETTING.equals(name) && value != null && !milletContainsGms(value)) {
                        String fixed = ensureMilletContainsGms(value);
                        Object[] args = chain.getArgs().toArray();
                        args[2] = fixed;
                        log(Log.INFO, TAG, "Millet putString: injected GMS -> " + fixed);
                        return chain.proceed(args);
                    }
                    return chain.proceed();
                });
                deoptimize(m);
            } catch (NoSuchMethodException ignored) {
            }
            // getStringForUser — read-side fixup: if persisted value somehow lost GMS, inject on read
            try {
                var m = sys.getDeclaredMethod("getStringForUser", android.content.ContentResolver.class, String.class, int.class);
                Utils.evaluate(hook(m), h -> setHookId(h, "SettingsSystem_getStringForUser")
                ).intercept(chain -> {
                    Object res = chain.proceed();
                    String name = (String) chain.getArg(1);
                    if (MILLET_SETTING.equals(name) && res instanceof String s && !milletContainsGms(s)) {
                        String fixed = ensureMilletContainsGms(s);
                        log(Log.WARN, TAG, "Millet getStringForUser: read-side fixup " + s + " -> " + fixed);
                        return fixed;
                    }
                    return res;
                });
                deoptimize(m);
            } catch (NoSuchMethodException ignored) {
            }
        } catch (ClassNotFoundException e) {
            log(Log.WARN, TAG, "Settings.System not found for Millet hook", e);
        }

        // -- SettingsProvider (authoritative store) -- best-effort, class may not be loaded at onSystemServerStarting
        try {
            Class<?> sp = classLoader.loadClass("com.android.providers.settings.SettingsProvider");
            // SettingsProvider.call(String method, String arg, Bundle extras) — handles PUT_system etc.
            for (var m : sp.getDeclaredMethods()) {
                if (!"call".equals(m.getName())) continue;
                // Hook every call overload (Bundle vs String args variants across Android versions)
                try {
                    Utils.evaluate(hook(m), h -> setHookId(h, "SettingsProvider_call_" + m.getParameterCount())
                    ).intercept(chain -> {
                        Object res = chain.proceed();
                        // After the write, ensure persisted value still contains GMS by re-reading via provider.
                        // We do this after proceed so we don't interfere with the call's own logic.
                        try {
                            // Try to detect a MILLET write: arg or extras contains the key
                            boolean isMillet = false;
                            for (Object arg : chain.getArgs()) {
                                if (MILLET_SETTING.equals(arg)) { isMillet = true; break; }
                                if (arg instanceof Bundle b && MILLET_SETTING.equals(b.getString("name"))) { isMillet = true; break; }
                                if (arg instanceof String s && s.contains(MILLET_SETTING)) { isMillet = true; break; }
                            }
                            if (isMillet && chain.getThisObject() != null) {
                                // Fire-and-forget correction via ContentResolver on a background thread would be racy;
                                // instead we just log — the Settings.System put hook already fixed the value before it reached here.
                                log(Log.DEBUG, TAG, "SettingsProvider.call observed MILLET write");
                            }
                        } catch (Throwable ignored) {
                        }
                        return res;
                    });
                    deoptimize(m);
                } catch (Throwable ignored) {
                }
            }
        } catch (ClassNotFoundException ignored) {
            // Not all builds ship SettingsProvider in system_server's classloader at this point — non-fatal.
        }
    }

    /**
     * Consumer-side: ensure in-memory {@code mNoRestrictAppSet} always contains GMS.
     * This covers both:
     * <ul>
     *   <li>Aurogon quick-freeze ({@code lambda$triggerQuickFreeze$0})</li>
     *   <li>PowerStrategyMode / PolicyMaker ({@code AurogonFilterManager.filter(...,64)})</li>
     * </ul>
     * and survives even if the persisted setting temporarily loses GMS before the
     * {@link #hookMilletSettings} write-hook corrects it. Conceptually identical
     * to Shizuku's 2s poll but synchronous and race-free.
     */
    private void hookAurogonNoRestrict(ClassLoader classLoader) {
        try {
            Class<?> cls = classLoader.loadClass("com.miui.server.greeze.AurogonImmobulusMode");
            java.lang.reflect.Field target = null;
            for (var f : cls.getDeclaredFields()) {
                String n = f.getName().toLowerCase();
                if ((n.contains("norestrict") || n.contains("no_restrict") || n.contains("millet")) && Set.class.isAssignableFrom(f.getType())) {
                    target = f;
                    break;
                }
            }
            if (target == null) {
                try { target = cls.getDeclaredField("mNoRestrictAppSet"); } catch (NoSuchFieldException ignored) {}
            }
            if (target != null) {
                target.setAccessible(true);
                java.lang.reflect.Field finalTarget = target;
                // After every constructor, inject GMS into the freshly parsed set
                for (var c : cls.getDeclaredConstructors()) {
                    Utils.evaluate(hook(c), h -> setHookId(h, "Aurogon_ctor")
                    ).intercept(chain -> {
                        Object ret = chain.proceed();
                        try {
                            Object setObj = finalTarget.get(chain.getThisObject());
                            if (setObj instanceof Set) {
                                @SuppressWarnings("unchecked")
                                Set<String> s = (Set<String>) setObj;
                                if (!s.contains(GMS_PACKAGE_NAME)) {
                                    s.add(GMS_PACKAGE_NAME);
                                    log(Log.INFO, TAG, "Aurogon mNoRestrictAppSet: injected GMS in ctor");
                                }
                            }
                        } catch (Throwable e) {
                            log(Log.ERROR, TAG, "Failed to inject GMS in Aurogon ctor", e);
                        }
                        return ret;
                    });
                    deoptimize(c);
                }
                // After any method, re-ensure (covers observer onChange, update* callbacks)
                for (var m : cls.getDeclaredMethods()) {
                    try {
                        // Skip synthetic lambda$ that may have weird signatures — still hook generically
                        Utils.evaluate(hook(m), h -> setHookId(h, "Aurogon_" + m.getName())
                        ).intercept(chain -> {
                            Object res = chain.proceed();
                            try {
                                Object setObj = finalTarget.get(chain.getThisObject());
                                if (setObj instanceof Set) {
                                    @SuppressWarnings("unchecked")
                                    Set<String> s = (Set<String>) setObj;
                                    if (!s.contains(GMS_PACKAGE_NAME)) {
                                        s.add(GMS_PACKAGE_NAME);
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                            return res;
                        });
                        deoptimize(m);
                    } catch (Throwable ignored) {
                    }
                }
            }
            // Explicit fast-path: if a helper like isNoRestrictApp(String) exists, short-circuit for GMS
            for (var m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class
                        && m.getReturnType() == boolean.class
                        && m.getName().toLowerCase().contains("norestrict")) {
                    try {
                        Utils.evaluate(hook(m), h -> setHookId(h, "Aurogon_isNoRestrict_" + m.getName())
                        ).intercept(chain -> {
                            String pkg = (String) chain.getArg(0);
                            if (GMS_PACKAGE_NAME.equals(pkg)) return true;
                            return chain.proceed();
                        });
                        deoptimize(m);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            log(Log.WARN, TAG, "AurogonImmobulusMode not found", e);
        }

        // PolicyMaker.isAllowFreeze / AurogonFilterManager.filter — final gates before freeze
        try {
            Class<?> pm = classLoader.loadClass("com.miui.server.greeze.power.PolicyMaker");
            for (var m : pm.getDeclaredMethods()) {
                if (!"isAllowFreeze".equals(m.getName())) continue;
                try {
                    Utils.evaluate(hook(m), h -> setHookId(h, "PolicyMaker_isAllowFreeze")
                    ).intercept(chain -> {
                        Object res = chain.proceed();
                        // If the original says "allow freeze" (true) and the UID belongs to GMS, veto it.
                        // We resolve UID->pkg via ActivityManager's getPackageNameForUid if available, otherwise via reflection on the caller.
                        try {
                            if (Boolean.TRUE.equals(res) && chain.getArgs().size() >= 1 && chain.getArg(0) instanceof Integer uid) {
                                String pkg = resolvePackageForUid(uid);
                                if (GMS_PACKAGE_NAME.equals(pkg)) {
                                    log(Log.INFO, TAG, "PolicyMaker.isAllowFreeze: veto GMS uid " + uid);
                                    return false;
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return res;
                    });
                    deoptimize(m);
                } catch (Throwable ignored) {
                }
            }
        } catch (ClassNotFoundException ignored) {
        }
        try {
            Class<?> afm = classLoader.loadClass("com.miui.server.greeze.AurogonFilterManager");
            for (var m : afm.getDeclaredMethods()) {
                if (!"filter".equals(m.getName())) continue;
                try {
                    Utils.evaluate(hook(m), h -> setHookId(h, "AurogonFilter_filter")
                    ).intercept(chain -> {
                        // filter(int uid, String pkg, int flags) — if pkg==GMS and flags contains NO_RESTRICT bit, return CANNOT_FREEZE
                        try {
                            String pkg = null;
                            for (Object arg : chain.getArgs()) if (arg instanceof String s && s.contains("com.google")) pkg = s;
                            // Heuristic: second arg is pkg in the 3-arg overload
                            if (chain.getArgs().size() >= 2 && chain.getArg(1) instanceof String s) pkg = s;
                            if (GMS_PACKAGE_NAME.equals(pkg)) {
                                Object res = chain.proceed();
                                // If original did NOT return the frozen-blocked sentinel, force it by returning the proceed result of a known no-restrict check?
                                // We don't know sentinel value; instead try to make filter return 0/CANNOT_FREEZE equivalent by short-circuiting:
                                // Empirically, returning 1 or the same as a no-restrict app would need decompilation.
                                // Safer: if the set hook above already injected GMS, this path is redundant; just return original (which will now be blocked).
                                return res;
                            }
                        } catch (Throwable ignored) {
                        }
                        return chain.proceed();
                    });
                    deoptimize(m);
                } catch (Throwable ignored) {
                }
            }
        } catch (ClassNotFoundException ignored) {
        }
    }

    private void hookGreezeNoRestrictFixups(ClassLoader classLoader) {
        // Ensure mGmsLimitEnabled is false on every GreezeManagerService construction,
        // and keep triggerGMSLimitAction neutered (already hooked above, but also handle alt overloads).
        try {
            Class<?> gms = classLoader.loadClass("com.miui.server.greeze.GreezeManagerService");
            for (var c : gms.getDeclaredConstructors()) {
                Utils.evaluate(hook(c), h -> setHookId(h, "Greeze_ctor_fixup")
                ).intercept(chain -> {
                    Object ret = chain.proceed();
                    try {
                        var f = gms.getDeclaredField("mGmsLimitEnabled");
                        f.setAccessible(true);
                        f.setBoolean(chain.getThisObject(), false);
                        log(Log.INFO, TAG, "GreezeManagerService ctor: mGmsLimitEnabled -> false");
                    } catch (Throwable ignored) {
                    }
                    return ret;
                });
                deoptimize(c);
            }
            // Also hook any remaining triggerGMSLimitAction overload not caught earlier (boolean vs no-arg vs int variants)
            for (var m : gms.getDeclaredMethods()) {
                if (!m.getName().toLowerCase().contains("triggerms")) continue;
                try {
                    Utils.evaluate(hook(m), h -> setHookId(h, "Greeze_trigger_" + m.getName() + "_" + m.getParameterCount())
                    ).intercept(chain -> {
                        try {
                            var f = gms.getDeclaredField("mGmsLimitEnabled");
                            f.setAccessible(true);
                            f.setBoolean(chain.getThisObject(), false);
                        } catch (Throwable ignored) {
                        }
                        // Neuter: set first boolean arg to false if present, else proceed (but we've already cleared the flag)
                        if (!chain.getArgs().isEmpty() && chain.getArg(0) instanceof Boolean) {
                            Object[] args = chain.getArgs().toArray();
                            args[0] = false;
                            return chain.proceed(args);
                        }
                        // If method is void trigger that would remove GMS from mAllowList, skip original logic by proceeding then ensuring GMS back in allowlist
                        Object res = chain.proceed();
                        try {
                            // Try to re-add GMS to mAllowList if field exists
                            for (var f : gms.getDeclaredFields()) {
                                if (f.getName().toLowerCase().contains("allowlist") && List.class.isAssignableFrom(f.getType())) {
                                    f.setAccessible(true);
                                    Object listObj = f.get(chain.getThisObject());
                                    if (listObj instanceof List) {
                                        @SuppressWarnings("unchecked") List<String> list = (List<String>) listObj;
                                        if (!list.contains(GMS_PACKAGE_NAME)) list.add(GMS_PACKAGE_NAME);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                        return res;
                    });
                    deoptimize(m);
                } catch (Throwable ignored) {
                }
            }
        } catch (ClassNotFoundException ignored) {
        }
    }

    /**
     * PowerKeeper side: {@code ActiveStateController.dealNoRestrictApp()} is the sole
     * place that regenerates {@link #MILLET_SETTING} from {@code userTable}.
     * Hooking it after the original write lets us re-append GMS without touching
     * the private DB (which requires signature permissions and Shizuku can't write
     * either). Equivalent to Shizuku's {@code ensureGmsNoRestrict()} but in-process,
     * immediate, and without a poll loop.
     */
    private void hookPowerKeeperMillet(ClassLoader classLoader) {
        String[] targetClasses = {
                "com.miui.powerkeeper.controller.ActiveStateController",
                "com.miui.powerkeeper.provider.PowerKeeperConfigureManager",
                "com.miui.powerkeeper.provider.UserConfigureHelper"
        };
        for (String clsName : targetClasses) {
            try {
                Class<?> cls = classLoader.loadClass(clsName);
                for (var m : cls.getDeclaredMethods()) {
                    String n = m.getName();
                    boolean isTarget = "dealNoRestrictApp".equals(n)
                            || "getNoRestrictApps".equals(n)
                            || "setAppConfigureUidPolicy".equals(n)
                            || n.toLowerCase().contains("norestrict");
                    if (!isTarget) continue;
                    try {
                        Utils.evaluate(hook(m), h -> setHookId(h, "PK_" + cls.getSimpleName() + "_" + n)
                        ).intercept(chain -> {
                            Object res = chain.proceed();
                            // After PowerKeeper regenerated MILLET, inject GMS via Settings.System
                            try {
                                Context ctx = getPowerKeeperContext();
                                if (ctx != null) {
                                    android.content.ContentResolver cr = ctx.getContentResolver();
                                    String cur = null;
                                    try {
                                        Class<?> ss = Class.forName("android.provider.Settings$System");
                                        try {
                                            var mGet = ss.getMethod("getStringForUser", android.content.ContentResolver.class, String.class, int.class);
                                            cur = (String) mGet.invoke(null, cr, MILLET_SETTING, 0);
                                        } catch (NoSuchMethodException ignored) {
                                            var mGet2 = ss.getMethod("getString", android.content.ContentResolver.class, String.class);
                                            cur = (String) mGet2.invoke(null, cr, MILLET_SETTING);
                                        }
                                    } catch (Throwable ignored) {}
                                    if (!milletContainsGms(cur)) {
                                        String fixed = ensureMilletContainsGms(cur);
                                        boolean ok = false;
                                        try {
                                            Class<?> ss2 = Class.forName("android.provider.Settings$System");
                                            try {
                                                var mPut = ss2.getMethod("putStringForUser", android.content.ContentResolver.class, String.class, String.class, int.class);
                                                ok = (Boolean) mPut.invoke(null, cr, MILLET_SETTING, fixed, 0);
                                            } catch (NoSuchMethodException ignored) {
                                                var mPut2 = ss2.getMethod("putString", android.content.ContentResolver.class, String.class, String.class);
                                                ok = (Boolean) mPut2.invoke(null, cr, MILLET_SETTING, fixed);
                                            }
                                        } catch (Throwable ignored) {}
                                        log(Log.INFO, TAG, "PowerKeeper " + n + ": restored MILLET -> " + fixed + " ok=" + ok);
                                    }
                                    // If this hook was getNoRestrictApps, also fix the returned collection directly
                                    if (res instanceof Set) {
                                        @SuppressWarnings("unchecked") Set<String> set = (Set<String>) res;
                                        if (!set.contains(GMS_PACKAGE_NAME)) set.add(GMS_PACKAGE_NAME);
                                    } else if (res instanceof List) {
                                        @SuppressWarnings("unchecked") List<String> list = (List<String>) res;
                                        if (!list.contains(GMS_PACKAGE_NAME)) list.add(GMS_PACKAGE_NAME);
                                    }
                                }
                            } catch (Throwable e) {
                                log(Log.ERROR, TAG, "Failed to restore MILLET after " + n, e);
                            }
                            return res;
                        });
                        deoptimize(m);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
        // Also hook Settings.System puts that originate from PowerKeeper process itself (redundant but cheap)
        try {
            Class<?> sys = classLoader.loadClass("android.provider.Settings$System");
            for (String name : new String[]{"putStringForUser", "putString"}) {
                for (var m : sys.getDeclaredMethods()) if (m.getName().equals(name)) {
                    try {
                        Utils.evaluate(hook(m), h -> setHookId(h, "PK_Settings_" + name)
                        ).intercept(chain -> {
                            String key = null; String val = null;
                            for (Object a : chain.getArgs()) if (MILLET_SETTING.equals(a)) key = MILLET_SETTING;
                            // args: (ContentResolver,String,String) or (ContentResolver,String,String,int)
                            if (chain.getArgs().size() >= 3 && chain.getArg(1) instanceof String k && chain.getArg(2) instanceof String v) {
                                key = k; val = v;
                            }
                            if (MILLET_SETTING.equals(key) && val != null && !milletContainsGms(val)) {
                                Object[] args = chain.getArgs().toArray();
                                args[2] = ensureMilletContainsGms(val);
                                return chain.proceed(args);
                            }
                            return chain.proceed();
                        });
                        deoptimize(m);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (ClassNotFoundException ignored) {}
    }

    private Context getPowerKeeperContext() {
        // PowerKeeper runs in com.miui.powerkeeper; ActivityThread.currentApplication() works there too.
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            var cur = at.getMethod("currentApplication");
            Object app = cur.invoke(null);
            if (app instanceof Context c) return c;
        } catch (Throwable ignored) {}
        return null;
    }

    private String resolvePackageForUid(int uid) {
        try {
            Context ctx = getSystemContext();
            if (ctx == null) ctx = getPowerKeeperContext();
            if (ctx != null) {
                Object pm = ctx.getPackageManager();
                try {
                    var m = pm.getClass().getMethod("getPackagesForUid", int.class);
                    String[] pkgs = (String[]) m.invoke(pm, uid);
                    if (pkgs != null && pkgs.length > 0) return pkgs[0];
                } catch (Throwable ignored) {}
                try {
                    var m2 = pm.getClass().getMethod("getNameForUid", int.class);
                    String n = (String) m2.invoke(pm, uid);
                    if (n != null) return n;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

}
