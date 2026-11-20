package com.example.mouseremap

private fun key(code: Int) = arrayOf("/system/bin/input", "keyevent", code.toString())

/**
 * ボタンに割り当てる動作。
 * buttonCode > 0 : 別のマウスボタンとして送る(Linuxのevdevコード)
 * command != null: 押下時にshell権限でコマンドを実行する
 * longPress      : マウスの左ボタンを長押し時間だけ押し続ける(マウスの長押し。アプリによっては無視される)
 * touchLong      : カーソル位置にタッチの長押しを送る(長押しタップ)。カーソル位置を取得できない端末では longPress で代用
 */
enum class RemapAction(
    val label: String,
    val buttonCode: Int = 0,
    val command: Array<String>? = null,
    val longPress: Boolean = false,
    val touchLong: Boolean = false,
) {
    PASS_THROUGH("変更しない"),
    NONE("無効化(何もしない)"),
    CONTEXT_MENU("コンテキストメニュー(長押しタップで表示)", touchLong = true),
    MOUSE_LONG_PRESS("マウス長押し(左ボタンを押し続ける)", longPress = true),
    MOUSE_LEFT("マウス左ボタン", 0x110),
    MOUSE_RIGHT("マウス右ボタン", 0x111),
    MOUSE_MIDDLE("マウス中ボタン", 0x112),
    MOUSE_BACK("マウス戻るボタン", 0x113),
    MOUSE_FORWARD("マウス進むボタン", 0x114),
    MENU_KEY("Android: メニューキー", command = key(82)),
    BACK("Android: 戻る", command = key(4)),
    HOME("Android: ホーム", command = key(3)),
    RECENTS("Android: 最近のアプリ", command = key(187)),
    NOTIFICATIONS("Android: 通知パネル", command = arrayOf("/system/bin/cmd", "statusbar", "expand-notifications")),
    QUICK_SETTINGS("Android: クイック設定", command = arrayOf("/system/bin/cmd", "statusbar", "expand-settings")),
    SCREENSHOT("Android: スクリーンショット", command = key(120)),
    SCREEN_OFF("Android: 画面オフ", command = key(223)),
    PLAY_PAUSE("メディア: 再生/一時停止", command = key(85)),
    NEXT_TRACK("メディア: 次の曲", command = key(87)),
    PREV_TRACK("メディア: 前の曲", command = key(88)),
    VOLUME_UP("音量 +", command = key(24)),
    VOLUME_DOWN("音量 -", command = key(25)),
    MUTE("ミュート", command = key(164));

    /** ネイティブデーモンへ送る設定行。 */
    fun toProtocol(slot: Int, longPressMs: Int, touchOk: Boolean = false): String = when {
        this == PASS_THROUGH -> "slot $slot pass"
        this == NONE -> "slot $slot none"
        touchLong && touchOk -> "slot $slot notify $NOTIFY_TOUCH_LONG"
        touchLong || longPress -> "slot $slot long $longPressMs"
        buttonCode > 0 -> "slot $slot btn $buttonCode"
        command != null -> "slot $slot cmd ${command.joinToString(" ")}"
        else -> "slot $slot pass"
    }
}

/** デーモンが「act <id>」で知らせる動作の番号。1 = カーソル位置への長押しタップ。 */
const val NOTIFY_TOUCH_LONG = 1
