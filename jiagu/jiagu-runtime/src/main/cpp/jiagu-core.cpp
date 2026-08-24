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

static jobject gRealApp = nullptr;

// 辅助函数：解密逻辑
void decrypt_memory(char* data, size_t size, const std::string& key) {
    if (key.empty()) return;
    unsigned char* u_data = reinterpret_cast<unsigned char*>(data);
    const unsigned char* u_key = reinterpret_cast<const unsigned char*>(key.c_str());
    size_t key_len = key.length();
    for (size_t i = 0; i < size; ++i) {
        u_data[i] ^= u_key[i % key_len];
    }
}

// 辅助函数：注入 DEX 到 ClassLoader
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

// --- 核心逻辑：Native 代理实现 ---

static void native_attach(JNIEnv *env, jobject thiz, jobject context) {
    LOGD("Native: Starting attachBaseContext proxy...");

    // 1. 获取 AES_KEY 和 REAL_APPLICATION 名称
    jclass context_class = env->GetObjectClass(context);
    jmethodID get_package_manager = env->GetMethodID(context_class, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    jobject pm = env->CallObjectMethod(context, get_package_manager);
    jmethodID get_package_name = env->GetMethodID(context_class, "getPackageName", "()Ljava/lang/String;");
    jstring pkg_name = (jstring)env->CallObjectMethod(context, get_package_name);

    jclass pm_class = env->GetObjectClass(pm);
    jmethodID get_app_info = env->GetMethodID(pm_class, "getApplicationInfo", "(Ljava/lang/String;I)Landroid/content/pm/ApplicationInfo;");
    jobject app_info = env->CallObjectMethod(pm, get_app_info, pkg_name, 128); // GET_META_DATA = 128

    jclass app_info_class = env->GetObjectClass(app_info);
    jfieldID meta_data_field = env->GetFieldID(app_info_class, "metaData", "Landroid/os/Bundle;");
    jobject meta_data = env->GetObjectField(app_info, meta_data_field);

    jclass bundle_class = env->FindClass("android/os/Bundle");
    jmethodID get_string = env->GetMethodID(bundle_class, "getString", "(Ljava/lang/String;)Ljava/lang/String;");

    jstring aes_key_j = (jstring)env->CallObjectMethod(meta_data, get_string, env->NewStringUTF("AES_KEY"));
    jstring real_app_name_j = (jstring)env->CallObjectMethod(meta_data, get_string, env->NewStringUTF("REAL_APPLICATION"));

    const char *aes_key = env->GetStringUTFChars(aes_key_j, nullptr);
    const char *real_app_name = env->GetStringUTFChars(real_app_name_j, nullptr);

    // 2. 加密 DEX 加载与注入 (保持原有逻辑)
    jmethodID get_assets_method = env->GetMethodID(context_class, "getAssets", "()Landroid/content/res/AssetManager;");
    jobject asset_manager_obj = env->CallObjectMethod(context, get_assets_method);
    AAssetManager *mgr = AAssetManager_fromJava(env, asset_manager_obj);
    AAsset *asset = AAssetManager_open(mgr, "jiagu_data.bin", AASSET_MODE_BUFFER);

    if (asset) {
        size_t total_size = AAsset_getLength(asset);
        std::vector<char> full_buffer(total_size);
        AAsset_read(asset, full_buffer.data(), total_size);
        AAsset_close(asset);

        size_t offset = 0;
        int dex_count = read_int_be(full_buffer.data(), offset);
        jclass byte_buffer_class = env->FindClass("java/nio/ByteBuffer");
        jmethodID allocate_direct = env->GetStaticMethodID(byte_buffer_class, "allocateDirect", "(I)Ljava/nio/ByteBuffer;");
        jobjectArray bb_array = env->NewObjectArray(dex_count, byte_buffer_class, nullptr);

        std::string key_str(aes_key);
        for (int i = 0; i < dex_count; ++i) {
            int dex_size = read_int_be(full_buffer.data(), offset);
            char* dex_ptr = full_buffer.data() + offset;
            decrypt_memory(dex_ptr, dex_size, key_str);
            jobject bb = env->CallStaticObjectMethod(byte_buffer_class, allocate_direct, (jint)dex_size);
            std::memcpy(env->GetDirectBufferAddress(bb), dex_ptr, dex_size);
            env->SetObjectArrayElement(bb_array, i, bb);
            offset += dex_size;
        }

        jclass mem_loader_class = env->FindClass("dalvik/system/InMemoryDexClassLoader");
        jmethodID loader_init = env->GetMethodID(mem_loader_class, "<init>", "([Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
        jclass app_class = env->GetObjectClass(thiz);
        jmethodID get_cl = env->GetMethodID(app_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
        jobject sys_cl = env->CallObjectMethod(thiz, get_cl);
        jobject mem_cl = env->NewObject(mem_loader_class, loader_init, bb_array, sys_cl);

        inject_dex_elements(env, sys_cl, mem_cl);
        LOGD("DEX Injection from JNI successful");
    }

    // 3. 实例化 Real Application 并替换 ActivityThread 状态
    std::string real_app_path = real_app_name;
    for(auto &c : real_app_path) if(c == '.') c = '/';
    jclass real_app_class = env->FindClass(real_app_path.c_str());
    if (!real_app_class) {
        LOGE("Failed to find real application class: %s", real_app_name);
        return;
    }

    jmethodID app_init = env->GetMethodID(real_app_class, "<init>", "()V");
    jobject real_app_obj = env->NewObject(real_app_class, app_init);
    gRealApp = env->NewGlobalRef(real_app_obj);

    // 4. 反射替换 ActivityThread 系统变量
    jclass activity_thread_cls = env->FindClass("android/app/ActivityThread");
    jmethodID current_at_mid = env->GetStaticMethodID(activity_thread_cls, "currentActivityThread", "()Landroid/app/ActivityThread;");
    jobject current_at = env->CallStaticObjectMethod(activity_thread_cls, current_at_mid);

    jfieldID m_bound_app_fid = env->GetFieldID(activity_thread_cls, "mBoundApplication", "Landroid/app/ActivityThread$AppBindData;");
    jobject m_bound_app = env->GetObjectField(current_at, m_bound_app_fid);

    jclass app_bind_data_cls = env->GetObjectClass(m_bound_app);
    jfieldID info_fid = env->GetFieldID(app_bind_data_cls, "info", "Landroid/app/LoadedApk;");
    jobject loaded_apk = env->GetObjectField(m_bound_app, info_fid);

    jclass loaded_apk_cls = env->GetObjectClass(loaded_apk);
    jfieldID m_app_fid = env->GetFieldID(loaded_apk_cls, "mApplication", "Landroid/app/Application;");
    env->SetObjectField(loaded_apk, m_app_fid, gRealApp);

    jfieldID m_initial_app_fid = env->GetFieldID(activity_thread_cls, "mInitialApplication", "Landroid/app/Application;");
    env->SetObjectField(current_at, m_initial_app_fid, gRealApp);

    jfieldID m_all_apps_fid = env->GetFieldID(activity_thread_cls, "mAllApplications", "Ljava/util/ArrayList;");
    jobject m_all_apps = env->GetObjectField(current_at, m_all_apps_fid);
    jclass list_cls = env->FindClass("java/util/ArrayList");
    jmethodID remove_mid = env->GetMethodID(list_cls, "remove", "(Ljava/lang/Object;)Z");
    jmethodID add_mid = env->GetMethodID(list_cls, "add", "(Ljava/lang/Object;)Z");
    env->CallBooleanMethod(m_all_apps, remove_mid, thiz);
    env->CallBooleanMethod(m_all_apps, add_mid, gRealApp);

    // 5. 调用原 App 的 attach(context)
    jclass application_cls = env->FindClass("android/app/Application");
    jmethodID attach_mid = env->GetMethodID(application_cls, "attach", "(Landroid/content/Context;)V");
    env->CallVoidMethod(gRealApp, attach_mid, context);

    LOGD("Native: System Replacement and attachBaseContext successful");

    env->ReleaseStringUTFChars(aes_key_j, aes_key);
    env->ReleaseStringUTFChars(real_app_name_j, real_app_name);
}

static void native_on_create(JNIEnv *env, jobject thiz) {
    if (gRealApp) {
        jclass app_cls = env->GetObjectClass(gRealApp);
        jmethodID on_create_mid = env->GetMethodID(app_cls, "onCreate", "()V");
        env->CallVoidMethod(gRealApp, on_create_mid);
        LOGD("Native: realApplication.onCreate() invoked");
    }
}

static const JNINativeMethod gMethods[] = {
    {"nativeAttach", "(Landroid/content/Context;)V", (void*)native_attach},
    {"nativeOnCreate", "()V", (void*)native_on_create}
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass clazz = env->FindClass("io/github/xjc/jiagu/ProxyApplication");
    if (env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0])) < 0) return JNI_ERR;
    return JNI_VERSION_1_6;
}
