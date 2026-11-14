/*
 * 本文件派生自 scrcpy（Genymobile/scrcpy, Apache License 2.0）的
 *   server/src/main/java/com/genymobile/scrcpy/FakeContext.java
 * 去掉了 ContentResolver / 剪贴板 / ActivityManager 的 patch 等无关部分。
 *
 * 原始版权：Copyright (C) 2018 Genymobile
 * 许可全文见仓库根目录 THIRD_PARTY_NOTICES.md
 */
package org.onmyoji.oas.server.third;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Process;

/**
 * 给 DisplayManager 用的假 Context。
 *
 * 我们是 root（uid 0）：AOSP 里 DisplayManagerService#validatePackageName 对 ROOT_UID
 * 直接放行，所以包名填什么都无所谓，这里沿用 scrcpy 的 "com.android.shell"。
 */
public final class FakeContext extends ContextWrapper {

    public static final String PACKAGE_NAME = "com.android.shell";

    private static FakeContext sInstance;

    public static synchronized FakeContext get() {
        if (sInstance == null) {
            sInstance = new FakeContext();
        }
        return sInstance;
    }

    private FakeContext() {
        super(Workarounds.getSystemContext());
        if (getBaseContext() == null) {
            Ln.w("FakeContext: system context 为 null，后续调用可能失败");
        }
    }

    @Override
    public String getPackageName() {
        return PACKAGE_NAME;
    }

    @Override
    public String getOpPackageName() {
        return PACKAGE_NAME;
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public Context createPackageContext(String packageName, int flags) {
        return this;
    }

    /**
     * attribution 里的 uid 必须和真实调用 uid 一致（我们是 0），
     * 否则部分 ROM 会在 DisplayManagerService 里做二次校验。
     */
    @Override
    public AttributionSource getAttributionSource() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return null;
        }
        AttributionSource.Builder builder = new AttributionSource.Builder(Process.ROOT_UID);
        builder.setPackageName(PACKAGE_NAME);
        return builder.build();
    }
}