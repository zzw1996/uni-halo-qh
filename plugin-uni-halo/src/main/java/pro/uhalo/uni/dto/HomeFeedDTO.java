package pro.uhalo.uni.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 首页内容流（服务端并集）：把「我订阅的几个分类」合并成一次请求返回。
 *
 * <p>为什么把并集搬到服务端：原先客户端要按分类各发一路请求（订阅 5 个就是 5 路），
 * 而订阅额度放开后这个数字会继续涨。并集接口让它与订阅数解耦，
 * 顺带把「合并、去重、置顶优先、时间倒序」这套口径收到一处。</p>
 *
 * @param items            文章对象（<b>与 Halo 原生文章接口同结构</b>，前端列表零改动）
 * @param hasMore          是否还有下一页
 * @param scope            实际使用的口径：{@code mine} 我的订阅 / {@code hot} 人气分类
 * @param fallback         降级原因（无则 null）：{@code no-subscription} 没有订阅 /
 *                         {@code no-content} 订阅的分类没有内容 / {@code hot-empty} 人气分类没有内容
 * @param categories       本次参与合并的分类（客户端用于展示「正在看哪几类」）
 * @param pausedCategories 因超出订阅额度而暂停的分类（记录还在，续费/解锁后恢复）
 * @param hint             给用户的一句话说明（空串表示不需要提示条）
 * @param page             当前页（从 1 开始）
 * @param size             每页条数
 */
@Data
@Builder
public class HomeFeedDTO {

    private List<JsonNode> items;
    private boolean hasMore;
    private String scope;
    private String fallback;
    private List<String> categories;
    private List<String> pausedCategories;
    private String hint;
    private int page;
    private int size;
}
