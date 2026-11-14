/*
 * 本文件派生自 scrcpy（Genymobile/scrcpy, Apache License 2.0）的
 *   server/src/main/java/com/genymobile/scrcpy/Workarounds.java
 * 只保留「让 app_process 进程拥有一个可用 Context」所需的部分，
 * 删除了音频抓取、剪贴板等与本项目无关的代码。
 *
 * 原始版权：Copyright (C) 2018 Genymobile
 * 许可全文见仓库根目录 THIRD_PARTY_NOTICES.md
 */
package org.onmyoji.oas.server.third;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 在 app_process 里伪造一个 ActivityThread，从而拿到 system context。
 *
 * 为什么必须这么做：DisplayManager 的公开 API 需要一个 Context，
 * 而 app_process 启动时根本没有 Context —— 这是 scrcpy 用了很多年的老办法。
 * 所有步骤都是「尽力而为」，失败只降级不崩，方便在异常 ROM 上继续跑。
 */
public final class Workarounds {

    private static final Class<?> ACTIVITY_THREAD_CLASS;
    private static final Object ACTIVITY_THREAD;

    private static String sInitError = "";

    static {
        Class<?> cls = null;
        Object thread = null;
        try {
            // ActivityThread activityThread = new ActivityThread();
            cls = Class.forName("android.app.ActivityThread");
            Constructor<?> ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            thread = ctor.newInstance();

            // ActivityThread.sCurrentActivityThread = activityThread;
            Field current = cls.getDeclaredField("sCurrentActivityThread");
            current.setAccessible(true);
            current.set(null, thread);

            // activityThread.mSystemThread = true;
            Field systemThread = cls.getDeclaredField("mSystemThread");
            systemThread.setAccessible(true);
            systemThread.setBoolean(thread, true);
        } catch (Throwable t) {
            sInitError = "ActivityThread bootstrap failed: " + t;
            Ln.e(sInitError, t);
        }
        ACTIVITY_THREAD_CLASS = cls;
        ACTIVITY_THREAD = thread;
    }

    private Workarounds() {
    }

    public static String initError() {
        return sInitError;
    }

    /** 依次填补 ActivityThread 里几个「不填就会在后续调用中 NPE」的字段。 */
    public static void apply() {
        if (ACTIVITY_THREAD == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 的 DisplayManagerGlobal.getDisplayInfoLocked() 会用到
            // ActivityThread.getConfiguration()，它要求 mConfigurationController 非空。
            fillConfigurationController();
        }
        fillAppInfo();
        fillAppContext();
    }

    private static void fillAppInfo() {
        try {
            Class<?> bindDataClass = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> bindDataCtor = bindDataClass.getDeclaredConstructor();
            bindDataCtor.setAccessible(true);
            Object bindData = bindDataCtor.newInstance();

            ApplicationInfo appInfo = new ApplicationInfo();
            appInfo.packageName = FakeContext.PACKAGE_NAME;

            Field appInfoField = bindDataClass.getDeclaredField("appInfo");
            appInfoField.setAccessible(true);
            appInfoField.set(bindData, appInfo);

            Field boundApplication = ACTIVITY_THREAD_CLASS.getDeclaredField("mBoundApplication");
            boundApplication.setAccessible(true);
            boundApplication.set(ACTIVITY_THREAD, bindData);
        } catch (Throwable t) {
            Ln.d("fillAppInfo skipped: " + t);
        }
    }

    private static void fillAppContext() {
        try {
            Application app = Instrumentation.newApplication(Application.class, FakeContext.get());
            Field initialApplication = ACTIVITY_THREAD_CLASS.getDeclaredField("mInitialApplication");
            initialApplication.setAccessible(true);
            initialApplication.set(ACTIVITY_THREAD, app);
        } catch (Throwable t) {
            Ln.d("fillAppContext skipped: " + t);
        }
    }

    private static void fillConfigurationController() {
        try {
            Class<?> controllerClass = Class.forName("android.app.ConfigurationController");
            Class<?> threadInternalClass = Class.forName("android.app.ActivityThreadInternal");
            Constructor<?> ctor = controllerClass.getDeclaredConstructor(threadInternalClass);
            ctor.setAccessible(true);
            Object controller = ctor.newInstance(ACTIVITY_THREAD);

            Field field = ACTIVITY_THREAD_CLASS.getDeclaredField("mConfigurationController");
            field.setAccessible(true);
            field.set(ACTIVITY_THREAD, controller);
        } catch (Throwable t) {
            Ln.d("fillConfigurationController skipped: " + t);
        }
    }

    /** 取出 system context；失败返回 null（调用方自己兜底）。 */
    public static Context getSystemContext() {
        if (ACTIVITY_THREAD == null) {
            return null;
        }
        try {
            Method method = ACTIVITY_THREAD_CLASS.getDeclaredMethod("getSystemContext");
            return (Context) method.invoke(ACTIVITY_THREAD);
        } catch (Throwable t) {
            Ln.e("getSystemContext failed", t);
            return null;
        }
    }
}