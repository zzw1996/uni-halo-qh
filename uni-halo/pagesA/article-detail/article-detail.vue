<template>
	<view class="app-page">
		<view v-if="loading === 'error'" class="load-error flex flex-center flex-col">
			<tm-empty icon="icon-wind-cry" :label="loadErrorMsg || '文章加载失败'"></tm-empty>
			<tm-button class="mt-24" size="m" theme="light-blue" @click="fnGetData">重新加载</tm-button>
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
						<text class="category-tag" :style="{ backgroundColor: item.color }" v-for="(item, index) in result.tags" :key="index" @click="fnToTag(item)">
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
						<view v-if="showContentArr.length == 0">
							<restrict-read-skeleton
								:loading="true"
								:lines="3"
								:tip-text="`此处内容已隐藏，「${getRestrictReadTypeName(result)}可见」`"
								:button-text="`${getRestrictReadTypeName(result)}`"
								button-color="#1890ff"
								skeleton-color="#f0f0f0"
								skeleton-highlight="#e0e0e0"
								animation-duration="2"
								@refresh="readMore"
							/>
						</view>
						<view v-else v-for="showContent in showContentArr">
							<mp-html
								class="evan-markdown"
								lazy-load
								:domain="markdownConfig.domain"
								:loading-img="markdownConfig.loadingGif"
								:scroll-table="true"
								:selectable="true"
								:tag-style="markdownConfig.tagStyle"
								:container-style="markdownConfig.containStyle"
								:content="showContent"
								:markdown="true"
								:showLineNumber="true"
								:showLanguageName="true"
								:copyByLongPress="true"
							/>
							<restrict-read-skeleton
								:loading="true"
								:lines="3"
								:tip-text="`此处内容已隐藏，「${getRestrictReadTypeName(result)}可见」`"
								:button-text="`${getRestrictReadTypeName(result)}`"
								button-color="#1890ff"
								skeleton-color="#f0f0f0"
								skeleton-highlight="#e0e0e0"
								animation-duration="2"
								@refresh="readMore"
							/>
						</view>
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

				<!-- 投票 -->
				<view v-if="!calcAuditModeEnabled && result._voteIds.length !== 0" class="vote-wrap copyright-wrap bg-white mt-24 pa-24 round-4">
					<view class="copyright-title text-weight-b flex items-center justify-between">
						<text>相关投票</text>
						<text class="vote-opra" @click="voteIsOpen = !voteIsOpen">
							{{ voteIsOpen ? '收起' : '展开' }}
						</text>
					</view>
					<template v-if="reloadVote">
						<view v-show="voteIsOpen" class="flex flex-col uh-gap-8 uh-mt-8">
							<PluginUnavailable
								v-if="!uniHaloPluginAvailable"
								:pluginId="uniHaloPluginId"
								:error-text="uniHaloPluginAvailableError"
								:useDecoration="false"
								:customStyle="{
									width: '100%',
									borderRadius: '16rpx'
								}"
							/>
							<template v-else>
								<ArticleVote v-for="(voteId, voteIdIndex) in result._voteIds" :key="voteId" :voteId="voteId" :article="result" :index="voteIdIndex"></ArticleVote>
							</template>
						</view>
						<view v-show="uniHaloPluginAvailable && !voteIsOpen" class="vote-tip" @click="voteIsOpen = !voteIsOpen">
							投票已收起，点击展开 {{ result._voteIds.length }} 个投票项
						</view>
						<view v-show="!uniHaloPluginAvailable && !voteIsOpen" class="vote-tip" @click="voteIsOpen = !voteIsOpen">提示区域已收起，点击显示</view>
					</template>
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
			<tm-flotbutton :width="90" :offset="[16, 80]" icon="icon-angle-up" color="bg-gradient-light-blue-accent" @click="fnToTopPage()"></tm-flotbutton>
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
								<tm-button theme="bg-gradient-light-blue-accent" size="m" @click="fnGetChildComments()">刷新试试</tm-button>
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
						<tm-button theme="bg-gradient-light-blue-accent" size="m" @click="fnSavePoster()">保存到相册</tm-button>
						<tm-button v-if="false" theme="bg-gradient-orange-accent" size="m" @click="fnShareTo()">分享给好友</tm-button>
						<tm-button theme="bg-gradient-blue-grey-accent" size="m" @click="fnOnPosterClose()">关 闭</tm-button>
					</view>
				</block>
			</view>
		</tm-poup>

		<!-- 验证码弹窗 -->
		<tm-dialog
			v-model="verificationCodeModal.show"
			:disabled="true"
			title="验证提示"
			:confirmText="verificationCodeModal.confirmText"
			:showCancel="true"
			model="verificationCodeModal.model"
			theme="split"
			confirmColor="blue shadow-blue-0"
			@cancel="verificationCodeModal.show = false"
			@confirm="restrictReadCheckOrViewVideo"
		>
			<template #default>
				<view class="pa-20">
					<!-- 扫码验证模式 -->
					<view v-if="verificationCodeModal.type === 'scan'" class="flex flex-col flex-center">
						<text class="mb-20">请扫描下方二维码获取验证码</text>
						<tm-images :width="180" :height="180" :src="verificationCodeModal.imgUrl" preview round="5"></tm-images>
						<tm-input bg-color="grey-lighten-5" required v-model="restrictReadInputCode" placeholder="请输入验证码" :border-bottom="false" :flat="true"></tm-input>
					</view>

					<!-- 观看视频模式 -->
					<view v-else class="flex flex-col flex-center">
						<text class="mb-20">点击观看视频之后，可访问</text>
					</view>
				</view>
			</template>
		</tm-dialog>
		<!-- 密码弹窗 -->
		<tm-dialog
			v-model="passwordModal.show"
			:disabled="true"
			title="验证提示"
			confirmText="确定"
			content="请输入密码"
			:showCancel="true"
			model="confirm"
			theme="split"
			confirmColor="blue shadow-blue-0"
			:input-val.sync="restrictReadInputCode"
			@cancel="passwordModal.show = false"
			@confirm="restrictReadCheck"
		></tm-dialog>

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

		<!-- 微信登录弹窗（评论 / 海报 / 点赞） -->
		<wx-login-dialog
			:show.sync="wxLoginDialogShow"
			:title="wxLoginDialogTitle"
			:desc="wxLoginDialogDesc"
			@login-ready="fnOnWxLoginReady"
			@success="fnOnWxLoginSuccess"
			@close="fnOnWxLoginClose"
		></wx-login-dialog>
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
import PointsToast from '@/components/points-toast/points-toast.vue';
import ArticleVote from '@/components/article-vote/article-vote.vue';
import ArticleDouban from '@/components/article-douban/article-douban.vue';
import contentReactionBar from '@/components/content-reaction-bar/content-reaction-bar.vue';
 
import barrage from '@/components/barrage/barrage.vue';
import { getAppConfigs } from '@/config/index.js';
import { checkHasWxLogin, getOpenid } from '@/utils/auth.js';
import { getCommentAuthorInfo } from '@/utils/comment.js';
import { isWxLoginRequired } from '@/utils/wxLogin.js';
import { upvote } from '@/utils/upvote.js';
import { recordUserActivity, removeUserActivity, syncArticleUpvoteStatus, USER_ACTIVITY_TYPES } from '@/utils/userActivity.js';
import { getPointsConfig, isPointsEnabled, tryEarnPointsByAction, checkPointsEarnStatus, POINTS_ACTIONS } from '@/utils/points.js';
import { getDomainOnly } from '@/utils/url.params.js'
import { checkPostRestrictRead, copyToClipboard, getRestrictReadTypeName, getShowableContent } from '@/utils/restrictRead';
import HaloTokenConfig from '@/config/uhalo.config';
import RestrictReadSkeleton from '@/components/restrict-read-skeleton/restrict-read-skeleton.vue';
import TmImages from '@/tm-vuetify/components/tm-images/tm-images.vue';
import TmInput from '@/tm-vuetify/components/tm-input/tm-input.vue';
import pluginAvailableMixin from '@/common/mixins/pluginAvailable.js';
import PluginUnavailable from '@/components/plugin-unavailable/plugin-unavailable.vue';

let videoAd = null;
export default {
	mixins: [pluginAvailableMixin],
	components: {
		TmInput,
		TmImages,
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
		PointsToast,
		ArticleVote,
		contentReactionBar,
		PluginUnavailable,
		ArticleDouban
	},
	data() {
		return {
			loading: 'loading',
			loadErrorMsg: '',
			markdownConfig: MarkdownConfig,

			flotButtonActions: [],
			queryParams: {
				name: null
			},
			result: null,

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
			showContentArr: [],
			restrictReadInputCode: '',
			verificationCodeModal: {
				show: false,
				model: 'confirm',
				confirmText: '确定',
				type: '',
				imgUrl: '',
				adId: ''
			},
			passwordModal: {
				show: false
			},
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

			voteIsOpen: true,
			reloadVote: false,
			doubanIsOpen: true,

			appAuthorize: {
				writePhotosAlbum: ''
			},
			browsePointsRemainSec: 0,
			browsePointsCounting: false,
			browsePointsEarnedToday: false,
			_browsePointsTimer: null,
			_browsePointsInterval: null
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
			return '登录后评论';
		},
		wxLoginDialogDesc() {
			if (this.pendingPosterShare) return '生成分享海报需要先完成微信授权登录';
			if (this.pendingLike) return '点赞需要先完成微信授权登录';
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
		// 获取博主信息
		bloggerInfo() {
			const blogger = this.$tm.vx.getters().getConfigs.authorConfig.blogger;
			blogger.avatar = this.$utils.checkAvatarUrl(blogger.avatar, true);
			return blogger;
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
		pluginsConfig() {
			return this.haloConfigs.pluginConfig;
		},
		doubanPluginConfig() {
			return this.pluginsConfig.doubanPlugin;
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
		// 文章加载优先，投票插件检查不阻塞
		this.setPluginId(this.NeedPluginIds.PluginVote);
		this.setPluginError('阿偶，检测到当前插件没有安装或者启用，无法使用投票功能，请联系管理员');
		this.checkPluginAvailable();
		this.fnGetData();
	},
	onShow() {
		if (this.reloadVote) {
			this.reloadVote = false;
			setTimeout(() => {
				this.reloadVote = true;
			}, 100);
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
		fnGetData() {
			const name = (this.queryParams.name || '').trim();
			if (!name) {
				this.loading = 'error';
				this.loadErrorMsg = '缺少文章参数';
				return;
			}
			this.loading = 'loading';
			this.loadErrorMsg = '';
			console.log('[article-detail] 请求文章:', name);
			this.$httpApi.v2
				.getPostByName(name)
				.then((res) => {
					console.log('详情', res);
					const tempResult = res;
					tempResult._voteIds = this.extractVoteBlockIds(res.content?.raw || res.content?.content || '');
					tempResult._doubanUrls = this.extractDoubanBlockUrls(res.content?.raw || res.content?.content || '');

					const openid = uni.getStorageSync('openid');
					if (openid === '' || openid === null) {
						this.fnGetOpenid();
					}

					const toolsPluginEnabled = getAppConfigs().pluginConfig.toolsPlugin?.enabled;
					const restrictRead = checkPostRestrictRead(tempResult);

					if (restrictRead && toolsPluginEnabled) {
						let verifyCodeType = getAppConfigs().pluginConfig.toolsPlugin?.verifyCodeType;
						const postVerifyCodeType = tempResult?.metadata?.annotations?.verifyCodeType;
						if (postVerifyCodeType) {
							verifyCodeType = postVerifyCodeType;
						}
						if (verifyCodeType === 'scan') {
							const scanCodeUrl = getAppConfigs().pluginConfig.toolsPlugin?.scanCodeUrl;
							this.verificationCodeModal.type = 'scan';
							this.verificationCodeModal.imgUrl = this.$utils.checkImageUrl(scanCodeUrl);
							this.verificationCodeModal.model = 'confirm';
							this.verificationCodeModal.confirmText = '立即验证';
						} else if (verifyCodeType === 'advert') {
							const rewardedVideoAdId = getAppConfigs().pluginConfig.toolsPlugin?.rewardedVideoAdId;
							this.verificationCodeModal.type = 'advert';
							this.verificationCodeModal.adId = rewardedVideoAdId;
							this.verificationCodeModal.model = 'dialog';
							this.verificationCodeModal.confirmText = '观看视频';
							// #ifdef MP-WEIXIN
							this.adLoad();
							// #endif
						}

						const showableContentArr = getShowableContent(tempResult);
						this.showContentArr = showableContentArr;
					}
					this.result = tempResult;
					this.fnSetPageTitle('文章详情');
					this.loading = 'success';
					this.reloadVote = true;
					this.fnSyncUpvoteStatus();
					this.fnHandleSetFlotButtonItems(this.haloConfigs);
					this.handleQueryCommentListScrollTop();
					this.fnTrackersCounter();
				})
				.catch((err) => {
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
					type: 'text',
					width: 400,
					height: 40,
					x: 140,
					y: 90,
					color: '#666666',
					fontSize: 24,
					lineHeight: 24,
					bold: false,
					content: this.bloggerInfo.description
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
			if (restrictReadEnable === 'password') {
				this.passwordModal.show = true;
				return;
			} else if (restrictReadEnable === 'code') {
				this.verificationCodeModal.show = true;
				return;
			} else if (restrictReadEnable === 'comment') {
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
		restrictReadCheckOrViewVideo() {
			console.log('restrictReadCheckOrViewVideo', this.verificationCodeModal.type);
			if (this.verificationCodeModal.type === 'advert') {
				this.openVideoAd();
			} else {
				this.restrictReadCheck();
			}
		},
		//   校验密码
		restrictReadCheck() {
			if (!this.restrictReadInputCode) {
				uni.showToast({
					title: '请输入内容',
					icon: 'none'
				});
				return;
			}
			this.$httpApi.v2
				.requestRestrictReadCheck(this.result?.metadata?.annotations?.restrictReadEnable, this.restrictReadInputCode, this.result?.metadata?.name)
				.then((res) => {
					if (res.code === 200) {
						this.passwordModal.show = false;
						this.verificationCodeModal.show = false;
						this.fnGetData();
					} else {
						uni.showToast({
							title: '密码错误',
							icon: 'none'
						});
					}
				})
				.catch((err) => {
					console.error(err);
				});
		},
		adLoad() {
			if (wx.createRewardedVideoAd) {
				videoAd = wx.createRewardedVideoAd({
					adUnitId: this.verificationCodeModal.adId
				});
				videoAd.onError((err) => {});
				videoAd.onClose((status) => {
					if ((status && status.isEnded) || status === undefined) {
						//这里写广告播放完成后的事件
						this.getVerificationCode();
					} else {
						// 广告播放未完成
					}
				});
			}
		},
		openVideoAd: function () {
			if (videoAd && this.verificationCodeModal.adId !== '') {
				videoAd.show().catch((err) => {
					// 失败重试
					console.log('广告拉取失败');
					videoAd.load().then(() => videoAd.show());
				});
			} else {
				this.getVerificationCode();
			}
		},
		getVerificationCode() {
			uni.showLoading({
				title: '正在获取...'
			});
			this.$httpApi.v2
				.createVerificationCode()
				.then((res) => {
					if (res.code === 200) {
						this.verificationCodeModal.show = false;
						this.restrictReadInputCode = res.data;
						this.restrictReadCheck();
					} else {
						uni.$tm.toast('操作失败，请重试！');
					}
				})
				.catch((err) => {
					uni.$tm.toast(err.message);
				});
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

		/**
		 * 从HTML字符串中提取所有vote-block的id
		 * @param {string} html - 包含vote-block的HTML字符串
		 * @returns {string[]} 提取到的id数组
		 */
		extractVoteBlockIds(html) {
			// 正则表达式匹配<vote-block id="xxx">格式
			// 捕获组提取id值
			const regex = /<vote-block\s+id="(vote-\w+)"\s*\/?>/g;
			const ids = [];
			let match;

			// 循环匹配所有符合条件的id
			while ((match = regex.exec(html)) !== null) {
				// 第一个捕获组是id值
				ids.push(match[1]);
			}

			return ids;
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
	background-color: #fafafd;
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
	background-color: #fff;
}

.head {
	display: flex;
	flex-direction: column;
	align-items: center;
	justify-content: center;
	padding: 36rpx 24rpx;
	background-color: #ffffff;
	box-shadow: 0rpx 4rpx 24rpx rgba(0, 0, 0, 0.03);
	// box-shadow: 0rpx 6rpx 30rpx rgba(182, 223, 255, 0.3);
	border-radius: 12rpx;

	.title {
		font-size: 36rpx;
		font-weight: 600;
		text-align: center;
	}

	.detail {
		width: 100%;
		margin-top: 24rpx;
		font-size: 26rpx;

		.author {
			text-align: center;
			font-size: 24rpx;
			color: #666;

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
				border-radius: 12rpx;
			}
		}

		.count {
			margin-top: 24rpx;
			display: flex;
			justify-content: space-between;

			&.no-thumbnail {
				border-top: 2rpx solid #f2f2f2;
				padding-top: 12rpx;

				.count-item {
					position: relative;
					color: #666;

					&::after {
						content: '';
						position: absolute;
						right: 0;
						background-color: #eee;
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
				color: #666;

				&.is-upvoted {
					color: #e53935;

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

.category {
	margin: 0 24rpx;
	padding: 24rpx;
	background-color: #ffffff;
	border-radius: 12rpx;
	// box-shadow: 0rpx 0rpx 24rpx rgba(182, 223, 255, 0.3);
	box-shadow: 0rpx 4rpx 24rpx rgba(0, 0, 0, 0.03);
	// border: 2rpx solid #f8f8f8;
	font-size: 28rpx;

	&-type {
		line-height: 55rpx;
	}

	&-tag {
		background-color: #5bb8fa;
		color: #fff;
		padding: 6rpx 12rpx;
		border-radius: 6rpx;
		font-size: 24rpx;

		&.is-empty {
			background-color: #607d8b;
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
	box-shadow: 0rpx 0rpx 24rpx rgba(0, 0, 0, 0.03);
}

.vote-wrap {
	position: relative;
}

.vote-opra {
	font-size: 24rpx;
	font-weight: normal;
	color: #999;
}

.vote-tip {
	margin-top: 12rpx;
	padding: 50rpx 24rpx;
	background: #f1f5f9;
	color: #666;
	font-size: 24rpx;
	border-radius: 12rpx;
	text-align: center;
}

.markdown-wrap {
	overflow: hidden;
	border-radius: 12rpx;
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
	color: #909399;
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
		background-color: rgb(3, 174, 252);
		border-radius: 6rpx;
	}
}

.poup-head {
	border-bottom: 2rpx solid #f5f5f5;
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
	border-top: 2rpx dashed #eee;
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
</style>
