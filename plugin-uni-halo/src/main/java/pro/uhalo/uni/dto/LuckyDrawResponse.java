package pro.uhalo.uni.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LuckyDrawResponse {

    private boolean won;

    private String name;

    private String emoji;

    /** 中奖单 id（needAddress 时返回，用于提交收货信息） */
    private String winningId;

    /** 是否需要填写收货信息 */
    private boolean needsAddress;

    private long balance;

    private String message;
}
