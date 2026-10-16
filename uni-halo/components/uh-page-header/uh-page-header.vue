<template>
    <!-- 只保留系统状态栏、微信胶囊的安全空间，不展示页面名称或说明。 -->
    <view class="uh-page-header" :style="'height:' + (statusHeight + rowHeight) + 'px'" aria-hidden="true"></view>
</template>

<script>
export default {
	name: 'uh-page-header',
	props: {
		title: { type: String, default: 'Halo' },
		subtitle: { type: String, default: '' },
		brand: { type: Boolean, default: false }
	},
	data() {
		const info = uni.getSystemInfoSync();
		const statusHeight = info.statusBarHeight || 20;
		let rowHeight = 48;
		let rightInset = 20;
		// 胶囊由微信绘制；只测量并留出空间，不模拟状态栏或胶囊。
		// #ifdef MP-WEIXIN
		rightInset = 112;
		try {
			const capsule = uni.getMenuButtonBoundingClientRect();
			if (capsule && capsule.width > 0) {
				rowHeight = Math.max(44, (capsule.top - statusHeight) * 2 + capsule.height);
				rightInset = info.windowWidth - capsule.left + 12;
			}
		} catch (err) { rightInset = 112; }
		// #endif
		return { statusHeight, rowHeight, rightInset };
	},
	computed: {
		rowStyle() {
			return { minHeight: this.rowHeight + 'px', paddingRight: this.rightInset + 'px' };
		}
	}
};
</script>

<style lang="scss" scoped>
.uh-page-header { padding-bottom: 10rpx; pointer-events:none; }
</style>
