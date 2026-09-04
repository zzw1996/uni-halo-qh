package pro.uhalo.uni.scheme;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import static pro.uhalo.uni.constants.Constants.BASIC_DOMAIN_NAME;
import static pro.uhalo.uni.constants.Constants.PLUGIN_API_VERSION;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = BASIC_DOMAIN_NAME, version = PLUGIN_API_VERSION,
    kind = "UserPoints", plural = "userPoints", singular = "userPoint")
public class UserPoints extends AbstractExtension {

    private String wxUserId;

    private String wxOpenid;

    private Long balance;

    /** 当前连续签到天数 */
    private Integer consecutiveDays;

    /** 最近一次签到日期，格式 yyyy-MM-dd（Asia/Shanghai） */
    private String lastSignInDate;
}
