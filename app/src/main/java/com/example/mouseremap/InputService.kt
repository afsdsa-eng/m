package com.example.mouseremap

import android.graphics.PointF
import android.os.Build
import android.os.IBinder
import android.system.Os
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/**
 * Shizukuが shell(uid 2000) 権限で起動するプロセス。
 * APKに同梱したネイティブデーモン(mouseremapd)を /data/local/tmp に取り出して起動し、
 * 設定を標準入力で渡して、状態を標準出力から受け取る。
 * デーモンはマウスを占有して仮想マウス経由で再送するので、左右クリックなども付け替えられる。
 */
class InputService : IUserService.Stub() {

    private val lock = Any()
    @Volatile private var proc: Process? = null
    private var enabled = false
    private var apkPath = ""
    private var restarts = 0
    private var longPressMs = 550
    @Volatile private var cursorOk = false // OSのAPIでカーソル位置を取得できる端末か
    @Volatile private var listener: IMouseListener? = null // アプリ側(オーバーレイ方式)の通知先
    private var lastActions = IntArray(Prefs.SLOT_COUNT)
    private val worker = Executors.newSingleThreadExecutor()
    private val lines = Array(Prefs.SLOT_COUNT) { "slot $it pass" }

    private val devices = ConcurrentHashMap<String, String>() // path -> name
    @Volatile private var lastEvent = "まだボタン入力はありません"
    @Volatile private var lastError = ""
    @Volatile private var running = true

    init {
        Thread({ watchdog() }, "watchdog").apply { isDaemon = true }.start()
    }

    override fun destroy() {
        running = false
        synchronized(lock) { stopDaemon() }
        exitProcess(0)
    }

    override fun start(apkPath: String?) {
        synchronized(lock) {
            this.apkPath = apkPath ?: ""
            cursorOk = cursorPosition() != null
            if (enabled && proc?.isAlive != true) startDaemon()
        }
    }

    override fun setMapping(actions: IntArray?, ms: Int) {
        if (actions == null) return
        synchronized(lock) {
            longPressMs = ms.coerceIn(100, 3000)
            cursorOk = cursorPosition() != null
            lastActions = actions.copyOf()
            applyLines()
        }
    }

    /** 保存してある割り当てから設定行を作り直し、デーモンへ送る(通知先が増減したときも呼ぶ)。 */
    private fun applyLines() {
        val all = RemapAction.values()
        val touchOk = cursorOk || listener != null
        for (s in 0 until Prefs.SLOT_COUNT) {
            val a = all.getOrElse(lastActions.getOrElse(s) { 0 }) { RemapAction.PASS_THROUGH }
            lines[s] = a.toProtocol(s, longPressMs, touchOk)
            send(lines[s])
        }
    }

    override fun setListener(l: IMouseListener?) {
        synchronized(lock) {
            listener = l
            try {
                l?.asBinder()?.linkToDeath({
                    synchronized(lock) {
                        listener = null
                        applyLines()
                    }
                }, 0)
            } catch (_: Exception) {
            }
            applyLines()
        }
    }

    override fun jitter() {
        synchronized(lock) { send("jitter") }
    }

    override fun touchLongPressAt(x: Int, y: Int) {
        worker.execute { touchAt(x, y) }
    }

    /**
     * オーバーレイ表示の権限(SYSTEM_ALERT_WINDOW)をshell権限で付与する。
     * appops と pm grant の両方を試し、最後に現在の状態を読み戻して、全部の出力を返す(原因調査用)。
     */
    override fun grantOverlay(pkg: String?): String {
        if (pkg == null || !Regex("[A-Za-z0-9_.]+").matches(pkg)) return "error"
        val out = StringBuilder()
        fun step(label: String, vararg cmd: String) {
            val r = sh(*cmd)
            out.append(label).append(": ").append(if (r.isEmpty()) "ok" else r).append('\n')
        }
        step("appops set", "/system/bin/appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow")
        step("pm grant", "/system/bin/pm", "grant", pkg, "android.permission.SYSTEM_ALERT_WINDOW")
        step("appops get", "/system/bin/appops", "get", pkg, "SYSTEM_ALERT_WINDOW")
        return out.toString().trim()
    }

    override fun setEnabled(on: Boolean) {
        synchronized(lock) {
            enabled = on
            if (on) {
                restarts = 0
                lastError = ""
                if (proc?.isAlive != true && apkPath.isNotEmpty()) startDaemon()
            } else {
                stopDaemon() // 止めればカーネルがgrabを解除し、マウスは元に戻る
            }
        }
    }

    override fun status(): String {
        val state = when {
            !enabled -> "オフ"
            proc?.isAlive == true -> "動作中"
            else -> "停止中"
        }
        val devs = if (devices.isEmpty()) "  (対象のマウスなし)"
        else devices.entries.joinToString("\n") { "  ・${it.value.ifEmpty { it.key }} (${it.key})" }
        val err = if (lastError.isEmpty()) "" else "\nエラー: $lastError"
        val cursor = when {
            cursorOk -> "可能(OSのカーソル位置APIを使用)"
            listener != null -> "可能(アプリのオーバーレイ経由で位置を取得)"
            else -> "不可(アプリ未接続。マウス長押しで代用)"
        }
        return "リマップ: $state\n長押しタップ: $cursor\n対象デバイス:\n$devs\n最後の入力: $lastEvent$err"
    }

    // ---- デーモンの管理 ----

    private fun watchdog() {
        while (running) {
            try { Thread.sleep(3000) } catch (_: InterruptedException) {}
            synchronized(lock) {
                if (enabled && proc?.isAlive != true && restarts < 5 && apkPath.isNotEmpty()) {
                    restarts++
                    startDaemon()
                }
            }
        }
    }

    private fun startDaemon() {
        val bin = extractBinary() ?: return
        try {
            val p = ProcessBuilder(bin).redirectErrorStream(true).start()
            proc = p
            devices.clear()
            Thread({ readOutput(p) }, "daemon-out").apply { isDaemon = true }.start()
            val config = StringBuilder()
            lines.forEach { config.append(it).append('\n') }
            p.outputStream.write(config.toString().toByteArray())
            p.outputStream.flush()
        } catch (e: Exception) {
            lastError = "デーモン起動失敗: ${e.message}"
        }
    }

    private fun stopDaemon() {
        val p = proc
        proc = null
        devices.clear()
        p?.destroy()
    }

    private fun send(line: String) {
        val p = proc ?: return
        try {
            p.outputStream.write((line + "\n").toByteArray())
            p.outputStream.flush()
        } catch (_: IOException) {
        }
    }

    /** APK内の assets/mouseremapd/<abi>/mouseremapd を取り出して実行可能にする。 */
    private fun extractBinary(): String? {
        if (apkPath.isEmpty()) return null
        try {
            ZipFile(apkPath).use { zip ->
                for (abi in Build.SUPPORTED_ABIS) {
                    val entry = zip.getEntry("assets/mouseremapd/$abi/mouseremapd") ?: continue
                    val tmp = File("/data/local/tmp/mouseremapd.tmp")
                    val dst = File("/data/local/tmp/mouseremapd")
                    tmp.delete()
                    zip.getInputStream(entry).use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    Os.chmod(tmp.path, 493) // 0755
                    // 実行中のファイルは上書きできないので、renameで差し替える
                    if (!tmp.renameTo(dst)) {
                        lastError = "バイナリの配置に失敗しました"
                        return null
                    }
                    return dst.path
                }
            }
            lastError = "この端末のABI(${Build.SUPPORTED_ABIS.joinToString()})向けバイナリがありません"
        } catch (e: Exception) {
            lastError = "バイナリの取り出しに失敗: ${e.message}"
        }
        return null
    }

    private fun readOutput(p: Process) {
        try {
            p.inputStream.bufferedReader().forEachLine { handleLine(it) }
        } catch (_: IOException) {
        }
        val code = try { p.waitFor() } catch (_: InterruptedException) { -1 }
        synchronized(lock) {
            if (proc === p) { // stopDaemon()を経ない = 想定外の終了
                proc = null
                devices.clear()
                lastError = "デーモンが終了しました(code=$code)" +
                    if (lastError.isNotEmpty()) " / $lastError" else ""
            }
        }
    }

    // ---- 長押しタップ(カーソル位置へのタッチ長押し) ----

    private fun sh(vararg cmd: String): String = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.trim()
    } catch (e: Exception) {
        "error: ${e.message}"
    }

    /**
     * 右ボタンが押されたときの長押しタップ。
     * OSのAPIでカーソル位置が取れればそのまま送り、取れなければアプリにオーバーレイで位置を調べてもらう。
     */
    private fun touchLongPress() {
        val p = cursorPosition()
        if (p != null) {
            touchAt(Math.round(p[0]), Math.round(p[1]))
            return
        }
        val l = listener
        if (l == null) {
            lastError = "長押しタップを送れません(カーソル位置を取得できず、アプリとも未接続)"
            return
        }
        try {
            l.onRightPress()
        } catch (_: Exception) {
            lastError = "アプリが応答しません。アプリを開き直してください"
            synchronized(lock) {
                listener = null
                applyLines()
            }
        }
    }

    /** 指定座標にタッチの長押しを送る(同じ座標のスワイプ = 長押し)。 */
    private fun touchAt(x: Int, y: Int) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        lastEvent = "$time 長押しタップ ($x, $y) を送信"
        val r = sh("/system/bin/input", "touchscreen", "swipe", "$x", "$y", "$x", "$y", "$longPressMs")
        if (r.startsWith("error")) lastError = "input: $r"
    }

    /**
     * 入力サービス(IInputManager)から、マウスカーソルの画面座標(論理ディスプレイ)を得る。
     * この取得APIは Android 16 の後のQPR以降にしかないので、無い端末ではnullを返す。
     */
    private fun cursorPosition(): FloatArray? {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "input") as? IBinder
                ?: return null
            val im = Class.forName("android.hardware.input.IInputManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: return null
            for (name in arrayOf("getCursorPositionInLogicalDisplay", "getCursorPosition")) {
                for (m in im.javaClass.methods) {
                    if (m.name != name) continue
                    val result = try {
                        when {
                            m.parameterTypes.isEmpty() -> m.invoke(im)
                            m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType ->
                                m.invoke(im, 0) // DEFAULT_DISPLAY
                            else -> null
                        }
                    } catch (_: Throwable) {
                        null
                    }
                    toXY(result)?.let { return it }
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    private fun toXY(r: Any?): FloatArray? {
        if (r == null) return null
        val xy: FloatArray? = when (r) {
            is FloatArray -> if (r.size >= 2) floatArrayOf(r[0], r[1]) else null
            is DoubleArray -> if (r.size >= 2) floatArrayOf(r[0].toFloat(), r[1].toFloat()) else null
            is PointF -> floatArrayOf(r.x, r.y)
            else -> try {
                val x = r.javaClass.getField("x").get(r) as Number
                val y = r.javaClass.getField("y").get(r) as Number
                floatArrayOf(x.toFloat(), y.toFloat())
            } catch (_: Throwable) {
                null
            }
        }
        if (xy == null) return null
        // 有限で、画面として妥当な範囲のものだけ採用する(カーソルが無いときの -1 や NaN を除く)
        for (v in xy) if (!v.isFinite() || v < 0f || v > 20000f) return null
        return xy
    }

    private fun handleLine(line: String) {
        val f = line.split('\t')
        when (f[0]) {
            "ready" -> {}
            "dev+" -> devices[f.getOrElse(1) { "?" }] = f.getOrElse(2) { "" }
            "dev-" -> devices.remove(f.getOrElse(1) { "" })
            "evt" -> {
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                lastEvent = "$time ${f.drop(1).joinToString(" ")}"
            }
            "act" -> if (f.getOrNull(1) == NOTIFY_TOUCH_LONG.toString()) worker.execute { touchLongPress() }
            "err" -> lastError = f.drop(1).joinToString(" ")
            else -> if (line.isNotBlank()) lastError = line // リンクエラー等の生の出力
        }
    }
}
