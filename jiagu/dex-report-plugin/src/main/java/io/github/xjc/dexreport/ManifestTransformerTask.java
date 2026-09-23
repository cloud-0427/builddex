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
@org.gradle.work.DisableCachingByDefault(because = "Transforms an AGP intermediate manifest in the variant pipeline")
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

    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getStartupLogUploaderClass();

    @InputFile
    @org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.NONE)
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

            // Preserve appComponentFactory and third-party Providers. Their classes are routed to
            // the shell by JiaguTask, so deleting their manifest declarations is no longer needed
            // and would change normal AndroidX/SDK startup behaviour.

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

            if (getStartupLogUploaderClass().isPresent()
                    && !getStartupLogUploaderClass().get().trim().isEmpty()) {
                Element uploaderMetaData = doc.createElement("meta-data");
                uploaderMetaData.setAttribute("android:name",
                        "io.github.xjc.jiagu.STARTUP_LOG_UPLOADER");
                uploaderMetaData.setAttribute("android:value",
                        getStartupLogUploaderClass().get().trim());
                applicationTag.appendChild(uploaderMetaData);
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

}
