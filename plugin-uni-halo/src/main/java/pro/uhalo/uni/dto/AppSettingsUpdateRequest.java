package pro.uhalo.uni.dto;

import lombok.Data;

import java.util.Map;

/**
 * 保存应用设置的请求体：<b>只带本次改动的键</b>（增量提交）。
 *
 * <p>键是扁平点号路径（如 {@code tabbar.show.rank}），值是布尔或枚举字符串。
 * 服务端按字段合并——两台设备各改各的不会互相抹掉；这也正是「不整包覆盖」的原因。</p>
 */
@Data
public class AppSettingsUpdateRequest {

    /** 本次改动的键值；空/缺省表示没有改动（服务端会原样返回当前副本） */
    private Map<String, Object> settings;
}
