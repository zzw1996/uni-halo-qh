package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 已拥有装扮的明细（含有效期）。
 *
 * <p>与 {@code OutfitStatusDTO.owned}（仅 key 列表）并存：{@code owned} 保持原语义不变，
 * 老版本小程序继续可用；新版本读取本字段即可展示「剩余 N 天 / 永久」。
 * 插件与小程序是独立发版的，新增字段必须可缺省，不能改已有字段的形状。</p>
 */
@Data
@Builder
public class OwnedOutfitDetailDTO {

    private String key;

    /** 到期时间（epoch 秒）；{@code null} 表示永久 */
    private Long expireAt;

    /** 是否永久拥有（等价于 expireAt == null，前端无需自行判空） */
    private boolean permanent;
}
