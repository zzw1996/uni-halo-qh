package pro.uhalo.uni.services;

import pro.uhalo.uni.dto.LevelStatusDTO;
import pro.uhalo.uni.dto.OutfitBuyResponse;
import pro.uhalo.uni.dto.OutfitStatusDTO;
import pro.uhalo.uni.dto.EquipOutfitResponse;
import reactor.core.publisher.Mono;

/**
 * 等级 / 称号 / 身份装扮：等级按累计获得积分动态计算，
 * 称号按条件自动解锁（可佩戴），装扮需消耗积分兑换。
 */
public interface LevelService {

    /** 查询等级状态（含佩戴称号与所有称号解锁进度） */
    Mono<LevelStatusDTO> getLevelStatus(String openid);

    /** 佩戴指定称号（必须已解锁），返回更新后的等级状态 */
    Mono<LevelStatusDTO> wearTitle(String openid, String titleId);

    /** 查询装扮商店与当前持有/穿戴状态 */
    Mono<OutfitStatusDTO> getOutfitStatus(String openid);

    /** 购买装扮（扣积分，重复购买幂等返回已拥有） */
    Mono<OutfitBuyResponse> buyOutfit(String openid, String key);

    /** 穿戴装扮（key 为 null 时脱下该类型装扮或全部，按类型处理） */
    Mono<EquipOutfitResponse> equipOutfit(String openid, String key);
}
