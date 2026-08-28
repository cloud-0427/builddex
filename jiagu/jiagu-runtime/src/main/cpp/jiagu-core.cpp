#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <cstring>
#include <cstdint>
#include <dlfcn.h>
#include <unistd.h>
#include <fcntl.h>
#include <pthread.h>
#include <algorithm>
#include <zlib.h>
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
    // PTRACE_TRACEME must not be used here. A tracee cannot detach itself, so
    // combining TRACEME with the TracerPid check below makes the app detect
    // the tracing relationship that it just created and terminate itself.
    // Read-only TracerPid inspection does not mutate process state.
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
                    LOGE("[Jiagu][AntiDebug] blocked: native tracer detected (TracerPid=%d)",
                         tracer_pid);
                    _exit(0);
                }
            }
        }
    }
    LOGD("[Jiagu][AntiDebug] debugger check passed");
}

static __attribute__((always_inline)) inline void die_if_hooked() {
    // 使用 raw syscall 读取 maps
    int fd = raw_syscall_open(X("/proc/self/maps"), O_RDONLY, 0);
    if (fd < 0) return;

    char buf[4096];
    ssize_t len;
    while ((len = raw_syscall(SYS_read, fd, (long)buf, sizeof(buf) - 1)) > 0) {
        buf[len] = 0;
        const char* detected = nullptr;
        if (strstr(buf, X("frida"))) detected = X("frida");
        else if (strstr(buf, X("xposed"))) detected = X("xposed");
        else if (strstr(buf, X("libdobby"))) detected = X("libdobby");
        else if (strstr(buf, X("substitute"))) detected = X("substitute");
        else if (strstr(buf, X("substrate"))) detected = X("substrate");
        else if (strstr(buf, X("com.saurik.substrate"))) detected = X("com.saurik.substrate");
        if (detected) {
            raw_syscall(SYS_close, fd);
            LOGE("[Jiagu][AntiDebug] blocked: hook framework mapping detected (%s)", detected);
            _exit(0);
        }
    }
    raw_syscall(SYS_close, fd);
    LOGD("[Jiagu][AntiDebug] hook check passed");
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

    jstring real_app_name_j = (jstring)env->CallObjectMethod(meta_data, get_string, env->NewStringUTF(X("REAL_APPLICATION")));

    // 读取防护配置
    jmethodID get_bool = env->GetMethodID(bundle_class, "getBoolean", "(Ljava/lang/String;Z)Z");
    bool anti_debug = env->CallBooleanMethod(meta_data, get_bool, env->NewStringUTF(X("ENABLE_ANTI_DEBUG")), true);
    bool sig_check = env->CallBooleanMethod(meta_data, get_bool, env->NewStringUTF(X("ENABLE_SIGNATURE_CHECK")), true);
    jstring expected_sig_j = (jstring)env->CallObjectMethod(meta_data, get_string, env->NewStringUTF(X("EXPECTED_SIGNATURE")));

    LOGD("[Jiagu] Protection config: antiDebug=%s, signatureCheck=%s",
         anti_debug ? "enabled" : "disabled", sig_check ? "enabled" : "disabled");
    if (anti_debug) {
        die_if_debugged();
        die_if_hooked();
    }

    if (sig_check && expected_sig_j) {
        const char* expected_sig = env->GetStringUTFChars(expected_sig_j, nullptr);
        if (!verify_signature(env, context, expected_sig)) {
            LOGE("[Jiagu][Signature] blocked: APK signing certificate verification failed");
            _exit(0);
        }
        env->ReleaseStringUTFChars(expected_sig_j, expected_sig);
        LOGD("[Jiagu][Signature] APK signing certificate check passed");
    }

    const char *real_app_name = env->GetStringUTFChars(real_app_name_j, nullptr);
    const char *pkg_name_str = env->GetStringUTFChars(pkg_name, nullptr);

    // 2. 从只读 ELF 段读取构建期固定的 RuntimeConfig。
    using payload_address_fn = const uint8_t* (*)();
    using payload_size_fn = size_t (*)();

    void* payload_handle = dlopen("liblog_ext.so", RTLD_NOW | RTLD_LOCAL);
    if (!payload_handle) {
        LOGE("Jiagu_Native: Failed to load RuntimeConfig ELF: %s", dlerror());
        env->ReleaseStringUTFChars(real_app_name_j, real_app_name);
        env->ReleaseStringUTFChars(pkg_name, pkg_name_str);
        return;
    }

    auto payload_address = reinterpret_cast<payload_address_fn>(
            dlsym(payload_handle, "jg_payload_address"));
    auto payload_size = reinterpret_cast<payload_size_fn>(
            dlsym(payload_handle, "jg_payload_size"));
    if (!payload_address || !payload_size) {
        LOGE("Jiagu_Native: RuntimeConfig ELF exports are missing: %s", dlerror());
        dlclose(payload_handle);
        return;
    }

    const uint8_t* bundle_source = payload_address();
    size_t bundle_length = payload_size();
    if (!bundle_source || bundle_length < 56 || bundle_length > 129 * 1024 * 1024 ||
            std::memcmp(bundle_source, "JGRC", 4) != 0) {
		LOGE("Jiagu_Native: Runtime bundle ELF returned invalid data");
        dlclose(payload_handle);
        return;
    }
    auto read_u32 = [bundle_source](size_t offset) -> uint32_t {
        return (static_cast<uint32_t>(bundle_source[offset]) << 24) |
               (static_cast<uint32_t>(bundle_source[offset + 1]) << 16) |
               (static_cast<uint32_t>(bundle_source[offset + 2]) << 8) |
               static_cast<uint32_t>(bundle_source[offset + 3]);
    };
    uint32_t bundle_version = read_u32(4);
    uint32_t config_length = read_u32(8);
    uint32_t local_payload_length = read_u32(12);
    if (bundle_version != 1 || config_length < 32 || config_length > 256 * 1024 ||
            local_payload_length < 40 || local_payload_length > 128 * 1024 * 1024 ||
            16ULL + config_length + local_payload_length != bundle_length) {
        LOGE("Jiagu_Native: Runtime bundle header is invalid");
        dlclose(payload_handle);
        return;
    }
    std::string runtime_config(reinterpret_cast<const char*>(bundle_source + 16), config_length);
    std::vector<uint8_t> local_payload(bundle_source + 16 + config_length,
                                       bundle_source + bundle_length);
    dlclose(payload_handle);

    // 3. Java 层完成 Keystore、Integrity、Credential/Grant 验证、设备 Key 解封和 JGPD 解密。
    jclass network_helper = env->FindClass("io/github/xjc/jiagu/NetworkHelper");
    JNI_CHECK_NULL(network_helper, "NetworkHelper class not found", );
    jmethodID get_payload_mid = env->GetStaticMethodID(
            network_helper, "getAuthorizedPayload", "(Landroid/content/Context;Ljava/lang/String;[B)[B");
    JNI_CHECK_NULL(get_payload_mid, "getAuthorizedPayload method not found", );
    jstring runtime_config_j = env->NewStringUTF(runtime_config.c_str());
    jbyteArray local_payload_j = env->NewByteArray(static_cast<jsize>(local_payload.size()));
    JNI_CHECK_NULL(local_payload_j, "Failed to allocate local payload array", );
    env->SetByteArrayRegion(local_payload_j, 0, static_cast<jsize>(local_payload.size()),
                            reinterpret_cast<const jbyte*>(local_payload.data()));
    jbyteArray payload_array = (jbyteArray)env->CallStaticObjectMethod(
            network_helper, get_payload_mid, context, runtime_config_j, local_payload_j);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    JNI_CHECK_NULL(payload_array, "device authorization returned no payload", );
    jsize payload_length = env->GetArrayLength(payload_array);
    if (payload_length < 8 || payload_length > 128 * 1024 * 1024) {
        LOGE("Jiagu_Native: Invalid authorized payload size: %d", payload_length);
        return;
    }
    std::vector<char> full_buffer(static_cast<size_t>(payload_length));
    env->GetByteArrayRegion(payload_array, 0, payload_length,
                            reinterpret_cast<jbyte*>(full_buffer.data()));

    if (std::memcmp(full_buffer.data(), "JG3\0", 4) != 0) {
        LOGE("Jiagu_Native: Invalid payload magic");
        return;
    }

    size_t offset = 4;
    int dex_count = read_int_be(full_buffer.data(), offset);
    if (dex_count <= 0 || dex_count > 128 ||
            offset + static_cast<size_t>(dex_count) * 12 > full_buffer.size()) {
        LOGE("Jiagu_Native: Invalid DEX count: %d", dex_count);
        return;
    }

    unsigned char* meta_data_ptr = reinterpret_cast<unsigned char*>(full_buffer.data());
    size_t meta_offset = offset;
    size_t body_start = offset + static_cast<size_t>(dex_count) * 12;
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
        int dex_plain_size = (static_cast<unsigned char>(meta_data_ptr[meta_offset]) << 24) |
                             (static_cast<unsigned char>(meta_data_ptr[meta_offset + 1]) << 16) |
                             (static_cast<unsigned char>(meta_data_ptr[meta_offset + 2]) << 8) |
                             static_cast<unsigned char>(meta_data_ptr[meta_offset + 3]);
        meta_offset += 4;

        // JG3 stores zlib-compressed DEX entries after the metadata table.
        if (dex_offset < 0 || dex_size <= 0 || dex_plain_size <= 0 ||
                dex_plain_size > 256 * 1024 * 1024 ||
                static_cast<size_t>(dex_offset) > body_size ||
                static_cast<size_t>(dex_size) > body_size - static_cast<size_t>(dex_offset)) {
            LOGE("Jiagu_Native: Invalid DEX entry %d (offset=%d, size=%d, plain=%d)",
                 i, dex_offset, dex_size, dex_plain_size);
            return;
        }

        const unsigned char* dex_ptr = reinterpret_cast<const unsigned char*>(
                full_buffer.data() + body_start + static_cast<size_t>(dex_offset));
        jobject bb = env->CallStaticObjectMethod(
                byte_buffer_class, allocate_direct, static_cast<jint>(dex_plain_size));
        JNI_CHECK_NULL(bb, "DEX buffer allocation failed", );
        void* direct_buffer = env->GetDirectBufferAddress(bb);
        JNI_CHECK_NULL(direct_buffer, "DirectBufferAddress access failed", );
        uLongf inflated_size = static_cast<uLongf>(dex_plain_size);
        int inflate_result = uncompress(
                 reinterpret_cast<Bytef*>(direct_buffer), &inflated_size,
                 reinterpret_cast<const Bytef*>(dex_ptr),
                 static_cast<uLong>(dex_size));
        if (inflate_result != Z_OK || inflated_size != static_cast<uLongf>(dex_plain_size)) {
            LOGE("Jiagu_Native: Failed to decompress DEX entry %d (zlib=%d, actual=%lu, expected=%d)",
                 i, inflate_result, static_cast<unsigned long>(inflated_size), dex_plain_size);
            return;
        }
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
