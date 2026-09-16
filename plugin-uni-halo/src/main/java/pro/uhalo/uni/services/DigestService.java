package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.DigestPreflightItemDTO;
import pro.uhalo.uni.scheme.DigestReport;
import reactor.core.publisher.Mono;

import java.util.List;

public interface DigestService {

    /**
     * 跑一次日报流水线。
     *
     * @param slot    触发时段（HH:mm 表示定时触发，manual 表示手动触发）
     * @param publish 是否在生成后自动发布文章
     */
    Mono<DigestReport> run(String slot, boolean publish);

    Mono<List<DigestReport>> listReports(int limit);

    Mono<DigestReport> getReport(String name);

    /**
     * 删除一条运行记录（只删记录本身，已经发布出去的文章不受影响）。
     *
     * <p>正在运行中的记录不允许删除，避免把任务状态删掉后再也看不到结果。</p>
     */
    Mono<Void> deleteReport(String name);

    /**
     * 把一条已生成的报告重新发布一次（生成成功但发布失败的补救入口）。
     */
    Mono<DigestReport> publishReport(String name);

    /**
     * 发布环境自检：令牌、作者、分类标签逐项检查，返回给后台页面直接展示。
     */
    Mono<List<DigestPreflightItemDTO>> preflight();

    /** 当前是否有一次运行正在进行，定时器与手动触发都据此避免并发 */
    boolean isRunning();
}
