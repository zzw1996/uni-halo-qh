<template>
	<view v-if="show" class="pd-mask" @click="fnClose">
		<view class="pd-panel" @click.stop>
			<view v-if="winner" class="pd-confetti">
				<text v-for="(emoji, index) in confettiList" :key="index" class="pd-confetti-item"
					:style="{ left: emoji.left, animationDelay: emoji.delay, animationDuration: emoji.duration }">
					{{ emoji.emoji }}
				</text>
			</view>

			<view class="pd-head">
				<text class="pd-title">每日翻牌</text>
				<text class="pd-tip">{{ costText }} 积分翻一次，最高 {{ maxPrizeText }} 积分</text>
			</view>

			<view v-if="alreadyDrawn" class="pd-already">
				<text class="pd-already-icon">🎴</text>
				<text class="pd-already-title">今日已翻牌</text>
				<text class="pd-already-desc">明天再来试试手气吧～</text>
			</view>
			<view v-else class="pd-grid">
				<view v-for="(card, index) in cards" :key="index" class="pd-card" @click="fnPick(index)">
					<view class="pd-card-inner" :class="{ 'is-flipped': card.revealed, 'is-winner': card.winner }">
						<view class="pd-face pd-back">
							<text class="pd-back-mark">?</text>
						</view>
						<view class="pd-face pd-front" :class="{ 'is-winner-front': card.winner }">
							<text v-if="card.winner" class="pd-front-points">{{ card.points }}</text>
							<text v-else-if="card.revealed" class="pd-front-miss">未中</text>
							<text v-else class="pd-front-blank"></text>
						</view>
					</view>
				</view>
			</view>

			<view class="pd-status-row">
				<text v-if="statusText" class="pd-status">{{ statusText }}</text>
				<tm-button
					v-if="settled"
					size="m"
					theme="primary"
					@click="fnClose"
				>{{ winner ? '收下奖励' : '明天再来' }}</tm-button>
			</view>
		</view>
	</view>
</template>

<script>
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
	import { performPointsDraw, fetchPointsDrawStatus, isDrawDoneToday } from '@/utils/points.js';

	const CARD_COUNT = 9;
	const CONFETTI_EMOJIS = ['🎉', '✨', '🎊', '⭐', '💥', '🎆', '🌟'];

	export default {
		name: 'points-draw',
		components: {
			tmButton
		},
		props: {
			show: {
				type: Boolean,
				default: false
			},
			cost: {
				type: Number,
				default: 10
			},
			maxPrize: {
				type: Number,
				default: 100
			}
		},
		data() {
			return {
				cards: [],
				picking: false,
				settled: false,
				winner: false,
				alreadyDrawn: false,
				statusText: '',
				confettiList: []
			};
		},
		computed: {
			costText() {
				return String(this.cost || 10);
			},
			maxPrizeText() {
				return String(this.maxPrize || 100);
			}
		},
		watch: {
			show(val) {
				if (val) {
					this.fnReset();
					this.fnCheckStatus();
				}
			}
		},
		methods: {
			fnReset() {
				this.cards = Array.from({ length: CARD_COUNT }, () => ({
					revealed: false,
					winner: false,
					points: 0
				}));
				this.picking = false;
				this.settled = false;
				this.winner = false;
				this.alreadyDrawn = false;
				this.statusText = '';
				this.confettiList = [];
			},
			// 打开弹窗先判断今日状态：本地标记优先（即时提示），再以服务端状态为准
			async fnCheckStatus() {
				if (isDrawDoneToday()) {
					this.alreadyDrawn = true;
					this.settled = true;
					this.statusText = '今日已翻牌，明天再来吧';
					return;
				}
				const status = await fetchPointsDrawStatus(this.$httpApi.v2);
				if (!status.ok) return;
				if (status.alreadyDrawn) {
					this.alreadyDrawn = true;
					this.settled = true;
					this.statusText = status.message || '今日已翻牌，明天再来吧';
				}
			},
			async fnPick(index) {
				if (!this.show || this.picking || this.settled) return;
				this.picking = true;
				this.statusText = '翻牌中...';
				const result = await performPointsDraw(this.$httpApi.v2);
				this.picking = false;
				if (!result.ok) {
					this.statusText = result.reason || '翻牌失败，请稍后重试';
					return;
				}
				if (result.alreadyDrawn) {
					this.cards[index] = { revealed: true, winner: false, points: 0 };
					this.settled = true;
					this.statusText = result.reason || '今日已翻牌，明天再来吧';
					return;
				}
				// 中奖卡率先翻面
				this.cards[index] = { revealed: true, winner: true, points: result.earned };
				this.winner = true;
				if (result.balance != null) {
					this.$emit('drawn', { balance: result.balance, earned: result.earned });
				}
				// 其余卡再逐一揭示（未中）
				const others = this.cards
					.map((card, idx) => ({ card, idx }))
					.filter((item) => item.idx !== index);
				others.forEach((item, order) => {
					setTimeout(() => {
						this.$set(item.card, 'revealed', true);
					}, 350 + order * 90);
				});
				this.fnBuildConfetti();
				setTimeout(() => {
					this.settled = true;
					this.statusText = `翻牌成功 +${result.earned} 积分！`;
				}, 350 + others.length * 90);
			},
			fnBuildConfetti() {
				const items = CONFETTI_EMOJIS
					.concat(CONFETTI_EMOJIS)
					.concat(CONFETTI_EMOJIS)
					.map((emoji, index) => ({
						emoji,
						left: `${4 + Math.random() * 88}%`,
						delay: `${(index % 7) * 0.18}s`,
						duration: `${1.2 + Math.random() * 0.8}s`
					}));
				this.confettiList = items.slice(0, 18);
			},
			fnClose() {
				this.$emit('update:show', false);
				this.$emit('close');
			}
		}
	};
</script>

<style scoped lang="scss">
	.pd-mask {
		position: fixed;
		left: 0;
		top: 0;
		right: 0;
		bottom: 0;
		z-index: 9999;
		background: rgba(10, 8, 24, 0.78);
		display: flex;
		align-items: center;
		justify-content: center;
	}

	.pd-panel {
		position: relative;
		width: 88%;
		max-width: 640rpx;
		padding: 44rpx 28rpx 32rpx;
		border-radius: 32rpx;
		background: linear-gradient(168deg, #2c2455 0%, #1b1536 52%, #120d26 100%);
		border: 1rpx solid rgba(255, 213, 79, 0.22);
		box-shadow: 0 24rpx 64rpx rgba(0, 0, 0, 0.5), 0 0 48rpx rgba(255, 213, 79, 0.12) inset;
		overflow: hidden;
		animation: pd-pop 0.35s cubic-bezier(0.18, 0.89, 0.32, 1.28);
	}

	@keyframes pd-pop {
		from {
			transform: scale(0.72);
			opacity: 0;
		}
		to {
			transform: scale(1);
			opacity: 1;
		}
	}

	.pd-head {
		margin-bottom: 30rpx;
		text-align: center;
	}

	.pd-title {
		display: block;
		font-size: 42rpx;
		font-weight: 800;
		color: #ffd54f;
		letter-spacing: 6rpx;
		text-shadow: 0 0 24rpx rgba(255, 213, 79, 0.45);
	}

	.pd-tip {
		display: block;
		margin-top: 12rpx;
		font-size: 22rpx;
		color: rgba(255, 255, 255, 0.65);
		letter-spacing: 1rpx;
	}

	.pd-grid {
		display: flex;
		flex-wrap: wrap;
		justify-content: space-between;
	}

	.pd-already {
		display: flex;
		flex-direction: column;
		align-items: center;
		padding: 60rpx 0 30rpx;
	}

	.pd-already-icon {
		font-size: 120rpx;
		animation: pd-float 2.2s ease-in-out infinite;
	}

	@keyframes pd-float {
		0%, 100% {
			transform: translateY(0);
		}
		50% {
			transform: translateY(-16rpx);
		}
	}

	.pd-already-title {
		margin-top: 20rpx;
		font-size: 34rpx;
		font-weight: 800;
		color: #ffd54f;
		letter-spacing: 4rpx;
	}

	.pd-already-desc {
		margin-top: 10rpx;
		font-size: 24rpx;
		color: rgba(255, 255, 255, 0.6);
	}

	.pd-card {
		width: 30%;
		aspect-ratio: 1;
		margin-bottom: 20rpx;
		perspective: 800rpx;
	}

	.pd-card-inner {
		position: relative;
		width: 100%;
		height: 100%;
		transform-style: preserve-3d;
		transition: transform 0.55s cubic-bezier(0.4, 0.1, 0.2, 1);

		&.is-flipped {
			transform: rotateY(180deg);
		}

		&.is-winner {
			animation: pd-win 0.7s ease 0.45s both;
		}
	}

	@keyframes pd-win {
		0% {
			transform: rotateY(180deg) scale(1);
		}
		50% {
			transform: rotateY(180deg) scale(1.16);
		}
		100% {
			transform: rotateY(180deg) scale(1);
		}
	}

	.pd-face {
		position: absolute;
		top: 0;
		left: 0;
		width: 100%;
		height: 100%;
		border-radius: 16rpx;
		display: flex;
		align-items: center;
		justify-content: center;
		backface-visibility: hidden;
		overflow: hidden;
	}

	.pd-back {
		background: radial-gradient(circle at 30% 22%, #ffe082 0%, #ffca28 46%, #fb8c00 100%);
		box-shadow: inset 0 2rpx 0 rgba(255, 255, 255, 0.5),
			inset 0 -8rpx 14rpx rgba(133, 61, 3, 0.25),
			0 6rpx 14rpx rgba(0, 0, 0, 0.35);
		border: 1rpx solid rgba(255, 255, 255, 0.28);
	}

	/* 卡片玻璃高光 */
	.pd-back::after {
		content: '';
		position: absolute;
		left: 0;
		top: 0;
		right: 0;
		bottom: 0;
		border-radius: 16rpx;
		background: linear-gradient(125deg, rgba(255, 255, 255, 0.38) 0%, rgba(255, 255, 255, 0) 38%);
		pointer-events: none;
	}

	.pd-back-mark {
		font-size: 58rpx;
		font-weight: 900;
		color: #5b3fa8;
		text-shadow: 0 2rpx 0 rgba(255, 255, 255, 0.55), 0 4rpx 10rpx rgba(0, 0, 0, 0.18);
	}

	.pd-front {
		transform: rotateY(180deg);
		background: linear-gradient(160deg, #ffffff 0%, #f2f3f7 100%);
		box-shadow: 0 6rpx 14rpx rgba(0, 0, 0, 0.35);
		border: 1rpx solid rgba(0, 0, 0, 0.06);
	}

	.pd-front.is-winner-front {
		background: radial-gradient(circle at 30% 22%, #fff8e1 0%, #ffecb3 55%, #ffd54f 100%);
		border: 1rpx solid #ffc107;
	}

	.pd-front-points {
		font-size: 54rpx;
		font-weight: 900;
		color: #e65100;
		text-shadow: 0 1rpx 0 rgba(255, 255, 255, 0.6);
	}

	.pd-front-miss {
		font-size: 28rpx;
		font-weight: 700;
		color: #b0b4c0;
	}

	.pd-front-blank {
		width: 100%;
		height: 100%;
	}

	.pd-status-row {
		min-height: 78rpx;
		margin-top: 10rpx;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 16rpx;
	}

	.pd-status {
		font-size: 26rpx;
		font-weight: 600;
		color: rgba(255, 255, 255, 0.85);
	}

	.pd-confetti {
		position: absolute;
		top: 0;
		left: 0;
		right: 0;
		bottom: 0;
		pointer-events: none;
	}

	.pd-confetti-item {
		position: absolute;
		top: -60rpx;
		font-size: 40rpx;
		animation: pd-fall linear infinite;
	}

	@keyframes pd-fall {
		0% {
			transform: translateY(-40rpx) rotate(0deg);
			opacity: 1;
		}
		100% {
			transform: translateY(780rpx) rotate(360deg);
			opacity: 0.35;
		}
	}
</style>
