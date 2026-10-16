package io.github.xjc.dexreport;

import org.objectweb.asm.*;

/** Generates only constants; no application classes are loaded in the build JVM. */
final class LocalTelemetryBytecode implements Opcodes {
    static byte[] generate(boolean enabled, String pkg, long version, String versionName, String buildId) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V11, ACC_FINAL | ACC_SUPER, "io/github/xjc/jiagu/local/LocalBuildInfo", null, "java/lang/Object", null);
        constant(writer, "enabled", "()Z", enabled ? 1 : 0, IRETURN, 1);
        constant(writer, "packageName", "()Ljava/lang/String;", pkg, ARETURN, 1);
        constant(writer, "versionCode", "()J", version, LRETURN, 2);
        constant(writer, "versionName", "()Ljava/lang/String;", versionName, ARETURN, 1);
        constant(writer, "buildId", "()Ljava/lang/String;", buildId, ARETURN, 1);
        writer.visitEnd();
        return writer.toByteArray();
    }
    private static void constant(ClassWriter writer, String name, String desc, Object value, int opcode, int stack) {
        MethodVisitor method = writer.visitMethod(ACC_STATIC, name, desc, null, null);
        method.visitCode(); method.visitLdcInsn(value); method.visitInsn(opcode); method.visitMaxs(stack, 0); method.visitEnd();
    }
}
