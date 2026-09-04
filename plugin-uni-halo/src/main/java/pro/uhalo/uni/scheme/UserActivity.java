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
    kind = "UserActivity", plural = "userActivities", singular = "userActivity")
public class UserActivity extends AbstractExtension {

    private String wxUserId;

    private String wxOpenid;

    private String type;

    /** post（默认）或 moment */
    private String contentType;

    private String articleName;

    private String title;

    private String cover;

    private String excerpt;

    private Instant publishTime;

    private Integer visit;

    private Instant recordTime;

    /** wxOpenid + type + articleName 唯一键，用于单字段索引查询 */
    private String activityKey;
}
