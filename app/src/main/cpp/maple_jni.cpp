#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "MEMO-MAPLE-JNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using maple_engine_t = void*;
using create_fn = maple_engine_t (*)(const char*, int, int, float, int);
using destroy_fn = void (*)(maple_engine_t);
using predict_app_type_fn = int (*)(maple_engine_t, const char*, char*, size_t);
using predict_next_app_fn = int (*)(maple_engine_t, const char*, const char*, char*, size_t);

struct MapleSymbols {
    void* handle = nullptr;
    create_fn create = nullptr;
    destroy_fn destroy = nullptr;
    predict_app_type_fn predict_app_type = nullptr;
    predict_next_app_fn predict_next_app = nullptr;
    std::string error;
};

static std::mutex g_mutex;
static MapleSymbols g_symbols;
static bool g_loaded = false;

static bool load_symbols_locked() {
    if (g_loaded) return g_symbols.handle != nullptr;
    g_loaded = true;
    const std::vector<const char*> candidates = {
        "libmaple_engine.so",
        "/data/local/tmp/memo/libmaple_engine.so",
        "/sdcard/MEMO/lib/libmaple_engine.so",
    };
    for (const char* path : candidates) {
        void* handle = dlopen(path, RTLD_NOW);
        if (!handle) {
            const char* error = dlerror();
            g_symbols.error = error ? error : "dlopen failed";
            continue;
        }
        g_symbols.create = reinterpret_cast<create_fn>(dlsym(handle, "maple_engine_create"));
        g_symbols.destroy = reinterpret_cast<destroy_fn>(dlsym(handle, "maple_engine_destroy"));
        g_symbols.predict_app_type = reinterpret_cast<predict_app_type_fn>(dlsym(handle, "maple_predict_app_type"));
        g_symbols.predict_next_app = reinterpret_cast<predict_next_app_fn>(dlsym(handle, "maple_predict_next_app"));
        if (g_symbols.create && g_symbols.destroy && g_symbols.predict_app_type && g_symbols.predict_next_app) {
            g_symbols.handle = handle;
            return true;
        }
        g_symbols.error = "libmaple_engine.so is missing required C API symbols";
        dlclose(handle);
    }
    LOGE("MAPLE engine unavailable: %s", g_symbols.error.c_str());
    return false;
}

static std::string jstring_to_string(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

static jstring string_to_jstring(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_memoos_maple_MapleNative_createEngine(
    JNIEnv* env,
    jobject,
    jstring modelPath,
    jint nCtx,
    jint nThreads,
    jfloat temperature,
    jint maxTokens) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!load_symbols_locked()) return 0;
    std::string path = jstring_to_string(env, modelPath);
    maple_engine_t engine = g_symbols.create(path.c_str(), nCtx, nThreads, temperature, maxTokens);
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_memoos_maple_MapleNative_destroyEngine(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!handle || !load_symbols_locked()) return;
    g_symbols.destroy(reinterpret_cast<maple_engine_t>(handle));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_memoos_maple_MapleNative_predictAppType(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring contextJson) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!handle || !load_symbols_locked()) return string_to_jstring(env, "{}");
    std::string context = jstring_to_string(env, contextJson);
    std::vector<char> output(64 * 1024);
    int rc = g_symbols.predict_app_type(
        reinterpret_cast<maple_engine_t>(handle),
        context.c_str(),
        output.data(),
        output.size());
    if (rc != 0) return string_to_jstring(env, "{}");
    return string_to_jstring(env, output.data());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_memoos_maple_MapleNative_predictNextApp(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring contextJson,
    jstring stage1Json) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!handle || !load_symbols_locked()) return string_to_jstring(env, "{}");
    std::string context = jstring_to_string(env, contextJson);
    std::string stage1 = jstring_to_string(env, stage1Json);
    std::vector<char> output(64 * 1024);
    int rc = g_symbols.predict_next_app(
        reinterpret_cast<maple_engine_t>(handle),
        context.c_str(),
        stage1.c_str(),
        output.data(),
        output.size());
    if (rc != 0) return string_to_jstring(env, "{}");
    return string_to_jstring(env, output.data());
}
