package pro.uhalo.uni.dto;

/**
 * 文章的上一篇 / 下一篇。
 *
 * <p>口径与首页主列表**完全一致**（这是产品明确定的口径）：候选集按查看者的订阅分类过滤、
 * 排序为「置顶优先，其次发布时间倒序」，按钮方向按列表位置取：</p>
 * <ul>
 *   <li>{@code prev} = <b>上一篇</b> = 列表里当前文章<b>上面</b>那一篇（更新，或置顶）；</li>
 *   <li>{@code next} = <b>下一篇</b> = 列表里当前文章<b>下面</b>那一篇（更早）。</li>
 * </ul>
 *
 * <p>所以它不等同于「时间上的前后」——列表里上下相邻才是这里的定义，
 * 用户从首页列表点进文章后，按钮指向的正是列表里挨着的那两篇。</p>
 *
 * <p>任一侧没有文章时为 {@code null}（例如当前是最新的一篇，就没有「上一篇」）；
 * 前端据此隐藏对应按钮，而不是显示一个点了没反应的占位。</p>
 */
public record PostNeighborsDTO(PostNeighborDTO prev, PostNeighborDTO next) {

    public static PostNeighborsDTO empty() {
        return new PostNeighborsDTO(null, null);
    }
}
