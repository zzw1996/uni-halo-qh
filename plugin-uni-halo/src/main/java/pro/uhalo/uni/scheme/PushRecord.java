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

    /**
     * 消息类型：newPost / aiDigest（见 PushConfigHelper.KIND_*）。
     *
     * <p><b>字段名不能叫 {@code kind}</b>：{@code AbstractExtension} 自带
     * {@code getKind()/setKind()}，而 Halo 把扩展序列化成 {@code Unstructured} 时
     * 读的就是实例的 {@code getKind()}（{@code groupVersionKind()} 同源）。
     * 子类声明 {@code kind} 字段会让 Lombok 生成的访问器覆盖基类方法，
     * 于是 Halo 拿消息类型（{@code newPost}）当扩展类型去查 scheme，
     * 写入直接失败：{@code Scheme not found for uni.uhalo.pro/v1alpha1/newPost}。</p>
     *
     * <p>这个坑在本仓库里真实发生过——2026-10 加公众号功能时由 {@code MpIssue} 触发，
     * 回头发现 {@code PushRecord} 一直在踩，只是它的写入错误被 {@code onErrorResume}
     * 吞成了「推送记录已存在，跳过重复写入」的 info 日志，于是表现成
     * 「推送记录表里什么都没有、幂等静默失效（同一件事可能被推两次）」。
     * 护栏测试见 {@code HaloSchemeFieldGuardTest}。</p>
     */
    private String sourceKind;

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
