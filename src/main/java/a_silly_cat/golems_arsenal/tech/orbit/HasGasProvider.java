package a_silly_cat.golems_arsenal.tech.orbit;

import org.jetbrains.annotations.Nullable;

/**
 * "持有一个气体储罐提供者"的实体接口 —— **刻意不引用任何 Mekanism 的类**。
 *
 * <p><b>为什么需要这个接口</b>：{@code RocketEntity} 不能引用 MEK 的类（没装 MEK 时也要能加载），
 * 但它必须把储罐提供者存起来供 {@link FuelBridge} 使用。直接用
 * {@code RocketGasProvider} 当字段类型就会把 MEK 拖进实体的类签名里。
 *
 * <p>所以这里只声明一个 {@code Object} 存取对：
 * <ul>
 *   <li>实体侧：字段是 {@code Object}，编译期与 MEK 无关；</li>
 *   <li>桥接侧（{@link FuelBridge}）：拿到后强转成 {@code RocketGasProvider}——</li>
 * </ul>
 * 唯一往这个字段里写值的地方就是 {@code FuelBridge.attach}，所以强转是安全的。
 */
public interface HasGasProvider {

    /** 储罐提供者。没装 MEK、或能力还没挂上时是 null。 */
    @Nullable
    Object gasProvider();

    /** 由 {@link FuelBridge} 在挂载能力时写入。 */
    void setGasProvider(@Nullable Object provider);

    /**
     * 储罐内容变化的回调（作为 Mekanism 储罐的 {@code IContentsListener}）。
     *
     * <p>由 {@link FuelBridge} 在构造储罐时传进去；实体自己负责"变了要做什么"
     * （这里是标脏，tick 里统一同步给客户端）。
     */
    void markFuelDirty();
}
