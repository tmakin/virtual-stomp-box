// AAudio output for the drum synth, asking for EXCLUSIVE sharing: on devices that support it
// (Pixel built-in speaker) that is the MMAP path, which skips Android's mixer. If exclusive is
// refused (USB audio usually), AAudio falls back to shared low-latency mode.
//
// Sounds are loaded once from Kotlin; play() queues a voice, the data callback mixes voices.
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <jni.h>
#include <atomic>
#include <cstring>
#include <ctime>
#include <mutex>
#include <vector>

#define TAG "AAudioDrum"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {

constexpr int kRate = 48000;
constexpr int kSlots = 8;
constexpr int kMaxVoices = 32;   // reverb tails make voices up to ~2.4 s long
constexpr int kQueue = 64;  // power of two

struct Voice { int slot; float gain; int pos; };
struct Request { int slot; float gain; };

// Read by the callback under soundsLock (try_lock, so it never blocks); nativeLoad swaps a new
// sound in under the same lock without closing the stream. A voice past the end of a shortened
// sound just finishes.
std::vector<float> sounds[kSlots];
std::mutex soundsLock;

// Single-consumer ring (the audio callback); producers serialised by a mutex.
Request queue[kQueue];
std::atomic<uint32_t> qHead{0}, qTail{0};
std::mutex producer;

Voice voices[kMaxVoices];
int voiceCount = 0;

std::mutex streamLock;
AAudioStream* stream = nullptr;
std::atomic<bool> disconnected{false};
int lastXruns = 0;

aaudio_data_callback_result_t onData(AAudioStream*, void*, void* audioData, int32_t frames) {
    auto* out = static_cast<float*>(audioData);
    // Take new voices.
    uint32_t tail = qTail.load(std::memory_order_acquire);
    uint32_t head = qHead.load(std::memory_order_relaxed);
    while (head != tail) {
        const Request& r = queue[head & (kQueue - 1)];
        if (voiceCount == kMaxVoices) {   // full: the oldest voice (index 0) makes way
            for (int v = 1; v < voiceCount; v++) voices[v - 1] = voices[v];
            voiceCount--;
        }
        voices[voiceCount++] = Voice{r.slot, r.gain, 0};
        head++;
    }
    qHead.store(head, std::memory_order_release);

    std::unique_lock<std::mutex> lock(soundsLock, std::try_to_lock);
    if (!lock.owns_lock()) {   // a sound is being swapped in (microseconds): one silent burst, voices wait
        std::memset(out, 0, sizeof(float) * 2 * frames);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    for (int i = 0; i < frames; i++) {
        float x = 0;
        for (int v = 0; v < voiceCount; v++) {
            const std::vector<float>& s = sounds[voices[v].slot];
            if (voices[v].pos < (int) s.size()) x += s[voices[v].pos++] * voices[v].gain;
        }
        if (x > 1) x = 1; else if (x < -1) x = -1;
        out[2 * i] = x; out[2 * i + 1] = x;
    }
    // Drop finished voices.
    int n = 0;
    for (int v = 0; v < voiceCount; v++)
        if (voices[v].pos < (int) sounds[voices[v].slot].size()) voices[n++] = voices[v];
    voiceCount = n;
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void onError(AAudioStream*, void*, aaudio_result_t error) {
    LOGI("stream error %s", AAudio_convertResultToText(error));
    disconnected = true;   // Kotlin polls this and reopens (never close from this callback)
}

void closeLocked() {
    if (stream) { AAudioStream_requestStop(stream); AAudioStream_close(stream); stream = nullptr; }
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_uk_neatlines_stompbox_audio_AAudioDrum_nativeLoad(JNIEnv* env, jclass, jint slot, jfloatArray data) {
    if (slot < 0 || slot >= kSlots) return;
    jsize n = env->GetArrayLength(data);
    std::vector<float> fresh(n);
    env->GetFloatArrayRegion(data, 0, n, fresh.data());
    {
        std::lock_guard<std::mutex> g(soundsLock);   // the swap is O(1); the old sound is freed below, unlocked
        sounds[slot].swap(fresh);
    }
}

JNIEXPORT jboolean JNICALL
Java_uk_neatlines_stompbox_audio_AAudioDrum_nativeOpen(JNIEnv*, jclass, jint deviceId, jboolean exclusive) {
    std::lock_guard<std::mutex> g(streamLock);
    closeLocked();
    AAudioStreamBuilder* b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return false;
    AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, exclusive ? AAUDIO_SHARING_MODE_EXCLUSIVE : AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setSampleRate(b, kRate);
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_GAME);
    AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_SONIFICATION);
    if (deviceId > 0) AAudioStreamBuilder_setDeviceId(b, deviceId);
    AAudioStreamBuilder_setDataCallback(b, onData, nullptr);
    AAudioStreamBuilder_setErrorCallback(b, onError, nullptr);
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &stream);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) { LOGI("open failed: %s", AAudio_convertResultToText(r)); stream = nullptr; return false; }
    voiceCount = 0; qHead = qTail.load(); lastXruns = 0; disconnected = false;
    AAudioStream_setBufferSizeInFrames(stream, AAudioStream_getFramesPerBurst(stream) * 2);
    r = AAudioStream_requestStart(stream);
    if (r != AAUDIO_OK) { LOGI("start failed: %s", AAudio_convertResultToText(r)); closeLocked(); return false; }
    LOGI("open: sharing %s, burst %d, rate %d, device %d",
         AAudioStream_getSharingMode(stream) == AAUDIO_SHARING_MODE_EXCLUSIVE ? "EXCLUSIVE" : "shared",
         AAudioStream_getFramesPerBurst(stream), AAudioStream_getSampleRate(stream), AAudioStream_getDeviceId(stream));
    return true;
}

JNIEXPORT void JNICALL
Java_uk_neatlines_stompbox_audio_AAudioDrum_nativeClose(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> g(streamLock);
    closeLocked();
}

JNIEXPORT void JNICALL
Java_uk_neatlines_stompbox_audio_AAudioDrum_nativePlay(JNIEnv*, jclass, jint slot, jfloat gain) {
    if (slot < 0 || slot >= kSlots) return;
    std::lock_guard<std::mutex> g(producer);
    uint32_t tail = qTail.load(std::memory_order_relaxed);
    if (tail - qHead.load(std::memory_order_acquire) >= kQueue) return;   // full: drop
    queue[tail & (kQueue - 1)] = Request{slot, gain};
    qTail.store(tail + 1, std::memory_order_release);
}

// Fills out[]: exclusive (1/0), buffer ms, output latency ms (NaN if unknown), xruns,
// burst frames, sample rate, device id, disconnected (1/0). Grows the buffer by one burst
// after new underruns. Returns false if no stream is open.
JNIEXPORT jboolean JNICALL
Java_uk_neatlines_stompbox_audio_AAudioDrum_nativeInfo(JNIEnv* env, jclass, jdoubleArray outArr) {
    std::lock_guard<std::mutex> g(streamLock);
    if (!stream) return false;
    int burst = AAudioStream_getFramesPerBurst(stream);
    int rate = AAudioStream_getSampleRate(stream);
    int xruns = AAudioStream_getXRunCount(stream);
    if (xruns > lastXruns) {
        lastXruns = xruns;
        int size = AAudioStream_getBufferSizeInFrames(stream);
        if (size + burst <= AAudioStream_getBufferCapacityInFrames(stream))
            AAudioStream_setBufferSizeInFrames(stream, size + burst);
    }
    double latency = 0.0 / 0.0;
    int64_t framePos = 0, timeNs = 0;
    if (AAudioStream_getTimestamp(stream, CLOCK_MONOTONIC, &framePos, &timeNs) == AAUDIO_OK) {
        timespec now{}; clock_gettime(CLOCK_MONOTONIC, &now);
        int64_t nowNs = now.tv_sec * 1000000000LL + now.tv_nsec;
        int64_t written = AAudioStream_getFramesWritten(stream);
        latency = (written - framePos) * 1000.0 / rate - (nowNs - timeNs) / 1e6;
    }
    double out[8] = {
        AAudioStream_getSharingMode(stream) == AAUDIO_SHARING_MODE_EXCLUSIVE ? 1.0 : 0.0,
        AAudioStream_getBufferSizeInFrames(stream) * 1000.0 / rate,
        latency, (double) xruns, (double) burst, (double) rate,
        (double) AAudioStream_getDeviceId(stream), disconnected ? 1.0 : 0.0,
    };
    env->SetDoubleArrayRegion(outArr, 0, 8, out);
    return true;
}

}  // extern "C"
