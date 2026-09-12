<template>
	<view class="app-page">
		<view class="claim-card">
			<text class="claim-emoji">{{ emoji }}</text>
			<text class="claim-title">恭喜中奖「{{ name }}」</text>
			<text class="claim-sub">{{ needForm ? '请填写收货信息，站长将按此地址安排发放奖品' : '这是你的礼品发放进度，请留意查收' }}</text>
		</view>

		<!-- 待填写：收货信息表单 -->
		<block v-if="needForm">
			<view class="form-card">
				<view class="form-item">
					<text class="form-label">收货人</text>
					<input class="form-input" v-model="form.contactName" placeholder="请输入收货人姓名" maxlength="20" />
				</view>
				<view class="form-item">
					<text class="form-label">手机号码</text>
					<input class="form-input" v-model="form.phone" type="number" placeholder="请输入 11 位手机号" maxlength="11" />
				</view>
				<view class="form-item">
					<text class="form-label">收货地址</text>
					<textarea class="form-textarea" v-model="form.address" placeholder="省市区 + 详细地址" maxlength="200" />
				</view>
			</view>

			<view class="form-actions">
				<tm-button
					theme="orange"
					:block="true"
					:loading="submitting"
					@click="fnSubmit"
				>提交收货信息</tm-button>
			</view>

			<view class="footer-tip">
				<text>提交后不可修改，请确认信息准确；提交后可随时在本页查看礼品发放进度。</text>
			</view>
		</block>

		<!-- 已提交 / 已发货：发放进度 -->
		<block v-else>
			<view class="progress-card">
				<view class="progress-row">
					<text class="progress-label">发放状态</text>
					<text class="progress-value" :class="claim && claim.status === 'shipped' ? 'progress-ok' : ''">
						{{ claim && claim.status === 'shipped' ? '已发货' : '已提交，站长安排发放中' }}
					</text>
				</view>
				<view class="progress-row">
					<text class="progress-label">参与时间</text>
					<text class="progress-value">{{ formatTimeSafe(claim && claim.winTime) }}</text>
				</view>
				<template v-if="claim && claim.status === 'submitted'">
					<view class="progress-row">
						<text class="progress-label">收货人</text>
						<text class="progress-value">{{ claim.contactName || '—' }}</text>
					</view>
					<view class="progress-row">
						<text class="progress-label">手机号</text>
						<text class="progress-value">{{ claim.phone || '—' }}</text>
					</view>
					<view class="progress-row">
						<text class="progress-label">收货地址</text>
						<text class="progress-value">{{ claim.address || '—' }}</text>
					</view>
				</template>
				<template v-if="claim && claim.status === 'shipped'">
					<view class="progress-row">
						<text class="progress-label">快递公司</text>
						<text class="progress-value">{{ claim.expressCompany || '—' }}</text>
					</view>
					<view class="progress-row">
						<text class="progress-label">物流单号</text>
						<text class="progress-value">{{ claim.expressNo || '—' }}</text>
					</view>
					<view class="progress-row">
						<text class="progress-label">发货时间</text>
						<text class="progress-value">{{ formatTimeSafe(claim.shipTime) }}</text>
					</view>
				</template>
				<view class="progress-tip">
					<text>{{ claim && claim.status === 'shipped' ? '礼品已发出，请保持手机畅通，注意查收～' : '已按你提交的收货信息安排，发放后本页会显示物流信息；也可在「我的」页 →「抽奖记录与消耗明细」随时查看进度。' }}</text>
				</view>
			</view>
		</block>
	</view>
</template>

<script>
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
	import { checkHasWxLogin } from '@/utils/auth.js';
	import { formatTimeSafe } from '@/utils/time.js';

	export default {
		components: {
			tmButton
		},
		data() {
			return {
				winningId: '',
				name: '',
				emoji: '🎁',
				submitting: false,
				claim: null,
				needForm: true,
				/** 从兑换页带入的状态（find 失败时用它兜底决策视图） */
				incomingStatus: '',
				form: {
					contactName: '',
					phone: '',
					address: ''
				}
			};
		},
		onLoad(e) {
			if (!checkHasWxLogin()) {
				uni.showToast({ title: '请先登录', icon: 'none' });
				setTimeout(() => uni.navigateBack(), 800);
				return;
			}
			this.winningId = decodeURIComponent(e?.winningId || '');
			this.name = decodeURIComponent(e?.name || '');
			this.emoji = decodeURIComponent(e?.emoji || '🎁');
			this.incomingStatus = decodeURIComponent(e?.status || '');
			if (!this.winningId) {
				uni.showToast({ title: '缺少中奖记录，请重新抽奖', icon: 'none' });
				setTimeout(() => uni.navigateBack(), 800);
				return;
			}
			// 已提交/已发货的单直接展示进度，待填写显示表单
			this.needForm = this.incomingStatus !== 'submitted' && this.incomingStatus !== 'shipped';
		},
		onShow() {
			// 每次进入页面都刷新发放状态，站长发货后回来即可看到最新进度
			if (this.winningId) {
				this.fnLoadProgress();
			}
		},
		methods: {
			/** 时间格式化（兼容 ISO 字符串 / 秒级或毫秒级时间戳；无法解析时返回「—」，不会渲染成 1970） */
			formatTimeSafe(value) {
				return formatTimeSafe(value);
			},
			/** 拉取本单的发放进度：已提交/已发货时展示结果，仍处于待填写时展示表单 */
			async fnLoadProgress() {
				try {
					const res = await this.$httpApi.v2.getMyLuckyClaims();
					const raw = Array.isArray(res) ? res : (res?.data || res || {});
					const list = Array.isArray(raw) ? raw : (Array.isArray(raw.items) ? raw.items : []);
					const mine = list.find((item) => item?.winningId === this.winningId || item?.id === this.winningId);
					if (mine) {
						this.claim = mine;
						// 只有「已提交 / 已发货」才收起表单；pending（待填写）必须继续显示表单。
						// 原实现写成 `status !== 'pending'`，正好反了——进入中奖页会直接显示
						// 「已提交」，用户根本没机会填收货信息。
						this.needForm = mine.status !== 'submitted' && mine.status !== 'shipped';
					} else if (this.incomingStatus && this.incomingStatus !== 'pending') {
						// 接口暂时匹配不到时，按进入状态兜底展示进度，避免误显示表单
						this.claim = { winningId: this.winningId, status: this.incomingStatus };
						this.needForm = false;
					}
				} catch (err) {
					// 静默：进度拉取失败仍展示表单（待填写场景不受影响）
				}
			},
			async fnSubmit() {
				if (this.submitting) return;
				const name = (this.form.contactName || '').trim();
				const phone = (this.form.phone || '').trim();
				const address = (this.form.address || '').trim();
				if (!name) {
					uni.showToast({ title: '请填写收货人', icon: 'none' });
					return;
				}
				if (!/^1[0-9]{10}$/.test(phone)) {
					uni.showToast({ title: '请填写正确的手机号', icon: 'none' });
					return;
				}
				if (address.length < 5) {
					uni.showToast({ title: '请填写详细收货地址', icon: 'none' });
					return;
				}
				this.submitting = true;
				try {
					const res = await this.$httpApi.v2.submitLuckyClaim({
						winningId: this.winningId,
						contactName: name,
						phone,
						address
					});
					const data = res?.data || res || {};
					if (data?.error) {
						uni.showToast({ title: data.error, icon: 'none' });
						return;
					}
					this.needForm = false;
					uni.showToast({ title: data?.message || '收货信息已提交', icon: 'success' });
					// 重新拉取服务端状态，回显已提交的收货信息
					await this.fnLoadProgress();
				} catch (err) {
					uni.showToast({ title: err?.error || '提交失败，请稍后重试', icon: 'none' });
				} finally {
					this.submitting = false;
				}
			}
		}
	};
</script>

<style scoped lang="scss">
	.app-page {
		min-height: 100vh;
		background: #f5f6fa;
		padding: 24rpx 24rpx 48rpx;
	}

	.claim-card {
		padding: 36rpx 28rpx;
		border-radius: 20rpx;
		background: linear-gradient(135deg, #ff9a56 0%, #ff5e7e 100%);
		color: #fff;
		text-align: center;
		margin-bottom: 24rpx;
	}

	.claim-emoji {
		display: block;
		font-size: 72rpx;
		line-height: 1.2;
	}

	.claim-title {
		display: block;
		margin-top: 16rpx;
		font-size: 32rpx;
		font-weight: 700;
	}

	.claim-sub {
		display: block;
		margin-top: 8rpx;
		font-size: 22rpx;
		opacity: 0.9;
	}

	.form-card {
		background: #fff;
		border-radius: 20rpx;
		padding: 28rpx;
	}

	.form-item {
		margin-bottom: 24rpx;

		&:last-child {
			margin-bottom: 0;
		}
	}

	.form-label {
		display: block;
		font-size: 24rpx;
		color: #606266;
		margin-bottom: 10rpx;
	}

	.form-input {
		height: 76rpx;
		background: #f5f6fa;
		border-radius: 12rpx;
		padding: 0 20rpx;
		font-size: 28rpx;
		color: #303133;
	}

	.form-textarea {
		width: 100%;
		height: 160rpx;
		background: #f5f6fa;
		border-radius: 12rpx;
		padding: 16rpx 20rpx;
		font-size: 28rpx;
		color: #303133;
		box-sizing: border-box;
	}

	.form-actions {
		margin-top: 32rpx;
	}

	.footer-tip {
		margin-top: 24rpx;
		padding: 24rpx;
		border-radius: 12rpx;
		background: #fff7e6;
	}

	.footer-tip text {
		display: block;
		font-size: 22rpx;
		line-height: 1.7;
		color: #b3800a;
	}

	/* ===== 发放进度 ===== */
	.progress-card {
		background: #fff;
		border-radius: 20rpx;
		padding: 28rpx;
	}

	.progress-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 18rpx 0;
		border-bottom: 2rpx solid #f5f6fa;

		&:first-child {
			padding-top: 0;
		}

		&:last-of-type {
			border-bottom: none;
		}
	}

	.progress-label {
		font-size: 24rpx;
		color: #909399;
	}

	.progress-value {
		font-size: 26rpx;
		color: #303133;
	}

	.progress-ok {
		color: #3a9e3a;
		font-weight: 700;
	}

	.progress-tip {
		margin-top: 24rpx;
		padding: 24rpx;
		border-radius: 12rpx;
		background: #fff7e6;
	}

	.progress-tip text {
		display: block;
		font-size: 22rpx;
		line-height: 1.7;
		color: #b3800a;
	}
</style>
