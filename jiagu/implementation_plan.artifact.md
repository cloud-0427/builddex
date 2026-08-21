# Fix ClassNotFoundException for MainActivity in Jiagu Application

The application fails to start with a `ClassNotFoundException` for `MainActivity`. This is because the `JiaguTask` in the Gradle plugin only packages the first DEX file (`classes.dex`) produced by D8 into the encrypted `jiagu_data.bin` asset. Since the business code (including dependencies like `appcompat` and `material`) likely exceeds the 65k method limit, D8 produces multiple DEX files, and `MainActivity` might be located in one of the secondary DEX files which are currently discarded.

## Proposed Changes

We will modify both the Gradle plugin (`JiaguTask`) and the native runtime (`jiagu-core.cpp`) to support multiple DEX files.

### [dex-report-plugin]

#### [MODIFY] [JiaguTask.java](file:///D:/xjc_git/builddex/jiagu/dex-report-plugin/src/main/java/io/github/xjc/dexreport/JiaguTask.java)
- Update the packaging logic to include ALL generated `.dex` files.
- The new format for `jiagu_data.bin` will be:
  - `int` (4 bytes, Big-Endian): Number of DEX files.
  - For each DEX:
    - `int` (4 bytes, Big-Endian): Size of the encrypted DEX.
    - `byte[]`: The encrypted DEX data.

### [jiagu-runtime]

#### [MODIFY] [jiagu-core.cpp](file:///D:/xjc_git/builddex/jiagu/jiagu-runtime/src/main/cpp/jiagu-core.cpp)
- Add a helper function to read Big-Endian integers.
- Update `native_init` to read the new `jiagu_data.bin` format.
- Create an array of `ByteBuffer`s to hold all decrypted DEX files.
- Use the `InMemoryDexClassLoader` constructor that accepts an array of `ByteBuffer`s.

## Verification Plan

### Automated Tests
- Run `./gradlew :app:assembleDebug` to ensure the build passes and the plugin correctly packages multiple DEX files.

### Manual Verification
- Deploy the application to an emulator or device.
- Verify that the app starts successfully without a `ClassNotFoundException`.
- Check Logcat for "Jiagu_Native: DEX injection successful" and "Jiagu_Proxy" logs.
