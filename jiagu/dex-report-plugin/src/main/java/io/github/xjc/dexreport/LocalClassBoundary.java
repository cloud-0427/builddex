package io.github.xjc.dexreport;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.io.*;
import java.util.*;
import java.util.jar.*;

/** Rejects linkage across a parent/child loader boundary before encryption. */
final class LocalClassBoundary {
    private LocalClassBoundary() {}
    static void verify(File shell, File runtime, File payload, String uploader) throws IOException {
        Map<String, ClassNode> parent = read(shell); parent.putAll(read(runtime));
        Map<String, ClassNode> child = read(payload);
        ClassNode core = parent.get("androidx/core/app/CoreComponentFactory");
        if (core != null && core.methods.stream().anyMatch(m -> m.name.equals("instantiateClassLoader")))
            throw new IOException("LOCAL_FACTORY_UNSUPPORTED: AndroidX overrides instantiateClassLoader");
        if (uploader != null) {
            ClassNode node = parent.get(uploader.replace('.', '/'));
            if (node == null || (node.access & Opcodes.ACC_PUBLIC) == 0 ||
                    node.methods.stream().noneMatch(m -> m.name.equals("<init>") && m.desc.equals("()V") && (m.access & Opcodes.ACC_PUBLIC) != 0) ||
                    !subtype(node.name, "io/github/xjc/jiagu/local/LocalEventUploader", parent, new HashSet<>()))
                throw new IOException("Local uploader must be a public Shell LocalEventUploader with public no-arg constructor: " + uploader);
        }
        Map<String, ClassNode> all = new HashMap<>(parent); all.putAll(child);
        for (ClassNode n : parent.values()) scan(n, child, all, false);
        for (ClassNode n : child.values()) scan(n, parent, all, true);
    }
    private static Map<String, ClassNode> read(File file) throws IOException {
        Map<String, ClassNode> result = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(file)) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    ClassNode n = new ClassNode(); new ClassReader(in).accept(n, 0); result.put(n.name, n);
                }
            }
        }
        return result;
    }
    private static void scan(ClassNode n, Map<String, ClassNode> targets, Map<String, ClassNode> own, boolean outward) throws IOException {
        check(n, n.superName, targets, outward); for (String i : n.interfaces) check(n, i, targets, outward);
        for (FieldNode f : n.fields) descriptor(n, f.desc, targets, outward);
        for (MethodNode m : n.methods) {
            Frame<BasicValue>[] frames = null;
            int index = 0;
            descriptor(n, m.desc, targets, outward);
            if (m.exceptions != null) for (String e : m.exceptions) check(n, e, targets, outward);
            for (TryCatchBlockNode t : m.tryCatchBlocks) check(n, t.type, targets, outward);
            for (AbstractInsnNode instruction : m.instructions) {
                if (instruction instanceof FieldInsnNode || instruction instanceof MethodInsnNode) {
                    if (frames == null && outward) {
                        try { frames = new Analyzer<>(new ReceiverTypes(own)).analyze(n.name, m); }
                        catch (AnalyzerException e) { throw new IOException("LOCAL_ACCESS_ANALYSIS_FAILED: " + n.name + "." + m.name + m.desc, e); }
                    }
                }
                if (instruction instanceof TypeInsnNode) check(n, ((TypeInsnNode) instruction).desc, targets, outward);
                else if (instruction instanceof FieldInsnNode) {
                    FieldInsnNode f = (FieldInsnNode) instruction;
                    check(n, f.owner, targets, outward); descriptor(n, f.desc, targets, outward);
                    access(n, f.owner, f.name, f.desc, targets, own, outward, false,
                            receiver(frames, index, f.getOpcode() == Opcodes.PUTFIELD ? 1 : 0), f.getOpcode());
                } else if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode f = (MethodInsnNode) instruction;
                    check(n, f.owner, targets, outward); descriptor(n, f.desc, targets, outward);
                    access(n, f.owner, f.name, f.desc, targets, own, outward, true,
                            receiver(frames, index, Type.getArgumentTypes(f.desc).length), f.getOpcode());
                } else if (instruction instanceof LdcInsnNode) constant(n, ((LdcInsnNode) instruction).cst, targets, own, outward);
                else if (instruction instanceof InvokeDynamicInsnNode) {
                    InvokeDynamicInsnNode i = (InvokeDynamicInsnNode) instruction;
                    descriptor(n, i.desc, targets, outward); constant(n, i.bsm, targets, own, outward);
                    for (Object a : i.bsmArgs) constant(n, a, targets, own, outward);
                } else if (instruction instanceof MultiANewArrayInsnNode) descriptor(n, ((MultiANewArrayInsnNode) instruction).desc, targets, outward);
                index++;
            }
        }
    }
    private static void constant(ClassNode n, Object value, Map<String, ClassNode> t, Map<String, ClassNode> own, boolean out) throws IOException {
        if (value instanceof Type) descriptor(n, ((Type) value).getDescriptor(), t, out);
        if (value instanceof Handle) {
            Handle h = (Handle) value; check(n, h.getOwner(), t, out); descriptor(n, h.getDesc(), t, out);
            int opcode = h.getTag() == Opcodes.H_INVOKESPECIAL ? Opcodes.INVOKESPECIAL :
                    h.getTag() == Opcodes.H_NEWINVOKESPECIAL ? Opcodes.NEW : Opcodes.INVOKEVIRTUAL;
            access(n, h.getOwner(), h.getName(), h.getDesc(), t, own, out, h.getTag() > Opcodes.H_PUTSTATIC,
                    opcode == Opcodes.INVOKESPECIAL ? n.name : h.getOwner(), opcode);
        }
        if (value instanceof ConstantDynamic) {
            ConstantDynamic c = (ConstantDynamic) value;
            descriptor(n, c.getDescriptor(), t, out); constant(n, c.getBootstrapMethod(), t, own, out);
            for (int i = 0; i < c.getBootstrapMethodArgumentCount(); i++) constant(n, c.getBootstrapMethodArgument(i), t, own, out);
        }
    }
    private static void descriptor(ClassNode n, String desc, Map<String, ClassNode> t, boolean out) throws IOException {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("L([^;]+);").matcher(desc);
        while (m.find()) check(n, m.group(1), t, out);
    }
    private static void check(ClassNode n, String target, Map<String, ClassNode> t, boolean out) throws IOException {
        if (target == null) return;
        if (target.startsWith("[")) { descriptor(n, target, t, out); return; }
        ClassNode dest = t.get(target);
        if (dest == null) return;
        if (!out) throw new IOException("LOCAL_SHELL_TO_PAYLOAD_REFERENCE: " + n.name + " -> " + target);
        if ((dest.access & Opcodes.ACC_PUBLIC) == 0) throw new IOException("LOCAL_CROSS_LOADER_ACCESS: " + n.name + " -> " + target);
    }
    private static void access(ClassNode n, String owner, String name, String desc, Map<String, ClassNode> t,
                               Map<String, ClassNode> own, boolean out, boolean method, String receiver, int opcode) throws IOException {
        if (!out) return; // Direct references and class hierarchy already reject Shell -> Payload.
        Map<String, ClassNode> all = own;
        Member member = resolve(owner, name, desc, method, all, new HashSet<>());
        if (member == null || !t.containsKey(member.owner)) return;
        if (!out) throw new IOException("LOCAL_SHELL_TO_PAYLOAD_REFERENCE: " + n.name + " -> " + member.owner + "." + name + desc);
        if ((member.flags & Opcodes.ACC_PUBLIC) != 0) return;
        boolean subclass = subtype(n.name, member.owner, all, new HashSet<>());
        boolean validReceiver = (member.flags & Opcodes.ACC_STATIC) != 0 ||
                (receiver != null && subtype(receiver, n.name, all, new HashSet<>()));
        boolean validConstructor = !name.equals("<init>") || opcode == Opcodes.INVOKESPECIAL;
        if ((member.flags & Opcodes.ACC_PROTECTED) == 0 || !subclass || !validReceiver || !validConstructor)
            throw new IOException("LOCAL_CROSS_LOADER_ACCESS: " + n.name + " -> " + member.owner + "." + name + desc
                    + " (symbolic owner=" + owner + ", receiver=" + receiver + "); keep caller and declaration in the same lane or expose a public ABI");
    }
    private static Member resolve(String owner, String name, String desc, boolean method,
                                  Map<String, ClassNode> all, Set<String> seen) {
        if (owner == null || !seen.add(owner)) return null;
        ClassNode node = all.get(owner); if (node == null) return null;
        if (method) for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return new Member(owner, m.access);
        if (!method) for (FieldNode f : node.fields) if (f.name.equals(name) && f.desc.equals(desc)) return new Member(owner, f.access);
        if (name.equals("<init>") || name.equals("<clinit>")) return null;
        if (method) { Member m = resolve(node.superName, name, desc, true, all, seen); if (m != null) return m; }
        for (String i : node.interfaces) { Member m = resolve(i, name, desc, method, all, seen); if (m != null) return m; }
        return method ? null : resolve(node.superName, name, desc, false, all, seen);
    }
    private static final class Member {
        final String owner; final int flags;
        Member(String owner, int flags) { this.owner = owner; this.flags = flags; }
    }
    private static String receiver(Frame<BasicValue>[] frames, int index, int arguments) {
        if (frames == null || frames[index] == null) return null;
        Frame<BasicValue> frame = frames[index]; int position = frame.getStackSize() - arguments - 1;
        if (position < 0) return null;
        Type type = frame.getStack(position).getType();
        return type != null && type.getSort() == Type.OBJECT ? type.getInternalName() : null;
    }
    // Retain verifier types without loading user classes into the Gradle daemon.
    // Ambiguous merges become Object and fail closed for protected instance access.
    private static final class ReceiverTypes extends BasicInterpreter {
        private final Map<String, ClassNode> classes;
        ReceiverTypes(Map<String, ClassNode> classes) { super(Opcodes.ASM9); this.classes = classes; }
        @Override public BasicValue newValue(Type type) {
            if (type != null && (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY)) return new BasicValue(type);
            return super.newValue(type);
        }
        @Override public BasicValue merge(BasicValue a, BasicValue b) {
            if (a.equals(b)) return a;
            if (a.isReference() && b.isReference()) {
                Type x = a.getType(), y = b.getType();
                if (x.getSort() == Type.OBJECT && y.getSort() == Type.OBJECT) {
                    String owner = x.getInternalName(); Set<String> seen = new HashSet<>();
                    while (owner != null && seen.add(owner)) {
                        if (subtype(y.getInternalName(), owner, classes, new HashSet<>())) return new BasicValue(Type.getObjectType(owner));
                        ClassNode node = classes.get(owner); owner = node == null ? null : node.superName;
                    }
                }
                return new BasicValue(Type.getObjectType("java/lang/Object"));
            }
            return BasicValue.UNINITIALIZED_VALUE;
        }
        @Override public BasicValue binaryOperation(AbstractInsnNode insn, BasicValue a, BasicValue b) throws AnalyzerException {
            if (insn.getOpcode() == Opcodes.AALOAD && a.getType() != null && a.getType().getSort() == Type.ARRAY)
                return newValue(Type.getType(a.getType().getDescriptor().substring(1)));
            return super.binaryOperation(insn, a, b);
        }
    }
    private static boolean subtype(String name, String target, Map<String, ClassNode> all, Set<String> seen) {
        if (target.equals(name)) return true;
        if (name == null || !seen.add(name)) return false;
        ClassNode n = all.get(name); if (n == null) return false;
        if (subtype(n.superName, target, all, seen)) return true;
        for (String i : n.interfaces) if (subtype(i, target, all, seen)) return true;
        return false;
    }
}
