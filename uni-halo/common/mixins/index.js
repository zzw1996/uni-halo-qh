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
import { SKINS, skinState, skinClassOf, effectiveSkinId, syncNativeChrome } from '@/utils/skin.js'
import { hideNativeTabBar } from '@/utils/tabbar.js'

export default {
    install(Vue) {
        Vue.mixin({
            data() {
                return {
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
                    // 用「生效皮肤」而不是用户选的皮肤：外观模式（跟随系统/强制深色）
                    // 会影响实际配色，根类名必须跟着它走，否则切了模式要等重启才生效
                    return skinClassOf(effectiveSkinId());
                },
                /** 当前皮肤信息（皮肤商店/身份中心展示用） */
                calcSkin() {
                    return SKINS[skinState.id] || SKINS.paper;
                }
            },
            onLoad() {
                this.fnResetSetAniWaitIndex()
                /**
                 * 原生栏同步放在 onLoad 而不是只放 onShow。
                 *
                 * <p>导航栏与窗口底色是按页面生效的，页面被创建时会先按 pages.json 里的静态值
                 * 画一帧；等到 onShow 再改就已经"闪"过去了（深色模式下表现为每次进页面
                 * 先白一下）。onLoad 比 onShow 早，能把这个窗口压到最短。
                 * 跟随系统时 pages.json 的 darkmode + theme.json 还会在绘制前就选好颜色，
                 * 两条路一起兜。</p>
                 */
                syncNativeChrome()
            },
            onShow() {
                this.fnResetSetAniWaitIndex()
                // 启用自绘底栏时，每次页面显示都压一次原生栏（从别的 tab 切回来时原生栏可能被重新显示）
                hideNativeTabBar(this.haloConfig)
                // 导航栏/tabBar 颜色是按页面生效的，带皮肤/深色外观的站每页都要重新同步一次
                //（从别的 tab 切回来时原生栏可能被重置）。默认浅色下不做动作，
                // 避免覆盖页面自己设置的导航栏样式。
                syncNativeChrome()
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
