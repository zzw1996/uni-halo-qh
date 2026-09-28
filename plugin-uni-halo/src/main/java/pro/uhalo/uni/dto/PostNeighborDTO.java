package pro.uhalo.uni.dto;

/**
 * 相邻文章（文章详情页底部的「上一篇 / 下一篇」按钮）。
 *
 * <p>只下发跳转与展示需要的字段：文章 name（跳详情用）与标题（按钮上要显示，用户才知道点进去是什么）。</p>
 *
 * @param name  文章 metadata.name
 * @param title 文章标题
 */
public record PostNeighborDTO(String name, String title) {
}
