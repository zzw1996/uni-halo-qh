<template>
	<view :class="skinClass" class="theme-page-pages-index-index app-page flex flex-center">
		<PluginUnavailable v-if="!uniHaloPluginAvailable" :pluginId="uniHaloPluginId" :error-text="uniHaloPluginAvailableError" :use-border="false" :use-decoration="false" />
	</view>
</template>

<script>
import pluginAvailableMixin from '@/common/mixins/pluginAvailable.js';
import PluginUnavailable from '@/components/plugin-unavailable/plugin-unavailable.vue';
import { tryAutoWxLogin } from '@/utils/wxLogin.js';

const homePagePath = '/pages/tabbar/home/home';
const startPagePath = '/pagesA/start/start';
const articleDetailPath = '/pagesA/article-detail/article-detail';

const _DEV_ = false;
const _DEV_TO_TYPE_ = 'page';
const _DEV_TO_PATH_ = '/pagesA/data-visual/data-visual';

export default {
	mixins: [pluginAvailableMixin],
	components: {
		PluginUnavailable
	},
	computed: {
		configs() {
			return this.$tm.vx.getters().getConfigs;
		}
	},
	async onLoad(options) {
		let pendingInvite = '';
		// 保存分享卡片携带的邀请码，首次登录注册时由 wxLogin 消费
		if (options && options.inviteCode) {
			try {
				pendingInvite = decodeURIComponent(options.inviteCode);
				uni.setStorageSync('invite.pending', pendingInvite);
			} catch (e) {
				// 忽略存储异常
			}
		}

		// 邀请海报小程序码：scene 形如 inv:邀请码，扫码进入即标记待消费邀请码
		if (options && options.scene) {
			try {
				const scene = decodeURIComponent(options.scene);
				if (typeof scene === 'string' && scene.startsWith('inv:')) {
					const inviteCode = scene.substring(4).trim();
					if (inviteCode) {
						pendingInvite = inviteCode;
						uni.setStorageSync('invite.pending', inviteCode);
					}
				}
			} catch (e) {
				// 忽略解析异常
			}
		}

		// 本地开发，快速跳转页面，发布请设置 _DEV_ = false
		if (_DEV_ && _DEV_TO_PATH_) {
			if (_DEV_TO_TYPE_ == 'tabbar') {
				uni.switchTab({
					url: _DEV_TO_PATH_
				});
			} else if (_DEV_TO_TYPE_ == 'page') {
				uni.navigateTo({
					url: _DEV_TO_PATH_
				});
			}
			return;
		}

		// 带邀请码进来（分享卡片 / 海报小程序码）：先去落地页看「分享人关注的内容」。
		// 落地页会自己完成静默登录与邀请结算，也负责把码写进待消费存储；
		// 这里就不再往下走启动流程，免得用户先看到首页、再被弹到别处。
		if (pendingInvite) {
			uni.redirectTo({
				url: '/pagesA/invite-landing/invite-landing?code=' + encodeURIComponent(pendingInvite)
			});
			return;
		}

		// 检查插件
		this.setPluginId(this.NeedPluginIds.PluginUniHalo);
		this.setPluginError('阿偶，检测到当前插件没有安装或者启用，无法启动 uni-halo 哦，请联系管理员');
		if (!(await this.checkPluginAvailable())) return;

			// 获取配置
			uni.$tm.vx
				.actions('config/fetchConfigs')
				.then(async (res) => {
					// 每次启动都静默登录一次（wx.login 用户无感知）：
					// 清缓存/重装/登录态过期后，靠它把会话恢复回来——否则首页会一直
					// 按未登录渲染（全站热门 + 订阅引导），而订阅明明还在服务端。
					// 带邀请码首次进入的用户同样由它自动完成邀请绑定。
					this.fnSilentLogin();

				if (options.scene) {
					if ('' !== options.scene) {
						// 邀请海报场景码（inv: 前缀）已在上方作为邀请码处理，跳过文章码查询
						const scene = decodeURIComponent(options.scene);
						if (!scene.startsWith('inv:')) {
							const postId = await this.getPostIdByQRCode(options.scene);
							if (postId) {
								uni.redirectTo({
									url: articleDetailPath + `?name=${postId}`,
									animationType: 'slide-in-right'
								});
							}
						}
					}
				}

				// #ifdef MP-WEIXIN
				// uni.$tm.vx.commit('setWxShare', res.shareConfig);
				// #endif

				// 获取mockjson
				if (res.auditConfig.auditModeEnabled) {
					if (res.auditConfig.auditModeData.jsonUrl) {
						await uni.$tm.vx.actions('config/fetchMockJson');
					} else {
						const mockJson = uni.$utils.checkJsonAndParse(res.auditConfig.auditModeData.jsonData);
						if (mockJson.ok) {
							uni.$tm.vx.commit('config/setMockJson', mockJson.jsonData);
						}
					}
				}

				// 进入检查
				this.fnCheckShowStarted();
			})
			.catch((err) => {
				uni.switchTab({
					url: homePagePath
				});
			});
	},
	methods: {
		/**
		 * 启动时静默登录：wx.login 用户无感知完成。
		 *
		 * <p>三件事都靠它：① 清缓存/重装后恢复登录态（订阅、设置跟账号走的那部分）；
		 * ② 登录态过期时续期；③ 带邀请码首次进入的用户自动绑定邀请关系并发奖。
		 * 静默失败不打扰用户，邀请码保留至下次登录成功时由 wxLogin 消费。
		 * 成功后 applyWxLoginSuccess 会广播 wx_login_status_changed，
		 * 首页等页面据此重拉各自的个性化数据。</p>
		 */
		async fnSilentLogin() {
			try {
				await tryAutoWxLogin(this.$httpApi.v2);
			} catch (e) {
				// 忽略静默登录失败
			}
		},
		fnCheckShowStarted() {
			if (!this.configs.appConfig.startConfig.enabled) {
				uni.switchTab({
					url: homePagePath
				});
				return;
			}

			// 是否每次都显示启动页
			if (this.configs.appConfig.startConfig.alwaysShow) {
				uni.removeStorageSync('APP_HAS_STARTED');
				uni.redirectTo({
					url: startPagePath
				});
				return;
			}

			// 只显示一次启动页
			if (uni.getStorageSync('APP_HAS_STARTED')) {
				uni.switchTab({
					url: homePagePath
				});
			} else {
				uni.redirectTo({
					url: startPagePath
				});
			}
		},
		async getPostIdByQRCode(key) {
			const response = await this.$httpApi.v2.getQRCodeInfo(key);
			if (response) {
				if (response && response.postId) {
					return response.postId;
				}
			}
			return null;
		}
	}
};
</script>

<style lang="scss" scoped>
.app-page {
	width: 100vw;
	height: 100vh;
}
@import '@/common/styles/redesign/pages-index-index-themes.scss';
</style>
