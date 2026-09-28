package pro.uhalo.uni.scheme;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

import static pro.uhalo.uni.constants.Constants.BASIC_DOMAIN_NAME;
import static pro.uhalo.uni.constants.Constants.PLUGIN_API_VERSION;

/**
 * 用户的订阅消息额度与开关：一个用户一条记录（资源名由 openid 派生）。
 *
 * <p><b>为什么必须自己存额度</b>：微信侧不提供「该用户还有几条订阅额度」的查询接口。
 * 一次性订阅的规则是「一次授权对应一条下发机会」，而授权结果只有前端
 * {@code wx.requestSubscribeMessage} 的返回值知道——所以「额度 +1」只能由
 * 前端上报、由服务端记账，发送成功后再扣减。</p>
 *
 * <p>两种消息各自计数（{@link #newPostQuota} / {@link #aiDigestQuota}）：它们的模板与
 * 触发场景不同，用户可能只想收其中一种。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = BASIC_DOMAIN_NAME, version = PLUGIN_API_VERSION,
    kind = "PushSubscription", plural = "pushSubscriptions", singular = "pushSubscription")
public class PushSubscription extends AbstractExtension {

    private String wxOpenid;

    /** 用户总开关：关掉后不再给他发任何订阅消息（额度保留） */
    private Boolean enabled;

    /** 新文章提醒剩余额度（一次授权 = 1 条；发送成功扣 1，被拒清零） */
    private Integer newPostQuota;

    /** AI 简报就绪提醒剩余额度 */
    private Integer aiDigestQuota;

    /** 最近一次成功推送时间（后台观察用） */
    private Instant lastPushAt;

    private Instant createdAt;

    private Instant updatedAt;
}
