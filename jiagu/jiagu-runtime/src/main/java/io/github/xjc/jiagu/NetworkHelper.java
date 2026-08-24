package io.github.xjc.jiagu;

import android.os.StrictMode;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class NetworkHelper {
    private static final String TAG = "Jiagu_Network";
    private static final String KEY_ALIAS = "jiagu_master_key";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    public static String fetchKey(String urlString, String jsonKey, int versionCode) {
        // 允许启动阶段的网络操作
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().permitAll().build());
        
        // 1. 获取注入的 KeyUrl (此处由 C++ 传入)
        Log.d(TAG, "Dynamic Key Request -> Version: " + versionCode);

        // 实际逻辑由 C++ 调用此方法，C++ 会先检查缓存。
        // 为了支持过期逻辑，我们将原本在 C++ 的缓存检查逻辑也移入 Java，以利用 KeyStore 的便利性。
        return null; 
    }

    /**
     * 核心逻辑：获取私钥（带缓存加密与过期检查）
     */
    public static String getSecureKey(android.content.Context context, String urlString, String jsonKey, int versionCode) {
        // 缓存文件名现在同时依赖于版本号和 JSON 节点名，确保配置变更时缓存自动失效
        String cacheName = String.format(".jiagu_v%d_%d.enc", versionCode, jsonKey.hashCode());
        File cacheFile = new File(context.getFilesDir(), cacheName);
        
        // 1. 尝试从缓存读取
        if (cacheFile.exists()) {
            String cachedKey = readAndDecryptCache(cacheFile);
            if (cachedKey != null) {
                Log.d(TAG, "Secure cache hit for version " + versionCode + " with key " + jsonKey);
                return cachedKey;
            }
            Log.w(TAG, "Cache invalid or expired. Re-fetching...");
        }

        // 2. 网络拉取
        String rawKey = performNetworkFetch(urlString, jsonKey, versionCode);
        if (rawKey != null) {
            // 3. 加密并存入缓存
            encryptAndSaveCache(cacheFile, rawKey);
            return rawKey;
        }

        return null;
    }

    private static String readAndDecryptCache(File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            fis.close();

            ByteBuffer bb = ByteBuffer.wrap(data);
            long expiry = bb.getLong(); // 读取过期时间
            if (System.currentTimeMillis() > expiry) {
                Log.w(TAG, "Key has expired!");
                return null;
            }

            byte[] iv = new byte[12];
            bb.get(iv);
            byte[] cipherText = new byte[bb.remaining()];
            bb.get(cipherText);

            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);
            SecretKey key = (SecretKey) ks.getKey(KEY_ALIAS, null);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] plainText = cipher.doFinal(cipherText);
            
            return new String(plainText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Decrypt cache failed: " + e.getMessage());
            return null;
        }
    }

    private static void encryptAndSaveCache(File file, String rawKey) {
        try {
            // 获取/生成硬件密钥
            SecretKey key = getOrGenerateMasterKey();
            
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] cipherText = cipher.doFinal(rawKey.getBytes(StandardCharsets.UTF_8));

            // 设置过期时间 (默认 2 天)
            long expiry = System.currentTimeMillis() + (2L * 24 * 60 * 60 * 1000);

            FileOutputStream fos = new FileOutputStream(file);
            ByteBuffer bb = ByteBuffer.allocate(8 + 12 + cipherText.length);
            bb.putLong(expiry);
            bb.put(iv);
            bb.put(cipherText);
            fos.write(bb.array());
            fos.close();
            Log.d(TAG, "Key secured with Hardware KeyStore and cached.");
        } catch (Exception e) {
            Log.e(TAG, "Encrypt cache failed: " + e.getMessage());
        }
    }

    private static SecretKey getOrGenerateMasterKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return (SecretKey) ks.getKey(KEY_ALIAS, null);
        }

        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return kg.generateKey();
    }

    private static String performNetworkFetch(String urlString, String jsonKey, int versionCode) {
        // 允许启动阶段在主线程进行网络操作（仅加固壳初始化需要）
        StrictMode.ThreadPolicy oldPolicy = StrictMode.getThreadPolicy();
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().permitAll().build());
        
        try {
            URL url = new URL(urlString);
            Log.d(TAG, "Fetching key from: " + urlString + " (Host: " + url.getHost() + ")");
            // 尝试解析 IP，确认为 DNS 是否正常
            try {
                java.net.InetAddress addr = java.net.InetAddress.getByName(url.getHost());
                Log.d(TAG, "Resolved IP: " + addr.getHostAddress());
            } catch (Exception e) {
                Log.w(TAG, "DNS resolution failed: " + e.getMessage());
            }

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile) JiaguClient/1.0");
            conn.setRequestProperty("Host", url.getHost()); // 显式设置 Host 头部，辅助 SNI
            conn.setRequestProperty("Connection", "close");
            conn.setInstanceFollowRedirects(true);
            
            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                java.io.InputStream is = conn.getInputStream();
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
                String jsonStr = new String(bos.toByteArray(), StandardCharsets.UTF_8);

                org.json.JSONObject root = new org.json.JSONObject(jsonStr);

                // 1. 获取指定的业务组节点
                if (!root.has(jsonKey)) {
                    Log.e(TAG, "CRITICAL: Root key '" + jsonKey + "' not found in JSON response.");
                    return null;
                }
                org.json.JSONObject group = root.getJSONObject(jsonKey);

                // 2. 获取版本节点
                String vStr = String.valueOf(versionCode);
                if (!group.has(vStr)) {
                    Log.e(TAG, "CRITICAL: Version '" + vStr + "' not found under key '" + jsonKey + "'.");
                    return null;
                }
                org.json.JSONObject versionBlock = group.getJSONObject(vStr);

                // 3. 提取字段并返回
                String salt = versionBlock.getString("salt");
                String nonce = versionBlock.getString("nonce");
                String bksBlob = versionBlock.getString("bksBlob");
                
                if (salt != null && nonce != null && bksBlob != null) {
                    Log.d(TAG, "Successfully fetched key for version " + versionCode);
                    return salt + "|" + nonce + "|" + bksBlob;
                }
            } else {
                Log.e(TAG, "Server returned error code: " + responseCode);
            }
        } catch (Exception e) {
            Log.e(TAG, "Network fetch error", e);
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }
        return null;
    }
}
