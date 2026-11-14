package org.onmyoji.oas.android.python

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/**
 * Chaquopy 运行时入口。
 *
 * 说明：
 * - Python 只在进程内启动一次（Activity 重建、Service 复用同一实例）。
 * - 所有对 Python 的调用都从这里走，方便后续换成"带日志/异常包装"的实现。
 */
object PyRuntime {

    const val MODULE = "android_entry"

    @Volatile
    private var started = false

    @Synchronized
    fun ensureStarted(context: Context) {
        if (started) return
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        started = true
    }

    fun isStarted(): Boolean = started

    fun module(): PyObject = Python.getInstance().getModule(MODULE)

    /** 调用 Python 模块里的函数并返回字符串结果。 */
    fun call(func: String, vararg args: Any?): String {
        val obj = module().callAttr(func, *args)
        return obj.toString()
    }

    /** 调用 Python 函数并返回原始 PyObject。 */
    fun callRaw(func: String, vararg args: Any?): PyObject = module().callAttr(func, *args)
}
