/*
 * 日志封装。跑在 app_process 里时 stdout 进不了 logcat，
 * 所以统一走 android.util.Log（tag = OASDisplay），排查用
 *   logcat -d | grep -a OASDisplay
 */
package org.onmyoji.oas.server.third;

public final class Ln {

    public static final String TAG = "OASDisplay";

    private Ln() {
    }

    public static void d(String msg) {
        android.util.Log.d(TAG, msg);
    }

    public static void i(String msg) {
        android.util.Log.i(TAG, msg);
    }

    public static void w(String msg) {
        android.util.Log.w(TAG, msg);
    }

    public static void e(String msg) {
        android.util.Log.e(TAG, msg);
    }

    public static void e(String msg, Throwable t) {
        android.util.Log.e(TAG, msg, t);
    }
}