<template>
	<view class="app-page">
		<!-- 未登录 -->
		<view v-if="!isLoggedIn" class="login-gate flex flex-center flex-col">
			<tm-empty icon="icon-user" label="登录后查看身份中心"></tm-empty>
			<tm-button class="mt-24" theme="bg-gradient-light-blue-accent" :shadow="4" @click="loginDialogShow = true">
				微信登录
			</tm-button>
			<tm-button class="mt-16" plan theme="grey" :shadow="0" @click="fnBackHome">返回首页</tm-button>
		</view>
		<template v-else>
			<view v-if="loading === 'loading'" class="loading-wrap">
				<tm-skeleton model="card"></tm-skeleton>
				<tm-skeleton model="list"></tm-skeleton>
			</view>
			<view v-else-if="loading === 'error'" class="content-empty flex flex-center flex-col" style="min-height: 60vh;">
				<tm-empty icon="icon-shiliangzhinengduixiang-" label="加载失败"></tm-empty>
				<tm-button class="mt-24" plan theme="grey" :shadow="0" @click="fnLoad">重新加载</tm-button>
			</view>
			<view v-else class="content">
				<!-- 身份头部 -->
				<view class="identity-head">
					<view class="identity-avatar" :class="headAvatarFrameClass">
						<image v-if="userInfo.avatarUrl" :src="userInfo.avatarUrl" mode="aspectFill"></image>
						<text v-else class="identity-avatar-placeholder">👤</text>
					</view>
					<view class="identity-head-main">
						<text class="identity-nickname">{{ userInfo.nickname || '微信用户' }}</text>
						<text class="identity-sub">我的身份 · Lv.{{ levelStatus.level }} {{ levelStatus.levelTitle }}</text>
					</view>
				</view>

				<!-- 等级卡 -->
				<view class="level-card" :style="themeStyle">
					<view class="level-badge">
						<text class="level-badge-num">{{ levelStatus.level }}</text>
						<text class="level-badge-label">LV</text>
					</view>
					<view class="level-card-main">
						<view class="level-card-top">
							<text class="level-title">{{ levelStatus.levelTitle }}</text>
							<text class="level-points">累计 {{ levelStatus.totalEarned }} 分</text>
						</view>
						<view class="level-earned">
							<text v-if="levelStatus.nextPoints > 0">距 Lv.{{ levelStatus.level + 1 }} 还差 {{ levelStatus.nextPoints }} 积分</text>
							<text v-else>已是最高等级</text>
							<text v-if="levelStatus.nextPoints > 0" class="level-percent">已完成 {{ levelPercent }}%</text>
						</view>
						<view v-if="levelStatus.nextPoints > 0" class="level-track">
							<view class="level-fill" :style="{ width: levelPercent + '%' }"></view>
						</view>
					</view>
				</view>

				<!-- 佩戴称号横幅 -->
				<view v-if="calcWornTitle" class="worn-banner">
					<text class="worn-banner-icon">{{ calcWornTitle.icon }}</text>
					<view class="worn-banner-main">
						<text class="worn-banner-name">{{ calcWornTitle.name }}</text>
						<text class="worn-banner-sub">已佩戴的称号，可随时更换</text>
					</view>
					<text class="worn-banner-flag">佩戴中</text>
				</view>

				<!-- 称号墙 -->
				<view class="section-card">
					<view class="section-title-row">
						<text class="section-title">称号</text>
						<text class="section-sub">达标自动解锁 · 点击佩戴</text>
					</view>
					<view class="title-grid">
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
				</view>

				<!-- 装扮商店 -->
				<view class="section-card">
					<view class="section-title-row">
						<text class="section-title">装扮商店</text>
						<text class="section-sub">积分兑换 · 永久持有</text>
					</view>
					<view v-if="outfitStatus.items.length === 0" class="empty-tip">暂无装扮，请在插件后台「等级与称号装扮」中配置。</view>
					<view v-for="item in outfitStatus.items" :key="item.key" class="outfit-item" :class="{ 'is-active': isActive(item) }">
						<view class="outfit-preview" :class="outfitPreviewClass(item)">
							<image v-if="item.type === 'avatar_frame'" class="outfit-avatar" :src="previewAvatar" mode="aspectFill"></image>
							<text v-if="item.type === 'nick_frame'" class="outfit-nick">{{ item.style }}</text>
							<view v-if="item.type === 'theme'" class="outfit-theme" :style="{ background: themeSwatchStyle(item.style) }"></view>
						</view>
						<view class="outfit-main">
							<view class="outfit-title-row">
								<text class="outfit-name">{{ item.name }}</text>
								<text class="outfit-tag">{{ outfitTypeLabel(item.type) }}</text>
							</view>
							<text class="outfit-price">{{ item.points }} 积分<text v-if="isActive(item)" class="outfit-using"> · 使用中</text></text>
						</view>
						<view class="outfit-action">
							<tm-button
								size="s"
								:theme="outfitButtonTheme(item)"
								:disabled="outfitBusy(item)"
								:loading="outfitBusy(item)"
								@click="fnOutfitAction(item)"
							>{{ outfitButtonText(item) }}</tm-button>
						</view>
					</view>
				</view>

				<view class="footer-tip">
					<text>等级按「累计获得积分」自动升级；称号达标自动解锁，可佩戴展示；装扮购买后永久持有，穿戴即时生效。</text>
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
	import { emitPointsUpdated } from '@/utils/points.js';

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
				outfitBusyKey: ''
			};
		},
		computed: {
			userInfo() {
				return getWxLoginInfo() || {};
			},
			previewAvatar() {
				return this.userInfo.avatarUrl || '';
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
				const frame = this.outfitStatus.avatarFrame;
				if (frame === 'gold') return 'frame-gold';
				if (frame === 'rainbow') return 'frame-rainbow';
				return '';
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
					this.loading = 'success';
				} catch (err) {
					this.loading = 'error';
					uni.showToast({ title: err?.error || '加载失败，请稍后重试', icon: 'none' });
				}
			},
			normalizeLevel(res) {
				const data = res?.data || res || {};
				const titles = (Array.isArray(data.unlockedTitles) ? data.unlockedTitles : []).map(t => {
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
					avatarFrame: data.avatarFrame || '',
					nickFrame: data.nickFrame || '',
					theme: data.theme || ''
				};
			},
			titleEmoji(type) {
				return TITLE_ICONS[type] || '🏅';
			},
			outfitTypeLabel(type) {
				return { avatar_frame: '头像框', nick_frame: '昵称前缀', theme: '主题色' }[type] || type;
			},
			themeSwatchStyle(style) {
				if (String(style).includes(',')) {
					const [from, to] = String(style).split(',');
					return `linear-gradient(135deg, ${from}, ${to})`;
				}
				return style;
			},
			outfitPreviewClass(item) {
				if (item.type === 'avatar_frame' && item.style === 'gold') return 'is-frame-gold';
				if (item.type === 'avatar_frame' && item.style === 'rainbow') return 'is-frame-rainbow';
				return '';
			},
			isOwned(item) {
				return this.outfitStatus.owned.includes(item.key);
			},
			isActive(item) {
				if (!this.isOwned(item)) return false;
				if (item.type === 'avatar_frame') return this.outfitStatus.avatarFrame === item.style;
				if (item.type === 'nick_frame') return this.outfitStatus.nickFrame === item.style;
				if (item.type === 'theme') return this.outfitStatus.theme === item.style;
				return false;
			},
			outfitButtonText(item) {
				if (this.isActive(item)) return '脱下';
				if (this.isOwned(item)) return '穿戴';
				return `购买 · ${item.points}`;
			},
			outfitButtonTheme(item) {
				if (this.isActive(item)) return 'grey';
				if (this.isOwned(item)) return 'primary';
				return 'green';
			},
			outfitBusy(item) {
				return this.outfitBusyKey === item.key;
			},
			async fnOutfitAction(item) {
				if (this.outfitBusyKey) return;
				this.outfitBusyKey = item.key;
				try {
					if (!this.isOwned(item)) {
						const res = await this.$httpApi.v2.buyOutfitItem(item.key);
						const data = res?.data || res || {};
						if (data?.error && !data?.owned) {
							uni.showToast({ title: data.error, icon: 'none' });
							return;
						}
						uni.showToast({ title: data?.message || '购买成功', icon: 'none' });
						if (data?.balance != null) {
							emitPointsUpdated({ balance: Number(data.balance) });
						}
						await this.fnLoad();
						return;
					}
					const res = await this.$httpApi.v2.equipOutfitItem(this.isActive(item) ? '' : item.key);
					const data = res?.data || res || {};
					if (data?.error) {
						uni.showToast({ title: data.error, icon: 'none' });
						return;
					}
					uni.showToast({ title: data?.message || '已更新装扮', icon: 'none' });
					await this.fnLoad();
				} catch (err) {
					uni.showToast({ title: err?.error || '操作失败，请稍后重试', icon: 'none' });
				} finally {
					this.outfitBusyKey = '';
				}
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
		background: linear-gradient(180deg, #eef1fa 0%, #f5f6fa 260rpx);
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

	/* ===== 身份头部 ===== */
	.identity-head {
		display: flex;
		align-items: center;
		padding: 12rpx 8rpx 24rpx;
	}

	.identity-avatar {
		width: 108rpx;
		height: 108rpx;
		border-radius: 50%;
		background: #fff;
		border: 3rpx solid #e8e8ee;
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
		color: #303133;
	}

	.identity-sub {
		display: block;
		margin-top: 6rpx;
		font-size: 22rpx;
		color: #909399;
	}

	/* ===== 等级卡 ===== */
	.level-card {
		display: flex;
		align-items: center;
		padding: 32rpx 28rpx;
		border-radius: 24rpx;
		background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
		color: #fff;
		margin-bottom: 20rpx;
		box-shadow: 0 12rpx 32rpx rgba(102, 126, 234, 0.35);
	}

	.level-badge {
		width: 96rpx;
		height: 96rpx;
		border-radius: 50%;
		background: rgba(255, 255, 255, 0.16);
		border: 4rpx solid rgba(255, 255, 255, 0.65);
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		flex-shrink: 0;
		margin-right: 24rpx;
	}

	.level-badge-num {
		font-size: 40rpx;
		font-weight: 800;
		line-height: 1;
	}

	.level-badge-label {
		margin-top: 4rpx;
		font-size: 18rpx;
		letter-spacing: 2rpx;
		opacity: 0.85;
	}

	.level-card-main {
		flex: 1;
		min-width: 0;
	}

	.level-card-top {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: 16rpx;
	}

	.level-title {
		font-size: 32rpx;
		font-weight: 700;
	}

	.level-points {
		font-size: 22rpx;
		opacity: 0.85;
	}

	.level-earned {
		display: flex;
		justify-content: space-between;
		align-items: baseline;
		margin-top: 10rpx;
		font-size: 22rpx;
		opacity: 0.85;
	}

	.level-percent {
		font-weight: 600;
		opacity: 1;
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

	/* ===== 佩戴称号横幅 ===== */
	.worn-banner {
		display: flex;
		align-items: center;
		padding: 24rpx 28rpx;
		border-radius: 20rpx;
		background: linear-gradient(135deg, #fff9e6 0%, #fff3cc 100%);
		border: 2rpx solid #f5c542;
		margin-bottom: 20rpx;
	}

	.worn-banner-icon {
		font-size: 52rpx;
		margin-right: 20rpx;
	}

	.worn-banner-main {
		flex: 1;
		min-width: 0;
	}

	.worn-banner-name {
		display: block;
		font-size: 28rpx;
		font-weight: 700;
		color: #8a6d1a;
	}

	.worn-banner-sub {
		display: block;
		margin-top: 4rpx;
		font-size: 20rpx;
		color: #b08d33;
	}

	.worn-banner-flag {
		font-size: 20rpx;
		font-weight: 600;
		color: #8a6d1a;
		background: rgba(245, 197, 66, 0.25);
		border-radius: 999rpx;
		padding: 6rpx 18rpx;
	}

	/* ===== 通用卡片 ===== */
	.section-card {
		background: #fff;
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
		color: #303133;
	}

	.section-sub {
		font-size: 20rpx;
		color: #909399;
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
		background: #f7f8fa;
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

	/* ===== 装扮商店 ===== */
	.empty-tip {
		font-size: 22rpx;
		line-height: 1.6;
		color: #909399;
	}

	.outfit-item {
		display: flex;
		align-items: center;
		padding: 20rpx;
		border-radius: 16rpx;
		background: #fbfbfd;
		border: 2rpx solid #f0f1f5;
		margin-bottom: 16rpx;

		&:last-child {
			margin-bottom: 0;
		}

		&.is-active {
			background: #f2fbe8;
			border-color: #a8e06a;
		}
	}

	.outfit-preview {
		position: relative;
		width: 100rpx;
		height: 100rpx;
		border-radius: 20rpx;
		background: #f3f4f7;
		display: flex;
		align-items: center;
		justify-content: center;
		flex-shrink: 0;
		overflow: hidden;

		&.is-frame-gold {
			border-radius: 50%;
			border: 5rpx solid #f5c542;
			box-shadow: 0 0 14rpx rgba(245, 197, 66, 0.4);
		}

		&.is-frame-rainbow {
			border-radius: 50%;
			border: 5rpx solid transparent;
			background: linear-gradient(#f3f4f7, #f3f4f7) padding-box,
				linear-gradient(135deg, #f79ea3, #9b8cff, #79d6f2) border-box;
			box-shadow: 0 0 14rpx rgba(155, 140, 255, 0.35);
		}
	}

	.outfit-avatar {
		width: 100%;
		height: 100%;
	}

	.outfit-nick {
		font-size: 52rpx;
		line-height: 1;
	}

	.outfit-theme {
		position: absolute;
		left: 0;
		top: 0;
		right: 0;
		bottom: 0;
	}

	.outfit-main {
		flex: 1;
		min-width: 0;
		margin: 0 16rpx;
	}

	.outfit-title-row {
		display: flex;
		align-items: center;
		gap: 10rpx;
	}

	.outfit-name {
		font-size: 28rpx;
		font-weight: 600;
		color: #303133;
	}

	.outfit-tag {
		font-size: 18rpx;
		color: #909399;
		background: #eef0f4;
		padding: 2rpx 10rpx;
		border-radius: 6rpx;
	}

	.outfit-price {
		display: block;
		margin-top: 8rpx;
		font-size: 22rpx;
		color: #f56c6c;
	}

	.outfit-using {
		color: #67a23a;
		font-weight: 600;
	}

	.outfit-action {
		flex-shrink: 0;
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
</style>
