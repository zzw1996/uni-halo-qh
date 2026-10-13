<template>
	<!--
		应用设置里的一行：左边标题 + 说明，右边控件。
		抽出来是因为设置页有十来行同样的结构，手写时每行的间距、分割线、字号都会飘，
		而这个页面的「好看」几乎全在「每行都长一样」上。
	-->
	<view class="uh-setting-row">
		<view class="row-text">
			<text class="row-title">{{ title }}</text>
			<text v-if="desc" class="row-desc">{{ desc }}</text>
		</view>
		<view class="row-control">
			<slot></slot>
		</view>
	</view>
</template>

<script>
	export default {
		name: 'uh-setting-row',
		props: {
			title: {
				type: String,
				default: ''
			},
			/** 一句话说明这一项是干什么的：设置页最缺的就是这个 */
			desc: {
				type: String,
				default: ''
			}
		}
	};
</script>

<style scoped lang="scss">
	.uh-setting-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 26rpx 28rpx;
	}

	/* 行与行之间用发丝线分隔（第一条由调用方决定要不要显示） */
	.uh-setting-row + .uh-setting-row {
		border-top: 1rpx solid $uh-line;
	}

	.row-text {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		padding-right: 20rpx;
	}

	.row-title {
		font-size: 28rpx;
		color: $uh-ink-1;
	}

	.row-desc {
		margin-top: 6rpx;
		font-size: 21rpx;
		line-height: 1.5;
		color: $uh-ink-3;
	}

	.row-control {
		flex-shrink: 0;
		display: flex;
		align-items: center;
		justify-content: flex-end;
	}
</style>
