<template>
	<view :class="skinClass" class="app-page">
		<view v-if="loading !== 'success'" class="loading-wrap">
			<tm-skeleton model="listAvatr"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
		</view>
		<block v-else>
			<!-- 券余额放在最上面：这张页面的核心信息是「我还有几张券」+「买过哪些」 -->
			<view class="summary-card">
				<view class="summary-row">
					<text class="summary-label">剩余阅读券</text>
					<text class="summary-value">{{ tickets === null ? '—' : tickets }}<text class="summary-unit">张</text></text>
				</view>
				<view class="summary-row">
					<text class="summary-label">已解锁</text>
					<text class="summary-value">{{ total }}<text class="summary-unit">篇</text></text>
				</view>
			</view>

			<view class="section-card">
				<view class="section-head">
					<text class="section-title">已解锁文章</text>
					<text class="section-sub">按解锁时间倒序，永久有效不会重复扣券</text>
				</view>

				<view v-if="items.length === 0" class="empty-tip">
					还没有解锁记录。遇到付费文章时用阅读券解锁后会出现在这里。
				</view>

				<view
					v-for="(item, index) in items"
					:key="item.articleName + index"
					class="article-item"
					:hover-class="item.available ? 'article-item-hover' : ''"
					@click="fnOpenArticle(item)"
				>
					<image
						v-if="item.cover"
						class="article-cover"
						mode="aspectFill"
						:src="fnCoverUrl(item.cover)"
					></image>
					<view class="article-main">
						<text class="article-title">{{ item.title }}</text>
						<view class="article-meta">
							<text class="article-time">{{ item.unlockTime || '—' }} 解锁</text>
							<text v-if="item.ticketsCost != null" class="article-cost">
								消耗 {{ item.ticketsCost }} 张券
							</text>
						</view>
						<text v-if="!item.available" class="article-offline">该文章已下架，无法继续阅读</text>
					</view>
					<text v-if="item.available" class="article-arrow">›</text>
				</view>
			</view>

			<view class="footer-tip">
				<text>解锁记录永久保存，同一篇文章不会重复扣券。若券不够用，可到「权益直购」页用积分兑换或直接购买。</text>
			</view>
		</block>
	</view>
</template>

<script>
	import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
	import { checkHasWxLogin } from '@/utils/auth.js';

	export default {
		components: {
			tmSkeleton
		},
		data() {
			return {
				loading: 'loading',
				items: [],
				total: 0,
				/** 当前券余额；null = 未取到（旧插件/接口不可用），展示为「—」而不是 0 张 */
				tickets: null
			};
		},
		onLoad() {
			if (!checkHasWxLogin()) {
				uni.showToast({ title: '请先登录', icon: 'none' });
				setTimeout(() => uni.navigateBack(), 800);
				return;
			}
			this.fnLoad();
		},
		onPullDownRefresh() {
			this.fnLoad();
		},
		methods: {
			async fnLoad() {
				this.loading = 'loading';
				try {
					const res = await this.$httpApi.v2.getMyUnlockedArticles();
					const body = res?.data || res || {};
					if (body.error) {
						throw { error: body.error };
					}
					this.items = Array.isArray(body.items) ? body.items : [];
					this.total = Number(body.total || this.items.length);
					const tickets = Number(body.tickets);
					this.tickets = Number.isFinite(tickets) ? tickets : null;
					this.loading = 'success';
				} catch (err) {
					this.loading = 'success';
					uni.showToast({
						title: err?.error || err?.detail || '加载失败，请稍后重试',
						icon: 'none'
					});
				} finally {
					uni.stopPullDownRefresh();
				}
			},
			/** 已下架的文章不给跳转，避免点进去 404 */
			fnOpenArticle(item) {
				if (!item.available || !item.articleName) {
					uni.showToast({ title: '该文章已下架', icon: 'none' });
					return;
				}
				uni.navigateTo({
					url: '/pagesA/article-detail/article-detail?name=' + item.articleName
				});
			},
			/** 封面地址兜底：已是完整地址则原样返回，否则拼站点前缀（与详情页 calcUrl 同口径） */
			fnCoverUrl(cover) {
				if (!cover) {
					return '';
				}
				if (this.$utils.checkIsUrl(cover)) {
					return cover;
				}
				return getApp().globalData.baseApiUrl + cover;
			}
		}
	};
</script>

<style scoped lang="scss">
	.app-page {
		min-height: 100vh;
		background: $uh-fill;
		padding-bottom: 40rpx;
	}

	.loading-wrap {
		padding: 24rpx;
	}

	.summary-card {
		display: flex;
		margin: 24rpx 28rpx 16rpx;
		padding: 32rpx 28rpx;
		border-radius: 20rpx;
		background-image: $uh-grad-accent;
		color: #fff;
	}

	.summary-row {
		flex: 1;
		display: flex;
		flex-direction: column;
		align-items: center;
	}

	.summary-label {
		font-size: 24rpx;
		opacity: 0.92;
	}

	.summary-value {
		margin-top: 8rpx;
		font-size: 48rpx;
		font-weight: 700;
		line-height: 1.1;
	}

	.summary-unit {
		margin-left: 6rpx;
		font-size: 22rpx;
		font-weight: 400;
		opacity: 0.9;
	}

	.section-card {
		margin: 0 28rpx 16rpx;
		padding: 24rpx 28rpx;
		border-radius: 20rpx;
		background: $uh-card;
	}

	.section-head {
		margin-bottom: 12rpx;
	}

	.section-title {
		display: block;
		font-size: 28rpx;
		font-weight: 700;
		color: $uh-ink-1;
	}

	.section-sub {
		display: block;
		margin-top: 4rpx;
		font-size: 20rpx;
		color: $uh-ink-3;
	}

	.empty-tip {
		padding: 48rpx 0;
		font-size: 24rpx;
		color: $uh-ink-3;
		text-align: center;
	}

	.article-item {
		display: flex;
		align-items: center;
		padding: 22rpx 0;
		border-top: 1rpx solid #f0f1f5;
	}

	.article-item:first-of-type {
		border-top: none;
	}

	.article-item-hover {
		background: #fafbff;
	}

	.article-cover {
		width: 120rpx;
		height: 90rpx;
		margin-right: 20rpx;
		border-radius: 12rpx;
		background: $uh-accent-weak;
		flex-shrink: 0;
	}

	.article-main {
		flex: 1;
		min-width: 0;
	}

	.article-title {
		display: block;
		font-size: 27rpx;
		font-weight: 600;
		color: $uh-ink-1;
		line-height: 1.45;
	}

	.article-meta {
		display: flex;
		align-items: center;
		margin-top: 8rpx;
		font-size: 21rpx;
		color: $uh-ink-3;
	}

	.article-cost {
		margin-left: 16rpx;
	}

	.article-offline {
		display: block;
		margin-top: 8rpx;
		font-size: 21rpx;
		color: #d1851a;
	}

	.article-arrow {
		margin-left: 12rpx;
		font-size: 32rpx;
		color: #c8ccd8;
	}

	.footer-tip {
		padding: 0 36rpx;
		font-size: 21rpx;
		line-height: 1.7;
		color: #a1a6b8;
	}
</style>
