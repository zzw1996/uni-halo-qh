<template>
	<!--
		图库（枫光改版 2026-10）。设计稿：Claude 画布「宥枫璟小程序 UI 改版」→ 图库页。
		结构：最新上传大卡 → 相册横滑 → 分组筛选 + 布局切换（瀑布流 / 按月）→ 照片墙；
		点相册进「相册详情」（同组件内的子视图，不新开页面，保留宿主页的下拉/触底转发）；
		点照片进自绘大图查看（带标题、描述、相册、日期，原生预览显示不了这些）。
		数据只用图库插件已有字段：spec.displayName / description / url / cover / groupName + metadata.creationTimestamp。
	-->
	<view class="gallery-view" :class="uniHaloPluginPageClass">
		<PluginUnavailable v-if="!uniHaloPluginAvailable" :pluginId="uniHaloPluginId"
			:error-text="uniHaloPluginAvailableError" />
		<template v-else>
			<!-- ===== 相册详情子视图 ===== -->
			<view v-if="album" class="album">
				<view class="album-hero">
					<image v-if="album.cover" class="album-hero_img" :src="album.cover" mode="aspectFill"></image>
					<view v-else class="album-hero_img album-hero_img--empty"></view>
					<view class="album-hero_mask"></view>
					<view class="album-hero_back" @click="fnCloseAlbum"><text class="iconfont icon-angle-left"></text></view>
					<view class="album-hero_info">
						<text class="album-hero_name">{{ album.displayName }}</text>
						<text class="album-hero_meta">{{ albumMetaText }}</text>
						<view class="album-hero_actions">
							<view class="album-btn album-btn--light" @click="fnPlayAlbum">
								<text class="album-btn_text album-btn_text--dark">▶ 从第一张看</text>
							</view>
						</view>
					</view>
				</view>
				<scroll-view v-if="albumList.length > 1" class="chips chips--album" scroll-x :show-scrollbar="false">
					<view v-for="g in albumList" :key="g.name" class="chip" :class="{ 'is-on': g.name === album.name }"
						@click="fnOpenAlbum(g)">
						<text class="chip_text">{{ g.displayName }}{{ g.count !== null ? ' · ' + g.count : '' }}</text>
					</view>
				</scroll-view>
				<block v-if="loading === 'loading'">
					<view class="sk-grid"><view v-for="i in 9" :key="i" class="sk-grid_cell"></view></view>
				</block>
				<view v-else-if="loading === 'error'" class="state-card">
					<text class="state-card_title">加载失败</text>
					<view class="state-btn" @click="fnGetData(true)"><text class="state-btn_text">重试</text></view>
				</view>
				<view v-else-if="dataList.length === 0" class="state-card">
					<text class="state-card_title">这个相册还没有照片</text>
					<view class="state-btn state-btn--dark" @click="fnCloseAlbum"><text class="state-btn_text">看全部照片</text></view>
				</view>
				<block v-else>
					<view v-for="m in monthGroups" :key="m.key" class="month">
						<view class="month-head">
							<text class="month-title">{{ m.title }}</text>
							<text class="month-count">{{ m.items.length }} 张</text>
						</view>
						<view class="month-grid">
							<view v-for="(p, i) in m.items" :key="p.metadata.name" class="month-cell"
								:class="{ 'month-cell--big': i === 0 && m.items.length >= 3 }" @click="fnOpenViewer(p)">
								<image class="month-cell_img" :src="p.spec.url" mode="aspectFill" lazy-load></image>
							</view>
						</view>
					</view>
					<view class="load-text">{{ loadMoreText }}</view>
				</block>
			</view>

			<!-- ===== 图库首屏 ===== -->
			<view v-else class="home">
				<view v-if="loading === 'loading' && dataList.length === 0" class="sk-waterfall">
					<view class="sk-waterfall_col">
						<view v-for="i in 3" :key="'l' + i" class="sk-waterfall_card" :style="{ height: i % 2 ? '360rpx' : '260rpx' }"></view>
					</view>
					<view class="sk-waterfall_col">
						<view v-for="i in 3" :key="'r' + i" class="sk-waterfall_card" :style="{ height: i % 2 ? '260rpx' : '340rpx' }"></view>
					</view>
				</view>
				<view v-else-if="loading === 'error'" class="state-card">
					<text class="state-card_title">图库暂时打不开</text>
					<text class="state-card_desc">网络开小差了，稍后再试</text>
					<view class="state-btn" @click="fnGetCategory"><text class="state-btn_text">重试</text></view>
				</view>
				<block v-else>
					<!-- 最新上传：只在「全部」视图且有图时出现 -->
					<view v-if="heroPhoto" class="hero" @click="fnOpenViewer(heroPhoto)">
						<view class="hero_stack hero_stack--2"></view>
						<view class="hero_stack hero_stack--1"></view>
						<view class="hero_card">
							<image class="hero_img" :src="heroPhoto.spec.url" mode="aspectFill"></image>
							<view class="hero_mask"></view>
							<text class="hero_tag">最新上传</text>
							<text v-if="totalCount" class="hero_count">共 {{ totalCount }} 张</text>
							<!-- 照片名来自附件文件名（如 IMG_2034.jpg），不展示；用相册名 + 日期作标题 -->
							<view class="hero_info">
								<text class="hero_title">{{ fnGroupName(heroPhoto) || '最新的一张' }}</text>
								<text v-if="fnPhotoDate(heroPhoto)" class="hero_meta">{{ fnPhotoDate(heroPhoto) }} 上传</text>
							</view>
						</view>
					</view>

					<!-- 相册横滑 -->
					<block v-if="albumList.length">
						<view class="sec-head">
							<text class="sec-title">相册</text>
							<text class="sec-sub">{{ albumSummaryText }}</text>
						</view>
						<scroll-view class="albums" scroll-x :show-scrollbar="false">
							<view v-for="g in albumList" :key="g.name" class="albums_item" @click="fnOpenAlbum(g)">
								<view class="albums_cover-wrap">
									<image v-if="g.cover" class="albums_cover" :src="g.cover" mode="aspectFill" lazy-load></image>
									<view v-else class="albums_cover albums_cover--empty"><text class="albums_letter">{{ g.displayName.charAt(0) }}</text></view>
									<text v-if="g.count" class="albums_count">{{ g.count }}</text>
								</view>
								<text class="albums_name">{{ g.displayName }}</text>
							</view>
						</scroll-view>
					</block>

					<!-- 筛选 + 布局切换 -->
					<view class="toolbar">
						<scroll-view v-if="category.list.length > 1" class="chips" scroll-x :show-scrollbar="false">
							<view v-for="(c, idx) in category.list" :key="c.displayName" class="chip"
								:class="{ 'is-on': category.activeIndex === idx }" @click="fnOnCategoryChange(idx)">
								<text class="chip_text">{{ c.displayName }}</text>
							</view>
						</scroll-view>
						<view v-else class="chips"></view>
						<view class="layout-switch">
							<view class="layout-switch_btn" :class="{ 'is-on': layout === 'waterfall' }" @click="fnSetLayout('waterfall')">
								<text class="layout-switch_text">瀑布</text>
							</view>
							<view class="layout-switch_btn" :class="{ 'is-on': layout === 'month' }" @click="fnSetLayout('month')">
								<text class="layout-switch_text">按月</text>
							</view>
						</view>
					</view>

					<view v-if="dataList.length === 0" class="state-card">
						<text class="state-card_title">博主还没有分享图片</text>
					</view>
					<block v-else-if="layout === 'waterfall'">
						<view class="waterfall">
							<view v-for="(col, ci) in waterfallCols" :key="ci" class="waterfall_col">
								<view v-for="p in col" :key="p.metadata.name" class="wf-item" @click="fnOpenViewer(p)">
									<image class="wf-item_img" :src="p.spec.url" mode="widthFix" lazy-load></image>
								</view>
							</view>
						</view>
						<view class="load-text">{{ loadMoreText }}</view>
					</block>
					<block v-else>
						<view v-for="m in monthGroups" :key="m.key" class="month">
							<view class="month-head">
								<text class="month-title">{{ m.title }}</text>
								<text class="month-count">{{ m.items.length }} 张</text>
							</view>
							<view class="month-grid">
								<view v-for="(p, i) in m.items" :key="p.metadata.name" class="month-cell"
									:class="{ 'month-cell--big': i === 0 && m.items.length >= 3 }" @click="fnOpenViewer(p)">
									<image class="month-cell_img" :src="p.spec.url" mode="aspectFill" lazy-load></image>
								</view>
							</view>
						</view>
						<view class="load-text">{{ loadMoreText }}</view>
					</block>
				</block>
			</view>

			<view v-if="!calcAuditModeEnabled && !viewer.show" class="flot-buttons" :style="calcFlotButtonsStyle">
				<view class="to-top" @click="fnToTopPage"><text class="iconfont icon-angle-up to-top_icon"></text></view>
			</view>

			<!-- ===== 大图查看 ===== -->
			<view v-if="viewer.show" class="viewer" @touchmove.stop.prevent="fnNoop">
				<view class="viewer-top" :style="viewerTopStyle">
					<view class="viewer-close" @click="fnCloseViewer"><text class="viewer-close_text">✕</text></view>
					<text class="viewer-counter">{{ viewer.index + 1 }} / {{ viewerList.length }}</text>
					<view class="viewer-close viewer-close--ghost"></view>
				</view>
				<swiper class="viewer-swiper" :current="viewer.index" @change="fnOnViewerChange">
					<swiper-item v-for="p in viewerList" :key="p.metadata.name">
						<image class="viewer-img" :src="p.spec.url" mode="aspectFit" @click="fnZoomCurrent"></image>
					</swiper-item>
				</swiper>
				<view v-if="viewerPhoto" class="viewer-panel">
					<view class="viewer-tags">
						<text v-if="fnGroupName(viewerPhoto)" class="viewer-tag viewer-tag--album">{{ fnGroupName(viewerPhoto) }}</text>
						<text v-if="fnPhotoDate(viewerPhoto)" class="viewer-tag">{{ fnPhotoDate(viewerPhoto) }}</text>
					</view>
					<text v-if="viewerPhoto.spec.description" class="viewer-desc">{{ viewerPhoto.spec.description }}</text>
					<scroll-view class="viewer-thumbs" scroll-x :show-scrollbar="false" :scroll-into-view="'vt-' + viewer.index" scroll-with-animation>
						<view v-for="(p, i) in viewerList" :key="p.metadata.name" :id="'vt-' + i" class="viewer-thumb"
							:class="{ 'is-on': i === viewer.index }" @click="viewer.index = i">
							<image class="viewer-thumb_img" :src="p.spec.url" mode="aspectFill" lazy-load></image>
						</view>
					</scroll-view>
					<view class="viewer-actions">
						<view class="viewer-act" @click="fnSaveCurrent"><text class="viewer-act_text">保存</text></view>
						<view class="viewer-act" @click="fnShareCurrent"><text class="viewer-act_text">分享</text></view>
						<view v-if="!album && viewerPhoto.spec.groupName && fnGroupByName(viewerPhoto.spec.groupName)"
							class="viewer-act viewer-act--accent" @click="fnOpenAlbumOfCurrent">
							<text class="viewer-act_text">整个相册</text>
						</view>
					</view>
				</view>
			</view>
		</template>
	</view>
</template>

<script>
	import { tabbarBottomOffsetPx } from '@/utils/tabbar.js';
	import pluginAvailable from "@/common/mixins/pluginAvailable.js"
	import PluginUnavailable from '@/components/plugin-unavailable/plugin-unavailable.vue'

	/** 相册封面/张数最多并发取前几个（每个相册一次 size=1 的请求），相册很多时不至于一进页就打几十个请求 */
	const ALBUM_META_LIMIT = 12;

	export default {
		name: 'gallery-view',
		options: {
			multipleSlots: true
		},
		mixins: [pluginAvailable],
		components: {
			PluginUnavailable
		},
		props: {
			/**
			 * 宿主页（瞬间页）的下拉刷新 / 触底加载信号。
			 * 小程序端不能用 `$refs` 调子组件方法，也不该用 `uni.$emit`（常驻监听，组件销毁后可能报错）；
			 * 用递增的计数属性最稳：宿主页 +1，这里 watch 到变化就执行。
			 */
			pullDownTick: { type: Number, default: 0 },
			reachBottomTick: { type: Number, default: 0 }
		},
		data() {
			return {
				loading: 'loading',
				category: {
					activeIndex: 0,
					list: []
				},
				/** 相册（分组）列表：{ name, displayName, priority, cover, count }；cover/count 异步补齐 */
				albumList: [],
				/** 当前打开的相册（null = 首屏） */
				album: null,
				queryParams: {
					size: 20,
					page: 1,
					group: ''
				},
				isLoadMore: false,
				loadMoreText: '',
				hasNext: false,
				dataList: [],
				/** 「全部」视图下的总张数，来自列表接口的 total */
				totalCount: 0,
				layout: 'waterfall',
				lock: false,
				viewer: {
					show: false,
					index: 0
				}
			};
		},
		computed: {
			calcFlotButtonsStyle() {
				const offset = tabbarBottomOffsetPx();
				return offset ? `bottom: calc(100rpx + ${offset}px)` : '';
			},
			haloConfigs() {
				return this.$tm.vx.getters().getConfigs;
			},
			mockJson() {
				return this.$tm.vx.getters().getMockJson;
			},
			calcAuditModeEnabled() {
				return !!(this.haloConfigs && this.haloConfigs.auditConfig && this.haloConfigs.auditConfig.auditModeEnabled);
			},
			heroPhoto() {
				return !this.album && this.category.activeIndex === 0 ? this.dataList[0] || null : null;
			},
			/** 两列瀑布流：按顺序交替分配。图片 widthFix 自然高度，不需要预先量图 */
			waterfallCols() {
				const cols = [[], []];
				const list = this.heroPhoto ? this.dataList.slice(1) : this.dataList;
				list.forEach((p, i) => cols[i % 2].push(p));
				return cols;
			},
			monthGroups() {
				const map = {};
				const order = [];
				this.dataList.forEach((p) => {
					const d = this.fnParseDate(p);
					const key = d ? `${d.getFullYear()}-${d.getMonth() + 1}` : 'unknown';
					if (!map[key]) {
						map[key] = { key, title: d ? `${d.getFullYear()} · ${d.getMonth() + 1} 月` : '更早', items: [] };
						order.push(key);
					}
					map[key].items.push(p);
				});
				return order.map((k) => map[k]);
			},
			albumSummaryText() {
				const n = this.albumList.length;
				return this.totalCount ? `${this.totalCount} 张 · ${n} 个相册` : `${n} 个相册`;
			},
			albumMetaText() {
				if (!this.album) return '';
				const count = this.album.count !== null && this.album.count !== undefined ? `${this.album.count} 张` : `${this.dataList.length} 张`;
				const range = this.monthGroups.length ? this.monthGroups[0].title : '';
				return range ? `${count} · 最近更新 ${range}` : count;
			},
			viewerList() {
				return this.dataList;
			},
			viewerPhoto() {
				return this.viewerList[this.viewer.index] || null;
			},
			viewerTopStyle() {
				let top = 20;
				try {
					const info = uni.getWindowInfo ? uni.getWindowInfo() : uni.getSystemInfoSync();
					top = (info.statusBarHeight || 20) + 6;
				} catch (e) {}
				return `padding-top:${top}px`;
			}
		},
		watch: {
			pullDownTick(val) {
				if (val > 0) this.viewOnPullDownRefresh();
			},
			reachBottomTick(val) {
				if (val > 0) this.viewOnReachBottom();
			}
		},
		async mounted() {
			this.setPluginId(this.NeedPluginIds.PluginPhotos)
			this.setPluginError("阿偶，检测到当前插件没有安装或者启用，无法使用图库功能哦，请联系管理员")
			await this.checkPluginAvailable()
			try {
				const saved = uni.getStorageSync('uhalo_gallery_layout');
				if (saved === 'month' || saved === 'waterfall') this.layout = saved;
			} catch (e) {}
			if (this.uniHaloPluginAvailable) this.fnGetCategory();
		},
		methods: {
			fnNoop() {},
			viewOnPullDownRefresh() {
				if (!this.uniHaloPluginAvailable) {
					uni.hideLoading();
					uni.stopPullDownRefresh();
					return
				}
				this.isLoadMore = false;
				this.queryParams.page = 1;
				this.fnGetData(true);
				if (!this.album) this.fnLoadAlbumMeta();
			},
			viewOnReachBottom() {
				if (!this.uniHaloPluginAvailable || this.viewer.show) return;
				if (this.calcAuditModeEnabled || !this.hasNext) {
					uni.showToast({ icon: 'none', title: '没有更多数据了' });
					return;
				}
				if (this.lock) return;
				this.queryParams.page += 1;
				this.isLoadMore = true;
				this.fnGetData(false);
			},
			fnSetLayout(layout) {
				this.layout = layout;
				try { uni.setStorageSync('uhalo_gallery_layout', layout); } catch (e) {}
			},
			fnResetList(group) {
				this.queryParams.group = group;
				this.queryParams.page = 1;
				this.isLoadMore = false;
				this.dataList = [];
				this.fnToTopPage();
				this.fnGetData(true);
			},
			fnOnCategoryChange(index) {
				if (this.lock || index === this.category.activeIndex) return;
				this.category.activeIndex = index;
				this.fnResetList(this.category.list[index].name);
			},
			fnGetCategory() {
				if (this.calcAuditModeEnabled) {
					this.fnGetData(true);
					return
				}
				this.loading = 'loading';
				this.$httpApi.v2.getPhotoGroupList({ page: 1, size: 0 }).then(res => {
					const groups = (Array.isArray(res) ? res : (res && res.items) || []).map(item => ({
						name: item.metadata.name,
						displayName: item.spec.displayName,
						priority: item.spec.priority,
						// 插件新版本在 status 上带张数；没有就等下面的 size=1 请求补
						count: item.status && typeof item.status.photoCount === 'number' ? item.status.photoCount : null,
						cover: ''
					})).sort((a, b) => a.priority - b.priority);
					this.albumList = groups;
					this.category.list = [{ name: undefined, displayName: '全部', priority: 0 }].concat(
						groups.map(g => ({ name: g.name, displayName: g.displayName, priority: g.priority })));
					this.category.activeIndex = 0;
					this.queryParams.group = undefined;
					this.fnGetData(true);
					this.fnLoadAlbumMeta();
				}).catch(() => {
					this.loading = 'error';
					this.category.list = [];
					this.albumList = [];
				});
			},
			/** 补齐每个相册的封面（第一张）与张数（列表接口的 total） */
			fnLoadAlbumMeta() {
				this.albumList.slice(0, ALBUM_META_LIMIT).forEach((g) => {
					this.$httpApi.v2.getPhotoListByGroupName({ page: 1, size: 1, group: g.name }).then(res => {
						const first = res && res.items && res.items[0];
						if (first) g.cover = this.$utils.checkImageUrl(first.spec.url || first.spec.cover);
						if (res && typeof res.total === 'number') g.count = res.total;
					}).catch(() => {});
				});
			},
			fnGetData(isReset = false) {
				if (this.calcAuditModeEnabled) {
					this.dataList = ((this.mockJson && this.mockJson.gallery && this.mockJson.gallery.list) || []).map((item, i) => ({
						metadata: { name: 'mock-' + i },
						spec: { url: this.$utils.checkImageUrl(item) }
					}));
					this.totalCount = this.dataList.length;
					this.loading = 'success';
					this.loadMoreText = '呜呜，没有更多数据啦~';
					uni.hideLoading();
					uni.stopPullDownRefresh();
					this.lock = false;
					return;
				}
				if (!this.isLoadMore) this.loading = 'loading';
				this.lock = true;
				this.loadMoreText = '';
				this.$httpApi.v2.getPhotoListByGroupName(this.queryParams).then(res => {
					this.hasNext = res.hasNext;
					this.loading = 'success';
					const list = (res.items || []).map(item => {
						item.spec.url = this.$utils.checkImageUrl(item.spec.url || item.spec.cover);
						return item;
					});
					this.dataList = this.isLoadMore && !isReset ? this.dataList.concat(list) : list;
					if (!this.queryParams.group && !this.album && typeof res.total === 'number') this.totalCount = res.total;
					if (this.album && typeof res.total === 'number') this.album.count = res.total;
					this.loadMoreText = res.hasNext ? '上拉加载更多' : (this.dataList.length ? `已显示全部 · 共 ${this.dataList.length} 张` : '');
				}).catch(err => {
					console.error(err);
					this.loading = 'error';
					this.loadMoreText = '加载失败，请下拉刷新！';
				}).finally(() => {
					this.isLoadMore = false;
					setTimeout(() => {
						uni.hideLoading();
						uni.stopPullDownRefresh();
						this.lock = false;
					}, 300);
				});
			},
			fnOpenAlbum(g) {
				if (!g || this.lock) return;
				this.album = g;
				this.fnResetList(g.name);
			},
			fnCloseAlbum() {
				this.album = null;
				const cur = this.category.list[this.category.activeIndex];
				this.fnResetList(cur ? cur.name : undefined);
			},
			fnPlayAlbum() {
				if (this.dataList.length) this.fnOpenViewer(this.dataList[0]);
			},
			fnGroupByName(name) {
				return this.albumList.find(g => g.name === name) || null;
			},
			fnGroupName(p) {
				const g = p && p.spec && p.spec.groupName ? this.fnGroupByName(p.spec.groupName) : null;
				return g ? g.displayName : '';
			},
			fnParseDate(p) {
				const raw = p && p.metadata && p.metadata.creationTimestamp;
				if (!raw) return null;
				const d = new Date(raw);
				return isNaN(d.getTime()) ? null : d;
			},
			fnPhotoDate(p) {
				const d = this.fnParseDate(p);
				return d ? `${d.getFullYear()}.${d.getMonth() + 1}.${d.getDate()}` : '';
			},
			fnPhotoMeta(p) {
				return [this.fnGroupName(p), this.fnPhotoDate(p)].filter(Boolean).join(' · ');
			},
			fnOpenViewer(p) {
				const index = this.dataList.findIndex(x => x.metadata.name === p.metadata.name);
				this.viewer.index = index < 0 ? 0 : index;
				this.viewer.show = true;
			},
			fnCloseViewer() {
				this.viewer.show = false;
			},
			fnOnViewerChange(e) {
				this.viewer.index = e.detail.current;
				// 翻到最后两张时预取下一页，免得用户以为已经看完
				if (this.viewer.index >= this.dataList.length - 2 && this.hasNext && !this.lock) {
					this.queryParams.page += 1;
					this.isLoadMore = true;
					this.fnGetData(false);
				}
			},
			/** 点大图：交给原生预览做双指缩放 */
			fnZoomCurrent() {
				if (!this.viewerPhoto) return;
				uni.previewImage({ current: this.viewerPhoto.spec.url, urls: [this.viewerPhoto.spec.url] });
			},
			fnSaveCurrent() {
				const p = this.viewerPhoto;
				if (!p) return;
				uni.showLoading({ title: '保存中' });
				uni.downloadFile({
					url: p.spec.url,
					success: (res) => {
						uni.saveImageToPhotosAlbum({
							filePath: res.tempFilePath,
							success: () => uni.showToast({ title: '已保存到相册', icon: 'none' }),
							fail: (err) => {
								const denied = err && /auth|deny/i.test(err.errMsg || '');
								uni.showToast({ title: denied ? '请在设置里允许保存到相册' : '保存失败', icon: 'none' });
							}
						});
					},
					fail: () => uni.showToast({ title: '下载失败', icon: 'none' }),
					complete: () => uni.hideLoading()
				});
			},
			/** 分享：微信支持 showShareImageMenu（可转发/保存/收藏），低版本退回原生预览长按 */
			fnShareCurrent() {
				const p = this.viewerPhoto;
				if (!p) return;
				// #ifdef MP-WEIXIN
				if (typeof wx !== 'undefined' && typeof wx.showShareImageMenu === 'function') {
					uni.downloadFile({
						url: p.spec.url,
						success: (res) => wx.showShareImageMenu({ path: res.tempFilePath }),
						fail: () => uni.showToast({ title: '图片加载失败', icon: 'none' })
					});
					return;
				}
				// #endif
				uni.previewImage({ current: p.spec.url, urls: [p.spec.url] });
			},
			fnOpenAlbumOfCurrent() {
				const g = this.viewerPhoto ? this.fnGroupByName(this.viewerPhoto.spec.groupName) : null;
				if (!g) return;
				this.viewer.show = false;
				this.fnOpenAlbum(g);
			}
		}
	};
</script>

<style lang="scss" scoped>
	.gallery-view {
		width: 100vw;
		min-height: 100vh;
		padding-bottom: 200rpx;
		box-sizing: border-box;
		background-color: $uh-bg;
	}

	/* ===== 最新上传 ===== */
	.hero { position: relative; height: 600rpx; margin: 16rpx 32rpx 0; }
	.hero_stack { position: absolute; height: 40rpx; }
	.hero_stack--2 { left: 36rpx; right: 36rpx; top: 0; border-radius: 40rpx 40rpx 0 0; background: #D8CFC2; }
	.hero_stack--1 { left: 18rpx; right: 18rpx; top: 14rpx; border-radius: 44rpx 44rpx 0 0; background: #E9E1D5; }
	.hero_card { position: absolute; left: 0; right: 0; top: 28rpx; bottom: 0; border-radius: $uh-radius-xl; overflow: hidden; background: $uh-fill; }
	.hero_img { width: 100%; height: 100%; display: block; }
	.hero_mask { position: absolute; left: 0; right: 0; bottom: 0; height: 320rpx; background: linear-gradient(180deg, rgba(20, 16, 12, 0), rgba(20, 16, 12, .75)); }
	.hero_tag { position: absolute; left: 28rpx; top: 28rpx; padding: 8rpx 20rpx; border-radius: $uh-radius-pill; background: $uh-accent-bright; color: #ffffff; font-size: 22rpx; font-weight: 700; }
	.hero_count { position: absolute; right: 28rpx; top: 28rpx; padding: 8rpx 20rpx; border-radius: $uh-radius-pill; background: rgba(26, 26, 31, .6); color: #ffffff; font-size: 22rpx; font-weight: 700; font-family: $uh-font-num; }
	.hero_info { position: absolute; left: 32rpx; right: 32rpx; bottom: 32rpx; display: flex; flex-direction: column; }
	.hero_title { color: #ffffff; font-size: 42rpx; font-weight: 900; line-height: 1.3; }
	.hero_meta { margin-top: 10rpx; color: rgba(255, 255, 255, .9); font-size: 24rpx; }

	/* ===== 相册横滑 ===== */
	.sec-head { display: flex; align-items: baseline; justify-content: space-between; margin: 48rpx 32rpx 0; }
	.sec-title { font-size: 38rpx; font-weight: 700; color: $uh-ink-1; }
	.sec-sub { font-size: 24rpx; color: $uh-ink-3; }
	.albums { margin-top: 24rpx; padding-left: 32rpx; white-space: nowrap; width: 100%; box-sizing: border-box; }
	.albums_item { display: inline-flex; flex-direction: column; width: 256rpx; margin-right: 20rpx; vertical-align: top; }
	.albums_cover-wrap { position: relative; width: 256rpx; height: 256rpx; }
	.albums_cover { width: 256rpx; height: 256rpx; border-radius: 40rpx; display: block; background: $uh-fill; }
	.albums_cover--empty { display: flex; align-items: center; justify-content: center; background: $uh-accent; }
	.albums_letter { font-size: 80rpx; font-weight: 900; color: rgba(255, 255, 255, .55); }
	.albums_count { position: absolute; right: 16rpx; bottom: 16rpx; padding: 4rpx 14rpx; border-radius: $uh-radius-pill; background: rgba(26, 26, 31, .62); color: #ffffff; font-size: 22rpx; font-weight: 700; font-family: $uh-font-num; }
	.albums_name { margin-top: 16rpx; font-size: 28rpx; font-weight: 700; color: $uh-ink-1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

	/* ===== 筛选 + 布局切换 ===== */
	.toolbar { display: flex; align-items: center; gap: 16rpx; margin: 48rpx 32rpx 0; }
	.chips { flex: 1; min-width: 0; white-space: nowrap; }
	.chips--album { margin: 32rpx 0 0; padding-left: 32rpx; width: 100%; box-sizing: border-box; }
	.chip { display: inline-flex; align-items: center; height: 64rpx; padding: 0 28rpx; margin-right: 12rpx; border-radius: $uh-radius-pill; background: $uh-card; }
	.chip.is-on { background: $uh-ink-1; }
	.chip_text { font-size: 26rpx; color: $uh-ink-2; }
	.chip.is-on .chip_text { color: #ffffff; font-weight: 700; }
	.layout-switch { flex-shrink: 0; display: flex; padding: 6rpx; border-radius: $uh-radius-pill; background: $uh-line; }
	.layout-switch_btn { padding: 8rpx 18rpx; border-radius: $uh-radius-pill; }
	.layout-switch_btn.is-on { background: $uh-card; }
	.layout-switch_text { font-size: 22rpx; color: $uh-ink-3; }
	.layout-switch_btn.is-on .layout-switch_text { color: $uh-ink-1; font-weight: 700; }

	/* ===== 瀑布流 ===== */
	.waterfall { display: flex; gap: 20rpx; margin: 24rpx 32rpx 0; align-items: flex-start; }
	.waterfall_col { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 20rpx; }
	.wf-item { display: flex; flex-direction: column; }
	.wf-item_img { width: 100%; border-radius: 36rpx; display: block; background: $uh-fill; }
	.wf-item_title { margin-top: 12rpx; font-size: 26rpx; font-weight: 700; color: $uh-ink-1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
	.wf-item_sub { font-size: 22rpx; color: $uh-ink-3; }

	/* ===== 按月 ===== */
	.month { margin-top: 40rpx; }
	.month-head { display: flex; align-items: baseline; justify-content: space-between; margin: 0 40rpx 20rpx; }
	.month-title { font-family: $uh-font-num; font-size: 40rpx; font-weight: 800; color: $uh-ink-1; }
	.month-count { font-size: 24rpx; color: $uh-ink-3; }
	.month-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8rpx; margin: 0 32rpx; border-radius: 32rpx; overflow: hidden; }
	.month-cell { height: 228rpx; }
	.month-cell--big { grid-column: span 2; grid-row: span 2; height: 464rpx; }
	.month-cell_img { width: 100%; height: 100%; display: block; background: $uh-fill; }

	/* ===== 相册详情 ===== */
	.album-hero { position: relative; height: 560rpx; margin: 0 32rpx; border-radius: $uh-radius-xl; overflow: hidden; }
	.album-hero_img { width: 100%; height: 100%; display: block; }
	.album-hero_img--empty { background: $uh-accent; }
	.album-hero_mask { position: absolute; left: 0; right: 0; top: 0; bottom: 0; background: linear-gradient(180deg, rgba(20, 16, 12, .1), rgba(20, 16, 12, .78)); }
	.album-hero_back { position: absolute; left: 24rpx; top: 24rpx; width: 72rpx; height: 72rpx; border-radius: 50%; background: rgba(255, 255, 255, .92); display: flex; align-items: center; justify-content: center; color: $uh-ink-1; font-size: 36rpx; }
	.album-hero_info { position: absolute; left: 36rpx; right: 36rpx; bottom: 36rpx; display: flex; flex-direction: column; }
	.album-hero_name { color: #ffffff; font-size: 56rpx; font-weight: 900; }
	.album-hero_meta { margin-top: 8rpx; color: rgba(255, 255, 255, .9); font-size: 26rpx; }
	.album-hero_actions { display: flex; gap: 16rpx; margin-top: 24rpx; }
	.album-btn { height: 72rpx; padding: 0 32rpx; border-radius: $uh-radius-pill; display: flex; align-items: center; }
	.album-btn--light { background: #ffffff; }
	.album-btn_text { font-size: 26rpx; font-weight: 700; color: #ffffff; }
	.album-btn_text--dark { color: $uh-ink-1; }

	/* ===== 骨架 / 状态 ===== */
	.sk-waterfall { display: flex; gap: 20rpx; padding: 24rpx 32rpx 0; }
	.sk-waterfall_col { flex: 1; }
	.sk-waterfall_card, .sk-grid_cell { width: 100%; margin-bottom: 20rpx; border-radius: 36rpx; background-color: $uh-fill; animation: skPulse 1.2s ease-in-out infinite; }
	.sk-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8rpx; margin: 40rpx 32rpx 0; }
	.sk-grid_cell { height: 228rpx; margin: 0; border-radius: 12rpx; }
	@keyframes skPulse { 0%, 100% { opacity: 1; } 50% { opacity: .55; } }
	.state-card { margin: 48rpx 32rpx 0; padding: 56rpx 40rpx; border-radius: $uh-radius-xl; background: $uh-card; display: flex; flex-direction: column; align-items: center; }
	.state-card_title { font-size: 32rpx; font-weight: 700; color: $uh-ink-1; }
	.state-card_desc { margin-top: 8rpx; font-size: 26rpx; color: $uh-ink-3; }
	.state-btn { margin-top: 28rpx; height: 76rpx; padding: 0 40rpx; border-radius: $uh-radius-pill; background: $uh-accent; display: flex; align-items: center; }
	.state-btn--dark { background: $uh-ink-1; }
	.state-btn_text { font-size: 26rpx; font-weight: 700; color: #ffffff; }
	.load-text { margin-top: 32rpx; text-align: center; font-size: 24rpx; color: $uh-ink-3; }

	.flot-buttons { position: fixed; bottom: 100rpx; right: 32rpx; z-index: 999; }
	.to-top { width: 92rpx; height: 92rpx; border-radius: 50%; background: $uh-ink-1; display: flex; align-items: center; justify-content: center; box-shadow: 0 16rpx 40rpx rgba(26, 26, 31, .24); }
	.to-top_icon { color: #ffffff; font-size: 36rpx; }

	/* ===== 大图查看：始终深色，与主题无关 ===== */
	.viewer { position: fixed; left: 0; top: 0; right: 0; bottom: 0; z-index: 10000; background: #0E0D14; display: flex; flex-direction: column; }
	.viewer-top { display: flex; align-items: center; justify-content: space-between; padding-left: 32rpx; padding-right: 32rpx; padding-bottom: 12rpx; }
	.viewer-close { width: 72rpx; height: 72rpx; border-radius: 50%; background: rgba(255, 255, 255, .12); display: flex; align-items: center; justify-content: center; }
	.viewer-close--ghost { background: transparent; }
	.viewer-close_text { color: #ffffff; font-size: 30rpx; }
	.viewer-counter { color: #ffffff; font-family: $uh-font-num; font-size: 30rpx; font-weight: 700; }
	.viewer-swiper { flex: 1; width: 100%; }
	.viewer-img { width: 100%; height: 100%; }
	.viewer-panel { padding: 24rpx 40rpx calc(40rpx + env(safe-area-inset-bottom)); display: flex; flex-direction: column; }
	.viewer-tags { display: flex; gap: 12rpx; }
	.viewer-tag { padding: 6rpx 18rpx; border-radius: $uh-radius-pill; background: rgba(255, 255, 255, .1); color: rgba(255, 255, 255, .75); font-size: 22rpx; }
	.viewer-tag--album { background: $uh-reward; color: $uh-ink-1; font-weight: 700; }
	.viewer-title { margin-top: 20rpx; color: #ffffff; font-size: 40rpx; font-weight: 900; }
	.viewer-desc { margin-top: 12rpx; color: rgba(255, 255, 255, .8); font-size: 28rpx; line-height: 1.7; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 3; overflow: hidden; }
	.viewer-thumbs { margin-top: 28rpx; white-space: nowrap; width: 100%; }
	.viewer-thumb { display: inline-block; width: 96rpx; height: 96rpx; margin-right: 12rpx; border-radius: 20rpx; overflow: hidden; opacity: .5; box-sizing: border-box; }
	.viewer-thumb.is-on { opacity: 1; border: 4rpx solid $uh-reward; }
	.viewer-thumb_img { width: 100%; height: 100%; display: block; }
	.viewer-actions { display: flex; gap: 16rpx; margin-top: 28rpx; }
	.viewer-act { flex: 1; height: 88rpx; border-radius: $uh-radius-pill; background: rgba(255, 255, 255, .12); display: flex; align-items: center; justify-content: center; }
	.viewer-act--accent { background: $uh-accent-bright; }
	.viewer-act_text { color: #ffffff; font-size: 28rpx; font-weight: 700; }

	/* 深色（极夜）皮肤：新元素在重跑皮肤生成器前手写兜底 */
	.skin-ink .gallery-view { background-color: #081528; }
	.skin-ink .chip, .skin-ink .state-card { background: #10213b; }
	.skin-ink .chip_text, .skin-ink .state-card_desc, .skin-ink .sec-sub, .skin-ink .month-count, .skin-ink .wf-item_sub, .skin-ink .load-text { color: #9bafcc; }
	.skin-ink .chip.is-on { background: #56e3ef; }
	.skin-ink .chip.is-on .chip_text { color: #081528; }
	.skin-ink .sec-title, .skin-ink .albums_name, .skin-ink .wf-item_title, .skin-ink .month-title, .skin-ink .state-card_title { color: #eaf3ff; }
	.skin-ink .layout-switch { background: #192d4a; }
	.skin-ink .layout-switch_btn.is-on { background: #10213b; }
	.skin-ink .layout-switch_btn.is-on .layout-switch_text { color: #eaf3ff; }
	.skin-ink .to-top { background: #56e3ef; }
	.skin-ink .to-top_icon { color: #081528; }
</style>
