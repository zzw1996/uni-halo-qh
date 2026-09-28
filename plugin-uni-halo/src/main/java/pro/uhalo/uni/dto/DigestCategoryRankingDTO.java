package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 分类订阅热度榜（公开只读）。
 *
 * <p>小程序「发现」页用它回答「哪些分类被订阅得最多」：分类都会越来越细、越来越多，
 * 光有一面分类墙的话用户不知道该订哪个，热度是一个不需要运营干预的排序依据。</p>
 *
 * <p><b>口径是「主动订阅人数」</b>：只数用户自己选的分类，不含从邀请人继承来的
 * （继承是「谁邀请了他，就用谁订的分类当起点」，不是他的兴趣表达）。
 * 排除关掉订阅（{@code enabled=false}）与没有 openid 的记录。</p>
 */
@Data
@Builder
public class DigestCategoryRankingDTO {

    /** 按订阅人数倒序；分类已从站点删除时也会返回（label 回落成 name），由前端按站点分类清单过滤 */
    private List<Item> items;

    /** 参与统计的用户数（开着订阅、且至少主动订阅了一个分类），用于让前端判断样本量是否值得显示 */
    private int totalSubscribers;

    /** 聚合时间（yyyy-MM-dd HH:mm），带缓存所以不是实时值 */
    private String updatedAt;

    /** 口径标记，固定 {@code own}（主动订阅） */
    private String metric;

    @Data
    @Builder
    public static class Item {

        /** 站点分类的 metadata.name */
        private String name;

        /** 分类显示名；分类已被删除时回落为 name */
        private String label;

        /** 主动订阅该分类的用户数 */
        private int subscribers;
    }
}
