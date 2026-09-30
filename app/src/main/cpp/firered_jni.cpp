#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "net.h"
#include "frontend/fbank.h"
#include "firered_vad_stream_packed.h"

namespace {

std::string JStringToString(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::vector<int16_t> PcmFromByteArray(JNIEnv* env, jbyteArray input) {
    if (!input) return {};
    const jsize length = env->GetArrayLength(input);
    if (length < 2) return {};

    std::vector<jbyte> bytes(static_cast<size_t>(length));
    env->GetByteArrayRegion(input, 0, length, bytes.data());

    const size_t sample_count = static_cast<size_t>(length) / 2;
    std::vector<int16_t> pcm(sample_count);
    for (size_t i = 0; i < sample_count; ++i) {
        const uint16_t lo = static_cast<uint8_t>(bytes[i * 2]);
        const uint16_t hi = static_cast<uint8_t>(bytes[i * 2 + 1]);
        pcm[i] = static_cast<int16_t>((hi << 8) | lo);
    }
    return pcm;
}

bool LoadFloatVector(const std::string& path, std::vector<float>* values) {
    FILE* file = fopen(path.c_str(), "rb");
    if (!file) return false;

    fseek(file, 0, SEEK_END);
    const long size = ftell(file);
    fseek(file, 0, SEEK_SET);

    if (size <= 0 || size % static_cast<long>(sizeof(float)) != 0) {
        fclose(file);
        return false;
    }

    values->resize(static_cast<size_t>(size) / sizeof(float));
    const size_t read = fread(values->data(), sizeof(float), values->size(), file);
    fclose(file);
    return read == values->size();
}

void ApplyCmvn(
    const std::vector<float>& means,
    const std::vector<float>& inverse_std,
    std::vector<float>* features,
    int frames,
    int dims) {
    if (means.size() < static_cast<size_t>(dims) ||
        inverse_std.size() < static_cast<size_t>(dims)) {
        return;
    }

    for (int frame = 0; frame < frames; ++frame) {
        float* row = features->data() + frame * dims;
        for (int dim = 0; dim < dims; ++dim) {
            row[dim] = (row[dim] - means[dim]) * inverse_std[dim];
        }
    }
}

std::vector<float> DetectNonStream(
    const std::string& model_dir,
    const std::vector<int16_t>& pcm) {
    constexpr int kSampleRate = 16000;
    constexpr int kFeatureDim = 80;
    constexpr int kFrameLength = 400;
    constexpr int kFrameShift = 160;

    if (pcm.size() < static_cast<size_t>(kFrameLength)) return {};

    std::vector<float> wave(pcm.size());
    for (size_t i = 0; i < pcm.size(); ++i) {
        wave[i] = static_cast<float>(pcm[i]);
    }

    vad::Fbank fbank(
        kFeatureDim,
        kSampleRate,
        kFrameLength,
        kFrameShift
    );
    std::vector<float> features;
    const int frames = fbank.Compute(wave, &features);
    if (frames <= 0 || features.empty()) return {};

    std::vector<float> means;
    std::vector<float> inverse_std;
    if (!LoadFloatVector(model_dir + "/cmvn_means.bin", &means) ||
        !LoadFloatVector(model_dir + "/cmvn_istd.bin", &inverse_std)) {
        return {};
    }
    ApplyCmvn(means, inverse_std, &features, frames, kFeatureDim);

    ncnn::Net net;
    net.opt.num_threads = 2;

    if (net.load_param((model_dir + "/firered_vad_non_stream.ncnn.param").c_str()) != 0) {
        return {};
    }
    if (net.load_model((model_dir + "/firered_vad_non_stream.ncnn.bin").c_str()) != 0) {
        return {};
    }

    ncnn::Mat input(kFeatureDim, frames);
    std::memcpy(input.data, features.data(), features.size() * sizeof(float));

    ncnn::Extractor extractor = net.create_extractor();
    extractor.input("in0", input);

    ncnn::Mat output;
    if (extractor.extract("out0", output) != 0 || output.empty()) {
        return {};
    }

    const int count = std::min(frames, static_cast<int>(output.total()));
    std::vector<float> probabilities(static_cast<size_t>(count));
    for (int index = 0; index < count; ++index) {
        probabilities[static_cast<size_t>(index)] = output[index];
    }
    return probabilities;
}

std::vector<float> DetectStream(
    const std::string& model_dir,
    const std::vector<int16_t>& pcm) {
    FireredVADHandle handle =
        firered_vad_create(
            (model_dir + "/firered_vad_packed_cache_stream.ncnn.param").c_str(),
            (model_dir + "/firered_vad_packed_cache_stream.ncnn.bin").c_str(),
            (model_dir + "/cmvn_means_stream.bin").c_str(),
            (model_dir + "/cmvn_istd_stream.bin").c_str()
        );
    if (!handle) return {};

    constexpr size_t kFrameSamples = 160;
    const size_t frame_count =
        (pcm.size() + kFrameSamples - 1) / kFrameSamples;

    std::vector<float> probabilities;
    probabilities.reserve(frame_count);

    std::vector<int16_t> frame(kFrameSamples, 0);
    for (size_t index = 0; index < frame_count; ++index) {
        std::fill(frame.begin(), frame.end(), 0);
        const size_t offset = index * kFrameSamples;
        const size_t count =
            std::min(kFrameSamples, pcm.size() - offset);
        std::copy_n(pcm.data() + offset, count, frame.data());

        FireredVADResult result{};
        if (firered_vad_process_stream(
                handle,
                frame.data(),
                static_cast<int>(kFrameSamples),
                &result) != 0) {
            firered_vad_destroy(handle);
            return {};
        }
        probabilities.push_back(result.confidence);
    }

    firered_vad_destroy(handle);
    return probabilities;
}

jfloatArray ToJavaFloatArray(
    JNIEnv* env,
    const std::vector<float>& values) {
    jfloatArray output =
        env->NewFloatArray(static_cast<jsize>(values.size()));
    if (!output) return nullptr;
    if (!values.empty()) {
        env->SetFloatArrayRegion(
            output,
            0,
            static_cast<jsize>(values.size()),
            values.data()
        );
    }
    return output;
}

}  // namespace

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_memoflow_vad_FireRedVadNative_detect(
    JNIEnv* env,
    jobject,
    jint mode,
    jstring model_dir,
    jbyteArray pcm16le) {
    const std::string model_path = JStringToString(env, model_dir);
    const std::vector<int16_t> pcm = PcmFromByteArray(env, pcm16le);

    if (model_path.empty() || pcm.empty()) {
        return env->NewFloatArray(0);
    }

    std::vector<float> probabilities;
    if (mode == 0) {
        probabilities = DetectNonStream(model_path, pcm);
    } else if (mode == 1) {
        probabilities = DetectStream(model_path, pcm);
    } else {
        return env->NewFloatArray(0);
    }

    return ToJavaFloatArray(env, probabilities);
}
