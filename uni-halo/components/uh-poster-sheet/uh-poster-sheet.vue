<template>
	<!--
		文章分享海报弹层（枫光改版 2026-10）：三套模板左右滑动切换，按需生成并缓存。
		绘制见 utils/posterRenderer.js；画布藏在屏幕外，只负责出图。
	-->
	<view v-if="show" class="ps" @touchmove.stop.prevent="fnNoop">
		<canvas class="ps-canvas" canvas-id="uhPosterCanvas" id="uhPosterCanvas"
			:style="{ width: canvasW + 'px', height: canvasH + 'px' }"></canvas>

		<view class="ps-top" :style="topStyle">
			<view class="ps-close" @click="fnClose"><text class="ps-close_text">✕</text></view>
			<text class="ps-title">分享海报</text>
			<view class="ps-close ps-close--ghost"></view>
		</view>

		<swiper class="ps-swiper" :current="current" previous-margin="90rpx" next-margin="90rpx" @change="fnOnSwipe">
			<swiper-item v-for="(t, i) in templates" :key="t.id" class="ps-slide">
				<view class="ps-frame" :class="{ 'is-on': i === current }">
					<image v-if="images[t.id]" class="ps-img" :src="images[t.id]" mode="aspectFit" @click="fnPreview(t.id)"></image>
					<view v-else class="ps-loading">
						<text class="ps-loading_dot"></text>
						<text class="ps-loading_text">{{ errors[t.id] ? '生成失败，点我重试' : '正在生成海报…' }}</text>
						<view v-if="errors[t.id]" class="ps-retry" @click="fnGenerate(t.id, true)"></view>
					</view>
				</view>
			</swiper-item>
		</swiper>

		<view class="ps-tabs">
			<view v-for="(t, i) in templates" :key="t.id" class="ps-tab" :class="{ 'is-on': i === current }" @click="current = i">
				<view class="ps-tab_thumb" :class="'ps-tab_thumb--' + t.id"></view>
				<text class="ps-tab_text">{{ t.name }}</text>
			</view>
		</view>

		<view class="ps-actions">
			<view class="ps-btn" @click="fnShare"><text class="ps-btn_text">发给好友</text></view>
			<view class="ps-btn ps-btn--accent" @click="fnSave"><text class="ps-btn_text">保存到相册</text></view>
		</view>
	</view>
</template>

<script>
	import { POSTER_W, POSTER_H, POSTER_TEMPLATES, normalizePosterData, renderPoster } from '@/utils/posterRenderer.js';

	const TEMPLATE_KEY = 'uhalo_poster_template';

	export default {
		name: 'uh-poster-sheet',
		props: {
			show: { type: Boolean, default: false },
			/** 见 posterRenderer.normalizePosterData 的入参 */
			data: { type: Object, default: null }
		},
		data() {
			return {
				canvasW: POSTER_W,
				canvasH: POSTER_H,
				templates: POSTER_TEMPLATES,
				current: 0,
				images: {},
				errors: {},
				busy: false,
				queue: []
			};
		},
		computed: {
			topStyle() {
				let top = 20;
				try {
					const info = uni.getWindowInfo ? uni.getWindowInfo() : uni.getSystemInfoSync();
					top = (info.statusBarHeight || 20) + 6;
				} catch (e) {}
				return `padding-top:${top}px`;
			},
			currentId() {
				return this.templates[this.current].id;
			},
			normalized() {
				return this.data ? normalizePosterData(this.data) : null;
			}
		},
		watch: {
			show(val) {
				if (val) this.fnOpen();
			},
			data() {
				// 换了文章：作废已生成的图
				this.images = {};
				this.errors = {};
			},
			current() {
				try { uni.setStorageSync(TEMPLATE_KEY, this.currentId); } catch (e) {}
				this.fnGenerate(this.currentId);
			}
		},
		methods: {
			fnNoop() {},
			fnOpen() {
				let saved = '';
				try { saved = uni.getStorageSync(TEMPLATE_KEY); } catch (e) {}
				const idx = this.templates.findIndex(t => t.id === saved);
				if (idx >= 0 && idx !== this.current) {
					this.current = idx; // watcher 会触发生成
				} else {
					this.$nextTick(() => this.fnGenerate(this.currentId));
				}
			},
			fnClose() {
				this.$emit('update:show', false);
				this.$emit('close');
			},
			fnOnSwipe(e) {
				this.current = e.detail.current;
			},
			/** 同一张画布串行出图：排队，避免两次绘制互相覆盖 */
			fnGenerate(id, force = false) {
				if (!this.normalized) return;
				if (!force && (this.images[id] || this.queue.includes(id))) return;
				this.$set(this.errors, id, false);
				this.queue.push(id);
				this.fnRunQueue();
			},
			async fnRunQueue() {
				if (this.busy || !this.queue.length) return;
				this.busy = true;
				const id = this.queue.shift();
				try {
					await this.$nextTick();
					const path = await renderPoster('uhPosterCanvas', this, id, this.normalized);
					this.$set(this.images, id, path);
				} catch (err) {
					console.error('[uh-poster-sheet] 生成失败', id, err);
					this.$set(this.errors, id, true);
				}
				this.busy = false;
				this.fnRunQueue();
			},
			fnPreview(id) {
				const url = this.images[id];
				if (url) uni.previewImage({ current: url, urls: [url] });
			},
			fnCurrentImage() {
				const url = this.images[this.currentId];
				if (!url) uni.showToast({ title: '海报还在生成，请稍等', icon: 'none' });
				return url;
			},
			fnShare() {
				const url = this.fnCurrentImage();
				if (!url) return;
				// #ifdef MP-WEIXIN
				if (typeof wx !== 'undefined' && typeof wx.showShareImageMenu === 'function') {
					wx.showShareImageMenu({ path: url });
					return;
				}
				// #endif
				uni.previewImage({ current: url, urls: [url] });
			},
			fnSave() {
				const url = this.fnCurrentImage();
				if (!url) return;
				uni.saveImageToPhotosAlbum({
					filePath: url,
					success: () => uni.showToast({ title: '已保存到相册', icon: 'none' }),
					fail: (err) => {
						if (/auth|deny/i.test((err && err.errMsg) || '')) {
							uni.showModal({
								title: '需要相册权限',
								content: '允许保存到相册后，才能把海报存下来',
								confirmText: '去设置',
								success: (r) => { if (r.confirm) uni.openSetting({}); }
							});
						} else {
							uni.showToast({ title: '保存失败', icon: 'none' });
						}
					}
				});
			}
		}
	};
</script>

<style lang="scss" scoped>
	.ps { position: fixed; left: 0; top: 0; right: 0; bottom: 0; z-index: 10001; background: #0E0D14; display: flex; flex-direction: column; }
	/* 画布只用来出图：放到屏幕外，不能 display:none（隐藏的 canvas 导不出图） */
	.ps-canvas { position: fixed; left: -2000px; top: 0; }
	.ps-top { display: flex; align-items: center; justify-content: space-between; padding: 0 32rpx 12rpx; }
	.ps-close { width: 72rpx; height: 72rpx; border-radius: 50%; background: rgba(255, 255, 255, .12); display: flex; align-items: center; justify-content: center; }
	.ps-close--ghost { background: transparent; }
	.ps-close_text { color: #ffffff; font-size: 30rpx; }
	.ps-title { color: #ffffff; font-size: 32rpx; font-weight: 700; }
	.ps-swiper { flex: 1; width: 100%; margin-top: 16rpx; }
	.ps-slide { display: flex; align-items: center; justify-content: center; }
	.ps-frame { width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; transform: scale(.9); opacity: .55; transition: transform .25s, opacity .25s; }
	.ps-frame.is-on { transform: scale(1); opacity: 1; }
	.ps-img { width: 100%; height: 100%; border-radius: 24rpx; }
	.ps-loading { position: relative; width: 100%; height: 88%; border-radius: 24rpx; background: rgba(255, 255, 255, .06); display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 20rpx; }
	.ps-loading_dot { width: 24rpx; height: 24rpx; border-radius: 50%; background: $uh-reward; animation: psPulse 1s ease-in-out infinite; }
	.ps-loading_text { color: rgba(255, 255, 255, .7); font-size: 24rpx; }
	.ps-retry { position: absolute; left: 0; top: 0; right: 0; bottom: 0; }
	@keyframes psPulse { 0%, 100% { opacity: .3; transform: scale(.8); } 50% { opacity: 1; transform: scale(1.1); } }

	.ps-tabs { display: flex; justify-content: center; gap: 28rpx; margin-top: 28rpx; }
	.ps-tab { display: flex; flex-direction: column; align-items: center; gap: 10rpx; }
	.ps-tab_thumb { width: 96rpx; height: 132rpx; border-radius: 18rpx; box-sizing: border-box; border: 2rpx solid rgba(255, 255, 255, .25); }
	.ps-tab.is-on .ps-tab_thumb { border: 5rpx solid $uh-reward; }
	.ps-tab_thumb--night { background: linear-gradient(180deg, #6C7A86 0%, #1E1B2E 60%); }
	.ps-tab_thumb--paper { background: #F6F3EE; border-bottom: 14rpx solid $uh-accent; }
	.ps-tab_thumb--quote { background: linear-gradient(135deg, #C2410C, #E2502A); }
	.ps-tab_text { color: rgba(255, 255, 255, .75); font-size: 22rpx; }
	.ps-tab.is-on .ps-tab_text { color: $uh-reward; font-weight: 700; }

	.ps-actions { display: flex; gap: 20rpx; padding: 36rpx 32rpx calc(36rpx + env(safe-area-inset-bottom)); }
	.ps-btn { flex: 1; height: 100rpx; border-radius: $uh-radius-pill; background: rgba(255, 255, 255, .12); display: flex; align-items: center; justify-content: center; }
	.ps-btn--accent { background: $uh-accent-bright; }
	.ps-btn_text { color: #ffffff; font-size: 30rpx; font-weight: 700; }
</style>
