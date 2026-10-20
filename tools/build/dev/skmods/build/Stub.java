package dev.skmods.build;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Hollows a class out: the same name, fields and methods, but every method body returns the empty value of its type (0, false, null, an empty list, map, set
 * or array, an empty string). A mod that carries a trimmed server gets the features it does not need this way: the code that calls them still links, and
 * nothing of what they did is in the mod.
 */
final class Stub {
    private Stub() {
    }

    static byte[] hollow(byte[] classFile) {
        ClassNode cn = new ClassNode();
        new ClassReader(classFile).accept(cn, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        cn.methods.removeIf(m -> m.name.equals("<clinit>"));
        for (MethodNode m : cn.methods) {
            if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            m.instructions.clear();
            m.tryCatchBlocks.clear();
            m.localVariables = null;
            if (m.name.equals("<init>")) {
                m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, cn.superName, "<init>", "()V", false));
                m.instructions.add(new InsnNode(Opcodes.RETURN));
            } else {
                empty(m.instructions, Type.getReturnType(m.desc));
            }
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static void empty(InsnList il, Type t) {
        switch (t.getSort()) {
            case Type.VOID:
                il.add(new InsnNode(Opcodes.RETURN));
                return;
            case Type.BOOLEAN: case Type.BYTE: case Type.CHAR: case Type.SHORT: case Type.INT:
                il.add(new InsnNode(Opcodes.ICONST_0));
                il.add(new InsnNode(Opcodes.IRETURN));
                return;
            case Type.LONG:
                il.add(new InsnNode(Opcodes.LCONST_0));
                il.add(new InsnNode(Opcodes.LRETURN));
                return;
            case Type.FLOAT:
                il.add(new InsnNode(Opcodes.FCONST_0));
                il.add(new InsnNode(Opcodes.FRETURN));
                return;
            case Type.DOUBLE:
                il.add(new InsnNode(Opcodes.DCONST_0));
                il.add(new InsnNode(Opcodes.DRETURN));
                return;
            case Type.ARRAY: {
                il.add(new InsnNode(Opcodes.ICONST_0));
                Type e = t.getElementType();
                if (t.getDimensions() == 1 && e.getSort() < Type.ARRAY) {
                    int code = switch (e.getSort()) {
                        case Type.BOOLEAN -> Opcodes.T_BOOLEAN;
                        case Type.CHAR -> Opcodes.T_CHAR;
                        case Type.BYTE -> Opcodes.T_BYTE;
                        case Type.SHORT -> Opcodes.T_SHORT;
                        case Type.INT -> Opcodes.T_INT;
                        case Type.FLOAT -> Opcodes.T_FLOAT;
                        case Type.LONG -> Opcodes.T_LONG;
                        default -> Opcodes.T_DOUBLE;
                    };
                    il.add(new IntInsnNode(Opcodes.NEWARRAY, code));
                } else {
                    String comp = t.getDescriptor().substring(1);
                    il.add(new TypeInsnNode(Opcodes.ANEWARRAY, comp.startsWith("L") ? comp.substring(1, comp.length() - 1) : comp));
                }
                il.add(new InsnNode(Opcodes.ARETURN));
                return;
            }
            default: {
                switch (t.getInternalName()) {
                    case "java/util/List": case "java/util/Collection": case "java/lang/Iterable":
                        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/List", "of", "()Ljava/util/List;", true));
                        break;
                    case "java/util/Set":
                        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Set", "of", "()Ljava/util/Set;", true));
                        break;
                    case "java/util/Map":
                        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Map", "of", "()Ljava/util/Map;", true));
                        break;
                    case "java/util/Optional":
                        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Optional", "empty", "()Ljava/util/Optional;", false));
                        break;
                    case "java/lang/String": case "java/lang/CharSequence":
                        il.add(new LdcInsnNode(""));
                        break;
                    default:
                        il.add(new InsnNode(Opcodes.ACONST_NULL));
                }
                il.add(new InsnNode(Opcodes.ARETURN));
            }
        }
    }
}
