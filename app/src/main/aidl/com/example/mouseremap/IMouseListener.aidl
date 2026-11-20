package com.example.mouseremap;

// shell側のサービス -> アプリ への通知
oneway interface IMouseListener {
    // 右ボタン(長押しタップ割り当て)が押された。アプリはカーソル位置を調べて長押しタップを送る
    void onRightPress() = 1;
}
