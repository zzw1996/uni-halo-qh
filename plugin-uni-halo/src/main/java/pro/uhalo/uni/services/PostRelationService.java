package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.PostNeighborsDTO;
import reactor.core.publisher.Mono;

/**
 * 文章之间的关系查询（详情页底部的「上一篇 / 下一篇」）。
 *
 * <p>口径：与首页主列表保持一致——候选集按查看者的订阅分类过滤（未登录或没订阅就是全站），
 * 排序为「置顶优先，其次发布时间倒序」，取列表里上下相邻的两篇。
 * 因此同一篇文章对不同登录用户可能给出不同的上下篇，这正是「和列表一致」的代价。</p>
 *
 * <p>为什么放服务端：相邻关系要在候选集里定位，前端拉一页文章只能猜；
 * 而且订阅是存在服务端的（按 openid），端上也没有第二份可信来源。</p>
 */
public interface PostRelationService {

    /**
     * 取某篇文章的上一篇 / 下一篇（按首页列表口径）。
     *
     * <p>只把「公众可见的已发布文章」算作邻居：草稿、回收站、定时发布、仅登录可见都不算——
     * 它们在首页列表里也不会出现。</p>
     *
     * <p>订阅口径用不了时（订阅为空、订阅分类下没有内容、或当前文章本身不在订阅范围内）
     * 退回全站口径，保证按钮不会凭空消失。</p>
     *
     * @param postName 文章 metadata.name
     * @param openid   查看者 openid（可为空；空即未登录，走全站口径）
     * @return 邻居；文章不存在或没有任何邻居时返回 {@link PostNeighborsDTO#empty()}
     */
    Mono<PostNeighborsDTO> fetchNeighbors(String postName, String openid);
}
