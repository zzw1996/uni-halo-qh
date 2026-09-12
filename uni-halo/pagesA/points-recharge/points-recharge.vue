<template>
	<view class="app-page">
		<view v-if="loading !== 'success'" class="loading-wrap">
			<tm-skeleton model="listAvatr"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
		</view>
		<block v-else>
			<view class="balance-card">
				<text class="balance-label">当前积分</text>
				<text class="balance-value">{{ balance }}</text>
				<text class="balance-sub">积分仅可通过浏览/评论/签到等行为免费获得，不支持现金购买</text>
			</view>

			<!-- ========== 权益道具（现金直购） ========== -->
			<view v-if="!payEnabled" class="section-card">
				<view class="section-title-row">
					<text class="section-title">🎟️ 权益道具</text>
					<!-- 支付未开启时也显示存量：券是此前购买的，用户需要能看到 -->
					<text v-if="contentTickets !== null" class="ticket-badge">阅读券 {{ contentTickets }} 张</text>
				</view>
				<view class="empty-tip">站点未开启虚拟支付，道具直购暂不可用；装扮仍可用积分兑换。</view>
			</view>
			<block v-else>
				<view class="section-card">
					<view class="section-title-row">
						<text class="section-title">🎟️ 权益道具</text>
						<text v-if="contentTickets !== null" class="ticket-badge">阅读券 {{ contentTickets }} 张</text>
						<text v-else class="section-sub">现金直购，支付成功后立即到账</text>
					</view>
					<view v-if="contentTickets !== null" class="ticket-hint">阅读券用于解锁带「付费」标签的文章，购买后张数会在这里实时更新</view>
					<view v-if="goodsList.length === 0" class="empty-tip">暂无可购买的权益道具</view>
					<view v-for="(item, index) in goodsList" :key="index" class="goods-item">
						<view class="goods-main">
							<text class="goods-name">{{ item.name }}</text>
							<text class="goods-points">{{ fnGoodsTip(item) }}</text>
						</view>
						<view class="goods-side">
							<text class="goods-price">￥{{ Number(item.price) / 100 }}</text>
							<tm-button
								size="s"
								theme="primary"
								:loading="payingIndex === index"
								@click="fnBuy(item, index)"
							>立即购买</tm-button>
						</view>
					</view>
				</view>
			</block>

			<!-- ========== 装扮商店 ==========
			     装扮的购买与佩戴都集中在这一页：积分兑换与现金直购并列，
			     已拥有则直接穿戴/脱下（身份页只做展示，不再重复一份列表）。 -->
			<view class="section-card">
				<view class="section-title-row">
					<text class="section-title">🎨 装扮商店</text>
					<text class="section-sub">{{ hasLimitedOutfit ? '积分兑换或现金购买 · 限时装扮到期自动失效' : '积分兑换或现金购买 · 永久持有' }}</text>
				</view>
				<view v-if="outfitStatus.items.length === 0" class="empty-tip">暂无装扮，请在插件后台「等级与称号装扮」中配置。</view>
				<view v-for="item in outfitStatus.items" :key="item.key" class="outfit-item" :class="{ 'is-active': isActive(item) }">
					<view class="outfit-preview" :class="outfitPreviewClass(item)">
						<image v-if="item.type === 'avatar_frame'" class="outfit-avatar" :src="previewAvatar" mode="aspectFill"></image>
						<text v-if="item.type === 'nick_frame'" class="outfit-nick">{{ nickPreviewText(item) }}</text>
						<view v-if="item.type === 'theme'" class="outfit-theme" :style="{ background: themeSwatchStyle(item.style) }"></view>
					</view>
					<view class="outfit-main">
						<view class="outfit-title-row">
							<text class="outfit-name">{{ item.name }}</text>
							<text class="outfit-tag">{{ outfitTypeLabel(item.type) }}</text>
						</view>
						<text v-if="item.type === 'theme'" class="outfit-skin">
							{{ themeSkinLabel(item.style) }}<text v-if="isInvalidThemeSkin(item.style)" class="outfit-invalid"> · 主题名未登记，穿戴后将回退默认配色</text>
						</text>
						<text v-if="item.type === 'avatar_frame'" class="outfit-skin">
							{{ avatarFrameLabelOf(item.style) }}<text v-if="isInvalidAvatarFrame(item.style)" class="outfit-invalid"> · 样式未登记，穿戴后不生效</text>
						</text>
						<text v-if="item.type === 'nick_frame'" class="outfit-skin">
							昵称前缀<text v-if="isInvalidNickFrame(item.style)" class="outfit-invalid"> · 前缀过长（建议 2 字符内），穿戴后不显示</text>
						</text>
						<text class="outfit-price">{{ item.points }} 积分<text v-if="isActive(item)" class="outfit-using"> · 使用中</text><text v-else-if="isOwned(item)" class="outfit-expire"> · {{ ownedExpiryText(item) }}</text><text v-else-if="outfitDurationLabel(item)" class="outfit-duration"> · {{ outfitDurationLabel(item) }}</text></text>
					</view>
					<view class="outfit-action">
						<!-- 未拥有：积分兑换为主；站长配了现金商品且支付已开启时，多给一个次级按钮 -->
						<tm-button
							v-if="!isOwned(item)"
							size="s"
							theme="green"
							:disabled="outfitBusy(item)"
							:loading="outfitBusy(item)"
							@click="fnBuyWithPoints(item)"
						>积分兑换 {{ item.points }}</tm-button>
						<tm-button
							v-if="!isOwned(item) && payEnabled && cashGoodsOf(item)"
							size="s"
							plan
							theme="blue-grey"
							:fllowTheme="false"
							:disabled="outfitBusy(item)"
							:loading="outfitBusy(item)"
							@click="fnBuyWithCash(item)"
						>￥{{ cashPriceText(cashGoodsOf(item).price) }} 购买</tm-button>
						<!-- 已拥有：穿戴 / 脱下 -->
						<tm-button
							v-if="isOwned(item)"
							size="s"
							:theme="outfitButtonTheme(item)"
							:fllowTheme="outfitButtonTheme(item) !== 'grey'"
							:disabled="outfitBusy(item)"
							:loading="outfitBusy(item)"
							@click="fnToggleEquip(item)"
						>{{ outfitButtonText(item) }}</tm-button>
					</view>
				</view>
				<view v-if="outfitStatus.orphanOwned.length" class="orphan-block">
					<text class="orphan-title">已下架装扮</text>
					<text class="orphan-desc">以下装扮你已购买，但站长已将其从装扮商店移除，当前无法穿戴：{{ outfitStatus.orphanOwned.join('、') }}。如需处理请联系站长。</text>
				</view>
			</view>

			<view class="footer-tip">
				<text>说明：装扮可积分兑换或现金购买，购买后在此页直接穿戴；道具权益（阅读券）在支付成功后立即到账，在文章详情页解锁使用。记录可在「积分明细」中查看；若订单需人工处理（如重复购买），页面会给出明确提示。</text>
				<text>虚拟支付由微信提供：个人主体小程序仅支持道具直购，不支持积分充值流转；Android 技术服务费约 1%、iOS 约 12%（180 天内退款退还技术服务费），月支付限额 10 万元。</text>
			</view>
		</block>
	</view>
</template>

<script>
	import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
	import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
	import { getAppConfigs } from '@/config/index.js';
	import { checkHasWxLogin, getWxLoginInfo } from '@/utils/auth.js';
	import { syncPointsAfterLogin, emitPointsUpdated } from '@/utils/points.js';
	import { payVirtualGoods, unwrapPayResponse } from '@/utils/virtualPay.js';
	import { avatarFrameClass, avatarFrameLabel, isUnregisteredAvatarFrame, nickFramePrefix, isUnregisteredNickFrame } from '@/utils/outfitSkin.js';
	import { applyThemeSkin, themeSwatchOf, themeLabelOf, isUnregisteredThemeSkin } from '@/utils/themeSkin.js';
	import { cacheOwnOutfit } from '@/utils/ownOutfit.js';

	const EMPTY_OUTFIT = {
		items: [],
		owned: [],
		ownedDetails: [],
		orphanOwned: [],
		avatarFrame: '',
		nickFrame: '',
		theme: ''
	};

	export default {
		components: {
			tmSkeleton,
			tmEmpty,
			tmButton
		},
		data() {
			return {
				loading: 'loading',
				balance: 0,
				goodsList: [],
				payingIndex: -1,
				/** 虚拟支付是否已开启：未开启时隐藏道具区与装扮的现金按钮，但不影响积分兑换 */
				payEnabled: false,
				/** 阅读券余额；null 表示尚未取到（旧插件/接口不可用），此时不展示，避免误显示成 0 张 */
				contentTickets: null,
				/** 装扮商店（含已拥有与穿戴状态） */
				outfitStatus: { ...EMPTY_OUTFIT },
				/** 装扮现金价：装扮 key → { goodsId, price } */
				cashGoodsMap: {},
				outfitBusyKey: ''
			};
		},
		computed: {
			/** 头像框预览用当前用户自己的头像 */
			previewAvatar() {
				return getWxLoginInfo()?.avatarUrl || '';
			},
			/** 装扮商店里是否存在限时装扮：决定副标题文案 */
			hasLimitedOutfit() {
				return this.outfitStatus.items.some((item) => Number(item.durationDays || 0) > 0);
			}
		},
		onLoad() {
			if (!checkHasWxLogin()) {
				uni.showToast({ title: '请先登录', icon: 'none' });
				setTimeout(() => uni.navigateBack(), 800);
				return;
			}
			this.fnLoad();
		},
		onShow() {
			// 从「中奖填写」等页面返回时同步一次装扮状态，避免穿戴/购买结果滞后
			this.fnLoadOutfit();
		},
		methods: {
			async fnLoad() {
				// 先用启动时缓存的插件配置（getConfigs 已含 payConfig 与商品）即时渲染
				const cached = (getAppConfigs()?.payConfig) || {};
				if (cached?.enabled === true && Array.isArray(cached?.goods)) {
					this.payEnabled = true;
					this.goodsList = this.normalizeGoods(cached.goods);
					this.loading = 'success';
				}
				// 装扮商店与支付功能解耦：先拉装扮，保证未开通虚拟支付的站点也能积分兑换
				this.fnLoadOutfit();
				this.fnLoadCashGoods();
				this.fnLoadContentTickets();
				try {
					const overview = await syncPointsAfterLogin(this.$httpApi.v2);
					if (overview) {
						this.balance = Number(overview.balance || 0);
					}
					const res = await this.$httpApi.v2.getVirtualPayConfig();
					const body = unwrapPayResponse(res);
					this.payEnabled = body.enabled === true;
					if (!this.payEnabled) {
						// 未开通虚拟支付：不再退出页面（积分兑换装扮不依赖支付），只隐藏现金相关部分
						this.goodsList = [];
						this.loading = 'success';
						return;
					}
					const goods = (body.goods && body.goods.length ? body.goods : cached?.goods) || [];
					this.goodsList = this.normalizeGoods(goods);
					this.loading = 'success';
				} catch (err) {
					this.loading = 'success';
					const notUpgraded = err?.statusCode === 404 || err?.isHtml
						|| String(err?.error || '').includes('404');
					uni.showToast({
						title: notUpgraded
							? '权益直购需要插件 v1.0.78+，请先更新插件'
							: (err?.error || '页面加载失败，请稍后重试'),
						icon: 'none'
					});
				}
			},
			/**
			 * 拉取装扮商店状态（列表 / 已拥有 / 穿戴中）。
			 *
			 * 这里同时承担两处「必须跟着商店走」的副作用：
			 * - applyThemeSkin：穿戴主题后全站立即换肤，不等重启；
			 * - cacheOwnOutfit：评论头像框、我的页头部读的是这份本地缓存。
			 * 漏掉任何一处，用户都会觉得「穿戴了但没生效」。
			 */
			/**
			 * 拉取阅读券余额。
			 *
			 * 券买了之后此前只在「付费文章的解锁卡片」里能看到，用户在别处找不到自己有几张券，
			 * 因此在道具区固定展示余额。接口不可用（旧插件）时保持 null 并不渲染，
			 * 避免把「未知」显示成「0 张」。
			 */
			async fnLoadContentTickets() {
				try {
					const res = await this.$httpApi.v2.getContentTickets();
					const body = res?.data || res || {};
					const tickets = Number(body.tickets);
					this.contentTickets = Number.isFinite(tickets) ? tickets : null;
				} catch (err) {
					this.contentTickets = null;
				}
			},
			/**
			 * 拉取支付配置，建立「装扮 key → 现金商品」映射。
			 *
			 * 装扮的现金商品配在插件【虚拟支付（权益直购）】里，其 style 字段填的就是装扮 key
			 * （后端下单前用同一个 key 反查装扮），因此按 style 建映射即可，无需后端新增接口；
			 * 未登记的装扮商品后端已不下发（v1.0.98+）。
			 * 失败一律按「没有现金商品」处理——装扮仍可用积分兑换。
			 */
			async fnLoadCashGoods() {
				try {
					const res = await this.$httpApi.v2.getVirtualPayConfig();
					const body = unwrapPayResponse(res);
					if (body.enabled !== true || !Array.isArray(body.goods)) {
						this.cashGoodsMap = {};
						return;
					}
					const map = {};
					body.goods.forEach((goods) => {
						if (!goods || goods.type !== 'outfit') return;
						const key = goods.style;
						if (!key || !goods.goodsId || !(Number(goods.price) > 0)) return;
						if (map[key]) {
							// 同一装扮配了多条现金商品：取第一条，并提示站长清理配置
							console.warn('[recharge] 装扮配置了多条现金商品，仅使用第一条：key=' + key);
							return;
						}
						map[key] = { goodsId: goods.goodsId, price: Number(goods.price) };
					});
					this.cashGoodsMap = map;
				} catch (err) {
					// 未登录 / 旧插件 / 支付未开启：视为没有现金商品，装扮仍可积分兑换
					this.cashGoodsMap = {};
				}
			},
			async fnLoadOutfit() {
				try {
					const outfitRes = await this.$httpApi.v2.getOutfitStatus();
					this.outfitStatus = this.normalizeOutfit(outfitRes);
					applyThemeSkin(this.outfitStatus.theme);
					cacheOwnOutfit(this.outfitStatus);
				} catch (err) {
					// 静默：装扮商店加载失败不影响道具直购与积分展示
				}
			},
			normalizeOutfit(res) {
				const data = res?.data || res || {};
				return {
					enabled: data.enabled !== false,
					items: Array.isArray(data.items) ? data.items : [],
					owned: Array.isArray(data.owned) ? data.owned : [],
					// 到期明细：插件 v1.0.98+ 才下发，旧版插件下为空数组，
					// 此时按「永久」展示，不影响老版本组合使用。
					ownedDetails: Array.isArray(data.ownedDetails) ? data.ownedDetails : [],
					orphanOwned: Array.isArray(data.orphanOwned) ? data.orphanOwned : [],
					avatarFrame: data.avatarFrame || '',
					nickFrame: data.nickFrame || '',
					theme: data.theme || ''
				};
			},
			titleEmoji(type) {
				return TITLE_ICONS[type] || '🏅';
			},
			outfitTypeLabel(type) {
				return { avatar_frame: '头像框', nick_frame: '昵称前缀', theme: '主题色' }[type] || type;
			},
			themeSwatchStyle(style) {
				// 新格式（主题名）取该主题主色；旧格式（CSS 渐变值）仍返回渐变，
				// 保证已配置的旧装扮预览不空白
				return themeSwatchOf(style);
			},
			/** 主题皮肤展示名：登记过的显示中文名，旧渐变值原样展示 */
			themeSkinLabel(style) {
				return themeLabelOf(style);
			},
			/**
			 * 主题名是否无效（未在白名单登记、也不是可映射的旧渐变值）。
			 * 站长填错 style（如拼成 gree）时会静默回退默认主题，
			 * 用户付了积分却看不到变化，因此必须显式提示，而不是无声失败。
			 */
			isInvalidThemeSkin(style) {
				return isUnregisteredThemeSkin(style);
			},
			/**
			 * 装扮预览样式：头像框预览由注册表派生（gold/rainbow/silver → is-frame-xxx）。
			 * 原先按 gold/rainbow 硬编码，站长新增样式（如 silver）时预览会空白且不报错。
			 */
			outfitPreviewClass(item) {
				if (item.type !== 'avatar_frame') return '';
				const frameClass = avatarFrameClass(item.style);
				return frameClass ? `is-${frameClass}` : '';
			},
			/** 昵称前缀预览：注册表命中用其前缀，否则透传 style 文本（配置契约：style 即前缀文本）。 */
			nickPreviewText(item) {
				return nickFramePrefix(item.style) || (item.style || '');
			},
			avatarFrameLabelOf(style) {
				return avatarFrameLabel(style);
			},
			isInvalidAvatarFrame(style) {
				return isUnregisteredAvatarFrame(style);
			},
			isInvalidNickFrame(style) {
				return isUnregisteredNickFrame(style);
			},
			isOwned(item) {
				return this.outfitStatus.owned.includes(item.key);
			},
			/** 取该装扮的到期明细（旧版插件不下发时为 null，按永久处理）。 */
			ownedDetailOf(key) {
				return this.outfitStatus.ownedDetails.find((entry) => entry && entry.key === key) || null;
			},
			/** 已拥有装扮的有效期文案：永久 / 剩余 N 天（不足一天按一天计）。 */
			ownedExpiryText(item) {
				const detail = this.ownedDetailOf(item.key);
				if (!detail || detail.permanent || detail.expireAt == null) return '永久';
				const remainSeconds = Number(detail.expireAt) - Math.floor(Date.now() / 1000);
				if (!Number.isFinite(remainSeconds)) return '永久';
				if (remainSeconds <= 0) return '已过期';
				return `剩余 ${Math.max(1, Math.ceil(remainSeconds / 86400))} 天`;
			},
			/** 未拥有时的限时说明（配置了 durationDays 才显示）。 */
			outfitDurationLabel(item) {
				const days = Number(item.durationDays || 0);
				return days > 0 ? `限时 ${days} 天` : '';
			},
			/** 装扮商店里是否存在限时装扮：决定「永久持有」类文案是否如实改写。 */
			hasLimitedOutfit() {
				return this.outfitStatus.items.some((item) => Number(item.durationDays || 0) > 0);
			},
			isActive(item) {
				if (!this.isOwned(item)) return false;
				if (item.type === 'avatar_frame') return this.outfitStatus.avatarFrame === item.style;
				if (item.type === 'nick_frame') return this.outfitStatus.nickFrame === item.style;
				if (item.type === 'theme') return this.outfitStatus.theme === item.style;
				return false;
			},
			outfitButtonText(item) {
				if (this.isActive(item)) return '脱下';
				if (this.isOwned(item)) return '穿戴';
				return `购买 · ${item.points}`;
			},
			outfitButtonTheme(item) {
				if (this.isActive(item)) return 'grey';
				if (this.isOwned(item)) return 'primary';
				return 'green';
			},
			outfitBusy(item) {
				return this.outfitBusyKey === item.key;
			},
			/** 该装扮是否配了现金商品（未配则为 null，只显示积分按钮）。 */
			cashGoodsOf(item) {
				return (item && this.cashGoodsMap[item.key]) || null;
			},
			/** 现金价显示口径与权益直购页一致：分 → 元，不做四舍五入加工。 */
			cashPriceText(price) {
				return Number(price || 0) / 100;
			},
			/** 积分兑换装扮（原 fnOutfitAction 的购买分支）。 */
			async fnBuyWithPoints(item) {
				if (this.outfitBusyKey) return;
				this.outfitBusyKey = item.key;
				try {
					const res = await this.$httpApi.v2.buyOutfitItem(item.key);
					const data = res?.data || res || {};
					if (data?.error && !data?.owned) {
						uni.showToast({ title: data.error, icon: 'none' });
						return;
					}
					uni.showToast({ title: data?.message || '购买成功', icon: 'none' });
					if (data?.balance != null) {
						this.balance = Number(data.balance);
						// 广播给其它页面（我的页积分卡、身份中心等）
						emitPointsUpdated({ balance: this.balance });
					}
					await this.fnLoadOutfit();
				} catch (err) {
					uni.showToast({ title: err?.error || '操作失败，请稍后重试', icon: 'none' });
				} finally {
					this.outfitBusyKey = '';
				}
			},
			/** 穿戴 / 脱下（原 fnOutfitAction 的佩戴分支）。 */
			async fnToggleEquip(item) {
				if (this.outfitBusyKey) return;
				this.outfitBusyKey = item.key;
				try {
					const res = await this.$httpApi.v2.equipOutfitItem(this.isActive(item) ? '' : item.key);
					const data = res?.data || res || {};
					if (data?.error) {
						uni.showToast({ title: data.error, icon: 'none' });
						return;
					}
					uni.showToast({ title: data?.message || '已更新装扮', icon: 'none' });
					// 只刷新装扮状态：穿脱不涉及积分与商品列表
					await this.fnLoadOutfit();
				} catch (err) {
					uni.showToast({ title: err?.error || '操作失败，请稍后重试', icon: 'none' });
				} finally {
					this.outfitBusyKey = '';
				}
			},
			/**
			 * 现金直购装扮。
			 *
			 * 与充值页走完全相同的链路（utils/virtualPay.js 的 payVirtualGoods）：
			 * 下单前由后端校验装扮已登记且未拥有，发货以服务端推送/查单为准，
			 * 前端支付回调只作展示。因此两次点击不会重复扣款（并发竞态由后端拦成
			 * needs_refund 终态，这里会明确告诉用户联系站长）。
			 */
			async fnBuyWithCash(item) {
				if (this.outfitBusyKey) return;
				const goods = this.cashGoodsOf(item);
				if (!goods) return;
				this.outfitBusyKey = item.key;
				// 只在真正显示过「确认到账中」时才隐藏，避免未配对的 hideLoading
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
						uni.showToast({ title: '购买成功，可立即穿戴', icon: 'success' });
						// 刷新后该装扮变为「已拥有」，按钮自动切换成穿戴
						await this.fnLoadOutfit();
					} else if (result.envBlocked) {
						uni.showModal({ title: '支付受限', content: result.reason, showCancel: false });
					} else if (result.cancelled) {
						uni.showToast({ title: result.reason || '支付已取消', icon: 'none' });
					} else if (result.terminal) {
						uni.showModal({
							title: '订单需人工处理',
							content: result.message
								|| '该装扮你已拥有，本次购买未重复发放，请联系站长退款。',
							showCancel: false
						});
					} else if (result.pending) {
						uni.showToast({ title: '支付成功，装扮稍后自动到账', icon: 'none' });
						await this.fnLoadOutfit();
					} else {
						uni.showToast({ title: result.error || '购买失败，请稍后重试', icon: 'none' });
					}
				} finally {
					if (waiting) uni.hideLoading();
					this.outfitBusyKey = '';
				}
			},
			normalizeGoods(goods) {
				// 个人主体仅支持道具直购：前端只接受在售权益类型，
				// 过滤掉 type 缺失或 points（现金充值积分）的商品，双重防止充值通道出现。
				// 装扮已迁到「身份中心 → 装扮商店」销售（那里可同时选积分或现金），
				// 因此本页不再接收 outfit；其余已下架类型（dashboard / publish_ticket）
				// 后端 parseConfig 也不下发，此处同步移除以保持一致。
				const ALLOWED = ['content_ticket'];
				return (Array.isArray(goods) ? goods : [])
					.filter((item) => item && item.goodsId && ALLOWED.includes(item.type))
					.map((item) => ({
						goodsId: item.goodsId,
						name: item.name,
						price: Number(item.price || 0),
						type: item.type,
						value: Number(item.value || 0),
						style: item.style || ''
					}));
			},
			fnGoodsTip(item) {
				if (item.type === 'content_ticket') {
					return `阅读券 x${item.value || 1}（解锁付费文章，每张解锁一篇）`;
				}
				// 已下架的 publish_ticket / dashboard 不会由后端下发，此处仅作兜底
				return '权益道具（支付成功后立即到账）';
			},
			async fnBuy(item, index) {
				if (this.payingIndex >= 0) return;
				this.payingIndex = index;
				// 只在真正显示过「确认到账中」时才隐藏，避免未配对的 hideLoading
				let waiting = false;
				try {
					const result = await payVirtualGoods({
						apiV2: this.$httpApi.v2,
						goodsId: item.goodsId,
						// 支付成功（前端回调仅作展示，发货以服务器推送/查单为准）
						onWaiting: () => {
							waiting = true;
							uni.showLoading({ title: '确认到账中...' });
						}
					});
					if (result.ok) {
						// 阅读券：刷新余额并提示张数，让用户立刻看到券到账
						await this.fnLoadContentTickets();
						const bought = Number(item.value || 0) || 1;
						uni.showToast({
							title: `购买成功，已到账 ${bought} 张阅读券`,
							icon: 'success'
						});
					} else if (result.envBlocked) {
						uni.showModal({ title: '支付受限', content: result.reason, showCancel: false });
					} else if (result.cancelled) {
						uni.showToast({ title: result.reason || '支付已取消', icon: 'none' });
					} else if (result.terminal) {
						// 终态不可发货（重复购买等）：必须说清发生了什么并给出去处，
						// 不能只说「稍后自动到账」——那是在骗用户。
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
			}
		}
	};
</script>

<style scoped lang="scss">
	.app-page {
		min-height: 100vh;
		background: #f5f6fa;
		padding-bottom: 40rpx;
	}

	.loading-wrap {
		padding: 24rpx;
	}

	.balance-card {
		margin: 24rpx 28rpx 16rpx;
		padding: 32rpx 28rpx;
		border-radius: 20rpx;
		background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
		color: #fff;
	}

	.balance-label {
		display: block;
		font-size: 28rpx;
		opacity: 0.92;
	}

	.balance-value {
		display: block;
		margin-top: 8rpx;
		font-size: 56rpx;
		font-weight: 700;
		line-height: 1.1;
	}

	.balance-sub {
		display: block;
		margin-top: 10rpx;
		font-size: 20rpx;
		line-height: 1.5;
		opacity: 0.85;
	}

	/* 权益道具行：位于「权益道具」卡片内部，用分隔线而非独立白卡 */
	.goods-item {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 20rpx 0;
		border-bottom: 2rpx solid #f5f6fa;
	}

	.goods-item:last-child {
		border-bottom: none;
		padding-bottom: 0;
	}


	.goods-main {
		flex: 1;
		min-width: 0;
		padding-right: 20rpx;
	}

	.goods-name {
		display: block;
		font-size: 30rpx;
		font-weight: 600;
		color: #303133;
	}

	.goods-points {
		display: block;
		margin-top: 8rpx;
		font-size: 24rpx;
		color: #909399;
	}

	.goods-side {
		flex-shrink: 0;
		text-align: right;
	}

	.goods-price {
		display: block;
		margin-bottom: 12rpx;
		font-size: 30rpx;
		font-weight: 700;
		color: #e53935;
	}

	.footer-tip {
		margin: 32rpx 28rpx 0;
		padding: 24rpx;
		border-radius: 12rpx;
		background: #fff7e6;
	}

	.footer-tip text {
		display: block;
		font-size: 22rpx;
		line-height: 1.7;
		color: #b3800a;

		& + text {
			margin-top: 8rpx;
		}
	}

	.empty {
		padding-top: 80rpx;
	}

	/* ===== 装扮商店（自身份页迁入：购买与穿戴都集中在本页） ===== */
	.section-card {
		background: #fff;
		border-radius: 20rpx;
		padding: 28rpx;
		margin-bottom: 20rpx;
		box-shadow: 0 6rpx 20rpx rgba(31, 41, 55, 0.05);
	}

	.section-title-row {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		margin-bottom: 22rpx;
	}

	.section-title {
		font-size: 30rpx;
		font-weight: 700;
		color: #303133;
	}

	/* 阅读券余额徽标：购买后张数在此实时更新，避免用户「找不到券」 */
	.ticket-badge {
		flex-shrink: 0;
		font-size: 22rpx;
		font-weight: 600;
		color: #3a6ee0;
		background: #e8f1ff;
		padding: 6rpx 16rpx;
		border-radius: 999rpx;
	}

	.ticket-hint {
		margin-top: 10rpx;
		font-size: 20rpx;
		color: #b0b5c0;
	}

	.section-sub {
		font-size: 20rpx;
		color: #909399;
	}

	.empty-tip {
		font-size: 22rpx;
		line-height: 1.6;
		color: #909399;
	}

	.outfit-item {
		display: flex;
		align-items: center;
		padding: 20rpx;
		border-radius: 16rpx;
		background: #fbfbfd;
		border: 2rpx solid #f0f1f5;
		margin-bottom: 16rpx;

		&:last-child {
			margin-bottom: 0;
		}

		&.is-active {
			background: #f2fbe8;
			border-color: #a8e06a;
		}
	}

	.outfit-preview {
		position: relative;
		width: 100rpx;
		height: 100rpx;
		border-radius: 20rpx;
		background: #f3f4f7;
		display: flex;
		align-items: center;
		justify-content: center;
		flex-shrink: 0;
		overflow: hidden;

		&.is-frame-gold {
			border-radius: 50%;
			border: 5rpx solid #f5c542;
			box-shadow: 0 0 14rpx rgba(245, 197, 66, 0.4);
		}

		&.is-frame-rainbow {
			border-radius: 50%;
			border: 5rpx solid transparent;
			background: linear-gradient(#f3f4f7, #f3f4f7) padding-box,
				linear-gradient(135deg, #f79ea3, #9b8cff, #79d6f2) border-box;
			box-shadow: 0 0 14rpx rgba(155, 140, 255, 0.35);
		}

		&.is-frame-silver {
			border-radius: 50%;
			border: 5rpx solid #c3cbd8;
			box-shadow: 0 0 14rpx rgba(195, 203, 216, 0.5);
		}
	}

	.outfit-avatar {
		width: 100%;
		height: 100%;
	}

	.outfit-nick {
		font-size: 52rpx;
		line-height: 1;
	}

	.outfit-theme {
		position: absolute;
		left: 0;
		top: 0;
		right: 0;
		bottom: 0;
	}

	.outfit-main {
		flex: 1;
		min-width: 0;
		margin: 0 16rpx;
	}

	.outfit-title-row {
		display: flex;
		align-items: center;
		gap: 10rpx;
	}

	.outfit-name {
		font-size: 28rpx;
		font-weight: 600;
		color: #303133;
	}

	.outfit-tag {
		font-size: 18rpx;
		color: #909399;
		background: #eef0f4;
		padding: 2rpx 10rpx;
		border-radius: 6rpx;
	}

	.outfit-price {
		display: block;
		margin-top: 8rpx;
		font-size: 22rpx;
		color: #f56c6c;
	}

	.outfit-skin {
		display: block;
		margin-top: 6rpx;
		font-size: 21rpx;
		color: #909399;
	}

	.outfit-invalid {
		color: #e6a23c;
	}

	.outfit-using {
		color: #67a23a;
		font-weight: 600;
	}

	.outfit-expire {
		color: #e6a23c;
	}

	.outfit-duration {
		color: #909399;
	}

	.orphan-block {
		margin-top: 18rpx;
		padding: 18rpx 20rpx;
		border-radius: 12rpx;
		background: #fdf6ec;
	}

	.orphan-title {
		display: block;
		font-size: 24rpx;
		font-weight: 600;
		color: #b3800a;
	}

	.orphan-desc {
		display: block;
		margin-top: 8rpx;
		font-size: 21rpx;
		line-height: 1.7;
		color: #b3800a;
	}

	.outfit-action {
		flex-shrink: 0;
		/* 两个买法（积分 / 现金）纵向并列，避免撑破卡片 */
		display: flex;
		flex-direction: column;
		align-items: flex-end;
	}
</style>
