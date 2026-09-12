package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 用户榜响应：榜单公开可读，{@code me} 仅在请求方已完成微信登录时返回。
 */
@Data
@Builder
public class UserRankResponse {

    /** points=累计积分榜，active=近 7 日活跃榜，invite=邀请榜 */
    private String type;

    /** all=全部，week=近 7 日（邀请榜不区分区间，恒为 all） */
    private String range;

    private List<UserRankItemDTO> items;

    /** 参与人数（数值大于 0 的用户数），前端据此决定是否降级为引导 */
    private int total;

    /** 当前登录用户的排名；未登录时为 null，未上榜时 joined=false */
    private Me me;

    @Data
    @Builder
    public static class Me {

        private int rank;

        private long value;

        /** 是否已上榜 */
        private boolean joined;
    }
}
