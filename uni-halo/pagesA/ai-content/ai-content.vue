<template>
	<view :class="skinClass" class="app-page">
		<view v-if="loading !== 'success'" class="loading-wrap">
			<tm-skeleton model="listAvatr"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
		</view>

		<block v-else>
			<!-- 权益状态：这一页的第一件事是「我有没有这个权益、还剩多久」 -->
			<view class="status-card">
				<view class="status-head">
					<text class="status-title">AI 定制内容</text>
					<text class="status-badge">{{ statusBadge }}</text>
				</view>
				<text class="status-desc">{{ statusDesc }}</text>
				<view v-if="status && status.active" class="status-meta">
					<text class="status-meta-item">{{ status.permanent ? '永久有效' : '到期时间 ' + status.expireAt }}</text>
					<text v-if="!status.permanent" class="status-meta-item">剩余 {{ status.daysRemaining }} 天</text>
				</view>
			</view>

			<!-- 未开通 / 已过期：权益说明 + 购买（买完立刻回到配置区） -->
			<view v-if="!status || !status.active" class="section-card">
				<view class="section-head">
					<text class="section-title">{{ status && status.expired ? '续费后继续使用' : '开通后你可以' }}</text>
					<text class="section-sub">{{ status && status.expired ? '已配置的关注方向会保留，续费即恢复' : '一次购买，有效期内不限次查看' }}</text>
				</view>
				<view v-for="(line, i) in benefitLines" :key="i" class="benefit-line">
					<text class="benefit-dot">·</text>
					<text class="benefit-text">{{ line }}</text>
				</view>

				<view v-if="goodsList.length" class="goods-list">
					<view
						v-for="(goods, i) in goodsList"
						:key="goods.goodsId + i"
						class="goods-item"
						:hover-class="'goods-item-hover'"
						@click="fnBuy(goods, i)"
					>
						<view class="goods-main">
							<text class="goods-name">{{ goods.name }}</text>
							<text class="goods-tip">{{ fnGoodsPeriod(goods) }}，{{ limits.maxCategories }} 个主题 / {{ limits.maxSources }} 个来源 / {{ limits.maxKeywords }} 个关键词</text>
						</view>
						<text class="goods-price">￥{{ fnPrice(goods.price) }}</text>
					</view>
				</view>
				<view v-else class="empty-tip">
					站长还没有配置可购买的商品。请先在 Halo 后台「虚拟支付 → 权益道具」里新增一条类型为「AI 定制内容」的商品。
				</view>
			</view>

			<!-- 已开通：关注方向配置 -->
			<block v-if="status && status.active">
				<view class="section-card">
					<view class="section-head">
						<text class="section-title">关注的主题</text>
						<text class="section-sub">最多 {{ limits.maxCategories }} 个（{{ picked.categories.length }}/{{ limits.maxCategories }}）</text>
					</view>
					<view v-if="categoryOptions.length === 0" class="empty-tip">站长还没有配置日报分类清单</view>
					<view v-else class="chip-wrap">
						<view
							v-for="option in categoryOptions"
							:key="option.key"
							:class="['chip', picked.categories.indexOf(option.key) >= 0 ? 'chip-on' : '']"
							@click="fnToggleCategory(option.key)"
						>
							<text class="chip-text">{{ option.emoji }} {{ option.label }}</text>
						</view>
					</view>
				</view>

				<view class="section-card">
					<view class="section-head">
						<text class="section-title">关注的来源</text>
						<text class="section-sub">最近 {{ limits.windowDays }} 天出现过的来源（{{ picked.sources.length }}/{{ limits.maxSources }}）</text>
					</view>
					<view v-if="sourceOptions.length === 0" class="empty-tip">最近还没有可选的来源</view>
					<view v-else class="chip-wrap">
						<view
							v-for="option in sourceOptions"
							:key="option.value"
							:class="['chip', picked.sources.indexOf(option.value) >= 0 ? 'chip-on' : '']"
							@click="fnToggleSource(option.value)"
						>
							<text class="chip-text">{{ option.value }}（{{ option.count }}）</text>
						</view>
					</view>
				</view>

				<view v-if="limits.maxKeywords > 0" class="section-card">
					<view class="section-head">
						<text class="section-title">关注的关键词</text>
						<text class="section-sub">命中标题或关键词即算相关（{{ picked.keywords.length }}/{{ limits.maxKeywords }}）</text>
					</view>
					<view class="keyword-row">
						<input
							v-model="keywordDraft"
							class="keyword-input"
							type="text"
							:maxlength="20"
							placeholder="如 Rust、Kubernetes"
							placeholder-class="keyword-placeholder"
							confirm-type="done"
							@confirm="fnAddKeyword"
						/>
						<view class="keyword-add" hover-class="keyword-add-hover" @click="fnAddKeyword">
							<text class="keyword-add-text">添加</text>
						</view>
					</view>
					<view v-if="picked.keywords.length" class="chip-wrap">
						<view
							v-for="(word, i) in picked.keywords"
							:key="word + i"
							class="chip chip-on"
							@click="fnRemoveKeyword(i)"
						>
							<text class="chip-text">{{ word }} ×</text>
						</view>
					</view>
				</view>

				<view class="save-bar">
					<view
						:class="['save-btn', saving ? 'save-btn-busy' : '']"
						hover-class="save-btn-hover"
						@click="fnSave"
					>
						<text class="save-btn-text">{{ saving ? '保存中…' : '保存关注方向' }}</text>
					</view>
				</view>

				<!-- 我的简报 -->
				<view class="section-card">
					<view class="section-head">
						<text class="section-title">{{ feedTitle }}</text>
						<text class="section-sub">{{ feedLoading ? '正在为你生成今天的简报…' : (feed && feed.summary ? feed.summary : '') }}</text>
					</view>

					<view v-if="feedLoading" class="feed-loading">
						<text class="feed-loading-text">首次打开会调用一次 AI 生成导语，通常几秒到十几秒</text>
					</view>

					<block v-else>
						<view v-if="feed && feed.intro" class="feed-intro">
							<text class="feed-intro-text">{{ feed.intro }}</text>
						</view>

						<view v-if="!feedItems.length" class="empty-tip">
							{{ feed && feed.summary ? feed.summary : '暂时没有可展示的内容' }}
						</view>

						<view v-for="(item, i) in feedItems" :key="(item.postName || item.title) + i" class="feed-item">
							<view class="feed-item-main" hover-class="feed-item-hover" @click="fnOpenItem(item)">
								<view class="feed-item-head">
									<text class="feed-item-title">{{ item.title }}</text>
									<text v-if="item.postName" class="feed-item-arrow">›</text>
								</view>
								<view class="feed-item-meta">
									<text v-if="item.categoryLabel" class="feed-item-tag">{{ item.categoryEmoji }} {{ item.categoryLabel }}</text>
									<text v-if="item.sourceName" class="feed-item-source">{{ item.sourceName }}</text>
								</view>
								<text v-if="item.reason" class="feed-item-reason">{{ item.reason }}</text>
								<text v-else-if="item.summary" class="feed-item-summary">{{ item.summary }}</text>
								<text v-if="item.matchedReason" class="feed-item-match">{{ item.matchedReason }}</text>
							</view>
						</view>

						<!-- 空态给出一键放宽：偏好太窄是「没有内容」最常见的原因 -->
						<view v-if="!feedItems.length && categoryOptions.length" class="chip-wrap">
							<view
								v-for="option in categoryOptions.slice(0, 6)"
								:key="'sug-' + option.key"
								class="chip chip-ghost"
								@click="fnQuickPick(option.key)"
							>
								<text class="chip-text">关注 {{ option.label }}</text>
							</view>
						</view>
					</block>
				</view>

				<!-- 往期 -->
				<view v-if="issues.length > 1" class="section-card">
					<view class="section-head">
						<text class="section-title">往期简报</text>
						<text class="section-sub">点开可回看那一天的内容</text>
					</view>
					<view
						v-for="issue in issues"
						:key="issue.date"
						class="issue-item"
						:hover-class="'issue-item-hover'"
						@click="fnOpenIssue(issue.date)"
					>
						<text class="issue-date">{{ issue.date }}</text>
						<text class="issue-intro">{{ issue.intro || (issue.count + ' 条内容') }}</text>
						<text class="issue-count">{{ issue.count }} 条 ›</text>
					</view>
				</view>
			</block>

			<view class="footer-tip">
				<text>简报内容来自站长已发布的 AI 日报，按你的关注方向筛选；每天最多生成一次，反复打开不会重复消耗 AI 额度。到期后关注方向会保留，续费即恢复。</text>
			</view>
		</block>
	</view>
</template>

<script>
	import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
	import { checkHasWxLogin } from '@/utils/auth.js';
	import { getAppConfigs } from '@/config/index.js';
	import { payVirtualGoods } from '@/utils/virtualPay.js';
	import { requestSubscribe } from '@/utils/subscribeMessage.js';

	export default {
		components: {
			tmSkeleton
		},
		data() {
			return {
				loading: 'loading',
				/** 权益状态（服务端 /ai/content/preference 下发） */
				status: null,
				limits: {
					maxCategories: 5,
					maxSources: 5,
					maxKeywords: 10,
					maxItems: 10,
					windowDays: 7,
					aiIntroEnabled: true
				},
				benefitNote: '',
				categoryOptions: [],
				sourceOptions: [],
				/** 正在编辑的关注方向（保存前的本地状态） */
				picked: { categories: [], sources: [], keywords: [] },
				keywordDraft: '',
				saving: false,
				feed: null,
				feedItems: [],
				feedLoading: false,
				activeDate: '',
				issues: [],
				payingIndex: -1
			};
		},
		computed: {
			statusBadge() {
				if (!this.status || !this.status.enabled) return '未开放';
				if (!this.status.active) {
					return this.status.expired ? '已过期' : '未开通';
				}
				return this.status.permanent ? '永久' : '生效中';
			},
			statusDesc() {
				if (!this.status || !this.status.enabled) {
					return '站长暂未开放这个功能';
				}
				if (!this.status.active) {
					return this.status.expired
						? '权益已到期，关注方向仍为你保留'
						: '按你自己的方向，每天一份专属精选简报';
				}
				return this.status.permanent
					? '永久有效，随时调整你的关注方向'
					: '生效中，随时调整你的关注方向';
			},
			/** 权益说明：站长配了就按他写的展示，没配用通用文案 */
			benefitLines() {
				const note = (this.benefitNote || '').trim();
				if (note) {
					return note.split('\n').map((line) => line.trim()).filter(Boolean);
				}
				return [
					`从站长已精选的 AI 日报内容里，按你的关注方向筛出每天一份简报`,
					`最多关注 ${this.limits.maxCategories} 个主题、${this.limits.maxSources} 个来源、${this.limits.maxKeywords} 个关键词`,
					this.limits.aiIntroEnabled
						? '每天用 AI 写一段导语与每篇的推荐理由'
						: '按你的方向排序（站长已关闭 AI 导语生成）',
					'有效期内不限次查看，关注方向随时可改'
				];
			},
			/** 可购买的「AI 定制内容」商品（现金直购） */
			goodsList() {
				const goods = getAppConfigs()?.payConfig?.goods;
				return (Array.isArray(goods) ? goods : []).filter(
					(item) => item && item.goodsId && item.type === 'ai_content'
				);
			},
			feedTitle() {
				const date = this.activeDate || (this.feed && this.feed.date);
				return date ? `我的简报 · ${date}` : '我的简报';
			}
		},
		onLoad() {
			this.fnLoad();
		},
		onPullDownRefresh() {
			this.fnLoad();
		},
		methods: {
			async fnLoad() {
				this.loading = 'loading';
				try {
					const res = await this.$httpApi.v2.getAiContentPreference();
					const body = res?.data || res || {};
					if (body.error) {
						throw { error: body.error };
					}
					this.status = body.status || null;
					this.limits = Object.assign({}, this.limits, body.limits || {});
					this.benefitNote = body.benefitNote || '';
					this.categoryOptions = Array.isArray(body.categoryOptions) ? body.categoryOptions : [];
					this.sourceOptions = Array.isArray(body.sourceOptions) ? body.sourceOptions : [];
					const preference = body.preference || {};
					this.picked = {
						categories: Array.isArray(preference.categories) ? preference.categories.slice() : [],
						sources: Array.isArray(preference.sources) ? preference.sources.slice() : [],
						keywords: Array.isArray(preference.keywords) ? preference.keywords.slice() : []
					};
					this.loading = 'success';
				} catch (err) {
					this.loading = 'success';
					uni.showToast({
						title: err?.error || err?.detail || '加载失败，请稍后重试',
						icon: 'none'
					});
					return;
				} finally {
					uni.stopPullDownRefresh();
				}
				if (this.status && this.status.active) {
					this.fnLoadFeed('');
					this.fnLoadIssues();
				}
			},
			/** 读取某一天的简报（留空 = 今天，服务端会在首次打开时生成） */
			async fnLoadFeed(date) {
				this.feedLoading = true;
				try {
					const res = await this.$httpApi.v2.getAiContentFeed(date || undefined);
					const body = res?.data || res || {};
					if (body.error) {
						throw { error: body.error };
					}
					this.feed = body;
					this.feedItems = Array.isArray(body.items) ? body.items : [];
					this.activeDate = body.date || date || '';
					if (!date) {
						this.fnLoadIssues();
					}
				} catch (err) {
					uni.showToast({
						title: err?.error || err?.detail || '简报加载失败，请稍后重试',
						icon: 'none'
					});
				} finally {
					this.feedLoading = false;
				}
			},
			async fnLoadIssues() {
				try {
					const res = await this.$httpApi.v2.getAiContentIssues(10);
					const body = res?.data || res || {};
					this.issues = Array.isArray(body.items) ? body.items : [];
				} catch (err) {
					this.issues = [];
				}
			},
			fnOpenIssue(date) {
				if (!date || date === this.activeDate) return;
				this.fnLoadFeed(date);
			},
			fnToggleCategory(key) {
				const index = this.picked.categories.indexOf(key);
				if (index >= 0) {
					this.picked.categories.splice(index, 1);
					return;
				}
				if (this.picked.categories.length >= this.limits.maxCategories) {
					uni.showToast({
						title: `最多关注 ${this.limits.maxCategories} 个主题`,
						icon: 'none'
					});
					return;
				}
				this.picked.categories.push(key);
			},
			fnToggleSource(value) {
				const index = this.picked.sources.indexOf(value);
				if (index >= 0) {
					this.picked.sources.splice(index, 1);
					return;
				}
				if (this.picked.sources.length >= this.limits.maxSources) {
					uni.showToast({
						title: `最多关注 ${this.limits.maxSources} 个来源`,
						icon: 'none'
					});
					return;
				}
				this.picked.sources.push(value);
			},
			fnAddKeyword() {
				const word = (this.keywordDraft || '').trim();
				if (!word) return;
				if (word.length > 20) {
					uni.showToast({ title: '关键词请控制在 20 个字符以内', icon: 'none' });
					return;
				}
				if (this.picked.keywords.indexOf(word) >= 0) {
					this.keywordDraft = '';
					return;
				}
				if (this.picked.keywords.length >= this.limits.maxKeywords) {
					uni.showToast({
						title: `最多填 ${this.limits.maxKeywords} 个关键词`,
						icon: 'none'
					});
					return;
				}
				this.picked.keywords.push(word);
				this.keywordDraft = '';
			},
			fnRemoveKeyword(index) {
				this.picked.keywords.splice(index, 1);
			},
			/** 空简报时的一键关注：点一下就把该主题加进去并保存 */
			fnQuickPick(key) {
				if (this.picked.categories.indexOf(key) < 0) {
					this.fnToggleCategory(key);
				}
				this.fnSave();
			},
			async fnSave() {
				if (this.saving) return;
				if (!checkHasWxLogin()) {
					uni.showToast({ title: '请先登录', icon: 'none' });
					return;
				}
				this.saving = true;
				try {
					const res = await this.$httpApi.v2.saveAiContentPreference({
						categories: this.picked.categories,
						sources: this.picked.sources,
						keywords: this.picked.keywords
					});
					const body = res?.data || res || {};
					if (body.error) {
						throw body;
					}
					// 以服务端返回为准回写：避免本地与服务端不一致
					this.picked = {
						categories: (body.categories || []).slice(),
						sources: (body.sources || []).slice(),
						keywords: (body.keywords || []).slice()
					};
					uni.showToast({ title: '已保存，简报会按新的方向生成', icon: 'none' });
					// 刚配好关注方向是最自然的授权时机：此时问「简报就绪时通知你」不会被当成打扰
					this.fnMaybeAskPushPermission();
					// 关注方向变了，今天的简报要重新生成（服务端当天已按新方向重建）
					this.fnLoadFeed('');
				} catch (err) {
					uni.showToast({
						title: err?.error || err?.detail || '保存失败，请稍后重试',
						icon: 'none'
					});
				} finally {
					this.saving = false;
				}
			},
			/** 未开通时的购买：买完直接刷新权益状态，页面切到配置区 */
			async fnBuy(goods, index) {
				if (this.payingIndex >= 0) return;
				if (!checkHasWxLogin()) {
					uni.showToast({ title: '请先登录', icon: 'none' });
					return;
				}
				this.payingIndex = index;
				let waiting = false;
				try {
					const result = await payVirtualGoods({
						apiV2: this.$httpApi.v2,
						goodsId: goods.goodsId,
						onWaiting: () => {
							waiting = true;
							uni.showLoading({ title: '确认到账中...' });
						}
					});
					if (result.ok) {
						uni.showToast({ title: '开通成功，来配置你的关注方向', icon: 'success' });
						// 刚付完款是最愿意收到提醒的时刻
						this.fnMaybeAskPushPermission();
						this.fnLoad();
					} else if (result.envBlocked) {
						uni.showModal({ title: '支付受限', content: result.reason, showCancel: false });
					} else if (result.cancelled) {
						uni.showToast({ title: result.reason || '支付已取消', icon: 'none' });
					} else if (result.terminal) {
						uni.showModal({
							title: '订单需人工处理',
							content: result.message
								|| '该权益你已拥有，本次购买未重复发放，请联系站长退款。',
							showCancel: false
						});
					} else if (result.pending) {
						uni.showToast({ title: '支付成功，权益稍后自动到账', icon: 'none' });
					} else {
						uni.showToast({ title: result.error || '购买失败，请稍后重试', icon: 'none' });
					}
				} finally {
					if (waiting) uni.hideLoading();
					this.payingIndex = -1;
				}
			},
			/**
			 * 争取一次「简报就绪提醒」的订阅授权。
			 *
			 * <p>一次性订阅：一次授权只能发一条，所以这里不做「每次都问」——用户点了拒绝就
			 * 不再追问（避免变成打扰，也避免踩微信「诱导订阅」的红线）。</p>
			 */
			async fnMaybeAskPushPermission() {
				const push = getAppConfigs()?.pushConfig || {};
				if (push.enabled !== true || !push.templateId) {
					return;
				}
				const tmplId = push.aiDigestTemplateId || push.templateId;
				await requestSubscribe(this.$httpApi.v2, [{ kind: 'aiDigest', tmplId }]);
			},
			fnOpenItem(item) {
				if (!item.postName) {
					uni.showToast({ title: '这条内容暂不可跳转', icon: 'none' });
					return;
				}
				uni.navigateTo({
					url: '/pagesA/article-detail/article-detail?name=' + item.postName
				});
			},
			fnPrice(cents) {
				return (Number(cents || 0) / 100).toFixed(2);
			},
			fnGoodsPeriod(goods) {
				const days = Number(goods.value || 0);
				return days > 0 ? `${days} 天有效期` : '永久有效';
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

	.status-card {
		margin: 24rpx 28rpx 16rpx;
		padding: 32rpx 28rpx;
		border-radius: 20rpx;
		background-image: $uh-grad-accent;
		color: #fff;
	}

	.status-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
	}

	.status-title {
		font-size: 34rpx;
		font-weight: 700;
	}

	.status-badge {
		padding: 4rpx 18rpx;
		border-radius: 999rpx;
		background: rgba(255, 255, 255, 0.22);
		font-size: 22rpx;
	}

	.status-desc {
		display: block;
		margin-top: 12rpx;
		font-size: 23rpx;
		line-height: 1.6;
		opacity: 0.94;
	}

	.status-meta {
		display: flex;
		margin-top: 16rpx;
		font-size: 22rpx;
		opacity: 0.9;
	}

	.status-meta-item {
		margin-right: 24rpx;
	}

	.section-card {
		margin: 0 28rpx 16rpx;
		padding: 24rpx 28rpx;
		border-radius: 20rpx;
		background: $uh-card;
	}

	.section-head {
		margin-bottom: 14rpx;
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
		line-height: 1.6;
		color: $uh-ink-3;
	}

	.benefit-line {
		display: flex;
		margin-top: 10rpx;
	}

	.benefit-dot {
		margin-right: 10rpx;
		color: $uh-accent;
	}

	.benefit-text {
		flex: 1;
		font-size: 23rpx;
		line-height: 1.6;
		color: $uh-ink-2;
	}

	.goods-list {
		margin-top: 18rpx;
	}

	.goods-item {
		display: flex;
		align-items: center;
		padding: 22rpx 24rpx;
		margin-top: 12rpx;
		border-radius: 16rpx;
		background: $uh-accent-weak;
	}

	.goods-item-hover {
		opacity: 0.86;
	}

	.goods-main {
		flex: 1;
		min-width: 0;
	}

	.goods-name {
		display: block;
		font-size: 27rpx;
		font-weight: 600;
		color: $uh-ink-1;
	}

	.goods-tip {
		display: block;
		margin-top: 6rpx;
		font-size: 20rpx;
		line-height: 1.5;
		color: $uh-ink-3;
	}

	.goods-price {
		margin-left: 16rpx;
		font-size: 32rpx;
		font-weight: 700;
		color: #e8622c;
	}

	.chip-wrap {
		display: flex;
		flex-wrap: wrap;
		margin-top: 6rpx;
	}

	.chip {
		margin: 8rpx 12rpx 0 0;
		padding: 10rpx 22rpx;
		border-radius: 999rpx;
		background: #f2f4fa;
	}

	.chip-on {
		background: $uh-accent-weak;
	}

	.chip-ghost {
		border: 1rpx solid $uh-accent-weak;
		background: transparent;
	}

	.chip-text {
		font-size: 23rpx;
		color: $uh-ink-2;
	}

	.chip-on .chip-text {
		color: $uh-accent;
		font-weight: 600;
	}

	.keyword-row {
		display: flex;
		align-items: center;
		margin-top: 6rpx;
	}

	.keyword-input {
		flex: 1;
		height: 72rpx;
		padding: 0 22rpx;
		border-radius: 14rpx;
		background: #f5f6fa;
		font-size: 25rpx;
		color: $uh-ink-1;
	}

	.keyword-placeholder {
		color: #b6bacb;
	}

	.keyword-add {
		margin-left: 14rpx;
		padding: 0 28rpx;
		height: 72rpx;
		display: flex;
		align-items: center;
		border-radius: 14rpx;
		background: $uh-accent-weak;
	}

	.keyword-add-hover {
		opacity: 0.86;
	}

	.keyword-add-text {
		font-size: 25rpx;
		font-weight: 600;
		color: $uh-accent;
	}

	.save-bar {
		margin: 0 28rpx 16rpx;
	}

	.save-btn {
		display: flex;
		align-items: center;
		justify-content: center;
		height: 88rpx;
		border-radius: 18rpx;
		background-image: $uh-grad-accent;
	}

	.save-btn-hover {
		opacity: 0.9;
	}

	.save-btn-busy {
		opacity: 0.7;
	}

	.save-btn-text {
		font-size: 28rpx;
		font-weight: 700;
		color: #fff;
	}

	.feed-loading {
		padding: 36rpx 0;
		text-align: center;
	}

	.feed-loading-text {
		font-size: 22rpx;
		color: $uh-ink-3;
	}

	.feed-intro {
		padding: 20rpx 22rpx;
		margin-bottom: 8rpx;
		border-radius: 14rpx;
		background: $uh-accent-weak;
	}

	.feed-intro-text {
		font-size: 25rpx;
		line-height: 1.7;
		color: $uh-ink-1;
	}

	.feed-item {
		border-top: 1rpx solid #f0f1f5;
	}

	.feed-item:first-of-type {
		border-top: none;
	}

	.feed-item-main {
		padding: 22rpx 0;
	}

	.feed-item-hover {
		background: #fafbff;
	}

	.feed-item-head {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
	}

	.feed-item-title {
		flex: 1;
		font-size: 27rpx;
		font-weight: 600;
		line-height: 1.45;
		color: $uh-ink-1;
	}

	.feed-item-arrow {
		margin-left: 12rpx;
		font-size: 32rpx;
		color: #c8ccd8;
	}

	.feed-item-meta {
		display: flex;
		align-items: center;
		margin-top: 8rpx;
		font-size: 21rpx;
		color: $uh-ink-3;
	}

	.feed-item-tag {
		margin-right: 14rpx;
	}

	.feed-item-reason {
		display: block;
		margin-top: 10rpx;
		font-size: 23rpx;
		line-height: 1.6;
		color: $uh-ink-2;
	}

	.feed-item-summary {
		display: block;
		margin-top: 10rpx;
		font-size: 23rpx;
		line-height: 1.6;
		color: $uh-ink-3;
	}

	.feed-item-match {
		display: block;
		margin-top: 8rpx;
		font-size: 20rpx;
		color: #9aa1b5;
	}

	.issue-item {
		display: flex;
		align-items: center;
		padding: 18rpx 0;
		border-top: 1rpx solid #f0f1f5;
	}

	.issue-item:first-of-type {
		border-top: none;
	}

	.issue-item-hover {
		background: #fafbff;
	}

	.issue-date {
		width: 200rpx;
		font-size: 24rpx;
		font-weight: 600;
		color: $uh-ink-1;
	}

	.issue-intro {
		flex: 1;
		font-size: 21rpx;
		color: $uh-ink-3;
		overflow: hidden;
		white-space: nowrap;
		text-overflow: ellipsis;
	}

	.issue-count {
		margin-left: 12rpx;
		font-size: 21rpx;
		color: $uh-ink-3;
	}

	.empty-tip {
		padding: 40rpx 0;
		font-size: 23rpx;
		line-height: 1.7;
		color: $uh-ink-3;
		text-align: center;
	}

	.footer-tip {
		padding: 0 36rpx;
		font-size: 21rpx;
		line-height: 1.7;
		color: #a1a6b8;
	}
</style>
