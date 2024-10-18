package io.github.lsposed.disableflagsecure;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.SurfaceControl;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.annotations.BeforeInvocation;
import io.github.libxposed.api.annotations.XposedHooker;

@SuppressLint({"PrivateApi", "BlockedPrivateApi"})
public class DisableFlagSecure extends XposedModule {
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String OPLUS_APPPLATFORM = "com.oplus.appplatform";
    private static final String FLYME_SYSTEMUIEX = "com.flyme.systemuiex";
    private static final String MIUI_SCREENSHOT = "com.miui.screenshot";

    private static XposedModule module;

    public DisableFlagSecure(XposedInterface base, ModuleLoadedParam param) {
        super(base, param);
        module = this;
    }

    @Override
    public void onSystemServerLoaded(@NonNull SystemServerLoadedParam param) {
        var classLoader = param.getClassLoader();

        try {
            deoptimizeSystemServer(classLoader);
        } catch (Throwable t) {
            log("deoptimize system server failed", t);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Screen record detection (V)
            try {
                hookWindowManagerService(classLoader);
            } catch (Throwable t) {
                log("hook WindowManagerService failed", t);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Screenshot detection (U~V)
            try {
                hookActivityTaskManagerService(classLoader);
            } catch (Throwable t) {
                log("hook ActivityTaskManagerService failed", t);
            }

            // Xiaomi HyperOS (U)
            try {
                hookHyperOS(classLoader);
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                log("hook HyperOS failed", t);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // ScreenCapture in WindowManagerService (S~V)
            try {
                hookScreenCapture(classLoader);
            } catch (Throwable t) {
                log("hook ScreenCapture failed", t);
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Blackout permission check (S~T)
                try {
                    hookActivityManagerService(classLoader);
                } catch (Throwable t) {
                    log("hook ActivityManagerService failed", t);
                }
            }

            // WifiDisplay (S~V) / OverlayDisplay (S~V) / VirtualDisplay (U~V)
            try {
                hookDisplayControl(classLoader);
            } catch (Throwable t) {
                log("hook DisplayControl failed", t);
            }

            // VirtualDisplay with MediaProjection (S~V)
            try {
                hookVirtualDisplayAdapter(classLoader);
            } catch (Throwable t) {
                log("hook VirtualDisplayAdapter failed", t);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // OneUI
            try {
                hookScreenshotHardwareBuffer(classLoader);
            } catch (Throwable t) {
                if (!(t instanceof ClassNotFoundException)) {
                    log("hook ScreenshotHardwareBuffer failed", t);
                }
            }
            try {
                hookOneUI(classLoader);
            } catch (Throwable t) {
                if (!(t instanceof ClassNotFoundException)) {
                    log("hook OneUI failed", t);
                }
            }
        }

        // secureLocked flag (S-)
        try {
            // Screenshot
            hookWindowState(classLoader);
        } catch (Throwable t) {
            log("hook WindowState failed", t);
        }

        // oplus dumpsys
        // dumpsys window screenshot systemQuickTileScreenshotOut display_id=0
        try {
            hookOplus(classLoader);
        } catch (Throwable ignored) {

        }

        // Remove FLAG_SLIPPERY for Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            removeFlagSlippery();
        }
    }

    @SuppressLint("PrivateApi")
    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        if (!param.isFirstPackage()) return;

        var classLoader = param.getClassLoader();
        var pn = param.getPackageName();
        switch (pn) {
            case FLYME_SYSTEMUIEX:
            case OPLUS_APPPLATFORM:
                // Flyme SystemUI Ext 10.3.0
                // OPlus AppPlatform 13.1.0 / 14.0.0
                try {
                    hookScreenshotHardwareBuffer(classLoader);
                } catch (Throwable t) {
                    if (!(t instanceof ClassNotFoundException)) {
                        log("hook ScreenshotHardwareBuffer failed", t);
                    }
                }
            case SYSTEMUI:
            case MIUI_SCREENSHOT:
                if (OPLUS_APPPLATFORM.equals(pn) ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE)) {
                    // ScreenCapture in App (S~T) (OPlus S-U)
                    try {
                        hookScreenCapture(classLoader);
                    } catch (Throwable t) {
                        log("hook ScreenCapture failed", t);
                    }
                }
                break;
            default:
                try {
                    hookOnResume();
                } catch (Throwable ignored) {
                }
        }
    }

    private void deoptimizeSystemServer(ClassLoader classLoader) throws ClassNotFoundException {
        deoptimizeMethods(
                classLoader.loadClass("com.android.server.wm.WindowStateAnimator"),
                "createSurfaceLocked");

        deoptimizeMethods(
                classLoader.loadClass("com.android.server.wm.WindowManagerService"),
                "relayoutWindow");

        for (int i = 0; i < 20; i++) {
            try {
                var clazz = classLoader.loadClass("com.android.server.wm.RootWindowContainer$$ExternalSyntheticLambda" + i);
                if (BiConsumer.class.isAssignableFrom(clazz)) {
                    deoptimizeMethods(clazz, "accept");
                }
            } catch (ClassNotFoundException ignored) {
            }
            try {
                var clazz = classLoader.loadClass("com.android.server.wm.DisplayContent$" + i);
                if (BiPredicate.class.isAssignableFrom(clazz)) {
                    deoptimizeMethods(clazz, "test");
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
    }

    private void deoptimizeMethods(Class<?> clazz, String... names) {
        var list = Arrays.asList(names);
        Arrays.stream(clazz.getDeclaredMethods())
                .filter(method -> list.contains(method.getName()))
                .forEach(this::deoptimize);
    }

    private void hookWindowState(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var windowStateClazz = classLoader.loadClass("com.android.server.wm.WindowState");
        Method isSecureLockedMethod;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            isSecureLockedMethod = windowStateClazz.getDeclaredMethod("isSecureLocked");
        } else {
            var windowManagerServiceClazz = classLoader.loadClass("com.android.server.wm.WindowManagerService");
            isSecureLockedMethod = windowManagerServiceClazz.getDeclaredMethod("isSecureLocked", windowStateClazz);
        }
        hook(isSecureLockedMethod, SecureLockedHooker.class);
    }

    private static Field captureSecureLayersField;

    @TargetApi(Build.VERSION_CODES.S)
    private void hookScreenCapture(ClassLoader classLoader) throws ClassNotFoundException, NoSuchFieldException {
        var screenCaptureClazz = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ?
                classLoader.loadClass("android.window.ScreenCapture") :
                SurfaceControl.class;
        var captureArgsClazz = classLoader.loadClass(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ?
                "android.window.ScreenCapture$CaptureArgs" :
                "android.view.SurfaceControl$CaptureArgs");
        captureSecureLayersField = captureArgsClazz.getDeclaredField("mCaptureSecureLayers");
        captureSecureLayersField.setAccessible(true);
        hookMethods(screenCaptureClazz, ScreenCaptureHooker.class, "nativeCaptureDisplay");
        hookMethods(screenCaptureClazz, ScreenCaptureHooker.class, "nativeCaptureLayers");
    }

    @TargetApi(Build.VERSION_CODES.S)
    private void hookDisplayControl(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var displayControlClazz = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ?
                classLoader.loadClass("com.android.server.display.DisplayControl") :
                SurfaceControl.class;
        var method = displayControlClazz.getDeclaredMethod(
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ?
                        "createVirtualDisplay" :
                        "createDisplay", String.class, boolean.class);
        hook(method, CreateDisplayHooker.class);
    }

    @TargetApi(Build.VERSION_CODES.S)
    private void hookVirtualDisplayAdapter(ClassLoader classLoader) throws ClassNotFoundException {
        var displayControlClazz = classLoader.loadClass("com.android.server.display.VirtualDisplayAdapter");
        hookMethods(displayControlClazz, CreateVirtualDisplayLockedHooker.class, "createVirtualDisplayLocked");
    }

    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void hookActivityTaskManagerService(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var activityTaskManagerServiceClazz = classLoader.loadClass("com.android.server.wm.ActivityTaskManagerService");
        var iBinderClazz = classLoader.loadClass("android.os.IBinder");
        var iScreenCaptureObserverClazz = classLoader.loadClass("android.app.IScreenCaptureObserver");
        var method = activityTaskManagerServiceClazz.getDeclaredMethod("registerScreenCaptureObserver", iBinderClazz, iScreenCaptureObserverClazz);
        hook(method, ReturnNullHooker.class);
    }

    @TargetApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private void hookWindowManagerService(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var windowManagerServiceClazz = classLoader.loadClass("com.android.server.wm.WindowManagerService");
        var iScreenRecordingCallbackClazz = classLoader.loadClass("android.window.IScreenRecordingCallback");
        var method = windowManagerServiceClazz.getDeclaredMethod("registerScreenRecordingCallback", iScreenRecordingCallbackClazz);
        hook(method, ReturnFalseHooker.class);
    }

    @TargetApi(Build.VERSION_CODES.S)
    private void hookActivityManagerService(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var activityTaskManagerServiceClazz = classLoader.loadClass("com.android.server.am.ActivityManagerService");
        var method = activityTaskManagerServiceClazz.getDeclaredMethod("checkPermission", String.class, int.class, int.class);
        hook(method, CheckPermissionHooker.class);
    }

    @TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private void hookHyperOS(ClassLoader classLoader) throws ClassNotFoundException {
        var windowManagerServiceImplClazz = classLoader.loadClass("com.android.server.wm.WindowManagerServiceImpl");
        hookMethods(windowManagerServiceImplClazz, ReturnFalseHooker.class, "notAllowCaptureDisplay");
    }

    @TargetApi(Build.VERSION_CODES.S)
    private void hookScreenshotHardwareBuffer(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        var screenshotHardwareBufferClazz = classLoader.loadClass(
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ?
                        "android.window.ScreenCapture$ScreenshotHardwareBuffer" :
                        "android.view.SurfaceControl$ScreenshotHardwareBuffer");
        var method = screenshotHardwareBufferClazz.getDeclaredMethod("containsSecureLayers");
        hook(method, ReturnFalseHooker.class);
    }

    private void hookOplus(ClassLoader classLoader) throws ClassNotFoundException {
        // caller: com.android.server.wm.OplusLongshotWindowDump#dumpWindows
        var longshotMainClazz = classLoader.loadClass("com.android.server.wm.OplusLongshotMainWindow");
        hookMethods(longshotMainClazz, ReturnFalseHooker.class, "hasSecure");
    }

    @TargetApi(Build.VERSION_CODES.S)
    private void hookOneUI(ClassLoader classLoader) throws ClassNotFoundException {
        var wmScreenshotControllerClazz = classLoader.loadClass("com.android.server.wm.WmScreenshotController");
        hookMethods(wmScreenshotControllerClazz, ReturnTrueHooker.class, "canBeScreenshotTarget");
    }

    private void hookMethods(Class<?> clazz, Class<? extends Hooker> hooker, String... names) {
        var list = Arrays.asList(names);
        Arrays.stream(clazz.getDeclaredMethods())
                .filter(method -> list.contains(method.getName()))
                .forEach(method -> hook(method, hooker));
    }

    private void hookOnResume() throws NoSuchMethodException {
        var method = Activity.class.getDeclaredMethod("onResume");
        hook(method, ToastHooker.class);
    }

    @XposedHooker
    private static class CreateDisplayHooker implements Hooker {

        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                String stack = Log.getStackTraceString(new Throwable());
                if (stack.contains("createVirtualDisplayLocked")) {
                    return;
                }
            }
            callback.getArgs()[1] = true;
        }
    }

    @XposedHooker
    private static class CheckPermissionHooker implements Hooker {

        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) {
            var permission = callback.getArgs()[0];
            if ("android.permission.CAPTURE_BLACKOUT_CONTENT".equals(permission)) {
                callback.getArgs()[0] = "android.permission.READ_FRAME_BUFFER";
            }
        }
    }

    @XposedHooker
    private static class ScreenCaptureHooker implements Hooker {

        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) {
            var captureArgs = callback.getArgs()[0];
            try {
                captureSecureLayersField.set(captureArgs, true);
            } catch (IllegalAccessException t) {
                module.log("ScreenCaptureHooker failed", t);
            }
        }
    }

    @XposedHooker
    private static class CreateVirtualDisplayLockedHooker implements Hooker {

        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) {
            var caller = (int) callback.getArgs()[2];
            if (caller != 1000 && callback.getArgs()[1] == null) {
                // not os and not media projection
                return;
            }
            for (int i = 3; i < callback.getArgs().length; i++) {
                var arg = callback.getArgs()[i];
                if (arg instanceof Integer) {
                    var flags = (int) arg;
                    flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE;
                    callback.getArgs()[i] = flags;
                    return;
                }
            }
            module.log("flag not found in CreateVirtualDisplayLockedHooker");
        }
    }

    @XposedHooker
    private static class SecureLockedHooker implements Hooker {

        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                String stack = Log.getStackTraceString(new Throwable());
                // don't change surface flags, but passing other checks
                if (stack.contains("setInitialSurfaceControlProperties")
                        || stack.contains("createSurfaceLocked")) {
                    return;
                }
            }
            callback.returnAndSkip(false);
        }
    }

    @XposedHooker
    private static class ReturnTrueHooker implements Hooker {
        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) {
            callback.returnAndSkip(true);
        }
    }

    @XposedHooker
    private static class ReturnFalseHooker implements Hooker {
        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) {
            callback.returnAndSkip(false);
        }
    }

    @XposedHooker
    private static class ReturnNullHooker implements Hooker {
        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) {
            callback.returnAndSkip(null);
        }
    }

    @XposedHooker
    private static class ToastHooker implements Hooker {
        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) {
            var activity = (Activity) callback.getThisObject();
            new AlertDialog.Builder(activity)
                    .setTitle("Enable Screenshot")
                    .setMessage("Incorrect module usage, remove this app from scope.")
                    .setCancelable(false)
                    .setPositiveButton("OK", (dialog, which) -> System.exit(0))
                    .show();
        }
    }

    private void removeFlagSlippery() {
        try {
            ContentResolver contentResolver = getContext().getContentResolver();
            Settings.Global.putInt(contentResolver, "flag_slippery", 0);
            log("FLAG_SLIPPERY removed");
        } catch (Exception e) {
            log("Failed to remove FLAG_SLIPPERY", e);
        }
    }
}
