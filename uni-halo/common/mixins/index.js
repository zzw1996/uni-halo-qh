/**
 *  功能：全局混入函数
 *  作者：小莫唐尼
 *  邮箱：studio@925i.cn
 *  时间：2022年07月21日 17:39:32
 *  版本：v0.1.0
 *  修改记录：
 *  修改内容：
 *  修改人员：
 *  修改时间：
 */

import { getDefaultAppSettings } from '@/utils/app.js'
import { SKINS, skinState, skinClassOf, applyNativeChrome } from '@/utils/skin.js'
import { hideNativeTabBar } from '@/utils/tabbar.js'

export default {
    install(Vue) {
        Vue.mixin({
            data() {
                return {
                    _isWechat: true,
                    _aniWaitIndex: 0
                };
            },
            computed: {
                haloConfig() {
                    return this.$tm.vx.getters().getConfigs;
                },
                // 获取全局应用设置
                //
                // 这里必须兜底：页面模板大量使用 globalAppSettings.layout.xxx / banner.xxx，
                // 一旦设置对象是 null（存储损坏、首次启动读不到），render 期就会抛
                // 「Cannot read properties of null」把整个页面渲染打断。
                // 兜底成默认设置后，最坏情况是「回到默认外观」，而不是「页面白掉 + 控制台报错」。
                globalAppSettings() {
                    const settings = uni.$tm.vx.getters().getSettings;
                    if (settings && typeof settings === 'object') {
                        return settings;
                    }
                    return getDefaultAppSettings();
                },
                // 计算动画索引
                calcAniWait() {
                    return (index) => {
                        if ((index + 1) % 10 === 0) {
                            this._aniWaitIndex = 1;
                        } else {
                            this._aniWaitIndex += 1;
                        }
                        return this._aniWaitIndex * 50
                    }
                },
                /**
                 * 当前皮肤要挂在页面根节点上的类名（默认皮肤返回空串，不产生任何样式）。
                 *
                 * <p>皮肤覆盖规则都以 `view.skin-x …` 开头，因此页面根节点必须带上这个类，
                 * 子孙元素才会命中规则。做成全局计算属性，各页面只需在根节点写 :class="skinClass"。</p>
                 */
                skinClass() {
                    return skinClassOf(skinState.id);
                },
                /** 当前皮肤信息（皮肤商店/身份中心展示用） */
                calcSkin() {
                    return SKINS[skinState.id] || SKINS.paper;
                }
            },
            onLoad() {
                this.fnResetSetAniWaitIndex()
            },
            created() {
                // #ifdef MP-WEIXIN
                this._isWechat = true;
                // #endif
                // #ifndef MP-WEIXIN
                this._isWechat = false;
                // #endif
            },
            onShow() {
                this.fnResetSetAniWaitIndex()
                // 启用自绘底栏时，每次页面显示都压一次原生栏（从别的 tab 切回来时原生栏可能被重新显示）
                hideNativeTabBar(this.haloConfig)
                // 导航栏/tabBar 颜色是按页面生效的，带皮肤的站每页都要重新同步一次。
                // 默认皮肤（paper）下不做任何动作：避免覆盖页面自己设置的导航栏样式。
                if (skinState.id !== 'paper') {
                    applyNativeChrome(skinState.id)
                }
            },
            /**
             * iOS 上首帧（onLoad/onShow 阶段）调 wx.hideTabBar 往往无效——那时视图还没 ready，
             * 原生栏随后才创建出来，于是原生栏和自绘栏同时出现。视图渲染完成的 onReady 里再压一次，
             * 配合 hideNativeTabBar 内部的延时重试，把「原生栏晚于调用创建」的时机覆盖住。
             */
            onReady() {
                hideNativeTabBar(this.haloConfig)
            },
            methods: {
                // 设置页面标题
                fnSetPageTitle(title) {
                    uni.setNavigationBarTitle({
                        title: title || this.haloConfig?.appConfig?.startConfig?.title || "uni-halo",
                    })
                },

                // 页面返回顶部
                fnToTopPage(duration = 500) {
                    duration = isNaN(duration) ? 500 : duration
                    uni.pageScrollTo({
                        scrollTop: 0,
                        duration: duration,
                        fail: (err) => {
                            console.log('err：', err);
                        },
                    });
                },
                // 初始化动画索引值（需要在每个页面调用）
                fnResetSetAniWaitIndex() {
                    this._aniWaitIndex = 0
                }
            },
        });
    },
};
