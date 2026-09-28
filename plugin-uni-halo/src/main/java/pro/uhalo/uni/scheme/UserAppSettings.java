package pro.uhalo.uni.scheme;

import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.time.Instant;

import static pro.uhalo.uni.constants.Constants.BASIC_DOMAIN_NAME;
import static pro.uhalo.uni.constants.Constants.PLUGIN_API_VERSION;

/**
 * 用户级「应用设置」的服务端副本：一个用户一条记录。
 *
 * <p>存的是设置页能改的那几项（底栏显示、外观模式、首页布局、卡片样式等），
 * 目的是「换设备 / 清缓存 / 更新重启后设置还在」。键清单与取值校验见
 * {@link pro.uhalo.uni.utils.AppSettingsCatalog}——这个类只负责存放，不负责判断合法性。</p>
 *
 * <p>资源名由 openid 派生（{@code uas-…}），因此同一用户永远只有一条、重复提交天然幂等
 * （与订阅、积分账户、AI 定制内容偏好同一套做法）。</p>
 *
 * <p><b>为什么 payload 是字符串而不是 Map</b>：Halo 的扩展序列化对 {@code Object} 类型的字段
 * 不友好（反序列化时拿不到具体类型）。存成服务端自己生成的 JSON 字符串，读取时按
 * {@code Map<String,Object>} 解析，读写两侧的行为都是确定的；而且写入的内容一定经过
 * 白名单清洗，不存在「把客户端原文原样存下」的路径。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = BASIC_DOMAIN_NAME, version = PLUGIN_API_VERSION,
    kind = "UserAppSettings", plural = "userAppSettings",
    singular = "userAppSettings")
public class UserAppSettings extends AbstractExtension {

    private String wxOpenid;

    /**
     * 扁平点号键 → 标量的 JSON 对象字符串，例如
     * {@code {"tabbar.show.rank":false,"appearance.mode":"dark"}}。
     *
     * <p>只含白名单内的键；由服务端生成，不接受客户端原文。</p>
     */
    private String payload;

    /** 写入次数（每次成功合并 +1）：客户端据此判断「服务端副本是否比自己新」 */
    private Long revision;

    private Instant updatedAt;
}
