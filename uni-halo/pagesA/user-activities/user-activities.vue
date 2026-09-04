<template>
	<view class="app-page">
		<view v-if="loading !== 'success'" class="loading-wrap">
			<tm-skeleton model="listAvatr"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
		</view>
		<block v-else>
			<view v-if="dataList.length === 0" class="empty">
				<tm-empty icon="icon-shiliangzhinengduixiang-" :label="emptyLabel"></tm-empty>
			</view>
			<block v-else>
				<block v-for="(article, index) in dataList" :key="article.metadata.name + index">
					<tm-translate animation-name="fadeUp" :wait="calcAniWait(index)">
						<article-card :article="article" @on-click="fnToArticleDetail"></article-card>
					</tm-translate>
				</block>
				<view class="load-text">{{ loadMoreText }}</view>
			</block>
			<tm-flotbutton @click="fnToTopPage" color="light-blue" size="m" icon="icon-angle-up"></tm-flotbutton>
		</block>
	</view>
</template>

<script>
	import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
	import tmTranslate from '@/tm-vuetify/components/tm-translate/tm-translate.vue';
	import tmFlotbutton from '@/tm-vuetify/components/tm-flotbutton/tm-flotbutton.vue';
	import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
	import ArticleCard from '@/components/article-card/article-card.vue';
	import { checkHasWxLogin } from '@/utils/auth.js';
	import { mapActivityItemToArticle, enrichActivityArticlesStats, USER_ACTIVITY_TITLES } from '@/utils/userActivity.js';

	export default {
		components: {
			tmSkeleton,
			tmTranslate,
			tmFlotbutton,
			tmEmpty,
			ArticleCard
		},
		data() {
			return {
				activityType: '',
				loading: 'loading',
				queryParams: {
					size: 10,
					page: 0
				},
				hasNext: false,
				dataList: [],
				isLoadMore: false,
				loadMoreText: ''
			};
		},
		computed: {
			pageTitle() {
				return USER_ACTIVITY_TITLES[this.activityType] || '我的记录';
			},
			emptyLabel() {
				const map = {
					comment: '还没有评论过文章',
					browse: '还没有浏览记录',
					upvote: '还没有点赞过文章'
				};
				return map[this.activityType] || '暂无数据';
			}
		},
		onLoad(options) {
			this.activityType = options.type || 'browse';
			if (!checkHasWxLogin()) {
				uni.showToast({ title: '请先登录', icon: 'none' });
				setTimeout(() => uni.navigateBack(), 800);
				return;
			}
			this.fnSetPageTitle(this.pageTitle);
			this.fnGetData();
		},
		onPullDownRefresh() {
			this.isLoadMore = false;
			this.queryParams.page = 0;
			this.fnGetData();
		},
		onReachBottom() {
			if (this.hasNext) {
				this.queryParams.page += 1;
				this.isLoadMore = true;
				this.fnGetData();
			} else {
				uni.showToast({ icon: 'none', title: '没有更多数据了' });
			}
		},
		methods: {
			calcAniWait(index) {
				return (index % 10) * 50;
			},
			async fnGetData() {
				if (!this.isLoadMore) {
					this.loading = 'loading';
				}
				this.loadMoreText = '加载中...';
				try {
					const res = await this.$httpApi.v2.getUserActivityList({
						type: this.activityType,
						page: this.queryParams.page,
						size: this.queryParams.size
					});
					if (res?.statusCode && res.statusCode !== 200) {
						throw { error: res?.data?.error || res?.data?.detail || '加载失败' };
					}
					const body = res?.data || res || {};
					let items = (body.items || []).map(mapActivityItemToArticle).filter(Boolean);
					items = await enrichActivityArticlesStats(this.$httpApi.v2, items);
					this.hasNext = body.hasNext === true;
					if (this.isLoadMore) {
						this.dataList = this.dataList.concat(items);
					} else {
						this.dataList = items;
					}
					const total = body.total != null ? body.total : this.dataList.length;
					this.fnSetPageTitle(`${this.pageTitle}（${total}）`);
					this.loadMoreText = this.hasNext ? '上拉加载更多' : '没有更多了';
					this.loading = 'success';
				} catch (err) {
					console.error(err);
					this.loading = 'success';
					if (!this.isLoadMore) {
						this.dataList = [];
					}
					this.loadMoreText = err?.error || '加载失败，请下拉刷新';
					uni.showToast({ title: err?.error || '加载失败', icon: 'none' });
				} finally {
					uni.stopPullDownRefresh();
				}
			},
			fnToArticleDetail(article) {
				const name = (article?.metadata?.name || '').trim();
				if (!name) {
					uni.showToast({ title: '文章信息无效', icon: 'none' });
					return;
				}
				uni.navigateTo({
					url: '/pagesA/article-detail/article-detail?name=' + encodeURIComponent(name),
					animationType: 'slide-in-right'
				});
			},
			fnToTopPage() {
				uni.pageScrollTo({ scrollTop: 0, duration: 300 });
			}
		}
	};
</script>

<style lang="scss">
.app-page {
	width: 100vw;
	min-height: 100vh;
	background: #f5f6fa;
}

.loading-wrap {
	padding: 24rpx;
}

.empty {
	padding-top: 120rpx;
}

.load-text {
	text-align: center;
	font-size: 24rpx;
	color: #909399;
	padding: 24rpx 0 48rpx;
}
</style>
