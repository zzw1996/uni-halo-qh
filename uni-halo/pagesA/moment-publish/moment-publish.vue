<template>
	<view class="publish-page">
		<view v-if="!permissionChecked" class="loading-wrap flex flex-center">
			<tm-loadding color="light-blue" :size="48"></tm-loadding>
		</view>
		<view v-else-if="!canPublish" class="denied-wrap flex flex-center flex-col">
			<tm-empty icon="icon-user" label="暂无发表权限"></tm-empty>
			<text class="denied-tip">仅与插件 personalToken 对应的博主微信账号可发表瞬间</text>
			<tm-button class="mt-24" plan theme="grey" :shadow="0" @click="fnBack">返回</tm-button>
		</view>
		<view v-else class="publish-body">
			<view class="publish-card">
				<textarea
					class="publish-input"
					v-model="content"
					maxlength="2000"
					placeholder="分享此刻的想法..."
					:disabled="submitting"
					:auto-height="true"
				></textarea>
				<view class="publish-count">{{ content.length }}/2000</view>

				<view class="image-grid">
					<view
						v-for="(item, index) in imageList"
						:key="item.id"
						class="image-item"
					>
						<image
							class="image-preview"
							:src="item.localPath"
							mode="aspectFill"
							@click="fnPreviewImage(index)"
						></image>
						<view
							v-if="!submitting"
							class="image-remove"
							@click.stop="fnRemoveImage(index)"
						>
							<tm-icons name="icon-times" :size="20" color="#fff"></tm-icons>
						</view>
					</view>
					<view
						v-if="imageList.length < maxImages && !submitting"
						class="image-add"
						@click="fnChooseImages"
					>
						<tm-icons name="icon-plus" :size="40" color="#c0c4cc"></tm-icons>
						<text class="image-add-text">{{ imageList.length }}/{{ maxImages }}</text>
					</view>
				</view>
				<text class="image-tip">支持纯文字、纯图片或图文混合，图片最多 {{ maxImages }} 张</text>
			</view>
			<view class="publish-actions">
				<tm-button
					block
					theme="bg-gradient-light-blue-accent"
					:shadow="4"
					:loading="submitting"
					:disabled="!canSubmit"
					@click="fnSubmit"
				>发表瞬间</tm-button>
			</view>
		</view>
	</view>
</template>

<script>
	import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
	import tmLoadding from '@/tm-vuetify/components/tm-loadding/tm-loadding.vue';
	import tmIcons from '@/tm-vuetify/components/tm-icons/tm-icons.vue';
	import { checkHasWxLogin, getWxLoginInfo } from '@/utils/auth.js';
	import {
		buildMomentCreatePayload,
		canShowMomentPublish,
		resolveMomentPublishPermission
	} from '@/utils/momentPublish.js';
	import { generateUUID } from '@/utils/uuid.js';

	const MAX_IMAGES = 9;

	export default {
		components: {
			tmEmpty,
			tmButton,
			tmLoadding,
			tmIcons
		},
		data() {
			return {
				maxImages: MAX_IMAGES,
				permissionChecked: false,
				canPublish: false,
				ownerName: '',
				content: '',
				imageList: [],
				submitting: false
			};
		},
		computed: {
			haloConfigs() {
				return this.$tm.vx.getters().getConfigs;
			},
			attachmentUploadConfig() {
				const appInfo = this.haloConfigs?.appConfig?.appInfo || {};
				return {
					policyName: appInfo.policyName || 'default-policy',
					groupName: appInfo.fileGroupName || ''
				};
			},
			hasText() {
				return !!this.content.trim();
			},
			hasImages() {
				return this.imageList.length > 0;
			},
			canSubmit() {
				return (this.hasText || this.hasImages) && !this.submitting;
			}
		},
		onLoad() {
			this.fnCheckPermission();
		},
		onShow() {
			if (this.permissionChecked && !this.canPublish) {
				return;
			}
			if (!this.permissionChecked) {
				this.fnCheckPermission();
			}
		},
		methods: {
			async fnCheckPermission() {
				if (!checkHasWxLogin()) {
					this.permissionChecked = true;
					this.canPublish = false;
					uni.showToast({ icon: 'none', title: '请先登录' });
					setTimeout(() => this.fnBack(), 800);
					return;
				}
				const permission = await resolveMomentPublishPermission(
					this.$httpApi.v2,
					this.haloConfigs,
					getWxLoginInfo()
				);
				this.permissionChecked = true;
				this.canPublish = canShowMomentPublish(permission);
				if (!this.canPublish) {
					uni.showToast({ icon: 'none', title: '当前账号无发表权限' });
					return;
				}
				await this.fnResolveOwnerName();
			},
			async fnResolveOwnerName() {
				const wxUser = getWxLoginInfo();
				const haloUserName = (wxUser?.haloUserName || '').trim();
				if (haloUserName) {
					this.ownerName = haloUserName;
					return;
				}
				try {
					const tokenUser = await this.$httpApi.v2.getPersonalTokenUser();
					this.ownerName = (tokenUser?.metadata?.name || tokenUser?.name || '').trim();
				} catch (err) {
					console.warn('[moment-publish] 获取 owner 失败:', err);
				}
			},
			fnBack() {
				uni.navigateBack({
					fail: () => {
						// 瞬间页已从底部导航移除时，回退到首页
						uni.switchTab({ url: '/pages/tabbar/home/home' });
					}
				});
			},
			fnChooseImages() {
				if (this.submitting) {
					return;
				}
				const remain = this.maxImages - this.imageList.length;
				if (remain <= 0) {
					uni.showToast({ icon: 'none', title: `最多选择 ${this.maxImages} 张图片` });
					return;
				}
				uni.chooseImage({
					count: remain,
					sizeType: ['compressed'],
					sourceType: ['album', 'camera'],
					success: (res) => {
						const paths = res.tempFilePaths || [];
						const nextItems = paths.map((localPath) => ({
							id: generateUUID(),
							localPath
						}));
						this.imageList = this.imageList.concat(nextItems).slice(0, this.maxImages);
					}
				});
			},
			fnRemoveImage(index) {
				if (this.submitting) {
					return;
				}
				this.imageList.splice(index, 1);
			},
			fnPreviewImage(index) {
				if (!this.imageList.length) {
					return;
				}
				uni.previewImage({
					current: index,
					urls: this.imageList.map((item) => item.localPath)
				});
			},
			fnResolveAttachmentUrl(attachment) {
				const url = attachment?.status?.permalink
					|| attachment?.spec?.url
					|| attachment?.url
					|| '';
				return this.$utils.checkUrl(url);
			},
			async fnUploadImages() {
				const uploaded = [];
				for (let i = 0; i < this.imageList.length; i += 1) {
					uni.showLoading({
						mask: true,
						title: `上传图片 ${i + 1}/${this.imageList.length}`
					});
					const attachment = await this.$httpApi.v2.uploadAttachment(
						this.imageList[i].localPath,
						this.attachmentUploadConfig
					);
					const url = this.fnResolveAttachmentUrl(attachment);
					if (!url) {
						throw { error: `第 ${i + 1} 张图片上传失败，未获取到链接` };
					}
					uploaded.push(url);
				}
				return uploaded;
			},
			fnBuildMomentPayload(rawText, imageUrls) {
				return buildMomentCreatePayload({
					rawText,
					imageUrls,
					ownerName: this.ownerName
				});
			},
			async fnSubmit() {
				if (!this.canSubmit) {
					return;
				}
				const raw = this.content.trim();
				if (!raw && !this.hasImages) {
					uni.showToast({ icon: 'none', title: '请输入文字或选择图片' });
					return;
				}
				this.submitting = true;
				uni.showLoading({ mask: true, title: '发布中...' });
				try {
					if (!this.ownerName) {
						throw { error: '无法确定博主账号，请稍后重试' };
					}
					let imageUrls = [];
					if (this.hasImages) {
						imageUrls = await this.fnUploadImages();
					}
					const payload = this.fnBuildMomentPayload(raw, imageUrls);
					await this.$httpApi.v2.createMoment(payload);
					uni.showToast({ icon: 'success', title: '发表成功' });
					const app = getApp();
					if (app?.globalData) {
						app.globalData.momentListNeedRefresh = true;
					}
					uni.$emit('moments_list_should_refresh');
					setTimeout(() => this.fnBack(), 600);
				} catch (err) {
					const message = err?.error || err?.detail || err?.message || '发表失败，请稍后重试';
					uni.showToast({ icon: 'none', title: message });
				} finally {
					this.submitting = false;
					uni.hideLoading();
				}
			}
		}
	};
</script>

<style lang="scss" scoped>
	.publish-page {
		min-height: 100vh;
		background: #f5f7fa;
		box-sizing: border-box;
	}

	.loading-wrap,
	.denied-wrap {
		min-height: 70vh;
		padding: 48rpx 24rpx;
		box-sizing: border-box;
	}

	.denied-tip {
		margin-top: 16rpx;
		padding: 0 48rpx;
		font-size: 26rpx;
		color: #909399;
		text-align: center;
		line-height: 1.6;
	}

	.publish-body {
		padding: 24rpx;
	}

	.publish-card {
		background: #fff;
		border-radius: 16rpx;
		padding: 24rpx;
		box-shadow: 0 2rpx 24rpx rgba(0, 0, 0, 0.03);
	}

	.publish-input {
		width: 100%;
		min-height: 240rpx;
		font-size: 30rpx;
		line-height: 1.7;
		color: #303133;
	}

	.publish-count {
		margin-top: 12rpx;
		text-align: right;
		font-size: 24rpx;
		color: #c0c4cc;
	}

	.image-grid {
		display: flex;
		flex-wrap: wrap;
		margin-top: 24rpx;
		gap: 16rpx;
	}

	.image-item,
	.image-add {
		position: relative;
		width: calc((100% - 32rpx) / 3);
		height: 200rpx;
		border-radius: 12rpx;
		overflow: hidden;
		box-sizing: border-box;
	}

	.image-preview {
		width: 100%;
		height: 100%;
		display: block;
		background: #f2f3f5;
	}

	.image-remove {
		position: absolute;
		top: 0;
		right: 0;
		width: 44rpx;
		height: 44rpx;
		display: flex;
		align-items: center;
		justify-content: center;
		background: rgba(0, 0, 0, 0.45);
		border-bottom-left-radius: 12rpx;
	}

	.image-add {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		background: #f7f8fa;
		border: 2rpx dashed #dcdfe6;
	}

	.image-add-text {
		margin-top: 8rpx;
		font-size: 22rpx;
		color: #c0c4cc;
	}

	.image-tip {
		display: block;
		margin-top: 20rpx;
		font-size: 24rpx;
		color: #909399;
		line-height: 1.5;
	}

	.publish-actions {
		margin-top: 32rpx;
	}
</style>
