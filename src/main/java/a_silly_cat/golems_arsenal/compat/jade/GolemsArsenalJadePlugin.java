package a_silly_cat.golems_arsenal.compat.jade;

import a_silly_cat.golems_arsenal.base.entity.RocketEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * 玉（Jade）插件入口 —— 把「火箭储罐里的气体」显示在准星提示里。
 *
 * <p>形式完全照傀儡装配的 {@code JadeGolemPlugin}：
 * <pre>
 *   &#64;WailaPlugin
 *   class JadeGolemPlugin implements IWailaPlugin {
 *       public void registerClient(IWailaClientRegistration r) {
 *           r.registerEntityComponent(GolemMaidModelProvider.INSTANCE, HumanoidGolemEntity.class);
 *       }
 *       public void register(IWailaCommonRegistration r) { }   // 空的
 *   }
 * </pre>
 * 我们同样只实现 {@code registerClient}——因为需要的数据（气体种类 + 储量）已经在
 * 实体的同步数据里，不需要服务端再送一遍。
 *
 * <p><b>玉是可选的</b>：这个类只在玉存在、由玉自己扫描 {@code @WailaPlugin} 时才会被加载；
 * 没装玉时它是个永远不会被碰到的类，不影响任何东西。
 */
@WailaPlugin
public class GolemsArsenalJadePlugin implements IWailaPlugin {

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerEntityComponent(RocketGasJadeProvider.INSTANCE, RocketEntity.class);
    }
}
