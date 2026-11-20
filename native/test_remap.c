/* ボタン付け替えロジックの単体テスト(実デバイス不要)。 gcc native/test_remap.c && ./a.out */
#define main daemon_main
#include "mouseremapd.c"
#undef main
#include <assert.h>

static struct dev D;
static int rfd;

static void feed(int type, int code, int value) {
    struct input_event e;
    memset(&e, 0, sizeof e);
    e.type = (unsigned short)type;
    e.code = (unsigned short)code;
    e.value = value;
    handle(&D, &e);
}

static int drain(struct input_event *out, int max) {
    int n = 0;
    while (n < max && read(rfd, &out[n], sizeof out[n]) == (ssize_t)sizeof out[n]) n++;
    return n;
}

static void line(const char *s) {
    char buf[256];
    snprintf(buf, sizeof buf, "%s", s);
    handle_line(buf);
}

#define EXPECT_ONE(t, c, v)                                              \
    do {                                                                 \
        struct input_event o[8];                                         \
        int n = drain(o, 8);                                             \
        assert(n == 1);                                                  \
        assert(o[0].type == (t) && o[0].code == (c) && o[0].value == (v)); \
    } while (0)
#define EXPECT_NONE() do { struct input_event o[8]; assert(drain(o, 8) == 0); } while (0)

int main(void) {
    int p[2], i;
    assert(pipe2(p, O_NONBLOCK) == 0);
    rfd = p[0];
    D.used = 1;
    D.out_fd = p[1];
    for (i = 0; i < NBTN; i++) D.held[i] = HELD_NONE;
    for (i = 0; i < NSLOT; i++) cfg[i].mode = M_PASS;

    /* 1. 既定は素通し */
    feed(EV_KEY, BTN_LEFT, 1); EXPECT_ONE(EV_KEY, BTN_LEFT, 1);
    feed(EV_KEY, BTN_LEFT, 0); EXPECT_ONE(EV_KEY, BTN_LEFT, 0);

    /* 2. 左右入れ替え */
    line("slot 0 btn 273");
    line("slot 1 btn 272");
    feed(EV_KEY, BTN_LEFT, 1);  EXPECT_ONE(EV_KEY, BTN_RIGHT, 1);
    feed(EV_KEY, BTN_LEFT, 0);  EXPECT_ONE(EV_KEY, BTN_RIGHT, 0);
    feed(EV_KEY, BTN_RIGHT, 1); EXPECT_ONE(EV_KEY, BTN_LEFT, 1);
    feed(EV_KEY, BTN_RIGHT, 0); EXPECT_ONE(EV_KEY, BTN_LEFT, 0);

    /* 3. 押している途中で設定を変えても、離す側は押したときの送出先に従う */
    feed(EV_KEY, BTN_LEFT, 1);  EXPECT_ONE(EV_KEY, BTN_RIGHT, 1);
    line("slot 0 pass");
    feed(EV_KEY, BTN_LEFT, 0);  EXPECT_ONE(EV_KEY, BTN_RIGHT, 0);
    feed(EV_KEY, BTN_LEFT, 1);  EXPECT_ONE(EV_KEY, BTN_LEFT, 1); /* 以後は素通し */
    feed(EV_KEY, BTN_LEFT, 0);  EXPECT_ONE(EV_KEY, BTN_LEFT, 0);

    /* 4. 無効化: 押下も解除も出力されない */
    line("slot 2 none");
    feed(EV_KEY, BTN_MIDDLE, 1); EXPECT_NONE();
    feed(EV_KEY, BTN_MIDDLE, 0); EXPECT_NONE();

    /* 5. サイドボタンは SIDE/BACK の両コードが「戻る」スロット、EXTRA/FORWARD が「進む」 */
    line("slot 3 btn 274");   /* 戻る -> 中ボタン */
    line("slot 4 btn 275");   /* 進む -> SIDE     */
    feed(EV_KEY, BTN_SIDE, 1);    EXPECT_ONE(EV_KEY, BTN_MIDDLE, 1);
    feed(EV_KEY, BTN_SIDE, 0);    EXPECT_ONE(EV_KEY, BTN_MIDDLE, 0);
    feed(EV_KEY, BTN_BACK, 1);    EXPECT_ONE(EV_KEY, BTN_MIDDLE, 1);
    feed(EV_KEY, BTN_BACK, 0);    EXPECT_ONE(EV_KEY, BTN_MIDDLE, 0);
    feed(EV_KEY, BTN_EXTRA, 1);   EXPECT_ONE(EV_KEY, BTN_SIDE, 1);
    feed(EV_KEY, BTN_EXTRA, 0);   EXPECT_ONE(EV_KEY, BTN_SIDE, 0);
    feed(EV_KEY, BTN_FORWARD, 1); EXPECT_ONE(EV_KEY, BTN_SIDE, 1);
    feed(EV_KEY, BTN_FORWARD, 0); EXPECT_ONE(EV_KEY, BTN_SIDE, 0);

    /* 6. コマンド割り当て: ボタンイベントは破棄され、コマンドが実行される */
    line("slot 5 cmd /bin/true a b");
    assert(cfg[5].mode == M_CMD && strcmp(cfg[5].argv[0], "/bin/true") == 0 && strcmp(cfg[5].argv[2], "b") == 0);
    feed(EV_KEY, BTN_TASK, 1); EXPECT_NONE();
    feed(EV_KEY, BTN_TASK, 0); EXPECT_NONE();

    /* 7. 不正な設定は無視される */
    line("slot 3 btn 300");   /* 範囲外 */
    assert(cfg[3].mode == M_BTN && cfg[3].code == 274);
    line("slot 9 none");      /* スロット番号が範囲外 */
    line("garbage");
    line("slot 1");

    /* 8. 移動・ホイール・SYNは素通し、MSCは捨てる */
    feed(EV_REL, REL_X, 5);              EXPECT_ONE(EV_REL, REL_X, 5);
    feed(EV_REL, REL_WHEEL_HI_RES, -120); EXPECT_ONE(EV_REL, REL_WHEEL_HI_RES, -120);
    feed(EV_MSC, MSC_SCAN, 90001);       EXPECT_NONE();
    feed(EV_SYN, SYN_REPORT, 0);         EXPECT_ONE(EV_SYN, SYN_REPORT, 0);

    /* 9. マウスボタン以外のキー(BTN_TOUCH等)は素通し */
    feed(EV_KEY, BTN_TOUCH, 1); EXPECT_ONE(EV_KEY, BTN_TOUCH, 1);

    /* 10. 押下を見ずに離しだけ来た場合は、元のコードでそのまま流す */
    line("slot 0 btn 273");
    feed(EV_KEY, BTN_LEFT, 0); EXPECT_ONE(EV_KEY, BTN_LEFT, 0);

    /* 11. 長押しの模擬(コンテキストメニュー) */
    line("slot 1 long 500");
    assert(cfg[1].mode == M_LONG && cfg[1].code == 500);
    feed(EV_KEY, BTN_RIGHT, 1);  EXPECT_ONE(EV_KEY, BTN_LEFT, 1);  /* 左ボタンを押し始める */
    assert(D.lp_active);
    feed(EV_REL, REL_X, 7);      EXPECT_NONE();                    /* 模擬中はカーソル固定 */
    feed(EV_REL, REL_Y, -3);     EXPECT_NONE();
    feed(EV_REL, REL_WHEEL, 1);  EXPECT_ONE(EV_REL, REL_WHEEL, 1); /* ホイールは通す */
    feed(EV_KEY, BTN_RIGHT, 0);  EXPECT_NONE();                    /* 早く離しても左はまだ離さない */
    assert(D.lp_active);
    feed(EV_KEY, BTN_RIGHT, 1);  EXPECT_NONE();                    /* 模擬中の再押下は無視 */
    feed(EV_KEY, BTN_RIGHT, 0);  EXPECT_NONE();
    check_timers(&D);            EXPECT_NONE();                    /* まだ時間前 */
    assert(D.lp_active);
    D.lp_release_at = now_ms() - 1;                                /* 時間が来た */
    check_timers(&D);
    {
        struct input_event o[4];
        int n = drain(o, 4);
        assert(n == 2);
        assert(o[0].type == EV_KEY && o[0].code == BTN_LEFT && o[0].value == 0); /* 左を離す */
        assert(o[1].type == EV_SYN);                                              /* 反映 */
    }
    assert(!D.lp_active);
    feed(EV_REL, REL_X, 7);      EXPECT_ONE(EV_REL, REL_X, 7);     /* 以後はカーソルが動く */

    /* 12. 右ボタンを長く押し続けた場合: 時間が来たら離し、物理ボタンの解除は何も出さない */
    feed(EV_KEY, BTN_RIGHT, 1);  EXPECT_ONE(EV_KEY, BTN_LEFT, 1);
    D.lp_release_at = now_ms() - 1;
    check_timers(&D);
    {
        struct input_event o[4];
        assert(drain(o, 4) == 2);
    }
    feed(EV_KEY, BTN_RIGHT, 0);  EXPECT_NONE();

    /* 13. 範囲外の長押し時間は無視される */
    line("slot 1 long 10");
    line("slot 1 long 99999");
    assert(cfg[1].mode == M_LONG && cfg[1].code == 500);

    /* 14. notify: 押下でホストへ通知し、ボタンイベント自体は破棄する */
    {
        char out[512];
        int saved, n;
        FILE *f;
        line("slot 1 notify 7");
        assert(cfg[1].mode == M_NOTIFY && cfg[1].code == 7);
        fflush(stdout);
        saved = dup(1);
        f = tmpfile();
        assert(f != NULL && saved >= 0);
        dup2(fileno(f), 1);
        feed(EV_KEY, BTN_RIGHT, 1);
        feed(EV_KEY, BTN_RIGHT, 0);
        fflush(stdout);
        dup2(saved, 1);
        close(saved);
        rewind(f);
        n = (int)fread(out, 1, sizeof out - 1, f);
        out[n] = 0;
        fclose(f);
        assert(strstr(out, "act\t7\n") != NULL);   /* 押下で1回だけ通知 */
        {
            int cnt = 0;
            const char *q = out;
            while ((q = strstr(q, "act\t7\n")) != NULL) { cnt++; q++; }
            assert(cnt == 1);
        }
        EXPECT_NONE();                              /* 仮想マウスへは何も送らない */
    }
    line("slot 1 notify 0");
    line("slot 1 notify 100");
    assert(cfg[1].mode == M_NOTIFY && cfg[1].code == 7);

    /* 15. jitter: 接続中の全マウスで +1 → SYN → -1 → SYN を出す */
    memset(&devs[0], 0, sizeof devs[0]);
    devs[0].used = 1;
    devs[0].out_fd = D.out_fd;
    line("jitter");
    {
        struct input_event o[8];
        int n = drain(o, 8);
        assert(n == 4);
        assert(o[0].type == EV_REL && o[0].code == REL_X && o[0].value == 1);
        assert(o[1].type == EV_SYN);
        assert(o[2].type == EV_REL && o[2].code == REL_X && o[2].value == -1);
        assert(o[3].type == EV_SYN);
    }
    devs[0].used = 0;
    line("jitter");   /* 接続中のマウスが無ければ何も出さない */
    EXPECT_NONE();

    printf("ALL TESTS PASSED\n");
    return 0;
}
