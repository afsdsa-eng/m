/*
 * mouseremapd - マウスを EVIOCGRAB で占有し、ボタンを付け替えて uinput の仮想マウスへ再送する。
 * shell(uid 2000) 権限で実行する想定(Shizuku / adb)。
 *
 * stdin  (1行1コマンド):
 *     slot <0-5> pass                 そのまま
 *     slot <0-5> none                 無効化
 *     slot <0-5> btn <272-279>        別のマウスボタン(BTN_LEFT=272 ... BTN_TASK=279)として送る
 *     slot <0-5> cmd <絶対パス> <引数>  押下時にコマンドを実行(ボタンイベント自体は破棄)
 *     slot <0-5> notify <1-99>        押下でホストへ「act <id>」を通知(ボタンイベント自体は破棄)
 *     slot <0-5> long <ms>            押下で左ボタンを<ms>だけ押し続ける = 長押し(コンテキストメニュー)
 *     jitter                          カーソルを+1px動かして戻す(オーバーレイでカーソル位置を知る合図)
 *                                     押している間はカーソルを固定し、手ブレでドラッグ扱いになるのを防ぐ
 *   スロット: 0=左 1=右 2=中 3=戻る(SIDE/BACK) 4=進む(EXTRA/FORWARD) 5=TASK
 * stdout (タブ区切り):
 *     ready / dev+ <path> <name> / dev- <path> / evt <text> / act <id> / err <text>
 *
 * stdin が閉じられた(親が終了した)ら、全デバイスを解放して終了する。
 * プロセスが死ねばカーネルが grab と仮想デバイスを自動で解放するので、マウスは必ず元に戻る。
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#define NSLOT 6
#define MAX_DEV 8
#define MAX_ARGS 12
#define NBTN 8 /* BTN_LEFT(0x110) .. BTN_TASK(0x117) */
#define HELD_NONE (-100)
#define VNAME_PREFIX "MouseRemap "
#define LBITS (sizeof(unsigned long) * 8)
#define NLONGS(n) (((n) + LBITS - 1) / LBITS)

enum { M_PASS, M_NONE, M_BTN, M_CMD, M_LONG, M_NOTIFY };

struct slotcfg {
    int mode;
    int code;
    char *buf;
    char *argv[MAX_ARGS + 1];
};

struct dev {
    int used;
    int in_fd;
    int out_fd;
    char path[320];
    char name[96];
    int held[NBTN]; /* 押下中ボタンの送出先: -1=同じコード, -2=破棄, >0=別コード */
    int lp_active;      /* 長押しの模擬中(左ボタンを押し続けている) */
    long lp_release_at; /* 左ボタンを離す時刻(CLOCK_MONOTONIC, ms) */
};

static struct slotcfg cfg[NSLOT];
static struct dev devs[MAX_DEV];

static void say(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    fflush(stdout);
}

static void report_err(const char *what, int err) {
    static char last[200];
    char cur[200];
    snprintf(cur, sizeof cur, "%s: %s", what, strerror(err));
    if (strcmp(cur, last) == 0) return; /* 同じエラーを繰り返し出さない */
    snprintf(last, sizeof last, "%s", cur);
    say("err\t%s\n", cur);
}

static int test_bit(int bit, const unsigned long *a) {
    return (int)((a[bit / LBITS] >> (bit % LBITS)) & 1UL);
}

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

static int slot_of(int code) {
    switch (code) {
    case BTN_LEFT: return 0;
    case BTN_RIGHT: return 1;
    case BTN_MIDDLE: return 2;
    case BTN_SIDE:
    case BTN_BACK: return 3;
    case BTN_EXTRA:
    case BTN_FORWARD: return 4;
    case BTN_TASK: return 5;
    default: return -1;
    }
}

static void run_cmd(char *const argv[]) {
    pid_t pid = fork();
    if (pid == 0) {
        int nul = open("/dev/null", O_RDWR);
        if (nul >= 0) {
            dup2(nul, 0);
            dup2(nul, 1);
            dup2(nul, 2);
        }
        execv(argv[0], argv);
        _exit(127);
    }
}

/* ---------- uinput ---------- */

static int create_uinput(const char *name, const struct input_id *id,
                         const unsigned long *keybits, const unsigned long *relbits) {
    struct uinput_user_dev uud;
    int c;
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) fd = open("/dev/input/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -1;

    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 || ioctl(fd, UI_SET_EVBIT, EV_REL) < 0) goto fail;
    /* 元のマウスに無いボタンへも割り当てできるよう、マウスボタン8個は常に有効にする */
    for (c = BTN_LEFT; c <= BTN_TASK; c++) ioctl(fd, UI_SET_KEYBIT, c);
    for (c = 0; c <= KEY_MAX; c++)
        if (test_bit(c, keybits)) ioctl(fd, UI_SET_KEYBIT, c);
    for (c = 0; c <= REL_MAX; c++)
        if (test_bit(c, relbits)) ioctl(fd, UI_SET_RELBIT, c);

    memset(&uud, 0, sizeof uud);
    snprintf(uud.name, UINPUT_MAX_NAME_SIZE, "%s%.*s", VNAME_PREFIX,
             (int)(UINPUT_MAX_NAME_SIZE - 1 - strlen(VNAME_PREFIX)), name);
    uud.id = *id;
    /* 旧式(write)の登録方法。古いカーネルでも新しいカーネルでも使える */
    if (write(fd, &uud, sizeof uud) != (ssize_t)sizeof uud) goto fail;
    if (ioctl(fd, UI_DEV_CREATE) < 0) goto fail;
    return fd;
fail: {
    int e = errno;
    close(fd);
    errno = e;
    return -1;
}
}

/* ---------- デバイスの取り付け/取り外し ---------- */

static int already_open(const char *path) {
    int i;
    for (i = 0; i < MAX_DEV; i++)
        if (devs[i].used && strcmp(devs[i].path, path) == 0) return 1;
    return 0;
}

static void attach(const char *path) {
    unsigned long evbits[NLONGS(EV_MAX + 1)];
    unsigned long keybits[NLONGS(KEY_MAX + 1)];
    unsigned long relbits[NLONGS(REL_MAX + 1)];
    char name[96];
    struct input_id id;
    struct dev *d = NULL;
    int i, in, out;

    if (already_open(path)) return;
    for (i = 0; i < MAX_DEV; i++)
        if (!devs[i].used) { d = &devs[i]; break; }
    if (!d) return;

    in = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (in < 0) {
        report_err(path, errno);
        return;
    }
    memset(evbits, 0, sizeof evbits);
    memset(keybits, 0, sizeof keybits);
    memset(relbits, 0, sizeof relbits);
    memset(name, 0, sizeof name);
    memset(&id, 0, sizeof id);

    if (ioctl(in, EVIOCGNAME(sizeof name - 1), name) < 0) name[0] = 0;
    /* 自分が作った仮想デバイスは対象外(無限ループ防止) */
    if (strncmp(name, VNAME_PREFIX, strlen(VNAME_PREFIX)) == 0) { close(in); return; }

    ioctl(in, EVIOCGBIT(0, sizeof evbits), evbits);
    if (!test_bit(EV_REL, evbits) || !test_bit(EV_KEY, evbits)) { close(in); return; }
    ioctl(in, EVIOCGBIT(EV_KEY, sizeof keybits), keybits);
    ioctl(in, EVIOCGBIT(EV_REL, sizeof relbits), relbits);
    /* マウス = 相対移動 + 左ボタン。キーボード(KEY_Aを持つ)は占有しない */
    if (!test_bit(REL_X, relbits) || !test_bit(REL_Y, relbits) ||
        !test_bit(BTN_LEFT, keybits) || test_bit(KEY_A, keybits)) {
        close(in);
        return;
    }
    ioctl(in, EVIOCGID, &id);

    /* 先に仮想マウスを作ってから占有する。失敗したら何も変えずに戻る */
    out = create_uinput(name, &id, keybits, relbits);
    if (out < 0) {
        report_err("uinput(/dev/uinput)", errno);
        close(in);
        return;
    }
    if (ioctl(in, EVIOCGRAB, 1) < 0) {
        report_err("EVIOCGRAB", errno);
        ioctl(out, UI_DEV_DESTROY);
        close(out);
        close(in);
        return;
    }

    memset(d, 0, sizeof *d);
    d->used = 1;
    d->in_fd = in;
    d->out_fd = out;
    snprintf(d->path, sizeof d->path, "%s", path);
    snprintf(d->name, sizeof d->name, "%s", name);
    for (i = 0; i < NBTN; i++) d->held[i] = HELD_NONE;
    say("dev+\t%s\t%s\n", d->path, d->name);
}

static void detach(struct dev *d) {
    ioctl(d->in_fd, EVIOCGRAB, 0);
    close(d->in_fd);
    ioctl(d->out_fd, UI_DEV_DESTROY);
    close(d->out_fd);
    say("dev-\t%s\n", d->path);
    d->used = 0;
}

static void scan(void) {
    struct dirent *e;
    char path[320];
    DIR *dir = opendir("/dev/input");
    if (!dir) {
        report_err("opendir(/dev/input)", errno);
        return;
    }
    while ((e = readdir(dir)) != NULL) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;
        snprintf(path, sizeof path, "/dev/input/%s", e->d_name);
        attach(path);
    }
    closedir(dir);
}

/* ---------- イベント処理 ---------- */

static void emit(struct dev *d, const struct input_event *ev) {
    if (write(d->out_fd, ev, sizeof *ev) < 0) { /* EAGAIN等は無視 */ }
}

/* 押下時の送出先を決める: -1=同じコード, -2=破棄, >0=別コード */
static int on_press(int code, int slot) {
    struct slotcfg *c = &cfg[slot];
    switch (c->mode) {
    case M_NONE:
        say("evt\t0x%x (slot %d) -> disabled\n", code, slot);
        return -2;
    case M_BTN:
        say("evt\t0x%x (slot %d) -> button 0x%x\n", code, slot, c->code);
        return c->code;
    case M_CMD:
        say("evt\t0x%x (slot %d) -> run %s\n", code, slot, c->argv[0]);
        run_cmd(c->argv);
        return -2;
    case M_LONG:
        say("evt\t0x%x (slot %d) -> long press (context menu)\n", code, slot);
        return -3;
    case M_NOTIFY:
        say("evt\t0x%x (slot %d) -> notify %d\n", code, slot, c->code);
        say("act\t%d\n", c->code);
        return -2;
    default:
        say("evt\t0x%x (slot %d) -> pass\n", code, slot);
        return -1;
    }
}

static void emit_key(struct dev *d, int code, int value) {
    struct input_event e;
    memset(&e, 0, sizeof e);
    e.type = EV_KEY;
    e.code = (unsigned short)code;
    e.value = value;
    emit(d, &e);
}

static void emit_syn(struct dev *d) {
    struct input_event e;
    memset(&e, 0, sizeof e);
    e.type = EV_SYN;
    e.code = SYN_REPORT;
    emit(d, &e);
}

/* 長押しの模擬を終える: 押し続けていた左ボタンを離す */
static void finish_long(struct dev *d) {
    d->lp_active = 0;
    emit_key(d, BTN_LEFT, 0);
}

/* 時間が来ていたら長押しの模擬を終える(メインループから毎回呼ぶ) */
static void check_timers(struct dev *d) {
    if (d->lp_active && now_ms() >= d->lp_release_at) {
        finish_long(d);
        emit_syn(d);
    }
}

static void handle(struct dev *d, const struct input_event *ev) {
    struct input_event out;
    if (ev->type == EV_MSC) return; /* スキャンコードは不要 */

    /* 長押しの模擬中はカーソルを動かさない(手ブレでドラッグ扱いになるのを防ぐ) */
    if (ev->type == EV_REL && d->lp_active && (ev->code == REL_X || ev->code == REL_Y)) return;

    if (ev->type == EV_KEY && ev->value != 2) {
        int slot = slot_of(ev->code);
        if (slot >= 0) {
            int idx = ev->code - BTN_LEFT;
            int target;
            if (ev->value == 1) {
                target = on_press(ev->code, slot);
                if (target == -3) { /* 長押しの模擬 */
                    if (!d->lp_active) {
                        d->lp_active = 1;
                        d->lp_release_at = now_ms() + cfg[slot].code;
                        emit_key(d, BTN_LEFT, 1);
                    }
                    target = -2; /* 元のボタンイベント自体は捨てる(離すのはタイマー側) */
                }
                d->held[idx] = target;
            } else {
                /* 離したときは、押したときに決めた送出先を使う(設定変更で食い違わないように) */
                target = d->held[idx];
                d->held[idx] = HELD_NONE;
                if (target == HELD_NONE) target = -1;
            }
            if (target == -2) return;
            out = *ev;
            if (target > 0) out.code = (unsigned short)target;
            emit(d, &out);
            return;
        }
    }
    emit(d, ev);
}

static void emit_rel(struct dev *d, int code, int value) {
    struct input_event e;
    memset(&e, 0, sizeof e);
    e.type = EV_REL;
    e.code = (unsigned short)code;
    e.value = value;
    emit(d, &e);
}

/*
 * カーソルを+1px動かして戻す。動きが無いとアプリ側のオーバーレイにホバーが届かないので、その合図に使う。
 * Androidのポインタ加速は速度で決まるため、戻す動きは30msあけて、行きと帰りで量がずれないようにする。
 * (画面の右端では+1が効かないが、戻しの-1が効くので、どちらかは必ず動く)
 */
static void jitter(void) {
    int i;
    for (i = 0; i < MAX_DEV; i++)
        if (devs[i].used) { emit_rel(&devs[i], REL_X, 1); emit_syn(&devs[i]); }
    usleep(30000);
    for (i = 0; i < MAX_DEV; i++)
        if (devs[i].used) { emit_rel(&devs[i], REL_X, -1); emit_syn(&devs[i]); }
}

/* ---------- 設定コマンド(stdin) ---------- */

static void handle_line(char *line) {
    char *save = NULL;
    char *cmd = strtok_r(line, " \t\r\n", &save);
    char *ns, *mode;
    struct slotcfg *c;
    int s;

    if (!cmd) return;
    if (strcmp(cmd, "jitter") == 0) {
        jitter();
        return;
    }
    if (strcmp(cmd, "slot") != 0) return;
    ns = strtok_r(NULL, " \t\r\n", &save);
    mode = strtok_r(NULL, " \t\r\n", &save);
    if (!ns || !mode) return;
    s = atoi(ns);
    if (s < 0 || s >= NSLOT) return;
    c = &cfg[s];

    if (strcmp(mode, "pass") == 0) {
        c->mode = M_PASS;
    } else if (strcmp(mode, "none") == 0) {
        c->mode = M_NONE;
    } else if (strcmp(mode, "btn") == 0) {
        char *v = strtok_r(NULL, " \t\r\n", &save);
        int code = v ? atoi(v) : 0;
        if (code >= BTN_LEFT && code <= BTN_TASK) {
            c->mode = M_BTN;
            c->code = code;
        }
    } else if (strcmp(mode, "long") == 0) {
        char *v = strtok_r(NULL, " \t\r\n", &save);
        int ms = v ? atoi(v) : 0;
        if (ms >= 100 && ms <= 3000) {
            c->mode = M_LONG;
            c->code = ms;
        }
    } else if (strcmp(mode, "notify") == 0) {
        char *v = strtok_r(NULL, " \t\r\n", &save);
        int id = v ? atoi(v) : 0;
        if (id >= 1 && id <= 99) {
            c->mode = M_NOTIFY;
            c->code = id;
        }
    } else if (strcmp(mode, "cmd") == 0) {
        char *copy = strdup(save ? save : "");
        char *sv2 = NULL, *t;
        int n = 0;
        if (!copy) return;
        t = strtok_r(copy, " \t\r\n", &sv2);
        if (!t) { free(copy); return; }
        free(c->buf);
        c->buf = copy;
        while (t && n < MAX_ARGS) {
            c->argv[n++] = t;
            t = strtok_r(NULL, " \t\r\n", &sv2);
        }
        c->argv[n] = NULL;
        c->mode = M_CMD;
    }
}

int main(void) {
    char inbuf[1024];
    size_t inlen = 0;
    long next_scan = 0;
    int i;

    signal(SIGCHLD, SIG_IGN); /* 実行したコマンドのゾンビを残さない */
    signal(SIGPIPE, SIG_IGN);
    setvbuf(stdout, NULL, _IOLBF, 0);
    for (i = 0; i < NSLOT; i++) cfg[i].mode = M_PASS;
    say("ready\n");

    for (;;) {
        struct pollfd pfds[MAX_DEV + 1];
        int which[MAX_DEV + 1];
        int n = 0, r, k, timeout = 1000;

        pfds[n].fd = 0;
        pfds[n].events = POLLIN;
        which[n++] = -1;
        for (i = 0; i < MAX_DEV; i++) {
            if (!devs[i].used) continue;
            pfds[n].fd = devs[i].in_fd;
            pfds[n].events = POLLIN;
            which[n++] = i;
        }

        { /* 長押しの模擬を終える時刻までしか眠らない */
            long now = now_ms();
            for (i = 0; i < MAX_DEV; i++) {
                long t;
                if (!devs[i].used || !devs[i].lp_active) continue;
                t = devs[i].lp_release_at - now;
                if (t < 0) t = 0;
                if (t < timeout) timeout = (int)t;
            }
        }
        r = poll(pfds, (nfds_t)n, timeout);
        if (r < 0 && errno != EINTR) break;

        for (k = 0; r > 0 && k < n; k++) {
            if (!pfds[k].revents) continue;

            if (which[k] < 0) { /* stdin: 設定コマンド / EOFで終了 */
                ssize_t m = read(0, inbuf + inlen, sizeof inbuf - inlen - 1);
                char *nl;
                if (m <= 0) goto done;
                inlen += (size_t)m;
                inbuf[inlen] = 0;
                while ((nl = memchr(inbuf, '\n', inlen)) != NULL) {
                    *nl = 0;
                    handle_line(inbuf);
                    inlen -= (size_t)(nl - inbuf) + 1;
                    memmove(inbuf, nl + 1, inlen);
                    inbuf[inlen] = 0;
                }
                if (inlen >= sizeof inbuf - 1) inlen = 0; /* 長すぎる行は捨てる */
            } else { /* マウスからのイベント */
                struct dev *d = &devs[which[k]];
                struct input_event evs[64];
                ssize_t m = read(d->in_fd, evs, sizeof evs);
                size_t cnt, j;
                if (m < 0) {
                    if (errno == EAGAIN || errno == EINTR) continue;
                    detach(d); /* ENODEV = 抜かれた */
                    continue;
                }
                if (m == 0) { detach(d); continue; }
                cnt = (size_t)m / sizeof evs[0];
                for (j = 0; j < cnt; j++) handle(d, &evs[j]);
            }
        }

        for (i = 0; i < MAX_DEV; i++)
            if (devs[i].used) check_timers(&devs[i]);

        if (now_ms() >= next_scan) {
            scan();
            next_scan = now_ms() + 3000;
        }
    }

done:
    for (i = 0; i < MAX_DEV; i++)
        if (devs[i].used) detach(&devs[i]);
    return 0;
}
