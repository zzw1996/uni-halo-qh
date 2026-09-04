<template>
	<view v-if="visible" class="points-toast-mask" @touchmove.stop.prevent>
		<view class="points-toast-box" :class="{ 'is-show': animating }">
			<view class="points-toast-icon">
				<tm-icons name="halocoloricon-attent" :size="56" color="#ffb300"></tm-icons>
			</view>
			<text class="points-toast-amount">{{ displayText }}</text>
			<text v-if="subText" class="points-toast-sub">{{ subText }}</text>
		</view>
	</view>
</template>

<script>
	import tmIcons from '@/tm-vuetify/components/tm-icons/tm-icons.vue';

	export default {
		name: 'PointsToast',
		components: { tmIcons },
		data() {
			return {
				visible: false,
				animating: false,
				displayText: '',
				subText: '',
				_timer: null
			};
		},
		created() {
			uni.$on('points_toast_show', this.fnShow);
		},
		beforeDestroy() {
			uni.$off('points_toast_show', this.fnShow);
			this.fnClearTimer();
		},
		methods: {
			fnClearTimer() {
				if (this._timer) {
					clearTimeout(this._timer);
					this._timer = null;
				}
			},
			fnShow(payload = {}) {
				this.fnClearTimer();
				const amount = Number(payload.amount) || 0;
				const message = (payload.message || '').trim();
				const duration = Math.max(Number(payload.duration) || 2000, 800);
				this.displayText = message || (amount > 0 ? `+${amount} 积分` : `${amount} 积分`);
				this.subText = amount > 0 && message && !message.includes('积分') ? '' : '';
				this.visible = true;
				this.animating = false;
				this.$nextTick(() => {
					this.animating = true;
				});
				this._timer = setTimeout(() => {
					this.animating = false;
					setTimeout(() => {
						this.visible = false;
					}, 220);
				}, duration);
			}
		}
	};
</script>

<style scoped lang="scss">
	.points-toast-mask {
		position: fixed;
		top: 0;
		left: 0;
		right: 0;
		bottom: 0;
		z-index: 10000;
		display: flex;
		align-items: center;
		justify-content: center;
		pointer-events: none;
	}

	.points-toast-box {
		min-width: 280rpx;
		max-width: 520rpx;
		padding: 36rpx 48rpx;
		border-radius: 24rpx;
		background: rgba(26, 26, 46, 0.88);
		box-shadow: 0 12rpx 40rpx rgba(0, 0, 0, 0.18);
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		opacity: 0;
		transform: scale(0.88) translateY(20rpx);
		transition: all 0.24s ease;

		&.is-show {
			opacity: 1;
			transform: scale(1) translateY(0);
		}
	}

	.points-toast-icon {
		margin-bottom: 12rpx;
	}

	.points-toast-amount {
		font-size: 34rpx;
		font-weight: 700;
		color: #ffd54f;
		text-align: center;
		line-height: 1.4;
	}

	.points-toast-sub {
		margin-top: 8rpx;
		font-size: 24rpx;
		color: rgba(255, 255, 255, 0.72);
		text-align: center;
	}
</style>
