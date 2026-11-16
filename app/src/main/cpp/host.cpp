// SPDX-License-Identifier: GPL-3.0-only
// ObsLoader: 2026 Orian Beltrame. The emulator's host: a firmware core (libcore_<name>.so, its simulator's sim_* API:
// tests/sim_core.c in ObsSynth FM-1, after Hortator's) run by the audio callback (AAudio, 44.1 kHz stereo float, the
// firmware's own clock), fed by a queue of inputs (buttons, keys, knobs, MIDI) from the app, and its screen, LEDs and
// flash handed back. One core at a time.
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "fm1emu", __VA_ARGS__)

namespace {
constexpr int W = 240;
constexpr int RATE = 44100;
constexpr uint32_t FLASH_SIZE = 0x100000;
constexpr uint32_t DIRTY_WORDS = FLASH_SIZE / 4096 / 32;

struct Core {
    void *h = nullptr;
    void (*init)(int) = nullptr;
    uint32_t (*render)(uint32_t) = nullptr;
    void (*btn)(uint32_t, int) = nullptr;
    void (*key)(uint32_t, int) = nullptr;
    void (*enc)(uint32_t, int32_t) = nullptr;
    void (*master)(uint32_t) = nullptr;
    uint32_t (*leds)() = nullptr;
    uint32_t (*keyLeds)() = nullptr;
    uint32_t (*glow)() = nullptr;
    uint32_t (*keyGlow)() = nullptr;
    uint16_t *(*fb)() = nullptr;
    float *(*audio)() = nullptr;
    uint8_t *(*flash)() = nullptr;
    uint32_t *(*flashDirty)() = nullptr;
    void (*flashClean)() = nullptr;
    void (*flashReset)() = nullptr;
    int (*midi)(uint32_t, uint32_t, uint32_t) = nullptr;
    const char *(*presetName)() = nullptr;
};

struct Cmd { int t, a, b, c; };
enum { C_BTN, C_KEY, C_ENC, C_MASTER, C_MIDI };

Core core;
std::mutex coreLock;                 // the core: the callback renders under it, the flash snapshot copies under it
std::mutex queueLock;
std::vector<Cmd> queue, taken;
std::mutex screenLock;
uint16_t screen[W * W];
uint32_t lights[4];
std::atomic<uint32_t> serial{0};
std::atomic<bool> flashDirty{false};
std::atomic<bool> running{false};
AAudioStream *stream = nullptr;
std::atomic<float> level{0};

template <typename T> bool sym(T &f, const char *name) {
    f = reinterpret_cast<T>(dlsym(core.h, name));
    if (!f) LOG("core: no %s", name);
    return f != nullptr;
}

void apply(const Cmd &c) {
    switch (c.t) {
    case C_BTN: core.btn((uint32_t)c.a, c.b); break;
    case C_KEY: core.key((uint32_t)c.a, c.b); break;
    case C_ENC: core.enc((uint32_t)c.a, c.b); break;
    case C_MASTER: core.master((uint32_t)c.a); break;
    case C_MIDI: core.midi((uint32_t)c.a, (uint32_t)c.b, (uint32_t)c.c); break;
    }
}

aaudio_data_callback_result_t onAudio(AAudioStream *, void *, void *data, int32_t frames) {
    float *out = static_cast<float *>(data);
    {
        std::unique_lock<std::mutex> q(queueLock, std::try_to_lock);
        if (q.owns_lock()) { taken.swap(queue); queue.clear(); }
    }
    std::lock_guard<std::mutex> g(coreLock);
    for (const Cmd &c : taken) apply(c);
    taken.clear();
    float peak = 0;
    for (int32_t done = 0; done < frames;) {
        const uint32_t n = (uint32_t)std::min<int32_t>(frames - done, 512);
        const uint32_t ui = core.render(n);
        const float *a = core.audio();
        std::memcpy(out + 2 * done, a, n * 2 * sizeof(float));
        for (uint32_t i = 0; i < 2 * n; i++) peak = std::max(peak, a[i] < 0 ? -a[i] : a[i]);
        done += (int32_t)n;
        if (ui) {                    // a UI frame ran: its screen and lights for the app
            std::unique_lock<std::mutex> s(screenLock, std::try_to_lock);
            if (s.owns_lock()) {
                std::memcpy(screen, core.fb(), sizeof screen);
                lights[0] = core.leds(); lights[1] = core.keyLeds(); lights[2] = core.glow(); lights[3] = core.keyGlow();
                serial.fetch_add(1);
            }
        }
    }
    level = std::max(peak, level.load() * 0.9f);
    const uint32_t *d = core.flashDirty();
    for (uint32_t i = 0; i < DIRTY_WORDS; i++) if (d[i]) { flashDirty = true; break; }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void openStream();
void onError(AAudioStream *, void *, aaudio_result_t err) {
    LOG("audio error %d: reopening", err);
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
    if (AAudioStreamBuilder_openStream(b, &stream) != AAUDIO_OK) { stream = nullptr; }
    AAudioStreamBuilder_delete(b);
    if (!stream) { LOG("no audio stream"); return; }
    // twice the burst: low latency without underruns on most phones
    AAudioStream_setBufferSizeInFrames(stream, AAudioStream_getFramesPerBurst(stream) * 2);
    AAudioStream_requestStart(stream);
    LOG("audio: %d Hz, burst %d, buffer %d", AAudioStream_getSampleRate(stream), AAudioStream_getFramesPerBurst(stream),
        AAudioStream_getBufferSizeInFrames(stream));
}

void push(Cmd c) { std::lock_guard<std::mutex> q(queueLock); queue.push_back(c); }
} // namespace

#define FN(name) Java_io_github_orianbsilva_obsloader_emu_Native_##name

extern "C" {

JNIEXPORT jboolean JNICALL FN(load)(JNIEnv *env, jclass, jstring lib) {
    if (core.h) return JNI_TRUE;
    const char *n = env->GetStringUTFChars(lib, nullptr);
    core.h = dlopen(n, RTLD_NOW | RTLD_LOCAL);
    env->ReleaseStringUTFChars(lib, n);
    if (!core.h) { LOG("dlopen: %s", dlerror()); return JNI_FALSE; }
    bool ok = sym(core.init, "sim_init") & sym(core.render, "sim_render") & sym(core.btn, "sim_btn") &
              sym(core.key, "sim_key") & sym(core.enc, "sim_enc") & sym(core.master, "sim_master") &
              sym(core.leds, "sim_leds") & sym(core.keyLeds, "sim_key_leds") & sym(core.glow, "sim_glow") &
              sym(core.keyGlow, "sim_key_glow") & sym(core.fb, "sim_fb") & sym(core.audio, "sim_audio") &
              sym(core.flash, "sim_flash") & sym(core.flashDirty, "sim_flash_dirty") &
              sym(core.flashClean, "sim_flash_clean") & sym(core.flashReset, "sim_flash_reset") &
              sym(core.midi, "sim_midi") & sym(core.presetName, "sim_preset_name");
    return ok ? JNI_TRUE : JNI_FALSE;
}

// boot the core (the flash as it was saved, or erased) and start the sound
JNIEXPORT void JNICALL FN(start)(JNIEnv *env, jclass, jbyteArray flash) {
    {
        std::lock_guard<std::mutex> g(coreLock);
        if (flash && env->GetArrayLength(flash) == (jsize)FLASH_SIZE) {
            core.flashReset();
            env->GetByteArrayRegion(flash, 0, FLASH_SIZE, reinterpret_cast<jbyte *>(core.flash()));
            core.init(0);
        } else {
            core.init(1);
        }
        std::memcpy(screen, core.fb(), sizeof screen);
    }
    running = true;
    openStream();
}

JNIEXPORT void JNICALL FN(stop)(JNIEnv *, jclass) { running = false; closeStream(); }
JNIEXPORT void JNICALL FN(pause)(JNIEnv *, jclass) { if (stream) AAudioStream_requestPause(stream); }
JNIEXPORT void JNICALL FN(resume)(JNIEnv *, jclass) { if (!stream) openStream(); else AAudioStream_requestStart(stream); }

JNIEXPORT void JNICALL FN(btn)(JNIEnv *, jclass, jint id, jboolean down) { push({C_BTN, id, down ? 1 : 0, 0}); }
JNIEXPORT void JNICALL FN(key)(JNIEnv *, jclass, jint n, jboolean down) { push({C_KEY, n, down ? 1 : 0, 0}); }
JNIEXPORT void JNICALL FN(enc)(JNIEnv *, jclass, jint role, jint steps) { push({C_ENC, role, steps, 0}); }
JNIEXPORT void JNICALL FN(master)(JNIEnv *, jclass, jint v) { push({C_MASTER, v, 0, 0}); }
JNIEXPORT void JNICALL FN(midi)(JNIEnv *, jclass, jint st, jint d1, jint d2) { push({C_MIDI, st, d1, d2}); }

// the screen as ARGB for a Bitmap, and the lights; returns the frame's serial (unchanged: nothing new)
JNIEXPORT jint JNICALL FN(screen)(JNIEnv *env, jclass, jintArray argb, jintArray lit) {
    static uint32_t px[W * W];
    uint32_t l[4];
    {
        std::lock_guard<std::mutex> s(screenLock);
        for (int i = 0; i < W * W; i++) {
            const uint16_t c = screen[i];
            const uint32_t r = (c >> 11) & 31, g = (c >> 5) & 63, b = c & 31;
            px[i] = 0xFF000000u | ((r << 3 | r >> 2) << 16) | ((g << 2 | g >> 4) << 8) | (b << 3 | b >> 2);
        }
        std::memcpy(l, lights, sizeof l);
    }
    env->SetIntArrayRegion(argb, 0, W * W, reinterpret_cast<const jint *>(px));
    env->SetIntArrayRegion(lit, 0, 4, reinterpret_cast<const jint *>(l));
    return (jint)serial.load();
}

JNIEXPORT jfloat JNICALL FN(level)(JNIEnv *, jclass) { return level.load(); }

// the flash, when the firmware wrote it since the last time (into dst, 1 MB): true if copied
JNIEXPORT jboolean JNICALL FN(flashIfDirty)(JNIEnv *env, jclass, jbyteArray dst) {
    if (!flashDirty || !core.h) return JNI_FALSE;
    std::lock_guard<std::mutex> g(coreLock);
    env->SetByteArrayRegion(dst, 0, FLASH_SIZE, reinterpret_cast<const jbyte *>(core.flash()));
    core.flashClean();
    flashDirty = false;
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL FN(presetName)(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> g(coreLock);
    const char *n = core.presetName ? core.presetName() : "";
    return env->NewStringUTF(n ? n : "");
}
}
