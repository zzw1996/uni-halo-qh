<template>
	<view :class="skinClass" class="theme-page-pagesA-identity-identity app-page">
		<!-- 未登录 -->
		<view v-if="!isLoggedIn" class="login-gate flex flex-center flex-col">
			<tm-empty icon="icon-user" label="登录后查看身份中心"></tm-empty>
			<tm-button class="mt-24" theme="bg-gradient-indigo-accent" :shadow="4" @click="loginDialogShow = true">
				微信登录
			</tm-button>
			<tm-button class="mt-16" plan theme="grey" :fllowTheme="false" :shadow="0" @click="fnBackHome">返回首页</tm-button>
		</view>
		<template v-else>
			<view v-if="loading === 'loading'" class="loading-wrap">
				<tm-skeleton model="card"></tm-skeleton>
				<tm-skeleton model="list"></tm-skeleton>
			</view>
			<view v-else-if="loading === 'error'" class="content-empty flex flex-center flex-col" style="min-height: 60vh;">
				<tm-empty icon="icon-shiliangzhinengduixiang-" label="加载失败"></tm-empty>
				<tm-button class="mt-24" plan theme="grey" :fllowTheme="false" :shadow="0" @click="fnLoad">重新加载</tm-button>
			</view>
			<view v-else class="content">
			<!-- ========== 身份卡（合并原「身份头部 + 等级卡 + 佩戴称号横幅」） ========== -->
			<view class="identity-card" :style="themeStyle">
				<view class="identity-head">
					<view class="identity-avatar" :class="headAvatarFrameClass">
						<image v-if="userInfo.avatarUrl" :src="userInfo.avatarUrl" mode="aspectFill"></image>
						<text v-else class="identity-avatar-placeholder">👤</text>
					</view>
					<view class="identity-head-main">
						<text class="identity-nickname">{{ headDisplayName }}</text>
						<view class="identity-badges">
							<text class="identity-level">Lv.{{ levelStatus.level }} {{ levelStatus.levelTitle }}</text>
							<text v-if="calcWornTitle" class="identity-worn">{{ calcWornTitle.icon }} {{ calcWornTitle.name }}</text>
						</view>
						<text v-if="avatarFrameUnregistered" class="identity-invalid">当前头像框样式未登记，已按无框显示</text>
					</view>
				</view>

				<!-- 等级进度：一条线表达「累计积分 + 距离下一级」 -->
				<view class="identity-progress">
					<view class="identity-progress-head">
						<text class="identity-progress-label">累计 {{ levelStatus.totalEarned }} 分</text>
						<text class="identity-progress-label">
							{{ levelStatus.nextPoints > 0 ? '距 Lv.' + (levelStatus.level + 1) + ' 还差 ' + levelStatus.nextPoints + ' 分' : '已是最高等级' }}
						</text>
					</view>
					<view v-if="levelStatus.nextPoints > 0" class="level-track">
						<view class="level-fill" :style="{ width: levelPercent + '%' }"></view>
					</view>
					<text v-if="levelStatus.nextPoints > 0" class="identity-progress-percent">等级进度 {{ levelPercent }}%</text>
				</view>
			</view>

			<!-- 称号墙（可折叠：默认展开，收起后只留一行） -->
			<view class="section-card">
				<view class="section-title-row" @click="titlesExpanded = !titlesExpanded">
					<text class="section-title">称号</text>
					<view class="section-title-action">
						<text class="section-sub">{{ calcWornTitle ? '佩戴中：' + calcWornTitle.name : '达标自动解锁 · 点击佩戴' }}</text>
						<text class="section-toggle">{{ titlesExpanded ? '收起 ⌃' : '展开 ⌄' }}</text>
					</view>
				</view>
				<view v-if="titlesExpanded" class="title-grid">
					<view
						v-for="title in levelStatus.unlockedTitles"
						:key="title.id"
						class="title-item"
						:class="{ 'is-unlocked': title.unlocked, 'is-worn': title.worn, 'is-loading': claimingTitle === title.id }"
						@click="fnWearTitle(title)"
					>
						<text class="title-icon">{{ titleEmoji(title.type) }}</text>
						<text class="title-name">{{ title.name }}</text>
						<view v-if="!title.unlocked" class="title-progress-track">
							<view class="title-progress-fill" :style="{ width: title.percent + '%' }"></view>
						</view>
						<text v-if="!title.unlocked" class="title-progress">{{ title.progress }}/{{ title.target }}</text>
						<text class="title-desc">{{ title.unlocked ? (title.worn ? '佩戴中' : '点击佩戴') : title.desc }}</text>
					</view>
				</view>
				<view v-else class="title-collapsed-tip">称号墙已收起，共 {{ levelStatus.unlockedTitles.length }} 个称号</view>
			</view>

			<view class="footer-tip">
					<text>等级按「累计获得积分」自动升级；称号达标自动解锁，点击即可佩戴展示；装扮在「装扮商店」购买后穿戴即时生效。</text>
				</view>
			</view>
		</template>

		<wx-login-dialog
			:show.sync="loginDialogShow"
			title="登录后查看身份中心"
			desc="登录后可查看等级、称号与装扮"
			@success="fnOnLoginSuccess"
		></wx-login-dialog>
		<points-toast></points-toast>
	</view>
</template>

<script>
	import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
	import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
	import wxLoginDialog from '@/components/wx-login-dialog/wx-login-dialog.vue';
	import pointsToast from '@/components/points-toast/points-toast.vue';
	import { checkHasWxLogin, getWxLoginInfo } from '@/utils/auth.js';
	// 本页只做装扮「展示」（头像框 / 昵称前缀）与等级称号；购买与穿戴都已移到权益直购页
	import { avatarFrameClass, isUnregisteredAvatarFrame, nickFramePrefix } from '@/utils/outfitSkin.js';
	import { applyThemeSkin } from '@/utils/themeSkin.js';
	import { cacheOwnOutfit } from '@/utils/ownOutfit.js';

	const EMPTY_LEVEL = {
		level: 1,
		levelTitle: '',
		totalEarned: 0,
		nextPoints: 0,
		unlockedTitles: []
	};

	const EMPTY_OUTFIT = {
		items: [],
		owned: [],
		ownedDetails: [],
		orphanOwned: [],
		avatarFrame: '',
		nickFrame: '',
		theme: ''
	};

	/** 称号类型 -> 图标 */
	const TITLE_ICONS = {
		total_browse: '📖',
		sign_in: '📅',
		vote: '🗳️',
		comment: '💬',
		invite: '🤝',
		total_points: '💎'
	};

	export default {
		components: {
			tmSkeleton,
			tmEmpty,
			tmButton,
			wxLoginDialog,
			pointsToast
		},
		data() {
			return {
				loading: 'loading',
				isLoggedIn: false,
				loginDialogShow: false,
				levelStatus: { ...EMPTY_LEVEL },
				outfitStatus: { ...EMPTY_OUTFIT },
				claimingTitle: '',
				/** 称号墙展开态（默认展开；不持久化，每次进入按默认值） */
				titlesExpanded: true,
				outfitBusyKey: '',
				// 装扮现金价：装扮 key → { goodsId, price }（来自支付配置，见 fnLoadCashGoods）
				cashGoodsMap: {}
			};
		},
		computed: {
			userInfo() {
				return getWxLoginInfo() || {};
			},
			levelPercent() {
				const status = this.levelStatus;
				if (!status.nextPoints) return 100;
				const span = Math.max(1, status.nextMin - status.currentMin);
				const passed = Math.min(span, Math.max(0, status.totalEarned - status.currentMin));
				return Math.min(100, Math.round((passed / span) * 100));
			},
			themeStyle() {
				const theme = this.outfitStatus.theme;
				if (theme && String(theme).includes(',')) {
					const [from, to] = String(theme).split(',');
					return { background: `linear-gradient(135deg, ${from} 0%, ${to} 100%)` };
				}
				return {};
			},
			headAvatarFrameClass() {
				// 注册表化：未登记的样式回退无框（页面另行提示站长配置错误）。
				return avatarFrameClass(this.outfitStatus.avatarFrame);
			},
			avatarFrameUnregistered() {
				return isUnregisteredAvatarFrame(this.outfitStatus.avatarFrame);
			},
			/** 头部昵称带前缀装饰：与评论列表同一解析口径（注册表 → 透传 style 文本）。 */
			headDisplayName() {
				const nickname = this.userInfo.nickname || '微信用户';
				const prefix = nickFramePrefix(this.outfitStatus.nickFrame);
				return prefix ? `${prefix} ${nickname}` : nickname;
			},
			calcWornTitle() {
				const wornId = this.levelStatus.wornTitleId;
				if (!wornId) return null;
				const found = (this.levelStatus.unlockedTitles || []).find(t => t.id === wornId);
				if (!found) return null;
				return {
					name: found.name,
					icon: this.titleEmoji(found.type || '')
				};
			}
		},
		onLoad() {
			this.isLoggedIn = checkHasWxLogin();
			if (this.isLoggedIn) {
				this.fnLoad();
			}
			uni.$on('points_updated', this.fnOnPointsUpdated);
		},
		onUnload() {
			uni.$off('points_updated', this.fnOnPointsUpdated);
		},
		onPullDownRefresh() {
			this.fnLoad().finally(() => uni.stopPullDownRefresh());
		},
		methods: {
			async fnLoad() {
				try {
					const [levelRes, outfitRes] = await Promise.all([
						this.$httpApi.v2.getLevelStatus(),
						this.$httpApi.v2.getOutfitStatus()
					]);
					this.levelStatus = this.normalizeLevel(levelRes);
					this.outfitStatus = this.normalizeOutfit(outfitRes);
					// 穿戴装扮后立即生效，无需重启小程序（购买/穿戴入口在权益直购页，本页负责应用）
					applyThemeSkin(this.outfitStatus.theme);
					// 同步本地装扮缓存：评论列表/输入弹窗等页面读它渲染头像框
					cacheOwnOutfit(this.outfitStatus);
					this.loading = 'success';
				} catch (err) {
					this.loading = 'error';
					uni.showToast({ title: err?.error || '加载失败，请稍后重试', icon: 'none' });
				}
			},
			normalizeLevel(res) {
				const data = res?.data || res || {};
				// 投票玩法已整体下线：后台配置里若还留着投票类称号（如「民意代表」），
				// 称号墙不能再把它挂出来——否则等于在小程序里宣称提供投票服务。
				const titles = (Array.isArray(data.unlockedTitles) ? data.unlockedTitles : [])
					.filter(t => t?.type !== 'vote')
					.map(t => {
					const target = Math.max(1, Number(t.target || 1));
					const progress = Math.min(target, Number(t.progress || 0));
					return {
						...t,
						progress,
						percent: Math.round((progress / target) * 100)
					};
				});
				return {
					enabled: data.enabled !== false,
					level: Number(data.level || 1),
					levelTitle: data.levelTitle || '',
					totalEarned: Number(data.totalEarned || 0),
					currentMin: Number(data.currentMin || 0),
					nextMin: Number(data.nextMin || 0),
					nextPoints: Number(data.nextPoints || 0),
					wornTitleId: data.wornTitleId || '',
					unlockedTitles: titles
				};
			},
			normalizeOutfit(res) {
				const data = res?.data || res || {};
				return {
					enabled: data.enabled !== false,
					items: Array.isArray(data.items) ? data.items : [],
					owned: Array.isArray(data.owned) ? data.owned : [],
					// 到期明细：插件 v1.0.98+ 才下发，旧版插件下为空数组，
					// 此时按「永久」展示，不影响老版本组合使用。
					ownedDetails: Array.isArray(data.ownedDetails) ? data.ownedDetails : [],
					orphanOwned: Array.isArray(data.orphanOwned) ? data.orphanOwned : [],
					avatarFrame: data.avatarFrame || '',
					nickFrame: data.nickFrame || '',
					theme: data.theme || ''
				};
			},
			titleEmoji(type) {
				return TITLE_ICONS[type] || '🏅';
			},
			async fnWearTitle(title) {
				if (!title?.unlocked || title?.worn || this.claimingTitle) return;
				this.claimingTitle = title.id;
				try {
					const res = await this.$httpApi.v2.wearLevelTitle(title.id);
					const data = res?.data || res || {};
					if (data?.error) {
						uni.showToast({ title: data.error, icon: 'none' });
						return;
					}
					uni.showToast({ title: `已佩戴「${title.name}」`, icon: 'none' });
					this.levelStatus = this.normalizeLevel(res);
				} catch (err) {
					uni.showToast({ title: err?.error || '佩戴失败，请稍后重试', icon: 'none' });
				} finally {
					this.claimingTitle = '';
				}
			},
			fnOnPointsUpdated() {
				if (this.loading === 'success') {
					this.fnLoad();
				}
			},
			fnOnLoginSuccess() {
				this.isLoggedIn = true;
				this.fnLoad();
			},
			fnBackHome() {
				uni.switchTab({ url: '/pages/tabbar/home/home' });
			}
		}
	};
</script>

<style scoped lang="scss">
	.app-page {
		min-height: 100vh;
		background: linear-gradient(180deg, #eef1fa 0%, $uh-fill 260rpx);
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

	/* ===== 身份卡（合并原「身份头部 + 等级卡 + 佩戴称号横幅」） =====
	   一张卡承载：头像框/昵称前缀展示、等级称号、累计积分与升级进度。 */
	.identity-card {
		margin-bottom: 20rpx;
		padding: 32rpx 28rpx 28rpx;
		border-radius: 24rpx;
		background-image: $uh-grad-accent;
		color: #fff;
		box-shadow: 0 12rpx 32rpx rgba(47, 84, 235, 0.35);
	}

	.identity-head {
		display: flex;
		align-items: center;
	}

	/* 昵称下方的等级 / 佩戴称号徽标 */
	.identity-badges {
		display: flex;
		align-items: center;
		flex-wrap: wrap;
		margin-top: 8rpx;
	}

	.identity-level {
		font-size: 20rpx;
		color: #fff;
		background: rgba(255, 255, 255, 0.22);
		padding: 2rpx 14rpx;
		border-radius: 999rpx;
	}

	.identity-worn {
		margin-left: 10rpx;
		font-size: 20rpx;
		color: #ffe9b0;
		background: rgba(255, 214, 102, 0.22);
		padding: 2rpx 14rpx;
		border-radius: 999rpx;
	}

	/* 等级进度：一条线表达「累计积分 + 距下一级」 */
	.identity-progress {
		margin-top: 24rpx;
		padding-top: 20rpx;
		border-top: 1rpx solid rgba(255, 255, 255, 0.22);
	}

	.identity-progress-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		font-size: 22rpx;
		color: rgba(255, 255, 255, 0.9);
	}

	.identity-progress-label {
		font-size: 22rpx;
		color: rgba(255, 255, 255, 0.92);
	}

	.identity-progress-percent {
		display: block;
		margin-top: 10rpx;
		font-size: 20rpx;
		color: rgba(255, 255, 255, 0.7);
	}

	/* 称号墙折叠后的提示 */
	.section-title-action {
		display: flex;
		align-items: center;
	}

	.section-toggle {
		margin-left: 12rpx;
		font-size: 22rpx;
		color: #7c6bd6;
	}

	.title-collapsed-tip {
		padding: 16rpx 0 4rpx;
		font-size: 22rpx;
		color: #b0b5c0;
	}

	.identity-avatar {
		width: 108rpx;
		height: 108rpx;
		border-radius: 50%;
		background: $uh-card;
		border: 3rpx solid rgba(255, 255, 255, 0.65);
		display: flex;
		align-items: center;
		justify-content: center;
		overflow: hidden;
		flex-shrink: 0;

		&.frame-gold {
			border: 6rpx solid #f5c542;
			box-shadow: 0 0 20rpx rgba(245, 197, 66, 0.55);
		}

		&.frame-rainbow {
			border: 6rpx solid transparent;
			background: linear-gradient(#fff, #fff) padding-box,
				linear-gradient(135deg, #f79ea3, #9b8cff, #79d6f2) border-box;
			box-shadow: 0 0 20rpx rgba(155, 140, 255, 0.45);
		}

		&.frame-silver {
			border: 6rpx solid #c3cbd8;
			box-shadow: 0 0 20rpx rgba(195, 203, 216, 0.5);
		}

		image {
			width: 100%;
			height: 100%;
		}
	}

	.identity-avatar-placeholder {
		font-size: 44rpx;
	}

	.identity-head-main {
		margin-left: 24rpx;
		min-width: 0;
	}

	.identity-nickname {
		display: block;
		font-size: 34rpx;
		font-weight: 700;
		color: #ffffff;
	}

	/* 头像框样式未登记：配置错误需显式提示，避免用户以为装扮失效 */
	.identity-invalid {
		display: block;
		margin-top: 6rpx;
		font-size: 21rpx;
		color: #ffe9b0;
	}

	.level-track {
		margin-top: 14rpx;
		height: 14rpx;
		border-radius: 7rpx;
		background: rgba(255, 255, 255, 0.25);
		overflow: hidden;
	}

	.level-fill {
		height: 100%;
		border-radius: 7rpx;
		background: linear-gradient(90deg, #ffffff 0%, #fff7d6 100%);
	}

	/* ===== 通用卡片 ===== */
	.section-card {
		background: $uh-card;
		border-radius: 20rpx;
		padding: 28rpx;
		margin-bottom: 20rpx;
		box-shadow: 0 6rpx 20rpx rgba(31, 41, 55, 0.05);
	}

	.section-title-row {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		margin-bottom: 22rpx;
	}

	.section-title {
		font-size: 30rpx;
		font-weight: 700;
		color: $uh-ink-1;
	}

	.section-sub {
		font-size: 20rpx;
		color: $uh-ink-3;
	}

	/* ===== 称号墙 ===== */
	.title-grid {
		display: grid;
		grid-template-columns: repeat(3, 1fr);
		gap: 14rpx;
	}

	.title-item {
		position: relative;
		min-width: 0;
		box-sizing: border-box;
		padding: 24rpx 12rpx 18rpx;
		border-radius: 16rpx;
		background: $uh-fill;
		border: 2rpx solid #f0f1f5;
		text-align: center;

		&.is-loading {
			opacity: 0.6;
		}

		&.is-unlocked {
			background: linear-gradient(160deg, #eef4ff 0%, #f6f0ff 100%);
			border-color: #c7d4fb;
		}

		&.is-worn {
			background: linear-gradient(160deg, #ffecb3 0%, #ffe08a 100%);
			border-color: #f5c542;

			.title-name {
				color: #7a5c12;
			}

			.title-desc {
				color: #a8822a;
			}
		}
	}

	.title-icon {
		display: block;
		font-size: 40rpx;
		line-height: 1.2;
	}

	.title-name {
		display: block;
		margin-top: 8rpx;
		font-size: 24rpx;
		font-weight: 700;
		color: #3a3d46;
	}

	.title-progress-track {
		margin-top: 10rpx;
		height: 8rpx;
		border-radius: 4rpx;
		background: #e8eaef;
		overflow: hidden;
	}

	.title-progress-fill {
		height: 100%;
		border-radius: 4rpx;
		background: linear-gradient(90deg, #a5b4fc, #c4b5fd);
	}

	.title-progress {
		display: block;
		margin-top: 6rpx;
		font-size: 18rpx;
		color: #9aa0ae;
	}

	.title-desc {
		display: block;
		margin-top: 4rpx;
		font-size: 18rpx;
		color: #b0b5c0;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.outfit-action > * + * {
		margin-top: 12rpx;
	}

	.footer-tip {
		margin-top: 8rpx;
		padding: 24rpx;
		border-radius: 12rpx;
		background: #fff7e6;
	}

	.footer-tip text {
		display: block;
		font-size: 22rpx;
		line-height: 1.7;
		color: #b3800a;
	}

@import '@/common/styles/redesign/pagesA-identity-identity-themes.scss';
</style>
