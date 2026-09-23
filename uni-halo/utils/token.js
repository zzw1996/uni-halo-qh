/**
 * 【已废弃：客户端不再持有个人令牌】
 *
 * 个人令牌等同管理员权限。此前插件把 basicConfig.tokenConfig.personalToken 随匿名配置
 * 下发给小程序，瞬间发布/删除/图片上传都靠它直连 Halo——等于把管理员身份发给了每个用户。
 * 现在这些能力都改由插件服务端代办（/userPublish/* 接口），服务端也不再下发该字段。
 *
 * 因此：**不要在客户端新增任何使用 getPersonalToken() 的逻辑**，它现在必然取不到值，
 * 且一旦有人把令牌重新塞回配置，就应该视为安全事件。
 */
import {getCache, setCache} from "./storage";
import utils from "./index"

export const APP_TOKENS_KEY = "APP_TOKENS";


/**
 * 获取 tokens
 */
export function getTokens() {
    const tokens = getCache(APP_TOKENS_KEY);
    return tokens ?? null;
}

/**
 * 设置 tokens
 */
export function setTokens(value) {
    setCache(APP_TOKENS_KEY, value);
    return true;
}

/**
 * 设置 tokens
 */
export function updateTokens(value) {
    if (!value) return false
    const tokens = getTokens(APP_TOKENS_KEY);
    if (!tokens) return false
    setTokens(utils.deepMerge(tokens, value))
    return true
}

/**
 * 获取 系统token
 */
export function getPersonalToken() {
    const tokens = getTokens(APP_TOKENS_KEY);
    console.log("tokens", tokens)
    return tokens?.personalToken;
}

/**
 * 设置 系统token
 */
export function setPersonalToken(personalToken) {
    const tokens = getCache(APP_TOKENS_KEY);
    if (!tokens) return false
    tokens.personalToken = personalToken
    updateTokens(tokens)
    return true
}
