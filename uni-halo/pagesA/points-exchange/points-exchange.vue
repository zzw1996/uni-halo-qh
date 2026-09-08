<template>
	<view class="app-page">
		<view v-if="loading !== 'success'" class="loading-wrap">
			<tm-skeleton model="listAvatr"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
		</view>
		<block v-else>
			<view class="balance-card">
				<text class="balance-label">当前积分</text>
				<text class="balance-value">{{ balance }}</text>
				<text class="balance-sub">积分仅限站内权益兑换，详见下方说明</text>
				</view>

				<!-- 幸运礼盒（实物/活动奖品，与每日翻牌抽积分区分） -->
			<view v-if="luckyEnabled" class="lucky-card">
				<view class="lucky-head">
					<text class="lucky-emoji">🎁</text>
					<view class="lucky-head-main">
						<text class="lucky-title">幸运礼盒</text>
						<text class="lucky-sub">抽实物/周边奖品 · 翻牌赢积分，礼盒抽奖品</text>
					</view>
				</view>
				<scroll-view scroll-x class="lucky-prizes-scroll" :show-scrollbar="false">
					<view class="lucky-prizes">
						<view v-for="(prize, idx) in luckyPrizes" :key="idx" class="lucky-prize">
							<text class="lucky-prize-emoji">{{ prize.emoji }}</text>
							<text class="lucky-prize-name">{{ prize.name }}</text>
						</view>
					</view>
				</scroll-view>
				<tm-button theme="orange" :block="true" :loading="luckyDrawing" @click="fnDrawLucky">
					开启礼盒（消耗 {{ luckyCost }} 积分）
				</tm-button>
				<text class="lucky-tip">中奖记录可在「积分明细」查看（积分抽奖·奖品名），奖品由站长人工发放</text>
				</view>

				<!-- 幸运礼盒奖品说明 -->
				<view v-if="luckyDescList.length" class="lucky-desc-card">
					<text class="lucky-desc-title">🎁 奖品说明</text>
					<text v-for="(prize, idx) in luckyDescList" :key="idx" class="lucky-desc-item">{{ prize.name }}：{{ prize.desc }}</text>
				</view>

				<!-- 我的中奖礼品（发放进度） -->
				<view v-if="myClaims.length" class="my-claim-card">
					<view class="my-claim-head">
						<text class="my-claim-title">我的中奖礼品</text>
						<text class="my-claim-sub">点击查看发放状态与物流信息</text>
					</view>
					<view v-for="(claim, idx) in myClaims" :key="idx" class="my-claim-item" :class="{ 'my-claim-item-pending': claim.status === 'pending' }" hover-class="my-claim-item-hover" @click="fnGoClaim(idx)">
						<text class="my-claim-emoji">{{ claim.emoji || '🎁' }}</text>
						<view class="my-claim-main">
							<text class="my-claim-name">{{ claim.prizeName }}</text>
							<text class="my-claim-time">{{ claim.winTime ? (claim.winTime | formatTime) : '—' }}</text>
							<text v-if="claim.status === 'shipped'" class="my-claim-express">
								{{ claim.expressCompany || '快递' }} {{ claim.expressNo || '（无物流单号）' }} · {{ claim.shipTime ? (claim.shipTime | formatTime) : '' }} 发货
							</text>
						</view>
						<text class="my-claim-tag" :class="fnLuckyTagClass(claim.status)">{{ fnLuckyStatusText(claim.status) }}</text>
						<text class="my-claim-arrow">›</text>
					</view>
				</view>

			<view v-if="exchangeItems.length === 0" class="tool-card">
				<text class="tool-card-tip">暂无兑换权益，请联系站长在插件后台「积分兑换权益」中配置。</text>
			</view>
			<view v-for="(item, index) in exchangeItems" :key="item.key" class="tool-card">
				<view class="tool-card-head">
					<view class="tool-card-title-row">
						<text class="tool-card-title">{{ item.name }}</text>
						<tm-tags
							v-if="item.dailyMaxCount > 0 && item.todayCount >= item.dailyMaxCount"
							color="bg-gradient-light-blue-accent" :shadow="0" size="s" model="fill"
						>今日已兑换</tm-tags>
					</view>
					<text class="tool-card-tip">{{ item.description || `每次 ${item.points} 积分，每日最多 ${item.dailyMaxCount} 次` }}</text>
					<text v-if="item.todayCount > 0" class="tool-card-tip">今日已兑换 {{ item.todayCount }}/{{ item.dailyMaxCount }} 次</text>
				</view>
				<view class="tool-actions">
					<tm-button
						size="m"
						theme="primary"
						:disabled="item.dailyMaxCount > 0 && item.todayCount >= item.dailyMaxCount"
						:loading="exchanging === item.key"
						@click="fnExchange(item.key)"
					>{{ item.dailyMaxCount > 0 && item.todayCount >= item.dailyMaxCount ? '今日已兑完' : '兑换（' + item.points + ' 积分）' }}</tm-button>
				</view>
			</view>

				<view class="footer-tip">
					<text>积分仅限站内权益兑换（当前：{{ exchangeItems.map((i) => i.name).join('、') || '暂无' }}），不可提现、不可转让、不可兑换现金或实物。</text>
					<text>兑换后权益当日有效（按权益说明为准）；兑换记录可在「积分明细」中查看。</text>
				</view>

				<!-- 最近消耗明细 -->
				<view class="history-card">
					<view class="history-head">
						<text class="history-title">最近消耗</text>
						<text class="history-more" @click="fnToHistory">全部明细 ›</text>
					</view>
					<view v-if="recentSpendList.length === 0" class="history-empty">暂无消耗记录</view>
					<view v-for="(item, idx) in recentSpendList" :key="idx" class="history-item">
						<view class="history-main">
							<text class="history-desc">{{ item.description || item.actionLabel }}</text>
							<text class="history-time">{{ item.createTimeText }}</text>
						</view>
						<text class="history-points">{{ item.pointsText }}</text>
					</view>
				</view>
		</block>

		<!-- 开盒动画：摇盒 → 爆开揭晓 -->
		<view v-if="boxAnim" class="box-mask" catchtouchmove>
			<view v-if="boxPhase === 'shaking'" class="box-stage">
				<text class="box-spark box-spark-a">✨</text>
				<text class="box-spark box-spark-b">🌟</text>
				<text class="box-spark box-spark-c">✨</text>
				<text class="box-gift-shake">🎁</text>
				<text class="box-hint">开盒中…</text>
			</view>
			<view v-else class="box-stage">
				<view class="box-confetti">
					<text v-for="(c, i) in confettiList" :key="i" class="box-confetti-item"
						:style="{ left: c.left, animationDelay: c.delay, animationDuration: c.duration }">{{ c.emoji }}</text>
				</view>
				<text class="box-burst">💥</text>
				<text class="box-prize pop">{{ boxResult ? (boxResult.emoji || '🎁') : '🎁' }}</text>
				<text class="box-title pop pop-2">{{ fnBoxTitle(boxResult) }}</text>
				<text v-if="boxResult && (boxResult.won || fnIsAgain(boxResult))" class="box-name pop pop-3">{{ boxResult.name }}</text>
				<text class="box-sub pop pop-3">{{ fnBoxSub(boxResult) }}</text>
				<view class="box-actions">
					<tm-button v-if="boxResult && boxResult.won && boxResult.needsAddress" theme="orange" :block="true" @click="fnGoClaimAfterDraw">
						填写收货信息
					</tm-button>
					<tm-button v-if="boxResult && fnIsAgain(boxResult)" theme="orange" :block="true" @click="fnAgainDraw">
						再抽一次
					</tm-button>
					<tm-button theme="orange" :block="true" @click="fnCloseBox">{{ fnBoxCloseText(boxResult) }}</tm-button>
				</view>
			</view>
		</view>
	</view>
</template>

<script>
	import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
	import tmTags from '@/tm-vuetify/components/tm-tags/tm-tags.vue';
	import { checkHasWxLogin } from '@/utils/auth.js';
	import { fetchExchangeBalance, exchangePointsItem, emitPointsUpdated, fetchPointsHistory, formatPointsHistoryItem } from '@/utils/points.js';

	export default {
		components: {
			tmSkeleton,
			tmButton,
			tmTags
		},
		data() {
			return {
				loading: 'loading',
				balance: 0,
				exchangeItems: [],
				exchanging: '',
				luckyEnabled: false,
				luckyCost: 20,
				luckyPrizes: [],
				luckyDrawing: false,
				pendingClaims: [],
				myClaims: [],
				recentSpendList: [],
				boxAnim: false,
				boxPhase: 'shaking',
				boxResult: null,
				confettiList: []
			};
		},
		computed: {
			/** 有说明文案的奖品（横向 pill 只留名称，说明统一放在礼盒下方） */
			luckyDescList() {
				return (this.luckyPrizes || []).filter((prize) => prize && prize.desc);
			}
		},
		onLoad() {
			if (!checkHasWxLogin()) {
				uni.showToast({ title: '请先登录', icon: 'none' });
				setTimeout(() => uni.navigateBack(), 800);
				return;
			}
			this.fnLoad();
			this.fnLoadLucky();
			this.fnLoadLuckyClaims();
			this.fnLoadRecentSpend();
		},
		onShow() {
			// 从中奖单页返回时刷新状态（避免列表显示过期状态）
			this.fnLoadLuckyClaims();
		},
		methods: {
			async fnLoad() {
				try {
					const status = await fetchExchangeBalance(this.$httpApi.v2);
					if (status.ok) {
						this.balance = status.balance != null ? status.balance : 0;
						this.exchangeItems = status.items || [];
					}
					this.loading = 'success';
				} catch (err) {
					this.loading = 'success';
					uni.showToast({ title: err?.error || '加载失败，请稍后重试', icon: 'none' });
				}
			},
			/** 幸运抽奖配置（开启才展示，失败静默） */
			async fnLoadLucky() {
				try {
					const res = await this.$httpApi.v2.getLuckyConfig();
					const data = res?.data || res || {};
					if (data?.enabled) {
						this.luckyEnabled = true;
						this.luckyCost = Number(data.costPoints || 20);
						this.luckyPrizes = Array.isArray(data.prizes) ? data.prizes : [];
					}
				} catch (err) {
					// 静默：接口不可用不影响兑换页
				}
			},
			/** 我的中奖单（待填写提醒 + 发放进度），失败静默 */
			async fnLoadLuckyClaims() {
				try {
					const res = await this.$httpApi.v2.getMyLuckyClaims();
					// 诊断：真实响应与字段（线上排查用）
					console.log('[uni-halo luckyMine] raw:', res);
					const raw = Array.isArray(res) ? res : (res?.data || res || {});
					const list = Array.isArray(raw) ? raw : (Array.isArray(raw.items) ? raw.items : []);
					if (list.length) {
						console.log('[uni-halo luckyMine] first item:', list[0]);
					}
					this.myClaims = list.map((item) => {
						const normalized = Object.assign({}, item);
						// 兼容新版 winningId / 旧接口 id 两种单号字段
						if (!normalized.winningId && item.id) {
							normalized.winningId = item.id;
						}
						return normalized;
					});
					this.pendingClaims = this.myClaims.filter((item) => item?.status === 'pending');
					// 诊断：条目为空时展示原始响应（线上排查用）
					if (list.length && this.myClaims.every((item) => !item || Object.keys(item).length === 0)) {
						uni.showModal({
							title: '🌈 中奖单数据诊断',
							content: '接口返回的条目是空对象/空值，原始响应：\n' + JSON.stringify(res).slice(0, 500),
							showCancel: false
						});
					}
				} catch (err) {
					console.warn('[uni-halo luckyMine] failed:', err || {});
					// 静默
				}
			},
			/** 中奖单状态文案：待填写/已提交待发货/已发货 */
			fnLuckyStatusText(status) {
				if (status === 'shipped') return '已发货';
				if (status === 'submitted') return '待发货';
				return '待填写';
			},
			/** 中奖单状态标签样式 */
			fnLuckyTagClass(status) {
				if (status === 'shipped') return 'my-claim-tag-done';
				if (status === 'submitted') return 'my-claim-tag-sent';
				return 'my-claim-tag-pending';
			},
			fnGoClaim(idx) {
				// 用索引从数组中取条目，避免编译产物事件参数解析问题（传对象会被解析成 null）
				const claim = this.myClaims[idx];
				if (!claim || !claim.winningId) {
					const keys = claim && typeof claim === 'object' ? Object.keys(claim) : [];
					console.warn('[uni-halo lucky] claim without winningId:', claim || null, 'keys:', keys);
					uni.showToast({ title: '中奖单数据异常，请下拉重试', icon: 'none' });
					return;
				}
				uni.navigateTo({
					url: `/pagesA/lucky-claim/lucky-claim?winningId=${encodeURIComponent(claim.winningId)}&name=${encodeURIComponent(claim.prizeName || '')}&emoji=${encodeURIComponent(claim.emoji || '🎁')}&status=${encodeURIComponent(claim.status || '')}`,
					fail: () => uni.showToast({ title: '页面打开失败，请重试', icon: 'none' })
				});
			},
			/** 最近消耗记录（最近 5 条扣分流水） */
			async fnLoadRecentSpend() {
				try {
					const data = await fetchPointsHistory(this.$httpApi.v2, { page: 0, size: 8 });
					const items = (data?.items || []).map(formatPointsHistoryItem)
						.filter((item) => item && Number(item.points) < 0)
						.slice(0, 5);
					this.recentSpendList = items;
				} catch (err) {
					// 静默
				}
			},
			fnToHistory() {
				uni.navigateTo({ url: '/pagesA/points-history/points-history' });
			},
			async fnDrawLucky() {
				if (this.luckyDrawing) return;
				this.luckyDrawing = true;
				// 开盒动画：先摇盒，结果出来后爆开揭晓
				this.boxAnim = true;
				this.boxPhase = 'shaking';
				this.boxResult = null;
				this.confettiList = [];
				try {
					const res = await this.$httpApi.v2.drawLuckyGift();
					const data = res?.data || res || {};
					if (data?.error) {
						this.boxAnim = false;
						uni.showToast({ title: data.error, icon: 'none' });
						return;
					}
					// 摇盒至少 900ms，避免一闪而过
					await new Promise((resolve) => setTimeout(resolve, 900));
					this.boxResult = data;
					this.boxPhase = 'reveal';
					if (data.won || this.fnIsAgain(data)) {
						this.fnBuildConfetti();
					}
					if (data.balance != null) {
						this.balance = Number(data.balance);
						emitPointsUpdated({ balance: this.balance });
						await this.fnLoad();
					}
					this.fnLoadLuckyClaims();
					this.fnLoadRecentSpend();
				} catch (err) {
					this.boxAnim = false;
					uni.showToast({ title: err?.error || '抽奖失败，请稍后重试', icon: 'none' });
				} finally {
					this.luckyDrawing = false;
				}
			},
			/** 开盒彩带：emoji 随机飘落 */
			fnBuildConfetti() {
				const EMOJIS = ['🎉', '✨', '⭐', '🌟', '🎊', '💫'];
				const list = [];
				for (let i = 0; i < 22; i++) {
					list.push({
						emoji: EMOJIS[i % EMOJIS.length],
						left: `${3 + Math.random() * 92}%`,
						delay: `${(i % 9) * 0.15}s`,
						duration: `${1.6 + Math.random() * 1.2}s`
					});
				}
				this.confettiList = list;
			},
			fnCloseBox() {
				this.boxAnim = false;
				this.boxPhase = 'shaking';
				this.boxResult = null;
				this.confettiList = [];
			},
			/** 是否抽中「再来一次」（与「感谢参与」区分） */
			fnIsAgain(result) {
				return !!result && !result.won && result.name === '再来一次';
			},
			fnBoxTitle(result) {
				if (!result) return '';
				if (result.won) return '恭喜中奖！';
				if (this.fnIsAgain(result)) return '再来一次！';
				return '谢谢参与';
			},
			fnBoxSub(result) {
				if (!result) return '';
				if (result.won) return result.message;
				if (this.fnIsAgain(result)) return '运气不错，抽中再来一次，要不要再开一盒？';
				return '再接再厉，下次好运！';
			},
			fnBoxCloseText(result) {
				if (result && result.won) return '收下礼品';
				return '知道了';
			},
			fnAgainDraw() {
				this.fnCloseBox();
				this.fnDrawLucky();
			},
			fnGoClaimAfterDraw() {
				if (!this.boxResult || !this.boxResult.winningId) return;
				const result = this.boxResult;
				this.fnCloseBox();
				uni.navigateTo({
					url: `/pagesA/lucky-claim/lucky-claim?winningId=${encodeURIComponent(result.winningId)}&name=${encodeURIComponent(result.name || '')}&emoji=${encodeURIComponent(result.emoji || '🎁')}`
				});
			},
			async fnExchange(itemKey) {
				if (this.exchanging || !itemKey) return;
				this.exchanging = itemKey;
				try {
					const result = await exchangePointsItem(this.$httpApi.v2, itemKey);
					if (!result.ok) {
						uni.showToast({ title: result.reason || '兑换失败', icon: 'none' });
						return;
					}
					uni.showToast({ title: result.message || '兑换成功', icon: 'success' });
					if (result.balance != null) {
						this.balance = result.balance;
						emitPointsUpdated({ balance: result.balance });
					}
					await this.fnLoad();
				} finally {
					this.exchanging = '';
				}
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

	.loading-wrap {
		padding: 24rpx;
	}

	.balance-card {
		padding: 32rpx 28rpx;
		border-radius: 20rpx;
		background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
		color: #fff;
		margin-bottom: 24rpx;
	}

	/* ===== 幸运抽奖卡 ===== */
	.lucky-card {
		padding: 28rpx;
		border-radius: 20rpx;
		background: linear-gradient(135deg, #ff9a56 0%, #ff5e7e 100%);
		color: #fff;
		margin-bottom: 24rpx;
	}

	.lucky-head {
		display: flex;
		align-items: center;
		gap: 16rpx;
	}

	.lucky-emoji {
		font-size: 52rpx;
	}

	.lucky-head-main {
		flex: 1;
		min-width: 0;
	}

	.lucky-title {
		display: block;
		font-size: 32rpx;
		font-weight: 700;
	}

	.lucky-sub {
		display: block;
		margin-top: 4rpx;
		font-size: 22rpx;
		opacity: 0.9;
	}

	.lucky-prizes-scroll {
		width: 100%;
		margin: 20rpx 0;
		white-space: nowrap;
	}

	.lucky-prizes {
		display: flex;
		flex-direction: row;
		align-items: center;
		padding: 4rpx 4rpx;
	}

	.lucky-prize {
		flex-shrink: 0;
		display: flex;
		align-items: center;
		gap: 8rpx;
		padding: 10rpx 18rpx;
		border-radius: 999rpx;
		background: rgba(255, 255, 255, 0.18);
		margin-right: 12rpx;
	}

	.lucky-prize-emoji {
		font-size: 26rpx;
		line-height: 1.4;
	}

	.lucky-prize-name {
		font-size: 22rpx;
		color: #fff;
		font-weight: 600;
		line-height: 1.3;
	}

	.lucky-tip {
		display: block;
		margin-top: 14rpx;
		font-size: 20rpx;
		opacity: 0.85;
		text-align: center;
	}

	/* ===== 奖品说明（礼盒下方） ===== */
	.lucky-desc-card {
		margin-bottom: 24rpx;
		padding: 22rpx 24rpx;
		border-radius: 14rpx;
		background: #fff;
	}

	.lucky-desc-title {
		display: block;
		font-size: 24rpx;
		font-weight: 700;
		color: #303133;
		margin-bottom: 8rpx;
	}

	.lucky-desc-item {
		display: block;
		font-size: 21rpx;
		line-height: 1.7;
		color: #909399;
	}

	/* ===== 待领取提醒 ===== */
	.claim-remind {
		display: flex;
		align-items: center;
		padding: 20rpx 24rpx;
		border-radius: 14rpx;
		background: #fff7e6;
		border: 2rpx solid #f5d68a;
		margin-bottom: 24rpx;
	}

	.claim-remind-emoji {
		font-size: 36rpx;
		margin-right: 14rpx;
	}

	.claim-remind-main {
		flex: 1;
		min-width: 0;
	}

	.claim-remind-title {
		display: block;
		font-size: 24rpx;
		font-weight: 700;
		color: #b3800a;
	}

	.claim-remind-sub {
		display: block;
		margin-top: 4rpx;
		font-size: 20rpx;
		color: #caa457;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.claim-remind-arrow {
		font-size: 30rpx;
		color: #caa457;
		margin-left: 8rpx;
	}

	/* ===== 我的礼品发放 ===== */
	.my-claim-card {
		margin-bottom: 24rpx;
		padding: 24rpx;
		border-radius: 16rpx;
		background: #fff;
	}

	.my-claim-head {
		margin-bottom: 8rpx;
	}

	.my-claim-title {
		display: block;
		font-size: 26rpx;
		font-weight: 700;
		color: #303133;
	}

	.my-claim-sub {
		display: block;
		margin-top: 4rpx;
		font-size: 20rpx;
		color: #909399;
	}

	.my-claim-item {
		display: flex;
		align-items: center;
		padding: 18rpx 0;
		border-bottom: 2rpx solid #f5f6fa;
	}

	.my-claim-item:last-child {
		border-bottom: none;
		padding-bottom: 0;
	}

	.my-claim-emoji {
		font-size: 40rpx;
		margin-right: 16rpx;
	}

	.my-claim-main {
		flex: 1;
		min-width: 0;
	}

	.my-claim-name {
		display: block;
		font-size: 26rpx;
		font-weight: 600;
		color: #303133;
	}

	.my-claim-time {
		display: block;
		margin-top: 4rpx;
		font-size: 20rpx;
		color: #909399;
	}

	.my-claim-express {
		display: block;
		margin-top: 4rpx;
		font-size: 20rpx;
		color: #3a9e3a;
	}

	.my-claim-arrow {
		font-size: 30rpx;
		color: #c0c4cc;
		margin-left: 8rpx;
	}

	.my-claim-item-pending {
		background: #fff7e6;
		border-radius: 12rpx;
		padding: 18rpx 12rpx;
	}

	.my-claim-item-hover {
		background: #f0f2f7;
	}

	.my-claim-tag {
		flex-shrink: 0;
		padding: 6rpx 16rpx;
		border-radius: 999rpx;
		font-size: 20rpx;
		margin-left: 8rpx;
	}

	.my-claim-tag-pending {
		background: #ffefe0;
		color: #e8862b;
	}

	.my-claim-tag-sent {
		background: #e8f1ff;
		color: #3a6ee0;
	}

	.my-claim-tag-done {
		background: #e6f7e6;
		color: #3a9e3a;
	}

	/* ===== 开盒动画 ===== */
	.box-mask {
		position: fixed;
		left: 0;
		top: 0;
		right: 0;
		bottom: 0;
		z-index: 9999;
		display: flex;
		align-items: center;
		justify-content: center;
		background: rgba(24, 24, 48, 0.88);
	}

	.box-stage {
		position: relative;
		display: flex;
		flex-direction: column;
		align-items: center;
		width: 100%;
		padding: 0 60rpx;
		box-sizing: border-box;
	}

	.box-gift-shake {
		font-size: 160rpx;
		line-height: 1.2;
		animation: box-shake 0.55s ease-in-out infinite;
	}

	@keyframes box-shake {
		0%, 100% { transform: rotate(-14deg) translateX(-10rpx); }
		25% { transform: rotate(10deg) translateX(10rpx); }
		50% { transform: rotate(-12deg) translateX(-8rpx); }
		75% { transform: rotate(12deg) translateX(8rpx); }
	}

	.box-hint {
		margin-top: 24rpx;
		font-size: 24rpx;
		color: #fff;
		opacity: 0.9;
		letter-spacing: 2rpx;
	}

	.box-spark {
		position: absolute;
		font-size: 44rpx;
		opacity: 0;
		animation: box-flash 1s ease-in-out infinite;
	}

	.box-spark-a { left: 22%; top: 28%; animation-delay: 0s; }
	.box-spark-b { right: 20%; top: 24%; animation-delay: 0.3s; }
	.box-spark-c { left: 32%; bottom: 32%; animation-delay: 0.55s; }

	@keyframes box-flash {
		0%, 100% { opacity: 0; transform: scale(0.5) rotate(-10deg); }
		50% { opacity: 1; transform: scale(1.2) rotate(10deg); }
	}

	.box-confetti {
		position: absolute;
		left: 0;
		right: 0;
		top: 0;
		bottom: 0;
		overflow: hidden;
		pointer-events: none;
	}

	.box-confetti-item {
		position: absolute;
		top: -40rpx;
		font-size: 30rpx;
		animation: box-fall linear infinite;
	}

	@keyframes box-fall {
		0% { transform: translateY(-60rpx) rotate(0deg); opacity: 1; }
		100% { transform: translateY(1400rpx) rotate(360deg); opacity: 0.15; }
	}

	.box-burst {
		font-size: 220rpx;
		line-height: 1.1;
		opacity: 0;
		animation: box-burst 0.7s cubic-bezier(0.2, 0.8, 0.3, 1.25) both;
	}

	@keyframes box-burst {
		0% { opacity: 0; transform: scale(0.2) rotate(-20deg); }
		45% { opacity: 1; transform: scale(1.35) rotate(8deg); }
		100% { opacity: 0; transform: scale(1.7) rotate(0deg); }
	}

	.box-prize {
		font-size: 120rpx;
		line-height: 1.2;
		margin-top: -10rpx;
	}

	.box-title {
		margin-top: 16rpx;
		font-size: 36rpx;
		font-weight: 700;
		color: #ffd04d;
		text-shadow: 0 0 12rpx rgba(255, 208, 77, 0.6);
	}

	.box-name {
		margin-top: 10rpx;
		font-size: 30rpx;
		font-weight: 600;
		color: #fff;
	}

	.box-sub {
		margin-top: 10rpx;
		font-size: 22rpx;
		color: rgba(255, 255, 255, 0.85);
		text-align: center;
	}

	.pop {
		opacity: 0;
		animation: box-pop 0.55s cubic-bezier(0.18, 0.89, 0.32, 1.28) 0.45s both;
	}

	.pop-2 {
		animation-delay: 0.6s;
	}

	.pop-3 {
		animation-delay: 0.72s;
	}

	@keyframes box-pop {
		0% { opacity: 0; transform: scale(0.2); }
		100% { opacity: 1; transform: scale(1); }
	}

	.box-actions {
		width: 100%;
		margin-top: 48rpx;
	}

	.box-actions tm-button {
		margin-bottom: 20rpx;
	}

	/* ===== 最近消耗 ===== */
	.history-card {
		margin-top: 32rpx;
		padding: 28rpx;
		border-radius: 16rpx;
		background: #fff;
	}

	.history-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-bottom: 16rpx;
	}

	.history-title {
		font-size: 30rpx;
		font-weight: 700;
		color: #303133;
	}

	.history-more {
		font-size: 22rpx;
		color: #909399;
	}

	.history-empty {
		font-size: 22rpx;
		color: #909399;
		padding: 16rpx 0;
	}

	.history-item {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 14rpx 0;
		border-bottom: 2rpx solid #f5f6fa;

		&:last-child {
			border-bottom: none;
		}
	}

	.history-main {
		flex: 1;
		min-width: 0;
		padding-right: 16rpx;
	}

	.history-desc {
		display: block;
		font-size: 24rpx;
		color: #303133;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.history-time {
		display: block;
		margin-top: 4rpx;
		font-size: 20rpx;
		color: #b0b5c0;
	}

	.history-points {
		flex-shrink: 0;
		font-size: 26rpx;
		font-weight: 700;
		color: #f56c6c;
	}

	.balance-label {
		display: block;
		font-size: 26rpx;
		opacity: 0.9;
	}

	.balance-value {
		display: block;
		margin-top: 8rpx;
		font-size: 56rpx;
		font-weight: 700;
	}

	.balance-sub {
		display: block;
		margin-top: 8rpx;
		font-size: 22rpx;
		opacity: 0.85;
	}

	.tool-card {
		background: #fff;
		border-radius: 16rpx;
		padding: 28rpx;
		margin-bottom: 24rpx;
	}

	.tool-card-head {
		margin-bottom: 20rpx;
	}

	.tool-card-title-row {
		display: flex;
		align-items: center;
		gap: 12rpx;
	}

	.tool-card-title {
		font-size: 30rpx;
		font-weight: 700;
		color: #303133;
	}

	.tool-card-tip {
		display: block;
		margin-top: 8rpx;
		font-size: 22rpx;
		line-height: 1.6;
		color: #909399;
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

		& + text {
			margin-top: 8rpx;
		}
	}
</style>
