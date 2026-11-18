package org.onmyoji.oas.android

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.onmyoji.oas.android.diag.CheckItem
import org.onmyoji.oas.android.diag.CheckState
import org.onmyoji.oas.android.diag.SelfCheck
import org.onmyoji.oas.android.poc.DisplayPoc
import org.onmyoji.oas.android.poc.KeepAlivePoc
import org.onmyoji.oas.android.preview.PreviewRenderer
import org.onmyoji.oas.android.preview.PreviewSession
import org.onmyoji.oas.android.python.PyRuntime
import org.onmyoji.oas.android.root.Device
import org.onmyoji.oas.android.root.DisplayServer
import org.onmyoji.oas.android.service.KeepAliveService
import org.onmyoji.oas.android.ui.theme.OasTheme
import org.onmyoji.oas.android.ui.theme.StateFail
import org.onmyoji.oas.android.ui.theme.StateOk
import org.onmyoji.oas.android.ui.theme.StateWarn
import java.io.File

/**
 * 无头触发通道。
 *
 * `am start -n org.onmyoji.oas.android/.MainActivity --ei bench_delay 8 [--ei bench_display 2]`
 *
 * 注意：Activity 是 standard/singleTop 复用实例，`am start` 若命中已有任务只会走
 * `onNewIntent()`，`LaunchedEffect(Unit)` 不会重跑 —— 上一版就是栽在这里。
 * 因此这里用「全局 Compose 状态 + 时间戳」做事件总线，onCreate/onNewIntent 都投递。
 */
object BenchTrigger {
    data class Request(
        val delayS: Int,
        val displayId: Int,
        val ts: Long,
        val poc: Boolean = false,
        val cleanup: Boolean = false,
        val keepalive: Boolean = false,
        val keepaliveStop: Boolean = false,
        val cvSelftest: Boolean = false,
        val kbA: Int = 30,
        val kbB: Int = 60,
        val kbC: Int = 60,
    )

    val request = mutableStateOf<Request?>(null)

    fun offer(intent: Intent?) {
        if (intent == null) {
            android.util.Log.i("OASBench", "offer: intent is null")
            return
        }
        val delay = intent.getIntExtra("bench_delay", -1)
        val poc = intent.getIntExtra("poc_display", 0) == 1 || intent.getBooleanExtra("poc_display", false)
        val cleanup = intent.getIntExtra("cleanup", 0) == 1 || intent.getBooleanExtra("cleanup", false)
        val keepalive = intent.getIntExtra("keepalive", 0) == 1 || intent.getBooleanExtra("keepalive", false)
        val keepaliveStop = intent.getIntExtra("keepalive_stop", 0) == 1 ||
            intent.getBooleanExtra("keepalive_stop", false)
        val cvSelftest = intent.getIntExtra("cv_selftest", 0) == 1 || intent.getBooleanExtra("cv_selftest", false)
        android.util.Log.i(
            "OASBench",
            "offer: delay=$delay display=${intent.getIntExtra("bench_display", -1)} " +
                "poc=$poc cleanup=$cleanup keepalive=$keepalive kbStop=$keepaliveStop cv=$cvSelftest",
        )
        if (delay < 0 && !poc && !cleanup && !keepalive && !keepaliveStop && !cvSelftest) return
        val displayId = intent.getIntExtra("bench_display", -1)
        val kbA = intent.getIntExtra("kb_a", 30)
        val kbB = intent.getIntExtra("kb_b", 60)
        val kbC = intent.getIntExtra("kb_c", 60)
        intent.removeExtra("bench_delay")
        intent.removeExtra("bench_display")
        intent.removeExtra("poc_display")
        intent.removeExtra("cleanup")
        intent.removeExtra("keepalive")
        intent.removeExtra("keepalive_stop")
        intent.removeExtra("cv_selftest")
        intent.removeExtra("kb_a")
        intent.removeExtra("kb_b")
        intent.removeExtra("kb_c")
        request.value = Request(
            delay, displayId, System.currentTimeMillis(),
            poc, cleanup, keepalive, keepaliveStop, cvSelftest, kbA, kbB, kbC,
        )
        android.util.Log.i("OASBench", "offer: posted")
    }

    fun consume(r: Request) {
        if (request.value?.ts == r.ts) request.value = null
    }
}

class MainActivity : ComponentActivity() {

    /**
     * Android 13+ 通知权限。前台服务的通知受它影响：
     * 被拒不会让 FGS 失效（服务照跑），但通知栏看不到 —— 报告里会记 WARN。
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            android.util.Log.i("OASBench", "POST_NOTIFICATIONS granted=$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Python / 设备层调用需要 Context（虚拟屏 daemon 的启动与 jar 解压都靠它）
        org.onmyoji.oas.android.root.Device.init(this)
        requestNotificationPermissionIfNeeded()
        BenchTrigger.offer(intent)
        enableEdgeToEdge()
        setContent {
            OasTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = { TopBar() },
                ) { innerPadding ->
                    SelfCheckScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        BenchTrigger.offer(intent)
    }

    override fun onDestroy() {
        // 用户主动关掉界面时把常驻 daemon 一并收掉，不留后台进程。
        // 配置变更（旋转等）不触发，避免把正在跑的虚拟屏误杀。
        if (isFinishing) {
            runCatching { org.onmyoji.oas.android.root.DisplayServer.stop(this) }
            // 前台服务也一起收：保持「不自启、退出即收」的纪律
            runCatching { KeepAliveService.stop(this) }
        }
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val granted = checkPermission("android.permission.POST_NOTIFICATIONS")
        if (!granted) {
            runCatching { notificationPermission.launch("android.permission.POST_NOTIFICATIONS") }
        }
    }

    private fun checkPermission(permission: String): Boolean =
        checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar() {
    TopAppBar(
        title = {
            Column {
                Text("OAS for Android", style = MaterialTheme.typography.titleMedium)
                Text(
                    "阶段 0：运行环境自检",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

@Composable
private fun SelfCheckScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val items = remember { mutableStateListOf<CheckItem>() }
    var running by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var frameResult by remember { mutableStateOf<String?>(null) }
    var frameBusy by remember { mutableStateOf(false) }

    fun startCheck() {
        if (running) return
        running = true
        finished = false
        items.clear()
        scope.launch {
            withContext(Dispatchers.IO) {
                SelfCheck.runAll(context) { item ->
                    mainHandler.post { items.add(item) }
                }
            }
            mainHandler.post {
                running = false
                finished = true
                // 落盘，方便息屏/黑屏时排查，也方便用户反馈
                runCatching { SelfCheck.writeReport(context, items.toList()) }
            }
        }
    }

    LaunchedEffect(Unit) { startCheck() }

    // delayS > 0 时先等待再抓：点完按钮切到游戏，到点抓的就是游戏画面。
    // displayId = -1 表示抓默认屏，> 0 时抓指定显示器（虚拟屏验证用）。
    fun startBench(delayS: Int, displayId: Int = -1) {
        android.util.Log.i("OASBench", "startBench enter delay=$delayS display=$displayId busy=$frameBusy")
        if (frameBusy) return
        frameBusy = true
        frameResult = null
        scope.launch {
            val workDir = File(context.filesDir, "oas").absolutePath
            val benchPath = File(workDir, "cache/bench.json").absolutePath
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    // 无头触发时自检可能还没跑完（它才是首个 Python.start 调用者），
                    // 这里必须自己保证运行时已启动，否则 Chaquopy 会抛
                    // "Cannot use GenericPlatform on Android"。
                    PyRuntime.ensureStarted(context)
                    PyRuntime.call("bench_capture", workDir, 2, delayS, displayId)
                }.getOrElse { "抓帧失败: ${it.message ?: it}" }
            }
            android.util.Log.i("OASBench", "bench done: $text")
            frameResult = "bench.json → $benchPath\n\n$text"
            frameBusy = false
        }
    }

    // 虚拟屏 PoC：建屏 → 拉游戏 → 抓帧 → 点击 → 主屏并发 → 关闭，全程落盘
    fun startPoc() {
        if (frameBusy) return
        frameBusy = true
        frameResult = "虚拟屏 PoC 运行中…（约 40s，别切走）"
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    Device.init(context)
                    DisplayPoc.run(context) { msg ->
                        mainHandler.post { frameResult = "虚拟屏 PoC: $msg" }
                    }.summary()
                }.getOrElse { "PoC 失败: ${it.message ?: it}" }
            }
            android.util.Log.i("OASBench", "poc done: ${text.take(300)}")
            frameResult = text.ifBlank { "PoC 无输出，见 files/oas/cache/display_poc.txt" }
            frameBusy = false
        }
    }

    // 阶段 2 / P1-1：后台保活四段验证（前台服务 + WakeLock + Python 心跳 + 息屏）
    fun startKeepAlive(aSec: Int = 30, bSec: Int = 60, cSec: Int = 60) {
        if (frameBusy || KeepAlivePoc.running) return
        frameBusy = true
        frameResult = "后台保活验证：准备中…（A ${aSec}s / B ${bSec}s / C ${cSec}s）"
        KeepAlivePoc.start(context, aSec, bSec, cSec)
        scope.launch {
            while (KeepAlivePoc.running) {
                val line = KeepAliveService.statusLine()
                frameResult = "后台保活验证：${KeepAlivePoc.lastMessage}\n$line"
                delay(1_000)
            }
            frameResult = KeepAlivePoc.summary().ifBlank { "验证结束，但报告为空（看 logcat）" }
            frameBusy = false
        }
    }

    fun stopKeepAlive() {
        scope.launch {
            val msg = runCatching {
                withContext(Dispatchers.IO) {
                    runCatching {
                        PyRuntime.ensureStarted(context)
                        PyRuntime.call("stop_heartbeat")
                    }
                    runCatching { KeepAliveService.stop(context) }
                    runCatching { DisplayServer.stop(context) }
                    "已停止：Python 心跳 / 前台服务 / 虚拟屏 daemon"
                }
            }.getOrElse { "停止失败: ${it.message}" }
            frameResult = msg
        }
    }

    // 阶段 2：cv2 shim 自测（OpenCV AAR + CvBridge + cv2.py）
    fun startCvSelftest() {
        if (frameBusy) return
        frameBusy = true
        frameResult = "cv2 自测运行中…（含 1280×720 模板匹配性能采样）"
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    PyRuntime.ensureStarted(context)
                    val workDir = File(context.filesDir, "oas").absolutePath
                    val json = PyRuntime.call("cv_selftest", workDir)
                    val m = Regex("\"passed\":\\s*(\\d+)").find(json)
                    val t = Regex("\"total\":\\s*(\\d+)").find(json)
                    if (m != null && t != null) "cv2 自测：通过 ${m.groupValues[1]}/${t.groupValues[1]}" else json.take(400)
                }.getOrElse { "cv2 自测失败: ${it.message ?: it}" }
            }
            val txt = File(context.filesDir, "oas/cache/cv_selftest.txt")
            frameResult = "$text\n\n${runCatching { txt.readText() }.getOrDefault("")}"
            frameBusy = false
        }
    }

    fun cleanupServer() {
        frameResult = "正在清理虚拟屏服务…"
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching {
                    runCatching {
                        PyRuntime.ensureStarted(context)
                        PyRuntime.call("stop_heartbeat")
                    }
                    KeepAliveService.stop(context)
                    DisplayServer.stop(context)
                    "已清理：停止 Python 心跳 / 前台服务，daemon 已停止，socket/pid/log 已删除"
                }.getOrElse { "清理失败: ${it.message}" }
            }
            frameResult = msg
        }
    }

    // 无头触发：am start -n org.onmyoji.oas.android/.MainActivity --ei bench_delay 8 --ei bench_display -1
    // 监听全局 BenchTrigger 状态（onCreate 与 onNewIntent 都会投递），而不是只读一次 intent。
    val benchReq = BenchTrigger.request.value
    android.util.Log.i("OASBench", "compose: benchReq=$benchReq")
    LaunchedEffect(benchReq) {
        android.util.Log.i("OASBench", "effect: triggered benchReq=$benchReq")
        if (benchReq == null) return@LaunchedEffect
        BenchTrigger.consume(benchReq)
        // 顺序很重要：keepalive 必须排在 else(startBench) 之前，
        // 否则 --ei keepalive 1 会掉进 bench 分支空跑（delay=-1 什么也不做）。
        when {
            benchReq.cleanup -> cleanupServer()
            benchReq.keepaliveStop -> stopKeepAlive()
            benchReq.keepalive -> startKeepAlive(benchReq.kbA, benchReq.kbB, benchReq.kbC)
            benchReq.cvSelftest -> startCvSelftest()
            benchReq.poc -> startPoc()
            else -> startBench(benchReq.delayS, benchReq.displayId)
        }
    }

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("正在自检…", style = MaterialTheme.typography.bodySmall)
            } else {
                val failed = items.count { it.state == CheckState.FAIL }
                Text(
                    if (failed == 0) "全部通过" else "$failed 项未通过",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed == 0) StateOk else StateFail,
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { startCheck() }, enabled = !running) { Text("重新自检") }
        }

        // 阶段 1 验证入口：抓帧 → 检测内容区 → 裁切/letterbox → 输出 1280x720
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { startBench(0) },
                enabled = !frameBusy,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (frameBusy) "抓帧中…" else "立即抓帧",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(
                onClick = { startBench(15) },
                enabled = !frameBusy,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (frameBusy) "等待中…（15s）" else "15 秒后抓帧（游戏前台用）",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // 阶段 1 虚拟屏 PoC：一条链路从建屏跑到销毁，结果写 cache/display_poc.json
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { startPoc() },
                enabled = !frameBusy,
                modifier = Modifier.weight(1f),
            ) {
                Text("虚拟屏 PoC（7 项）", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                onClick = { cleanupServer() },
                modifier = Modifier.weight(1f),
            ) {
                Text("停止虚拟屏服务", style = MaterialTheme.typography.bodySmall)
            }
        }

        // 阶段 2 兜底入口：无头通道（am start --ei ...）被 ROM 拦时，仍能手动触发
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { startCvSelftest() },
                enabled = !frameBusy,
                modifier = Modifier.weight(1f),
            ) {
                Text("cv2 自测", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                onClick = { startKeepAlive() },
                enabled = !frameBusy && !KeepAlivePoc.running,
                modifier = Modifier.weight(1f),
            ) {
                Text("后台保活四段", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                onClick = { stopKeepAlive() },
                modifier = Modifier.weight(1f),
            ) {
                Text("停止保活", style = MaterialTheme.typography.bodySmall)
            }
        }

        // BG-35 C-2 片③：App 内实时预览（默认关，只有点开开关才抽帧）
        PreviewSection()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (frameResult != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            frameResult!!,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
            items(items, key = { it.name + it.detail }) { item ->
                CheckCard(item)
            }
            if (finished) {
                item {
                    OutlinedButton(
                        onClick = {
                            val report = SelfCheck.formatReport(items)
                            copyToClipboard(context, report)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("复制自检报告") }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun CheckCard(item: CheckItem) {
    val color = when (item.state) {
        CheckState.OK -> StateOk
        CheckState.WARN -> StateWarn
        CheckState.FAIL -> StateFail
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(10.dp)
                    .background(color, CircleShape),
            )
            Spacer(Modifier.size(10.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "${item.costMs}ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private fun copyToClipboard(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("OAS self-check", text))
}

/**
 * BG-35 C-2 · 片③：App 内实时预览 `oas` 虚拟屏。
 *
 * 三条纪律：
 * 1. **默认关** —— 只有用户把开关打开才建 SurfaceView、才起抽帧线程（口径②：进 App 不许抢 CPU/IO）；
 * 2. 打开后才把 `PreviewRenderer` 绑到 SurfaceView 的 holder 并 `start()`；渲染线程自己等 surface 就绪，
 *    未就绪时只丢帧、不抓帧（不白烧 IO）；
 * 3. 关掉/离开界面即释放：`stop()` 停线程 → IO 线程 `PreviewSession.releaseAll()`
 *    → `destroy` + `DisplayInfo{"oas"` 归 0 校验，结果直接显示在界面上（口径④）。
 */
@Composable
private fun PreviewSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(false) }
    // 只有「本次预览曾开过」才允许在离开界面时释放：oas 屏可能是用户为挂机手动建的，
    // 打开一次 App 就把它 destroy 掉是事故。
    val everStarted = remember { mutableStateOf(false) }
    var stat by remember { mutableStateOf<PreviewRenderer.Stat?>(null) }
    var releaseText by remember { mutableStateOf<String?>(null) }

    val renderer = remember {
        PreviewRenderer(File(context.cacheDir, "preview")).also { r -> r.onStat = { s -> stat = s } }
    }

    fun stopAndRelease() {
        renderer.stop()
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                runCatching { PreviewSession.releaseAll().summary() }
                    .getOrElse { "释放失败: ${it.message ?: it}" }
            }
            releaseText = msg
        }
    }

    // 离开界面（返回/退出 App）：只有本次预览曾开过才释放，绝不动用户手动建来挂机的 oas 屏
    DisposableEffect(Unit) {
        onDispose {
            if (!everStarted.value) return@onDispose
            renderer.stop()
            // onDispose 里的 scope 已不可用，用独立的 IO 协程收尾
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { PreviewSession.releaseAll() }
            }
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "预览虚拟屏（默认关）",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = on,
                onCheckedChange = { want ->
                    on = want
                    if (want) {
                        releaseText = null
                        everStarted.value = true
                    } else {
                        stopAndRelease()
                    }
                },
            )
        }

        if (on) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                factory = { ctx ->
                    SurfaceView(ctx).also { sv ->
                        // holder 此时已存在；surface 真正就绪由渲染线程自己判（isValid），
                        // 未就绪时只丢帧不抓帧，所以这里可以放心直接 start()
                        renderer.bind(sv.holder)
                        renderer.start()
                    }
                },
            )
            val s = stat
            Text(
                if (s == null) {
                    "预览启动中…"
                } else {
                    "抓帧 ${s.grabs}｜丢帧 ${s.drops}｜grab ${"%.0f".format(s.lastGrabMs)}ms" +
                        "｜daemon frames ${s.daemonFrames}｜贴图 ${s.drawMs}ms｜${s.note}"
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }

        releaseText?.let {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}
