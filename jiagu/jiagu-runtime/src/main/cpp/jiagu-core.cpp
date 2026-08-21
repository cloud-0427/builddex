#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <cstring>

#define TAG "Jiagu_Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

void decrypt_memory(char* data, size_t size, const std::string& key) {
    if (key.empty()) return;
    unsigned char* u_data = reinterpret_cast<unsigned char*>(data);
    const unsigned char* u_key = reinterpret_cast<const unsigned char*>(key.c_str());
    size_t key_len = key.length();
    for (size_t i = 0; i < size; ++i) {
        u_data[i] ^= u_key[i % key_len];
    }
}

void inject_dex_elements(JNIEnv *env, jobject system_loader, jobject memory_loader) {
    jclass base_loader_class = env->FindClass("dalvik/system/BaseDexClassLoader");
    jfieldID path_list_field = env->GetFieldID(base_loader_class, "pathList", "Ldalvik/system/DexPathList;");

    jobject system_path_list = env->GetObjectField(system_loader, path_list_field);
    jobject memory_path_list = env->GetObjectField(memory_loader, path_list_field);

    jclass path_list_class = env->FindClass("dalvik/system/DexPathList");
    jfieldID dex_elements_field = env->GetFieldID(path_list_class, "dexElements", "[Ldalvik/system/DexPathList$Element;");

    jobjectArray system_elements = (jobjectArray)env->GetObjectField(system_path_list, dex_elements_field);
    jobjectArray memory_elements = (jobjectArray)env->GetObjectField(memory_path_list, dex_elements_field);

    jsize system_len = env->GetArrayLength(system_elements);
    jsize memory_len = env->GetArrayLength(memory_elements);
    jsize new_len = system_len + memory_len;

    jclass element_class = env->FindClass("dalvik/system/DexPathList$Element");
    jobjectArray new_elements = env->NewObjectArray(new_len, element_class, nullptr);

    for (int i = 0; i < memory_len; ++i) {
        env->SetObjectArrayElement(new_elements, i, env->GetObjectArrayElement(memory_elements, i));
    }
    for (int i = 0; i < system_len; ++i) {
        env->SetObjectArrayElement(new_elements, memory_len + i, env->GetObjectArrayElement(system_elements, i));
    }

    env->SetObjectField(system_path_list, dex_elements_field, new_elements);
}

static int read_int_be(const char* data, size_t& offset) {
    int value = (static_cast<unsigned char>(data[offset]) << 24) |
                (static_cast<unsigned char>(data[offset + 1]) << 16) |
                (static_cast<unsigned char>(data[offset + 2]) << 8) |
                (static_cast<unsigned char>(data[offset + 3]));
    offset += 4;
    return value;
}

static void native_init(JNIEnv *env, jobject thiz, jobject context, jstring aes_key) {
    const char *c_key = env->GetStringUTFChars(aes_key, nullptr);

    jclass context_class = env->GetObjectClass(context);
    jmethodID get_assets_method = env->GetMethodID(context_class, "getAssets", "()Landroid/content/res/AssetManager;");
    jobject asset_manager_obj = env->CallObjectMethod(context, get_assets_method);
    AAssetManager *mgr = AAssetManager_fromJava(env, asset_manager_obj);

    AAsset *asset = AAssetManager_open(mgr, "jiagu_data.bin", AASSET_MODE_BUFFER);
    if (!asset) {
        LOGE("Failed to open jiagu_data.bin");
        env->ReleaseStringUTFChars(aes_key, c_key);
        return;
    }

    size_t total_size = AAsset_getLength(asset);
    std::vector<char> full_buffer(total_size);
    AAsset_read(asset, full_buffer.data(), total_size);
    AAsset_close(asset);

    size_t offset = 0;
    int dex_count = read_int_be(full_buffer.data(), offset);
    LOGD("jiagu_data.bin contains %d DEX files", dex_count);

    jclass byte_buffer_class = env->FindClass("java/nio/ByteBuffer");
    jmethodID allocate_direct = env->GetStaticMethodID(byte_buffer_class, "allocateDirect", "(I)Ljava/nio/ByteBuffer;");
    jobjectArray bb_array = env->NewObjectArray(dex_count, byte_buffer_class, nullptr);

    std::string key_str(c_key);

    for (int i = 0; i < dex_count; ++i) {
        int dex_size = read_int_be(full_buffer.data(), offset);
        char* dex_ptr = full_buffer.data() + offset;

        decrypt_memory(dex_ptr, dex_size, key_str);

        jobject byte_buffer = env->CallStaticObjectMethod(byte_buffer_class, allocate_direct, (jint)dex_size);
        if (!byte_buffer) {
            LOGE("Failed to allocate direct ByteBuffer for DEX %d", i);
            continue;
        }

        void* bb_ptr = env->GetDirectBufferAddress(byte_buffer);
        std::memcpy(bb_ptr, dex_ptr, dex_size);
        env->SetObjectArrayElement(bb_array, i, byte_buffer);

        offset += dex_size;
        LOGD("DEX %d loaded: %d bytes", i, dex_size);
    }

    jclass memory_loader_class = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    jmethodID loader_init = env->GetMethodID(memory_loader_class, "<init>", "([Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");

    jclass app_class = env->GetObjectClass(thiz);
    jmethodID get_classloader = env->GetMethodID(app_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject system_loader = env->CallObjectMethod(thiz, get_classloader);

    jobject memory_loader = env->NewObject(memory_loader_class, loader_init, bb_array, system_loader);

    if (env->ExceptionCheck()) {
        LOGE("InMemoryDexClassLoader initialization failed");
        env->ExceptionDescribe();
        env->ExceptionClear();
        env->ReleaseStringUTFChars(aes_key, c_key);
        return;
    }

    if (memory_loader) {
        inject_dex_elements(env, system_loader, memory_loader);
        LOGD("DEX injection successful");
    } else {
        LOGE("Failed to create InMemoryDexClassLoader");
    }

    env->ReleaseStringUTFChars(aes_key, c_key);
}

static const JNINativeMethod gMethods[] = {
    {"nativeInit", "(Landroid/content/Context;Ljava/lang/String;)V", (void*)native_init}
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass clazz = env->FindClass("io/github/xjc/jiagu/ProxyApplication");
    if (clazz == nullptr) {
        return JNI_ERR;
    }

    if (env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0])) < 0) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
