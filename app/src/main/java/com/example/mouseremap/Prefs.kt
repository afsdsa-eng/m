package com.example.mouseremap

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val SLOT_LEFT = 0
    const val SLOT_RIGHT = 1
    const val SLOT_MIDDLE = 2
    const val SLOT_BACK = 3
    const val SLOT_FORWARD = 4
    const val SLOT_TASK = 5
    const val SLOT_COUNT = 6

    private fun store(c: Context): SharedPreferences =
        c.getSharedPreferences("mouse_remap", Context.MODE_PRIVATE)

    /** 未設定時の既定値: 右ボタンはコンテキストメニュー、他はそのまま。 */
    private fun defaultFor(slot: Int) =
        if (slot == SLOT_RIGHT) RemapAction.CONTEXT_MENU else RemapAction.PASS_THROUGH

    fun action(c: Context, slot: Int): RemapAction {
        val name = store(c).getString("slot_$slot", null) ?: return defaultFor(slot)
        return runCatching { RemapAction.valueOf(name) }.getOrDefault(defaultFor(slot))
    }

    fun setAction(c: Context, slot: Int, a: RemapAction) {
        store(c).edit().putString("slot_$slot", a.name).apply()
    }

    fun all(c: Context): IntArray = IntArray(SLOT_COUNT) { action(c, it).ordinal }

    /** 安全のため初期値はオフ。 */
    fun enabled(c: Context): Boolean = store(c).getBoolean("enabled", false)

    fun setEnabled(c: Context, on: Boolean) {
        store(c).edit().putBoolean("enabled", on).apply()
    }
}
