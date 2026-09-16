package pro.uhalo.uni.services;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import pro.uhalo.uni.dto.DigestProgressDTO;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 日报运行进度上报。
 *
 * <p>只保留「最近一次」进度，进程内共享：后台页面轮询概览接口即可看到当前阶段与逐篇发布进度。
 * 进程重启后回到空闲状态，这对一个进度指示器来说是合理的（真正的结果始终在报告记录里）。</p>
 */
@Component
public class DigestProgressTracker {

    public static final String STAGE_GENERATE = "GENERATE";
    public static final String STAGE_PUBLISH = "PUBLISH";

    private final AtomicReference<DigestProgressDTO> current =
        new AtomicReference<>(DigestProgressDTO.idle());

    /** 生成阶段：只更新阶段描述 */
    public void generating(String message) {
        update(STAGE_GENERATE, message, 0, 0, null);
    }

    /** 发布阶段：带逐篇进度（done 为已完成篇数，正在处理第 done+1 篇） */
    public void publishing(int total, int done, String currentTitle) {
        var position = Math.min(done + 1, total);
        var message = "正在发布第 " + position + "/" + total + " 篇"
            + (StringUtils.hasText(currentTitle) ? "：" + currentTitle : "…");
        update(STAGE_PUBLISH, message, total, done, currentTitle);
    }

    /** 发布阶段收尾：把进度填满，方便前台看到「已完成」 */
    public void publishingFinished(int total, int succeeded) {
        update(STAGE_PUBLISH, "发布完成：成功 " + succeeded + "/" + total + " 篇", total, total, null);
    }

    public void idle() {
        current.set(DigestProgressDTO.idle());
    }

    /**
     * 成功收尾：清掉「进行中」标记。
     *
     * <p>收尾必须做。原先发布完成时只是把进度填满，{@code running} 仍是 true，
     * 于是页面永远显示「进行中」；失败路径更糟——进度停在「开始抓取 RSS 源」，
     * 看起来像任务卡死，而真正的原因一个字都看不到。</p>
     */
    public void completed(String message) {
        terminal("COMPLETED", message);
    }

    /** 失败收尾：清掉「进行中」并留下失败原因，让页面能显示真正发生了什么 */
    public void failed(String message) {
        terminal("FAILED", message);
    }

    private void terminal(String stage, String message) {
        current.set(DigestProgressDTO.builder()
            .running(false)
            .stage(stage)
            .message(message)
            .updatedAt(Instant.now())
            .build());
    }

    public DigestProgressDTO snapshot() {
        return current.get();
    }

    private void update(String stage, String message, int total, int done, String currentTitle) {
        current.set(DigestProgressDTO.builder()
            .running(true)
            .stage(stage)
            .message(message)
            .total(total)
            .done(done)
            .currentTitle(currentTitle)
            .updatedAt(Instant.now())
            .build());
    }
}
