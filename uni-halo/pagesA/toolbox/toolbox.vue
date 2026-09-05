<template>
	<view class="app-page">
		<view v-if="loading !== 'success'" class="loading-wrap">
			<tm-skeleton model="listAvatr"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
		</view>
		<view v-else class="tool-card">
			<view class="tool-card-head">
				<text class="tool-card-title">随机决定</text>
				<text class="tool-card-tip">纠结时交给命运：选个场景一键生成，也可以自定义选项（换行/顿号分隔）</text>
			</view>
			<view class="tool-chip-list">
				<text
					v-for="(preset, index) in pickerPresets"
					:key="index"
					class="tool-chip"
					@click="fnUsePreset(preset)"
				>{{ preset.label }}</text>
			</view>
			<textarea class="tool-textarea" v-model="pickerInput"
				placeholder="例如：吃火锅\n吃烤肉\n吃面条" maxlength="500" />
			<view v-if="pickerOptions.length" class="tool-chip-list">
				<text v-for="(opt, index) in pickerOptions" :key="index" class="tool-chip tool-chip-plain">{{ opt }}</text>
			</view>
			<view class="tool-picker-result" :class="{ 'is-rolling': pickerRolling }">{{ pickerResult || ' ' }}</view>
			<tm-button size="m" theme="primary" :loading="pickerRolling" @click="fnPickRandom">开始决定</tm-button>
		</view>
	</view>
</template>

<script>
	import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';

	const PICKER_PRESETS = [
		{ label: '今天吃什么', options: ['火锅', '烤肉', '面条', '烧烤', '外卖'] },
		{ label: '出门去哪里', options: ['公园', '商场', '咖啡店', '电影院', '宅家'] },
		{ label: '要不要买', options: ['买', '不买', '再想想'] },
		{ label: '做不做这件事', options: ['做', '不做', '明天再说'] }
	];

	export default {
		components: {
			tmSkeleton,
			tmButton
		},
		data() {
			return {
				loading: 'loading',
				pickerPresets: PICKER_PRESETS,
				pickerInput: '',
				pickerResult: '',
				pickerRolling: false
			};
		},
		onLoad() {
			this.loading = 'success';
		},
		methods: {
			fnUsePreset(preset) {
				if (!preset || !Array.isArray(preset.options)) return;
				this.pickerInput = preset.options.join('\n');
				this.pickerResult = '';
			},
			fnParsePickerOptions() {
				return this.pickerInput
					.split(/[\n,，、;；]/)
					.map((item) => item.trim())
					.filter(Boolean);
			},
			fnPickRandom() {
				const options = this.fnParsePickerOptions();
				if (options.length < 2) {
					uni.showToast({ title: '至少需要 2 个选项', icon: 'none' });
					return;
				}
				if (this.pickerRolling) return;
				this.pickerRolling = true;
				let count = 0;
				const interval = setInterval(() => {
					count += 1;
					this.pickerResult = options[Math.floor(Math.random() * options.length)];
					if (count >= 12) {
						clearInterval(interval);
						this.pickerRolling = false;
					}
				}, 90);
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

	.tool-card {
		background: #fff;
		border-radius: 16rpx;
		padding: 28rpx;
		margin-bottom: 24rpx;
	}

	.tool-card-head {
		margin-bottom: 20rpx;
	}

	.tool-card-title {
		display: block;
		font-size: 30rpx;
		font-weight: 700;
		color: #303133;
	}

	.tool-card-tip {
		display: block;
		margin-top: 6rpx;
		font-size: 22rpx;
		color: #909399;
	}

	.tool-textarea {
		width: 100%;
		height: 160rpx;
		box-sizing: border-box;
		padding: 20rpx;
		background: #f5f6fa;
		border-radius: 12rpx;
		font-size: 26rpx;
	}

	.tool-chip-list {
		display: flex;
		flex-wrap: wrap;
		gap: 16rpx;
		margin: 16rpx 0;
	}

	.tool-chip {
		padding: 10rpx 20rpx;
		background: #f0f8ff;
		border-radius: 12rpx;
		font-size: 24rpx;
		color: #03a9f4;
	}

	.tool-chip-plain {
		background: #f5f6fa;
		color: #606266;
	}

	.tool-picker-result {
		margin: 20rpx 0;
		text-align: center;
		font-size: 34rpx;
		font-weight: 700;
		color: #303133;
		min-height: 44rpx;

		&.is-rolling {
			color: #909399;
		}
	}
</style>
