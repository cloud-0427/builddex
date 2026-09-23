package io.github.xjc.dexreport;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Routing policy shared by the class splitter and its tests. */
final class PayloadRouting {
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*\\.\\*{1,2}");
    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");

    enum Destination { SHELL, PAYLOAD }

    static final class Decision {
        final Destination destination;
        final String reason;
        final boolean startupAllowlistConflict;

        Decision(Destination destination, String reason, boolean startupAllowlistConflict) {
            this.destination = destination;
            this.reason = reason;
            this.startupAllowlistConflict = startupAllowlistConflict;
        }
    }

    private PayloadRouting() {}

    static void validate(String mode, Set<String> includes, String startupPolicy, String r8Policy) {
        if (!"legacy".equals(mode) && !"allowlist".equals(mode)) {
            throw new IllegalArgumentException("payloadSelectionMode must be 'legacy' or 'allowlist'");
        }
        if ("allowlist".equals(mode) && includes.isEmpty()) {
            throw new IllegalArgumentException(
                    "payloadIncludePackages must not be empty when payloadSelectionMode='allowlist'");
        }
        validatePackages(includes, "payloadIncludePackages");
        if (!"off".equals(startupPolicy) && !"validate".equals(startupPolicy)
                && !"fail".equals(startupPolicy)) {
            throw new IllegalArgumentException("startupComponentPolicy must be 'off', 'validate', or 'fail'");
        }
        if (!"fail".equals(r8Policy)) {
            throw new IllegalArgumentException("payloadR8Policy='" + r8Policy
                    + "' is not implemented; use 'fail'");
        }
    }

    static void validatePackages(Set<String> patterns, String property) {
        for (String pattern : patterns) {
            if (pattern == null || !PACKAGE_PATTERN.matcher(pattern.trim()).matches()) {
                throw new IllegalArgumentException(property + " contains invalid package pattern: " + pattern
                        + "; expected e.g. com.example.**");
            }
        }
    }

    static void validateClasses(Set<String> classes, String property) {
        for (String className : classes) {
            if (className == null || !CLASS_PATTERN.matcher(className.trim()).matches()) {
                throw new IllegalArgumentException(property + " contains invalid class name: " + className);
            }
        }
    }

    static Decision route(String entryName, String mode, Set<String> includes,
                          Set<String> shellPackages, Set<String> shellClasses,
                          Set<String> startupClasses) {
        if (!entryName.endsWith(".class")) return new Decision(Destination.SHELL, "non-program", false);
        String className = entryName.substring(0, entryName.length() - ".class".length()).replace('/', '.');
        boolean includeMatch = matchesAnyPackage(className, includes);
        if (isFixedShell(entryName)) return new Decision(Destination.SHELL, "fixed-shell", false);
        if (startupClasses.contains(className)) {
            return new Decision(Destination.SHELL, "manifest-startup-component", includeMatch);
        }
        if (shellClasses.contains(className)) return new Decision(Destination.SHELL, "shellKeepClasses", false);
        if (matchesAnyPackage(className, shellPackages)) {
            return new Decision(Destination.SHELL, "shellKeepPackages", false);
        }
        if ("allowlist".equals(mode)) {
            return includeMatch ? new Decision(Destination.PAYLOAD, "payloadIncludePackages", false)
                    : new Decision(Destination.SHELL, "not-in-payload-allowlist", false);
        }
        return new Decision(Destination.PAYLOAD, "legacy-default", false);
    }

    static boolean matchesAnyPackage(String className, Set<String> patterns) {
        for (String pattern : patterns) {
            String prefix = pattern.trim().replaceAll("\\.\\*{1,2}$", "") + ".";
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    static Set<String> startupClasses(File manifest, String fallbackPackage) throws Exception {
        if (manifest == null || !manifest.isFile()) return Collections.emptySet();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(manifest);
        String packageName = document.getDocumentElement().getAttribute("package");
        if (packageName == null || packageName.isEmpty()) packageName = fallbackPackage;
        Set<String> classes = new LinkedHashSet<>();
        NodeList apps = document.getElementsByTagName("application");
        if (apps.getLength() == 0) return classes;
        Element application = (Element) apps.item(0);
        addClass(classes, application.getAttribute("android:appComponentFactory"), packageName);
        addClass(classes, application.getAttribute("android:backupAgent"), packageName);
        NodeList providers = application.getElementsByTagName("provider");
        for (int i = 0; i < providers.getLength(); i++) {
            Element provider = (Element) providers.item(i);
            addClass(classes, provider.getAttribute("android:name"), packageName);
            NodeList children = provider.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if (child instanceof Element && "meta-data".equals(((Element) child).getTagName())) {
                    addClass(classes, ((Element) child).getAttribute("android:value"), packageName);
                }
            }
        }
        return classes;
    }

    private static void addClass(Set<String> target, String rawName, String packageName) {
        if (rawName == null || rawName.isEmpty() || rawName.startsWith("@") || rawName.startsWith("${")) return;
        String name = rawName.startsWith(".") ? packageName + rawName
                : rawName.indexOf('.') < 0 ? packageName + "." + rawName : rawName;
        if (CLASS_PATTERN.matcher(name).matches()) target.add(name);
    }

    private static boolean isFixedShell(String entryName) {
        return entryName.startsWith("io/github/xjc/jiagu/")
                || entryName.startsWith("com/google/crypto/tink/")
                || entryName.startsWith("com/google/android/play/")
                || entryName.startsWith("com/google/android/gms/")
                || entryName.startsWith("androidx/collection/")
                || entryName.startsWith("okhttp3/") || entryName.startsWith("okio/")
                || entryName.startsWith("org/conscrypt/") || entryName.startsWith("kotlin/")
                || entryName.startsWith("androidx/startup/")
                || entryName.startsWith("org/jetbrains/annotations/")
                || entryName.startsWith("org/jspecify/annotations/")
                || entryName.contains("/R$") || entryName.endsWith("/R.class");
    }
}
