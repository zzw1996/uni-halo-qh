package pro.uhalo.uni.scheme;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

import static pro.uhalo.uni.constants.Constants.BASIC_DOMAIN_NAME;
import static pro.uhalo.uni.constants.Constants.PLUGIN_API_VERSION;

/**
 * 一次推送尝试的记录：既是幂等键，也是审计（后台看成功率与失败原因）。
 *
 * <p>资源名 `push-&lt;sha256(openid:类型:业务键)&gt;`，业务键例如
 * 「文章名」或「2026-09-24」——<b>同一件事不会给同一个人推两次</b>，
 * 重复触发（定时任务重跑、水位回退）只会撞名，不会重复打扰用户。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = BASIC_DOMAIN_NAME, version = PLUGIN_API_VERSION,
    kind = "PushRecord", plural = "pushRecords", singular = "pushRecord")
public class PushRecord extends AbstractExtension {

    private String wxOpenid;

    /** 消息类型：newPost / aiDigest（见 PushConfigHelper.KIND_*） */
    private String kind;

    /** 业务键：文章名或日期，保证同一件事只推一次 */
    private String bizKey;

    /** 点击消息跳转的小程序页面（不含前导斜杠） */
    private String page;

    /** SENT / FAILED / SKIPPED_NO_QUOTA */
    private String status;

    /** 微信返回的 errcode（0 或空表示成功） */
    private Integer errorCode;

    private String message;

    private Instant sentAt;
}
