package com.example.mouseremap

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private lateinit var shizukuStatus: TextView
    private lateinit var live: TextView
    private lateinit var enableSwitch: Switch
    private var updatingSwitch = false
    private val spinners = arrayOfNulls<Spinner>(Prefs.SLOT_COUNT)

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) Bridge.bind()
        refresh()
    }

    private val slots = listOf(
        Prefs.SLOT_LEFT to "左ボタン",
        Prefs.SLOT_RIGHT to "右ボタン",
        Prefs.SLOT_MIDDLE to "中ボタン(ホイール押し込み)",
        Prefs.SLOT_BACK to "戻るボタン(サイド手前)",
        Prefs.SLOT_FORWARD to "進むボタン(サイド奥)",
        Prefs.SLOT_TASK to "追加ボタン(6ボタン以上のマウス用)",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permListener)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        fun label(text: String, size: Float = 14f) = TextView(this).apply {
            this.text = text
            textSize = size
            setPadding(0, dp(14), 0, dp(4))
        }

        col.addView(label("マウスボタン設定 (Shizuku + ネイティブ)", 22f))
        shizukuStatus = label("")
        col.addView(shizukuStatus)

        col.addView(Button(this).apply {
            text = "Shizukuを開く"
            setOnClickListener {
                val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (i != null) startActivity(i) else toast("Shizukuがインストールされていません")
            }
        })
        col.addView(Button(this).apply {
            text = "Shizukuの権限を許可する"
            setOnClickListener { requestShizukuPermission() }
        })

        enableSwitch = Switch(this).apply {
            text = "リマップを有効にする"
            isChecked = Prefs.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                if (!updatingSwitch) {
                    Bridge.setEnabled(checked)
                    updateKeepAlive(checked)
                }
            }
        }
        col.addView(label("", 6f))
        col.addView(enableSwitch)
        col.addView(label(
            "有効にするとマウスを占有し、仮想マウス経由で操作を再送します。" +
                "マウスが反応しなくなったら、タッチ操作で本アプリを開いてオフにしてください(すぐ元に戻ります)。",
            12f
        ))

        for ((slot, title) in slots) {
            col.addView(label(title))
            col.addView(actionSpinner(slot))
        }

        col.addView(label(
            "「コンテキストメニュー(長押しタップで表示)」は、右ボタンを押した位置にタッチの長押しを送ります" +
                "(右ボタンの初期設定)。位置は、OSのカーソル位置API(Android 16のQPR以降)で調べ、" +
                "無ければ一瞬だけ透明なオーバーレイを重ねて調べます。オーバーレイ権限が必要で、" +
                "アプリをタスクから消すと動かなくなります。" +
                "Chromeなど右クリックに元から対応するアプリでは、「変更しない」のほうが自然です。",
            12f
        ))
        col.addView(Button(this).apply {
            text = "オーバーレイ権限を許可(Shizuku経由)"
            setOnClickListener {
                Thread {
                    val r = Bridge.grantOverlay()
                    runOnUiThread {
                        val now = if (Settings.canDrawOverlays(this@MainActivity)) "許可済み" else "未許可"
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("オーバーレイ権限: $now")
                            .setMessage(r)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }.start()
            }
        })
        col.addView(Button(this).apply {
            text = "オーバーレイ権限の設定画面を開く(手動で許可)"
            setOnClickListener {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    toast("設定画面を開けませんでした。設定 > アプリ > MouseRemap > 他のアプリの上に重ねて表示 から許可してください")
                }
            }
        })
        col.addView(Button(this).apply {
            text = "左右ボタンを入れ替える"
            setOnClickListener {
                choose(Prefs.SLOT_LEFT, RemapAction.MOUSE_RIGHT)
                choose(Prefs.SLOT_RIGHT, RemapAction.MOUSE_LEFT)
            }
        })
        col.addView(Button(this).apply {
            text = "すべて元に戻す"
            setOnClickListener {
                for (s in 0 until Prefs.SLOT_COUNT) choose(s, RemapAction.PASS_THROUGH)
            }
        })

        col.addView(label("状態", 16f))
        live = label("", 13f)
        col.addView(live)

        setContentView(ScrollView(this).apply { addView(col) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2)
        }
        if (Prefs.enabled(this)) updateKeepAlive(true)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permListener)
    }

    /** リマップが有効な間だけ、アプリのプロセスを生かす常駐サービスを動かす。 */
    private fun updateKeepAlive(on: Boolean) {
        val i = Intent(this, KeepAliveService::class.java)
        if (on) {
            try {
                startForegroundService(i)
            } catch (_: Exception) {
            }
        } else {
            stopService(i)
        }
    }

    private fun requestShizukuPermission() {
        when {
            !Shizuku.pingBinder() -> toast("先にShizukuアプリでShizukuを起動してください")
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> Bridge.bind()
            Shizuku.shouldShowRequestPermissionRationale() ->
                toast("権限が拒否されています。Shizukuアプリ側で許可してください")
            else -> Shizuku.requestPermission(100)
        }
    }

    private fun refresh() {
        val installed = try {
            packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true
        } catch (e: Exception) {
            false
        }
        val state = when {
            !installed -> "Shizukuがインストールされていません"
            !Shizuku.pingBinder() -> "Shizukuが起動していません(Shizukuアプリで開始してください)"
            !Bridge.shizukuReady() -> "権限が未許可です(「Shizukuの権限を許可する」を押してください)"
            Bridge.service == null -> "サービスに接続中…"
            else -> "接続済み"
        }
        shizukuStatus.text = "Shizuku: $state"

        val s = Bridge.service
        val base = if (s != null) runCatching { s.status() }.getOrDefault("(取得失敗)") else "(未接続)"
        val overlay = if (Settings.canDrawOverlays(this)) "許可済み" else "未許可"
        live.text = base + "\nオーバーレイ権限: " + overlay
    }

    private fun actionSpinner(slot: Int): Spinner = Spinner(this).apply {
        val items = RemapAction.values()
        adapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_dropdown_item, items.map { it.label }
        )
        setSelection(Prefs.action(context, slot).ordinal)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                Prefs.setAction(context, slot, items[pos])
                Bridge.pushMapping()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spinners[slot] = this
    }

    private fun choose(slot: Int, a: RemapAction) {
        spinners[slot]?.setSelection(a.ordinal)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
