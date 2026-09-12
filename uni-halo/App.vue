<script>
import HaloTokenConfig from '@/config/uhalo.config.js';
import v2Api from '@/api/v2/all.api.js';
import { syncPointsAfterLogin, resetPointsSession } from '@/utils/points.js';
import { checkHasWxLogin } from '@/utils/auth.js';
import { restoreThemeSkin } from '@/utils/themeSkin.js';

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

        uni.$tm.vx.actions('config/fetchConfigs').then((res) => {
        })
        // 启动检查app的配置是否已经就绪，若未就绪则设置默认的
        uni.$tm.vx.actions('setting/checkAndSetDefaultAppSettings');

		uni.$on('wx_login_status_changed', (info) => {
			if (!info) {
				resetPointsSession();
				return;
			}
			syncPointsAfterLogin({ v2: v2Api });
		});
		if (checkHasWxLogin()) {
			syncPointsAfterLogin({ v2: v2Api });
		}
    },
    onShow: function () {
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

page {
    background-color: #fafafa;
}
</style>
