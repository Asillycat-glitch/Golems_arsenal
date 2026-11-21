package sanctuary.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 引擎层的两道门：
 *
 * <ol>
 *   <li><b>方法入口</b>：把"硬清除/处决"入口的第一行改成一进方法就 {@code GuardRuntime.block(...)}；</li>
 *   <li><b>字段写入</b>：把外来模组对 {@code DataItem.f_135391_}（血量槽）、
 *       {@code Entity.f_19825_/f_19826_/f_185933_}（坐标）、{@code f_146795_}（removalReason）、
 *       {@code isAddedToWorld}/{@code canUpdate} 的 {@code PUTFIELD} 换成 {@code GuardRuntime.putX(...)}。</li>
 * </ol>
 *
 * <p>字段替换是"栈中立"的：PUTFIELD 时栈上正好是 {@code [obj, value]}，我们压一个 site 常量再
 * 调静态方法（消费三个槽），不需要 {@code DUP}/{@code SWAP} 那套栈操作，也不会改变 max stack。
 * 因此字段门这条路径用 {@code ClassWriter(reader, 0)}（不重算帧）——只有方法入口那条需要算帧。</p>
 *
 * <p>只改 {@link #HOSTILE} 里列出的写入方；原版 / 宿主 / 我们自己的写一律不碰。</p>
 */
public final class SanctuaryTransformer implements ClassFileTransformer {

    private static final String RUNTIME = "sanctuary/agent/GuardRuntime";

    /** 会被字段门处理的写入方。白名单之外的类不生成字段门。 */
    /**
     * 2026-11-18：不再维护对手包前缀。保留空数组只为兼容旧调用点；
     * 真正的覆盖是 transform() 里的 isModClass(domain) 分支（所有 mods 类）。
     */
    private static final String[] HOSTILE = {};

    /** key = owner.name，value = GuardRuntime 里对应的方法名。 */
    private static final Map<String, String> FIELD_RULES = new HashMap<>();

    static {
        // 血量所在槽
        FIELD_RULES.put("net/minecraft/network/syncher/SynchedEntityData$DataItem.f_135391_", "putObject");
        // 坐标：1.20.1 的 position 是 Vec3 f_19825_，不是 x/y/z 三个 double
        FIELD_RULES.put("net/minecraft/world/entity/Entity.f_19825_", "putObject");
        FIELD_RULES.put("net/minecraft/world/entity/Entity.f_19826_", "putObject");
        FIELD_RULES.put("net/minecraft/world/entity/Entity.f_185933_", "putObject");
        // 移除 / 冻结
        FIELD_RULES.put("net/minecraft/world/entity/Entity.f_146795_", "putObject");
        FIELD_RULES.put("net/minecraft/world/entity/Entity.f_146801_", "putObject");
        FIELD_RULES.put("net/minecraft/world/entity/Entity.isAddedToWorld", "putBoolean");
        FIELD_RULES.put("net/minecraft/world/entity/Entity.canUpdate", "putBoolean");
    }

    /** 2026-11-18：不再按对手类名打 trace；保留空集合兼容旧路径。 */
    private static final java.util.Set<String> TRACE = java.util.Collections.emptySet();

    /**
     * 直接"废掉"的方法（整类）。
     *
     * <p>2026-11-10 实测：AV 的 `ClassRestoreManager` 把原版字节缓存在 **native** 里
     * （`NativeAgentBridge.nativeRegisterRestoreClass` / `nativeRetransformRestoreTargets`），
     * 每 ~0.4 秒把我们的目标类恢复一次（2 分钟里 VanShRadiantSun 被恢复 367 次）。
     * 在 Java 层重打永远追不上，所以直接把它的"注册/重放"入口做成空方法。</p>
     */
    /**
     * 2026-11-18：不再按对手恢复类整类 neutralize。
     * 恢复对抗改为 SanctuaryRestoreWatch：只对“本 agent 改过的类”做 retransform watch。
     */
    private static final Map<String, String[]> NEUTRALIZE = new HashMap<>();

    

    public static java.util.Set<String> neutralizeNames() {
        return NEUTRALIZE.keySet();
    }

    private static boolean matchesAny(String name, String[] prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 方法一进就按返回类型立刻返回（void → return，int → 0，引用 → null）。 */
    private static MethodVisitor alwaysReturn(MethodVisitor mv, String desc) {
        Type ret = Type.getReturnType(desc);
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                emitDefaultReturn(mv, ret);
            }
        };
    }

    private final Map<String, Rule> rules = new HashMap<>();
    private final boolean patchObjectReturns;
    /** 真正改过字节的类（internal name）；后续重转只碰这批。 */
    private static final Set<String> MODIFIED_CLASSES = ConcurrentHashMap.newKeySet();
    /** 已确认 no-op 的类 + 字节哈希，避免重复 ASM。 */
    private final Map<String, Integer> noPatchHash = new ConcurrentHashMap<>();

    /** 预筛常量池里是否出现字段/方法门需要的东西；不命中就不进 ASM。 */
    private static final String[] INTEREST_NEEDLES = {
            "f_135391_", "f_19825_", "f_19826_", "f_185933_", "f_146795_",
            "f_146801_", "isAddedToWorld", "canUpdate",
            "setHealth", "getHealth", "getMaxHealth",
            "setRemoved", "remove", "kill", "die",
            "sun/misc/Unsafe", "jdk/internal/misc/Unsafe", "VarHandle"
    };
    /** 类层次缓存，避免每条帧都 Class.forName。 */
    private static final Map<String, Class<?>> CLASS_RESOLVE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> COMMON_SUPER_CACHE = new ConcurrentHashMap<>();

    public SanctuaryTransformer(boolean patchObjectReturns) {
        this.patchObjectReturns = patchObjectReturns;
        // 2026-11-18 去针对化：不再按对手类名注册方法入口。
        // 防御只看“所有 mods 类的字段写/方法调用”这一技术条件；
        // rules 保留为空容器只为兼容旧代码路径，transform 的 isModClass 分支才是覆盖来源。
    }

    /** 这个类是不是要过字段门。 */
    private static boolean isModClass(java.security.ProtectionDomain domain) {
        try {
            if (domain == null || domain.getCodeSource() == null || domain.getCodeSource().getLocation() == null) {
                return false;
            }
            String location = domain.getCodeSource().getLocation().toString().replace('\\', '/');
            return location.contains("/mods/") || location.endsWith("/mods");
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isHostile(String className) {
        if (className == null) {
            return false;
        }
        // 数据驱动优先：mod 扫 jar 推过来的类。
        if (GuardRuntime.isTarget(className)) {
            return true;
        }
        for (String prefix : HOSTILE) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Heartbeat 用：当前规则命中的类（点分名）。 */
    public Set<String> patchedClassNames() {
        Set<String> out = new java.util.HashSet<>();
        for (String key : rules.keySet()) {
            out.add(key.replace('/', '.'));
        }
        return out;
    }

    /** 真正被改过字节的类（internal name），供后续只重转这批。 */
    public static Set<String> modifiedClassNames() {
        return new java.util.HashSet<>(MODIFIED_CLASSES);
    }

    public static int modifiedClassCount() {
        return MODIFIED_CLASSES.size();
    }

    /** 给已加载类补 retransform 前的轻量判断；bytes 为 null 时保守放行。 */
    public boolean isInterested(String className, byte[] bytes) {
        if (rules.containsKey(className)
                || NEUTRALIZE.containsKey(className)
                || isVanillaGateClass(className)
                || TRACE.contains(className)) {
            return true;
        }
        return bytes == null || containsInterestNeedle(bytes);
    }

    private static boolean containsInterestNeedle(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        // 常量池里的 name 是 ASCII；用 Latin-1 做低成本字节搜索，不解析 class。
        String raw = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        for (String needle : INTEREST_NEEDLES) {
            if (raw.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public Map<String, Rule> rules() {
        return rules;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain domain, byte[] classfileBuffer) {
        if (className == null) {
            return null;
        }
        Rule rule = rules.get(className);
        String[] neutralize = NEUTRALIZE.get(className);
        boolean vanillaGate = isVanillaGateClass(className);
        boolean hostile = isHostile(className) || (isModClass(domain) && !GuardRuntime.isExcludedDotted(className.replace('/', '.')));
        if (rule == null && neutralize == null && !vanillaGate && !hostile) {
            return null;
        }
        int classHash = Arrays.hashCode(classfileBuffer);
        boolean forced = rule != null || neutralize != null || vanillaGate
                || TRACE.contains(className);
        if (!forced) {
            Integer previous = noPatchHash.get(className);
            if (previous != null && previous == classHash) {
                return null;
            }
            if (hostile && !containsInterestNeedle(classfileBuffer)) {
                noPatchHash.put(className, classHash);
                return null;
            }
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            // 方法入口那条会插入跳转，必须算帧；字段门/探针不改控制流，只用 COMPUTE_MAXS
            // （探针会多压一个常量，max stack 必须重算，但帧可以沿用原类）。
            //
            // 2026-11-12 修：原实现 getCommonSuperClass 恒返回 Object，遇到
            // `catch (any)` 的异常处理器时会把栈上的 Throwable 算成 Object，
            // 运行期 VerifyError（toaru GungnirArcaneCompat.finishNativeHealth 实测）。
            // 这里用实际 ClassLoader 解析继承链；解析不到才退回 Object。
            final ClassLoader hierarchyLoader = loader != null
                    ? loader : SanctuaryTransformer.class.getClassLoader();
            ClassWriter writer = (rule != null || neutralize != null || vanillaGate)
                    ? new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
                        @Override
                        protected String getCommonSuperClass(String a, String b) {
                            return commonSuperClass(a, b, hierarchyLoader);
                        }
                    }
                    : new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            final int[] patchedMethods = {0};
            final int[] patchedFields = {0};
            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                    if (neutralize != null && matchesAny(name, neutralize)) {
                        patchedMethods[0]++;
                        return alwaysReturn(mv, desc);
                    }
                    String gate = VANILLA_GATES.get(className + "#" + name);
                    if (gate != null) {
                        patchedMethods[0]++;
                        mv = vanillaGate(mv, className, name, gate);
                    }
                    if (rule != null && rule.matches(name)) {
                        mv = headPatch(mv, className, name, desc, access, rule, patchedMethods);
                    }
                    if (hostile) {
                        mv = new FieldWriteVisitor(mv, patchedFields, className, name);
                    }
                    if (TRACE.contains(className)) {
                        mv = new EntryTraceVisitor(mv, className + "." + name);
                    }
                    return mv;
                }
            };
            reader.accept(visitor, 0);
            if (patchedMethods[0] == 0 && patchedFields[0] == 0) {
                noPatchHash.put(className, classHash);
                return null;
            }
            MODIFIED_CLASSES.add(className);
            noPatchHash.remove(className);
            GuardRuntime.log("[SanctuaryAgent] patched " + className
                    + " methods=" + patchedMethods[0] + " fields=" + patchedFields[0]);
            return writer.toByteArray();
        } catch (Throwable t) {
            GuardRuntime.log("[SanctuaryAgent] transform failed for " + className + ": " + t);
            return null;
        }
    }

    /** 方法入口门：{} */
    private MethodVisitor headPatch(MethodVisitor mv, String className, String name, String desc,
                                    int access, Rule rule, int[] patched) {
        int arg = rule.argZero ? 0 : firstEntityArg(desc, access);
        if (arg < 0) {
            return mv;
        }
        Type ret = Type.getReturnType(desc);
        if (!patchObjectReturns && !rule.objectReturn && ret.getSort() == Type.OBJECT) {
            return mv;
        }
        if (!patchObjectReturns && !rule.objectReturn && ret.getSort() == Type.ARRAY) {
            return mv;
        }
        patched[0]++;
        final int slot = arg;
        final String site = className + "." + name;
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                super.visitVarInsn(Opcodes.ALOAD, slot);
                super.visitLdcInsn(site);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "block",
                        "(Ljava/lang/Object;Ljava/lang/String;)Z", false);
                Label cont = new Label();
                super.visitJumpInsn(Opcodes.IFEQ, cont);
                emitDefaultReturn(super.mv, ret);
                super.visitLabel(cont);
            }
        };
    }

    /** 方法入口探针：只压一个常量、调一次静态方法，栈完全不变。 */
    private static final class EntryTraceVisitor extends MethodVisitor {

        private final String site;

        EntryTraceVisitor(MethodVisitor mv, String site) {
            super(Opcodes.ASM9, mv);
            this.site = site;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitLdcInsn(site);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "trace",
                    "(Ljava/lang/String;)V", false);
        }
    }

    /**
     * 原版关卡：不管攻击者叫什么名字，最后都要经过这几个方法。
     *
     * <p>不用 mixin —— mixin 是 launcher plugin，被 retransform 就没了；这里是
     * Instrumentation 直接改原版类的字节码，和我们的其它补丁同一层。</p>
     */
    private static final Map<String, String> VANILLA_GATES = new HashMap<>();
    static {
        // 2026-11-11：原版巨型类的 COMPUTE_FRAMES 在 AV 的 retransform 循环里会撞 GC 崩 JVM，全部撤销。
    }

    private static final Map<String, String> VANILLA_UNUSED = new HashMap<>();

    static {
        // 所有"改实体数据"的必经之路（血量、最大生命、位置、自定义数据全走这里）
        // 所有"移除实体"的必经之路
        // 拆索引：不需要 setRemoved，直接把实体从 ClassInstanceMultiMap 里摘掉
        // 任何实体从 NBT 生成（含傀儡物品放出）都会经过这里：先洗掉"假死名单"里的 UUID
        // 反射绕过 hurt 的伤害准入
        // 更里层的索引摘除（EntitySection）
    }

    public static boolean isVanillaGateClass(String className) {
        if (className == null) {
            return false;
        }
        for (String key : VANILLA_GATES.keySet()) {
            if (key.startsWith(className + "#")) {
                return true;
            }
        }
        return false;
    }

    /** 在原版关卡方法入口插一段守卫：返回 true 就直接 return。 */
    private static MethodVisitor vanillaGate(MethodVisitor mv, String className, String name, String kind) {
        String site = className + "#" + name;
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                // tag：静态方法，slot0 就是 CompoundTag，洗一下 UUID 就继续，不做跳转
                if ("tag".equals(kind)) {
                    super.visitVarInsn(Opcodes.ALOAD, 0);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "scrubSpawnTag",
                            "(Ljava/lang/Object;)V", false);
                    return;
                }
                Label cont = new Label();
                super.visitVarInsn(Opcodes.ALOAD, 0);
                if ("data".equals(kind)) {
                    super.visitVarInsn(Opcodes.ALOAD, 1);
                    super.visitVarInsn(Opcodes.ALOAD, 2);
                    super.visitLdcInsn(site);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "guardDataItem",
                            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Z", false);
                } else if ("hurt".equals(kind)) {

                    super.visitVarInsn(Opcodes.FLOAD, 1);

                    super.visitLdcInsn(site);

                    super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "blockHurt", "(Ljava/lang/Object;FLjava/lang/String;)Z", false);

                    super.visitJumpInsn(Opcodes.IFEQ, cont);

                    super.visitInsn(Opcodes.RETURN);

                    super.visitLabel(cont);

                    return;

                } else if ("index".equals(kind)) {
                    super.visitVarInsn(Opcodes.ALOAD, 1);
                    super.visitLdcInsn(site);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "blockIndexRemoval",
                            "(Ljava/lang/Object;Ljava/lang/String;)Z", false);
                    super.visitJumpInsn(Opcodes.IFEQ, cont);
                    super.visitInsn(Opcodes.ICONST_1);
                    super.visitInsn(Opcodes.IRETURN);
                    super.visitLabel(cont);
                    return;
                } else {
                    super.visitLdcInsn(site);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "blockRemoval",
                            "(Ljava/lang/Object;Ljava/lang/String;)Z", false);
                }
                super.visitJumpInsn(Opcodes.IFEQ, cont);
                super.visitInsn(Opcodes.RETURN);
                super.visitLabel(cont);
            }
        };
    }
    /** 字段写门：{@code PUTFIELD} → {@code LDC site; INVOKESTATIC GuardRuntime.putX(Object, T, String)}。 */
    private static final class FieldWriteVisitor extends MethodVisitor {

        private final int[] patched;
        private final String caller;
        private final String callerMethod;

        FieldWriteVisitor(MethodVisitor mv, int[] patched, String caller, String callerMethod) {
            super(Opcodes.ASM9, mv);
            this.patched = patched;
            this.caller = caller;
            this.callerMethod = callerMethod;
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            String key = owner + "." + name;
            // 读侧掩护：DataItem.value 的读取换成 GuardRuntime.readObject（掩护值）。
            // 只处理 f_135391_ —— 它的消费者都按 Object + instanceof 用，返回 Float 安全；
            // 坐标字段的读取不能这么换（下游会 checkcast 成 Vec3，换类型会 CCE）。
            if (opcode == Opcodes.GETFIELD && key.endsWith(".f_135391_")) {
                super.visitLdcInsn(key);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "readObject",
                        "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
                patched[0]++;
                return;
            }
            if (opcode == Opcodes.PUTFIELD) {
                String method = FIELD_RULES.get(key);
                if (method != null) {
                    super.visitLdcInsn(key);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, method,
                            fieldDescriptor(desc), false);
                    patched[0]++;
                    return;
                }
            }
            super.visitFieldInsn(opcode, owner, name, desc);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            String site = owner + "." + name;
            // Generic terminal-call gates.  No mod names: any mod class that calls
            // Field.set / Map.remove / Collection.remove / Iterator.remove goes through
            // GuardRuntime, which decides by "is this a protected entity/key".
            // Skip our own mod classes: their rebuild path is scoped separately and
            // must be able to put entries back.
            if (!caller.startsWith("a_silly_cat/golems_arsenal/")
                    && (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE
                        || opcode == Opcodes.INVOKESTATIC)) {
                String unsafe = unsafeGuard(owner, name, desc);
                if (unsafe != null) {
                    super.visitLdcInsn(name);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME,
                            unsafe.substring(0, unsafe.indexOf('|')),
                            unsafe.substring(unsafe.indexOf('|') + 1), false);
                    patched[0]++;
                    return;
                }
                String varHandle = varHandleGuard(owner, name, desc);
                if (varHandle != null) {
                    super.visitLdcInsn(name);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME,
                            varHandle.substring(0, varHandle.indexOf('|')),
                            varHandle.substring(varHandle.indexOf('|') + 1), false);
                    patched[0]++;
                    return;
                }
                String guard = genericCallGuard(opcode, owner, name, desc);
                if (guard != null) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, guard,
                            genericCallDescriptor(guard), false);
                    patched[0]++;
                    return;
                }
            }
            // 读侧掩护：getHealth()/getMaxHealth() 换成 GuardRuntime.readFloat（线程级判定）。
            if (opcode == Opcodes.INVOKEVIRTUAL && "()F".equals(desc)
                    && ("getHealth".equals(name) || "getMaxHealth".equals(name))) {
                super.visitLdcInsn(site);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, "readFloat",
                        "(Ljava/lang/Object;Ljava/lang/String;)F", false);
                patched[0]++;
                return;
            }
            // 统一入口门：把敏感方法调用换成带"调用方身份"的静态调用。
            // 栈中立 —— 原参数不动，只多压 caller / callerMethod / site 三个常量。
            String guard = null;
            String guardDesc = null;
            if (opcode == Opcodes.INVOKEVIRTUAL) {
                if ("setHealth".equals(name) && "(F)V".equals(desc)) {
                    guard = "callSetHealth";
                    guardDesc = "(Ljava/lang/Object;FLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V";
                } else if (("setRemoved".equals(name) || "remove".equals(name))
                        && "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V".equals(desc)) {
                    guard = "setRemoved".equals(name) ? "callSetRemoved" : "callRemove";
                    guardDesc = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V";
                } else if ("kill".equals(name) && "()V".equals(desc)) {
                    guard = "callKill";
                    guardDesc = "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V";
                } else if ("die".equals(name)
                        && "(Lnet/minecraft/world/damagesource/DamageSource;)V".equals(desc)) {
                    guard = "callDie";
                    guardDesc = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V";
                }
            }
            if (guard != null) {
                super.visitLdcInsn(caller);
                super.visitLdcInsn(callerMethod);
                super.visitLdcInsn(site);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME, guard, guardDesc, false);
                patched[0]++;
                return;
            }
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }
    }

    /** Unsafe / VarHandle 写调用点：返回 "wrapper|descriptor"，null 表示不处理。 */
    private static String unsafeGuard(String owner, String name, String desc) {
        if (!"sun/misc/Unsafe".equals(owner) && !"jdk/internal/misc/Unsafe".equals(owner)) {
            return null;
        }
        Type[] args = Type.getArgumentTypes(desc);
        if (args.length < 3 || args[0].getSort() != Type.OBJECT || args[1].getSort() != Type.LONG) {
            return null;
        }
        boolean cas = name.startsWith("compareAndSwap");
        boolean put = name.startsWith("put");
        if (!cas && !put) {
            return null;
        }
        String suffix = unsafeSuffix(args[2]);
        if (suffix == null) {
            return null;
        }
        Type[] wrapperArgs = new Type[args.length + 2];
        wrapperArgs[0] = Type.getObjectType("java/lang/Object"); // unsafe
        wrapperArgs[1] = args[0];                               // target
        wrapperArgs[2] = args[1];                               // offset
        for (int i = 2; i < args.length; i++) {
            wrapperArgs[i + 1] = args[i];
        }
        wrapperArgs[wrapperArgs.length - 1] = Type.getObjectType("java/lang/String");
        Type ret = cas ? Type.BOOLEAN_TYPE : Type.VOID_TYPE;
        String wrapper = cas ? "unsafeCompareAndSwap" + suffix : "unsafePut" + suffix;
        return wrapper + "|" + Type.getMethodDescriptor(ret, wrapperArgs);
    }

    private static String unsafeSuffix(Type type) {
        return switch (type.getSort()) {
            case Type.OBJECT, Type.ARRAY -> "Object";
            case Type.INT -> "Int";
            case Type.LONG -> "Long";
            case Type.FLOAT -> "Float";
            case Type.DOUBLE -> "Double";
            case Type.BOOLEAN -> "Boolean";
            case Type.BYTE -> "Byte";
            case Type.SHORT -> "Short";
            case Type.CHAR -> "Char";
            default -> null;
        };
    }

    private static String varHandleGuard(String owner, String name, String desc) {
        if (!"java/lang/invoke/VarHandle".equals(owner)) {
            return null;
        }
        if (("set".equals(name) || "setVolatile".equals(name)
                || "setOpaque".equals(name) || "setRelease".equals(name))
                && "([Ljava/lang/Object;)V".equals(desc)) {
            return "varHandleSet|(Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/String;)V";
        }
        if ("compareAndSet".equals(name) && "([Ljava/lang/Object;)Z".equals(desc)) {
            return "varHandleCompareAndSet|(Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/String;)Z";
        }
        if (("getAndSet".equals(name) || "getAndSetAcquire".equals(name)
                || "getAndSetRelease".equals(name)) && "([Ljava/lang/Object;)Ljava/lang/Object;".equals(desc)) {
            return "varHandleGetAndSet|(Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;";
        }
        return null;
    }

    /** Map the generic terminal calls to a GuardRuntime entry point. */
    private static String genericCallGuard(int opcode, String owner, String name, String desc) {
        if ("java/lang/reflect/Field".equals(owner) && "set".equals(name)) {
            return switch (desc) {
                case "(Ljava/lang/Object;Ljava/lang/Object;)V" -> "reflectSet";
                case "(Ljava/lang/Object;Z)V" -> "reflectSetBoolean";
                case "(Ljava/lang/Object;I)V" -> "reflectSetInt";
                case "(Ljava/lang/Object;F)V" -> "reflectSetFloat";
                case "(Ljava/lang/Object;D)V" -> "reflectSetDouble";
                case "(Ljava/lang/Object;J)V" -> "reflectSetLong";
                default -> null;
            };
        }
        if (!"remove".equals(name)) {
            return null;
        }
        if ("java/util/Iterator".equals(owner) && "()V".equals(desc)) {
            return "iteratorRemove";
        }
        if (isIntMapOwner(owner)) {
            if ("(I)Ljava/lang/Object;".equals(desc)) {
                return "mapRemoveInt";
            }
            if ("(ILjava/lang/Object;)Z".equals(desc)) {
                return "mapRemoveIntBool";
            }
        }
        if (isMapOwner(owner) && "(Ljava/lang/Object;)Ljava/lang/Object;".equals(desc)) {
            return "mapRemove";
        }
        if (isCollectionOwner(owner) && "(Ljava/lang/Object;)Z".equals(desc)) {
            return "collectionRemove";
        }
        return null;
    }

    private static String genericCallDescriptor(String guard) {
        return switch (guard) {
            case "reflectSet" -> "(Ljava/lang/reflect/Field;Ljava/lang/Object;Ljava/lang/Object;)V";
            case "reflectSetBoolean" -> "(Ljava/lang/reflect/Field;Ljava/lang/Object;Z)V";
            case "reflectSetInt" -> "(Ljava/lang/reflect/Field;Ljava/lang/Object;I)V";
            case "reflectSetFloat" -> "(Ljava/lang/reflect/Field;Ljava/lang/Object;F)V";
            case "reflectSetDouble" -> "(Ljava/lang/reflect/Field;Ljava/lang/Object;D)V";
            case "reflectSetLong" -> "(Ljava/lang/reflect/Field;Ljava/lang/Object;J)V";
            case "mapRemove" -> "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
            case "mapRemoveInt" -> "(Ljava/lang/Object;I)Ljava/lang/Object;";
            case "mapRemoveIntBool" -> "(Ljava/lang/Object;ILjava/lang/Object;)Z";
            case "collectionRemove" -> "(Ljava/lang/Object;Ljava/lang/Object;)Z";
            case "iteratorRemove" -> "(Ljava/lang/Object;)V";
            default -> "(Ljava/lang/Object;)V";
        };
    }

    private static boolean isIntMapOwner(String owner) {
        return owner != null && (owner.endsWith("Int2ObjectMap")
                || owner.endsWith("Int2ObjectOpenHashMap")
                || owner.endsWith("Int2ObjectLinkedOpenHashMap"));
    }

    private static boolean isMapOwner(String owner) {
        return "java/util/Map".equals(owner)
                || "java/util/HashMap".equals(owner)
                || "java/util/LinkedHashMap".equals(owner)
                || owner.endsWith("Int2ObjectMap");
    }

    private static boolean isCollectionOwner(String owner) {
        return "java/util/Collection".equals(owner)
                || "java/util/List".equals(owner)
                || "java/util/Set".equals(owner)
                || "java/util/ArrayList".equals(owner)
                || "java/util/HashSet".equals(owner);
    }

    /** 栈上的实际类型：布尔/字节/字符/短整型都退化成 I。 */
    private static String fieldDescriptor(String desc) {        char c = desc.charAt(0);
        String type = switch (c) {
            case 'Z', 'B', 'C', 'S', 'I' -> "I";
            case 'J' -> "J";
            case 'F' -> "F";
            case 'D' -> "D";
            default -> "Ljava/lang/Object;";
        };
        return "(Ljava/lang/Object;" + type + "Ljava/lang/String;)V";
    }

    /** 用转换目标自己的 ClassLoader 解析继承链；解析不到时退回 Object（保持旧行为）。 */
    private static String commonSuperClass(String a, String b, ClassLoader loader) {
        if (a == null || b == null || a.equals(b)) {
            return a == null ? "java/lang/Object" : a;
        }
        String cacheKey = System.identityHashCode(loader) + "|" + a + "|" + b;
        String cached = COMMON_SUPER_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String result = "java/lang/Object";
        try {
            Class<?> ca = resolveClass(a, loader);
            Class<?> cb = resolveClass(b, loader);
            if (ca.isAssignableFrom(cb)) {
                result = a;
            } else if (cb.isAssignableFrom(ca)) {
                result = b;
            } else if (ca.isInterface() || cb.isInterface()) {
                result = "java/lang/Object";
            } else {
                Class<?> c = ca;
                do {
                    c = c.getSuperclass();
                } while (c != null && !c.isAssignableFrom(cb));
                if (c != null) {
                    result = c.getName().replace('.', '/');
                }
            }
        } catch (Throwable ignored) {
        }
        COMMON_SUPER_CACHE.put(cacheKey, result);
        return result;
    }

    private static Class<?> resolveClass(String internalName, ClassLoader loader) throws ClassNotFoundException {
        String key = System.identityHashCode(loader) + "|" + internalName;
        Class<?> cached = CLASS_RESOLVE_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Class<?> resolved = Class.forName(internalName.replace('/', '.'), false, loader);
        CLASS_RESOLVE_CACHE.put(key, resolved);
        return resolved;
    }

    /** Slot of the first parameter whose type lives in net/minecraft/world/entity/. */
    private static int firstEntityArg(String desc, int access) {
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        int slot = isStatic ? 0 : 1;
        Type[] args = Type.getArgumentTypes(desc);
        for (Type type : args) {
            if (type.getSort() == Type.OBJECT) {
                String name = type.getInternalName();
                // Player 也是 net/minecraft/world/entity/ 下；VanSh.onLeftClickEntity 的
                // 参数顺序是 (ItemStack, Player, Entity)，不能把 Player 当目标。
                if (name.startsWith("net/minecraft/world/entity/")
                        && !name.startsWith("net/minecraft/world/entity/player/")) {
                    return slot;
                }
            }
            slot += type.getSize();
        }
        return -1;
    }

    private static void emitDefaultReturn(MethodVisitor mv, Type ret) {
        switch (ret.getSort()) {
            case Type.VOID -> mv.visitInsn(Opcodes.RETURN);
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.IRETURN);
            }
            case Type.LONG -> {
                mv.visitInsn(Opcodes.LCONST_0);
                mv.visitInsn(Opcodes.LRETURN);
            }
            case Type.FLOAT -> {
                mv.visitInsn(Opcodes.FCONST_0);
                mv.visitInsn(Opcodes.FRETURN);
            }
            case Type.DOUBLE -> {
                mv.visitInsn(Opcodes.DCONST_0);
                mv.visitInsn(Opcodes.DRETURN);
            }
            default -> {
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);
            }
        }
    }
}
