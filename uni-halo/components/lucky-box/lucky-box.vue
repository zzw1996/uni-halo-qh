<template>
	<!-- 开盒动画：摇盒 → 爆开揭晓。组件只负责「开盒」这一件事，入口卡片由各页面自己排版 -->
	<view v-if="show" class="box-mask" catchtouchmove>
		<view v-if="phase === 'shaking'" class="box-stage">
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
			<text class="box-prize pop">{{ result ? (result.emoji || '🎁') : '🎁' }}</text>
			<text class="box-title pop pop-2">{{ fnBoxTitle(result) }}</text>
			<text v-if="result && (result.won || isAgain(result))" class="box-name pop pop-3">{{ result.name }}</text>
			<text class="box-sub pop pop-3">{{ fnBoxSub(result) }}</text>
			<view class="box-actions">
				<tm-button v-if="result && result.won && result.needsAddress" theme="orange" :block="true" @click="fnGoClaimAfterDraw">
					填写收货信息
				</tm-button>
				<tm-button v-if="result && isAgain(result)" theme="orange" :block="true" @click="fnAgainDraw">
					再抽一次
				</tm-button>
				<tm-button theme="orange" :block="true" @click="fnCloseBox">{{ fnBoxCloseText(result) }}</tm-button>
			</view>
		</view>
	</view>
</template>

<script>
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';

	/**
	 * 幸运礼盒（盲盒）开盒弹窗。
	 *
	 * 从「积分兑换」页原内联实现抽出，供兑换页与「我的」页共用：两处入口不同，
	 * 但开盒过程、结果文案、中奖后引导填收货信息必须完全一致，否则会出现
	 * 「同一个礼盒在两个页面表现不一样」的问题。
	 *
	 * 用法：
	 *   <lucky-box :show.sync="luckyBoxShow" @drawn="fnOnLuckyDrawn" />
	 * 父页只需把 show 置 true 即开始开盒；抽奖成功后由 `drawn` 事件回传结果，
	 * 父页据此刷新积分余额与中奖列表（组件自身不碰全局状态）。
	 */
	export default {
		name: 'LuckyBox',
		components: {
			tmButton
		},
		props: {
			show: {
				type: Boolean,
				default: false
			}
		},
		data() {
			return {
				phase: 'shaking',
				result: null,
				confettiList: [],
				drawing: false
			};
		},
		watch: {
			show: {
				immediate: true,
				handler(val) {
					if (val) {
						this.fnReset();
						this.fnStart();
					}
				}
			}
		},
		methods: {
			fnReset() {
				this.phase = 'shaking';
				this.result = null;
				this.confettiList = [];
			},
			/** 摇盒 → 调接口 → 揭晓。摇盒至少 900ms，避免结果一闪而过。 */
			async fnStart() {
				if (this.drawing) return;
				this.drawing = true;
				try {
					const res = await this.$httpApi.v2.drawLuckyGift();
					const data = res?.data || res || {};
					if (data?.error) {
						this.fnCloseBox();
						uni.showToast({ title: data.error, icon: 'none' });
						return;
					}
					await new Promise((resolve) => setTimeout(resolve, 900));
					this.result = data;
					this.phase = 'reveal';
					if (data.won || this.isAgain(data)) {
						this.fnBuildConfetti();
					}
					this.$emit('drawn', data);
				} catch (err) {
					this.fnCloseBox();
					uni.showToast({ title: err?.error || '抽奖失败，请稍后重试', icon: 'none' });
				} finally {
					this.drawing = false;
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
				this.fnReset();
				this.$emit('update:show', false);
			},
			/** 是否抽中「再来一次」（与「感谢参与」区分） */
			isAgain(result) {
				return !!result && !result.won && result.name === '再来一次';
			},
			fnBoxTitle(result) {
				if (!result) return '';
				if (result.won) return '恭喜中奖！';
				if (this.isAgain(result)) return '再来一次！';
				return '谢谢参与';
			},
			fnBoxSub(result) {
				if (!result) return '';
				if (result.won) return result.message;
				if (this.isAgain(result)) return '运气不错，抽中再来一次，要不要再开一盒？';
				return '再接再厉，下次好运！';
			},
			fnBoxCloseText(result) {
				if (result && result.won) return '收下礼品';
				return '知道了';
			},
			fnAgainDraw() {
				this.fnReset();
				this.fnStart();
			},
			fnGoClaimAfterDraw() {
				if (!this.result || !this.result.winningId) return;
				const result = this.result;
				this.fnCloseBox();
				uni.navigateTo({
					url: `/pagesA/lucky-claim/lucky-claim?winningId=${encodeURIComponent(result.winningId)}&name=${encodeURIComponent(result.name || '')}&emoji=${encodeURIComponent(result.emoji || '🎁')}`
				});
			}
		}
	};
</script>

<style scoped lang="scss">
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
</style>
