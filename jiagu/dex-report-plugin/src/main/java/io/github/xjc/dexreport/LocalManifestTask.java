package io.github.xjc.dexreport;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.work.DisableCachingByDefault;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

@DisableCachingByDefault(because = "Produces a variant manifest")
public abstract class LocalManifestTask extends DefaultTask {
    private static final String NS = "http://schemas.android.com/apk/res/android";
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getInputManifest();
    @OutputFile public abstract RegularFileProperty getOutputManifest();
    @Input public abstract Property<String> getPackageName();
    @Input public abstract Property<Boolean> getTelemetryEnabled();
    @Input public abstract Property<String> getUploaderClass();

    @TaskAction public void transform() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document d = f.newDocumentBuilder().parse(getInputManifest().get().getAsFile());
        Element root = d.getDocumentElement();
        if (root.hasAttributeNS(NS, "sharedUserId") || "true".equals(root.getAttributeNS(NS, "isolatedSplits")))
            throw new IllegalStateException("Local MVP does not support sharedUserId/isolatedSplits");
        Element app = (Element) d.getElementsByTagName("application").item(0);
        if (app == null) throw new IllegalStateException("Missing application");
        if (app.hasAttributeNS(NS, "classLoader")) throw new IllegalStateException("Custom android:classLoader unsupported");
        validateSpecialEntrypoints(app, getPackageName().get());
        String original = app.getAttributeNS(NS, "name");
        if (original.contains("ProxyApplication")) throw new IllegalStateException("Already protected manifest");
        if (!original.isEmpty()) app.setAttributeNS(NS, "android:name", resolve(original, getPackageName().get()));
        String factory = app.getAttributeNS(NS, "appComponentFactory");
        boolean androidx = "androidx.core.app.CoreComponentFactory".equals(factory);
        if (!factory.isEmpty() && !"android.app.AppComponentFactory".equals(factory) && !androidx)
            throw new IllegalStateException("LOCAL_FACTORY_UNSUPPORTED: " + factory);
        app.setAttributeNS(NS, "android:appComponentFactory", "io.github.xjc.jiagu.local." +
                (androidx ? "LocalAndroidXComponentFactory" : "LocalComponentFactory"));
        if (getTelemetryEnabled().get()) {
            String authority = getPackageName().get() + ".jiagu.local.events";
            NodeList providers = app.getElementsByTagName("provider");
            for (int i = 0; i < providers.getLength(); i++) {
                for (String value : ((Element) providers.item(i)).getAttributeNS(NS, "authorities").split(";"))
                    if (authority.equals(value)) throw new IllegalStateException("Event provider authority collision");
            }
            Element p = d.createElement("provider");
            p.setAttributeNS(NS, "android:name", "io.github.xjc.jiagu.local.LocalEventInitializer");
            p.setAttributeNS(NS, "android:authorities", authority);
            p.setAttributeNS(NS, "android:exported", "false");
            p.setAttributeNS(NS, "android:initOrder", "1000"); app.appendChild(p);
            Element m = d.createElement("meta-data");
            m.setAttributeNS(NS, "android:name", "io.github.xjc.jiagu.local.UPLOADER");
            m.setAttributeNS(NS, "android:value", getUploaderClass().get()); app.appendChild(m);
        }
        java.nio.file.Files.createDirectories(getOutputManifest().get().getAsFile().toPath().getParent());
        TransformerFactory.newInstance().newTransformer().transform(new DOMSource(d), new StreamResult(getOutputManifest().get().getAsFile()));
    }
    private static String resolve(String name, String pkg) {
        return name.startsWith(".") ? pkg + name : name.contains(".") ? name : pkg + "." + name;
    }
    static void validateSpecialEntrypoints(Element app, String pkg) {
        String backup = app.getAttributeNS(NS, "backupAgent").trim();
        if (!backup.isEmpty()) throw new IllegalStateException("LOCAL_SPECIAL_ENTRY_UNSUPPORTED: android:backupAgent="
                + resolve(backup, pkg) + "; backup/restore ClassLoader lifecycle is not validated in local MVP");
        if ("true".equals(app.getAttributeNS(NS, "cantSaveState")))
            throw new IllegalStateException("LOCAL_SPECIAL_ENTRY_UNSUPPORTED: android:cantSaveState=true (heavy-weight application)");
    }
}
