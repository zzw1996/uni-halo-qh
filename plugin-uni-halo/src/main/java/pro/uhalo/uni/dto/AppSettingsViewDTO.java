package pro.uhalo.uni.dto;

import java.util.List;
import java.util.Map;

/**
 * 用户级应用设置的视图：GET 与 POST 都返回这个形状，前端一条解析路径。
 *
 * @param settings 扁平点号键 → 标量（{@code {"tabbar.show.rank": false, ...}}）；
 *                 {@code synced=false} 时为空对象，前端不要据此清空本地设置
 * @param revision 服务端写入次数；0 表示还没有服务端副本
 * @param updatedAt 最近一次写入时间（北京时间 yyyy-MM-dd HH:mm，可直接展示）
 * @param synced 服务端是否有副本（区分「还没同步过」与「同步过但用户没改过任何一项」）
 * @param rejected 本次提交被丢弃的键（白名单外或取值非法）——前端据此提示，
 *                 同时它也是「客户端与服务端白名单不一致」的唯一线索
 */
public record AppSettingsViewDTO(
    Map<String, Object> settings,
    long revision,
    String updatedAt,
    boolean synced,
    List<String> rejected) {

    /** 还没有服务端副本时的空视图 */
    public static AppSettingsViewDTO empty() {
        return new AppSettingsViewDTO(Map.of(), 0L, null, false, List.of());
    }
}
