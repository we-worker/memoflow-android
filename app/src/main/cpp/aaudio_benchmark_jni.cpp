#include <jni.h>
#include <aaudio/AAudio.h>
#include <atomic>
#include <cstdint>
#include <memory>
#include <thread>
#include <vector>

namespace {

struct Probe {
    AAudioStream* stream = nullptr;
    std::atomic<bool> running{false};
    std::atomic<int64_t> framesRead{0};
    std::thread worker;
    bool mmapUsed = false;
    int32_t sampleRate = 0;
    int32_t channelCount = 0;
};

void stopProbe(Probe* probe) {
    if (!probe) return;
    probe->running.store(false);
    if (probe->stream) {
        AAudioStream_requestStop(probe->stream);
    }
    if (probe->worker.joinable()) {
        probe->worker.join();
    }
    if (probe->stream) {
        AAudioStream_close(probe->stream);
        probe->stream = nullptr;
    }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_memoflow_benchmark_AAudioPowerProbe_nativeStart(
        JNIEnv*,
        jobject) {
    auto probe = std::make_unique<Probe>();

    AAudioStreamBuilder* builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK || builder == nullptr) {
        return 0;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, 1);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_POWER_SAVING);

    const aaudio_result_t openResult =
            AAudioStreamBuilder_openStream(builder, &probe->stream);
    AAudioStreamBuilder_delete(builder);

    if (openResult != AAUDIO_OK || probe->stream == nullptr) {
        return 0;
    }

    probe->mmapUsed = AAudioStream_isMMapUsed(probe->stream);
    probe->sampleRate = AAudioStream_getSampleRate(probe->stream);
    probe->channelCount = AAudioStream_getChannelCount(probe->stream);

    if (AAudioStream_requestStart(probe->stream) != AAUDIO_OK) {
        AAudioStream_close(probe->stream);
        probe->stream = nullptr;
        return 0;
    }

    probe->running.store(true);
    Probe* raw = probe.release();

    raw->worker = std::thread([raw]() {
        // Use a large blocking batch. This probe intentionally performs no file I/O
        // or AAC encoding: it isolates whether this device exposes an efficient
        // AAudio/MMAP capture path to a normal third-party app.
        constexpr int32_t kFramesPerRead = 4096;
        std::vector<int16_t> buffer(
                static_cast<size_t>(kFramesPerRead) *
                static_cast<size_t>(raw->channelCount > 0 ? raw->channelCount : 1));

        while (raw->running.load()) {
            const aaudio_result_t result =
                    AAudioStream_read(
                            raw->stream,
                            buffer.data(),
                            kFramesPerRead,
                            2'000'000'000LL);
            if (result > 0) {
                raw->framesRead.fetch_add(result);
            } else if (result < 0) {
                break;
            }
        }
    });

    return reinterpret_cast<jlong>(raw);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_memoflow_benchmark_AAudioPowerProbe_nativeIsMMapUsed(
        JNIEnv*,
        jobject,
        jlong handle) {
    auto* probe = reinterpret_cast<Probe*>(handle);
    return probe != nullptr && probe->mmapUsed;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_memoflow_benchmark_AAudioPowerProbe_nativeSampleRate(
        JNIEnv*,
        jobject,
        jlong handle) {
    auto* probe = reinterpret_cast<Probe*>(handle);
    return probe == nullptr ? 0 : probe->sampleRate;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_memoflow_benchmark_AAudioPowerProbe_nativeFramesRead(
        JNIEnv*,
        jobject,
        jlong handle) {
    auto* probe = reinterpret_cast<Probe*>(handle);
    return probe == nullptr ? 0 : probe->framesRead.load();
}

extern "C" JNIEXPORT void JNICALL
Java_com_memoflow_benchmark_AAudioPowerProbe_nativeStop(
        JNIEnv*,
        jobject,
        jlong handle) {
    auto* probe = reinterpret_cast<Probe*>(handle);
    if (!probe) return;
    stopProbe(probe);
    delete probe;
}
