/**
 *  功能：全局API管理
 *  说明：所有接口统一在 v2 (all.api.js) 中管理
 *  作者：小莫唐尼
 *  邮箱：studio@925i.cn
 */

// 全部接口统一管理（Halo v2 核心 + UniHalo 插件）
import v2 from './v2/all.api.js'

const ApiManager = {
    v2
};

const install = (Vue) => {
    Vue.prototype.$httpApi = ApiManager
}

export default {
    install
}
