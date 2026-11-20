package com.example.mouseremap

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast

/**
 * 右ボタンが押された位置(カーソル位置)を調べる仕掛け。OSにカーソル位置のAPIが無い端末(Android 15など)用。
 *
 * 1. 画面全体を覆う透明なオーバーレイを、一瞬だけ重ねる(フォーカスは奪わない)。
 * 2. shell側にカーソルを+1px動かして戻してもらうと、カーソルの下にあるオーバーレイにホバーイベントが届く。
 *    そのイベントの画面座標が、カーソル位置になる。
 * 3. オーバーレイを外してから、その座標に長押しタップを送る。
 */
object CursorProbe {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var appCtx: Context
    private var view: View? = null

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        handler.post {
            if (view != null) return@post // 実行中は無視
            if (!Settings.canDrawOverlays(appCtx)) {
                toast("オーバーレイ権限がありません。アプリで権限を許可してください")
                return@post
            }
            val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = View(appCtx)
            v.setOnHoverListener { _, e ->
                if (view === v) {
                    view = null
                    val x = Math.round(e.rawX)
                    val y = Math.round(e.rawY)
                    // ホバーの処理中にウィンドウを外さず、あとで外してから長押しタップを送る
                    handler.post {
                        remove(wm, v)
                        Bridge.touchAt(x, y)
                    }
                }
                true
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            try {
                wm.addView(v, lp)
                view = v
            } catch (e: Exception) {
                toast("オーバーレイを表示できませんでした: ${e.message}")
                return@post
            }
            // ウィンドウが入力システムに登録されるのを少し待ってから、合図を出す
            handler.postDelayed({ attempt(wm, v, 0) }, 150)
        }
    }

    private fun attempt(wm: WindowManager, v: View, n: Int) {
        if (view !== v) return // 取得済み
        if (n >= 4) {
            view = null
            remove(wm, v)
            toast("カーソル位置を取得できませんでした")
            return
        }
        Bridge.jitter()
        handler.postDelayed({ attempt(wm, v, n + 1) }, 250)
    }

    private fun remove(wm: WindowManager, v: View) {
        try {
            wm.removeViewImmediate(v)
        } catch (_: Exception) {
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show()
    }
}
