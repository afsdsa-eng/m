package com.example.mouseremap

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.provider.Settings
import android.view.ViewConfiguration
import rikka.shizuku.Shizuku

/** アプリ側から Shizuku の UserService(InputService) へ接続・操作する窓口。 */
object Bridge {
    private lateinit var app: Context

    @Volatile var service: IUserService? = null
        private set
    @Volatile private var binding = false

    /** shell側から「右ボタンが押された」と知らされる窓口。 */
    private val listener = object : IMouseListener.Stub() {
        override fun onRightPress() {
            CursorProbe.start(app)
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            if (binder == null || !binder.pingBinder()) return
            service = IUserService.Stub.asInterface(binder)
            pushAll()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
        }
    }

    /** 端末の「長押しの時間」設定に、反映の遅れ分(150ms)を足した値。 */
    private fun longPressMs(): Int = ViewConfiguration.getLongPressTimeout() + 150

    fun init(c: Context) {
        app = c.applicationContext
    }

    fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun bind() {
        if (service != null || binding || !shizukuReady()) return
        try {
            binding = true
            val version = app.packageManager.getPackageInfo(app.packageName, 0)
                .longVersionCode.toInt()
            val args = Shizuku.UserServiceArgs(
                ComponentName(app.packageName, InputService::class.java.name)
            )
                .processNameSuffix("input")
                .daemon(true) // アプリが終了してもshell側で動き続ける
                .version(version)
            Shizuku.bindUserService(args, conn)
        } catch (e: Throwable) {
            binding = false
        }
    }

    fun onDead() {
        service = null
        binding = false
    }

    /** 接続直後に、APKパス・割り当て・通知先・オン/オフをまとめて送る(権限付与で待つので別スレッド)。 */
    private fun pushAll() {
        val s = service ?: return
        Thread {
            try {
                s.start(app.applicationInfo.sourceDir)
                s.setMapping(Prefs.all(app), longPressMs())
                s.setListener(listener)
                if (!Settings.canDrawOverlays(app)) s.grantOverlay(app.packageName)
                s.setEnabled(Prefs.enabled(app))
            } catch (_: Exception) {
            }
        }.start()
    }

    /** カーソルを+1px動かして戻す(オーバーレイにホバーを届ける合図)。 */
    fun jitter() {
        try {
            service?.jitter()
        } catch (_: Exception) {
        }
    }

    /** 指定した画面座標に長押しタップを送る。 */
    fun touchAt(x: Int, y: Int) {
        try {
            service?.touchLongPressAt(x, y)
        } catch (_: Exception) {
        }
    }

    /** オーバーレイ表示の権限をShizuku経由で付与する(待つので別スレッドから呼ぶ)。 */
    fun grantOverlay(): String = try {
        service?.grantOverlay(app.packageName) ?: "未接続"
    } catch (e: Exception) {
        "error"
    }

    fun pushMapping() {
        try {
            service?.setMapping(Prefs.all(app), longPressMs())
        } catch (_: Exception) {
        }
    }

    fun setEnabled(on: Boolean) {
        Prefs.setEnabled(app, on)
        try {
            service?.setEnabled(on)
        } catch (_: Exception) {
        }
    }
}
