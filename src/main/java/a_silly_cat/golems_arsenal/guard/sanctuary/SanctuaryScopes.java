package a_silly_cat.golems_arsenal.guard.sanctuary;

import net.minecraft.world.entity.Entity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

/**
 * 写入准入用的作用域开关。
 *
 * <p>旧模型在 {@code setHealth} 里用 {@link StackWalker} 认调用者类名，谁不在白名单里就拒。
 * 那个模型的天花板是"永远补不全"：任何一个模组从自己的方法里写血，栈里就不会出现我们
 * 认识的类名。现在改成<b>作用域</b>：</p>
 *
 * <ul>
 *   <li>{@link #pushHurt}/{@link #popHurt}/{@link #admit}：一次 {@code LivingEntity#hurt}
 *       的窗口。只有窗口里、且这次 hurt 被 {@link Sanctuary} 放行过，窗口才算"已准入"。
 *       普通伤害的实际写血发生在 {@code hurt} 调用栈内部，所以窗口一开就够；不需要再看栈。</li>
 *   <li>{@link #enterDeath}/{@link #exitDeath}：死亡栈。{@code die()}/{@code kill()} 里
 *       把血写成 0、写 guardedData 都属于这一类。</li>
 *   <li>{@link #enterLoad}/{@link #exitLoad}：读档（{@code readAdditionalSaveData}）。</li>
 *   <li>{@link #callInternal}：本模组自己的写。调用点显式声明，不再靠类名猜。</li>
 * </ul>
 *
 * <p>所有作用域都是线程本地的：写入和伤害都在服务器线程上同步发生，嵌套（梦终的
 * re-hurt、分摊到另一只傀儡）用栈/计数处理。</p>
 */
public final class SanctuaryScopes {

    private static final ThreadLocal<Deque<HurtFrame>> HURT =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<int[]> DEPTH =
            ThreadLocal.withInitial(() -> new int[3]);

    private static final int DEATH = 0;
    private static final int LOAD = 1;
    private static final int INTERNAL = 2;

    private SanctuaryScopes() {
    }

    // ------------------------------------------------------------------ hurt 窗口

    /** {@code LivingEntity#hurt} HEAD：开一个窗口。 */
    public static void pushHurt(Entity entity) {
        HURT.get().push(new HurtFrame(entity));
    }

    /** {@code LivingEntity#hurt} RETURN：关窗口。嵌套时只弹最上面一层。 */
    public static void popHurt() {
        Deque<HurtFrame> stack = HURT.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
    }

    /** {@link Sanctuary} 放行这次 hurt 时调用：把当前窗口标成"已准入"。 */
    public static void admit(Entity entity) {
        HurtFrame frame = HURT.get().peek();
        if (frame != null && frame.entity == entity) {
            frame.admitted = true;
        }
    }

    /** 当前窗口是否属于这个实体、且已经被准入。 */
    public static boolean isAdmitted(Entity entity) {
        HurtFrame frame = HURT.get().peek();
        return frame != null && frame.entity == entity && frame.admitted;
    }

    /** 当前是否处在这个实体的 hurt 窗口里（不管准没准入）。 */
    public static boolean inHurt(Entity entity) {
        HurtFrame frame = HURT.get().peek();
        return frame != null && frame.entity == entity;
    }

    // ------------------------------------------------------------------ 死亡栈

    public static void enterDeath() {
        DEPTH.get()[DEATH]++;
    }

    public static void exitDeath() {
        int[] depth = DEPTH.get();
        if (depth[DEATH] > 0) {
            depth[DEATH]--;
        }
    }

    public static boolean inDeath() {
        return DEPTH.get()[DEATH] > 0;
    }

    // ------------------------------------------------------------------ 读档

    public static void enterLoad() {
        DEPTH.get()[LOAD]++;
    }

    public static void exitLoad() {
        int[] depth = DEPTH.get();
        if (depth[LOAD] > 0) {
            depth[LOAD]--;
        }
    }

    public static boolean inLoad() {
        return DEPTH.get()[LOAD] > 0;
    }

    // ------------------------------------------------------------------ 本模组内部写

    public static void enterInternal() {
        DEPTH.get()[INTERNAL]++;
    }

    public static void exitInternal() {
        int[] depth = DEPTH.get();
        if (depth[INTERNAL] > 0) {
            depth[INTERNAL]--;
        }
    }

    public static boolean inInternal() {
        return DEPTH.get()[INTERNAL] > 0;
    }

    /** 显式声明"这一段写血是我们自己的"。异常也会正确退出。 */
    public static void runInternal(Runnable body) {
        enterInternal();
        try {
            body.run();
        } finally {
            exitInternal();
        }
    }

    /** {@link #runInternal(Runnable)} 的带返回值版本。 */
    public static <T> T callInternal(Supplier<T> body) {
        enterInternal();
        try {
            return body.get();
        } finally {
            exitInternal();
        }
    }

    // ------------------------------------------------------------------ 诊断

    public static String status() {
        int[] depth = DEPTH.get();
        Deque<HurtFrame> stack = HURT.get();
        return "hurtDepth=" + stack.size()
                + " admitted=" + (stack.peek() != null && stack.peek().admitted)
                + " death=" + depth[DEATH]
                + " load=" + depth[LOAD]
                + " internal=" + depth[INTERNAL];
    }

    private static final class HurtFrame {
        final Entity entity;
        boolean admitted;

        HurtFrame(Entity entity) {
            this.entity = entity;
        }
    }
}
