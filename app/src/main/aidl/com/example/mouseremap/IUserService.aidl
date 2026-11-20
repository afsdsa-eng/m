package com.example.mouseremap;

// AIDLは同じパッケージの型でも import が必要
import com.example.mouseremap.IMouseListener;

// Shizukuのshell権限プロセス(InputService)とアプリ本体をつなぐ
interface IUserService {
    // Shizukuが終了時に呼ぶ(この番号は固定)
    void destroy() = 16777114;

    // APKのパスを渡す(中のネイティブデーモンを取り出すため)
    void start(String apkPath) = 1;

    // 6スロット分の RemapAction.ordinal と、長押し(コンテキストメニュー)で左ボタンを押し続けるms
    void setMapping(in int[] actions, int longPressMs) = 2;

    // リマップのオン/オフ。オフにするとデーモンを止めてマウスを元の状態に戻す
    void setEnabled(boolean on) = 3;

    // 表示用の状態(対象デバイス、最後の入力、エラー)
    String status() = 4;

    // アプリ側の通知先を登録する(カーソル位置をオーバーレイで調べる方式に使う)
    void setListener(IMouseListener listener) = 5;

    // カーソルを+1px動かして戻す(オーバーレイにホバーを届けるための合図)
    oneway void jitter() = 6;

    // 指定した画面座標に、長押しタップ(タッチの長押し)を送る
    oneway void touchLongPressAt(int x, int y) = 7;

    // オーバーレイ表示の権限を shell 権限で付与する(appops)
    String grantOverlay(String pkg) = 8;
}
