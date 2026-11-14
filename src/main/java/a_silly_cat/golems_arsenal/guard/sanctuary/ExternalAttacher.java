package a_silly_cat.golems_arsenal.guard.sanctuary;

/**
 * 外部附加器：在<b>另一个 JVM</b> 里运行，attach 到游戏进程再 {@code loadAgent}。
 *
 * <p>为什么需要它（2026-11-08 实测）：</p>
 * <ul>
 *   <li>{@code VirtualMachine.attach(自己的 pid)} 需要 {@code jdk.attach.allowAttachSelf=true}，
 *       而这个属性是 <b>JVM 启动时</b>读进 saved properties 的；
 *       运行期 {@code System.setProperty} <b>无效</b>。</li>
 *   <li>失败时 JDK 把所有 provider 的异常吞掉，只抛
 *       {@code IOException: Can not attach to current VM}，看不出真实原因。</li>
 *   <li>实测：运行期设属性 → 失败；启动加 {@code -D} → 成功；<b>外部进程 attach → 成功（不需要任何参数）</b>。</li>
 * </ul>
 *
 * <p>所以自 attach 失败时，mod 会把本类从自己的 jar 里释放到临时目录，用同一个 java 起一个子进程，
 * 由它 attach 游戏进程。这样不需要改启动器的 JVM 参数。</p>
 *
 * <p>本类<b>不能</b>引用任何 Minecraft/Forge 类型：它跑在裸 JVM 里。</p>
 *
 * <p>用法：{@code java -cp <临时目录> a_silly_cat.golems_arsenal.guard.sanctuary.ExternalAttacher <pid> <agent.jar>}</p>
 */
public final class ExternalAttacher {

    private ExternalAttacher() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("EXTERNAL_ATTACH_BAD_ARGS need <pid> <agentJar>");
            Runtime.getRuntime().halt(2);
            return;
        }
        String pid = args[0];
        String agentJar = args[1];
        try {
            Class<?> vmClass = Class.forName("com.sun.tools.attach.VirtualMachine");
            Object vm = vmClass.getMethod("attach", String.class).invoke(null, pid);
            try {
                vmClass.getMethod("loadAgent", String.class, String.class).invoke(vm, agentJar, "");
            } finally {
                try {
                    vmClass.getMethod("detach").invoke(vm);
                } catch (Throwable ignored) {
                }
            }
            System.out.println("EXTERNAL_ATTACH_OK pid=" + pid);
            Runtime.getRuntime().halt(0);
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            System.out.println("EXTERNAL_ATTACH_FAILED "
                    + cause.getClass().getName() + ": " + cause.getMessage());
            Runtime.getRuntime().halt(1);
        }
    }
}
