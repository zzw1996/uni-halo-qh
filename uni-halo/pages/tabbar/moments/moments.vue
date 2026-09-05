<template>
	<view class="app-page" :class="[uniHaloPluginPageClass]">
		<PluginUnavailable v-if="!uniHaloPluginAvailable" :pluginId="uniHaloPluginId"
			:error-text="uniHaloPluginAvailableError" />
		<view v-else-if="!canShowMoments" class="login-gate flex flex-center flex-col">
			<tm-empty icon="icon-user" label="登录后查看瞬间"></tm-empty>
			<tm-button class="mt-24" theme="bg-gradient-light-blue-accent" :shadow="4" @click="loginDialogShow = true">
				微信登录
			</tm-button>
			<tm-button class="mt-16" plan theme="grey" :shadow="0" @click="fnBackHome">返回首页</tm-button>
		</view>
		<template v-else>
			<view v-if="loading === 'loading'" class="loading-wrap">
				<tm-skeleton model="listAvatr"></tm-skeleton>
				<tm-skeleton model="listAvatr"></tm-skeleton>
				<tm-skeleton model="listAvatr"></tm-skeleton>
			</view>
			<view v-else-if="loading === 'error'" class="content-empty flex flex-center flex-col" style="min-height: 70vh;">
				<tm-empty icon="icon-shiliangzhinengduixiang-" label="加载失败"></tm-empty>
				<tm-button class="mt-24" plan theme="grey" :shadow="0" @click="fnRetryLoad">重新加载</tm-button>
			</view>
			<!-- 内容区域 -->
			<view v-else class="app-page-content" @click="fnCloseInteractionMenus">
				<view class="feed-tabs">
					<view
						v-for="tab in feedTabs"
						:key="tab.value"
						class="feed-tab"
						:class="{ 'is-active': activeFeed === tab.value }"
						@click="activeFeed = tab.value"
					>
						<text>{{ tab.label }}</text>
					</view>
				</view>
				<view v-if="displayList.length === 0" class="content-empty flex flex-center" style="min-height: 70vh;">
					<!-- 空布局 -->
					<tm-empty icon="icon-shiliangzhinengduixiang-" label="暂无数据"></tm-empty>
				</view>
				<block v-else>
					<!-- 卡片 -->
					<tm-translate v-for="(moment, index) in displayList" :key="moment.metadata.name"
						animation-name="fadeUp" :wait="calcAniWait(index)">
						<view class="moment-card">
							<view class="moment-card__head">
								<view class="moment-card__user">
									<view class="moment-card__avatar">
										<image
											class="moment-card__avatar-img"
											:src="moment.spec.user.avatar"
										/>
									</view>
									<view class="moment-card__meta">
										<view class="moment-card__name">{{ moment.spec.user.displayName }}</view>
										<view class="moment-card__time">
											{{ {d: moment.spec.releaseTime, f: 'yyyy年MM月dd日 星期w'} | formatTime }}
										</view>
									</view>
								</view>
								<view
									v-if="fnCanDeleteMoment(moment)"
									class="moment-card__delete"
									:class="{ 'is-loading': deletingMomentName === moment.metadata.name }"
									@click.stop="fnConfirmDeleteMoment(moment)"
								>
									<text class="icon iconfont icon-delete-fill"></text>
								</view>
							</view>
							<view class="content">
								<mp-html class="evan-markdown" lazy-load :domain="markdownConfig.domain"
									:loading-img="markdownConfig.loadingGif" :scroll-table="true" :selectable="true"
									:tag-style="markdownConfig.tagStyle" :container-style="markdownConfig.containStyle"
									:content="moment.spec.newHtml" :markdown="true" :showLineNumber="true"
									:showLanguageName="true" :copyByLongPress="true" />
							</view>
							<view v-if="moment.images && moment.images.length!==0" class="images"
								:class="['images-'+moment.images.length]">
								<view class="image-item" v-for="(image,mediumIndex) in moment.images"
									:key="mediumIndex">
									<image mode="aspectFill" style="width: 100%;height: 100%;border-radius: 6rpx;"
										:src="image.url" @click="handlePreview(mediumIndex,moment.images)" />
								</view>
							</view>
							<view v-if="moment.audios && moment.audios.length!==0" class="mb-12"
								style="display: flex; flex-direction: column; gap: 12rpx 0;padding: 0 24rpx;padding-right:28rpx;">
								<audio v-for="(audio,index) in moment.audios" :controls="true" :key="index"
									:id="audio.url" :poster="bloggerInfo.avatar"
									:name="'来自' + (startConfig.title||bloggerInfo.nickname) + '的声音'"
									:author="bloggerInfo.nickname" :src="audio.url"></audio>
							</view>
							<view v-if="moment.videos && moment.videos.length!==0" class="mb-12"
								style="display: flex; flex-direction: column; gap: 12rpx 0;padding: 0 24rpx; ">
								<video style="width:100%;height: 400rpx;border-radius: 12rpx;"
									v-for="(video,index) in moment.videos" :key="index" :src="video.url"
									:id="'video_' + video.id" :show-mute-btn="true" :controls="true"
									:show-center-play-btn="true" :enable-progress-gesture="true"
									@play="onVideoPlay(video.id)" @pause="onVideoPause(video.id)"
									@ended="onVideoEnded(video.id)"></video>
							</view>
							<view v-if="moment.spec.tags && moment.spec.tags.length!==0"
								class="mt-12 px-16 flex flex-wrap">
								<tm-tags v-for="(tag,tagIndex) in moment.spec.tags" :key="tagIndex"
									:color="randomTagColor()" size="m" model="text">
									{{ tag }}
								</tm-tags>
							</view>
							<moment-interaction
								v-if="!calcAuditModeEnabled"
								:ref="'momentInteraction_' + moment.metadata.name"
								:moment="moment"
								:show-comment="calcShowComment"
								:current-user-name="momentDisplayUser.displayName"
								:current-user-avatar="momentDisplayUser.avatar"
								:blogger-nickname="bloggerInfo.nickname"
								@comment="fnOnMomentComment"
								@need-login="fnOnMomentNeedLogin"
							></moment-interaction>
						</view>
					</tm-translate>
					<view class="load-text">{{ loadMoreText }}</view>
				</block>
			</view>
			<tm-flotbutton
				v-if="loading === 'success' && canPublishMoment"
				:offset="[16, 80]"
				label="发表"
				:show-text="true"
				actions-pos="left"
				:width="90"
				size="xs"
				color="bg-gradient-light-blue-accent"
				icon="icon-plus"
				:icon-size="24"
				@click="fnToPublishMoment"
			></tm-flotbutton>
			<tm-flotbutton
				v-if="loading === 'success'"
				@click="fnToTopPage"
				:width="90"
				size="xs"
				color="light-blue"
				:icon-size="24"
				icon="icon-angle-up"
			></tm-flotbutton>
		</template>

		<wx-login-dialog
			:show.sync="loginDialogShow"
			title="登录后查看瞬间"
			desc="登录后即可浏览和互动瞬间内容"
			@login-ready="fnOnWxLoginReady"
			@success="fnOnLoginSuccess"
			@close="fnOnLoginClose"
		></wx-login-dialog>

		<wx-login-dialog
			:show.sync="interactionLoginShow"
			:title="interactionLoginTitle"
			:desc="interactionLoginDesc"
			@login-ready="fnOnWxLoginReady"
			@success="fnOnInteractionLoginSuccess"
			@close="fnOnInteractionLoginClose"
		></wx-login-dialog>

		<comment-modal
			v-if="calcShowComment"
			:show="commentModal.show"
			:title="commentModal.title"
			:postName="commentModal.postName"
			:isComment="commentModal.isComment"
			subject-type="moment"
			@on-close="fnOnCommentModalClose"
		></comment-modal>
	</view>
</template>

<script>
	import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
	import tmFlotbutton from '@/tm-vuetify/components/tm-flotbutton/tm-flotbutton.vue';
	import tmTranslate from '@/tm-vuetify/components/tm-translate/tm-translate.vue';
	import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
	import tmTags from '@/tm-vuetify/components/tm-tags/tm-tags.vue';

	import MarkdownConfig from '@/common/markdown/markdown.config.js';
	import mpHtml from '@/components/mp-html/components/mp-html/mp-html.vue';
	import {
		getRandomNumberByRange
	} from "@/utils/random.js";
	import {
		generateUUID
	} from '@/utils/uuid.js';

	import pluginAvailableMixin from "@/common/mixins/pluginAvailable.js"
	import PluginUnavailable from '@/components/plugin-unavailable/plugin-unavailable.vue'
	import wxLoginDialog from '@/components/wx-login-dialog/wx-login-dialog.vue';
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
	import momentInteraction from '@/pagesA/components/moment-interaction/moment-interaction.vue';
	import commentModal from '@/pagesA/components/comment-modal/comment-modal.vue';
	import { checkHasWxLogin, getWxLoginInfo } from '@/utils/auth.js';
	import { isWxLoginRequired } from '@/utils/wxLogin.js';
	import { USER_ACTIVITY_TYPES, recordMomentUserActivity } from '@/utils/userActivity.js';
	import {
		canShowMomentPublish,
		isOwnMoment,
		normalizeMomentListItem,
		resolveMomentPublishPermission
	} from '@/utils/momentPublish.js';
	
	export default {
		mixins: [pluginAvailableMixin],
		components: {
			tmSkeleton,
			tmFlotbutton,
			tmTranslate,
			tmEmpty,
			tmTags,
			mpHtml,
			PluginUnavailable,
			wxLoginDialog,
			tmButton,
			momentInteraction,
			commentModal
		},
		data() {
			return {
				markdownConfig: MarkdownConfig,
				loading: 'loading',
				queryParams: {
					size: 10,
					page: 1
				},
				hasNext: false,
				dataList: [],
				isLoadMore: false,
				loadMoreText: '加载中...',
				tagColors: ['orange', 'green', 'red', 'blue'],
				videoContexts: {},
				currentVideoId: null,
				isLoggedIn: false,
				loginDialogShow: false,
				interactionLoginShow: false,
				pendingInteractionAction: null,
				pendingCommentPayload: null,
				pendingLikeMomentName: '',
				commentModal: {
					show: false,
					isComment: true,
					postName: '',
					title: ''
				},
				activeCommentMomentName: '',
				wxUserInfo: null,
				canPublishMoment: false,
				momentOwnerName: '',
				deletingMomentName: '',
				activeFeed: 'latest'
			};
		},

		computed: {
			// 瞬间 feed 切换：latest=最新发布，hot=点赞最多（客户端排序）
			feedTabs() {
				return [
					{ label: '最新', value: 'latest' },
					{ label: '最热', value: 'hot' }
				];
			},
			displayList() {
				const list = [...this.dataList];
				if (this.activeFeed === 'hot') {
					list.sort((a, b) => {
						const av = Number(a?.stats?.upvote ?? 0);
						const bv = Number(b?.stats?.upvote ?? 0);
						return bv - av;
					});
				} else {
					list.sort((a, b) => {
						const at = String(a?.spec?.publishTime || a?.spec?.releaseTime || '');
						const bt = String(b?.spec?.publishTime || b?.spec?.releaseTime || '');
						return bt.localeCompare(at);
					});
				}
				return list;
			},
			loginRequired() {
				return isWxLoginRequired(this.haloConfigs);
			},
			canShowMoments() {
				return !this.loginRequired || this.isLoggedIn;
			},
			momentDisplayUser() {
				if (this.wxUserInfo?.avatarUrl) {
					return {
						displayName: this.wxUserInfo.nickname || '微信用户',
						avatar: this.$utils.checkAvatarUrl(this.wxUserInfo.avatarUrl, true)
					};
				}
				return {
					displayName: this.bloggerInfo.nickname,
					avatar: this.bloggerInfo.avatar
				};
			},
			bloggerInfo() {
				let blogger = this.$tm.vx.getters().getConfigs.authorConfig.blogger;
				blogger.avatar = this.$utils.checkAvatarUrl(blogger.avatar, true);
				return blogger;
			},
			haloConfigs() {
				return this.$tm.vx.getters().getConfigs;
			},
			mockJson() {
				return this.$tm.vx.getters().getMockJson;
			},
			calcAuditModeEnabled() {
				return this.haloConfigs.auditConfig.auditModeEnabled
			},
			calcUseTagRandomColor() {
				return this.haloConfigs.pageConfig.momentConfig.useTagRandomColor
			},
			startConfig() {
				return this.haloConfigs.appConfig.startConfig;
			},
			postDetailConfig() {
				return this.haloConfigs?.basicConfig?.postDetailConfig || { showComment: true };
			},
			calcShowComment() {
				return this.postDetailConfig.showComment !== false;
			},
			interactionLoginTitle() {
				if (this.pendingInteractionAction === 'like') return '登录后点赞';
				if (this.pendingInteractionAction === 'comment' || this.pendingInteractionAction === 'reply') {
					return '登录后评论';
				}
				return '登录后继续';
			},
			interactionLoginDesc() {
				if (this.pendingInteractionAction === 'like') return '点赞需要先完成微信授权登录';
				if (this.pendingInteractionAction === 'comment' || this.pendingInteractionAction === 'reply') {
					return '评论需要先完成微信授权登录';
				}
				return '请先完成微信授权登录';
			}
		},
		async onLoad() { 
			this.setPluginId(this.NeedPluginIds.PluginMoments)
			this.setPluginError("阿偶，检测到当前插件没有安装或者启用，无法使用瞬间功能哦，请联系管理员")
			if (!await this.checkPluginAvailable()) return

			this.fnSyncWxUser();
			this.fnRefreshPublishPermission();
			if (!this.fnEnsureLoggedIn()) return;
			this.fnGetData();
		},
		onShow() {
			this.fnSyncWxUser();
			this.fnRefreshPublishPermission();
			if (!this.uniHaloPluginAvailable) return;
			if (!this.fnEnsureLoggedIn()) return;
			const app = getApp();
			if (app?.globalData?.momentListNeedRefresh) {
				app.globalData.momentListNeedRefresh = false;
				this.fnReloadMomentList();
				return;
			}
			if (this.dataList.length === 0 && this.loading !== 'success') {
				this.fnGetData();
			}
		},
		created() {
			uni.$on('wx_login_status_changed', this.fnOnWxLoginStatusChanged);
			uni.$on('moments_list_should_refresh', this.fnReloadMomentList);
		},
		onUnload() {
			uni.$off('wx_login_status_changed', this.fnOnWxLoginStatusChanged);
			uni.$off('moments_list_should_refresh', this.fnReloadMomentList);
		},
		onPullDownRefresh() {
			if (!this.uniHaloPluginAvailable || !this.canShowMoments) {
				uni.hideLoading();
				uni.stopPullDownRefresh();
				return
			}
			this.isLoadMore = false;
			this.queryParams.page = 1;
			this.videoContexts = {};
			this.currentVideoId = null;
			this.fnGetData();
		},
		onReachBottom(e) {
			if (!this.uniHaloPluginAvailable || !this.canShowMoments) return;
			if (this.calcAuditModeEnabled) {
				uni.showToast({
					icon: 'none',
					title: '没有更多数据了'
				});
				return
			}
			if (this.hasNext) {
				this.queryParams.page += 1;
				this.isLoadMore = true;
				this.fnGetData();
			} else {
				uni.showToast({
					icon: 'none',
					title: '没有更多数据了'
				});
			}
		},
		methods: {
			fnOnWxLoginStatusChanged(userInfo) {
				this.fnSyncWxUser(userInfo);
				this.fnRefreshPublishPermission();
			},
			fnSyncWxUser(userInfo) {
				this.wxUserInfo = userInfo || (checkHasWxLogin() ? getWxLoginInfo() : null);
				this.isLoggedIn = checkHasWxLogin();
			},
			async fnRefreshPublishPermission() {
				if (!this.uniHaloPluginAvailable || this.calcAuditModeEnabled) {
					this.canPublishMoment = false;
					this.momentOwnerName = '';
					return;
				}
				const permission = await resolveMomentPublishPermission(
					this.$httpApi.v2,
					this.haloConfigs,
					this.wxUserInfo || getWxLoginInfo()
				);
				this.canPublishMoment = canShowMomentPublish(permission);
				this.momentOwnerName = permission.ownerName || '';
			},
			fnCanDeleteMoment(moment) {
				return !!this.momentOwnerName && isOwnMoment(moment, this.momentOwnerName);
			},
			fnConfirmDeleteMoment(moment) {
				const name = (moment?.metadata?.name || '').trim();
				if (!name || this.deletingMomentName) {
					return;
				}
				uni.showModal({
					title: '删除瞬间',
					content: '确定删除这条瞬间吗？删除后不可恢复。',
					confirmText: '删除',
					confirmColor: '#ff5722',
					success: (res) => {
						if (res.confirm) {
							this.fnDoDeleteMoment(name);
						}
					}
				});
			},
			async fnDoDeleteMoment(momentName) {
				const name = (momentName || '').trim();
				if (!name || this.deletingMomentName) {
					return;
				}
				this.deletingMomentName = name;
				uni.showLoading({ mask: true, title: '删除中...' });
				try {
					await this.$httpApi.v2.deleteMoment(name);
					uni.showToast({ icon: 'success', title: '已删除' });
					this.fnReloadMomentList();
				} catch (err) {
					const message = err?.detail || err?.error || err?.message || '删除失败，请稍后重试';
					uni.showToast({ icon: 'none', title: message });
				} finally {
					this.deletingMomentName = '';
					uni.hideLoading();
				}
			},
			fnToPublishMoment() {
				if (!this.canPublishMoment) {
					return;
				}
				if (!checkHasWxLogin()) {
					this.loginDialogShow = true;
					return;
				}
				uni.navigateTo({
					url: '/pagesA/moment-publish/moment-publish'
				});
			},
			fnEnsureLoggedIn() {
				if (!this.loginRequired) {
					this.isLoggedIn = true;
					return true;
				}
				this.isLoggedIn = checkHasWxLogin();
				return this.isLoggedIn;
			},
			fnOnWxLoginReady(userInfo) {
				this.wxUserInfo = userInfo;
				this.isLoggedIn = true;
				this.fnRefreshPublishPermission();
			},
			fnOnLoginSuccess(userInfo) {
				this.wxUserInfo = userInfo;
				this.isLoggedIn = true;
				this.fnRefreshPublishPermission();
				this.isLoadMore = false;
				this.queryParams.page = 1;
				this.fnGetData();
			},
			fnOnLoginClose() {
				this.loginDialogShow = false;
			},
			fnBackHome() {
				uni.switchTab({ url: '/pages/tabbar/home/home' });
			},
			fnGetMomentUser() {
				return this.momentDisplayUser;
			},
			fnRetryLoad() {
				this.fnReloadMomentList();
			},
			fnReloadMomentList() {
				if (!this.uniHaloPluginAvailable || !this.canShowMoments) {
					return;
				}
				this.isLoadMore = false;
				this.queryParams.page = 1;
				this.videoContexts = {};
				this.currentVideoId = null;
				this.fnGetData({ keepContent: true });
				uni.pageScrollTo({
					scrollTop: 0,
					duration: 200
				});
			},
			fnGetData(options = {}) {
				if (!this.canShowMoments) return;
				if (this.calcAuditModeEnabled) {
					const displayUser = this.fnGetMomentUser();
					this.dataList = this.mockJson.moments.list.map((item) => {
						return {
							metadata: {
								name: Date.now() * Math.random(),
							},
							spec: {
								user: {
									displayName: displayUser.displayName,
									avatar: displayUser.avatar,
								},
								content: {
									html: item.content
								},
								releaseTime: item.time,
								visible: "PUBLIC"
							},
							images: item.images.map((img) => {
								return {
									type: "PHOTO",
									url: this.$utils.checkThumbnailUrl(img),
								}
							}),
							videos: []
						}
					});
					this.loading = 'success';
					this.loadMoreText = '呜呜，没有更多数据啦~';
					uni.hideLoading();
					uni.stopPullDownRefresh();
					this.fnRefreshPublishPermission();
					return;
				}
				uni.showLoading({
					mask: true,
					title: '加载中...'
				});
				// 设置状态为加载中
				if (!this.isLoadMore && !options.keepContent) {
					this.loading = 'loading';
				}
				this.loadMoreText = '加载中...';
				this.$httpApi.v2
					.getMomentList(this.queryParams)
					.then(res => {
						this.loading = 'success';
						this.loadMoreText = res.hasNext ? '上拉加载更多' : '呜呜，没有更多数据啦~';
						this.hasNext = res.hasNext;

						const displayUser = this.fnGetMomentUser();
						const items = Array.isArray(res?.items) ? res.items : [];
						const tempItems = items
							.filter((item) => item?.spec?.visible === 'PUBLIC')
							.map((item) => normalizeMomentListItem(item, {
								displayUser,
								checkThumbnailUrl: (url, thumb) => this.$utils.checkThumbnailUrl(url, thumb),
								removeTagLinksCompletely: (html) => this.removeTagLinksCompletely(html),
								generateUUID
							}));

						if (this.isLoadMore) {
							this.dataList = this.dataList.concat(tempItems);
						} else {
							this.dataList = tempItems;
						}

						this.$nextTick(() => {
							this.createVideoContexts(tempItems);
						})

					})
					.catch(err => {
						console.error(err);
						this.loading = 'error';
						this.loadMoreText = '加载失败，请下拉刷新！';
					})
					.finally(() => {
						setTimeout(() => {
							uni.hideLoading();
							uni.stopPullDownRefresh();
						}, 500);
						this.fnRefreshPublishPermission();
					});
			},
			handlePreview(index, list) {
				uni.previewImage({
					current: index,
					urls: list.map(item => item.url)
				})
			},
			removeTagLinksCompletely(htmlString) {
				if (!htmlString || typeof htmlString !== 'string') {
					return '';
				}
				const regex = /<a\s+(?:[^>]*?\s+)?class=(['"])[^'"]*?\btag\b[^'"]*?\1[^>]*?>.*?<\/a>/gi;
				return htmlString.replace(regex, '');
			},
			randomTagColor() {
				if (!this.calcUseTagRandomColor) return "blue";
				const randomIndex = getRandomNumberByRange(0, this.tagColors.length);
				return this.tagColors[randomIndex];
			},
			createVideoContexts(list) {
				this.stopAllVideos()
				list.map(item => item.videos).flat().forEach(item => {
					this.videoContexts[item.id] = uni.createVideoContext(`video_${item.id}`, this);
				})
			},
			onVideoPlay(videoId) {
				this.currentVideoId = videoId;
				this.stopAllVideos(videoId)
			},
			onVideoPause(videoId) {
				if (this.currentVideoId == videoId) {
					this.currentVideoId = null;
				}
			},
			onVideoEnded(videoId) {
				this.currentVideoId = null;
			},
			stopAllVideos(excludesVideoId = null) {
				Object.keys(this.videoContexts).forEach(videoId => {
					if (!excludesVideoId || excludesVideoId != videoId) {
						const videoContext = this.videoContexts[videoId]
						videoContext?.pause();
					}
				});
			},
			fnCloseInteractionMenus() {
				uni.$emit('moment_interaction_close_menu');
			},
			fnOnMomentComment(payload) {
				this.activeCommentMomentName = payload.momentName || payload.postName;
				if (isWxLoginRequired(this.haloConfigs) && !checkHasWxLogin()) {
					this.pendingCommentPayload = payload;
					this.pendingInteractionAction = payload.isComment ? 'comment' : 'reply';
					this.interactionLoginShow = true;
					return;
				}
				this.fnOpenCommentModal(payload);
			},
			fnOnMomentNeedLogin({ action, momentName }) {
				this.pendingInteractionAction = action;
				if (action === 'like') {
					this.pendingLikeMomentName = momentName || '';
				}
				this.interactionLoginShow = true;
			},
			fnOpenCommentModal(payload) {
				this.commentModal.isComment = payload.isComment;
				this.commentModal.postName = payload.postName;
				this.commentModal.title = payload.title;
				this.commentModal.show = true;
			},
			fnOnInteractionLoginSuccess(userInfo) {
				this.wxUserInfo = userInfo;
				this.isLoggedIn = true;
				this.fnRefreshPublishPermission();
				if (this.pendingCommentPayload) {
					const payload = this.pendingCommentPayload;
					this.pendingCommentPayload = null;
					this.pendingInteractionAction = null;
					this.fnOpenCommentModal(payload);
					return;
				}
				if (this.pendingInteractionAction === 'like' && this.pendingLikeMomentName) {
					const momentName = this.pendingLikeMomentName;
					this.pendingLikeMomentName = '';
					this.pendingInteractionAction = null;
					this.$nextTick(() => {
						this.fnGetMomentInteractionRef(momentName)?.fnDoLike?.();
					});
					return;
				}
				this.pendingInteractionAction = null;
			},
			fnOnInteractionLoginClose() {
				this.interactionLoginShow = false;
				this.pendingCommentPayload = null;
				this.pendingInteractionAction = null;
				this.pendingLikeMomentName = '';
			},
			fnGetMomentInteractionRef(momentName) {
				const ref = this.$refs[`momentInteraction_${momentName}`];
				return Array.isArray(ref) ? ref[0] : ref;
			},
			fnOnCommentModalClose({ refresh, isSubmit, needLogin }) {
				if (needLogin) {
					this.commentModal.show = false;
					this.pendingCommentPayload = {
						isComment: this.commentModal.isComment,
						postName: this.commentModal.postName,
						title: this.commentModal.title
					};
					this.pendingInteractionAction = this.commentModal.isComment ? 'comment' : 'reply';
					this.interactionLoginShow = true;
					return;
				}
				const momentName = this.activeCommentMomentName;
				if (refresh && isSubmit && momentName) {
					const interactionRef = this.fnGetMomentInteractionRef(momentName);
					interactionRef?.onCommentSubmitted?.();
				}
				if (isSubmit && momentName) {
					const moment = this.dataList.find((item) => item.metadata.name === momentName);
					recordMomentUserActivity(this.$httpApi.v2, USER_ACTIVITY_TYPES.COMMENT, {
						momentName,
						title: (moment?.spec?.content?.raw || '').slice(0, 50) || '瞬间',
						excerpt: (moment?.spec?.content?.raw || '').slice(0, 100)
					});
				}
				this.commentModal.show = false;
				this.commentModal.isComment = true;
				this.commentModal.postName = '';
				this.commentModal.title = '';
				this.activeCommentMomentName = '';
			}
		}
	};
</script>

<style lang="scss" scoped>
	.app-page {
		box-sizing: border-box;
		width: 100vw;
		min-height: 100vh;
		display: flex;
		flex-direction: column;
		padding: 24rpx 0;
	}

	.loading-wrap {
		padding: 24rpx;
	}

	.feed-tabs {
		display: flex;
		align-items: center;
		gap: 12rpx;
		padding: 20rpx 24rpx 4rpx;
	}

	.feed-tab {
		padding: 8rpx 32rpx;
		border-radius: 999rpx;
		background: #fff;
		font-size: 26rpx;
		color: #909399;
	}

	.feed-tab.is-active {
		background: #03a9f4;
		color: #fff;
		font-weight: 600;
	}

	.login-gate {
		min-height: 70vh;
		padding: 48rpx 24rpx;
		box-sizing: border-box;
	}

	.moment-card {
		display: flex;
		flex-direction: column;
		box-sizing: border-box;
		margin: 0 24rpx;
		border-radius: 12rpx;
		background-color: #ffff;
		box-shadow: 0rpx 2rpx 24rpx rgba(0, 0, 0, 0.03);
		overflow: hidden;
		margin-bottom: 24rpx;
	}

	.moment-card__head {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
		padding: 24rpx 24rpx 0;
		gap: 16rpx;
	}

	.moment-card__user {
		display: flex;
		align-items: center;
		flex: 1;
		min-width: 0;
	}

	.moment-card__avatar {
		flex-shrink: 0;
	}

	.moment-card__avatar-img {
		width: 66rpx;
		height: 66rpx;
		border-radius: 50%;
		display: block;
	}

	.moment-card__meta {
		margin-left: 12rpx;
		min-width: 0;
	}

	.moment-card__name {
		font-size: 30rpx;
		font-weight: bold;
		color: #333333;
		line-height: 1.4;
	}

	.moment-card__time {
		margin-top: 6rpx;
		font-size: 24rpx;
		color: #666;
		line-height: 1.4;
	}

	.moment-card__delete {
		flex-shrink: 0;
		width: 56rpx;
		height: 56rpx;
		margin-top: 4rpx;
		border-radius: 50%;
		display: flex;
		align-items: center;
		justify-content: center;
		background: #f5f7fa;
		transition: background-color 0.2s ease;

		.icon {
			font-size: 28rpx;
			color: #b0b8c4;
		}

		&:active {
			background: #fdeeee;

			.icon {
				color: #f56c6c;
			}
		}

		&.is-loading {
			opacity: 0.45;
			pointer-events: none;
		}
	}

	.images {
		display: flex;
		flex-wrap: wrap;
		align-items: flex-start;
		padding: 24rpx;
		padding-top: 0;

		.image-item {
			box-sizing: border-box;
			border-radius: 24rpx;
			padding: 6rpx;
			width: 33%;
			height: 200rpx
		}

		&-1 {
			>.image-item {
				width: 100%;
				height: 350rpx
			}
		}

		&-2 {
			>.image-item {
				width: 50%;
				height: 250rpx
			}
		}
	}

	::v-deep .uni-audio-default {
		width: 100%;
		border-radius: 12rpx;
	}
</style>