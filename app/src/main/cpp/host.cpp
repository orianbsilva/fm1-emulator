// SPDX-License-Identifier: GPL-3.0-only
// FM-1 Pocket: 2026 Orian Beltrame. The emulator's host. The FM-1 itself is fm1-emu's (Joakim Eriksson, MIT,
// https://github.com/joakimeriksson/fm1-emu): its two JieLi cores (compiled to native code), screen, keys, knobs,
// flash, MIDI IN and I2S audio, running an update package (.fwsc) unchanged, from libfm1_web.so's C API. Here: a
// thread of its own runs the machine (urgent audio priority) a little ahead of the sound, into a ring; the AAudio
// callback (44.1 kHz) only copies from the ring, so an uneven block of emulation is not a click. The ring's lead grows
// by itself where the phone falls behind (an underrun fades out instead of cutting). The inputs are queued from the
// app and applied on that thread; the screen, the LEDs and the flash go back over JNI. One machine at a time.
#include <aaudio/AAudio.h>
#include <sys/resource.h>
#include <unistd.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <thread>
#include <chrono>
#include <condition_variable>
#include <vector>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "fm1emu", __VA_ARGS__)

namespace {
constexpr int W = 240;
constexpr int RATE = 44100;
constexpr uint32_t MHZ = 240;                       // the FM-1's clock
constexpr uint32_t SECTOR = 4096;

struct Lib {
    void *h = nullptr;
    uint8_t *(*alloc)(uint32_t) = nullptr;
    uint32_t (*bootFwsc)(uint8_t *, uint32_t, uint8_t *, uint32_t, uint32_t) = nullptr;
    uint32_t (*flashRestore)(uint8_t *, uint32_t) = nullptr;
    void (*jit)(uint32_t, uint32_t) = nullptr;
    void (*ahead)(uint32_t) = nullptr;
    void (*flashPart)(uint32_t) = nullptr;
    uint32_t (*render)(uint32_t, uint32_t) = nullptr;
    const float *(*outL)() = nullptr;
    const float *(*outR)() = nullptr;
    const uint16_t *(*lcd)() = nullptr;
    uint32_t (*lcdRevision)() = nullptr;
    const float *(*leds)() = nullptr;
    void (*key)(uint32_t, uint32_t) = nullptr;
    void (*enc)(uint32_t, int32_t) = nullptr;
    void (*midi)(uint32_t) = nullptr;
    void (*pot)(uint32_t) = nullptr;
    const uint8_t *(*flash)() = nullptr;
    const uint8_t *(*flashBase)() = nullptr;
    uint32_t (*flashLen)() = nullptr;
    uint32_t (*flashRevision)() = nullptr;
    uint32_t (*flashBusy)() = nullptr;
    uint32_t (*halted)() = nullptr;
    uint32_t (*board)() = nullptr;
};

struct Cmd { int t, a, b, c; };
enum { C_KEY, C_ENC, C_POT, C_MIDI };

Lib lib;
bool booted = false;
std::mutex coreLock;                 // the machine: the callback runs it under this, a flash copy and a boot take it too
std::mutex queueLock;
std::vector<Cmd> queue, taken;
std::mutex screenLock;
uint16_t screen[W * W];
float ledLevel[41];                  // each LED's brightness 0..1 (fm1-emu's on-fraction: a lit LED is on ~1 tick in 11)
std::atomic<uint32_t> serial{0};
std::atomic<uint32_t> flashRev{0};
std::atomic<bool> running{false};
std::atomic<bool> halted{false};
std::atomic<float> level{0};
std::atomic<uint32_t> late{0};       // callbacks that could not produce their frames in time (the emulation too slow)
AAudioStream *stream = nullptr;
uint32_t lastLcd = ~0u;

// the ring between the emulation thread (writer) and the audio callback (reader): stereo frames
constexpr uint32_t RING = 16384;
float ring[2 * RING];
std::atomic<uint32_t> ringW{0}, ringR{0};
std::atomic<uint32_t> lead{1024};          // frames the emulation keeps ahead of the sound (grows after an underrun)
std::atomic<uint32_t> underruns{0};
std::atomic<uint64_t> played{0};           // frames the callback has played since the boot
std::atomic<uint64_t> lastUnderrun{0};     // .. when the last underrun was
constexpr uint32_t LEAD_MIN = 768, LEAD_MAX = 8192, WARMUP = RATE * 3;   // (the first seconds: the JIT compiles)
float lastL = 0, lastR = 0;                // the callback's last sample (an underrun fades from it)
std::thread emuThread;
std::atomic<bool> emuRun{false};
std::mutex wakeLock;
std::condition_variable wake;

template <typename T> bool sym(T &f, const char *name) {
    f = reinterpret_cast<T>(dlsym(lib.h, name));
    if (!f) LOG("fm1-emu: no %s", name);
    return f != nullptr;
}

void apply(const Cmd &c) {
    switch (c.t) {
    case C_KEY: lib.key((uint32_t)c.a, (uint32_t)c.b); break;
    case C_ENC: lib.enc((uint32_t)c.a, c.b); break;
    case C_POT: lib.pot((uint32_t)c.a); break;
    case C_MIDI: lib.midi((uint32_t)c.a); break;
    }
}

// the emulation thread: keep the ring `lead` frames ahead of the callback
void emulate() {
    setpriority(PRIO_PROCESS, (id_t)gettid(), -19);                   // (Android's urgent audio; refused: as it is)
    while (emuRun) {
        const uint32_t fill = ringW.load() - ringR.load();
        if (fill >= lead.load() || !booted) {
            std::unique_lock<std::mutex> w(wakeLock);
            wake.wait_for(w, std::chrono::milliseconds(2));
            continue;
        }
        std::lock_guard<std::mutex> g(coreLock);
        if (!booted) continue;
        {
            std::unique_lock<std::mutex> q(queueLock, std::try_to_lock);
            if (q.owns_lock()) { taken.swap(queue); queue.clear(); }
        }
        for (const Cmd &c : taken) apply(c);
        taken.clear();
        const uint32_t n = std::min<uint32_t>(256, RING - fill);
        // at most twice the emulated time these frames stand for (fm1_render raises it to what its pace needs)
        const uint32_t got = lib.render(n, n * (MHZ * 1000000u / RATE) * 2u);
        const float *l = lib.outL(), *r = lib.outR();
        uint32_t w0 = ringW.load();
        float peak = 0;
        for (uint32_t i = 0; i < got; i++, w0++) {
            ring[2 * (w0 % RING)] = l[i];
            ring[2 * (w0 % RING) + 1] = r[i];
            peak = std::max(peak, std::max(l[i] < 0 ? -l[i] : l[i], r[i] < 0 ? -r[i] : r[i]));
        }
        ringW.store(w0);
        if (got < n) late.fetch_add(1);
        level = std::max(peak, level.load() * 0.995f);
        halted = lib.halted() != 0;
        flashRev = lib.flashRevision();
        const uint32_t rev = lib.lcdRevision();
        std::unique_lock<std::mutex> s(screenLock, std::try_to_lock);
        if (s.owns_lock()) {
            if (rev != lastLcd) { std::memcpy(screen, lib.lcd(), sizeof screen); lastLcd = rev; }
            const float *f = lib.leds();
            for (int i = 0; i < 41; i++) ledLevel[i] = std::min(1.0f, f[i] * 11.0f);
            serial.fetch_add(1);
        }
        if (halted) std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
}

void startEmu() {
    if (emuRun) return;
    emuRun = true;
    emuThread = std::thread(emulate);
}

void stopEmu() {
    if (!emuRun) return;
    emuRun = false;
    wake.notify_all();
    if (emuThread.joinable()) emuThread.join();
}

aaudio_data_callback_result_t onAudio(AAudioStream *, void *, void *data, int32_t frames) {
    float *out = static_cast<float *>(data);
    uint32_t r = ringR.load();
    const uint32_t have = ringW.load() - r;
    const uint32_t n = std::min<uint32_t>((uint32_t)frames, have);
    for (uint32_t i = 0; i < n; i++, r++) {
        out[2 * i] = ring[2 * (r % RING)];
        out[2 * i + 1] = ring[2 * (r % RING) + 1];
    }
    ringR.store(r);
    if (n > 0) { lastL = out[2 * n - 2]; lastR = out[2 * n - 1]; }
    if (n < (uint32_t)frames) {
        // the emulation fell behind: fade from the last sample (no click), and keep more ahead from now on
        for (uint32_t i = n; i < (uint32_t)frames; i++) {
            lastL *= 0.995f; lastR *= 0.995f;
            out[2 * i] = lastL; out[2 * i + 1] = lastR;
        }
        if (booted && played.load() > WARMUP) {          // (a boot's compiling is not the phone being slow)
            underruns.fetch_add(1);
            lastUnderrun = played.load();
            lead.store(std::min<uint32_t>(lead.load() + 512, LEAD_MAX));
        }
    } else if (played.load() - lastUnderrun.load() > (uint64_t)RATE * 4 && lead.load() > LEAD_MIN) {
        // 4 s without one: a little less ahead (less latency), down to the least
        lead.store(std::max<uint32_t>(lead.load() - 128, LEAD_MIN));
        lastUnderrun = played.load();
    }
    played.fetch_add((uint64_t)frames);
    wake.notify_one();
    {   // a line now and then: the sound's peak, the underruns, the lead (logcat fm1emu)
        static uint32_t calls = 0;
        if (++calls % 250 == 0)
            LOG("level %.4f underruns %u lead %u late %u halted %u", level.load(), underruns.load(), lead.load(), late.load(), (unsigned)halted.load());
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void openStream();
void onError(AAudioStream *, void *, aaudio_result_t err) {
    LOG("audio error %d", err);
    if (err == AAUDIO_ERROR_DISCONNECTED) std::thread([] { if (running) openStream(); }).detach();   // headphones, USB audio
}

void closeStream() {
    if (stream) { AAudioStream_requestStop(stream); AAudioStream_close(stream); stream = nullptr; }
}

void openStream() {
    closeStream();
    AAudioStreamBuilder *b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return;
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setSampleRate(b, RATE);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setDataCallback(b, onAudio, nullptr);
    AAudioStreamBuilder_setErrorCallback(b, onError, nullptr);
    if (AAudioStreamBuilder_openStream(b, &stream) != AAUDIO_OK) stream = nullptr;
    AAudioStreamBuilder_delete(b);
    if (!stream) { LOG("no audio stream"); return; }
    // a few bursts: the emulation's work per callback is uneven (the firmware's DMA halves)
    AAudioStream_setBufferSizeInFrames(stream, AAudioStream_getFramesPerBurst(stream) * 2);   // (the ring is the margin)
    AAudioStream_requestStart(stream);
    LOG("audio: %d Hz, burst %d, buffer %d", AAudioStream_getSampleRate(stream), AAudioStream_getFramesPerBurst(stream),
        AAudioStream_getBufferSizeInFrames(stream));
}

void push(Cmd c) { std::lock_guard<std::mutex> q(queueLock); queue.push_back(c); }

uint8_t *copyIn(JNIEnv *env, jbyteArray a) {
    const jsize n = env->GetArrayLength(a);
    uint8_t *p = lib.alloc((uint32_t)n);
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(p));
    return p;
}
} // namespace

#define FN(name) Java_io_github_orianbsilva_obsloader_emu_Native_##name

extern "C" {

JNIEXPORT jboolean JNICALL FN(load)(JNIEnv *, jclass) {
    if (lib.h) return JNI_TRUE;
    lib.h = dlopen("libfm1_web.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib.h) { LOG("dlopen: %s", dlerror()); return JNI_FALSE; }
    bool ok = sym(lib.alloc, "fm1_alloc") & sym(lib.bootFwsc, "fm1_boot_fwsc") & sym(lib.flashRestore, "fm1_flash_restore") &
              sym(lib.jit, "fm1_jit") & sym(lib.ahead, "fm1_ahead") & sym(lib.flashPart, "fm1_flash_part") &
              sym(lib.render, "fm1_render") & sym(lib.outL, "fm1_out_l") & sym(lib.outR, "fm1_out_r") &
              sym(lib.lcd, "fm1_lcd") & sym(lib.lcdRevision, "fm1_lcd_revision") & sym(lib.leds, "fm1_leds") &
              sym(lib.key, "fm1_key") & sym(lib.enc, "fm1_enc") & sym(lib.midi, "fm1_midi") & sym(lib.pot, "fm1_pot") &
              sym(lib.flash, "fm1_flash") & sym(lib.flashBase, "fm1_flash_base") & sym(lib.flashLen, "fm1_flash_len") &
              sym(lib.flashRevision, "fm1_flash_revision") & sym(lib.flashBusy, "fm1_flash_busy") &
              sym(lib.halted, "fm1_halted") & sym(lib.board, "fm1_board");
    return ok ? JNI_TRUE : JNI_FALSE;
}

// boot a package (.fwsc, whole) with the flash sectors saved from an earlier run (records of a 4-byte little-endian
// offset and 4096 bytes, fm1-emu's format; null: the package's fresh flash); chip 1: the newer flash part
// (UC25HQ80). 0: booted; 1: not an FM-1 package; 2: the saved flash did not fit; 3: an SMK-37 package
JNIEXPORT jint JNICALL FN(boot)(JNIEnv *env, jclass, jbyteArray pkg, jbyteArray sectors, jint chip) {
    std::lock_guard<std::mutex> g(coreLock);
    booted = false;
    lib.jit(1, 0);                                   // native code for the hot blocks
    lib.flashPart((uint32_t)chip);
    lib.ahead(1024);
    if (lib.bootFwsc(copyIn(env, pkg), (uint32_t)env->GetArrayLength(pkg), nullptr, 0, MHZ) != 0) return 1;
    if (lib.board() != 0) return 3;
    int rc = 0;
    if (sectors && env->GetArrayLength(sectors) > 0 &&
        lib.flashRestore(copyIn(env, sectors), (uint32_t)env->GetArrayLength(sectors)) != 0) rc = 2;
    lastLcd = ~0u;
    std::memset(screen, 0, sizeof screen);
    halted = false;
    late = 0;
    underruns = 0;
    lead = 1024;
    played = 0;
    lastUnderrun = 0;
    ringR.store(ringW.load());
    booted = true;
    running = true;
    startEmu();
    if (!stream) openStream();
    return rc;
}

JNIEXPORT void JNICALL FN(stop)(JNIEnv *, jclass) {
    running = false;
    closeStream();
    stopEmu();
    std::lock_guard<std::mutex> g(coreLock);
    booted = false;
}
JNIEXPORT void JNICALL FN(pause)(JNIEnv *, jclass) { if (stream) AAudioStream_requestPause(stream); }
JNIEXPORT void JNICALL FN(resume)(JNIEnv *, jclass) { if (running) { if (!stream) openStream(); else AAudioStream_requestStart(stream); } }

// inputs by the FM-1's matrix ids (fm1-emu's): buttons OCT- 0, OCT+ 1, FX..GLO 2..7, HOME..REC 8..13, keys F3..G5 14..40;
// encoders SELECT 0, ALGORITHM 1, KNOB1..4 2..5, PRESETS 6; MASTER 0..1023
JNIEXPORT void JNICALL FN(key)(JNIEnv *, jclass, jint id, jboolean down) { push({C_KEY, id, down ? 1 : 0, 0}); }
JNIEXPORT void JNICALL FN(enc)(JNIEnv *, jclass, jint enc, jint clicks) { push({C_ENC, enc, clicks, 0}); }
JNIEXPORT void JNICALL FN(master)(JNIEnv *, jclass, jint v) { push({C_POT, v, 0, 0}); }
// a MIDI message into the MIDI IN jack, byte by byte (the firmware reads it with its own driver)
JNIEXPORT void JNICALL FN(midi)(JNIEnv *env, jclass, jbyteArray msg) {
    const jsize n = env->GetArrayLength(msg);
    jbyte b[512];
    if (n > (jsize)sizeof b) return;
    env->GetByteArrayRegion(msg, 0, n, b);
    std::lock_guard<std::mutex> q(queueLock);
    for (jsize i = 0; i < n; i++) queue.push_back({C_MIDI, (uint8_t)b[i], 0, 0});
}

// the screen as ARGB for a Bitmap and the LEDs' levels (41, by matrix id); the frame's serial (unchanged: nothing new)
JNIEXPORT jint JNICALL FN(screen)(JNIEnv *env, jclass, jintArray argb, jfloatArray leds) {
    static uint32_t px[W * W];
    float l[41];
    {
        std::lock_guard<std::mutex> s(screenLock);
        for (int i = 0; i < W * W; i++) {
            const uint16_t c = screen[i];
            const uint32_t r = (c >> 11) & 31, g = (c >> 5) & 63, b = c & 31;
            px[i] = 0xFF000000u | ((r << 3 | r >> 2) << 16) | ((g << 2 | g >> 4) << 8) | (b << 3 | b >> 2);
        }
        std::memcpy(l, ledLevel, sizeof l);
    }
    env->SetIntArrayRegion(argb, 0, W * W, reinterpret_cast<const jint *>(px));
    env->SetFloatArrayRegion(leds, 0, 41, l);
    return (jint)serial.load();
}

JNIEXPORT jfloat JNICALL FN(level)(JNIEnv *, jclass) { return level.load(); }
JNIEXPORT jboolean JNICALL FN(halted)(JNIEnv *, jclass) { return halted ? JNI_TRUE : JNI_FALSE; }
JNIEXPORT jint JNICALL FN(late)(JNIEnv *, jclass) { return (jint)late.load(); }
JNIEXPORT jint JNICALL FN(flashRevision)(JNIEnv *, jclass) { return (jint)flashRev.load(); }

// the sectors the firmware changed against its package's fresh flash (fm1-emu's save format); null while the flash is
// busy (a write in progress: the next call takes it whole)
JNIEXPORT jbyteArray JNICALL FN(sectors)(JNIEnv *env, jclass) {
    std::vector<uint8_t> out;
    {
        std::lock_guard<std::mutex> g(coreLock);
        if (!booted || lib.flashBusy()) return nullptr;
        const uint8_t *f = lib.flash(), *b = lib.flashBase();
        const uint32_t n = lib.flashLen();
        for (uint32_t at = 0; at + SECTOR <= n; at += SECTOR) {
            if (std::memcmp(f + at, b + at, SECTOR) == 0) continue;
            const uint8_t h[4] = {(uint8_t)at, (uint8_t)(at >> 8), (uint8_t)(at >> 16), (uint8_t)(at >> 24)};
            out.insert(out.end(), h, h + 4);
            out.insert(out.end(), f + at, f + at + SECTOR);
        }
    }
    jbyteArray a = env->NewByteArray((jsize)out.size());
    env->SetByteArrayRegion(a, 0, (jsize)out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return a;
}
}
