<template>
	<view :class="skinClass" class="app-page">
		<view v-if="loading === 'error'" class="load-error flex flex-center flex-col">
			<tm-empty icon="icon-wind-cry" :label="loadErrorMsg || '文章加载失败'"></tm-empty>
			<tm-button class="mt-24" size="m" theme="indigo" @click="fnGetData">重新加载</tm-button>
		</view>
		<!--
			文章被插件拦截：付费墙（阅读券解锁）或订阅墙（分类未订阅）。
			此处只展示 title/excerpt/cover，不含任何正文内容。
			两种墙共用一张卡片骨架（403 + locked 的同一识别口径），按 type 分流按钮区。
		-->
		<view v-else-if="loading === 'locked' && contentLock" class="content-locked ma-24 pa-24 round-12">
			<view class="locked-title">{{ contentLock.title || (calcLockIsSubscription ? '订阅后可读' : '付费内容') }}</view>
			<image
				v-if="contentLock.cover"
				class="locked-cover mt-24"
				mode="aspectFill"
				:src="calcUrl(contentLock.cover)"
				@click="handlePreview(0, [{ url: calcUrl(contentLock.cover) }])"
			></image>
			<view v-if="contentLock.excerpt" class="locked-excerpt mt-24">{{ contentLock.excerpt }}</view>
			<!-- 订阅墙：分类未订阅，引导订阅（列表能看，全文需订阅——转化漏斗的最后一环） -->
			<template v-if="calcLockIsSubscription">
				<view class="locked-tip mt-24">🔒 {{ contentLock.message || '订阅后可阅读全文' }}</view>
				<view v-if="lockCategoryNames" class="locked-balance mt-16">
					<text>所属分类：{{ lockCategoryNames }}</text>
				</view>
				<view class="locked-actions mt-24">
					<tm-button
						v-if="!calcLockIsLogin"
						size="m"
						theme="bg-gradient-indigo-accent"
						@click="fnLoginForSubscribe"
					>登录后订阅查看全文</tm-button>
					<tm-button
						v-else-if="calcLockPaused || lockQuotaFull"
						size="m"
						theme="bg-gradient-orange-accent"
						:loading="subscribing"
						@click="fnOpenLockUnlockSheet"
					>{{ calcLockPaused ? '订阅已暂停，去解锁' : '订阅额度已满，去解锁' }}</tm-button>
					<tm-button
						v-else
						size="m"
						theme="bg-gradient-indigo-accent"
						:loading="subscribing"
						@click="fnSubscribeLockedCategory"
					>{{ subscribing ? '订阅中...' : '立即订阅' }}</tm-button>
				</view>
				<view v-if="lockQuotaHint" class="locked-balance mt-16">
					<text>{{ lockQuotaHint }}</text>
				</view>
			</template>
			<!-- 付费墙：阅读券解锁（原有流程不动） -->
			<template v-else>
				<view class="locked-tip mt-24">🔒 {{ contentLock.message || '本文为付费内容，需使用阅读券解锁' }}</view>
				<view class="locked-balance mt-16">
					<text>我的阅读券：{{ calcLockTickets }} 张</text>
					<text>解锁需 {{ calcLockCostTickets }} 张</text>
				</view>
				<view class="locked-actions mt-24">
					<tm-button
						v-if="calcLockCanUnlock"
						size="m"
						theme="bg-gradient-indigo-accent"
						:loading="unlocking"
						@click="fnUnlockContent"
					>{{ unlocking ? '解锁中...' : '使用阅读券解锁' }}</tm-button>
					<tm-button
						v-else
						size="m"
						theme="bg-gradient-orange-accent"
						:disabled="unlocking"
						@click="fnGoGetTickets"
					>{{ calcLockIsLogin ? '阅读券不足，去获取' : '登录后查看并解锁' }}</tm-button>
				</view>
				<!--
					会员引导：会员期内本文直接免券，因此这比「再买张券」更划算。
					只在会员卡开启、用户不是会员、且会员确实含免券权益时展示
					（承诺一个用户开了也享受不到的权益是最伤信任的做法）。
				-->
				<view v-if="calcLockIsLogin && !calcLockCanUnlock && memberPromo" class="locked-member mt-16">
					<view class="locked-member-main">
						<text class="locked-member-title">{{ memberPromo.title }}</text>
						<text class="locked-member-desc">{{ memberPromo.desc }}</text>
					</view>
					<text class="locked-member-link" @click="fnGoGetTickets">去看看 ›</text>
				</view>
				<!--
					券不够时的最高意图时刻：用户已经点开文章、看到摘要、被墙挡住。
					在这里直接给一个「用积分换 1 张券」的按钮，比让他自己跳去充值页再找入口短得多。
				-->
				<view v-if="calcLockIsLogin && !calcLockCanUnlock && ticketExchange" class="locked-exchange mt-16">
					<text class="locked-exchange-text">
						可用 {{ ticketExchange.points }} 积分兑换 {{ ticketExchange.ticketCount || 1 }} 张阅读券
						<text v-if="ticketExchange.todayCount >= ticketExchange.dailyMaxCount" class="locked-exchange-warn">
							（今日已兑完）
						</text>
					</text>
					<tm-button
						size="s"
						theme="primary"
						:disabled="exchangingTicket || ticketExchange.todayCount >= ticketExchange.dailyMaxCount"
						:loading="exchangingTicket"
						@click="fnExchangeTicketForArticle"
					>积分兑换</tm-button>
				</view>
			</template>
		</view>
		<view v-else-if="loading !== 'success'" class="loading-wrap">
			<tm-skeleton model="card"></tm-skeleton>
			<tm-skeleton model="card"></tm-skeleton>
			<tm-skeleton model="card"></tm-skeleton>
		</view>
		<block v-else>
			<!-- 顶部信息 -->
			<view class="head ma-24">
				<view class="title">{{ result.spec.title }}</view>
				<view class="detail">
					<view class="author">
						<text class="author-name">作者：{{ result.owner.displayName }}</text>
						<text class="author-time">时间：{{ { d: result.spec.publishTime, f: 'yyyy年MM月dd日 星期w' } | formatTime }}</text>
					</view>

					<view class="cover" v-if="result.spec.cover">
						<image class="cover-img" mode="aspectFill" :src="calcUrl(result.spec.cover)" @click="handlePreview(0, [{ url: calcUrl(result.spec.cover) }])"></image>
					</view>
					<view v-if="calcShowArticleStats" class="count" :class="{ 'no-thumbnail': !result.spec.cover }">
						<view class="count-item">
							<text class="value">{{ result.stats.visit }}</text>
							<text class="label">阅读</text>
						</view>
						<view class="count-item" :class="{ 'is-upvoted': isUpvoted }">
							<text class="value">{{ result.stats.upvote }}</text>
							<text class="label">喜欢</text>
						</view>
						<view v-if="postDetailConfig && postDetailConfig.showComment" class="count-item">
							<text class="value">{{ result.stats.comment }}</text>
							<text class="label">评论</text>
						</view>
						<view class="count-item">
							<text class="value">{{ result.content.raw.length }}</text>
							<text class="label">字数</text>
						</view>
					</view>
				</view>
			</view>
			<!-- 分类 -->
			<view class="category">
				<view class="category-type">
					<text class="text-weight-b">分类：</text>
					<text v-if="result.categories.length === 0" class="category-tag is-empty">未选择分类</text>
					<block v-else>
						<text class="category-tag" v-for="(item, index) in result.categories" :key="index" @click="fnToCate(item)">
							{{ item.spec.displayName }}
						</text>
					</block>
				</view>
				<view class="mt-18 category-type">
					<text class="text-weight-b">标签：</text>
					<text v-if="result.tags.length === 0" class="category-tag is-empty">未选择标签</text>
					<block v-else>
						<!--
							标签底色取 spec.color（Halo 的标签色字段），不是顶层的 color——
							顶层没有这个字段，写成 item.color 等于永远没有底色，标签只剩灰字。
							站长手工建的标签通常没设颜色，所以要有兜底底色。
						-->
						<text class="category-tag" :style="{ backgroundColor: fnTagColor(item) }" v-for="(item, index) in result.tags" :key="index" @click="fnToTag(item)">
							{{ item.spec.displayName }}
						</text>
					</block>
				</view>
				<view v-if="originalURL" class="mt-18 category-type original-url">
					<view class="original-url_left text-weight-b">原文：</view>
					<view class="original-url_right text-grey">
						<text class="original-url_right__link" @click.stop="fnToOriginal(originalURL)">{{ originalURL }}</text>
						<text class="original-url_right__btn" @click.stop="fnToOriginal(originalURL)">
							阅读原文
							<text class="iconfont icon-angle-right ml-5 text-size-s"></text>
						</text>
					</view>
				</view>
			</view>
			<view v-if="!calcAuditModeEnabled && result._doubanUrls.length !== 0 && doubanPluginConfig.position === 'top'" class="pa-24 pb-0">
				<view class="vote-wrap copyright-wrap bg-white pa-24 round-4">
					<view class="copyright-title text-weight-b flex items-center justify-between">
						<text>豆瓣资源</text>
						<text class="vote-opra" @click="doubanIsOpen = !doubanIsOpen">
							{{ doubanIsOpen ? '收起' : '展开' }}
						</text>
					</view>

					<view v-show="doubanIsOpen" class="flex flex-col uh-gap-8 uh-mt-8">
						<ArticleDouban
							v-for="(doubanUrl, doubanIndex) in result._doubanUrls"
							:key="doubanIndex"
							:url="doubanUrl"
							:article="result"
							:index="doubanIndex"
						></ArticleDouban>
					</view>
					<view v-show="uniHaloPluginAvailable && !doubanIsOpen" class="vote-tip" @click="doubanIsOpen = !doubanIsOpen">豆瓣内容已收起，点击展开</view>
					<view v-show="!uniHaloPluginAvailable && !doubanIsOpen" class="vote-tip" @click="doubanIsOpen = !doubanIsOpen">提示区域已收起，点击显示</view>
				</view>
			</view>
			<!-- 内容区域 -->
			<view class="content ml-24 mr-24">
				<!-- markdown渲染 -->
				<view class="markdown-wrap">
					<view v-if="checkPostRestrictRead(result)">
						<restrict-read-skeleton
							:loading="true"
							:lines="3"
							:tip-text="`此处内容已隐藏，「${getRestrictReadTypeName(result)}可见」`"
							:button-text="`${getRestrictReadTypeName(result)}`"
							button-color="#2F54EB"
							animation-duration="2"
							@refresh="readMore"
						/>
					</view>
					<view v-else>
						<mp-html
							class="evan-markdown"
							lazy-load
							:domain="markdownConfig.domain"
							:loading-img="markdownConfig.loadingGif"
							:scroll-table="true"
							:selectable="true"
							:tag-style="markdownConfig.tagStyle"
							:container-style="markdownConfig.containStyle"
							:content="result.content.raw"
							:markdown="true"
							:showLineNumber="true"
							:showLanguageName="true"
							:copyByLongPress="true"
						/>
					</view>
				</view>

				<!--
					上一篇 / 下一篇：正文读完紧接着给「继续阅读」的出口，所以贴在内容区正下方。
					左右两栏：左=上一篇（更早发布），右=下一篇（更晚发布）；标题单行省略（超出显示 ...）。
					哪一侧没有文章就不渲染那一栏，不留点了没反应的占位。
				-->
				<view v-if="!calcAuditModeEnabled && (neighbors.prev || neighbors.next)"
					class="post-nav bg-white mt-24 round-4">
					<view v-if="neighbors.prev" class="post-nav_cell" hover-class="post-nav_cell--pressed"
						:hover-stay-time="60" @click="fnToRelatedPost(neighbors.prev.name)">
						<text class="post-nav_label">‹ 上一篇</text>
						<text class="post-nav_title text-sub-one">{{ neighbors.prev.title }}</text>
					</view>
					<view v-if="neighbors.prev && neighbors.next" class="post-nav_divider"></view>
					<view v-if="neighbors.next" class="post-nav_cell post-nav_cell--right"
						hover-class="post-nav_cell--pressed" :hover-stay-time="60"
						@click="fnToRelatedPost(neighbors.next.name)">
						<text class="post-nav_label">下一篇 ›</text>
						<text class="post-nav_title text-sub-one">{{ neighbors.next.title }}</text>
					</view>
				</view>

				<!-- 豆瓣 -->
				<view
					v-if="!calcAuditModeEnabled && result._doubanUrls.length !== 0 && doubanPluginConfig.position === 'bottom'"
					class="vote-wrap copyright-wrap bg-white mt-24 pa-24 round-4"
				>
					<view class="copyright-title text-weight-b flex items-center justify-between">
						<text>豆瓣资源</text>
						<text class="vote-opra" @click="doubanIsOpen = !doubanIsOpen">
							{{ doubanIsOpen ? '收起' : '展开' }}
						</text>
					</view>

					<view v-show="doubanIsOpen" class="flex flex-col uh-gap-8 uh-mt-8">
						<ArticleDouban
							v-for="(doubanUrl, doubanIndex) in result._doubanUrls"
							:key="doubanIndex"
							:url="doubanUrl"
							:article="result"
							:index="doubanIndex"
						></ArticleDouban>
					</view>
					<view v-show="uniHaloPluginAvailable && !doubanIsOpen" class="vote-tip" @click="doubanIsOpen = !doubanIsOpen">豆瓣内容已收起，点击展开</view>
					<view v-show="!uniHaloPluginAvailable && !doubanIsOpen" class="vote-tip" @click="doubanIsOpen = !doubanIsOpen">提示区域已收起，点击显示</view>
				</view>

				<!-- 版权声明 -->
				<view v-if="postDetailConfig && postDetailConfig.copyrightEnabled" class="copyright-wrap bg-white mt-24 pa-24 round-4">
					<view class="copyright-title text-weight-b">版权声明</view>
					<view class="copyright-content mt-12 grey-lighten-5 text-grey-darken-2 round-4 pt-12 pb-12 pl-24 pr-24">
						<view v-if="postDetailConfig.copyrightAuthor" class="copyright-text text-size-s">版权归属：{{ postDetailConfig.copyrightAuthor }}</view>
						<view v-if="postDetailConfig.copyrightDesc" class="copyright-text text-size-s mt-12">版权说明：{{ postDetailConfig.copyrightDesc }}</view>
						<view v-if="postDetailConfig.copyrightViolation" class="copyright-text text-size-s mt-12 text-red">
							侵权处理：{{ postDetailConfig.copyrightViolation }}
						</view>
					</view>
				</view>

				<!-- 表情快捷反馈 -->
				<view v-if="result" class="reaction-block mt-24">
					<content-reaction-bar content-type="post" :content-name="result.metadata.name"></content-reaction-bar>
				</view>

				<!--
					猜你喜欢：按本文标签 → 分类 → 最新 的顺序凑 5 篇（前两级可能不足 5 篇）。
					标签比分类更贴近「相似」；都不够时用最新文章兜底，保证这一栏不空着。
					只列标题（不带封面、不带日期）：这一栏是「顺手看一眼还有啥」，做成大卡片反而
					把评论区推得更远，一行一条更好扫。
				-->
				<view v-if="!calcAuditModeEnabled && relatedPosts.length" class="related-wrap bg-white mt-24 pa-24 round-4">
					<view class="related-head text-weight-b">猜你喜欢</view>
					<view v-for="(item, index) in relatedPosts" :key="item.name" class="related-item"
						hover-class="related-item--pressed" :hover-stay-time="60" @click="fnToRelatedPost(item.name)">
						<text class="related-item_index">{{ index + 1 }}</text>
						<text class="related-item_title text-sub-one">{{ item.title }}</text>
					</view>
				</view>

				<!-- 评论展示区域 -->
				<block v-if="postDetailConfig && postDetailConfig.showComment">
					<view v-if="result" id="CommentList" class="comment-wrap bg-white mt-24 pa-24 round-4">
						<commentList
							ref="commentListRef"
							:disallowComment="!result.spec.allowComment"
							:postName="result.metadata.name"
							:post="result"
							@on-comment="fnOnComment"
							@on-comment-detail="fnOnShowCommentDetail"
							@on-loaded="fnOnCommentLoaded"
							@on-count-change="fnOnCommentCountChange"
						></commentList>
					</view>
				</block>
			</view>

			<!-- 弹幕效果 -->
			<barrage ref="barrage" :maxTop="240" :type="globalAppSettings.barrage.type"></barrage>
			<!-- 浏览积分悬浮钮（左下角，避免与右侧操作按钮重叠） -->
			<view
				v-if="calcBrowsePointsFloatVisible"
				class="browse-points-float"
				:class="{ 'is-done': browsePointsEarnedToday, 'is-counting': browsePointsCounting }"
			>
				<view class="browse-points-float__ring">
					<view class="browse-points-float__inner">
						<block v-if="browsePointsEarnedToday">
							<text class="browse-points-float__check">✓</text>
							<text class="browse-points-float__caption">已领</text>
						</block>
						<block v-else>
							<text class="browse-points-float__num">{{ browsePointsRemainSec }}</text>
							<text class="browse-points-float__caption">浏览</text>
						</block>
					</view>
				</view>
			</view>
			<!-- 返回顶部 -->
			<tm-flotbutton :width="90" :offset="[16, 80]" icon="icon-angle-up" color="bg-gradient-indigo-accent" @click="fnToTopPage()"></tm-flotbutton>
			<tm-flotbutton
				:key="'flot-actions-' + (isUpvoted ? 1 : 0)"
				:width="90"
				:actions="flotButtonActions"
				:click-actions-hiden="false"
				actions-pos="left"
				:show-text="true"
				color="bg-gradient-orange-accent"
				@change="fnOnFlotButtonChange"
			></tm-flotbutton>
		</block>

		<!-- 评论详情 -->
		<tm-poup v-model="commentDetail.show" height="auto" :round="6" :over-close="true" position="bottom">
			<view v-if="commentDetail.show && result" class="pa-24">
				<view class="poup-head pb-24">
					<view class="poup-title text-align-center text-size-g text-weight-b mb-32">评论详情</view>
					<comment-item :useContentBg="false" :useActions="false" :isChild="false" :comment="commentDetail.comment" :postName="result.metadata.name"></comment-item>
				</view>

				<scroll-view :scroll-y="true" class="poup-body">
					<view v-if="commentDetail.loading !== 'success'" class="poup-loading-wrap flex flex-center">
						<view v-if="commentDetail.loading === 'loading'" class="loading flex flex-center flex-col">
							<text class="e-loading-icon iconfont icon-loading text-blue"></text>
							<view class="text-size-n text-grey-lighten-1 py-12 mt-12">加载中，请稍等...</view>
						</view>
						<view v-else-if="commentDetail.loading === 'error'" class="error">
							<tm-empty icon="icon-wind-cry" label="加载失败">
								<tm-button theme="bg-gradient-indigo-accent" size="m" @click="fnGetChildComments()">刷新试试</tm-button>
							</tm-empty>
						</view>
					</view>
					<block v-else>
						<view v-if="commentDetail.list.length === 0" class="poup-empty flex flex-center">
							<tm-empty icon="icon-shiliangzhinengduixiang-" label="没有更多评论啦~"></tm-empty>
						</view>

						<block v-else>
							<comment-item
								v-for="(comment, index) in commentDetail.list"
								:useContentBg="false"
								:useSolid="false"
								:useActions="false"
								:key="index"
								:isChild="false"
								:comment="comment"
								:postName="result.metadata.name"
							></comment-item>
						</block>
					</block>
				</scroll-view>
			</view>
		</tm-poup>
 
		<tm-poup v-model="poster.show" width="90vw" height="auto" :round="6" :over-close="true" position="center">
			<view class="poster-content pt-12 bg-white">
				<liu-poster ref="liuPoster" :width="674" :height="940" @change="handleOnPosterChange"></liu-poster>
				<view v-if="poster.loading" class="poster-loading flex flex-center text-grey-darken-1">
					<text class="e-loading-icon iconfont icon-loading"></text>
					<text class="ml-6">海报正在生成...</text>
				</view>
				<block v-if="!poster.loading">
					<image
						:style="{
							width: '100%',
							height: '940rpx'
						}"
						:src="poster.url"
					></image>
					<view class="poster-save ma-24 mt-0 pt-20 flex flex-center">
						<tm-button theme="bg-gradient-indigo-accent" size="m" @click="fnSavePoster()">保存到相册</tm-button>
						<tm-button v-if="false" theme="bg-gradient-orange-accent" size="m" @click="fnShareTo()">分享给好友</tm-button>
						<tm-button theme="bg-gradient-blue-grey-accent" size="m" @click="fnOnPosterClose()">关 闭</tm-button>
					</view>
				</block>
			</view>
		</tm-poup>

		<!-- 评论弹窗 -->
		<block v-if="calcIsShowComment">
			<comment-modal
				:show="commentModal.show"
				:title="commentModal.title"
				:postName="commentModal.postName"
				:isComment="commentModal.isComment"
				@on-close="fnOnCommentModalClose"
			></comment-modal>
		</block>

		<!-- 微信登录弹窗（评论 / 海报 / 点赞 / 订阅墙登录引导） -->
		<wx-login-dialog
			:show.sync="wxLoginDialogShow"
			:title="wxLoginDialogTitle"
			:desc="wxLoginDialogDesc"
			@login-ready="fnOnWxLoginReady"
			@success="fnOnWxLoginSuccess"
			@close="fnOnWxLoginClose"
		></wx-login-dialog>
		<!-- 订阅墙：额度已满/已暂停时的解锁面板（选项与发现页一致） -->
		<tm-actionSheet v-model="lockUnlockSheetShow" title="解锁订阅额度"
			:actions="lockUnlockActions" @change="fnOnLockUnlockAction"></tm-actionSheet>
		<points-toast></points-toast>
	</view>
</template>

<script>
import MarkdownConfig from '@/common/markdown/markdown.config.js';

import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
import tmPoup from '@/tm-vuetify/components/tm-poup/tm-poup.vue';
import tmFlotbutton from '@/tm-vuetify/components/tm-flotbutton/tm-flotbutton.vue';
import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
import tmDialog from '@/tm-vuetify/components/tm-dialog/tm-dialog.vue';
import tmMore from '@/tm-vuetify/components/tm-more/tm-more.vue';

import mpHtml from '@/components/mp-html/components/mp-html/mp-html.vue';
import commentList from '@/pagesA/components/comment-list/comment-list.vue';
import commentItem from '@/pagesA/components/comment-item/comment-item.vue';
import commentModal from '@/pagesA/components/comment-modal/comment-modal.vue';
import wxLoginDialog from '@/components/wx-login-dialog/wx-login-dialog.vue';
import tmActionSheet from '@/tm-vuetify/components/tm-actionSheet/tm-actionSheet.vue';
import PointsToast from '@/components/points-toast/points-toast.vue';
import ArticleDouban from '@/components/article-douban/article-douban.vue';
import contentReactionBar from '@/components/content-reaction-bar/content-reaction-bar.vue';
 
import barrage from '@/components/barrage/barrage.vue';
import { checkHasWxLogin, getOpenid } from '@/utils/auth.js';
import { fetchCategorySubscription, subscribeCategory, subscriptionLimit, isQuotaFull, quotaText } from '@/utils/categorySubscription.js';
import { getCommentAuthorInfo } from '@/utils/comment.js';
import { isWxLoginRequired, tryAutoWxLogin } from '@/utils/wxLogin.js';
import { upvote } from '@/utils/upvote.js';
import { recordUserActivity, removeUserActivity, syncArticleUpvoteStatus, USER_ACTIVITY_TYPES } from '@/utils/userActivity.js';
import { getPointsConfig, isPointsEnabled, tryEarnPointsByAction, checkPointsEarnStatus, POINTS_ACTIONS, fetchExchangeBalance, exchangePointsItem, normalizePointsApiError } from '@/utils/points.js';
import { getDomainOnly } from '@/utils/url.params.js'
import { checkPostRestrictRead, copyToClipboard, getRestrictReadTypeName } from '@/utils/restrictRead';
import HaloTokenConfig from '@/config/uhalo.config';
import RestrictReadSkeleton from '@/components/restrict-read-skeleton/restrict-read-skeleton.vue';
import pluginAvailableMixin from '@/common/mixins/pluginAvailable.js';

export default {
	mixins: [pluginAvailableMixin],
	components: {
		RestrictReadSkeleton,
		tmSkeleton,
		tmPoup,
		tmFlotbutton,
		tmButton,
		tmEmpty,
		tmDialog,
		tmMore,
		mpHtml,
		commentList,
		commentItem, 
		barrage,
		commentModal,
		wxLoginDialog,
		tmActionSheet,
		PointsToast,
		contentReactionBar,
		ArticleDouban
	},
	data() {
		return {
			loading: 'loading',
			loadErrorMsg: '',
			markdownConfig: MarkdownConfig,

			/**
			 * 付费文章锁定信息（后端 ContentTicketFilter 拦截时随 403 返回）。
			 *
			 * <p>结构：{ locked, unlocked, title, excerpt, cover, tickets, costTickets, message }
			 * 为 null 表示本文不是付费文章或已解锁，走正常渲染。</p>
			 */
			contentLock: null,
			/** 解锁请求进行中，防止重复点击重复扣券 */
			unlocking: false,
			/**
			 * 进入锁定态那一刻的登录状态（响应式快照）。
			 *
			 * <p>不能写成 computed 现算：checkHasWxLogin()/getOpenid() 读的是 uni storage，
			 * 不是 Vue 响应式依赖，computed 会把首次结果永久缓存。那样未登录用户登录成功后，
			 * 按钮会一直停在「登录后查看并解锁」并反复弹登录框。</p>
			 */
			lockIsLogin: false,

			flotButtonActions: [],
			queryParams: {
				name: null
			},
			result: null,
			/** 上一篇 / 下一篇（插件接口按发布时间算好，没有对应文章时为 null 并隐藏按钮） */
			neighbors: {
				prev: null,
				next: null
			},
			/** 猜你喜欢：按标签/分类取的相似文章，最多 5 篇 */
			relatedPosts: [],

			commentDetail: {
				loading: 'loading',
				show: false,
				comment: {},
				postName: undefined,
				list: []
			},
			poster: {
				show: false,
				showCanvas: false,
				loading: true,
				res: null,
				url: '',
				configs: []
			},

			metas: [], // 自定义元数据
			commentModal: {
				show: false,
				isComment: false,
				postName: '',
				title: ''
			},
			wxLoginDialogShow: false,
			pendingCommentPayload: null,
			pendingPosterShare: false,
			pendingLike: false,
			isUpvoted: false,
			upvoteLoading: false,

			commentListScrollTop: 0,

			doubanIsOpen: true,

			appAuthorize: {
				writePhotosAlbum: ''
			},
			browsePointsRemainSec: 0,
			browsePointsCounting: false,
			browsePointsEarnedToday: false,
			_browsePointsTimer: null,
			_browsePointsInterval: null,
			/** 跳转充值页获取阅读券后返回，需要复查锁定态（券可能已增加） */
			_needRecheckLock: false,
			/** 券够但未登录时点了解锁，登录成功后自动复查并继续解锁 */
			pendingLockUnlock: false,
			/** 积分兑换券的兑换项；null 表示不可用（未开启付费内容/接口不可用） */
			ticketExchange: null,
			/** 兑换请求进行中，防止连点重复扣积分 */
			exchangingTicket: false,
			/** 会员状态：仅用于锁定卡上的引导文案（已开通会员根本不会进锁定态） */
			membership: { enabled: false, active: false, freeContent: false, bonusTickets: 0 },

			// ── 订阅墙（分类未订阅）──
			/** 订阅请求/额度解锁进行中，防止连点 */
			subscribing: false,
			/** 进入订阅墙锁定态时取一次订阅状态：按钮文案与出口由额度决定 */
			lockSubscription: null,
			/** 额度已满/已暂停时的解锁面板 */
			lockUnlockSheetShow: false,
			/** 游客点「登录后订阅」：登录成功后回到锁定卡片，按钮变成「立即订阅」 */
			pendingSubscribeLogin: false
		};
	},
		computed: {
			haloConfigs() {
				return this.$tm.vx.getters().getConfigs;
			},
			// 应用设置：是否显示文章统计条
			calcShowArticleStats() {
				return this.globalAppSettings?.articleDetail?.showStats !== false;
			},
			postDetailConfig() {
			return this.haloConfigs?.basicConfig?.postDetailConfig || { showComment: true };
		},
		wxLoginDialogTitle() {
			if (this.pendingPosterShare) return '登录后生成海报';
			if (this.pendingLike) return '登录后点赞';
			if (this.pendingLockUnlock) return '登录后解锁';
			return '登录后评论';
		},
		wxLoginDialogDesc() {
			if (this.pendingPosterShare) return '生成分享海报需要先完成微信授权登录';
			if (this.pendingLike) return '点赞需要先完成微信授权登录';
			if (this.pendingLockUnlock) return '解锁付费内容需要先完成微信授权登录';
			return '发表评论需要先完成微信授权登录';
		},
		calcUrl() {
			return (url) => {
				if (this.$utils.checkIsUrl(url)) {
					return url;
				}
				return getApp().globalData.baseApiUrl + url;
			};
		},
		// 原文链接：个人资质=可以打开公众号文章；非个人：任意链接地址（需在小程序后台配置）
		originalURL() {
			return this.result?.metadata?.annotations?.unihalo_originalURL || '';
		},

		calcIsShowComment() {
			return this.postDetailConfig.showComment;
		},
		calcUpvoted() {
			return this.isUpvoted;
		},
		calcAuditModeEnabled() {
			return this.haloConfigs.auditConfig.auditModeEnabled;
		},
		calcPointsEnabled() {
			return isPointsEnabled(this.haloConfigs);
		},
		calcBrowsePointsFloatVisible() {
			return this.calcPointsEnabled
				&& checkHasWxLogin()
				&& (this.browsePointsCounting || this.browsePointsEarnedToday);
		},
		doubanPluginConfig() {
			return this.haloConfigs?.pluginIntegrations?.douban || { position: 'bottom' };
		},

		/** 解锁是否已登录：读进入锁定态时存下的响应式快照，登录成功后会刷新 */
		calcLockIsLogin() {
			return this.lockIsLogin;
		},
		/** 我的阅读券余额，后端未登录时返回 0 */
		calcLockTickets() {
			return Number(this.contentLock?.tickets || 0);
		},
		/** 本文解锁所需券数；后端已保证 costTickets ≥ 1 */
		calcLockCostTickets() {
			return Math.max(Number(this.contentLock?.costTickets || 1), 1);
		},
		/** 登录且券够才展示「解锁」按钮，否则展示「去获取」 */
		calcLockCanUnlock() {
			return this.calcLockIsLogin && this.calcLockTickets >= this.calcLockCostTickets;
		},
		/**
		 * 会员引导文案；返回 null 表示不展示。
		 *
		 * <p>会员卡未开启、用户已是会员、或该站点的会员不含免券权益时都不展示——
		 * 那三种情况下这条引导要么无意义，要么是在承诺用户享受不到的权益。</p>
		 */
		memberPromo() {
			const member = this.membership;
			if (member?.enabled !== true || member?.active === true || member?.freeContent !== true) {
				return null;
			}
			const bonus = Number(member.bonusTickets || 0);
			return {
				title: '开通会员，付费文章免券阅读',
				desc: bonus > 0
					? `会员期内不再消耗阅读券，开通还送 ${bonus} 张券`
					: '会员期内不再消耗阅读券'
			};
		},

		// ── 订阅墙锁定卡 ──
		/** 锁定类型：订阅墙（分类未订阅）还是付费墙（阅读券）。type 缺省按付费墙（兼容老服务端） */
		calcLockIsSubscription() {
			return this.contentLock?.type === 'subscription';
		},
		/** 文章所属未订阅分类的显示名（订阅引导展示用） */
		lockCategoryNames() {
			return (this.contentLock?.categories || [])
				.map((item) => item?.displayName || item?.name)
				.filter(Boolean)
				.join('、');
		},
		calcLockPaused() {
			return this.contentLock?.paused === true;
		},
		/** 订阅额度是否已满（额度下发失败时按未满处理，点订阅时公共模块还会再校验一次） */
		lockQuotaFull() {
			return this.lockSubscription ? isQuotaFull(this.lockSubscription) : false;
		},
		/** 卡片底部的额度提示：说清「还差多少位」比让用户自己去发现页数格子友好 */
		lockQuotaHint() {
			const state = this.lockSubscription;
			if (!state || !state.loggedIn) {
				return '';
			}
			return `已订阅 ${(state.subscribed || []).length}/${quotaText(state.limits)} 个分类`;
		},
		/** 解锁面板选项（与发现页同一组：积分解锁 / 开通会员） */
		lockUnlockActions() {
			const limits = this.lockSubscription?.limits || {};
			const price = Number(limits.quotaPrice) || 0;
			const balance = Number(limits.pointsBalance) || 0;
			const short = Math.max(0, price - balance);
			return [
				{
					title: `积分解锁一份（${price} 积分）`,
					label: short > 0
						? `当前 ${balance} 积分，还差 ${short} 积分`
						: `当前 ${balance} 积分`
				},
				{
					title: '购买会员卡（不限量）',
					label: '会员期内订阅不限量，还可全站畅读'
				}
			];
		}
	},
	watch: {
		haloConfigs: {
			deep: true,
			immediate: true,
			handler: function (newVal) {
				if (!newVal) return;
				this.fnHandleSetFlotButtonItems(newVal);
			}
		}
	},
	onLoad(e) {
		// 从分享卡片/扫码进来时先收下邀请码。
		// 以前这里只解析 name，文章分享带出来的 inviteCode 到这一页就被丢掉了——
		// 结果「分享文章」这条最容易传播的路径对裂变毫无贡献。
		this.fnCaptureInvite(e);
		const name = (e?.name || '').trim();
		if (!name) {
			this.loading = 'error';
			this.loadErrorMsg = '缺少文章参数，无法打开详情';
			uni.showToast({ title: '缺少文章参数', icon: 'none' });
			return;
		}
		this.queryParams.name = decodeURIComponent(name);
		this.fnPrefetchInviteCode();
		this.fnSetPageTitle('文章加载中...');
		this.fnGetData();
	},
	onShow() {
		// 从积分充值页获取阅读券后返回：重新拉取锁定态，
		// 否则卡片仍显示旧的「0 张」，用户以为没到账。只复查一次。
		if (this._needRecheckLock && this.loading === 'locked') {
			this._needRecheckLock = false;
			this.fnGetData();
			return;
		}
		if (this.loading === 'success' && this.calcPointsEnabled && checkHasWxLogin()) {
			this.fnRefreshBrowsePointsStatus();
		}
	},
	onPullDownRefresh() {
		this.fnGetData();
	},
	onUnload() {
		this.fnClearBrowsePointsTimer();
	},
	onShareAppMessage() {
		// 付费锁定态下 result 为 null（正文被后端拦截），
		// 直接读 result.spec 会抛错导致分享面板打不开，这里改用锁定信息兜底。
		if (!this.result) {
			const lock = this.contentLock;
			return {
				path: '/pagesA/article-detail/article-detail?name=' + encodeURIComponent(this.queryParams.name || ''),
				title: lock?.title || '付费内容',
				imageUrl: lock?.cover ? this.calcUrl(lock.cover) : ''
			};
		}
		const cover = this.result.spec.cover ? this.calcUrl(this.result.spec.cover) : '';
		const inviteCode = uni.getStorageSync('invite.code') || '';
		let path = '/pagesA/article-detail/article-detail?name=' + this.result.metadata.name;
		if (inviteCode) {
			path += '&inviteCode=' + encodeURIComponent(inviteCode);
		}
		return {
			path,
			title: this.result.spec.title,
			imageUrl: cover
		};
	},
	onShareTimeline() {
		// 同上：锁定态用锁定信息兜底，避免读取空对象报错
		if (!this.result) {
			const lock = this.contentLock;
			return {
				title: lock?.title || '付费内容',
				query: { name: this.queryParams.name || '' },
				imageUrl: lock?.cover ? this.calcUrl(lock.cover) : ''
			};
		}
		const cover = this.result.spec.cover ? this.calcUrl(this.result.spec.cover) : '';
		return {
			title: this.result.spec.title,
			query: {
				name: this.result.metadata.name
			},
			imageUrl: cover
		};
	},
	methods: {
		getRestrictReadTypeName,
		checkPostRestrictRead,
		/**
		 * 详情页底部的两块内容：上一篇/下一篇、猜你喜欢。
		 *
		 * <p>都在正文渲染完后再取，且各自独立失败（catch 掉，页面安静地少一块），
		 * 绝不能让附件内容把正文的加载或渲染拖下水。</p>
		 */
		fnLoadFooterModules() {
			if (this.calcAuditModeEnabled || !this.result) return;
			this.fnLoadNeighbors();
			this.fnLoadRelatedPosts();
		},

		/** 上一篇 / 下一篇（插件接口，服务端按发布时间算好相邻关系） */
		async fnLoadNeighbors() {
			const name = this.result?.metadata?.name;
			this.neighbors = { prev: null, next: null };
			if (!name) return;
			try {
				const res = await this.$httpApi.v2.getPostNeighbors(name);
				const body = res?.data || res || {};
				this.neighbors = {
					prev: body.prev && body.prev.name ? body.prev : null,
					next: body.next && body.next.name ? body.next : null
				};
			} catch (err) {
				console.warn('[article-detail] 上下篇加载失败：', err);
			}
		},

		/**
		 * 猜你喜欢：标签 → 分类 → 最新，依次凑满 5 篇。
		 *
		 * <p>为什么要有「最新」兜底：只按标签/分类取，冷门文章的相似列表常常只剩 1~2 篇，
		 * 这一栏就会看起来像坏了一半；用最新文章补齐后至少始终有 5 条可读内容。</p>
		 */
		async fnLoadRelatedPosts() {
			const post = this.result;
			this.relatedPosts = [];
			if (!post) return;
			const limit = 5;
			const exclude = new Set([post?.metadata?.name]);
			const picked = [];
			const collect = (items) => {
				(Array.isArray(items) ? items : []).forEach((item) => {
					const name = item?.metadata?.name;
					if (!name || exclude.has(name) || picked.length >= limit) return;
					exclude.add(name);
					picked.push(item);
				});
			};
			// 每个来源单独兜错：某一个接口挂了（标签没权限/分类被删）不能让整栏都空掉
			const loadFrom = async (label, loader) => {
				try {
					const res = await loader();
					collect(res?.items);
				} catch (err) {
					console.warn(`[article-detail] 猜你喜欢(${label})加载失败：`, err);
				}
			};
			try {
				// 1) 标签（最贴近「相似」）
				for (const tag of (post.tags || []).slice(0, 2)) {
					if (picked.length >= limit) break;
					const name = tag?.metadata?.name;
					if (!name) continue;
					await loadFrom('标签', () => this.$httpApi.v2.getPostByTagName(name, { page: 0, size: 6 }));
				}
				// 2) 分类（同一栏目）
				for (const cate of (post.categories || []).slice(0, 2)) {
					if (picked.length >= limit) break;
					const name = cate?.metadata?.name;
					if (!name) continue;
					await loadFrom('分类', () => this.$httpApi.v2.getCategoryPostList(name, { page: 0, size: 6 }));
				}
				// 3) 最新（兜底，保证凑满）
				if (picked.length < limit) {
					await loadFrom('最新', () => this.$httpApi.v2.getPostList({ page: 0, size: 8 }));
				}
				// 只留跳转与展示要的字段：这一栏只显示标题，封面/日期都不取，
				// 也顺带避免了把 Halo 那些带点的 label 一起塞进 data（setData 会当路径解析）
				this.relatedPosts = picked.slice(0, limit).map((item) => ({
					name: item.metadata.name,
					title: item.spec?.title || '未命名文章'
				}));
			} catch (err) {
				console.warn('[article-detail] 猜你喜欢加载失败：', err);
				this.relatedPosts = [];
			}
		},

		/**
		 * 跳转到相邻 / 推荐文章。
		 *
		 * <p>用 redirectTo 而不是 navigateTo：详情页之间互相跳很容易把页面栈顶到 10 层上限
		 * （A→B→C…），到那时再点就没反应了。redirectTo 替换当前页，返回时回到文章列表。</p>
		 */
		fnToRelatedPost(name) {
			if (!name) return;
			uni.redirectTo({
				url: '/pagesA/article-detail/article-detail?name=' + name
			});
		},

		fnGetData() {
			const name = (this.queryParams.name || '').trim();
			if (!name) {
				this.loading = 'error';
				this.loadErrorMsg = '缺少文章参数';
				return;
			}
			this.loading = 'loading';
			this.loadErrorMsg = '';
			// 每次加载都重置锁定态：解锁成功后会重新加载，
			// 若不重置，已解锁的文章仍会渲染上一次的锁定卡片
			this.contentLock = null;
			this.lockIsLogin = false;
			console.log('[article-detail] 请求文章:', name);
			this.$httpApi.v2
				.getPostByName(name)
				.then((res) => {
					console.log('详情', res);
					const tempResult = res;
					tempResult._doubanUrls = this.extractDoubanBlockUrls(res.content?.raw || res.content?.content || '');

					const openid = uni.getStorageSync('openid');
					if (openid === '' || openid === null) {
						this.fnGetOpenid();
					}

					this.result = tempResult;
					this.fnSetPageTitle('文章详情');
					this.loading = 'success';
					this.fnSyncUpvoteStatus();
					this.fnHandleSetFlotButtonItems(this.haloConfigs);
					this.handleQueryCommentListScrollTop();
					this.fnTrackersCounter();
					// 底部两块是附加内容：正文渲染完之后再取，且各自失败互不影响
					this.fnLoadFooterModules();
				})
				.catch((err) => {
					// 文章被插件拦截，必须与真正的加载失败区分：这里要渲染引导卡片，
					// 而不是「文章加载失败」。两种墙共用 403 + locked 的识别口径：
					// 付费墙（ContentTicketFilter）→ 阅读券解锁；订阅墙（SubscriptionWallFilter）→ 引导订阅。
					const lockData = err?.data;
					if ((err?.isContentLocked || err?.statusCode === 403) && lockData && lockData.locked === true) {
						this.contentLock = lockData;
						// 存登录态快照：卡片按钮要根据它决定「解锁」还是「去登录/去获取」
						this.lockIsLogin = checkHasWxLogin() && !!getOpenid();
						this.loading = 'locked';
						this.fnSetPageTitle(lockData.title || '付费内容');
						if (lockData.type === 'subscription') {
							// 订阅墙：按钮出口由额度决定（可订阅 / 已满去解锁 / 已暂停去解锁）
							this.fnPrepareSubscriptionLock();
							return;
						}
						// 券不够时卡片要给「用积分兑换」与「开通会员」两个出口，进入锁定态就查一次
						this.fnLoadTicketExchange();
						this.fnLoadMembership();
						return;
					}
					console.error('[article-detail] 加载失败', err);
					this.loadErrorMsg = err?.message || err?.error || err?.detail || '文章加载失败，请稍后重试';
					this.loading = 'error';
				})
				.finally(() => {
					uni.hideLoading();
					uni.stopPullDownRefresh();
				});
		},
		fnHandleSetFlotButtonItems(configs) {
			const upvoteAction = {
				icon: this.isUpvoted ? 'icon-heart-fill' : 'icon-like',
				color: this.isUpvoted ? 'bg-gradient-red-accent' : 'bg-gradient-orange-accent',
				use: true,
				key: this.isUpvoted ? 'upvoted' : 'not-upvoted'
			};
			const actions = [
				{
					icon: 'icon-share1',
					color: 'bg-gradient-blue-accent',
					use: true,
					key: 'share'
				},
				upvoteAction,
				{
					icon: 'icon-commentdots-fill',
					color: 'bg-gradient-green-accent',
					use: configs?.basicConfig?.postDetailConfig?.showComment,
					key: 'comment'
				}
			];

			this.flotButtonActions = actions.filter((x) => x.use === true);
		},
		// 浮动按钮点击
		fnOnFlotButtonChange(index) {
			switch (index) {
				case 0:
					this.fnShowShare();
					break;
				case 1:
					this.fnTryDoLikes();
					break;
				case 2:
					this.fnToComment();
					break;
			}
		},

		fnToComment() {
			if (!this.calcIsShowComment) {
				return;
			}
			if (!this.result.spec.allowComment) {
				return uni.$tm.toast('文章已开启禁止评论！');
			}
			this.fnTryOpenComment({
				isComment: true,
				postName: this.result.metadata.name,
				title: '新增评论'
			});
		},
		fnOnComment(data) {
			this.fnTryOpenComment({
				isComment: data.isComment,
				postName: data.postName,
				title: data.title
			});
		},
		fnTryOpenComment(payload) {
			if (isWxLoginRequired(this.haloConfigs) && !checkHasWxLogin()) {
				this.pendingPosterShare = false;
				this.pendingCommentPayload = payload;
				this.wxLoginDialogShow = true;
				return;
			}
			if (!getOpenid()) {
				this.pendingPosterShare = false;
				this.pendingCommentPayload = payload;
				this.wxLoginDialogShow = true;
				return;
			}
			this.fnOpenCommentModal(payload);
		},
		fnOpenCommentModal(payload) {
			this.commentModal.isComment = payload.isComment;
			this.commentModal.postName = payload.postName;
			this.commentModal.title = payload.title;
			this.commentModal.show = true;

			setTimeout(() => {
				uni.pageScrollTo({
					scrollTop: this.commentListScrollTop,
					duration: 100
				});
			}, 300);
		},
		fnOnWxLoginReady() {
			// 微信登录已完成，绑定页展示中；待绑定完成后再执行 pending 操作
		},
		fnOnWxLoginSuccess() {
			if (this.pendingSubscribeLogin) {
				// 订阅墙的登录引导：登录后重进锁定态（按钮从「去登录」变成「立即订阅」）。
				// fnGetData 再次命中 403 时会带着新登录态重渲染卡片并回读额度
				this.pendingSubscribeLogin = false;
				this.fnGetData();
				return;
			}
			if (this.pendingLockUnlock) {
				// 登录后重新拉取：后端此时能识别身份并返回真实券余额。
				// 这里刻意不自动扣券——解锁是消费行为，必须由用户再点一次确认。
				this.pendingLockUnlock = false;
				this.fnGetData();
				return;
			}
			if (this.pendingPosterShare) {
				this.pendingPosterShare = false;
				this.fnGeneratePoster();
				return;
			}
			if (this.pendingLike) {
				this.pendingLike = false;
				this.fnDoLikes();
				return;
			}
			if (this.pendingCommentPayload) {
				const payload = this.pendingCommentPayload;
				this.pendingCommentPayload = null;
				this.fnOpenCommentModal(payload);
			}
		},
		fnOnWxLoginClose() {
			this.pendingPosterShare = false;
			this.pendingCommentPayload = null;
			this.pendingLike = false;
			this.pendingLockUnlock = false;
			this.pendingSubscribeLogin = false;
		},
		fnOnCommentModalClose({ refresh, isSubmit, needLogin }) {
			if (needLogin) {
				this.commentModal.show = false;
				this.pendingPosterShare = false;
				this.pendingCommentPayload = {
					isComment: this.commentModal.isComment,
					postName: this.commentModal.postName,
					title: this.commentModal.title
				};
				this.wxLoginDialogShow = true;
				return;
			}
			// 评论后自动刷新
			if (this.result?.metadata?.annotations?.restrictReadEnable === 'comment') {
				this.fnGetData();
			}
			if (refresh && isSubmit && this.$refs.commentListRef) {
				this.$refs.commentListRef.fnGetData();
			}
			if (isSubmit) {
				this.fnRecordUserActivity(USER_ACTIVITY_TYPES.COMMENT);
			}
			this.commentModal.show = false;
			this.commentModal.isComment = false;
			this.commentModal.postName = '';
			this.commentModal.title = '';
		},
		fnOnCommentCountChange(count) {
			if (this.result?.stats && typeof count === 'number') {
				this.$set(this.result.stats, 'comment', count);
			}
		},
		fnBuildActivityPayload() {
			if (!this.result?.metadata?.name) return null;
			return {
				articleName: this.result.metadata.name,
				title: this.result.spec?.title || '',
				cover: this.result.spec?.cover || '',
				excerpt: this.result.status?.excerpt || ''
			};
		},
		fnRecordUserActivity(type) {
			const payload = this.fnBuildActivityPayload();
			if (!payload) return Promise.resolve();
			return recordUserActivity(this.$httpApi.v2, type, payload).then(() => {
				if (type === USER_ACTIVITY_TYPES.BROWSE) {
					this.fnInitBrowsePointsEarn(payload);
				}
			});
		},
		fnClearBrowsePointsTimer() {
			if (this._browsePointsTimer) {
				clearTimeout(this._browsePointsTimer);
				this._browsePointsTimer = null;
			}
			if (this._browsePointsInterval) {
				clearInterval(this._browsePointsInterval);
				this._browsePointsInterval = null;
			}
			this.browsePointsCounting = false;
		},
		async fnRefreshBrowsePointsStatus() {
			if (!this.calcPointsEnabled || !checkHasWxLogin()) return;
			const payload = this.fnBuildActivityPayload();
			if (!payload?.articleName) return;
			const status = await checkPointsEarnStatus(
				this.$httpApi.v2,
				POINTS_ACTIONS.BROWSE,
				payload.articleName
			);
			if (status?.earnedToday) {
				this.browsePointsEarnedToday = true;
				this.fnClearBrowsePointsTimer();
			}
		},
		async fnInitBrowsePointsEarn(payload) {
			if (!this.calcPointsEnabled || !checkHasWxLogin()) return;
			const articlePayload = payload || this.fnBuildActivityPayload();
			if (!articlePayload?.articleName) return;

			this.fnClearBrowsePointsTimer();
			this.browsePointsEarnedToday = false;
			this.browsePointsRemainSec = 0;

			const status = await checkPointsEarnStatus(
				this.$httpApi.v2,
				POINTS_ACTIONS.BROWSE,
				articlePayload.articleName
			);
			if (status?.earnedToday) {
				this.browsePointsEarnedToday = true;
				return;
			}

			const minSec = Math.max(Number(getPointsConfig()?.minBrowseDuration ?? 5), 1);
			this.browsePointsRemainSec = minSec;
			this.browsePointsCounting = true;
			this._browsePointsInterval = setInterval(() => {
				if (this.browsePointsRemainSec <= 1) {
					this.fnClearBrowsePointsTimer();
					this.fnExecuteBrowsePointsEarn(articlePayload);
					return;
				}
				this.browsePointsRemainSec -= 1;
			}, 1000);
		},
		async fnExecuteBrowsePointsEarn(articlePayload) {
			const payload = articlePayload || this.fnBuildActivityPayload();
			if (!payload?.articleName) return;
			await tryEarnPointsByAction(this.$httpApi.v2, POINTS_ACTIONS.BROWSE, payload);
			const status = await checkPointsEarnStatus(
				this.$httpApi.v2,
				POINTS_ACTIONS.BROWSE,
				payload.articleName
			);
			this.browsePointsEarnedToday = !!status?.earnedToday;
		},
		fnUpdateUpvoteState(upvoted) {
			const articleName = this.result?.metadata?.name;
			if (articleName) {
				upvote.sync('post', articleName, upvoted);
				if (upvoted) {
					upvote.setUpvoteTime('post', articleName);
				} else {
					upvote.clearUpvoteTime('post', articleName);
				}
			}
			this.isUpvoted = !!upvoted;
			this.fnHandleSetFlotButtonItems(this.haloConfigs);
		},
		fnGetUpvoteCancelCooldownTip() {
			const articleName = this.result?.metadata?.name;
			if (!articleName) return '点赞后需满1分钟才能取消';
			const remainMs = upvote.getCancelCooldownRemainingMs('post', articleName);
			if (remainMs <= 0) return '';
			const remainSec = Math.ceil(remainMs / 1000);
			return `点赞后需满1分钟才能取消，请${remainSec}秒后再试`;
		},
		fnCanCancelUpvote() {
			const articleName = this.result?.metadata?.name;
			if (!articleName) return true;
			return upvote.canCancelUpvote('post', articleName);
		},
		async fnSyncUpvoteStatus() {
			const articleName = this.result?.metadata?.name;
			if (!articleName) {
				this.isUpvoted = false;
				return;
			}
			if (!checkHasWxLogin() || !getOpenid()) {
				this.isUpvoted = false;
				return;
			}
			const upvoted = await syncArticleUpvoteStatus(this.$httpApi.v2, articleName);
			this.isUpvoted = !!upvoted;
			this.fnHandleSetFlotButtonItems(this.haloConfigs);
		},
		fnTryDoLikes() {
			if (isWxLoginRequired(this.haloConfigs) && !checkHasWxLogin()) {
				this.pendingPosterShare = false;
				this.pendingCommentPayload = null;
				this.pendingLike = true;
				this.wxLoginDialogShow = true;
				return;
			}
			if (!getOpenid()) {
				this.pendingPosterShare = false;
				this.pendingCommentPayload = null;
				this.pendingLike = true;
				this.wxLoginDialogShow = true;
				return;
			}
			this.fnDoLikes();
		},
		fnApplyUpvoteStatsDelta(delta) {
			if (!this.result?.stats || !delta) return;
			const nextUpvote = Math.max(Number(this.result.stats.upvote || 0) + delta, 0);
			this.$set(this.result.stats, 'upvote', nextUpvote);
		},
		fnDoLikes() {
			if (this.upvoteLoading || !this.result?.metadata?.name) {
				return;
			}
			const payload = this.fnBuildActivityPayload();
			if (!payload) {
				return;
			}
			if (this.isUpvoted && !this.fnCanCancelUpvote()) {
				uni.$tm.toast(this.fnGetUpvoteCancelCooldownTip());
				return;
			}
			this.upvoteLoading = true;

			if (this.isUpvoted) {
				removeUserActivity(this.$httpApi.v2, USER_ACTIVITY_TYPES.UPVOTE, payload)
					.then(async () => {
						this.fnUpdateUpvoteState(false);
						this.fnApplyUpvoteStatsDelta(-1);
						await this.fnSyncUpvoteStatus();
						uni.$tm.toast('已取消点赞');
					})
					.catch(async (err) => {
						await this.fnSyncUpvoteStatus();
						if (!this.isUpvoted) {
							uni.$tm.toast('已取消点赞');
							return;
						}
						uni.$tm.toast(err?.error || '取消点赞失败');
					})
					.finally(() => {
						this.upvoteLoading = false;
					});
				return;
			}

			const articleName = this.result.metadata.name;
			this.$httpApi.v2
				.submitUpvote({
					group: 'content.halo.run',
					plural: 'posts',
					name: articleName
				})
				.then(async () => {
					this.fnUpdateUpvoteState(true);
					this.fnApplyUpvoteStatsDelta(1);
					await this.fnRecordUserActivity(USER_ACTIVITY_TYPES.UPVOTE);
					await this.fnSyncUpvoteStatus();
					uni.$tm.toast('点赞成功!');
				})
				.catch(async (err) => {
					await this.fnSyncUpvoteStatus();
					if (this.isUpvoted) {
						uni.$tm.toast('点赞成功!');
						return;
					}
					uni.$tm.toast(err?.error || '点赞失败');
				})
				.finally(() => {
					this.upvoteLoading = false;
				});
		},
		fnTrackersCounter() {
			if (!this.result?.metadata?.name) return;
			const winInfo = uni.getWindowInfo();
			const appBaseInfo = uni.getAppBaseInfo();
			const baseApi = HaloTokenConfig.BASE_API.replace(/\/+$/, '');
			const permalink = this.result?.status?.permalink || '';
			const pageUrl = permalink || `/archives/${this.result?.spec?.slug || this.result.metadata.name}`;

			this.$httpApi.v2.postTrackersCounter({
				group: 'content.halo.run',
				plural: 'posts',
				name: this.result.metadata.name,
				hostname: getDomainOnly(baseApi),
				screen: `${winInfo.screenWidth}x${winInfo.screenHeight}`,
				language: appBaseInfo.language || 'zh_CN',
				url: pageUrl,
				referrer: `${baseApi}/`
			}).then(() => {
				if (this.result?.stats) {
					const nextVisit = Number(this.result.stats.visit || 0) + 1;
					this.$set(this.result.stats, 'visit', nextVisit);
				}
			}).catch((err) => {
				console.warn('[article-detail] 浏览量上报失败', err?.error || err);
			}).finally(() => {
				this.fnRecordUserActivity(USER_ACTIVITY_TYPES.BROWSE);
			});
		},
		/** 预取当前用户邀请码，供分享卡片携带（未登录/未开启时静默） */
		/**
		 * 收下分享带进来的邀请码，并尝试静默登录完成邀请结算。
		 *
		 * <p>邀请关系与奖励是在「新用户首次登录」时由服务端结算的，所以一进页面就要把码
		 * 写进待消费存储，不能等用户自己去找登录入口；登录失败也不影响看文章。</p>
		 */
		fnCaptureInvite(options) {
			const query = options || {};
			let code = query.inviteCode || '';
			if (!code && query.scene) {
				const scene = String(query.scene);
				if (scene.startsWith('inv:')) {
					code = scene.substring(4);
				}
			}
			code = String(code || '').trim();
			if (!code) {
				return;
			}
			try {
				uni.setStorageSync('invite.pending', decodeURIComponent(code));
			} catch (err) {
				// 忽略存储异常
			}
			if (checkHasWxLogin()) {
				return;
			}
			tryAutoWxLogin(this.$httpApi).catch(() => {
				// 静默：未登录也能读文章
			});
		},
		async fnPrefetchInviteCode() {
			if (!checkHasWxLogin()) return;
			try {
				const res = await this.$httpApi.v2.getInviteInfo();
				const data = res?.data || res || {};
				if (data?.inviteCode) {
					uni.setStorageSync('invite.code', data.inviteCode);
				}
			} catch (err) {
				// 静默：接口不可用不影响文章阅读
			}
		},
		fnShowShare() {
			if (!checkHasWxLogin() || !getOpenid()) {
				this.pendingCommentPayload = null;
				this.pendingPosterShare = true;
				this.wxLoginDialogShow = true;
				return;
			}
			this.fnGeneratePoster();
		},
		async fnGeneratePoster() {
			this.poster.show = true;
			this.poster.loading = true;
			if (this._posterTimer) {
				clearTimeout(this._posterTimer);
			}
			this._posterTimer = setTimeout(() => {
				if (this.poster.loading) {
					uni.hideLoading();
					this.poster.loading = false;
					this.poster.show = false;
					uni.$tm.toast('海报生成超时，请检查图片配置');
				}
			}, 20000);
			try {
				await this.handleCreatePoster();
				setTimeout(() => {
					this.poster.showCanvas = true;
					this.$nextTick(() => {
						if (this.$refs.liuPoster) {
							this.$refs.liuPoster.init(this.poster.configs);
						}
					});
				}, 500);
			} catch (err) {
				if (this._posterTimer) {
					clearTimeout(this._posterTimer);
					this._posterTimer = null;
				}
				console.error('[article-detail] 海报生成失败', err);
				this.poster.loading = false;
				this.poster.show = false;
				uni.$tm.toast(err?.detail || err?.message || err?.error || '海报生成失败，请稍后重试');
			}
		},
		handleOnPosterChange(url) {
			if (this._posterTimer) {
				clearTimeout(this._posterTimer);
				this._posterTimer = null;
			}
			uni.hideLoading();
			if (!url) {
				this.poster.loading = false;
				this.poster.show = false;
				uni.$tm.toast('海报绘制失败，请检查图片配置');
				return;
			}
			this.poster.url = url;
			this.poster.loading = false;
		},
		/** 海报专用：过滤失效外链，回退到站点内图片 */
		getPosterImageUrl(url) {
			const imagesConfig = this.haloConfigs?.imagesConfig || {};
			const fallback = this.$utils.checkUrl(
				imagesConfig.defaultImageUrl ||
				imagesConfig.defaultStaticThumbnailUrl ||
				imagesConfig.defaultThumbnailUrl ||
				''
			);
			if (!url || (typeof url === 'string' && url.includes('api.qjqq.cn'))) {
				return fallback;
			}
			return this.$utils.checkIsUrl(url) ? url : this.$utils.checkUrl(url);
		},
		/** 海报顶部：使用微信登录用户头像与昵称 */
		getPosterUserInfo() {
			const author = getCommentAuthorInfo();
			const avatarRaw = author.avatar || '';
			const avatar = avatarRaw
				? this.getPosterImageUrl(
					this.$utils.checkIsUrl(avatarRaw) ? avatarRaw : this.$utils.checkUrl(avatarRaw)
				)
				: this.getPosterImageUrl(this.$utils.checkAvatarUrl(''));
			return {
				avatar,
				nickname: author.displayName || '微信用户'
			};
		},
		async handleCreatePoster() {
			const systemInfo = await uni.getSystemInfoSync();
			const posterUser = this.getPosterUserInfo();
			const _posterAvatar = posterUser.avatar;
			const _articleCover = this.getPosterImageUrl(this.$utils.checkThumbnailUrl(this.result.spec.cover, true));
			const _qrCodeImageUrl = await this.qrCodeImageUrl();
			this.poster.configs = [
				{
					type: 'color',
					width: 674,
					height: 940,
					x: 0,
					y: 0,
					radius: 24,
					lineWidth: 0,
					lineColor: '#ffffff',
					colorObj: {
						colorList: ['#FFFFFF'],
						direction: 2
					}
				},
				{
					type: 'image',
					width: 96,
					height: 96,
					x: 24,
					y: 24,
					radius: 48,
					lineWidth: 2,
					lineColor: '#FFFFFF',
					path: _posterAvatar
				},
				{
					type: 'text',
					width: 400,
					height: 40,
					x: 140,
					y: 42,
					color: '#000000',
					fontSize: 30,
					lineHeight: 30,
					bold: true,
					content: posterUser.nickname
				},
				{
					type: 'image',
					width: 624,
					height: 360,
					x: 24,
					y: 152,
					radius: 12,
					lineWidth: 0,
					lineColor: '#FFFFFF',
					path: _articleCover
				},
				{
					type: 'text',
					width: 626,
					height: 40,
					x: 24,
					y: 562,
					color: '#333333',
					fontSize: 28,
					lineHeight: 28,
					bold: true,
					content: this.result.spec.title
				},
				{
					type: 'text',
					width: 626,
					height: 80,
					x: 24,
					y: 612,
					color: '#333333',
					fontSize: 24,
					lineHeight: 40,
					bold: false,
					content: this.result?.status?.excerpt || '文章暂无摘要信息'
				},
				{
					type: 'line',
					width: 2,
					color: '#999999',
					startX: 24,
					startY: 722,
					endX: 646,
					endY: 722,
					lineType: 'dash'
				},
				{
					type: 'image',
					width: 160,
					height: 160,
					x: 24,
					y: 752,
					radius: 12,
					lineWidth: 0,
					lineColor: '#FFFFFF',
					path: this.getPosterImageUrl(this.$utils.checkImageUrl(_qrCodeImageUrl))
				},
				{
					type: 'text',
					width: 300,
					height: 44,
					x: 320,
					y: 772,
					color: '#333333',
					fontSize: 32,
					lineHeight: 44,
					bold: true,
					content: '长按识别小程序'
				},
				{
					type: 'text',
					width: 442,
					height: 24,
					x: 234,
					y: 872,
					color: '#333333',
					fontSize: 24,
					lineHeight: 24,
					bold: false,
					content: '关注我，给你分享更多有趣的知识'
				}
			];
		},

		// 绘制虚线：https://blog.csdn.net/a460550542/article/details/124821248
		drawDashedLine(ctx, x, y, w, h, pattern, color) {
			ctx.lineWidth = h;
			ctx.strokeStyle = color;
			ctx.beginPath();
			ctx.setLineDash(pattern);
			ctx.moveTo(x, y);
			ctx.lineTo(w, y);
			ctx.stroke();
			y += 20;
		}, 
		fnOnPosterClose() {
			this.poster.show = false;
			this.poster.showCanvas = false;
			this.poster.loading = true;
		},
		fnGetAlbumAuthorize() {
			return new Promise((resolve) => {
				uni.showModal({
					title: '提示',
					content: '保存到相册未授权，是否去授权？',
					success: (res) => {
						if (res.confirm) { 
							uni.openSetting({
								success: (res) => {
									if (res.authSetting['scope.writePhotosAlbum'] === true) {
										uni.showToast({
											icon: 'none',
											title: '授权成功，请重新保存'
										});
										resolve(true);
									} else {
										uni.showToast({
											icon: 'none',
											title: '未进行授权操作'
										});
										resolve(false);
									}
								},
								fail: (err) => {
									uni.showToast({
										icon: 'none',
										title: '授权失败'
									});
									resolve(false);
								}
							});
						}
					},
					fail: () => {
						resolve(false);
					}
				});
			});
		},
		fnCheckAlbumAppAuthorize() {
			return new Promise((resolve) => {
				// #ifdef MP-WEIXIN
				uni.getSetting({
					success: async (res) => {
						if (res.authSetting['scope.writePhotosAlbum'] === false) {
							const authorize = await this.fnGetAlbumAuthorize();
							console.log('authorize1', authorize);
							if (!authorize) {
								uni.openSetting({
									success: (res) => {
										console.log('授权1', res);
									}
								});
								console.log('打开授权页面1');
							}
							resolve(false);
						} else {
							resolve(true);
						}
					},
					fail: async () => {
						const authorize = await this.fnGetAlbumAuthorize();
						console.log('authorize2', authorize);
						if (!authorize) {
							uni.openSetting({
								success: (res) => {
									console.log('授权2', res);
								}
							});
							console.log('打开授权页面2');
						}
						resolve(false);
					}
				});
				// #endif
				// #ifndef MP-WEIXIN
				resolve(true);
				// #endif
			});
		},
		async fnSavePoster() {  
			// 检查授权
			const authorize = await this.fnCheckAlbumAppAuthorize();
			if (!authorize) return;

			uni.saveImageToPhotosAlbum({
				filePath: this.poster.url,
				success: () => {
					uni.$tm.toast('保存成功');
				},
				fail: (e) => {
					if(!e?.errMsg.includes('cancel')){
						uni.$tm.toast('保存失败，请重试');
					}
				}
			});
		},
		fnShareTo() {
			// #ifdef MP-WEIXIN
			uni.$tm.toast('点击右上角分享给好友！');
			// #endif
		},
		fnOnShowCommentDetail(data) {
			const { postName, comment } = data;
			this.commentDetail.comment = comment;
			this.commentDetail.postName = postName;
			this.commentDetail.list = [];
			this.commentDetail.show = true;
			this.fnGetChildComments();
		},
		fnGetChildComments() {
			this.commentDetail.loading = 'loading';
			this.$httpApi.v2
				.getPostCommentReplyList(this.commentDetail.postName, {
					page: 1,
					size: 100
				})
				.then((res) => {
					console.log('getPostChildrenCommentList res', res);
					this.commentDetail.loading = 'success';
					this.commentDetail.list = res.items;
				})
				.catch((err) => {
					console.log('getPostChildrenCommentList err', err);
					this.commentDetail.loading = 'error';
				});
		},
		/**
		 * 标签底色：优先用标签自己的颜色（spec.color），没有就用一层中性底色。
		 *
		 * <p>不给兜底的话，站长手工建的标签（控制台不写 color）会渲染成没有底色的灰字，
		 * 看起来像没加载完。</p>
		 */
		fnTagColor(tag) {
			const color = tag && tag.spec && tag.spec.color;
			return color || 'rgba(102, 126, 234, 0.12)';
		},
		fnToCate(category) {
			uni.navigateTo({
				url: `/pagesA/category-detail/category-detail?name=${category.metadata.name}&title=${category.spec.displayName}`
			});
		},
		fnToTag(tag) {
			uni.navigateTo({
				url: `/pagesA/tag-detail/tag-detail?name=${tag.metadata.name}&title=${tag.spec.displayName}`
			});
		},
		async fnOnCommentLoaded(data) {
			const _list = [];
			const _handleData = (list) => {
				return new Promise((resolve) => {
					if (list.length === 0) {
						resolve();
					} else {
						list.forEach((item) => {
							_list.push(item);
							if (item.replies && item.replies.length !== 0) {
								_handleData(item.replies.items);
							}
							resolve();
						});
					}
				});
			};
			await _handleData(data);
			// if (this.globalAppSettings.barrage.use) {
			// 	this.$nextTick(() => {
			// 		if (_list.length != 0) {
			// 			_handleAddBarrage();
			// 		}
			// 	});
			// }
			// const _handleRemove = () => {
			// 	this.$refs['barrage'] && this.$refs['barrage'].remove({
			// 		duration: 5000, // 延迟关闭的时间
			// 		speed: 600 // 弹幕消失的速度
			// 	});
			// };
			// let index = 0;
			// const _handleAddBarrage = () => {
			// 	setTimeout(() => {
			// 		this.$refs['barrage'] && this.$refs['barrage'].add(_list[index]);
			// 		index += 1;
			// 		if (index < _list.length - 1) {
			// 			_handleAddBarrage();
			// 		} else {
			// 			_handleRemove();
			// 		}
			// 	}, 1000);
			// };
		},
		fnToWebview(data) {
			uni.navigateTo({
				url:
					'/pagesC/website/website?data=' +
					JSON.stringify({
						title: data.title,
						url: encodeURIComponent(data.url)
					})
			});
		},
		fnToOriginal(originalURL) {
			this.fnToWebview({
				title: this.result.title,
				url: originalURL
			});
		},
		readMore() {
			const annotations = this.result?.metadata?.annotations;
			const restrictReadEnable = annotations?.restrictReadEnable;
			if (restrictReadEnable === 'comment') {
				this.fnToComment();
				return;
			} else if (restrictReadEnable === 'login') {
				uni.showToast({
					title: '前往web端登录后访问',
					icon: 'none'
				});
			} else if (restrictReadEnable === 'pay') {
				uni.showToast({
					title: '前往web端支付后访问',
					icon: 'none'
				});
			}
			// 两秒后执行
			setTimeout(() => {
				copyToClipboard(`${HaloTokenConfig.BASE_API + this.result.status.permalink}`);
			}, 2000);
		},
		//   获取openid（作为未登录用户的临时标识）
		fnGetOpenid() {
			// #ifdef MP-WEIXIN
			uni.login({
				success: function (loginRes) {
					try {
						// 如果已通过登录流程获取到 openid，则不再覆盖
						const existingOpenid = uni.getStorageSync('openid');
						if (existingOpenid && existingOpenid.length > 20) {
							return; // 已有真实 openid，不覆盖
						}
						// 未登录时用 code 作为临时标识
						if (loginRes.code) {
							uni.setStorageSync('openid', loginRes.code);
						}
					} catch (error) {
						console.log('[UniHalo] fnGetOpenid error:', error);
					}
				}
			});
			// #endif
		},
		async qrCodeImageUrl() {
			const appInfo = this.haloConfigs?.appConfig?.appInfo || {};
			const staticUrl = appInfo.qrCodeImageUrl || 'https://uni-halo.925i.cn/uni_halo/uni_halo_qq_qrcode.png';
			const useDynamicQRCode = appInfo.useDynamicQRCode;
			if (!useDynamicQRCode) {
				return staticUrl;
			}
			try {
				const qrCodeImg = await this.$httpApi.v2.getQRCodeImg(this.result.metadata.name);
				const url = typeof qrCodeImg === 'string' ? qrCodeImg : (qrCodeImg?.data || qrCodeImg?.imageUrl || '');
				if (url) {
					return url;
				}
				throw new Error('动态二维码返回为空');
			} catch (err) {
				console.warn('[article-detail] 动态二维码获取失败，已回退静态二维码', err?.error || err?.detail || err?.message || err);
				return staticUrl;
			}
		},
		handleQueryCommentListScrollTop() {
			if (!this.postDetailConfig) return;
			if (!this.postDetailConfig.showComment) return;
			this.$nextTick(() => {
				setTimeout(() => {
					uni.createSelectorQuery()
						.in(this)
						.select('#CommentList')
						.boundingClientRect((res) => {
							this.commentListScrollTop = res.top - 12;
						})
						.exec();
				}, 2 * 1000);
			});
		},

		extractDoubanBlockUrls(html) {
			// 匹配<douban src="xxx">格式，仅关注标签和src属性，不限制域名
			const regex = /<douban\s+src="([^"]+)"\s*\/?>/g;
			const urls = [];
			let match;

			while ((match = regex.exec(html)) !== null) {
				// 捕获src属性中双引号内的所有内容（不限制域名）
				urls.push(match[1]);
			}

			return urls;
		},
		/**
		 * 使用阅读券解锁当前付费文章。
		 *
		 * <p>后端幂等：已解锁不会重复扣券。成功后重新拉取文章渲染正文。</p>
		 */
		fnUnlockContent() {
			const lock = this.contentLock;
			const articleName = lock?.articleName || this.queryParams.name;
			if (!articleName) {
				uni.$tm.toast('缺少文章参数，无法解锁');
				return;
			}
			if (this.unlocking) return;
			if (!this.calcLockIsLogin) {
				// 记录「登录后要继续解锁」，登录成功回调会重新拉取锁定态。
				// 不会自动扣券：解锁是消费行为，需用户登录后再点一次确认。
				this.pendingLockUnlock = true;
				this.wxLoginDialogShow = true;
				return;
			}
			if (this.calcLockTickets < this.calcLockCostTickets) {
				uni.$tm.toast(`阅读券不足，还需 ${this.calcLockCostTickets - this.calcLockTickets} 张`);
				return;
			}
			this.unlocking = true;
			uni.showLoading({ title: '解锁中...' });
			this.$httpApi.v2
				.unlockContent(articleName)
				.then(() => {
					// 后端返回解锁后的 ContentLockedDTO（含剩余券数）。
					// 这里不回写卡片券数：紧接着就会重新拉取文章，
					// 锁定卡片会被正文替换，且回写前 contentLock 可能已被置空。
					uni.$tm.toast('解锁成功');
					this.fnGetData();
				})
				.catch((err) => {
					console.error('[article-detail] 解锁失败', err);
					// 网络失败时 detail 是原始错误对象，不能直接当文案展示
					uni.$tm.toast(err?.error || err?.message || '解锁失败，请稍后重试');
				})
				.finally(() => {
					uni.hideLoading();
					this.unlocking = false;
				});
		},

		/**
		 * 拉取「积分兑换阅读券」的兑换项。
		 *
		 * 只认 key=content_ticket：付费内容开启时后端会自动补一条内置兑换项，
		 * 因此站长即使忘了在积分商城配置，这里也能拿到。失败静默——
		 * 兑换入口只是补充路径，拿不到不影响「去获取」按钮。
		 */
		async fnLoadTicketExchange() {
			try {
				const status = await fetchExchangeBalance(this.$httpApi.v2);
				if (!status.ok) {
					this.ticketExchange = null;
					return;
				}
				this.ticketExchange = (status.items || [])
					.find((entry) => entry.key === 'content_ticket') || null;
			} catch (err) {
				this.ticketExchange = null;
			}
		},
		/**
		 * 拉取会员状态（仅用于锁定卡上的引导）。
		 *
		 * <p>会员身份不在这里做任何放行判断——放行完全由服务端决定，
		 * 前端状态只影响文案，避免两处口径不一致导致「页面说我是会员但文章还是锁的」。</p>
		 */
		async fnLoadMembership() {
			try {
				const res = await this.$httpApi.v2.getMembershipStatus();
				const body = res?.data || res || {};
				if (body && typeof body === 'object' && !body.error) {
					this.membership = {
						enabled: body.enabled === true,
						active: body.active === true,
						freeContent: body.freeContent === true,
						bonusTickets: Number(body.bonusTickets || 0)
					};
				}
			} catch (err) {
				// 静默：拿不到会员状态只是少一条引导，不影响解锁
			}
		},
		/**
		 * 用积分兑换阅读券。
		 *
		 * 兑换成功后不自动扣券解锁：解锁是消费行为，让用户看着新的券余额再点一次确认，
		 * 避免「我只是想换张券，怎么文章直接被解锁了」的意外扣费感。
		 */
		async fnExchangeTicketForArticle() {
			if (!this.ticketExchange || this.exchangingTicket) return;
			this.exchangingTicket = true;
			uni.showLoading({ title: '兑换中...' });
			try {
				const result = await exchangePointsItem(this.$httpApi.v2, 'content_ticket');
				if (!result.ok) {
					uni.showToast({ title: result.reason || '兑换失败，请稍后重试', icon: 'none' });
					return;
				}
				uni.showToast({ title: result.message || '兑换成功', icon: 'success' });
				// 重新拉取锁定态：券余额与今日兑换次数都会变，按钮随之切到「使用阅读券解锁」
				await this.fnLoadTicketExchange();
				this.fnGetData();
			} catch (err) {
				uni.showToast({ title: normalizePointsApiError(err), icon: 'none' });
			} finally {
				uni.hideLoading();
				this.exchangingTicket = false;
			}
		},
		/** 阅读券不足：去积分充值页获取（可兑换或购买阅读券） */
		fnGoGetTickets() {
			if (!this.calcLockIsLogin) {
				// 未登录无法查询券余额，先登录再回来复查
				this.pendingLockUnlock = true;
				this.wxLoginDialogShow = true;
				return;
			}
			// 标记待复查：从充值页返回后 onShow 会重新拉取锁定态，
			// 让新到账的阅读券立刻反映在卡片上
			this._needRecheckLock = true;
			uni.navigateTo({ url: '/pagesA/points-recharge/points-recharge' });
		},

		// ── 订阅墙（分类未订阅）──

		/** 进入订阅墙锁定态时取一次订阅状态：按钮出口由额度决定（可订阅 / 已满 / 已暂停） */
		async fnPrepareSubscriptionLock() {
			this.lockSubscription = await fetchCategorySubscription(this.$httpApi.v2);
		},
		fnLoginForSubscribe() {
			// 登录成功后重进锁定卡片，按钮从「去登录」变成「立即订阅」；
			// 刻意不自动订阅——订阅是用户自己的选择，登录只负责把门打开
			this.pendingSubscribeLogin = true;
			this.wxLoginDialogShow = true;
		},
		/** 锁定卡片上点「立即订阅」：订阅成功即重拉正文（fnGetData 会重置锁定态） */
		async fnSubscribeLockedCategory() {
			const categoryName = this.contentLock?.categories?.[0]?.name;
			if (!categoryName || this.subscribing) {
				return;
			}
			this.subscribing = true;
			try {
				const result = await subscribeCategory(this.$httpApi.v2, categoryName);
				if (result.ok) {
					uni.showToast({ title: '订阅成功', icon: 'success' });
					this.fnGetData();
					return;
				}
				if (result.reason === 'not-logged-in') {
					this.fnLoginForSubscribe();
					return;
				}
				if (result.reason === 'quota-full') {
					this.fnOpenLockUnlockSheet();
					return;
				}
				uni.showToast({ title: result.reason || '订阅失败，请稍后重试', icon: 'none' });
			} finally {
				this.subscribing = false;
			}
		},
		/** 额度已满 / 已暂停：摆出「怎么多订」的两个出口，不让用户自己去别处找 */
		fnOpenLockUnlockSheet() {
			this.lockUnlockSheetShow = true;
		},
		fnOnLockUnlockAction(e) {
			const index = e?.index ?? -1;
			if (index === 0) {
				this.fnUnlockQuotaWithPoints();
				return;
			}
			if (index === 1) {
				// 充值页回来后 onShow 复查锁定态（_needRecheckLock 约定）：会员开通/续费后墙自动放行
				this._needRecheckLock = true;
				uni.navigateTo({
					url: '/pagesA/points-recharge/points-recharge',
					fail: () => uni.showToast({ title: '打开权益直购页失败', icon: 'none' })
				});
			}
		},
		/** 积分解锁一份额度，成功后顺手把本文的分类订阅上，一步到位 */
		async fnUnlockQuotaWithPoints() {
			if (this.subscribing) {
				return;
			}
			this.subscribing = true;
			try {
				const res = await this.$httpApi.v2.purchaseSubscribeQuota(1);
				const body = res?.data || res || {};
				if (body.error) {
					uni.showToast({ title: body.error, icon: 'none' });
					return;
				}
				// 积分余额变了：我的页/身份页靠这个事件同步（与其它扣分链路同一约定）
				if (body.balance != null) {
					uni.$emit('points_updated', { balance: body.balance });
				}
				// 解锁后额度变了：以服务端回读为准刷新卡片按钮
				this.lockSubscription = await fetchCategorySubscription(this.$httpApi.v2);
				const categoryName = this.contentLock?.categories?.[0]?.name;
				if (!categoryName) {
					uni.showToast({ title: body.message || '已解锁一份订阅额度', icon: 'none' });
					return;
				}
				const result = await subscribeCategory(this.$httpApi.v2, categoryName);
				if (result.ok) {
					uni.showToast({ title: '订阅成功', icon: 'success' });
					this.fnGetData();
					return;
				}
				uni.showToast({ title: result.reason || '订阅失败，请稍后重试', icon: 'none' });
			} catch (err) {
				uni.showToast({
					title: err?.error || err?.detail || '解锁失败，请稍后重试',
					icon: 'none'
				});
			} finally {
				this.subscribing = false;
			}
		},
		handlePreview(index, list) {
			uni.previewImage({
				current: index,
				urls: list.map((item) => item.url)
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
	box-sizing: border-box;
	background-color: $uh-bg;
	padding-bottom: 24rpx;
}

.bg-image {
	position: fixed;
	left: 0;
	top: 0;
	right: 0;
	bottom: 0;
	width: 100vw;
	height: 100vh;
	z-index: 0;
}

.load-error {
	min-height: 60vh;
	padding: 48rpx 24rpx;
	box-sizing: border-box;
}

.loading-wrap {
	padding: 0 24rpx;
	height: inherit;
	background-color: $uh-card;
}

.head {
	display: flex;
	flex-direction: column;
	align-items: center;
	justify-content: center;
	padding: 40rpx 32rpx;
	background-color: $uh-card;
	box-shadow: $uh-shadow-sm;
	border-radius: $uh-radius-lg;

	/* 杂志编辑风：衬线大标题，左对齐（B 方向只在文章详情落地） */
	.title {
		width: 100%;
		font-family: $uh-font-serif;
		font-size: 44rpx;
		font-weight: 700;
		line-height: 1.5;
		letter-spacing: 1rpx;
		text-align: left;
		color: $uh-ink-1;
	}

	.detail {
		width: 100%;
		margin-top: 24rpx;
		font-size: 26rpx;

		.author {
			text-align: left;
			font-size: 24rpx;
			color: $uh-ink-3;

			&-name {
			}

			&-time {
				margin-left: 36rpx;
			}
		}

		.cover {
			margin-top: 24rpx;
			width: 100%;
			height: 280rpx;

			&-img {
				width: 100%;
				height: 100%;
				border-radius: $uh-radius-md;
			}
		}

		.count {
			margin-top: 24rpx;
			display: flex;
			justify-content: space-between;

			&.no-thumbnail {
				border-top: 2rpx solid $uh-line;
				padding-top: 12rpx;

				.count-item {
					position: relative;
					color: $uh-ink-3;

					&::after {
						content: '';
						position: absolute;
						right: 0;
						background-color: $uh-line;
						width: 2rpx;
						height: 32rpx;
					}

					&:last-child {
						&::after {
							display: none;
						}
					}
				}
			}

			&-item {
				flex: 1;
				display: flex;
				align-items: flex-end;
				justify-content: center;
				color: $uh-ink-3;

				&.is-upvoted {
					color: $uh-danger;

					.value {
						font-weight: 600;
					}
				}

				.label {
					font-size: 24rpx;
					padding-left: 8rpx;
				}

				.value {
					font-size: 32rpx;
				}
			}
		}
	}
}

.locked-member {
	display: flex;
	align-items: center;
	justify-content: space-between;
	padding: 18rpx 20rpx;
	border-radius: 12rpx;
	background: linear-gradient(135deg, #2c2f4a 0%, #4a3f6b 100%);
}

.locked-member-main {
	flex: 1;
	margin-right: 16rpx;
}

.locked-member-title {
	display: block;
	font-size: 24rpx;
	font-weight: 600;
	color: #f7d774;
}

.locked-member-desc {
	display: block;
	margin-top: 4rpx;
	font-size: 20rpx;
	color: rgba(255, 255, 255, 0.78);
}

.locked-member-link {
	font-size: 22rpx;
	color: #f7d774;
}

.locked-exchange {
	display: flex;
	align-items: center;
	justify-content: space-between;
	padding: 18rpx 20rpx;
	border-radius: $uh-radius-md;
	background: $uh-accent-weak;
}

.locked-exchange-text {
	flex: 1;
	margin-right: 16rpx;
	font-size: 22rpx;
	color: $uh-ink-2;
}

.locked-exchange-warn {
	color: $uh-warning;
}

.content-locked {
	background-color: $uh-card;
	box-shadow: $uh-shadow-sm;

	.locked-title {
		font-family: $uh-font-serif;
		font-size: 40rpx;
		font-weight: 700;
		line-height: 1.5;
		text-align: center;
		color: $uh-ink-1;
	}

	.locked-cover {
		width: 100%;
		height: 280rpx;
		border-radius: $uh-radius-md;
	}

	.locked-excerpt {
		font-size: 28rpx;
		line-height: 1.8;
		color: $uh-ink-2;
	}

	.locked-tip {
		padding: 20rpx 24rpx;
		background-color: $uh-warning-weak;
		border-radius: $uh-radius-sm;
		font-size: 26rpx;
		color: $uh-warning;
		text-align: center;
	}

	.locked-balance {
		display: flex;
		justify-content: space-between;
		padding: 0 24rpx;
		font-size: 24rpx;
		color: $uh-ink-3;
	}

	.locked-actions {
		padding: 0 24rpx;
	}
}

.category {
	margin: 0 24rpx;
	padding: 24rpx;
	background-color: $uh-card;
	border-radius: $uh-radius-lg;
	box-shadow: $uh-shadow-sm;
	font-size: 28rpx;

	&-type {
		line-height: 55rpx;
	}

	&-tag {
		background-color: $uh-accent;
		color: #fff;
		padding: 6rpx 12rpx;
		border-radius: 8rpx;
		font-size: 24rpx;

		&.is-empty {
			background-color: $uh-ink-3;
		}
	}
}

.category-tag + .category-tag {
	margin-left: 12rpx;
}

.content {
	margin-top: 24rpx;
}

.markdown-wrap,
.evan-markdown,
.ad-wrap,
.copyright-wrap,
.comment-wrap {
	box-shadow: $uh-shadow-sm;
}

.vote-wrap {
	position: relative;
}

.vote-opra {
	font-size: 24rpx;
	font-weight: normal;
	color: $uh-ink-3;
}

.vote-tip {
	margin-top: 12rpx;
	padding: 50rpx 24rpx;
	background: $uh-fill;
	color: $uh-ink-2;
	font-size: 24rpx;
	border-radius: $uh-radius-md;
	text-align: center;
}

.markdown-wrap {
	overflow: hidden;
	border-radius: $uh-radius-lg;
}

.browse-points-float {
	position: fixed;
	left: 32rpx;
	bottom: calc(200rpx + env(safe-area-inset-bottom));
	z-index: 996;
	pointer-events: none;
}

.browse-points-float__ring {
	width: 108rpx;
	height: 108rpx;
	border-radius: 50%;
	padding: 6rpx;
	box-sizing: border-box;
	background: linear-gradient(145deg, #ffb300 0%, #ff9100 100%);
	box-shadow: 0 8rpx 24rpx rgba(255, 145, 0, 0.32);
}

.browse-points-float.is-counting .browse-points-float__ring {
	animation: browse-points-float-breathe 1.8s ease-in-out infinite;
}

.browse-points-float.is-done .browse-points-float__ring {
	background: linear-gradient(145deg, #66bb6a 0%, #43a047 100%);
	box-shadow: 0 8rpx 24rpx rgba(67, 160, 71, 0.28);
	animation: none;
}

.browse-points-float__inner {
	width: 100%;
	height: 100%;
	border-radius: 50%;
	background: #ffffff;
	display: flex;
	flex-direction: column;
	align-items: center;
	justify-content: center;
}

.browse-points-float__num {
	font-size: 38rpx;
	font-weight: 700;
	color: #ef6c00;
	line-height: 1;
}

.browse-points-float__check {
	font-size: 36rpx;
	font-weight: 700;
	color: #43a047;
	line-height: 1;
}

.browse-points-float__caption {
	margin-top: 4rpx;
	font-size: 20rpx;
	color: $uh-ink-3;
	line-height: 1;
}

.browse-points-float.is-counting .browse-points-float__caption {
	color: #f57c00;
}

.browse-points-float.is-done .browse-points-float__caption {
	color: #43a047;
}

@keyframes browse-points-float-breathe {
	0%, 100% {
		transform: scale(1);
	}
	50% {
		transform: scale(1.05);
	}
}

.copyright-title {
	position: relative;
	box-sizing: border-box;
	padding-left: 24rpx;
	font-size: 30rpx;

	&:before {
		content: '';
		position: absolute;
		left: 0rpx;
		top: 8rpx;
		width: 8rpx;
		height: 26rpx;
		background-color: $uh-accent;
		border-radius: 6rpx;
	}
}

.poup-head {
	border-bottom: 2rpx solid $uh-line;
}

.poup-body {
	height: 50vh;
}

.poup-empty {
	width: 100%;
	height: 40vh;
}

.poup-loading-wrap {
	width: 100%;
	height: 40vh;

	.e-loading-icon {
		font-size: 80rpx;
	}
}

.poster-content {
	width: 100%;
	min-height: 60vh;
	overflow: hidden;
}

.copyright-text {
	line-height: 1.7;
}

.poster-loading {
	width: 100%;
	position: absolute;
	left: 0;
	top: 0;
	right: 0;
	bottom: 0;
	// background-color: rgba(0, 0, 0, 0.1);
	z-index: 666;
}

.poster-save {
	box-sizing: border-box;
	border-top: 2rpx dashed $uh-line;
}

.original-url {
	display: flex;

	&_left {
		flex-shrink: 0;
		width: 84rpx;
	}

	&_right {
		flex-grow: 1;
		display: inline-flex;
		align-items: center;

		&__link {
			width: 410rpx;
			display: inline-block;
			overflow: hidden;
			text-overflow: ellipsis;
			white-space: nowrap;
		}

		&__btn {
			flex-grow: 1;
			flex-shrink: 0;
			display: block;
			text-align: right;
		}
	}
}

	/* ===== 上一篇 / 下一篇（正文下方左右两栏） ===== */
	.post-nav {
		display: flex;
		align-items: stretch;
		box-sizing: border-box;
		overflow: hidden;
	}

	.post-nav_cell {
		flex: 1;
		min-width: 0;
		box-sizing: border-box;
		padding: 20rpx 24rpx;
	}

	/* 整块是热区，按下时给一层浅底；两侧各自反馈，用户能明确知道点的是哪一边 */
	.post-nav_cell--pressed {
		background-color: $uh-fill;
	}

	.post-nav_divider {
		width: 1rpx;
		background-color: $uh-line;
	}

	.post-nav_label {
		display: block;
		font-size: 22rpx;
		color: $uh-accent;
	}

	/* 标题单行省略：超出部分显示 ...（text-sub-one 提供 ellipsis），
	   右侧栏整体右对齐，视觉上像「往右翻」 */
	.post-nav_title {
		display: block;
		margin-top: 10rpx;
		font-size: 26rpx;
		color: $uh-ink-1;
	}

	.post-nav_cell--right {
		text-align: right;
	}

	/* ===== 猜你喜欢（紧凑标题列表） ===== */
	.related-wrap {
		box-sizing: border-box;
	}

	.related-head {
		font-size: 28rpx;
		color: $uh-ink-1;
	}

	.related-item {
		display: flex;
		align-items: center;
		/* 单行标题：行高压到 12rpx 内边距，5 条加起来只占一百多 rpx */
		padding: 12rpx 0;
	}

	/* 文字行的按下反馈用透明度，比整行变色更轻，也不会和卡片底色打架 */
	.related-item--pressed {
		opacity: 0.55;
	}

	.related-item_index {
		flex-shrink: 0;
		width: 32rpx;
		margin-right: 8rpx;
		font-size: 22rpx;
		color: $uh-ink-4;
	}

	.related-item_title {
		flex: 1;
		min-width: 0;
		font-size: 26rpx;
		color: $uh-ink-2;
	}

</style>
