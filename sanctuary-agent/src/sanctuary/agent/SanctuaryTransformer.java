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
    private static final String[] HOSTILE = {
            "com/luoee/arcanevortex/", "com/csdy/jzyy/", "com/csdy/tcondiadema/",
            "com/mizi/miztinker/", "com/momosensei/momotinker/", "com/wzz/witherzilla/",
            "plz/lizi/supersteve/", "net/diexv/", "flashfur/omnimobs/", "dev/othinus/toaru/"
    };

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

    /** 方法入口探针：这些类的每个方法进来都打一行（每个 site 只一次）。 */
    private static final java.util.Set<String> TRACE = new java.util.HashSet<>(java.util.List.of(
            "com/luoee/arcanevortex/Help/HealthModifyHelp/HealthModify",
            "com/luoee/arcanevortex/Help/EntityKillerHelp",
            "com/luoee/arcanevortex/Content/Item/EntityRemover/RemoveHelp/MainRemove",
            "com/luoee/arcanevortex/Content/Item/VanSh/VanShCrusher",
            "com/luoee/arcanevortex/Content/Item/VanSh/VanShTool/VanShRadiantSun"
    ));

    /**
     * 直接"废掉"的方法（整类）。
     *
     * <p>2026-11-10 实测：AV 的 `ClassRestoreManager` 把原版字节缓存在 **native** 里
     * （`NativeAgentBridge.nativeRegisterRestoreClass` / `nativeRetransformRestoreTargets`），
     * 每 ~0.4 秒把我们的目标类恢复一次（2 分钟里 VanShRadiantSun 被恢复 367 次）。
     * 在 Java 层重打永远追不上，所以直接把它的"注册/重放"入口做成空方法。</p>
     */
    private static final Map<String, String[]> NEUTRALIZE = new HashMap<>();

    static {
        NEUTRALIZE.put("com/luoee/arcanevortex/NativeAgent/ClassRestoreManager",
                new String[]{"register", "retransform", "prime"});
        NEUTRALIZE.put("com/luoee/arcanevortex/NativeAgent/TailGuard",
                new String[]{"runLoop", "assertTail", "start", "acquire", "loop", "burst", "kick"});
        NEUTRALIZE.put("com/luoee/arcanevortex/NativeAgent/MemoryDllLoader",
                new String[]{"load", "map", "inject", "resolve"});
        NEUTRALIZE.put("com/luoee/arcanevortex/NativeAgent/OriginalClassProvider",
                new String[]{"register", "provide", "restore"});
    }

    

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
            "setRemoved", "remove", "kill", "die"
    };
    /** 类层次缓存，避免每条帧都 Class.forName。 */
    private static final Map<String, Class<?>> CLASS_RESOLVE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> COMMON_SUPER_CACHE = new ConcurrentHashMap<>();

    public SanctuaryTransformer(boolean patchObjectReturns) {
        this.patchObjectReturns = patchObjectReturns;

        // ArcaneVortex 0.7.10 — hard erase / purge family.
        // HardErase.erase/eraseLegacy 返回 Result（对象），必须显式允许 objectReturn；
        // 拦下时返回 null，调用方会失败，但实体不会被清。stripEntity/purgeSections 仍是原规则。
        rules.put("com/luoee/arcanevortex/Content/Item/EntityRemover/Purge/HardErase",
                new Rule(false, true, "stripEntity", "purgeSections", "purgeKnownUuids", "erase"));
        // 2026-11-12 修正：HardRemove 实际在 RemoveHelp 包，不在 Purge 包。
        rules.put("com/luoee/arcanevortex/Content/Item/EntityRemover/RemoveHelp/HardRemove",
                new Rule(false, "purge"));
        // 2026-11-12 新增：MainRemove 是 AV 的"拆索引/回调"主入口，之前只有字段门。
        rules.put("com/luoee/arcanevortex/Content/Item/EntityRemover/RemoveHelp/MainRemove",
                new Rule(false, "remove", "forceRemove", "clearEntity", "chunkMapRemove", "deleteFrom"));
        // 2026-11-14：VanSh Shift+左键走 EntityKillerHelp.tickDeath，不经过普通 hurt 链。
        // 这组方法都直接拿 Entity 当第一参，入口必须挡住。
        rules.put("com/luoee/arcanevortex/Help/EntityKillerHelp",
                new Rule(false, "tickDeath", "killEntity", "kill", "remove",
                        "AsynchronousRemove", "setRemoved", "removeServerLevelEntity",
                        "deleteEntity", "setEntityPos", "setEntityPosReflection"));
        rules.put("com/luoee/arcanevortex/NativeAgent/NativeAgentHealthMethod",
                new Rule(true, "forceSetHealth", "forceSetMaxHealth"));

        // toaru — execution / world removal chain.
        rules.put("dev/othinus/toaru/GungnirExecution",
                new Rule(false, "execute", "erase", "performExecution", "removeNonPlayer",
                        "finishRemoval", "finishCollateralRemoval", "settleAndRemove"));
        rules.put("dev/othinus/toaru/GungnirArcaneCompat",
                new Rule(false, "finishNativeHealth", "suppressTerminalDefense"));
        // 2026-11-12：`/ga guard toaru` 的 ToaruProbe 直接调 DamageService.strike，
        // 不走 GungnirExecution.execute，所以只补 execute 入口时探针会"又有用"。
        // 实际实现是 GungnirDamageApi$Authority；把 strike 入口也纳入同一套门。
        rules.put("dev/othinus/toaru/GungnirDamageApi$Authority",
                new Rule(false, true, "strike"));

        // 2026-11-12：OrangeSummer 走的是 VanShCrusher.remove 这一条，不是 execute；
        // 之前只有字段门，没有方法入口，所以 raw 被写成 0、实体被清掉。
        rules.put("com/luoee/arcanevortex/Content/Item/Sword/OrangeSummer/OrangeSummer",
                new Rule(false, "remove"));
        rules.put("com/luoee/arcanevortex/Content/Item/VanSh/VanShCrusher",
                new Rule(false, "remove", "forceRemove", "chunkMapRemove"));
        rules.put("com/luoee/arcanevortex/Help/HealthModifyHelp/HealthModify",
                new Rule(false, "forceModify", "entityDataModify", "tagModify",
                        "attributeModifier", "setHealth"));
        rules.put("com/luoee/arcanevortex/Help/DropLootHelp",
                new Rule(false, "dropLoot"));

        // 2026-11-10：实测 VanShRadiantSun 被 AV 反复恢复（5 秒一轮里被重打 64 次），
        // 恢复窗口内它的 remove() 会把 DATA_HEALTH 写成 0.1。字段门只在补丁活着时有效，
        // 所以这里直接堵方法入口：只要目标受保护，remove()/m_5551_() 直接 return。
        rules.put("com/luoee/arcanevortex/Content/Item/VanSh/VanShTool/VanShRadiantSun",
                new Rule(false, "remove"));
        rules.put("com/luoee/arcanevortex/Content/Item/VanSh/VanShTool/VanSh",
                new Rule(false, "remove", "m_5551_"));
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

    /** 栈上的实际类型：布尔/字节/字符/短整型都退化成 I。 */
    private static String fieldDescriptor(String desc) {
        char c = desc.charAt(0);
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
                if (name.startsWith("net/minecraft/world/entity/")) {
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
