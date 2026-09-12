package pro.uhalo.uni.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 日报任务的实时进度。
 *
 * <p>生成一次要跑 1-3 分钟、逐篇发布还要等发布快照，光靠「运行中」一个状态看不出来卡在哪，
 * 所以把当前阶段与逐篇进度暴露出来，后台页面直接画进度条。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DigestProgressDTO {

    /** 是否有任务在跑 */
    private boolean running;

    /** GENERATE / PUBLISH */
    private String stage;

    /** 人类可读的一行，例如「正在发布第 2/5 篇：xxx」 */
    private String message;

    /** 当前阶段的总量（发布总篇数；生成阶段为 0） */
    private int total;

    /** 当前阶段已完成量 */
    private int done;

    /** 正在处理的标题 */
    private String currentTitle;

    private Instant updatedAt;

    public static DigestProgressDTO idle() {
        return DigestProgressDTO.builder().running(false).build();
    }
}
