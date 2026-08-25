# Implementation Plan - Dynamic Protections (Anti-Debug, Hook Detection, Signature Verification)

Add dynamic protection features to the Jiagu runtime to enhance security against debugging, hooking, and unauthorized re-packaging.

## Proposed Changes

### [jiagu-runtime]

#### [MODIFY] [jiagu-core.cpp](file:///D:/xjc_git/builddex/jiagu/jiagu-runtime/src/main/cpp/jiagu-core.cpp)
- Add `#include <sys/ptrace.h>`, `<unistd.h>`, `<fcntl.h>`, and `<pthread.h>`.
- Implement `die_if_debugged()`:
    - Performs `ptrace(PTRACE_TRACEME, ...)` check.
    - Scans `/proc/self/status` for `TracerPid`.
- Implement `die_if_hooked()`:
    - Scans `/proc/self/maps` for suspicious strings like "frida", "xposed", "libdobby", etc.
- Implement `verify_signature(JNIEnv* env, jobject context, const char* expected_hash)`:
    - Uses JNI to retrieve the first signature of the APK.
    - Computes the SHA-256 hash of the signature.
    - Compares it with the `expected_hash`.
- Update `native_attach`:
    - Read `ENABLE_ANTI_DEBUG`, `ENABLE_SIGNATURE_CHECK`, and `EXPECTED_SIGNATURE` from meta-data.
    - Execute the checks based on these flags (defaulting to enabled).

### [dex-report-plugin]

#### [MODIFY] [ManifestTransformerTask.java](file:///D:/xjc_git/builddex/jiagu/dex-report-plugin/src/main/java/io/github/xjc/dexreport/ManifestTransformerTask.java)
- Inject default meta-data values for `ENABLE_ANTI_DEBUG` ("true") and `ENABLE_SIGNATURE_CHECK` ("true").
- (Optional) Provide a way for users to configure the `EXPECTED_SIGNATURE`.

## Verification Plan

### Automated Tests
- Build the project using `./gradlew :app:assembleDebug`.
- Check if the modified `AndroidManifest.xml` in the build directory contains the new meta-data.

### Manual Verification
- **Anti-Debug**: Attempt to attach a debugger (e.g., via Android Studio or `lldb`) and verify the app exits.
- **Hook Detection**: Attempt to run the app on a device with Frida or Xposed installed/active and verify it exits.
- **Signature Verification**:
    - Test with a correct signature hash in meta-data.
    - Test with an incorrect signature hash and verify the app exits.
