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

    private Instant createTime;
}
