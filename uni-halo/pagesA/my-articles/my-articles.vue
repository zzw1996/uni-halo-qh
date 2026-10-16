<template>
	<view :class="skinClass" class="theme-page-pagesA-my-articles-my-articles my-articles-page">
		<view v-if="loading === 'loading' && !list.length" class="loading-wrap flex flex-center">
			<tm-loadding color="indigo" :size="48"></tm-loadding>
		</view>

		<view v-else-if="loading === 'error' && !list.length" class="error-wrap flex flex-center flex-col">
			<tm-empty icon="icon-article" label="加载失败"></tm-empty>
			<text class="error-tip">{{ errorTip }}</text>
			<tm-button class="mt-24" plan theme="grey" :fllowTheme="false" :shadow="0"
				@click="fnLoad">重试</tm-button>
		</view>

		<view v-else class="body">
			<view class="head-card">
				<view class="head-main">
					<text class="head-count">我的投稿 · {{ list.length }} 篇</text>
					<text class="head-sub">{{ publishedCount }} 篇已发布 · {{ pendingCount }} 篇待审</text>
				</view>
				<tm-button :shadow="2" theme="bg-gradient-indigo-accent" @click="fnToPublish">写一篇</tm-button>
			</view>

			<!-- 投稿多了以后「找一篇」比「多一个按钮」更重要：先给筛选与搜索 -->
			<view v-if="list.length" class="filter-card">
				<view class="filter-tabs">
					<view v-for="tab in filterTabs" :key="tab.key" class="filter-tab"
						:class="{ 'is-on': stateFilter === tab.key }" @click="fnSetFilter(tab.key)">
						{{ tab.label }} {{ tab.count }}
					</view>
				</view>
				<view class="search-row">
					<input class="search-input" v-model="keyword" type="text" placeholder="搜索标题"
						placeholder-class="search-placeholder" confirm-type="search" />
					<text v-if="keyword" class="search-clear" @click="keyword = ''">清空</text>
				</view>
			</view>

			<view v-if="!list.length" class="empty-wrap flex flex-center flex-col">
				<tm-empty icon="icon-article" label="还没有投过稿"></tm-empty>
				<text class="empty-tip">投过稿后，这里能看到你的全部文章与状态</text>
			</view>

			<view v-else-if="!filteredList.length" class="empty-wrap flex flex-center flex-col">
				<tm-empty icon="icon-article" label="没有符合条件的文章"></tm-empty>
				<text class="empty-tip">换个状态筛选，或清空搜索词试试</text>
			</view>

			<view v-for="item in renderedList" :key="item.name" class="article-card">
				<view class="card-body">
					<image v-if="item.cover" class="card-cover" :src="item.cover" mode="aspectFill"
						lazy-load @click="fnPreviewCover(item)"></image>
					<view class="card-main">
						<text class="card-title" @click="fnOpen(item)">{{ item.title || '（无标题）' }}</text>
						<view class="card-meta">
							<text class="state-chip" :class="item.state === 'published' ? 'is-live' : 'is-pending'">
								{{ item.stateText }}
							</text>
							<text v-if="item.hasPendingChanges" class="state-chip is-dirty">有修改待生效</text>
							<text class="meta-time">{{ item.timeText }}</text>
						</view>
						<view v-if="item.categoryLabels.length || item.tags.length" class="card-tags">
							<text v-for="label in item.categoryLabels" :key="'c-' + label"
								class="mini-tag mini-tag--cat">{{ label }}</text>
							<text v-for="tag in item.tags" :key="'t-' + tag" class="mini-tag">{{ tag }}</text>
						</view>
						<!-- 数据只在已发布时有意义（草稿还没进前台，Halo 统计恒为 0） -->
						<text v-if="item.state === 'published'" class="card-stats">
							阅读 {{ item.visitCount }} · 点赞 {{ item.upvoteCount }} · 评论 {{ item.commentCount }}
						</text>
					</view>
				</view>
				<view class="card-actions">
					<view class="act-group">
						<!-- 待审文章在站内本来就打不开：状态标签已经说明了，这里不再占一行文字 -->
						<text v-if="item.state === 'published'" class="act" @click="fnOpen(item)">查看文章</text>
						<text class="act" @click="fnToEdit(item)">编辑</text>
						<!-- 自行发布的按钮只给直发权限的角色：待审投稿能自己上线，审核就形同虚设 -->
						<text v-if="canDirectPublish && item.state === 'published' && item.hasPendingChanges"
							class="act" @click="fnConfirmPublish(item)">发布修改</text>
						<text v-else-if="canDirectPublish && item.state !== 'published'" class="act"
							@click="fnConfirmPublish(item)">立即发布</text>
						<text v-else-if="canDirectPublish" class="act" @click="fnConfirmWithdraw(item)">撤回发布</text>
					</view>
					<text class="act act--danger" :class="{ 'is-muted': fnIsBusy(item) }"
						@click="fnConfirmDelete(item)">
						{{ fnIsBusy(item) ? '处理中…' : '删除' }}
					</text>
				</view>
			</view>

			<text v-if="renderedList.length < filteredList.length" class="foot-tip">上拉查看更多投稿</text>
			<text v-else-if="list.length" class="foot-tip">删除会进 Halo 回收站，站长仍可恢复；物理删除请在后台操作</text>
		</view>
	</view>
</template>

<script>
	import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
	import tmLoadding from '@/tm-vuetify/components/tm-loadding/tm-loadding.vue';
	import { checkHasWxLogin, getOpenid } from '@/utils/auth.js';
	import { formatTimeSafe } from '@/utils/time.js';
	import {
		fetchUserPublishStatus,
		publishUserArticleNow,
		withdrawUserArticle
	} from '@/utils/userPublish.js';

	// 只保留当前账号的短时内存快照，不持久化私人投稿。
	let articleSnapshot = null;

	export default {
		components: {
			tmEmpty,
			tmButton,
			tmLoadding
		},
		data() {
			return {
				loading: 'loading',
				renderLimit: 20,
				errorTip: '',
				/** 服务端返回的我的文章（含待审） */
				list: [],
				/** 分类 metadata.name → 显示名：分类名本身是 category-xxxxx 这种，直接显示看不懂 */
				categoryLabels: {},
				/** 投稿状态：只用来判断「能否自行发布/撤回」（服务端仍是唯一的判定方） */
				status: null,
				/** 正在处理的文章 name：删除/发布/撤回共用，避免同一篇被点两次 */
				busy: '',
				/** 状态筛选：all | published | pending */
				stateFilter: 'all',
				/** 标题搜索词（本机过滤，列表本身就是自己的全部投稿） */
				keyword: '',
				/** 是否已加载过一次：返回本页时静默刷新用，避免重复显示整页 loading */
				loadedOnce: false
			};
		},
		watch: {
			keyword() { this.renderLimit = 20; },
			stateFilter() { this.renderLimit = 20; }
		},
		computed: {
			renderedList() { return this.filteredList.slice(0, this.renderLimit); },
			publishedCount() {
				return this.list.filter((item) => item.state === 'published').length;
			},
			pendingCount() {
				return this.list.filter((item) => item.state !== 'published').length;
			},
			filterTabs() {
				return [
					{ key: 'all', label: '全部', count: this.list.length },
					{ key: 'published', label: '已发布', count: this.publishedCount },
					{ key: 'pending', label: '待审', count: this.pendingCount }
				];
			},
			filteredList() {
				const keyword = this.keyword.trim().toLowerCase();
				return this.list.filter((item) => {
					if (this.stateFilter === 'published' && item.state !== 'published') {
						return false;
					}
					if (this.stateFilter === 'pending' && item.state === 'published') {
						return false;
					}
					if (!keyword) {
						return true;
					}
					return (item.title || '').toLowerCase().includes(keyword);
				});
			},
			/** 当前角色能否自行发布/撤回（直发策略或博主/管理员） */
			canDirectPublish() {
				if (!this.status) {
					return false;
				}
				return this.status.privileged === true || this.status.articleMode === 'direct';
			}
		},
		onLoad() {
			this.fnSetPageTitle('我的投稿');
			const owner = getOpenid();
			if (owner && articleSnapshot?.owner === owner && Date.now() - articleSnapshot.at < 60000) {
				this.list = articleSnapshot.list;
				this.categoryLabels = articleSnapshot.labels;
				this.loading = 'success';
				this.loadedOnce = true;
			}
			this.fnLoad();
			this.fnLoadStatus();
		},
		onShow() {
			// 从编辑页回来时列表必须变样（标题/状态/修改待生效）；首屏由 onLoad 负责
			if (this._shownOnce) {
				this.fnLoad();
			}
			this._shownOnce = true;
		},
		onReachBottom() { this.renderLimit += 20; },
		onPullDownRefresh() {
			this.fnLoad().finally(() => uni.stopPullDownRefresh());
		},
		methods: {
			fnLoad() {
				if (this._loadPromise) return this._loadPromise;
				this._loadPromise = this.fnFetchArticles().finally(() => { this._loadPromise = null; });
				return this._loadPromise;
			},
			async fnFetchArticles() {
				const owner = getOpenid();
				if (!checkHasWxLogin()) {
					this.list = [];
					this.loading = 'error';
					this.errorTip = '请先登录后再查看投稿';
					return;
				}
				if (!this.list.length) this.loading = 'loading';
				// 分类名是辅助信息，不能阻塞文章首屏；失败时仍能浏览投稿。
				this.fnLoadCategoryLabels().then(labels => {
					if (getOpenid() !== owner) return;
					this.categoryLabels = labels;
					this.list = this.list.map(item => ({ ...item,
						categoryLabels: item.categories.map(name => labels[name] || name) }));
					if (articleSnapshot?.owner === owner) {
						articleSnapshot = { ...articleSnapshot, list: this.list, labels };
					}
				});
				try {
					const articles = await this.$httpApi.v2.getMyArticles();
					if (getOpenid() !== owner) return;
					const body = articles?.data || articles || {};
					if (body.error) throw body;
					const items = Array.isArray(body.items) ? body.items : [];
					this.list = items.map(item => this.fnMapItem(item));
					this.loading = 'success';
					this.loadedOnce = true;
					articleSnapshot = { owner, at: Date.now(), list: this.list, labels: this.categoryLabels };
				} catch (err) {
					if (getOpenid() !== owner) return;
					this.errorTip = err?.error || err?.detail || err?.message || '加载失败，请稍后重试';
					this.loading = this.list.length ? 'success' : 'error';
				}
			},

			/** 取投稿状态：只为「能否自行发布/撤回」这一个结论，拿不到就不显示这两个按钮 */
			async fnLoadStatus() {
				this.status = await fetchUserPublishStatus(this.$httpApi.v2);
			},

			async fnLoadCategoryLabels() {
				try {
					const res = await this.$httpApi.v2.getCategoryList({ size: 200, page: 1 });
					const items = this.$utils.stripDataUnsafeList(res?.items || []);
					const map = {};
					items.forEach((item) => {
						const name = item?.metadata?.name;
						if (!name) {
							return;
						}
						const spec = item?.spec || {};
						map[name] = spec.displayName || spec.slug || name;
					});
					return map;
				} catch (err) {
					return {};
				}
			},

			fnMapItem(item) {
				const categories = Array.isArray(item?.categories) ? item.categories : [];
				return {
					name: item?.name || '',
					title: item?.title || '',
					state: item?.state || 'pending',
					stateText: item?.stateText || '待审 / 未发布',
					// 封面是相对路径（/upload/xxx.png）：不补域名渲染层会直接报加载失败
					cover: item?.cover ? this.$utils.checkThumbnailUrl(item.cover, true) : '',
					tags: Array.isArray(item?.tags) ? item.tags : [],
					categories,
					categoryLabels: categories.map((name) => this.categoryLabels[name] || name),
					// 已发布但改了没生效：站点上还是旧内容，列表要标出来
					hasPendingChanges: item?.hasPendingChanges === true,
					// 数据来自 Halo 列表的 stats；未发布时为 0
					visitCount: Number(item?.visitCount || 0),
					upvoteCount: Number(item?.upvoteCount || 0),
					commentCount: Number(item?.commentCount || 0),
					timeText: formatTimeSafe(item?.publishTime || item?.createTime, 'date')
				};
			},

			/**
			 * 这一篇正在处理吗。
			 *
			 * <p>必须带真值判断：{@code busy} 初始是空串，文章 name 缺失时也是空串，
			 * 直接写 {@code busy === item.name} 会让每一行都显示「处理中…」。</p>
			 */
			fnIsBusy(item) {
				return !!this.busy && this.busy === item.name;
			},

			fnSetFilter(key) {
				this.stateFilter = key;
			},

			fnToPublish() {
				uni.navigateTo({ url: '/pagesA/article-publish/article-publish' });
			},

			fnToEdit(item) {
				if (!item.name) {
					return;
				}
				uni.navigateTo({
					url: `/pagesA/article-publish/article-publish?name=${encodeURIComponent(item.name)}`
				});
			},

			fnOpen(item) {
				if (item.state !== 'published' || !item.name) {
					uni.showToast({ icon: 'none', title: '文章还在待审，通过后可在站内查看' });
					return;
				}
				uni.navigateTo({
					url: `/pagesA/article-detail/article-detail?name=${encodeURIComponent(item.name)}`
				});
			},

			fnPreviewCover(item) {
				if (item.cover) {
					uni.previewImage({ current: 0, urls: [item.cover] });
				}
			},

			fnConfirmPublish(item) {
				if (this.busy) {
					return;
				}
				const pending = item.state === 'published';
				uni.showModal({
					title: pending ? '发布修改' : '立即发布',
					content: pending
						? `把《${item.title || '无标题'}》的修改发布到站点？发布后读者看到的就是新内容。`
						: `把《${item.title || '无标题'}》直接发布到站点？发布后所有人可见。`,
					confirmText: '发布',
					confirmColor: '#2F54EB',
					success: (res) => {
						if (res.confirm) {
							this.fnDoPublish(item);
						}
					}
				});
			},

			async fnDoPublish(item) {
				if (!item.name || this.busy) {
					return;
				}
				this.busy = item.name;
				uni.showLoading({ mask: true, title: '发布中...' });
				try {
					const result = await publishUserArticleNow(this.$httpApi.v2, item.name);
					if (!result.ok) {
						throw { error: result.reason };
					}
					uni.showToast({ icon: 'success', title: result.data?.statusText || '已发布' });
					// 发布后状态、数据、按钮都会变：整页重拉比原地改字段更不容易漏
					articleSnapshot = null;
					await this.fnLoad();
				} catch (err) {
					uni.showToast({
						icon: 'none',
						title: err?.error || err?.detail || err?.message || '发布失败，请稍后重试'
					});
				} finally {
					this.busy = '';
					uni.hideLoading();
				}
			},

			fnConfirmWithdraw(item) {
				if (this.busy) {
					return;
				}
				uni.showModal({
					title: '撤回发布',
					content: `《${item.title || '无标题'}》将从站点上撤下，读者看不到（内容保留，可再次发布）。确定撤回吗？`,
					confirmText: '撤回',
					confirmColor: '#d9483b',
					success: (res) => {
						if (res.confirm) {
							this.fnDoWithdraw(item);
						}
					}
				});
			},

			async fnDoWithdraw(item) {
				if (!item.name || this.busy) {
					return;
				}
				this.busy = item.name;
				uni.showLoading({ mask: true, title: '撤回中...' });
				try {
					const result = await withdrawUserArticle(this.$httpApi.v2, item.name);
					if (!result.ok) {
						throw { error: result.reason };
					}
					uni.showToast({ icon: 'success', title: '已撤回，文章回到草稿' });
					articleSnapshot = null;
					await this.fnLoad();
				} catch (err) {
					uni.showToast({
						icon: 'none',
						title: err?.error || err?.detail || err?.message || '撤回失败，请稍后重试'
					});
				} finally {
					this.busy = '';
					uni.hideLoading();
				}
			},

			fnConfirmDelete(item) {
				if (this.busy) {
					return;
				}
				uni.showModal({
					title: '删除文章',
					content: `确定删除《${item.title || '无标题'}》吗？\n会进 Halo 回收站，站长仍可恢复。`,
					confirmText: '删除',
					confirmColor: '#d9483b',
					success: (res) => {
						if (res.confirm) {
							this.fnDoDelete(item);
						}
					}
				});
			},

			async fnDoDelete(item) {
				const name = item.name;
				if (!name || this.busy) {
					return;
				}
				this.busy = name;
				uni.showLoading({ mask: true, title: '删除中...' });
				try {
					const res = await this.$httpApi.v2.deleteUserArticle(name);
					uni.hideLoading();
					// directRequest 会把 400 业务错误也 resolve 回来（data.error 带服务端原因），
					// 不检查就会「提示已删除、其实没删」
					const serverError = res?.data?.error || res?.error;
					if (res?.statusCode >= 400 || serverError) {
						uni.showToast({ icon: 'none', title: serverError || '删除失败，请稍后重试' });
						return;
					}
					// 原地移除：不重拉列表、不滚回顶部，删完停留在当前位置
					this.list = this.list.filter((entry) => entry.name !== name);
					articleSnapshot = null;
					uni.showToast({ icon: 'success', title: '已删除' });
				} catch (err) {
					uni.hideLoading();
					uni.showToast({
						icon: 'none',
						title: err?.error || err?.detail || err?.message || '删除失败，请稍后重试'
					});
				} finally {
					this.busy = '';
					uni.hideLoading();
				}
			}
		}
	};
</script>

<style lang="scss" scoped>
	.my-articles-page {
		min-height: 100vh;
		background-color: $uh-bg;
	}

	.loading-wrap,
	.error-wrap {
		padding-top: 30vh;
	}

	.error-tip,
	.empty-tip {
		margin-top: 16rpx;
		font-size: 26rpx;
		color: $uh-ink-3;
		text-align: center;
	}

	.body {
		padding: 24rpx;
		padding-bottom: calc(40rpx + env(safe-area-inset-bottom));
	}

	.head-card {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 22rpx 24rpx;
		background-color: $uh-card;
		border-radius: $uh-radius-lg;
		box-shadow: $uh-shadow-sm;
	}

	.head-main {
		flex: 1;
		overflow: hidden;
	}

	.head-count {
		display: block;
		font-size: 29rpx;
		font-weight: 600;
		color: $uh-ink-1;
	}

	.head-sub {
		display: block;
		margin-top: 6rpx;
		font-size: 23rpx;
		color: $uh-ink-3;
	}

	.filter-card {
		margin-top: 20rpx;
		padding: 18rpx 20rpx;
		background-color: $uh-card;
		border-radius: $uh-radius-lg;
		box-shadow: $uh-shadow-sm;
	}

	.filter-tabs {
		display: flex;
		align-items: center;
	}

	.filter-tab {
		margin-right: 16rpx;
		padding: 8rpx 24rpx;
		border-radius: $uh-radius-pill;
		background-color: $uh-fill;
		font-size: 24rpx;
		color: $uh-ink-2;

		&.is-on {
			background-color: $uh-accent-weak;
			color: $uh-accent;
			font-weight: 600;
		}
	}

	.search-row {
		display: flex;
		align-items: center;
		margin-top: 16rpx;
		padding: 10rpx 20rpx;
		border-radius: $uh-radius-md;
		background-color: $uh-fill;
	}

	.search-input {
		flex: 1;
		height: 48rpx;
		font-size: 25rpx;
		color: $uh-ink-1;
	}

	.search-placeholder {
		color: $uh-ink-4;
		font-size: 25rpx;
	}

	.search-clear {
		margin-left: 16rpx;
		font-size: 24rpx;
		color: $uh-accent;
	}

	.empty-wrap {
		padding: 14vh 0 0;
	}

	.article-card {
		margin-top: 20rpx;
		padding: 22rpx 24rpx;
		background-color: $uh-card;
		border-radius: $uh-radius-lg;
		box-shadow: $uh-shadow-sm;
	}

	.card-body {
		display: flex;
	}

	.card-cover {
		flex: none;
		width: 180rpx;
		height: 130rpx;
		margin-right: 20rpx;
		border-radius: $uh-radius-md;
		background-color: $uh-fill;
	}

	.card-main {
		flex: 1;
		overflow: hidden;
	}

	.card-title {
		display: block;
		font-size: 29rpx;
		font-weight: 600;
		color: $uh-ink-1;
		line-height: 1.45;
	}

	.card-meta {
		display: flex;
		align-items: center;
		flex-wrap: wrap;
		margin-top: 12rpx;
	}

	.state-chip {
		margin: 0 10rpx 6rpx 0;
		padding: 4rpx 16rpx;
		border-radius: $uh-radius-pill;
		font-size: 21rpx;

		&.is-live {
			background-color: $uh-success-weak;
			color: $uh-success;
		}

		&.is-pending {
			background-color: $uh-warning-weak;
			color: $uh-warning;
		}

		&.is-dirty {
			background-color: $uh-accent-weak;
			color: $uh-accent;
		}
	}

	.meta-time {
		margin-bottom: 6rpx;
		font-size: 22rpx;
		color: $uh-ink-4;
	}

	.card-tags {
		display: flex;
		flex-wrap: wrap;
		margin-top: 10rpx;
	}

	.mini-tag {
		margin: 0 10rpx 6rpx 0;
		padding: 4rpx 14rpx;
		border-radius: $uh-radius-pill;
		background-color: $uh-fill;
		font-size: 20rpx;
		color: $uh-ink-2;
	}

	.mini-tag--cat {
		background-color: $uh-accent-weak;
		color: $uh-accent;
	}

	.card-stats {
		display: block;
		margin-top: 8rpx;
		font-size: 21rpx;
		color: $uh-ink-4;
	}

	.card-actions {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-top: 18rpx;
		padding-top: 16rpx;
		border-top: 1rpx solid $uh-line;
	}

	.act-group {
		display: flex;
		align-items: center;
		flex: 1;
		overflow: hidden;
	}

	.act {
		margin-right: 20rpx;
		font-size: 25rpx;
		color: $uh-accent;
		white-space: nowrap;

		&.is-muted {
			color: $uh-ink-4;
		}

		&--danger {
			margin-right: 0;
			color: $uh-danger;
		}
	}

	.foot-tip {
		display: block;
		margin-top: 20rpx;
		font-size: 22rpx;
		color: $uh-ink-4;
		text-align: center;
	}
@import '@/common/styles/redesign/pagesA-my-articles-my-articles-themes.scss';
</style>
