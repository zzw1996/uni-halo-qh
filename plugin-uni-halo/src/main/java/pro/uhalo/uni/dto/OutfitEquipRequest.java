package pro.uhalo.uni.dto;

import lombok.Data;

@Data
public class OutfitEquipRequest {

    /** 装扮 key；为 null 表示脱下该类型装扮 */
    private String key;
}
