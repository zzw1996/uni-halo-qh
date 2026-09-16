package pro.uhalo.uni.scheme;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

import static pro.uhalo.uni.constants.Constants.BASIC_DOMAIN_NAME;
import static pro.uhalo.uni.constants.Constants.PLUGIN_API_VERSION;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = BASIC_DOMAIN_NAME, version = PLUGIN_API_VERSION,
    kind = "PointsRecord", plural = "pointsRecords", singular = "pointsRecord")
public class PointsRecord extends AbstractExtension {

    private String wxOpenid;

    private String action;

    private Integer points;

    private Long balance;

    private String description;

    private String refName;

    /** 幂等键：openid + action + ref，用于防止同一文章同行为重复计分 */
    private String earnKey;

    /** 关联的小程序用户 ID，便于后台展示时准确关联用户 */
    private String wxUserId;

    /**
     * 本次变动的阅读券张数：正=获得，负=消耗，null=与券无关。
     *
     * <p>为什么不复用 {@link #points}：券张数不是积分，混进 points 会污染「累计获得积分」
     * （等级、称号、榜单都基于它求和），一次买 5 张券会被当成 5 积分。
     * 单独一个字段才能算出真实的「券发放 / 券消耗」比，
     * 而这是判断赠券力度是否过头的唯一依据。</p>
     */
    private Integer tickets;

    private Instant createTime;
}
