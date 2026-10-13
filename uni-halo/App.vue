<script>
import HaloTokenConfig from '@/config/uhalo.config.js';
import v2Api from '@/api/v2/all.api.js';
import { syncPointsAfterLogin, resetPointsSession } from '@/utils/points.js';
import { checkHasWxLogin } from '@/utils/auth.js';
import { restoreThemeSkin } from '@/utils/themeSkin.js';
import { restoreAppearance, watchSystemTheme, refreshSystemTheme } from '@/utils/skin.js';
import { pullAppSettings } from '@/utils/appSettingsSync.js';
import { refreshAppConfigs } from '@/utils/configRefresh.js';

import {CheckWxUpdate} from '@/utils/update.js';
// #ifdef APP-PLUS
// app升级检测（搭配：https://ext.dcloud.net.cn/plugin?id=4470 升级中心）
import CheckAppUpdate from '@/uni_modules/uni-upgrade-center-app/utils/check-update';
// #endif

export default {
    globalData: {
        baseApiUrl: HaloTokenConfig.BASE_API,
        momentListNeedRefresh: false
    },
    onLaunch: function () {

        // #ifdef APP-PLUS
        CheckAppUpdate();
        // #endif

        // #ifdef MP-WEIXIN
        CheckWxUpdate();
        // #endif

        // 先用本地缓存恢复主题，避免首屏闪回默认配色；
        // 服务端 activeTheme 才是权威来源，进入「我的」/「身份中心」时会据此纠正。
        restoreThemeSkin();

        // 外观模式（跟随系统/始终浅色/始终深色）必须在首个页面渲染前恢复：
        // 这里读的是同步存储与同步系统信息，首帧就拿得到正确配色，不会先浅后深地闪一下。
        // 之前只 import 没调用，表现是「始终深色」冷启动后落回默认——只写得进、读不回来。
        restoreAppearance();
        // 用户切了系统深色要立刻跟着变（跟随系统时）
        watchSystemTheme();

        // 应用设置（底栏显示项/外观/首页布局等）跟账号走：并行拉取，不 await——
        // 首屏照旧用本地设置渲染，服务端副本到货后若有差异再平滑应用（换设备/清缓存后自动回来）
        pullAppSettings();

        // 配置到手后才能知道是否启用自绘底栏：尽早压掉原生栏，减少冷启动时那一帧闪动。
        // force：冷启动必定拉一次（节流只用于回到前台那种自动刷新）
        refreshAppConfigs({ force: true });
        // 启动检查app的配置是否已经就绪，若未就绪则设置默认的
        uni.$tm.vx.actions('setting/checkAndSetDefaultAppSettings');

		uni.$on('wx_login_status_changed', (info) => {
			if (!info) {
				resetPointsSession();
				return;
			}
			syncPointsAfterLogin({ v2: v2Api });
			// 静默登录往往比上面那次启动拉取更晚完成，那时还没有 openid、拉取被跳过了：
			// 登录成功后再拉一次，新设备才能立刻拿到自己的设置（同一会话内换账号也走这里）
			pullAppSettings();
		});
		if (checkHasWxLogin()) {
			syncPointsAfterLogin({ v2: v2Api });
		}
    },
    onShow: function () {
        // 从系统设置切完主题再切回小程序时，onThemeChange 在部分机型不会补发：
        // 这里再对一次，避免「跟随系统」下系统已深色而界面还是浅色
        refreshSystemTheme();
        // 后台改了「底部导航」这类站点配置后，回到前台就自动对齐（3 分钟节流）——
        // 否则站长改完得杀进程重进，看起来就像「配置没生效」
        refreshAppConfigs();
        console.log('App Show');
    },
    onHide: function () {
        console.log('App Hide');
    }
};
</script>

<style lang="scss">
// 基础样式
@import './common/styles/app.theme.scss';
@import './common/styles/app.base.scss';

// 引入tmUI2.x样式
@import './tm-vuetify/mian.min.css';
// 引入tmUI2.x主题包
@import './tm-vuetify/scss/theme.css';
// 自定义图标
@import './common/icons/halocoloriconfont.css';
@import './common/icons/haloiconfont.css';
@import './common/icons/mphtmliconfont.css';

/* #ifndef MP-WEIXIN */
@import './common/markdown/markdown.scss';
/* #endif */

/* 皮肤包（装扮商店可穿戴）：放在最后，其 !important 覆盖规则才能压过前面的基础样式 */
@import './common/styles/skins.scss';

/*
 * page 元素（最外层容器）**刻意不设静态底色**。
 *
 * 它曾经是 `page { background-color: #f2f2ec }`——而皮肤生成器会跳过 page 选择器
 * （page 拿不到皮肤类前缀），于是深色模式下这一层永远是浅色：切页的第一帧、
 * 下拉回弹、以及页面内容没铺满时都会露出它，表现为「每次切页顶部闪一下白」。
 * 现在交给窗口底色（pages.json 的 backgroundColor + 运行时 uni.setBackgroundColor，
 * 按生效皮肤取色），深浅色都由它负责，见 utils/skin.js 的 applyNativeChrome。
 *
 * 下面这段只是兜底：某些平台/版本上窗口底色不生效时，至少跟随系统深色。
 */
@media (prefers-color-scheme: dark) {
    page {
        background-color: #14141a;
    }
}
</style>
