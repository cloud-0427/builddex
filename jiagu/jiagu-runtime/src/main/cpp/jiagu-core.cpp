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

// 辅助函数：使用 JNI 调用 Java 的 AES-GCM 解密
void decrypt_aes_gcm(JNIEnv *env, unsigned char* data, size_t size, const std::string& key_base64) {
    if (key_base64.empty() || size <= 12) return;

    // 1. 获取 IV (前 12 字节)
    jbyteArray iv_array = env->NewByteArray(12);
    env->SetByteArrayRegion(iv_array, 0, 12, reinterpret_cast<jbyte*>(data));

    // 2. 获取加密数据 (IV 之后的部分)
    size_t cipher_len = size - 12;
    jbyteArray cipher_array = env->NewByteArray(cipher_len);
    env->SetByteArrayRegion(cipher_array, 0, cipher_len, reinterpret_cast<jbyte*>(data + 12));

    // 3. 准备密钥
    jclass base64_class = env->FindClass("java/util/Base64");
    jmethodID get_decoder = env->GetStaticMethodID(base64_class, "getDecoder", "()Ljava/util/Base64$Decoder;");
    jobject decoder = env->CallStaticObjectMethod(base64_class, get_decoder);
    jmethodID decode_mid = env->GetMethodID(env->FindClass("java/util/Base64$Decoder"), "decode", "(Ljava/lang/String;)[B");
    jbyteArray key_bytes = (jbyteArray)env->CallObjectMethod(decoder, decode_mid, env->NewStringUTF(key_base64.c_str()));

    // 提取私钥中的原始字节并截取 32 字节 (AES-256)
    jbyte* kb = env->GetByteArrayElements(key_bytes, nullptr);
    jbyteArray aes_key_array = env->NewByteArray(32);
    env->SetByteArrayRegion(aes_key_array, 0, 32, kb);
    env->ReleaseByteArrayElements(key_bytes, kb, 0);

    // 4. 执行 AES-GCM 解密
    jclass cipher_class = env->FindClass("javax/crypto/Cipher");
    jmethodID get_instance = env->GetStaticMethodID(cipher_class, "getInstance", "(Ljava/lang/String;)Ljavax/crypto/Cipher;");
    jobject cipher = env->CallStaticObjectMethod(cipher_class, get_instance, env->NewStringUTF("AES/GCM/NoPadding"));

    jclass key_spec_class = env->FindClass("javax/crypto/spec/SecretKeySpec");
    jmethodID key_spec_init = env->GetMethodID(key_spec_class, "<init>", "([BLjava/lang/String;)V");
    jobject key_spec = env->NewObject(key_spec_class, key_spec_init, aes_key_array, env->NewStringUTF("AES"));

    jclass gcm_spec_class = env->FindClass("javax/crypto/spec/GCMParameterSpec");
    jmethodID gcm_spec_init = env->GetMethodID(gcm_spec_class, "<init>", "(I[B)V");
    jobject gcm_spec = env->NewObject(gcm_spec_class, gcm_spec_init, 128, iv_array);

    jmethodID init_mid = env->GetMethodID(cipher_class, "init", "(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;)V");
    env->CallVoidMethod(cipher, init_mid, 2, key_spec, gcm_spec); // 2 = DECRYPT_MODE

    jmethodID do_final_mid = env->GetMethodID(cipher_class, "doFinal", "([B)[B");
    jbyteArray decrypted_bytes = (jbyteArray)env->CallObjectMethod(cipher, do_final_mid, cipher_array);

    if (env->ExceptionCheck()) {
        LOGE("Jiagu_Native: AES-GCM decryption failed (likely bad key/tag)");
        env->ExceptionDescribe();
        env->ExceptionClear();
        return;
    }

    // 5. 将结果拷回原内存
    jbyte* db = env->GetByteArrayElements(decrypted_bytes, nullptr);
    jsize db_len = env->GetArrayLength(decrypted_bytes);
    std::memcpy(data, db, db_len);
    env->ReleaseByteArrayElements(decrypted_bytes, db, 0);
}

// 辅助函数：十六进制字符串转字节数组
std::vector<unsigned char> hex_to_bytes(const std::string& hex) {
    std::vector<unsigned char> bytes;
    for (size_t i = 0; i < hex.length(); i += 2) {
        std::string byteString = hex.substr(i, 2);
        unsigned char byte = (unsigned char) strtol(byteString.c_str(), nullptr, 16);
        bytes.push_back(byte);
    }
    return bytes;
}

// 核心逻辑：从包裹块中解密出真正的 Session Key
std::string decrypt_kms_key(JNIEnv *env, const std::string& kms_blob_str) {
    // kms_blob_str 格式: salt|nonce|bksBlob
    size_t first_pipe = kms_blob_str.find('|');
    size_t second_pipe = kms_blob_str.find('|', first_pipe + 1);
    if (first_pipe == std::string::npos || second_pipe == std::string::npos) return "";

    std::string salt_hex = kms_blob_str.substr(0, first_pipe);
    std::string nonce_hex = kms_blob_str.substr(first_pipe + 1, second_pipe - first_pipe - 1);
    std::string bks_blob_hex = kms_blob_str.substr(second_pipe + 1);

    auto nonce_bytes = hex_to_bytes(nonce_hex);
    auto bks_blob_bytes = hex_to_bytes(bks_blob_hex);

    // 硬编码的 Master Key (与 JiaguTask 一致)
    std::string master_key = "PRO_JIAGU_MASTER_KEY_2026_SECRET";
    jbyteArray master_key_array = env->NewByteArray(master_key.length());
    env->SetByteArrayRegion(master_key_array, 0, master_key.length(), reinterpret_cast<const jbyte*>(master_key.c_str()));

    jbyteArray nonce_array = env->NewByteArray(nonce_bytes.size());
    env->SetByteArrayRegion(nonce_array, 0, nonce_bytes.size(), reinterpret_cast<const jbyte*>(nonce_bytes.data()));

    jbyteArray blob_array = env->NewByteArray(bks_blob_bytes.size());
    env->SetByteArrayRegion(blob_array, 0, bks_blob_bytes.size(), reinterpret_cast<const jbyte*>(bks_blob_bytes.data()));

    // 调用 Java AES-GCM 解密出 Session Key
    jclass cipher_class = env->FindClass("javax/crypto/Cipher");
    jmethodID get_instance = env->GetStaticMethodID(cipher_class, "getInstance", "(Ljava/lang/String;)Ljavax/crypto/Cipher;");
    jobject cipher = env->CallStaticObjectMethod(cipher_class, get_instance, env->NewStringUTF("AES/GCM/NoPadding"));

    jclass key_spec_class = env->FindClass("javax/crypto/spec/SecretKeySpec");
    jmethodID key_spec_init = env->GetMethodID(key_spec_class, "<init>", "([BLjava/lang/String;)V");
    jobject key_spec = env->NewObject(key_spec_class, key_spec_init, master_key_array, env->NewStringUTF("AES"));

    jclass gcm_spec_class = env->FindClass("javax/crypto/spec/GCMParameterSpec");
    jmethodID gcm_spec_init = env->GetMethodID(gcm_spec_class, "<init>", "(I[B)V");
    jobject gcm_spec = env->NewObject(gcm_spec_class, gcm_spec_init, 128, nonce_array);

    jmethodID init_mid = env->GetMethodID(cipher_class, "init", "(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;)V");
    env->CallVoidMethod(cipher, init_mid, 2, key_spec, gcm_spec); // DECRYPT_MODE

    jmethodID do_final_mid = env->GetMethodID(cipher_class, "doFinal", "([B)[B");
    jbyteArray session_key_bytes = (jbyteArray)env->CallObjectMethod(cipher, do_final_mid, blob_array);

    if (env->ExceptionCheck()) {
        LOGE("Jiagu_Native: KMS key unwrapping failed.");
        env->ExceptionDescribe();
        env->ExceptionClear();
        return "";
    }

    jclass base64_class = env->FindClass("java/util/Base64");
    jmethodID get_encoder = env->GetStaticMethodID(base64_class, "getEncoder", "()Ljava/util/Base64$Encoder;");
    jobject encoder = env->CallStaticObjectMethod(base64_class, get_encoder);
    jmethodID encode_mid = env->GetMethodID(env->FindClass("java/util/Base64$Encoder"), "encodeToString", "([B)Ljava/lang/String;");
    jstring session_key_base64_j = (jstring)env->CallObjectMethod(encoder, encode_mid, session_key_bytes);

    const char* sk_base64 = env->GetStringUTFChars(session_key_base64_j, nullptr);
    std::string result(sk_base64);
    env->ReleaseStringUTFChars(session_key_base64_j, sk_base64);

    return result;
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

    // 1. 获取配置 (URL 和 REAL_APPLICATION)
    jclass context_class = env->GetObjectClass(context);
    jmethodID get_package_manager = env->GetMethodID(context_class, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    jobject pm = env->CallObjectMethod(context, get_package_manager);
    jmethodID get_package_name = env->GetMethodID(context_class, "getPackageName", "()Ljava/lang/String;");
    jstring pkg_name = (jstring)env->CallObjectMethod(context, get_package_name);

    jclass pm_class = env->GetObjectClass(pm);
    jmethodID get_app_info = env->GetMethodID(pm_class, "getApplicationInfo", "(Ljava/lang/String;I)Landroid/content/pm/ApplicationInfo;");
    jobject app_info = env->CallObjectMethod(pm, get_app_info, pkg_name, 128); // GET_META_DATA

    jclass app_info_class = env->GetObjectClass(app_info);
    jfieldID meta_data_field = env->GetFieldID(app_info_class, "metaData", "Landroid/os/Bundle;");
    jobject meta_data = env->GetObjectField(app_info, meta_data_field);

    jclass bundle_class = env->FindClass("android/os/Bundle");
    jmethodID get_string = env->GetMethodID(bundle_class, "getString", "(Ljava/lang/String;)Ljava/lang/String;");

    jstring key_url_j = (jstring)env->CallObjectMethod(meta_data, get_string, env->NewStringUTF("KEY_URL"));
    jstring json_key_j = (jstring)env->CallObjectMethod(meta_data, get_string, env->NewStringUTF("JSON_KEY"));
    jstring real_app_name_j = (jstring)env->CallObjectMethod(meta_data, get_string, env->NewStringUTF("REAL_APPLICATION"));

    const char *key_url = env->GetStringUTFChars(key_url_j, nullptr);
    const char *json_key = env->GetStringUTFChars(json_key_j, nullptr);
    const char *real_app_name = env->GetStringUTFChars(real_app_name_j, nullptr);

    // 2. 安全获取私钥 (KeyStore 硬件绑定 + 有效期校验)
    std::string private_key;
    jclass network_helper = env->FindClass("io/github/xjc/jiagu/NetworkHelper");
    if (network_helper) {
         jmethodID get_secure_mid = env->GetStaticMethodID(network_helper, "getSecureKey", "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;");

         // 获取 vcode 逻辑
         jclass pkg_info_class = env->FindClass("android/content/pm/PackageInfo");
         jmethodID get_pkg_info = env->GetMethodID(pm_class, "getPackageInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
         jobject pkg_info = env->CallObjectMethod(pm, get_pkg_info, pkg_name, 0);
         jfieldID vcode_fid = env->GetFieldID(env->GetObjectClass(pkg_info), "versionCode", "I");
         jint vcode = env->GetIntField(pkg_info, vcode_fid);

         jstring fetched_key_j = (jstring)env->CallStaticObjectMethod(network_helper, get_secure_mid, context, key_url_j, json_key_j, vcode);
         if (fetched_key_j) {
             const char* fetched_key_raw = env->GetStringUTFChars(fetched_key_j, nullptr);
             std::string fetched_key_str(fetched_key_raw);

             // 执行 KMS 密钥解封，还原 Session Key
             private_key = decrypt_kms_key(env, fetched_key_str);

             env->ReleaseStringUTFChars(fetched_key_j, fetched_key_raw);
         }
    }

    if (private_key.empty()) {
        LOGE("Jiagu_Native: CRITICAL - Failed to acquire decryption key.");
        return;
    }

    // 3. 解密 DEX 加载与注入
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

        for (int i = 0; i < dex_count; ++i) {
            int dex_size = read_int_be(full_buffer.data(), offset);
            unsigned char* dex_ptr = reinterpret_cast<unsigned char*>(full_buffer.data() + offset);
            decrypt_aes_gcm(env, dex_ptr, dex_size, private_key);
            int plain_size = dex_size - 12 - 16;
            jobject bb = env->CallStaticObjectMethod(byte_buffer_class, allocate_direct, (jint)plain_size);
            std::memcpy(env->GetDirectBufferAddress(bb), dex_ptr, plain_size);
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

    // 4. 实例化 Real Application 并替换
    std::string real_app_path = real_app_name;
    for (size_t i = 0; i < real_app_path.length(); ++i) {
        if (real_app_path[i] == '.') real_app_path[i] = '/';
    }
    jclass real_app_class = env->FindClass(real_app_path.c_str());
    if (real_app_class) {
        jmethodID app_init = env->GetMethodID(real_app_class, "<init>", "()V");
        jobject real_app_obj = env->NewObject(real_app_class, app_init);
        gRealApp = env->NewGlobalRef(real_app_obj);

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

        jclass application_cls = env->FindClass("android/app/Application");
        jmethodID attach_mid = env->GetMethodID(application_cls, "attach", "(Landroid/content/Context;)V");
        env->CallVoidMethod(gRealApp, attach_mid, context);
    }

    env->ReleaseStringUTFChars(key_url_j, key_url);
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
