package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class OutfitStatusDTO {

    private boolean enabled;

    private List<OutfitItemDTO> items;

    /** 已拥有且在有效期内的装扮 key 列表（语义保持不变，兼容老版本小程序） */
    private List<String> owned;

    /**
     * 已拥有装扮的明细（含到期时间），与 {@link #owned} 一一对应。
     *
     * <p>新增字段：限时装扮需要让用户看到「剩余几天」。老版本小程序不读它，
     * 也不会因为多出这个字段而出错。</p>
     */
    private List<OwnedOutfitDetailDTO> ownedDetails;

    /**
     * 已拥有但装扮商店配置中已不存在的装扮 key（站长删除/改名后出现）。
     *
     * <p>这类装扮以配置为数据源渲染，若不单独列出，用户会「花钱买过的东西凭空消失」，
     * 既看不到也穿不上、还以为是系统出错。前端据此提示「已下架」。</p>
     */
    private List<String> orphanOwned;

    private String avatarFrame;

    private String nickFrame;

    private String theme;
}
