#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <cstring>
#include <cstdint>
#include <dlfcn.h>
#include <sys/ptrace.h>
#include <unistd.h>
#include <fcntl.h>
#include <pthread.h>
#include <algorithm>
#include "syscall_arch.h"
#include "obfuscate_str.h"

#define TAG X("Jiagu_Native")
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, X("Jiagu_Native"), __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, X("Jiagu_Native"), __VA_ARGS__)

#define JNI_CHECK_NULL(obj, msg, ret) \
    if (!(obj)) { \
        LOGE("Jiagu_Native: JNI Error - %s", msg); \
        if (env->ExceptionCheck()) env->ExceptionDescribe(); \
        env->ExceptionClear(); \
        return ret; \
    }

static jobject gRealApp = nullptr;

// --- 动态防护模块 ---

static __attribute__((always_inline)) inline void die_if_debugged() {
    // 1. Ptrace 占坑 (使用 raw syscall)
    if (raw_syscall(SYS_ptrace, PTRACE_TRACEME, 0, 1, 0) < 0) {
        _exit(0);
    }
    raw_syscall(SYS_ptrace, PTRACE_DETACH, 0, 1, 0);

    // 2. TracerPid 检测 (使用 raw syscall)
    char buf[512];
    int fd = raw_syscall_open(X("/proc/self/status"), O_RDONLY, 0);
    if (fd >= 0) {
        ssize_t len = raw_syscall(SYS_read, fd, (long)buf, sizeof(buf) - 1);
        raw_syscall(SYS_close, fd);
        if (len > 0) {
            buf[len] = 0;
            char* tracer_pid_ptr = strstr(buf, X("TracerPid:"));
            if (tracer_pid_ptr) {
                int tracer_pid = atoi(tracer_pid_ptr + 10);
                if (tracer_pid != 0) {
                    _exit(0);
                }
            }
        }
    }
}

static __attribute__((always_inline)) inline void die_if_hooked() {
    // 使用 raw syscall 读取 maps
    int fd = raw_syscall_open(X("/proc/self/maps"), O_RDONLY, 0);
    if (fd < 0) return;

    char buf[4096];
    ssize_t len;
    while ((len = raw_syscall(SYS_read, fd, (long)buf, sizeof(buf) - 1)) > 0) {
        buf[len] = 0;
        if (strstr(buf, X("frida")) || strstr(buf, X("xposed")) ||
            strstr(buf, X("libdobby")) || strstr(buf, X("substitute")) ||
            strstr(buf, X("substrate")) || strstr(buf, X("com.saurik.substrate"))) {
            raw_syscall(SYS_close, fd);
            _exit(0);
        }
    }
    raw_syscall(SYS_close, fd);
}

static __attribute__((always_inline)) inline bool verify_signature(JNIEnv* env, jobject context, const std::string& expected_hash) {
    if (expected_hash.empty()) return true; // 未配置则跳过

    jclass context_class = env->GetObjectClass(context);
    JNI_CHECK_NULL(context_class, "Context class not found in verify_signature", false);
    jmethodID get_pm = env->GetMethodID(context_class, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    JNI_CHECK_NULL(get_pm, "getPackageManager not found in verify_signature", false);
    jobject pm = env->CallObjectMethod(context, get_pm);
    JNI_CHECK_NULL(pm, "PackageManager not found in verify_signature", false);
    jmethodID get_pkg_name = env->GetMethodID(context_class, "getPackageName", "()Ljava/lang/String;");
    JNI_CHECK_NULL(get_pkg_name, "getPackageName not found in verify_signature", false);
    jstring pkg_name = (jstring)env->CallObjectMethod(context, get_pkg_name);
    JNI_CHECK_NULL(pkg_name, "PackageName not found in verify_signature", false);

    jclass pm_class = env->GetObjectClass(pm);
    JNI_CHECK_NULL(pm_class, "PackageManager class not found in verify_signature", false);
    // 使用 GET_SIGNATURES (64)
    jmethodID get_pkg_info = env->GetMethodID(pm_class, "getPackageInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
    JNI_CHECK_NULL(get_pkg_info, "getPackageInfo not found in verify_signature", false);
    jobject pkg_info = env->CallObjectMethod(pm, get_pkg_info, pkg_name, 64);
    JNI_CHECK_NULL(pkg_info, "PackageInfo not found in verify_signature", false);

    jclass pkg_info_class = env->GetObjectClass(pkg_info);
    JNI_CHECK_NULL(pkg_info_class, "PackageInfo class not found in verify_signature", false);
    jfieldID sigs_fid = env->GetFieldID(pkg_info_class, "signatures", "[Landroid/content/pm/Signature;");
    JNI_CHECK_NULL(sigs_fid, "signatures field not found in verify_signature", false);
    jobjectArray sigs = (jobjectArray)env->GetObjectField(pkg_info, sigs_fid);

    if (!sigs || env->GetArrayLength(sigs) == 0) return false;

    jobject sig = env->GetObjectArrayElement(sigs, 0);
    JNI_CHECK_NULL(sig, "Signature element not found in verify_signature", false);
    jclass sig_class = env->GetObjectClass(sig);
    JNI_CHECK_NULL(sig_class, "Signature class not found in verify_signature", false);
    jmethodID to_byte_array = env->GetMethodID(sig_class, "toByteArray", "()[B");
    JNI_CHECK_NULL(to_byte_array, "toByteArray not found in verify_signature", false);
    jbyteArray sig_bytes = (jbyteArray)env->CallObjectMethod(sig, to_byte_array);
    JNI_CHECK_NULL(sig_bytes, "toByteArray result not found in verify_signature", false);

    // 计算 SHA-256
    jclass digest_class = env->FindClass("java/security/MessageDigest");
    JNI_CHECK_NULL(digest_class, "MessageDigest class not found in verify_signature", false);
    jmethodID get_instance = env->GetStaticMethodID(digest_class, "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    JNI_CHECK_NULL(get_instance, "MessageDigest.getInstance not found in verify_signature", false);
    jobject digest_obj = env->CallStaticObjectMethod(digest_class, get_instance, env->NewStringUTF(X("SHA-256")));
    JNI_CHECK_NULL(digest_obj, "MessageDigest instance not found in verify_signature", false);
    jmethodID digest_mid = env->GetMethodID(digest_class, "digest", "([B)[B");
    JNI_CHECK_NULL(digest_mid, "MessageDigest.digest not found in verify_signature", false);
    jbyteArray hash_bytes = (jbyteArray)env->CallObjectMethod(digest_obj, digest_mid, sig_bytes);
    JNI_CHECK_NULL(hash_bytes, "MessageDigest.digest result not found in verify_signature", false);

    // 转为 Hex 字符串比较
    jsize len = env->GetArrayLength(hash_bytes);
    jbyte* hb = env->GetByteArrayElements(hash_bytes, nullptr);
    std::string actual_hash;
    actual_hash.reserve(len * 2);
    for (int i = 0; i < len; i++) {
        char tmp[3];
        snprintf(tmp, sizeof(tmp), "%02x", (unsigned char)hb[i]);
        actual_hash += tmp;
    }
    env->ReleaseByteArrayElements(hash_bytes, hb, 0);

    if (actual_hash != expected_hash) {
        LOGE("Jiagu_Native: Signature mismatch! Actual: %s, Expected: %s", actual_hash.c_str(), expected_hash.c_str());
        return false;
    }
    return true;
}

// 辅助函数：使用 JNI 调用 Java 的 AES-GCM 解密
bool decrypt_aes_gcm(JNIEnv *env, unsigned char* data, size_t size, const std::string& key_base64) {
    if (key_base64.empty() || size < 28) return false;

    // 1. 获取 IV (前 12 字节)
    jbyteArray iv_array = env->NewByteArray(12);
    env->SetByteArrayRegion(iv_array, 0, 12, reinterpret_cast<jbyte*>(data));

    // 2. 获取加密数据 (IV 之后的部分)
    size_t cipher_len = size - 12;
    jbyteArray cipher_array = env->NewByteArray(cipher_len);
    env->SetByteArrayRegion(cipher_array, 0, cipher_len, reinterpret_cast<jbyte*>(data + 12));

    // 3. 准备密钥
    jclass base64_class = env->FindClass(X("android/util/Base64"));
    jmethodID decode_mid = env->GetStaticMethodID(base64_class, X("decode"), X("(Ljava/lang/String;I)[B"));
    jbyteArray key_bytes = (jbyteArray)env->CallStaticObjectMethod(base64_class, decode_mid, env->NewStringUTF(key_base64.c_str()), 0); // 0 = DEFAULT

    // 提取私钥中的原始字节并截取 32 字节 (AES-256)
    jbyte* kb = env->GetByteArrayElements(key_bytes, nullptr);
    jbyteArray aes_key_array = env->NewByteArray(32);
    env->SetByteArrayRegion(aes_key_array, 0, 32, kb);
    env->ReleaseByteArrayElements(key_bytes, kb, 0);

    // 4. 执行 AES-GCM 解密
    jclass cipher_class = env->FindClass(X("javax/crypto/Cipher"));
    JNI_CHECK_NULL(cipher_class, "Cipher class not found", false);
    jmethodID get_instance = env->GetStaticMethodID(cipher_class, X("getInstance"), X("(Ljava/lang/String;)Ljavax/crypto/Cipher;"));
    JNI_CHECK_NULL(get_instance, "Cipher.getInstance not found", false);
    jobject cipher = env->CallStaticObjectMethod(cipher_class, get_instance, env->NewStringUTF(X("AES/GCM/NoPadding")));
    JNI_CHECK_NULL(cipher, "Cipher instance creation failed", false);

    jclass key_spec_class = env->FindClass(X("javax/crypto/spec/SecretKeySpec"));
    JNI_CHECK_NULL(key_spec_class, "SecretKeySpec class not found", false);
    jmethodID key_spec_init = env->GetMethodID(key_spec_class, "<init>", X("([BLjava/lang/String;)V"));
    JNI_CHECK_NULL(key_spec_init, "SecretKeySpec.<init> not found", false);
    jobject key_spec = env->NewObject(key_spec_class, key_spec_init, aes_key_array, env->NewStringUTF(X("AES")));

    jclass gcm_spec_class = env->FindClass("javax/crypto/spec/GCMParameterSpec");
    JNI_CHECK_NULL(gcm_spec_class, "GCMParameterSpec class not found", false);
    jmethodID gcm_spec_init = env->GetMethodID(gcm_spec_class, "<init>", "(I[B)V");
    JNI_CHECK_NULL(gcm_spec_init, "GCMParameterSpec.<init> not found", false);
    jobject gcm_spec = env->NewObject(gcm_spec_class, gcm_spec_init, 128, iv_array);

    jmethodID init_mid = env->GetMethodID(cipher_class, "init", "(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;)V");
    env->CallVoidMethod(cipher, init_mid, 2, key_spec, gcm_spec); // 2 = DECRYPT_MODE

    jmethodID do_final_mid = env->GetMethodID(cipher_class, "doFinal", "([B)[B");
    jbyteArray decrypted_bytes = (jbyteArray)env->CallObjectMethod(cipher, do_final_mid, cipher_array);

    if (env->ExceptionCheck()) {
        LOGE("Jiagu_Native: AES-GCM decryption failed (likely bad key/tag)");
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
    }

    // 5. 将结果拷回原内存
    jbyte* db = env->GetByteArrayElements(decrypted_bytes, nullptr);
    jsize db_len = env->GetArrayLength(decrypted_bytes);
    std::memcpy(data, db, db_len);
    env->ReleaseByteArrayElements(decrypted_bytes, db, 0);
    return true;
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
std::string decrypt_kms_key(JNIEnv *env, const std::string& kms_blob_str, const std::string& pkg_name, const std::string& version_name) {
    // kms_blob_str 格式: salt|nonce|bksBlob
    size_t first_pipe = kms_blob_str.find('|');
    size_t second_pipe = kms_blob_str.find('|', first_pipe + 1);
    if (first_pipe == std::string::npos || second_pipe == std::string::npos) return "";

    std::string salt_hex = kms_blob_str.substr(0, first_pipe);
    std::string nonce_hex = kms_blob_str.substr(first_pipe + 1, second_pipe - first_pipe - 1);
    std::string bks_blob_hex = kms_blob_str.substr(second_pipe + 1);

    auto nonce_bytes = hex_to_bytes(nonce_hex);
    auto bks_blob_bytes = hex_to_bytes(bks_blob_hex);

    // 动态派生 Master Key: SHA-256(pkg:version:salt)
    // 使用 JNI 调用 Java MessageDigest 确保算法实现对齐且代码精简
    jclass digest_class = env->FindClass("java/security/MessageDigest");
    JNI_CHECK_NULL(digest_class, "MessageDigest class not found", "");
    jmethodID get_instance = env->GetStaticMethodID(digest_class, "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    JNI_CHECK_NULL(get_instance, "MessageDigest.getInstance not found", "");
    jobject digest_obj = env->CallStaticObjectMethod(digest_class, get_instance, env->NewStringUTF(X("SHA-256")));
    JNI_CHECK_NULL(digest_obj, "MessageDigest instance creation failed", "");

    // 构造混淆盐值
    std::string salt_str = X("JIAGU_SALT_2026");

    std::string input_str = pkg_name + ":" + version_name + ":" + salt_str;
    jbyteArray input_bytes = env->NewByteArray(input_str.length());
    env->SetByteArrayRegion(input_bytes, 0, input_str.length(), reinterpret_cast<const jbyte*>(input_str.c_str()));

    jmethodID digest_mid = env->GetMethodID(digest_class, "digest", "([B)[B");
    jbyteArray master_key_array = (jbyteArray)env->CallObjectMethod(digest_obj, digest_mid, input_bytes);

    jbyteArray nonce_array = env->NewByteArray(nonce_bytes.size());
    env->SetByteArrayRegion(nonce_array, 0, nonce_bytes.size(), reinterpret_cast<const jbyte*>(nonce_bytes.data()));

    jbyteArray blob_array = env->NewByteArray(bks_blob_bytes.size());
    env->SetByteArrayRegion(blob_array, 0, bks_blob_bytes.size(), reinterpret_cast<const jbyte*>(bks_blob_bytes.data()));

    // 调用 Java AES-GCM 解密出 Session Key
    jclass cipher_class = env->FindClass("javax/crypto/Cipher");
    JNI_CHECK_NULL(cipher_class, "Cipher class not found in KMS", "");
    jmethodID get_cipher_instance = env->GetStaticMethodID(cipher_class, "getInstance", "(Ljava/lang/String;)Ljavax/crypto/Cipher;");
    JNI_CHECK_NULL(get_cipher_instance, "Cipher.getInstance not found in KMS", "");
    jobject cipher = env->CallStaticObjectMethod(cipher_class, get_cipher_instance, env->NewStringUTF("AES/GCM/NoPadding"));
    JNI_CHECK_NULL(cipher, "Cipher instance creation failed in KMS", "");

    jclass key_spec_class = env->FindClass("javax/crypto/spec/SecretKeySpec");
    JNI_CHECK_NULL(key_spec_class, "SecretKeySpec class not found in KMS", "");
    jmethodID key_spec_init = env->GetMethodID(key_spec_class, "<init>", "([BLjava/lang/String;)V");
    JNI_CHECK_NULL(key_spec_init, "SecretKeySpec.<init> not found in KMS", "");
    jobject key_spec = env->NewObject(key_spec_class, key_spec_init, master_key_array, env->NewStringUTF("AES"));
    JNI_CHECK_NULL(key_spec, "SecretKeySpec instance creation failed in KMS", "");

    jclass gcm_spec_class = env->FindClass("javax/crypto/spec/GCMParameterSpec");
    JNI_CHECK_NULL(gcm_spec_class, "GCMParameterSpec class not found in KMS", "");
    jmethodID gcm_spec_init = env->GetMethodID(gcm_spec_class, "<init>", "(I[B)V");
    JNI_CHECK_NULL(gcm_spec_init, "GCMParameterSpec.<init> not found in KMS", "");
    jobject gcm_spec = env->NewObject(gcm_spec_class, gcm_spec_init, 128, nonce_array);
    JNI_CHECK_NULL(gcm_spec, "GCMParameterSpec instance creation failed in KMS", "");

    jmethodID init_mid = env->GetMethodID(cipher_class, "init", "(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;)V");
    JNI_CHECK_NULL(init_mid, "Cipher.init not found in KMS", "");
    env->CallVoidMethod(cipher, init_mid, 2, key_spec, gcm_spec); // DECRYPT_MODE

    jmethodID do_final_mid = env->GetMethodID(cipher_class, "doFinal", "([B)[B");
    JNI_CHECK_NULL(do_final_mid, "Cipher.doFinal not found in KMS", "");
    jbyteArray session_key_bytes = (jbyteArray)env->CallObjectMethod(cipher, do_final_mid, blob_array);
    JNI_CHECK_NULL(session_key_bytes, "Session Key decryption failed in KMS", "");

    jclass base64_class = env->FindClass("android/util/Base64");
    JNI_CHECK_NULL(base64_class, "Base64 class not found in KMS", "");
    jmethodID encode_mid = env->GetStaticMethodID(base64_class, "encodeToString", "([BI)Ljava/lang/String;");
    JNI_CHECK_NULL(encode_mid, "Base64.encodeToString not found in KMS", "");
    jstring session_key_base64_j = (jstring)env->CallStaticObjectMethod(base64_class, encode_mid, session_key_bytes, 2); // 2 = NO_WRAP
    JNI_CHECK_NULL(session_key_base64_j, "Base64 encoding failed in KMS", "");

    const char* sk_base64 = env->GetStringUTFChars(session_key_base64_j, nullptr);
    std::string result(sk_base64);
    env->ReleaseStringUTFChars(session_key_base64_j, sk_base64);

    return result;
}

// 辅助函数：注入 DEX 到 ClassLoader
void inject_dex_elements(JNIEnv *env, jobject system_loader, jobject memory_loader) {
    jclass base_loader_class = env->FindClass(X("dalvik/system/BaseDexClassLoader"));
    JNI_CHECK_NULL(base_loader_class, "BaseDexClassLoader class not found", );
    jfieldID path_list_field = env->GetFieldID(base_loader_class, X("pathList"), X("Ldalvik/system/DexPathList;"));
    JNI_CHECK_NULL(path_list_field, "pathList field not found", );
    jobject system_path_list = env->GetObjectField(system_loader, path_list_field);
    JNI_CHECK_NULL(system_path_list, "system pathList not found", );
    jobject memory_path_list = env->GetObjectField(memory_loader, path_list_field);
    JNI_CHECK_NULL(memory_path_list, "memory pathList not found", );
    jclass path_list_class = env->FindClass("dalvik/system/DexPathList");
    JNI_CHECK_NULL(path_list_class, "DexPathList class not found", );
    jfieldID dex_elements_field = env->GetFieldID(path_list_class, "dexElements", "[Ldalvik/system/DexPathList$Element;");
    JNI_CHECK_NULL(dex_elements_field, "dexElements field not found", );
    jobjectArray system_elements = (jobjectArray)env->GetObjectField(system_path_list, dex_elements_field);
    JNI_CHECK_NULL(system_elements, "system dexElements not found", );
    jobjectArray memory_elements = (jobjectArray)env->GetObjectField(memory_path_list, dex_elements_field);
    JNI_CHECK_NULL(memory_elements, "memory dexElements not found", );
    jsize system_len = env->GetArrayLength(system_elements);
    jsize memory_len = env->GetArrayLength(memory_elements);
    jsize new_len = system_len + memory_len;
    jclass element_class = env->FindClass("dalvik/system/DexPathList$Element");
    JNI_CHECK_NULL(element_class, "DexPathList$Element class not found", );
    jobjectArray new_elements = env->NewObjectArray(new_len, element_class, nullptr);
    JNI_CHECK_NULL(new_elements, "Failed to create new dexElements array", );
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
    JNI_CHECK_NULL(context_class, "Context class not found", );
    jmethodID get_package_manager = env->GetMethodID(context_class, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    JNI_CHECK_NULL(get_package_manager, "getPackageManager method not found", );
    jobject pm = env->CallObjectMethod(context, get_package_manager);
    JNI_CHECK_NULL(pm, "PackageManager not found", );
    jmethodID get_package_name = env->GetMethodID(context_class, "getPackageName", "()Ljava/lang/String;");
    JNI_CHECK_NULL(get_package_name, "getPackageName method not found", );
    jstring pkg_name = (jstring)env->CallObjectMethod(context, get_package_name);

    jclass pm_class = env->GetObjectClass(pm);
    JNI_CHECK_NULL(pm_class, "PackageManager class not found", );
    jmethodID get_app_info = env->GetMethodID(pm_class, "getApplicationInfo", "(Ljava/lang/String;I)Landroid/content/pm/ApplicationInfo;");
    JNI_CHECK_NULL(get_app_info, "getApplicationInfo method not found", );
    jobject app_info = env->CallObjectMethod(pm, get_app_info, pkg_name, 128); // GET_META_DATA

    jclass app_info_class = env->GetObjectClass(app_info);
    JNI_CHECK_NULL(app_info_class, "ApplicationInfo class not found", );
    jfieldID meta_data_field = env->GetFieldID(app_info_class, "metaData", "Landroid/os/Bundle;");
    JNI_CHECK_NULL(meta_data_field, "metaData field not found", );
    jobject meta_data = env->GetObjectField(app_info, meta_data_field);

    jclass bundle_class = env->FindClass("android/os/Bundle");
    JNI_CHECK_NULL(bundle_class, "Bundle class not found", );
    jmethodID get_string = env->GetMethodID(bundle_class, "getString", "(Ljava/lang/String;)Ljava/lang/String;");

    jstring key_url_j = (jstring)env->CallObjectMethod(meta_data, get_string, env->NewStringUTF(X("KEY_URL")));
    jstring json_key_j = (jstring)env->CallObjectMethod(meta_data, get_string, env->NewStringUTF(X("JSON_KEY")));
    jstring real_app_name_j = (jstring)env->CallObjectMethod(meta_data, get_string, env->NewStringUTF(X("REAL_APPLICATION")));

    // 读取防护配置
    jmethodID get_bool = env->GetMethodID(bundle_class, "getBoolean", "(Ljava/lang/String;Z)Z");
    bool anti_debug = env->CallBooleanMethod(meta_data, get_bool, env->NewStringUTF(X("ENABLE_ANTI_DEBUG")), true);
    bool sig_check = env->CallBooleanMethod(meta_data, get_bool, env->NewStringUTF(X("ENABLE_SIGNATURE_CHECK")), true);
    jstring expected_sig_j = (jstring)env->CallObjectMethod(meta_data, get_string, env->NewStringUTF(X("EXPECTED_SIGNATURE")));

    if (anti_debug) {
        die_if_debugged();
        die_if_hooked();
    }

    if (sig_check && expected_sig_j) {
        const char* expected_sig = env->GetStringUTFChars(expected_sig_j, nullptr);
        if (!verify_signature(env, context, expected_sig)) {
            _exit(0);
        }
        env->ReleaseStringUTFChars(expected_sig_j, expected_sig);
    }

    const char *key_url = env->GetStringUTFChars(key_url_j, nullptr);
    const char *json_key = env->GetStringUTFChars(json_key_j, nullptr);
    const char *real_app_name = env->GetStringUTFChars(real_app_name_j, nullptr);
    const char *pkg_name_str = env->GetStringUTFChars(pkg_name, nullptr);

    // 2. 安全获取私钥 (KeyStore 硬件绑定 + 有效期校验)
    std::string private_key;
    jclass network_helper = env->FindClass("io/github/xjc/jiagu/NetworkHelper");
    if (network_helper) {
         jmethodID get_secure_mid = env->GetStaticMethodID(network_helper, "getSecureKey", "(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;");
         JNI_CHECK_NULL(get_secure_mid, "getSecureKey method not found", );

         // 获取 vcode 和 vname 逻辑
         jclass pkg_info_class = env->FindClass("android/content/pm/PackageInfo");
         JNI_CHECK_NULL(pkg_info_class, "PackageInfo class not found", );
         jmethodID get_pkg_info_mid = env->GetMethodID(pm_class, "getPackageInfo", "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
         JNI_CHECK_NULL(get_pkg_info_mid, "getPackageInfo method not found", );
         jobject pkg_info = env->CallObjectMethod(pm, get_pkg_info_mid, pkg_name, 0);
         JNI_CHECK_NULL(pkg_info, "PackageInfo not found", );
         jfieldID vcode_fid = env->GetFieldID(env->GetObjectClass(pkg_info), "versionCode", "I");
         JNI_CHECK_NULL(vcode_fid, "versionCode field not found", );
         jint vcode = env->GetIntField(pkg_info, vcode_fid);

         jfieldID vname_fid = env->GetFieldID(env->GetObjectClass(pkg_info), "versionName", "Ljava/lang/String;");
         JNI_CHECK_NULL(vname_fid, "versionName field not found", );
         jstring vname_j = (jstring)env->GetObjectField(pkg_info, vname_fid);
         JNI_CHECK_NULL(vname_j, "versionName not found", );
         const char* vname_str = env->GetStringUTFChars(vname_j, nullptr);

         jstring fetched_key_j = (jstring)env->CallStaticObjectMethod(network_helper, get_secure_mid, context, key_url_j, json_key_j, vcode);
         if (fetched_key_j) {
             const char* fetched_key_raw = env->GetStringUTFChars(fetched_key_j, nullptr);
             std::string fetched_key_str(fetched_key_raw);

             // 执行 KMS 密钥解封，还原 Session Key (传入包名和版本名进行动态 Master Key 派生)
             private_key = decrypt_kms_key(env, fetched_key_str, pkg_name_str, vname_str);

             env->ReleaseStringUTFChars(fetched_key_j, fetched_key_raw);
         }
         env->ReleaseStringUTFChars(vname_j, vname_str);
    }

    if (private_key.empty()) {
        LOGE("Jiagu_Native: CRITICAL - Failed to acquire decryption key.");
        env->ReleaseStringUTFChars(key_url_j, key_url);
        env->ReleaseStringUTFChars(real_app_name_j, real_app_name);
        env->ReleaseStringUTFChars(pkg_name, pkg_name_str);
        return;
    }

    // 3. 由 Android linker 加载真实 ELF，并从其只读自定义段取得加密载荷。
    using payload_address_fn = const uint8_t* (*)();
    using payload_size_fn = size_t (*)();

    void* payload_handle = dlopen("liblog_ext.so", RTLD_NOW | RTLD_LOCAL);
    if (!payload_handle) {
        LOGE("Jiagu_Native: Failed to load payload ELF: %s", dlerror());
        env->ReleaseStringUTFChars(key_url_j, key_url);
        env->ReleaseStringUTFChars(real_app_name_j, real_app_name);
        env->ReleaseStringUTFChars(pkg_name, pkg_name_str);
        return;
    }

    auto payload_address = reinterpret_cast<payload_address_fn>(
            dlsym(payload_handle, "jg_payload_address"));
    auto payload_size = reinterpret_cast<payload_size_fn>(
            dlsym(payload_handle, "jg_payload_size"));
    if (!payload_address || !payload_size) {
        LOGE("Jiagu_Native: Payload ELF exports are missing: %s", dlerror());
        dlclose(payload_handle);
        return;
    }

    const uint8_t* payload_source = payload_address();
    size_t payload_length = payload_size();
    if (!payload_source || payload_length < 8) {
        LOGE("Jiagu_Native: Payload ELF returned invalid data");
        dlclose(payload_handle);
        return;
    }

    // ELF 段是只读映射；复制后才能在缓冲区内执行解密。
    std::vector<char> full_buffer(
            reinterpret_cast<const char*>(payload_source),
            reinterpret_cast<const char*>(payload_source) + payload_length);
    dlclose(payload_handle);

    if (std::memcmp(full_buffer.data(), "JAG\0", 4) != 0) {
        LOGE("Jiagu_Native: Invalid payload magic");
        return;
    }

    size_t offset = 4;
    int meta_size = read_int_be(full_buffer.data(), offset);
    if (meta_size < 28 || static_cast<size_t>(meta_size) > full_buffer.size() - offset) {
        LOGE("Jiagu_Native: Invalid metadata size: %d", meta_size);
        return;
    }

    unsigned char* meta_ptr = reinterpret_cast<unsigned char*>(full_buffer.data() + offset);
    if (!decrypt_aes_gcm(env, meta_ptr, static_cast<size_t>(meta_size), private_key)) {
        LOGE("Jiagu_Native: Failed to decrypt payload metadata");
        return;
    }

    size_t meta_plain_size = static_cast<size_t>(meta_size) - 12 - 16;
    if (meta_plain_size < 4) {
        LOGE("Jiagu_Native: Decrypted metadata is too small");
        return;
    }

    unsigned char* meta_data_ptr = meta_ptr;
    size_t meta_offset = 0;
    int dex_count = (meta_data_ptr[0] << 24) | (meta_data_ptr[1] << 16) |
                    (meta_data_ptr[2] << 8) | meta_data_ptr[3];
    meta_offset += 4;
    if (dex_count <= 0 || dex_count > 128 ||
            meta_plain_size < 4 + static_cast<size_t>(dex_count) * 8) {
        LOGE("Jiagu_Native: Invalid DEX count: %d", dex_count);
        return;
    }

    offset += static_cast<size_t>(meta_size);
    size_t body_start = offset;
    size_t body_size = full_buffer.size() - body_start;

    jclass byte_buffer_class = env->FindClass("java/nio/ByteBuffer");
    JNI_CHECK_NULL(byte_buffer_class, "ByteBuffer class not found", );
    jmethodID allocate_direct = env->GetStaticMethodID(
            byte_buffer_class, "allocateDirect", "(I)Ljava/nio/ByteBuffer;");
    JNI_CHECK_NULL(allocate_direct, "ByteBuffer.allocateDirect not found", );
    jobjectArray bb_array = env->NewObjectArray(dex_count, byte_buffer_class, nullptr);
    JNI_CHECK_NULL(bb_array, "Failed to create ByteBuffer array", );

    for (int i = 0; i < dex_count; ++i) {
        int dex_offset = (static_cast<unsigned char>(meta_data_ptr[meta_offset]) << 24) |
                         (static_cast<unsigned char>(meta_data_ptr[meta_offset + 1]) << 16) |
                         (static_cast<unsigned char>(meta_data_ptr[meta_offset + 2]) << 8) |
                         static_cast<unsigned char>(meta_data_ptr[meta_offset + 3]);
        meta_offset += 4;
        int dex_size = (static_cast<unsigned char>(meta_data_ptr[meta_offset]) << 24) |
                       (static_cast<unsigned char>(meta_data_ptr[meta_offset + 1]) << 16) |
                       (static_cast<unsigned char>(meta_data_ptr[meta_offset + 2]) << 8) |
                       static_cast<unsigned char>(meta_data_ptr[meta_offset + 3]);
        meta_offset += 4;

        // Each encrypted DEX is IV (12 bytes) + ciphertext + GCM tag (16 bytes).
        // Validate all metadata before deriving a pointer into the payload buffer.
        if (dex_offset < 0 || dex_size < 28 ||
                static_cast<size_t>(dex_offset) > body_size ||
                static_cast<size_t>(dex_size) > body_size - static_cast<size_t>(dex_offset)) {
            LOGE("Jiagu_Native: Invalid DEX entry %d (offset=%d, size=%d)",
                 i, dex_offset, dex_size);
            return;
        }

        unsigned char* dex_ptr = reinterpret_cast<unsigned char*>(
                full_buffer.data() + body_start + static_cast<size_t>(dex_offset));
        if (!decrypt_aes_gcm(env, dex_ptr, static_cast<size_t>(dex_size), private_key)) {
            LOGE("Jiagu_Native: Failed to decrypt DEX entry %d", i);
            return;
        }

        int plain_size = dex_size - 12 - 16;
        jobject bb = env->CallStaticObjectMethod(
                byte_buffer_class, allocate_direct, static_cast<jint>(plain_size));
        JNI_CHECK_NULL(bb, "DEX buffer allocation failed", );
        void* direct_buffer = env->GetDirectBufferAddress(bb);
        JNI_CHECK_NULL(direct_buffer, "DirectBufferAddress access failed", );
        std::memcpy(direct_buffer, dex_ptr, static_cast<size_t>(plain_size));
        env->SetObjectArrayElement(bb_array, i, bb);
    }

    jclass mem_loader_class = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    JNI_CHECK_NULL(mem_loader_class, "InMemoryDexClassLoader class not found", );
    jmethodID loader_init = env->GetMethodID(
            mem_loader_class, "<init>", "([Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    JNI_CHECK_NULL(loader_init, "InMemoryDexClassLoader.<init> not found", );
    jclass app_class = env->GetObjectClass(thiz);
    JNI_CHECK_NULL(app_class, "ProxyApplication class not found", );
    jmethodID get_cl = env->GetMethodID(app_class, "getClassLoader", "()Ljava/lang/ClassLoader;");
    JNI_CHECK_NULL(get_cl, "getClassLoader method not found", );
    jobject sys_cl = env->CallObjectMethod(thiz, get_cl);
    JNI_CHECK_NULL(sys_cl, "ClassLoader not found", );
    jobject mem_cl = env->NewObject(mem_loader_class, loader_init, bb_array, sys_cl);
    JNI_CHECK_NULL(mem_cl, "InMemoryDexClassLoader instance creation failed", );
    inject_dex_elements(env, sys_cl, mem_cl);
    LOGD("DEX Injection from payload ELF successful");

    // 4. 实例化 Real Application 并替换
    std::string real_app_path = real_app_name;
    for (size_t i = 0; i < real_app_path.length(); ++i) {
        if (real_app_path[i] == '.') real_app_path[i] = '/';
    }
    jclass real_app_class = env->FindClass(real_app_path.c_str());
    if (real_app_class) {
        jmethodID app_init = env->GetMethodID(real_app_class, "<init>", "()V");
        JNI_CHECK_NULL(app_init, "RealApplication.<init> not found", );
        jobject real_app_obj = env->NewObject(real_app_class, app_init);
        JNI_CHECK_NULL(real_app_obj, "RealApplication instance creation failed", );
        gRealApp = env->NewGlobalRef(real_app_obj);

        jclass activity_thread_cls = env->FindClass("android/app/ActivityThread");
        JNI_CHECK_NULL(activity_thread_cls, "ActivityThread class not found", );
        jmethodID current_at_mid = env->GetStaticMethodID(activity_thread_cls, "currentActivityThread", "()Landroid/app/ActivityThread;");
        JNI_CHECK_NULL(current_at_mid, "currentActivityThread method not found", );
        jobject current_at = env->CallStaticObjectMethod(activity_thread_cls, current_at_mid);
        JNI_CHECK_NULL(current_at, "currentActivityThread failed", );

        jfieldID m_bound_app_fid = env->GetFieldID(activity_thread_cls, "mBoundApplication", "Landroid/app/ActivityThread$AppBindData;");
        JNI_CHECK_NULL(m_bound_app_fid, "mBoundApplication field not found", );
        jobject m_bound_app = env->GetObjectField(current_at, m_bound_app_fid);
        JNI_CHECK_NULL(m_bound_app, "mBoundApplication not found", );

        jclass app_bind_data_cls = env->GetObjectClass(m_bound_app);
        JNI_CHECK_NULL(app_bind_data_cls, "AppBindData class not found", );
        jfieldID info_fid = env->GetFieldID(app_bind_data_cls, "info", "Landroid/app/LoadedApk;");
        JNI_CHECK_NULL(info_fid, "LoadedApk info field not found", );
        jobject loaded_apk = env->GetObjectField(m_bound_app, info_fid);
        JNI_CHECK_NULL(loaded_apk, "LoadedApk not found", );

        jclass loaded_apk_cls = env->GetObjectClass(loaded_apk);
        JNI_CHECK_NULL(loaded_apk_cls, "LoadedApk class not found", );
        jfieldID m_app_fid = env->GetFieldID(loaded_apk_cls, "mApplication", "Landroid/app/Application;");
        JNI_CHECK_NULL(m_app_fid, "mApplication field not found in LoadedApk", );
        env->SetObjectField(loaded_apk, m_app_fid, gRealApp);

        jfieldID m_initial_app_fid = env->GetFieldID(activity_thread_cls, "mInitialApplication", "Landroid/app/Application;");
        JNI_CHECK_NULL(m_initial_app_fid, "mInitialApplication field not found", );
        env->SetObjectField(current_at, m_initial_app_fid, gRealApp);

        jfieldID m_all_apps_fid = env->GetFieldID(activity_thread_cls, "mAllApplications", "Ljava/util/ArrayList;");
        JNI_CHECK_NULL(m_all_apps_fid, "mAllApplications field not found", );
        jobject m_all_apps = env->GetObjectField(current_at, m_all_apps_fid);
        JNI_CHECK_NULL(m_all_apps, "mAllApplications not found", );
        jclass list_cls = env->FindClass("java/util/ArrayList");
        JNI_CHECK_NULL(list_cls, "ArrayList class not found", );
        jmethodID remove_mid = env->GetMethodID(list_cls, "remove", "(Ljava/lang/Object;)Z");
        JNI_CHECK_NULL(remove_mid, "ArrayList.remove method not found", );
        jmethodID add_mid = env->GetMethodID(list_cls, "add", "(Ljava/lang/Object;)Z");
        JNI_CHECK_NULL(add_mid, "ArrayList.add method not found", );
        env->CallBooleanMethod(m_all_apps, remove_mid, thiz);
        env->CallBooleanMethod(m_all_apps, add_mid, gRealApp);

        jclass application_cls = env->FindClass("android/app/Application");
        JNI_CHECK_NULL(application_cls, "Application class not found", );
        jmethodID attach_mid = env->GetMethodID(application_cls, "attach", "(Landroid/content/Context;)V");
        JNI_CHECK_NULL(attach_mid, "Application.attach method not found", );
        env->CallVoidMethod(gRealApp, attach_mid, context);
    }

    env->ReleaseStringUTFChars(key_url_j, key_url);
    env->ReleaseStringUTFChars(real_app_name_j, real_app_name);
    env->ReleaseStringUTFChars(pkg_name, pkg_name_str);
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
    if (!clazz) {
        LOGE("Jiagu_Native: ProxyApplication class not found in JNI_OnLoad");
        return JNI_ERR;
    }
    if (env->RegisterNatives(clazz, gMethods, sizeof(gMethods) / sizeof(gMethods[0])) < 0) {
        LOGE("Jiagu_Native: Failed to register natives");
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
