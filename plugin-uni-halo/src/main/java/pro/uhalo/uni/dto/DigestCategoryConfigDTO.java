package pro.uhalo.uni.dto;

/**
 * 日报分类**配置**条目（后台「AI 日报 → 分类清单」里的一行）。
 *
 * <p>与 {@link DigestCategoryDTO}（对外下发用，含篇数统计）区分开：
 * 这个是站长配置的输入，字段是「AI 怎么判、生成时怎么落」。</p>
 *
 * @param key            英文标识：写进 AI prompt（AI 只在清单内选），也是自动创建站点分类时的 slug
 * @param label          中文显示名：日报正文分组小标题、自动创建分类时的 displayName
 * @param emoji          展示图标（留空用内置兜底或 📝）
 * @param description    给 AI 的判定说明（留空用 label 兜底）
 * @param targetCategory 可选：映射到的**已有**站点分类名（仅 {@code categoryMode=map} 生效；
 *                       只匹配、不创建——笔误不会在站点上多出分类）
 */
public record DigestCategoryConfigDTO(String key, String label, String emoji, String description,
                                      String targetCategory) {

    public DigestCategoryConfigDTO(String key, String label) {
        this(key, label, "", "", "");
    }

    /** 是否配了映射目标 */
    public boolean hasTarget() {
        return targetCategory != null && !targetCategory.isBlank();
    }
}
