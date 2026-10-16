<template>
	<view :class="skinClass" class="theme-page-pagesA-invite-landing-invite-landing app-page">
		<!-- 分享人卡片：一眼看出「这是谁给我挑的」，比一张干巴巴的链接有说服力 -->
		<view class="sharer-card">
			<image v-if="landing.sharerAvatar" class="sharer-avatar" :src="landing.sharerAvatar"
				mode="aspectFill"></image>
			<view v-else class="sharer-avatar sharer-avatar--fallback">
				<text class="sharer-initial">{{ sharerInitial }}</text>
			</view>
			<view class="sharer-main">
				<text class="sharer-title">{{ sharerTitle }}</text>
				<text class="sharer-desc">{{ landing.message || '这些是他正在关注的分类，点开就能看' }}</text>
			</view>
		</view>

		<!-- 分类 chips：已关注的标灰，避免用户以为要重复关注 -->
		<view v-if="landing.categories && landing.categories.length" class="chip-wrap">
			<text v-for="item in landing.categories" :key="item.name" class="chip"
				:class="{ 'is-on': fnFollowed(item.name) }">
				{{ item.label }}{{ fnFollowed(item.name) ? ' · 已关注' : '' }}
			</text>
		</view>

		<!-- 行动区：按访问者状态给不同的一句话与按钮 -->
		<view class="cta-card">
			<text class="cta-text">{{ ctaText }}</text>
			<button v-if="!landing.loggedIn" class="cta-btn" @click="fnLogin">微信登录后自动关注</button>
			<button v-else-if="!landing.hasOwnSubscription" class="cta-btn" :disabled="busy"
				@click="fnAccept">{{ busy ? '处理中…' : '关注这些分类' }}</button>
			<button v-else class="cta-btn" :disabled="busy" @click="fnAccept">
				{{ busy ? '处理中…' : `也关注这些（已关注 ${followedCount} 个）` }}
			</button>
			<text class="cta-tip">{{ ctaTip }}</text>
		</view>

		<!-- 文章列表：按分享人订阅的分类取站内文章，口径与首页一致 -->
		<view class="list-head">
			<text class="list-title">{{ landing.categories && landing.categories.length ? '他关注分类下的内容' : '全站最新' }}</text>
			<text class="list-sub">{{ listSubText }}</text>
		</view>

		<view v-if="loading" class="loading-wrap">
			<tm-skeleton model="listAvatr"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
		</view>
		<view v-else-if="!articles.length" class="empty-wrap">
			<tm-empty icon="icon-shiliangzhinengduixiang-" label="暂时没有可看的内容~"></tm-empty>
		</view>
		<block v-else>
			<view :class="globalAppSettings.layout.home">
				<tm-translate v-for="(article, index) in articles" :key="article.metadata.name"
					class="ani-item" animation-name="fadeUp" :wait="calcAniWait(index)">
					<article-card from="home" :article="article" :post="article"
						@on-click="fnToArticleDetail"></article-card>
				</tm-translate>
			</view>
			<view class="load-text">{{ loadMoreText }}</view>
		</block>

		<wx-login-dialog
			:show.sync="loginDialogShow"
			title="登录后自动关注"
			desc="登录后这位朋友关注的分类会自动成为你首页的起点，随时可以取消"
			@success="fnOnLoginSuccess"
		></wx-login-dialog>
	</view>
</template>

<script>
	import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
	import loadMoreMixin from '@/common/mixins/loadMore.js';
	import tmTranslate from '@/tm-vuetify/components/tm-translate/tm-translate.vue';
	import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
	import wxLoginDialog from '@/components/wx-login-dialog/wx-login-dialog.vue';
	import ArticleCard from '@/components/article-card/article-card.vue';
	import { checkHasWxLogin } from '@/utils/auth.js';
	import { tryAutoWxLogin } from '@/utils/wxLogin.js';

	/**
	 * 邀请分享落地页：被分享者打开分享卡片后看到的第一屏。
	 *
	 * <p><b>为什么需要这一页</b>：分享卡片能带的信息很少（path 里塞不下分类清单），
	 * 而"谁给你挑了什么"恰好是决定他会不会留下的关键。所以卡片只带邀请码，
	 * 落地后用这个码换出分享人的昵称与订阅分类，再按这些分类取站内文章展示。</p>
	 *
	 * <p><b>首页不受影响</b>：这一页只展示与（可选地）关注，
	 * 不会因为打开别人的分享就改写自己原来的订阅——那是登录结算时由服务端判定的事。</p>
	 */
	export default {
		mixins: [loadMoreMixin],
		components: {
			tmSkeleton,
			tmTranslate,
			tmEmpty,
			wxLoginDialog,
			ArticleCard
		},
		data() {
			return {
				inviteCode: '',
				landing: {
					valid: true,
					message: '',
					sharerName: '',
					sharerAvatar: '',
					categories: [],
					loggedIn: false,
					hasOwnSubscription: false,
					followedCategories: []
				},
				articles: [],
				loading: true,
				busy: false,
				loginDialogShow: false,
				loadMoreText: '',
				maxArticles: 12
			};
		},
		computed: {
			sharerInitial() {
				const name = String(this.landing.sharerName || '').trim();
				return name ? name.charAt(0) : '友';
			},
			sharerTitle() {
				const name = String(this.landing.sharerName || '').trim();
				const count = (this.landing.categories || []).length;
				if (!name) {
					return count ? `为你精选了 ${count} 个分类` : '为你精选了内容';
				}
				return count ? `${name} 为你精选了 ${count} 个分类` : `${name} 为你精选了内容`;
			},
			followedCount() {
				const followed = this.landing.followedCategories || [];
				return (this.landing.categories || []).filter((item) => followed.includes(item.name)).length;
			},
			ctaText() {
				if (!this.landing.loggedIn) {
					return '登录后这些分类会成为你首页的起点';
				}
				if (!this.landing.hasOwnSubscription) {
					return '关注后首页就只看这些分类的内容';
				}
				return `你有自己的订阅，也可以把它们一起关注（最多 8 个）`;
			},
			ctaTip() {
				if (!this.landing.loggedIn) {
					return '不会改变你已有的订阅；随时可以在「分类」页取消';
				}
				return '自己订阅的分类优先保留，推荐项只占剩余名额';
			},
			listSubText() {
				const count = this.articles.length;
				return count ? `共 ${count} 篇` : '';
			}
		},
		onLoad(options) {
			this.inviteCode = this.fnResolveInviteCode(options);
			this.fnApplyPendingInvite();
			this.fnLoadLanding();
		},
		onPullDownRefresh() {
			this.fnLoadLanding();
		},
		onReachBottom() {
			// 落地页只做"看一眼就走"的第一屏，不做无限翻页：够了就引导去关注或回首页。
			// 走 mixin 只为「只提示一次」——否则在底部滑动会反复弹这句。
			this.fnLoadMore(false, null, '看完了，关注这些分类去首页看更多吧');
		},
		methods: {
			/** 从分享参数里取出邀请码：卡片 path 带 query，海报小程序码带 scene */
			fnResolveInviteCode(options) {
				const query = options || {};
				let code = '';
				if (query.code) {
					code = query.code;
				} else if (query.inviteCode) {
					code = query.inviteCode;
				} else if (query.scene) {
					const scene = String(query.scene);
					if (scene.startsWith('inv:')) {
						code = scene.substring(4);
					}
				}
				try {
					return decodeURIComponent(String(code || '').trim());
				} catch (e) {
					return String(code || '').trim();
				}
			},
			/**
			 * 把邀请码写进待消费存储并静默登录一次。
			 *
			 * <p>这是裂变能不能成立的关键一步：邀请关系与奖励是在「新用户首次登录」时结算的，
			 * 所以落地页一打开就要把码存好并尝试自动登录，不能等用户自己去找登录入口。</p>
			 */
			fnApplyPendingInvite() {
				if (!this.inviteCode) {
					return;
				}
				try {
					uni.setStorageSync('invite.pending', this.inviteCode);
				} catch (e) {
					// 忽略存储异常
				}
				if (checkHasWxLogin()) {
					this.landing.loggedIn = true;
					return;
				}
				tryAutoWxLogin(this.$httpApi).then(() => {
					this.landing.loggedIn = checkHasWxLogin();
				}).catch(() => {
					// 静默失败：未登录也能看落地页，点按钮再引导登录
				});
			},
			async fnLoadLanding() {
				this.loading = true;
				try {
					const res = await this.$httpApi.v2.getInviteLanding(this.inviteCode);
					const body = res?.data || res || {};
					this.landing = {
						valid: body.valid !== false,
						message: body.message || '',
						sharerName: body.sharerName || '',
						sharerAvatar: body.sharerAvatar || '',
						categories: Array.isArray(body.categories) ? body.categories : [],
						loggedIn: !!body.loggedIn,
						hasOwnSubscription: !!body.hasOwnSubscription,
						followedCategories: Array.isArray(body.followedCategories)
							? body.followedCategories : []
					};
					await this.fnLoadArticles();
				} catch (err) {
					this.landing.valid = false;
					this.landing.message = err?.error || '分享内容加载失败，请让好友重新分享一次';
					this.articles = [];
				} finally {
					this.loading = false;
					uni.stopPullDownRefresh();
				}
			},
			/**
			 * 取文章：按分享人订阅的分类并发取，合并去重后按置顶 + 时间倒序。
			 *
			 * <p>用的就是首页那套分类文章接口，所以点进去是正常文章页，
			 * 阅读量、积分、付费判定全都不受影响；分享人没有订阅分类时回落站内最新。</p>
			 */
			async fnLoadArticles() {
				const names = (this.landing.categories || []).map((item) => item.name);
				if (!names.length) {
					const res = await this.$httpApi.v2.getPostList({ page: 1, size: this.maxArticles });
					this.articles = this.$utils.stripDataUnsafeList((res && res.items) || []);
					this.loadMoreText = '';
					return;
				}
				const results = await Promise.all(names.map((name) => this.$httpApi.v2
					.getCategoryPostList(name, { page: 0, size: 4 })
					.then((res) => (res && res.items) || [])
					.catch(() => [])));
				const flat = [];
				results.forEach((items) => items.forEach((item) => flat.push(item)));
				this.articles = this.fnMergeArticles(flat).slice(0, this.maxArticles);
				this.loadMoreText = '';
			},
			/** 合并去重：按文章名去重，置顶优先、其次发布时间倒序（与首页同一口径） */
			fnMergeArticles(items) {
				const seen = {};
				const merged = [];
				(this.$utils.stripDataUnsafeList(items) || []).forEach((item) => {
					const name = item && item.metadata && item.metadata.name;
					if (!name || seen[name]) {
						return;
					}
					seen[name] = true;
					merged.push(item);
				});
				merged.sort((a, b) => {
					const pinnedA = a && a.spec && a.spec.pinned === true ? 1 : 0;
					const pinnedB = b && b.spec && b.spec.pinned === true ? 1 : 0;
					if (pinnedA !== pinnedB) {
						return pinnedB - pinnedA;
					}
					const timeA = (a && a.spec && a.spec.publishTime) || '';
					const timeB = (b && b.spec && b.spec.publishTime) || '';
					if (timeA === timeB) {
						return 0;
					}
					return timeA < timeB ? 1 : -1;
				});
				return merged;
			},
			fnFollowed(name) {
				return (this.landing.followedCategories || []).includes(name);
			},
			fnLogin() {
				this.loginDialogShow = true;
			},
			async fnOnLoginSuccess() {
				this.loginDialogShow = false;
				this.landing.loggedIn = true;
				// 登录时服务端已完成邀请结算（绑定 + 双方奖励），新人可能已经继承了这些分类
				await this.fnLoadLanding();
			},
			/** 「也关注这些」：并入自己的订阅，然后回首页看效果 */
			async fnAccept() {
				if (this.busy) {
					return;
				}
				if (!checkHasWxLogin()) {
					this.loginDialogShow = true;
					return;
				}
				this.busy = true;
				try {
					const res = await this.$httpApi.v2.acceptInviteRecommendation(this.inviteCode);
					const body = res?.data || res || {};
					if (body.error) {
						uni.showToast({ title: body.error, icon: 'none' });
						return;
					}
					// 与分类页同一套刷新机制：写版本号 + 发事件，首页切回去立刻生效
					uni.setStorageSync('category.subscription.rev', String(Date.now()));
					uni.$emit('category_subscription_changed');
					this.landing.followedCategories = Array.isArray(body.effectiveCategories)
						? body.effectiveCategories : this.landing.followedCategories;
					uni.showToast({ title: '已加入你的订阅', icon: 'none' });
					setTimeout(() => {
						uni.switchTab({ url: '/pages/tabbar/home/home' });
					}, 800);
				} catch (err) {
					uni.showToast({ title: err?.error || '操作失败，请稍后重试', icon: 'none' });
				} finally {
					this.busy = false;
				}
			},
			fnToArticleDetail(article) {
				const name = article && article.metadata && article.metadata.name;
				if (!name) {
					return;
				}
				uni.navigateTo({
					url: '/pagesA/article-detail/article-detail?name=' + name,
					animationType: 'slide-in-right'
				});
			}
		}
	};
</script>

<style lang="scss" scoped>
	.app-page {
		width: 100vw;
		min-height: 100vh;
		display: flex;
		flex-direction: column;
	}

	.sharer-card {
		display: flex;
		align-items: center;
		margin: 24rpx;
		padding: 28rpx;
		border-radius: 24rpx;
		background-image: $uh-grad-accent;
		box-shadow: 0 10rpx 26rpx rgba(47, 84, 235, 0.28);
	}

	.sharer-avatar {
		width: 96rpx;
		height: 96rpx;
		border-radius: 50%;
		border: 4rpx solid rgba(255, 255, 255, 0.7);
		flex-shrink: 0;

		&--fallback {
			display: flex;
			align-items: center;
			justify-content: center;
			background: rgba(255, 255, 255, 0.25);
		}
	}

	.sharer-initial {
		font-size: 40rpx;
		font-weight: 700;
		color: #fff;
	}

	.sharer-main {
		margin-left: 24rpx;
		display: flex;
		flex-direction: column;
		flex: 1;
		min-width: 0;
	}

	.sharer-title {
		font-size: 34rpx;
		font-weight: 700;
		color: #fff;
	}

	.sharer-desc {
		margin-top: 8rpx;
		font-size: 22rpx;
		line-height: 1.6;
		color: rgba(255, 255, 255, 0.85);
	}

	.chip-wrap {
		display: flex;
		flex-wrap: wrap;
		padding: 0 24rpx;
	}

	.chip {
		margin: 0 16rpx 16rpx 0;
		padding: 10rpx 24rpx;
		border-radius: 999rpx;
		background: $uh-card;
		border: 1rpx solid #e6e9f3;
		font-size: 22rpx;
		color: $uh-ink-2;

		&.is-on {
			background: #f2f4fa;
			color: $uh-ink-3;
		}
	}

	.cta-card {
		margin: 8rpx 24rpx 24rpx;
		padding: 24rpx;
		border-radius: 20rpx;
		background: $uh-card;
		box-shadow: 0 6rpx 20rpx rgba(31, 41, 55, 0.06);
		display: flex;
		flex-direction: column;
	}

	.cta-text {
		font-size: 25rpx;
		color: $uh-ink-1;
		line-height: 1.6;
	}

	.cta-btn {
		margin: 20rpx 0 0;
		height: 76rpx;
		line-height: 76rpx;
		border-radius: 999rpx;
		background-image: $uh-grad-accent;
		color: #fff;
		font-size: 27rpx;

		&::after {
			border: none;
		}

		&[disabled] {
			opacity: 0.6;
		}
	}

	.cta-tip {
		margin-top: 14rpx;
		font-size: 21rpx;
		color: $uh-ink-3;
	}

	.list-head {
		display: flex;
		align-items: baseline;
		padding: 0 28rpx 12rpx;
	}

	.list-title {
		font-size: 30rpx;
		font-weight: 700;
		color: $uh-ink-1;
	}

	.list-sub {
		margin-left: auto;
		font-size: 22rpx;
		color: $uh-ink-3;
	}

	.loading-wrap {
		padding: 0 24rpx;
	}

	.empty-wrap {
		padding: 80rpx 0;
	}

	.load-text {
		width: 100%;
		text-align: center;
		padding: 24rpx 0 40rpx;
		font-size: 22rpx;
		color: $uh-ink-3;
	}
@import '@/common/styles/redesign/pagesA-invite-landing-invite-landing-themes.scss';
</style>
