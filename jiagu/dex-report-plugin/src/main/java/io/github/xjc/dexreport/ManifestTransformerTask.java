package io.github.xjc.dexreport;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;

/**
 * 自动篡改 Manifest 任务：
 * 1. 替换 Application 入口。
 * 2. 注入原始 Application 类名到 meta-data。
 */
public abstract class ManifestTransformerTask extends DefaultTask {

    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<Boolean> getAntiDebugEnabled();

    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<Boolean> getSignatureCheckEnabled();

    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getExpectedSignature();

    @InputFile
    public abstract RegularFileProperty getMergedManifest();

    @OutputFile
    public abstract RegularFileProperty getUpdatedManifest();

    @TaskAction
    public void taskAction() throws Exception {
        long startedAt = System.nanoTime();
        File manifestFile = getMergedManifest().get().getAsFile();
        
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(manifestFile);

        NodeList applicationNodes = doc.getElementsByTagName("application");
        if (applicationNodes.getLength() > 0) {
            Element applicationTag = (Element) applicationNodes.item(0);

            // 1. 获取原始 Application 名称
            String originalAppName = applicationTag.getAttribute("android:name");
            if (originalAppName.isEmpty()) {
                originalAppName = "android.app.Application";
            }

            // 2. 替换为壳程序的名称
            applicationTag.setAttribute("android:name", "io.github.xjc.jiagu.ProxyApplication");

            // 3. 移除 android:appComponentFactory
            // 因为业务代码被加密了，系统在启动时无法找到 CoreComponentFactory 会导致 Crash
            if (applicationTag.hasAttribute("android:appComponentFactory")) {
                applicationTag.removeAttribute("android:appComponentFactory");
                getLogger().lifecycle("[Jiagu] 已移除 android:appComponentFactory");
            }

            // 4. 强力清理：移除所有 androidx.startup 和 profileinstaller 的组件
            // 这些组件会在启动时尝试读取 classes.dex，导致在加固环境下报警
            cleanUpComponent(applicationTag, "provider", "androidx.startup.InitializationProvider");
            cleanUpComponent(applicationTag, "receiver", "androidx.profileinstaller.ProfileInstallReceiver");

            // 5. 注入 REAL_APPLICATION 记录
            Element metaData = doc.createElement("meta-data");
            metaData.setAttribute("android:name", "REAL_APPLICATION");
            metaData.setAttribute("android:value", originalAppName);
            applicationTag.appendChild(metaData);

            // 5. 注入防护开关。服务地址、公司和 release 绑定写入 Native 配置 ELF。
            Element antiDebugMetaData = doc.createElement("meta-data");
            antiDebugMetaData.setAttribute("android:name", "ENABLE_ANTI_DEBUG");
            antiDebugMetaData.setAttribute("android:value", String.valueOf(getAntiDebugEnabled().get()));
            applicationTag.appendChild(antiDebugMetaData);

            Element sigCheckMetaData = doc.createElement("meta-data");
            sigCheckMetaData.setAttribute("android:name", "ENABLE_SIGNATURE_CHECK");
            sigCheckMetaData.setAttribute("android:value", String.valueOf(getSignatureCheckEnabled().get()));
            applicationTag.appendChild(sigCheckMetaData);

            if (getExpectedSignature().isPresent() && !getExpectedSignature().get().isEmpty()) {
                Element expectedSigMetaData = doc.createElement("meta-data");
                expectedSigMetaData.setAttribute("android:name", "EXPECTED_SIGNATURE");
                expectedSigMetaData.setAttribute("android:value", getExpectedSignature().get());
                applicationTag.appendChild(expectedSigMetaData);
            }
            
            getLogger().lifecycle("[Jiagu] Manifest 已修改: 入口 -> ProxyApplication, 已启用动态防护功能");
        }

        // 保存文件
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        DOMSource source = new DOMSource(doc);
        StreamResult result = new StreamResult(getUpdatedManifest().get().getAsFile());
        transformer.transform(source, result);
        long millis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedAt);
        getLogger().lifecycle("[Jiagu][计时] Manifest 修改: {}",
                String.format(java.util.Locale.ROOT, "%.3f s", millis / 1000.0d));
    }

    private void cleanUpComponent(Element applicationTag, String tagName, String className) {
        NodeList nodes = applicationTag.getElementsByTagName(tagName);
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            if (className.equals(element.getAttribute("android:name"))) {
                applicationTag.removeChild(element);
                getLogger().lifecycle("[Jiagu] 已从 Manifest 移除组件: " + className);
                cleanUpComponent(applicationTag, tagName, className);
                return;
            }
        }
    }
}
