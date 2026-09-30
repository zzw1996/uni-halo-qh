package pro.uhalo.uni.services;

import pro.uhalo.uni.scheme.WxUser;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * 特权身份判定（站点所有者 / 管理员）。
 *
 * <p><b>为什么要收口到一处</b>：站点所有者与管理员应当豁免一切「付费 / 门槛」限制——
 * 他们本来就是这个站的主人，让自己给自己付钱、或者上架前无法自测功能都是荒谬的。
 * 但豁免判定一度散落在各功能里各写一份（投稿、付费文章、订阅墙…），
 * 于是每加一个限制性功能就漏一次，表现是「站长明明有权限却看不到入口 / 用不了功能」。</p>
 *
 * <p>两条判定（命中任意一条即为特权）：</p>
 * <ol>
 *   <li>微信 openid 与后台声明的博客所有者一致（{@code wxAppConfig.ownerOpenid}）；</li>
 *   <li>该用户的 Halo 角色包含 {@code super-role} 或 {@code admin}。</li>
 * </ol>
 *
 * <p><b>失败方向</b>：读不到用户或角色时返回 {@code false}（不放行）。
 * 这与「订阅墙 / 付费墙」的 fail-open 相反，理由不同：那边失败放行只是少赚一次钱，
 * 这边失败放行等于把付费权益白送给任何人。</p>
 */
public interface PrivilegeService {

    /** 按 openid 判定（内部自行取用户与角色；带短时缓存） */
    Mono<Boolean> isPrivileged(String openid);

    /**
     * 用已取好的用户与角色判定。
     *
     * <p>调用方已经查过 {@link WxUser} 与角色时用这个重载，省掉重复查询。</p>
     */
    Mono<Boolean> isPrivileged(WxUser user, Set<String> roles);

    /**
     * 读取某 Halo 用户的角色集合。
     *
     * <p>优先官方 {@code RoleService}，取不到再读 User 上的角色注解；
     * 都拿不到时返回空集（不是异常）。</p>
     */
    Mono<Set<String>> rolesOf(String haloUserName);

    /** 清掉某用户的特权判定缓存（角色刚变更、或断言测试用） */
    void evict(String openid);
}
