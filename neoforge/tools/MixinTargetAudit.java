import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Checks configured mixin contracts against the actual patched NeoForge class files.
 * Does not load Minecraft classes or initialize the game. Run after classes/processResources.
 * This catches missing methods, stale invocation owners/descriptors and callback signatures;
 * it complements, rather than replaces, application by Mixin in a running client.
 */
public final class MixinTargetAudit {
    private static final Map<String, ClassNode> targets = new HashMap<>();
    private static final List<String> failures = new ArrayList<>();
    private static JarFile game;
    private static final List<JarFile> optionalJars = new ArrayList<>();

    private static ClassNode parse(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    private static ClassNode target(String name) throws Exception {
        if (targets.containsKey(name)) return targets.get(name);
        var entry = game.getJarEntry(name + ".class");
        JarFile source = game;
        if (entry == null) for (JarFile optional : optionalJars) {
            entry = optional.getJarEntry(name + ".class");
            if (entry != null) { source = optional; break; }
        }
        if (entry == null) return null;
        ClassNode node = parse(source.getInputStream(entry).readAllBytes());
        targets.put(name, node);
        return node;
    }

    private static List<AnnotationNode> annotations(List<AnnotationNode> a, List<AnnotationNode> b) {
        var all = new ArrayList<AnnotationNode>();
        if (a != null) all.addAll(a);
        if (b != null) all.addAll(b);
        return all;
    }

    private static Object value(AnnotationNode a, String key) {
        if (a.values != null) for (int i = 0; i < a.values.size(); i += 2)
            if (a.values.get(i).equals(key)) return a.values.get(i + 1);
        return null;
    }

    private static List<?> list(Object v) {
        return v == null ? List.of() : v instanceof List<?> l ? l : List.of(v);
    }

    private static void fail(String where, String why) {
        failures.add(where + ": " + why);
    }

    private static FieldNode field(ClassNode node, String name) throws Exception {
        if (node == null) return null;
        for (FieldNode f : node.fields) if (f.name.equals(name)) return f;
        return node.superName == null ? null : field(target(node.superName), name);
    }

    private static List<MethodNode> methods(ClassNode node, String selector) {
        int paren = selector.indexOf('(');
        String name = paren < 0 ? selector : selector.substring(0, paren);
        String desc = paren < 0 ? null : selector.substring(paren);
        return node.methods.stream().filter(m -> m.name.equals(name) && (desc == null || m.desc.equals(desc))).toList();
    }

    private static void audit(ClassNode mixin, ClassNode node) throws Exception {
        String where = mixin.name + " -> " + node.name;
        for (FieldNode f : mixin.fields) for (AnnotationNode a : annotations(f.visibleAnnotations, f.invisibleAnnotations)) {
            if (a.desc.endsWith("/Shadow;")) {
                FieldNode actual = field(node, f.name);
                if (actual == null || !actual.desc.equals(f.desc)) fail(where, "shadow field " + f.name + f.desc);
            }
        }
        for (MethodNode handler : mixin.methods) for (AnnotationNode a : annotations(handler.visibleAnnotations, handler.invisibleAnnotations)) {
            String context = where + "#" + handler.name;
            if (a.desc.endsWith("/Accessor;")) {
                String name = (String) value(a, "value");
                FieldNode f = field(node, name);
                Type[] args = Type.getArgumentTypes(handler.desc);
                String desc = args.length == 0 ? Type.getReturnType(handler.desc).getDescriptor() : args[0].getDescriptor();
                if (f == null || !f.desc.equals(desc)) fail(context, "accessor field " + name + " expects " + desc);
                continue;
            }
            if (a.desc.endsWith("/Invoker;")) {
                String name = (String) value(a, "value");
                if (methods(node, name + handler.desc).isEmpty()) fail(context, "invoker " + name + handler.desc);
                continue;
            }
            Object selectors = value(a, "method");
            if (selectors == null) continue;
            for (Object selector : list(selectors)) {
                List<MethodNode> selected = methods(node, (String) selector);
                if (selected.isEmpty()) fail(context, "method not found: " + selector);
                for (MethodNode method : selected) {
                    if (!method.name.equals("<init>") && ((method.access ^ handler.access) & Opcodes.ACC_STATIC) != 0)
                        fail(context, "static/instance mismatch in " + method.name + method.desc);
                    if (a.desc.endsWith("/Inject;")) {
                        Type[] args = Type.getArgumentTypes(handler.desc);
                        int callback = -1;
                        for (int i = 0; i < args.length; i++) if (args[i].getDescriptor().contains("/CallbackInfo")) callback = i;
                        if (callback > 0 && !Arrays.equals(Arrays.copyOf(args, callback), Type.getArgumentTypes(method.desc)))
                            fail(context, "callback arguments differ from " + method.name + method.desc);
                        if (callback >= 0) {
                            boolean returns = Type.getReturnType(method.desc).getSort() != Type.VOID;
                            if (returns != args[callback].getDescriptor().contains("CallbackInfoReturnable"))
                                fail(context, "wrong callback return type for " + method.name + method.desc);
                        }
                    }
                    for (Object at : list(value(a, "at"))) {
                        AnnotationNode point = (AnnotationNode) at;
                        if ("FIELD".equals(value(point, "value"))) {
                            String ref = (String) value(point, "target");
                            int semi = ref.indexOf(';'), colon = ref.indexOf(':', semi);
                            String owner = ref.substring(1, semi), name = ref.substring(semi + 1, colon), desc = ref.substring(colon + 1);
                            Object opcode = value(point, "opcode");
                            boolean found = false;
                            for (AbstractInsnNode insn : method.instructions) if (insn instanceof FieldInsnNode access
                                && access.owner.equals(owner) && access.name.equals(name) && access.desc.equals(desc)
                                && (opcode == null || access.getOpcode() == (Integer) opcode)) {
                                found = true;
                                if (a.desc.endsWith("/ModifyExpressionValue;") && (!handler.desc.equals("(" + desc + ")" + desc)
                                    || (access.getOpcode() != Opcodes.GETFIELD && access.getOpcode() != Opcodes.GETSTATIC)))
                                    fail(context, "field expression signature differs from " + ref);
                            }
                            if (!found) fail(context, "field access not found in " + method.name + method.desc + ": " + ref);
                            continue;
                        }
                        if (!"INVOKE".equals(value(point, "value"))) continue;
                        String ref = (String) value(point, "target");
                        int semi = ref.indexOf(';'), paren = ref.indexOf('(', semi);
                        String owner = ref.substring(1, semi), name = ref.substring(semi + 1, paren), desc = ref.substring(paren);
                        boolean found = false;
                        for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode call
                            && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc)) {
                            found = true;
                            if (a.desc.endsWith("/WrapOperation;")) {
                                var expected = new ArrayList<Type>();
                                if (call.getOpcode() != Opcodes.INVOKESTATIC) expected.add(Type.getObjectType(owner));
                                expected.addAll(List.of(Type.getArgumentTypes(desc)));
                                expected.add(Type.getObjectType("com/llamalad7/mixinextras/injector/wrapoperation/Operation"));
                                Type[] actual = Type.getArgumentTypes(handler.desc);
                                if (!Arrays.equals(expected.toArray(Type[]::new), actual)
                                    || !Type.getReturnType(handler.desc).equals(Type.getReturnType(desc)))
                                    fail(context, "wrapper signature differs from " + ref);
                            }
                        }
                        if (!found) fail(context, "invocation not found in " + method.name + method.desc + ": " + ref);
                    }
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Path classes = Path.of(args[0]), resources = Path.of(args[1]);
        int count = 0;
        for (int i = 3; i < args.length; i++) optionalJars.add(new JarFile(args[i]));
        try (JarFile jar = new JarFile(args[2])) {
            game = jar;
            for (String config : List.of("skycraft.mixins.json", "skycraft.client.mixins.json")) {
                String json = Files.readString(resources.resolve(config));
                var pkg = Pattern.compile("\"package\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                if (!pkg.find()) throw new IllegalStateException("No package in " + config);
                var names = Pattern.compile("\"(\\w+(?:Mixin|Accessor))\"").matcher(json);
                while (names.find()) {
                    ClassNode mixin = parse(Files.readAllBytes(classes.resolve(pkg.group(1).replace('.', '/') + "/" + names.group(1) + ".class")));
                    for (AnnotationNode a : annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations)) if (a.desc.endsWith("/Mixin;")) {
                        var types = new ArrayList<String>();
                        for (Object t : list(value(a, "value"))) types.add(((Type) t).getInternalName());
                        for (Object t : list(value(a, "targets"))) types.add(((String) t).replace('.', '/'));
                        for (String name : types) {
                            ClassNode node = target(name);
                            if (node == null && annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations).stream().anyMatch(annotation -> annotation.desc.endsWith("/Pseudo;"))) {
                                System.out.println("Optional @Pseudo target absent from audit classpath: " + name);
                            } else if (node == null) fail(mixin.name, "target class not found: " + name);
                            else audit(mixin, node);
                        }
                    }
                    count++;
                }
            }
        }
        for (String f : failures) System.err.println(f);
        System.out.println("Audited " + count + " configured mixins; " + failures.size() + " contract errors.");
        if (!failures.isEmpty()) System.exit(1);
    }
}
