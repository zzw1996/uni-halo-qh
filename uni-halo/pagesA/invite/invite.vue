<template>
	<view class="app-page">
		<!-- 未登录 -->
		<view v-if="!isLoggedIn" class="login-gate flex flex-center flex-col">
			<tm-empty icon="icon-user" label="登录后生成邀请码"></tm-empty>
			<tm-button class="mt-24" theme="bg-gradient-light-blue-accent" :shadow="4" @click="loginDialogShow = true">
				微信登录
			</tm-button>
			<tm-button class="mt-16" plan theme="grey" :shadow="0" @click="fnBackHome">返回首页</tm-button>
		</view>
		<template v-else>
			<view v-if="loading === 'loading'" class="loading-wrap">
				<tm-skeleton model="card"></tm-skeleton>
			</view>
			<view v-else-if="loading === 'error'" class="content-empty flex flex-center flex-col" style="min-height: 60vh;">
				<tm-empty icon="icon-shiliangzhinengduixiang-" label="加载失败"></tm-empty>
				<tm-button class="mt-24" plan theme="grey" :shadow="0" @click="fnLoad">重新加载</tm-button>
			</view>
			<view v-else class="content">
				<view class="invite-card">
					<image class="invite-avatar" :src="userAvatar" mode="aspectFill"></image>
					<text class="invite-title">{{ userNickname }} 邀你一起玩</text>
					<text class="invite-sub">在微信里打开邀请卡，注册登录即算邀请成功</text>
					<view class="invite-code-row">
						<view class="invite-code-box">
							<text class="invite-code-label">我的邀请码</text>
							<text class="invite-code-value">{{ inviteInfo.inviteCode }}</text>
						</view>
						<tm-button size="m" theme="primary" :shadow="2" @click="fnCopyCode">复制邀请码</tm-button>
					</view>
					<view class="invite-stats">
						<view class="invite-stat">
							<text class="invite-stat-value">{{ inviteInfo.invitedCount }}</text>
							<text class="invite-stat-label">已邀请</text>
						</view>
						<view class="invite-stat">
							<text class="invite-stat-value">+{{ inviteInfo.rewardPoints }}</text>
							<text class="invite-stat-label">每成功 1 人</text>
						</view>
					</view>
				</view>

				<tm-button
					v-if="inviteInfo.enabled !== false"
					theme="bg-gradient-light-blue-accent"
					:shadow="4"
					:height="88"
					:block="true"
					open-type="share"
				>发送给微信好友</tm-button>
				<tm-button
					v-if="inviteInfo.enabled !== false"
					class="mt-16"
					plan
					theme="primary"
					:shadow="0"
					:height="88"
					:block="true"
					@click="fnShowPoster"
				>生成邀请海报</tm-button>

				<view class="footer-tip">
					<text>邀请新用户通过分享进入小程序并完成登录，每成功注册 1 人你获得 {{ inviteInfo.rewardPoints }} 积分；好友登录后可领取「新人见面礼」。</text>
					<text>获得的积分可参与「幸运翻牌」抽奖、兑换中心兑换礼品。同一好友只计一次；邀请奖励自动到账，可在「积分明细」中查看。</text>
				</view>
			</view>
		</template>

		<!-- 邀请海报弹窗 -->
		<tm-poup v-model="poster.show" width="90vw" height="auto" :round="6" :over-close="true" position="center">
			<view class="poster-content pt-12 bg-white">
				<liu-poster ref="liuPoster" :width="674" :height="940" @change="handleOnPosterChange"></liu-poster>
				<view v-if="poster.loading" class="poster-loading flex flex-center text-grey-darken-1">
					<text class="e-loading-icon iconfont icon-loading"></text>
					<text class="ml-6">海报正在生成...</text>
				</view>
				<block v-if="!poster.loading">
					<image
						v-if="poster.url"
						:style="{
							width: '100%',
							height: '940rpx'
						}"
						:src="poster.url"
					></image>
					<view class="poster-save ma-24 mt-0 pt-20 flex flex-center">
						<tm-button theme="bg-gradient-light-blue-accent" size="m" @click="fnSavePoster()">保存到相册</tm-button>
						<tm-button theme="bg-gradient-blue-grey-accent" size="m" @click="fnOnPosterClose()">关 闭</tm-button>
					</view>
				</block>
			</view>
		</tm-poup>

		<wx-login-dialog
			:show.sync="loginDialogShow"
			title="登录后生成邀请码"
			desc="登录后即可分享邀请好友"
			@success="fnOnLoginSuccess"
		></wx-login-dialog>
	</view>
</template>

<script>
	import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
	import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
	import tmPoup from '@/tm-vuetify/components/tm-poup/tm-poup.vue';
	import liuPoster from '@/uni_modules/liu-poster/components/liu-poster/liu-poster.vue';
	import wxLoginDialog from '@/components/wx-login-dialog/wx-login-dialog.vue';
	import { checkHasWxLogin, getWxLoginInfo } from '@/utils/auth.js';
	import utils from '@/utils/index.js';

	export default {
		components: {
			tmSkeleton,
			tmEmpty,
			tmButton,
			tmPoup,
			liuPoster,
			wxLoginDialog
		},
		data() {
			return {
				loading: 'loading',
				isLoggedIn: false,
				loginDialogShow: false,
				inviteInfo: {
					enabled: true,
					rewardPoints: 30,
					inviteCode: '',
					invitedCount: 0
				},
				poster: {
					show: false,
					loading: false,
					url: '',
					configs: []
				}
			};
		},
		computed: {
			haloConfigs() {
				return this.$tm.vx.getters().getConfigs;
			},
			userAvatar() {
				const user = getWxLoginInfo();
				return user?.avatarUrl || '';
			},
			userNickname() {
				const user = getWxLoginInfo();
				return user?.nickname || '我';
			}
		},
		onLoad() {
			this.isLoggedIn = checkHasWxLogin();
			if (this.isLoggedIn) {
				this.fnLoad();
			}
		},
		onPullDownRefresh() {
			this.fnLoad().finally(() => uni.stopPullDownRefresh());
		},
		onShareAppMessage() {
			const code = this.inviteInfo.inviteCode;
			return {
				title: `${this.userNickname} 邀请你加入 uni-halo，和我一起玩`,
				path: '/pages/index/index' + (code ? '?inviteCode=' + encodeURIComponent(code) : ''),
				imageUrl: this.userAvatar || ''
			};
		},
		onShareTimeline() {
			return {
				title: `${this.userNickname} 邀请你加入 uni-halo`,
				query: this.inviteInfo.inviteCode
					? { inviteCode: this.inviteInfo.inviteCode }
					: {}
			};
		},
		methods: {
			async fnLoad() {
				try {
					const res = await this.$httpApi.v2.getInviteInfo();
					const data = res?.data || res || {};
					if (data?.error) {
						this.loading = 'error';
						uni.showToast({ title: data.error, icon: 'none' });
						return;
					}
					this.inviteInfo = {
						enabled: data.enabled !== false,
						rewardPoints: Number(data.rewardPoints || 30),
						inviteCode: data.inviteCode || '',
						invitedCount: Number(data.invitedCount || 0)
					};
					if (this.inviteInfo.inviteCode) {
						uni.setStorageSync('invite.code', this.inviteInfo.inviteCode);
					}
					this.loading = 'success';
				} catch (err) {
					this.loading = 'error';
					uni.showToast({ title: err?.error || '加载失败，请稍后重试', icon: 'none' });
				}
			},
			fnCopyCode() {
				if (!this.inviteInfo.inviteCode) return;
				utils.copyText(this.inviteInfo.inviteCode, '邀请码已复制');
			},
			fnOnLoginSuccess() {
				this.isLoggedIn = true;
				this.fnLoad();
			},
			fnBackHome() {
				uni.switchTab({ url: '/pages/tabbar/home/home' });
			},

			// ==================== 邀请海报 ====================
			fnShowPoster() {
				if (!this.inviteInfo.inviteCode) {
					uni.$tm.toast('邀请码尚未生成，请下拉刷新重试');
					return;
				}
				this.poster.url = '';
				this.fnGeneratePoster();
			},
			async fnGeneratePoster() {
				this.poster.show = true;
				this.poster.loading = true;
				if (this._posterTimer) {
					clearTimeout(this._posterTimer);
				}
				this._posterTimer = setTimeout(() => {
					if (this.poster.loading) {
						uni.hideLoading();
						this.poster.loading = false;
						this.poster.show = false;
						uni.$tm.toast('海报生成超时，请检查图片配置');
					}
				}, 20000);
				try {
					await this.handleCreatePoster();
					setTimeout(() => {
						this.$nextTick(() => {
							if (this.$refs.liuPoster) {
								this.$refs.liuPoster.init(this.poster.configs);
							}
						});
					}, 300);
				} catch (err) {
					if (this._posterTimer) {
						clearTimeout(this._posterTimer);
						this._posterTimer = null;
					}
					console.error('[invite] 海报生成失败', err);
					this.poster.loading = false;
					this.poster.show = false;
					uni.$tm.toast(err?.detail || err?.message || err?.error || '海报生成失败，请稍后重试');
				}
			},
			handleOnPosterChange(url) {
				if (this._posterTimer) {
					clearTimeout(this._posterTimer);
					this._posterTimer = null;
				}
				uni.hideLoading();
				if (!url) {
					this.poster.loading = false;
					this.poster.show = false;
					uni.$tm.toast('海报绘制失败，请检查图片配置');
					return;
				}
				this.poster.url = url;
				this.poster.loading = false;
			},
			/** 海报图片兜底：失效外链回退到站点内默认图 */
			getPosterImageUrl(url) {
				const imagesConfig = this.haloConfigs?.imagesConfig || {};
				const fallback = this.$utils.checkUrl(
					imagesConfig.defaultImageUrl ||
					imagesConfig.defaultStaticThumbnailUrl ||
					imagesConfig.defaultThumbnailUrl ||
					''
				);
				if (!url || (typeof url === 'string' && url.includes('api.qjqq.cn'))) {
					return fallback;
				}
				return this.$utils.checkIsUrl(url) ? url : this.$utils.checkUrl(url);
			},
			/**
			 * 下载远程图片到本地临时路径（canvas 绘制网络图可能被静默跳过，
			 * 先用 downloadFile 拿本地路径最稳；失败时回退原 URL）。
			 */
			fnDownloadImage(url) {
				return new Promise((resolve) => {
					if (!url || typeof url !== 'string') {
						resolve('');
						return;
					}
					uni.downloadFile({
						url,
						success: (res) => {
							if (res.statusCode === 200 && res.tempFilePath) {
								resolve(res.tempFilePath);
							} else {
								console.warn('[invite] 下载海报图片失败 statusCode=', res.statusCode);
								resolve('');
							}
						},
						fail: (err) => {
							console.warn('[invite] 下载海报图片失败', err);
							resolve('');
						}
					});
				});
			},
			/**
			 * 确保小程序码图片可被 canvas 绘制：先用 getImageInfo 校验网络图，
			 * 加载失败则下载为本地临时文件再校验；全部失败时返回明确错误，
			 * 避免海报生成后二维码静默缺失。
			 */
			fnEnsurePosterQR(url) {
				return new Promise((resolve, reject) => {
					// 清理可能的环境包裹（引号/空白/未解析 JSON），避免出现 downloadFile Invalid URL
					let normalized = '';
					let rawStr = String(url == null ? '' : url).trim();
					if (rawStr.startsWith('"') || rawStr.startsWith("'")) {
						try {
							rawStr = JSON.parse(rawStr);
						} catch (e) {
							rawStr = rawStr.replace(/^["']+|["']+$/g, '');
						}
					}
					normalized = this.$utils.checkIsUrl(rawStr) ? rawStr : this.$utils.checkUrl(rawStr);
					console.log('[invite] imageUrl:', normalized.slice(0, 200));
					uni.getImageInfo({
						src: normalized,
						success: () => resolve(normalized),
						fail: () => {
							this.fnDownloadImage(normalized).then((localPath) => {
								if (localPath) {
									uni.getImageInfo({
										src: localPath,
										success: () => resolve(localPath),
										fail: () => reject(new Error('小程序码图片加载失败，请确认小程序后台「downloadFile 合法域名」已配置站点域名'))
									});
								} else {
									reject(new Error('小程序码图片加载失败，请确认小程序后台「downloadFile 合法域名」已配置站点域名'));
								}
							});
						}
					});
				});
			},
			/**
			 * 把 base64 图片写入本地临时文件（微信 FileSystemManager），
			 * 绘制本地文件不依赖「downloadFile 合法域名」白名单。
			 * 同步写失败时降级异步写，并记录真实失败原因。
			 */
			fnSaveBase64ToTemp(base64) {
				return new Promise((resolve) => {
					if (!base64 || typeof base64 !== 'string') {
						resolve('');
						return;
					}
					// #ifdef MP-WEIXIN
					this._posterQrBase64Fail = '';
					try {
						const fs = wx.getFileSystemManager();
						const filePath = `${wx.env.USER_DATA_PATH}/invite_qr_${Date.now()}.png`;
						fs.writeFileSync(filePath, base64, 'base64');
						console.log('[invite] 小程序码已写入本地文件:', filePath, '字节数:', Math.round(base64.length * 0.75));
						resolve(filePath);
						return;
					} catch (syncErr) {
						this._posterQrBase64Fail = syncErr && (syncErr.errMsg || syncErr.message);
						console.warn('[invite] writeFileSync 失败，改为异步写入:', syncErr && (syncErr.errMsg || syncErr.message));
						try {
							const fs = wx.getFileSystemManager();
							const filePath = `${wx.env.USER_DATA_PATH}/invite_qr_${Date.now()}.png`;
							fs.writeFile({
								filePath,
								data: base64,
								encoding: 'base64',
								success: () => resolve(filePath),
								fail: (err) => {
									this._posterQrBase64Fail = err && (err.errMsg || err.message);
									console.warn('[invite] writeFile 失败:', err && (err.errMsg || err.message));
									resolve('');
								}
							});
						} catch (asyncErr) {
							this._posterQrBase64Fail = asyncErr && (asyncErr.errMsg || asyncErr.message);
							console.warn('[invite] writeFile 调用异常:', asyncErr && (asyncErr.errMsg || asyncErr.message));
							resolve('');
						}
					}
					// #endif
					// #ifndef MP-WEIXIN
					resolve('');
					// #endif
				});
			},
			/** 估算文本宽度（CJK 按 1 字宽、ASCII 按 0.55 字宽），用于居中定位 */
			estimateTextWidth(content, fontSize) {
				let width = 0;
				for (const ch of String(content || '')) {
					width += ch.charCodeAt(0) > 127 ? fontSize : fontSize * 0.55;
				}
				return width;
			},
			/** 计算一行文本的居中起始 x */
			centerTextX(content, fontSize, totalWidth = 674) {
				const width = this.estimateTextWidth(content, fontSize);
				const x = (totalWidth - width) / 2;
				return Math.max(24, Math.min(x, totalWidth - 24 - width));
			},
			async handleCreatePoster() {
				const rewardPoints = Number(this.inviteInfo.rewardPoints || 30);
				const nickname = this.userNickname || '我';
				const inviteCode = this.inviteInfo.inviteCode || '';
				const invitedCount = Number(this.inviteInfo.invitedCount || 0);
				const avatar = this.getPosterImageUrl(
					this.userAvatar
						? (this.$utils.checkIsUrl(this.userAvatar) ? this.userAvatar : this.$utils.checkUrl(this.userAvatar))
						: this.$utils.checkAvatarUrl('')
				);
				// 海报小程序码：动态生成（scene=inv:邀请码，扫码自动带入邀请码），
				// 后端返回 base64 + imageUrl，base64 写本地临时文件绘制可绕开域名白名单
				let qrCodeData = null;
				try {
					let qrCodeImg = await this.$httpApi.v2.getInviteQRCodeImg(inviteCode);
					// 防御：接口可能因响应体过大未走 JSON 解析，返回的是原始 JSON 字符串
					if (typeof qrCodeImg === 'string') {
						const trimmed = qrCodeImg.trim();
						if (trimmed.startsWith('{')) {
							try {
								qrCodeImg = JSON.parse(trimmed);
							} catch (parseErr) {
								console.warn('[invite] 响应 JSON 解析失败（可能响应被截断）', parseErr && parseErr.message);
							}
						}
					}
					console.log('[invite] 接口返回:', typeof qrCodeImg === 'string'
						? qrCodeImg
						: JSON.stringify(qrCodeImg || {}).slice(0, 160));
					if (qrCodeImg && typeof qrCodeImg === 'object') {
						qrCodeData = qrCodeImg.data || qrCodeImg;
					} else {
						qrCodeData = typeof qrCodeImg === 'string' ? { imageUrl: qrCodeImg } : null;
					}
				} catch (err) {
					console.warn('[invite] 动态邀请小程序码获取失败', err);
					const detail = err?.data?.error || err?.error || err?.message || '';
					if (detail) {
						throw new Error('邀请小程序码获取失败：' + detail);
					}
				}
				if (!qrCodeData || (!qrCodeData.base64 && !qrCodeData.imageUrl)) {
					throw new Error('邀请小程序码获取失败，请检查插件 appId/appSecret 配置');
				}
				// 优先用 base64 写本地文件绘制（不依赖 downloadFile 白名单），失败再用图片地址校验绘制
				let qrPath = '';
				if (qrCodeData.base64) {
					qrPath = await this.fnSaveBase64ToTemp(qrCodeData.base64);
				}
				if (!qrPath && qrCodeData.imageUrl) {
					try {
						qrPath = await this.fnEnsurePosterQR(qrCodeData.imageUrl);
					} catch (imgErr) {
						console.warn('[invite] 图片地址加载失败（兜底失效）', imgErr && imgErr.message);
					}
				}
				if (!qrPath) {
					const writeFail = this._posterQrBase64Fail
						? `（base64 写入失败：${this._posterQrBase64Fail}）`
						: '（后端未返回 base64，说明服务器上仍是旧版插件）';
					throw new Error('小程序码图片加载失败' + writeFail + '；请确认已部署 plugin-uni-halo-1.0.88.jar 或配置小程序「downloadFile 合法域名」');
				}

				const title = `${nickname} 邀你一起玩`;
				const subText = `好友注册登录 · 你可得 +${rewardPoints} 积分`;
				const codeText = inviteCode.split('').join(' ');
				const countText = `已邀请 ${invitedCount} 人`;

				const benefit1 = `邀请成功每 1 人奖励 +${rewardPoints} 积分`;
				const benefit2 = '积分可参与「幸运翻牌」抽奖，还能兑换礼品';

				// 白卡片内居中文本起点
				const pillLeft = 52;
				const pillWidth = 128;
				const pillText = '我的邀请码';
				const pillTextX = Math.round(pillLeft + (pillWidth - this.estimateTextWidth(pillText, 22)) / 2);
				const chip1X = Math.round(52 + (522 - this.estimateTextWidth(benefit1, 24)) / 2);
				const chip2X = Math.round(52 + (522 - this.estimateTextWidth(benefit2, 22)) / 2);

				this.poster.configs = [
					// 炫彩渐变主背景
					{
						type: 'color',
						width: 674,
						height: 940,
						x: 0,
						y: 0,
						radius: 24,
						lineWidth: 0,
						lineColor: '#FFFFFF',
						colorObj: {
							colorList: ['#5b6ee8', '#9a4ee0'],
							direction: 2
						}
					},
					// 装饰圆与星光
					{
						type: 'color',
						width: 260,
						height: 260,
						x: -70,
						y: -70,
						radius: 130,
						lineWidth: 0,
						lineColor: '#FFFFFF',
						colorObj: { colorList: ['rgba(255,255,255,0.08)'], direction: 2 }
					},
					{
						type: 'color',
						width: 220,
						height: 220,
						x: 560,
						y: 150,
						radius: 110,
						lineWidth: 0,
						lineColor: '#FFFFFF',
						colorObj: { colorList: ['rgba(255,255,255,0.10)'], direction: 2 }
					},
					{
						type: 'color',
						width: 180,
						height: 180,
						x: -50,
						y: 600,
						radius: 90,
						lineWidth: 0,
						lineColor: '#FFFFFF',
						colorObj: { colorList: ['rgba(0,0,0,0.08)'], direction: 2 }
					},
					{ type: 'text', width: 100, height: 30, x: 96, y: 180, color: 'rgba(255,255,255,0.55)', fontSize: 22, lineHeight: 30, bold: false, content: '✦' },
					{ type: 'text', width: 100, height: 30, x: 560, y: 214, color: 'rgba(255,255,255,0.5)', fontSize: 18, lineHeight: 30, bold: false, content: '✦' },
					{ type: 'text', width: 100, height: 30, x: 64, y: 252, color: 'rgba(255,255,255,0.45)', fontSize: 16, lineHeight: 30, bold: false, content: '✦' },
					{ type: 'text', width: 100, height: 30, x: 594, y: 120, color: 'rgba(255,255,255,0.6)', fontSize: 26, lineHeight: 30, bold: false, content: '✦' },
					// 头像光晕 + 头像
					{
						type: 'color',
						width: 140,
						height: 140,
						x: 267,
						y: 46,
						radius: 70,
						lineWidth: 0,
						lineColor: '#FFFFFF',
						colorObj: { colorList: ['rgba(255,255,255,0.16)'], direction: 2 }
					},
					{
						type: 'image',
						width: 120,
						height: 120,
						x: 277,
						y: 56,
						radius: 60,
						lineWidth: 6,
						lineColor: '#FFFFFF',
						path: avatar
					},
					// 标题 + 副标题
					{
						type: 'text',
						width: 600,
						height: 44,
						x: this.centerTextX(title, 34),
						y: 198,
						color: '#FFFFFF',
						fontSize: 34,
						lineHeight: 44,
						bold: true,
						content: title
					},
					{
						type: 'text',
						width: 600,
						height: 30,
						x: this.centerTextX(subText, 20),
						y: 252,
						color: '#E3DCFF',
						fontSize: 20,
						lineHeight: 30,
						bold: false,
						content: subText
					},
					// 卡片 A：邀请码
					{
						type: 'color',
						width: 626,
						height: 170,
						x: 24,
						y: 296,
						radius: 24,
						lineWidth: 0,
						lineColor: '#FFFFFF',
						colorObj: { colorList: ['#FFFFFF'], direction: 2 }
					},
					{
						type: 'color',
						width: pillWidth,
						height: 44,
						x: pillLeft,
						y: 322,
						radius: 22,
						lineWidth: 0,
						lineColor: '#FFFFFF',
						colorObj: { colorList: ['#667eea'], direction: 2 }
					},
					{
						type: 'text',
						width: 120,
						height: 30,
						x: pillTextX,
						y: 329,
						color: '#FFFFFF',
						fontSize: 22,
						lineHeight: 30,
						bold: true,
						content: pillText
					},
					{
						type: 'text',
						width: 200,
						height: 30,
						x: 420,
						y: 329,
						color: '#909399',
						fontSize: 20,
						lineHeight: 30,
						bold: false,
						content: countText
					},
					{
						type: 'text',
						width: 560,
						height: 56,
						x: 52,
						y: 380,
						color: '#333333',
						fontSize: 44,
						lineHeight: 56,
						bold: true,
						content: codeText
					},
					// 卡片 B：邀请福利
					{
						type: 'color',
						width: 626,
						height: 218,
						x: 24,
						y: 478,
						radius: 24,
						lineWidth: 0,
						lineColor: '#FFFFFF',
						colorObj: { colorList: ['#FFFFFF'], direction: 2 }
					},
					{
						type: 'text',
						width: 300,
						height: 32,
						x: 52,
						y: 500,
						color: '#333333',
						fontSize: 24,
						lineHeight: 32,
						bold: true,
						content: '🎁 邀请福利'
					},
					{
						type: 'color',
						width: 522,
						height: 58,
						x: 52,
						y: 542,
						radius: 29,
						lineWidth: 0,
						lineColor: '#FFFFFF',
						colorObj: { colorList: ['#667eea', '#764ba2'], direction: 3 }
					},
					{
						type: 'text',
						width: 490,
						height: 34,
						x: chip1X,
						y: 554,
						color: '#FFFFFF',
						fontSize: 24,
						lineHeight: 34,
						bold: true,
						content: benefit1
					},
					{
						type: 'color',
						width: 522,
						height: 58,
						x: 52,
						y: 608,
						radius: 29,
						lineWidth: 0,
						lineColor: '#FFFFFF',
						colorObj: { colorList: ['#f0f8ff'], direction: 2 }
					},
					{
						type: 'text',
						width: 490,
						height: 34,
						x: chip2X,
						y: 620,
						color: '#03a9f4',
						fontSize: 22,
						lineHeight: 34,
						bold: true,
						content: benefit2
					},
					// 卡片 C：小程序码
					{
						type: 'color',
						width: 626,
						height: 208,
						x: 24,
						y: 708,
						radius: 24,
						lineWidth: 0,
						lineColor: '#FFFFFF',
						colorObj: { colorList: ['#FFFFFF'], direction: 2 }
					},
					{
						type: 'image',
						width: 152,
						height: 152,
						x: 52,
						y: 736,
						radius: 12,
						lineWidth: 0,
						lineColor: '#FFFFFF',
						path: qrPath
					},
					{
						type: 'text',
						width: 380,
						height: 36,
						x: 236,
						y: 766,
						color: '#333333',
						fontSize: 26,
						lineHeight: 36,
						bold: true,
						content: '长按识别小程序码'
					},
					{
						type: 'text',
						width: 400,
						height: 30,
						x: 236,
						y: 818,
						color: '#909399',
						fontSize: 20,
						lineHeight: 30,
						bold: false,
						content: '微信扫一扫 · 立即领取新人见面礼'
					}
				];
			},
			fnOnPosterClose() {
				this.poster.show = false;
				this.poster.loading = false;
			},
			async fnSavePoster() {
				if (!this.poster.url) return;
				const authorize = await this.fnCheckAlbumAppAuthorize();
				if (!authorize) return;
				uni.saveImageToPhotosAlbum({
					filePath: this.poster.url,
					success: () => {
						uni.$tm.toast('保存成功');
					},
					fail: (e) => {
						if (!e?.errMsg?.includes('cancel')) {
							uni.$tm.toast('保存失败，请重试');
						}
					}
				});
			},
			fnCheckAlbumAppAuthorize() {
				return new Promise((resolve) => {
					// #ifdef MP-WEIXIN
					uni.getSetting({
						success: async (res) => {
							if (res.authSetting['scope.writePhotosAlbum'] === false) {
								uni.showModal({
									title: '提示',
									content: '需要您授权保存图片到相册，请在设置中开启',
									success: (modalRes) => {
										if (modalRes.confirm) {
											uni.openSetting({
												success: () => resolve(false)
											});
										} else {
											resolve(false);
										}
									}
								});
							} else {
								resolve(true);
							}
						},
						fail: () => resolve(false)
					});
					// #endif
					// #ifndef MP-WEIXIN
					resolve(true);
					// #endif
				});
			}
		}
	};
</script>

<style scoped lang="scss">
	.app-page {
		min-height: 100vh;
		background: #f5f6fa;
		padding: 24rpx 24rpx 48rpx;
	}

	.login-gate {
		min-height: 70vh;
	}

	.mt-24 {
		margin-top: 24rpx;
	}

	.mt-16 {
		margin-top: 16rpx;
	}

	.loading-wrap,
	.content-empty {
		padding: 24rpx;
	}

	.invite-card {
		padding: 40rpx 28rpx;
		border-radius: 20rpx;
		background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
		color: #fff;
		margin-bottom: 24rpx;
		text-align: center;
	}

	.invite-avatar {
		width: 120rpx;
		height: 120rpx;
		border-radius: 50%;
		border: 4rpx solid rgba(255, 255, 255, 0.6);
		background: #eee;
	}

	.invite-title {
		display: block;
		margin-top: 20rpx;
		font-size: 32rpx;
		font-weight: 700;
	}

	.invite-sub {
		display: block;
		margin-top: 8rpx;
		font-size: 22rpx;
		opacity: 0.85;
	}

	.invite-code-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 16rpx;
		margin-top: 28rpx;
		padding: 20rpx 24rpx;
		border-radius: 14rpx;
		background: rgba(255, 255, 255, 0.12);
	}

	.invite-code-box {
		text-align: left;
	}

	.invite-code-label {
		display: block;
		font-size: 20rpx;
		opacity: 0.85;
	}

	.invite-code-value {
		display: block;
		margin-top: 4rpx;
		font-size: 40rpx;
		font-weight: 800;
		letter-spacing: 6rpx;
	}

	.invite-stats {
		display: flex;
		justify-content: center;
		gap: 80rpx;
		margin-top: 28rpx;
	}

	.invite-stat-value {
		display: block;
		font-size: 36rpx;
		font-weight: 700;
	}

	.invite-stat-label {
		display: block;
		margin-top: 4rpx;
		font-size: 20rpx;
		opacity: 0.85;
	}

	.footer-tip {
		margin-top: 24rpx;
		padding: 24rpx;
		border-radius: 12rpx;
		background: #fff7e6;
	}

	.footer-tip text {
		display: block;
		font-size: 22rpx;
		line-height: 1.7;
		color: #b3800a;

		& + text {
			margin-top: 8rpx;
		}
	}

	.poster-loading {
		height: 240rpx;
		justify-content: center;
	}
</style>
