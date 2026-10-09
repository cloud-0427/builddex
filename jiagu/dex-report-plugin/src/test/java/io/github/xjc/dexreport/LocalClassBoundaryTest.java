package io.github.xjc.dexreport;

import org.junit.Test;
import org.objectweb.asm.*;
import java.io.*;
import java.util.jar.*;
import static org.junit.Assert.*;

public class LocalClassBoundaryTest {
    private File jar(String name, String parent, int access) throws Exception {
        File f = File.createTempFile("boundary", ".jar"); f.deleteOnExit();
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(f))) {
            if (name != null) {
                ClassWriter w = new ClassWriter(0); w.visit(Opcodes.V11, access, name, null, parent, null); w.visitEnd();
                out.putNextEntry(new JarEntry(name + ".class")); out.write(w.toByteArray()); out.closeEntry();
            }
        }
        return f;
    }
    @Test public void parentCannotExtendEncryptedClass() throws Exception {
        File shell = jar("sdk/Provider", "business/Base", Opcodes.ACC_PUBLIC);
        File child = jar("business/Base", "java/lang/Object", Opcodes.ACC_PUBLIC);
        IOException e = assertThrows(IOException.class, () -> LocalClassBoundary.verify(shell, jar(null, null, 0), child, null));
        assertTrue(e.getMessage().contains("LOCAL_SHELL_TO_PAYLOAD_REFERENCE"));
    }
    @Test public void childCanExtendPublicSdkButNotPackagePrivateSdk() throws Exception {
        File child = jar("sdk/Business", "sdk/Base", Opcodes.ACC_PUBLIC);
        LocalClassBoundary.verify(jar("sdk/Base", "java/lang/Object", Opcodes.ACC_PUBLIC), jar(null, null, 0), child, null);
        assertThrows(IOException.class, () -> LocalClassBoundary.verify(jar("sdk/Base", "java/lang/Object", 0), jar(null, null, 0), child, null));
    }
    private File bytes(byte[] code) throws Exception {
        File file = File.createTempFile("boundary-members", ".jar"); file.deleteOnExit();
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(file))) {
            out.putNextEntry(new JarEntry(new ClassReader(code).getClassName() + ".class"));
            out.write(code); out.closeEntry();
        }
        return file;
    }
    private File base(int flags, boolean field) throws Exception {
        ClassWriter w = new ClassWriter(0); w.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "sdk/Base", null, "java/lang/Object", null);
        if (field) w.visitField(flags, "value", "Ljava/lang/Object;", null, null).visitEnd();
        else {
            MethodVisitor m = w.visitMethod(flags, "call", "()V", null, null);
            m.visitCode(); m.visitInsn(Opcodes.RETURN); m.visitMaxs(0, 1); m.visitEnd();
        }
        w.visitEnd(); return bytes(w.toByteArray());
    }
    private File caller(String owner, boolean field, boolean baseReceiver) throws Exception {
        ClassWriter w = new ClassWriter(0); w.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "sdk/Child", null, "sdk/Base", null);
        MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, "test", "(Lsdk/Base;)V", null, null);
        m.visitCode(); m.visitVarInsn(Opcodes.ALOAD, baseReceiver ? 1 : 0);
        if (field) { m.visitFieldInsn(Opcodes.GETFIELD, owner, "value", "Ljava/lang/Object;"); m.visitInsn(Opcodes.POP); }
        else m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "call", "()V", false);
        m.visitInsn(Opcodes.RETURN); m.visitMaxs(1, 2); m.visitEnd(); w.visitEnd();
        return bytes(w.toByteArray());
    }
    private void verifyMember(int flags, boolean field, String owner, boolean receiver) throws Exception {
        LocalClassBoundary.verify(base(flags, field), jar(null, null, 0), caller(owner, field, receiver), null);
    }
    @Test public void inheritedPackagePrivateMethodCannotHideBehindPayloadOwner() throws Exception {
        IOException error = assertThrows(IOException.class, () -> verifyMember(0, false, "sdk/Child", false));
        assertTrue(error.getMessage().contains("sdk/Base.call"));
    }
    @Test public void inheritedPackagePrivateFieldCannotHideBehindPayloadOwner() throws Exception {
        assertThrows(IOException.class, () -> verifyMember(0, true, "sdk/Child", false));
    }
    @Test public void protectedMethodAllowsThisButRejectsBaseReceiver() throws Exception {
        verifyMember(Opcodes.ACC_PROTECTED, false, "sdk/Base", false);
        assertThrows(IOException.class, () -> verifyMember(Opcodes.ACC_PROTECTED, false, "sdk/Base", true));
    }
    @Test public void protectedFieldAllowsThisButRejectsBaseReceiver() throws Exception {
        verifyMember(Opcodes.ACC_PROTECTED, true, "sdk/Base", false);
        assertThrows(IOException.class, () -> verifyMember(Opcodes.ACC_PROTECTED, true, "sdk/Base", true));
    }
    @Test public void inheritedPublicMembersRemainAccessible() throws Exception {
        verifyMember(Opcodes.ACC_PUBLIC, false, "sdk/Child", false);
        verifyMember(Opcodes.ACC_PUBLIC, true, "sdk/Child", false);
        verifyMember(Opcodes.ACC_PUBLIC, false, "sdk/Base", true);
    }
}
