<template>
	<!--
		首页轮播图。
		只保留「封面 + 底部渐变蒙层（标题/时间 + 圆点）」两层：
		旧版还在左上角叠了「今日首推」日期盒、右侧叠了一列缩略图条、底部再叠一条 8.5% 黑底标题条，
		加起来的装饰盖掉了封面近三成面积；而且日期盒里的标题位父页面从来没传过值，那块一直是空的。

		跳转由父页面处理（@on-click），本组件不碰业务。
	-->
	<view class="uh-banner">
		<swiper class="uh-banner_swiper" :style="{ height: boxHeight }" :circular="hasMultiple"
			:autoplay="calcAutoplay" :interval="interval" :duration="duration" :current="currentIndex"
			@change="fnOnChange">
			<swiper-item v-for="(item, index) in slides" :key="item.id || index" class="uh-banner_item"
				@click="fnOnItemTap(item)">
				<!--
					视频封面：只在当前项创建 video（切走即销毁），否则多个视频同时解码会卡首页。
				-->
				<video v-if="item.mp4 && currentIndex === index" class="uh-banner_media" :src="item.mp4" :loop="true"
					:autoplay="true" :controls="false" :show-fullscreen-btn="false" :show-play-btn="false"
					:enable-progress-gesture="false" :play-strategy="0" :poster="item.image || item.src"></video>
				<image v-else class="uh-banner_media" :src="item.image || item.src" mode="aspectFill"
					@load="fnOnMediaLoad($event, index)"></image>
			</swiper-item>
		</swiper>
		<!--
			底部渐变蒙层：只为标题白字铺垫可读性，本身不接手势（WebView 渲染器下 pointer-events 生效，
			滑动可以穿透到 swiper）。万一某个环境不支持，它同时挂了点击，至少不会变成死区。
		-->
		<view v-if="showOverlay" class="uh-banner_scrim" @click="fnOnItemTap(calcCurrentItem)"></view>
		<!--
			文字条：只有这一条会挡住滑动（约 70rpx），点标题同样能进文章。
		-->
		<view v-if="showOverlay" class="uh-banner_bar" :class="calcBarClass" @click="fnOnItemTap(calcCurrentItem)">
			<view v-if="showTitle && calcCurrentTitle" class="uh-banner_caption">
				<text class="uh-banner_title">{{ calcCurrentTitle }}</text>
				<text v-if="calcCurrentMeta" class="uh-banner_meta">{{ calcCurrentMeta }}</text>
			</view>
			<view v-if="showDots && hasMultiple" class="uh-banner_dots">
				<!-- 外面这层只负责把可点区域撑大：圆点本身只有 10rpx，直接点很难点中 -->
				<view v-for="(item, index) in slides" :key="index" class="uh-banner_dot-hit"
					@click.stop="fnToIndex(index)">
					<view class="uh-banner_dot" :class="{ 'is-active': currentIndex === index }"></view>
				</view>
			</view>
		</view>
	</view>
</template>

<script>
	export default {
		name: 'e-swiper',
		props: {
			/** 轮播数据：[{ id, title, image/src, mp4, nickname, createTime }] */
			list: {
				type: Array,
				default: () => []
			},
			/** 是否自动轮播（只有一张时不轮播：没有可切的目标） */
			autoplay: {
				type: Boolean,
				default: true
			},
			/** 自动切换间隔 / 切换动画时长（毫秒） */
			interval: {
				type: Number,
				default: 4000
			},
			duration: {
				type: Number,
				default: 400
			},
			/** 当前项（受控） */
			current: {
				type: Number,
				default: 0
			},
			/**
			 * 按封面真实宽高自适应高度。
			 * 打开后 4:3 封面不会再被 16:9 的固定高度切掉上下两头。
			 */
			fit: {
				type: Boolean,
				default: true
			},
			/** fit 生效前（或关闭 fit 时）的高度 */
			fallbackHeight: {
				type: String,
				default: '400rpx'
			},
			/** 自适应高度的上下限（rpx）：太矮压不住字，太高把首屏顶下去 */
			minHeight: {
				type: Number,
				default: 340
			},
			maxHeight: {
				type: Number,
				default: 520
			},
			showTitle: {
				type: Boolean,
				default: true
			},
			showDots: {
				type: Boolean,
				default: true
			},
			/** 指示器位置：right=压在文字条右侧（一行）；bottom=文字条下方居中（两行） */
			dotPosition: {
				type: String,
				default: 'right'
			}
		},
		data() {
			return {
				currentIndex: 0,
				boxHeight: this.fallbackHeight,
				/** 一批数据只按第一张封面算一次高度，避免每张图加载都重排 */
				fitted: false
			};
		},
		computed: {
			/** 最多 5 张：轮播是推荐位，再多用户也翻不完，白拉长首屏 */
			slides() {
				return this.list.slice(0, 5);
			},
			hasMultiple() {
				return this.slides.length > 1;
			},
			calcAutoplay() {
				return this.autoplay && this.hasMultiple;
			},
			calcCurrentItem() {
				return this.slides[this.currentIndex] || {};
			},
			calcCurrentTitle() {
				return this.calcCurrentItem.title || '';
			},
			calcCurrentMeta() {
				// 自定义轮播没有作者/时间，这里会得到空串，模板据此隐藏整行
				return [this.calcCurrentItem.nickname, this.calcCurrentItem.createTime]
					.filter(Boolean)
					.join(' · ');
			},
			showOverlay() {
				const hasTitle = this.showTitle && this.slides.some((item) => item.title);
				return hasTitle || (this.showDots && this.hasMultiple);
			},
			calcBarClass() {
				return this.dotPosition === 'bottom' ? 'is-dots-bottom' : 'is-dots-right';
			}
		},
		watch: {
			list() {
				// 换数据要按新封面重新量高度，否则会沿用上一批的比例
				this.fitted = false;
				this.boxHeight = this.fallbackHeight;
				this.currentIndex = 0;
			},
			current(val) {
				this.currentIndex = val;
			}
		},
		created() {
			this.currentIndex = this.current;
		},
		methods: {
			fnOnChange(e) {
				const current = e?.detail?.current;
				// 不按 source 过滤：H5 端 change 的 source 可能为空，
				// 旧实现只认 autoplay/touch，结果 H5 上标题与指示器永远停在第一张
				if (typeof current !== 'number' || current === this.currentIndex) return;
				this.currentIndex = current;
				this.$emit('change', { current });
			},
			fnToIndex(index) {
				if (index === this.currentIndex) return;
				this.currentIndex = index;
				this.$emit('change', { current: index });
			},
			fnOnItemTap(item) {
				if (!item) return;
				this.$emit('on-click', item);
			},
			fnOnMediaLoad(e, index) {
				if (!this.fit || this.fitted || index !== 0) return;
				const width = e?.detail?.width;
				const height = e?.detail?.height;
				if (!width || !height) return;
				this.fitted = true;
				this.fnFitHeight(height / width);
			},
			/** 高度 = 卡宽 × 封面高宽比，再卡在上下限之间 */
			fnFitHeight(ratio) {
				const info = uni.getSystemInfoSync();
				const windowWidth = info?.windowWidth || 375;
				try {
					uni.createSelectorQuery()
						.in(this)
						.select('.uh-banner_swiper')
						.boundingClientRect((rect) => {
							// 量不到就按首页两侧各 24rpx 边距估算
							const boxWidth = rect?.width || windowWidth * 702 / 750;
							this.boxHeight = this.fnToRpx(boxWidth * ratio, windowWidth);
						})
						.exec();
				} catch (err) {
					// 低版本环境不支持选择器查询时退回估算，宁可高度差几像素也不能白屏
					this.boxHeight = this.fnToRpx(windowWidth * 702 / 750 * ratio, windowWidth);
				}
			},
			fnToRpx(px, windowWidth) {
				const rpx = px * 750 / windowWidth;
				return Math.round(Math.min(Math.max(rpx, this.minHeight), this.maxHeight)) + 'rpx';
			}
		}
	};
</script>

<style lang="scss" scoped>
	@import './e-swiper.scss';
</style>
