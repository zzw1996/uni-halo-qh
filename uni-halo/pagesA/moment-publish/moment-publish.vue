<template>
	<view :class="skinClass" class="publish-page">
		<view v-if="!permissionChecked" class="loading-wrap flex flex-center">
			<tm-loadding color="indigo" :size="48"></tm-loadding>
		</view>
		<view v-else-if="!canPublish" class="denied-wrap flex flex-center flex-col">
			<tm-empty icon="icon-user" label="暂无发表权限"></tm-empty>
			<text class="denied-tip">{{ deniedReason }}</text>
			<tm-button class="mt-24" plan theme="grey" :fllowTheme="false" :shadow="0" @click="fnBack">返回</tm-button>
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
						<tm-icons name="icon-plus" :size="40" color="#c6c6c0"></tm-icons>
						<text class="image-add-text">{{ imageList.length }}/{{ maxImages }}</text>
					</view>
				</view>
				<text class="image-tip">支持纯文字、纯图片或图文混合，图片最多 {{ maxImages }} 张</text>
			</view>
			<text v-if="publishStatus && !publishStatus.privileged" class="cost-tip">
				本次发布将消耗 {{ publishStatus.momentCost }} 积分（当前 {{ publishStatus.balance }} 积分）
			</text>
			<text v-else-if="publishStatus" class="cost-tip">
				你是站点所有者/管理员，发布不消耗积分
			</text>
			<view class="publish-actions">
				<tm-button
					block
					theme="bg-gradient-indigo-accent"
					:shadow="4"
					:loading="submitting"
					:disabled="!canSubmit"
					@click="fnSubmit"
				>{{ submitText }}</tm-button>
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
	import { generateUUID } from '@/utils/uuid.js';
	import { emitPointsUpdated } from '@/utils/points.js';
	import {
		fetchUserPublishStatus,
		submitUserMoment,
		uploadUserPublishImage
	} from '@/utils/userPublish.js';

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
				/** 投稿状态（含价格、余额、角色结论、是否特权），全部来自服务端 */
				publishStatus: null,
				deniedReason: '暂不能发布瞬间',
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
			submitText() {
				const status = this.publishStatus;
				if (!status) {
					return '发表瞬间';
				}
				// 博主/管理员免费，不显示积分；其他人按直发/待审给出不同措辞
				const costText = status.privileged ? '' : `（${status.momentCost} 积分）`;
				return 'draft' === status.momentMode
					? `提交审核${costText}`
					: `发布${costText}`;
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
					this.deniedReason = '请先登录后再发布';
					uni.showToast({ icon: 'none', title: '请先登录' });
					setTimeout(() => this.fnBack(), 800);
					return;
				}
				// 能不能发、发出来是直发还是待审，一律以服务端结论为准：
				// 客户端不再持有个人令牌，也就无法（也不该）自己判定权限
				const status = await fetchUserPublishStatus(this.$httpApi.v2);
				this.permissionChecked = true;
				this.publishStatus = status;
				this.canPublish = !!(status && status.canPublishMoment && status.visible);
				if (!this.canPublish) {
					this.deniedReason = (status && status.momentDisabledReason)
						|| '当前账号暂无发布权限，可在「我的」查看赚积分方式';
					uni.showToast({ icon: 'none', title: '当前账号无发表权限' });
					return;
				}
				this.maxImages = status.maxMomentImages != null
					? status.maxMomentImages
					: MAX_IMAGES;
			},
			fnBack() {
				uni.navigateBack({
					fail: () => {
						// 瞬间已是 tabBar 页面：回不到上一页时（如从分享直接进入）回到瞬间 tab
						// 瞬间页现在还是图库/小店的宿主页：先声明要回到瞬间视图，再切过去
						setPendingTabbarMode('moments');
						uni.switchTab({ url: '/pages/tabbar/moments/moments' });
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
				let result = null;
				try {
					result = await this.fnSubmitAsUser(raw);
				} catch (err) {
					this.submitting = false;
					uni.hideLoading();
					const message = err?.error || err?.detail || err?.message || '发表失败，请稍后重试';
					// 用弹窗而不是 toast：失败原因可能较长（「请先关联 Halo 账号…」
					// 或服务端带回的 Halo 拒绝详情），toast 两秒就消失，用户看不清也来不及记
					uni.showModal({
						title: '发布失败',
						content: message,
						showCancel: false,
						confirmText: '知道了'
					});
					return;
				}
				this.submitting = false;
				uni.hideLoading();
				// 余额变了（普通用户扣分）就广播出去：我的页/积分明细靠它同步
				if (result?.balance != null) {
					emitPointsUpdated({ balance: result.balance });
				}
				// 通知瞬间列表刷新，否则返回列表看不到刚发的那条
				uni.$emit('moments_list_should_refresh');
				// 结果文案由服务端给（「已发布」/「已提交，等待站长审核」）；用 none 图标是因为它可能较长
				uni.showToast({ icon: 'none', title: result?.statusText || '发布成功' });
				setTimeout(() => this.fnBack(), 1200);
			},
			/**
			 * 真正提交：先逐张上传配图，再连同文字一起交给服务端。
			 *
			 * <p>上传走服务端代理（客户端不持有个人令牌）；串行上传而不是并发——
			 * 一次最多 9 张，串行既能定位到具体第几张失败，也不会把上传通道打满。</p>
			 *
			 * <p>权限一律以服务端结论为准：博主/管理员的免积分、免审核、免次数限制
			 * 都在服务端判定，这里不做任何客户端侧的门槛判断（否则后台策略一改就对不上）。</p>
			 *
			 * @returns {Promise<object>} 服务端返回的结果（mode/statusText/balance…）
			 * @throws {{error: string}} 任一步失败都抛出，由 {@link fnSubmit} 统一提示
			 */
			async fnSubmitAsUser(raw) {
				const images = [];
				for (let i = 0; i < this.imageList.length; i++) {
					const uploaded = await uploadUserPublishImage(this.$httpApi.v2,
						this.imageList[i].localPath);
					if (!uploaded.ok) {
						throw { error: uploaded.reason || `第 ${i + 1} 张图片上传失败` };
					}
					images.push(uploaded.url);
				}
				const result = await submitUserMoment(this.$httpApi.v2, {
					content: raw,
					images
				});
				if (!result.ok) {
					throw { error: result.reason || '发布失败，请稍后重试' };
				}
				return result.data || {};
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
		color: $uh-ink-3;
		text-align: center;
		line-height: 1.6;
	}

	.publish-body {
		padding: 24rpx;
	}

	.publish-card {
		background: $uh-card;
		border-radius: 16rpx;
		padding: 24rpx;
		box-shadow: $uh-shadow-sm;
	}

	.publish-input {
		width: 100%;
		min-height: 240rpx;
		font-size: 30rpx;
		line-height: 1.7;
		color: $uh-ink-1;
	}

	.publish-count {
		margin-top: 12rpx;
		text-align: right;
		font-size: 24rpx;
		color: $uh-ink-4;
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
		background: $uh-fill;
		border: 2rpx dashed #dcdfe6;
	}

	.image-add-text {
		margin-top: 8rpx;
		font-size: 22rpx;
		color: $uh-ink-4;
	}

	.image-tip {
		display: block;
		margin-top: 20rpx;
		font-size: 24rpx;
		color: $uh-ink-3;
		line-height: 1.5;
	}

	.publish-actions {
		margin-top: 32rpx;
	}
</style>
