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
    kind = "WxUser", plural = "wxUsers", singular = "wxUser")
public class WxUser extends AbstractExtension {

    private String id;

    private String openid;

    private String sessionKey;

    private String unionid;

    private String nickname;

    private String avatarUrl;

    private Boolean profileComplete;

    private Integer profileModifyCount;

    private String haloUserName;

    /** 本人邀请码（注册时惰性生成，供好友分享注册） */
    private String inviteCode;

    /** 邀请我注册的人（openid，未受邀则为空） */
    private String inviterOpenid;

    /** 最近一次生成邀请海报时间（null=未生成过海报） */
    private Instant posterGeneratedAt;

    /** 邀请海报图片地址（上传到后台附件后回填，null=未上传或生成失败） */
    private String posterImageUrl;
}
