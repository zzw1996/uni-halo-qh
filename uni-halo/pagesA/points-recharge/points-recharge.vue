<template>
	<view class="app-page">
		<view v-if="loading !== 'success'" class="loading-wrap">
			<tm-skeleton model="listAvatr"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
		</view>
		<block v-else>
			<!-- ===== 资产总览：积分 + 阅读券 ===== -->
			<view class="asset-card">
				<view class="asset-main">
					<text class="asset-label">我的积分</text>
					<text class="asset-value">{{ balance }}</text>
				</view>
				<view class="asset-side">
					<view class="asset-chip">
						<text class="asset-chip-label">阅读券</text>
						<text class="asset-chip-value">{{ contentTickets === null ? '—' : contentTickets }}</text>
					</view>
					<text class="asset-note">积分由浏览 / 评论 / 签到等行为免费获得，不支持购买</text>
				</view>
			</view>

			<!-- ===== 权益道具 ===== -->
			<view v-if="payEnabled && goodsList.length" class="section">
				<view class="section-head">
					<text class="section-title">权益道具</text>
					<text class="section-sub">支付成功后立即到账，用于解锁「付费」文章</text>
				</view>
				<!--
					会员状态：已开通显示到期时间与权益；未开通且有在售会员卡时，
					由下面的商品卡片承担引导，这里不重复说一遍。
				-->
				<view v-if="membership.active" class="member-card">
					<view class="member-head">
						<text class="member-badge">会员</text>
						<text class="member-expire">{{ membership.expireAt }} 到期</text>
					</view>
					<text class="member-days">剩余 {{ membership.daysRemaining }} 天</text>
					<view v-if="membershipBenefits.length" class="member-benefits">
						<text v-for="(b, i) in membershipBenefits" :key="i" class="member-benefit">· {{ b }}</text>
					</view>
					<text class="member-note">会员期内阅读付费文章不消耗阅读券</text>
				</view>

				<!-- 首单礼：把「第一笔多送券」摆在购买按钮之前，直接打掉第一次付钱的心理门槛 -->
				<view v-if="firstPurchaseTicketBonus > 0" class="promo-banner">
					<text class="promo-banner-tag">首单礼</text>
					<text class="promo-banner-text">首次购买额外赠送 {{ firstPurchaseTicketBonus }} 张阅读券</text>
				</view>
				<view class="goods-list">
					<view v-for="(item, index) in goodsList" :key="index" class="goods-card">
						<view v-if="isPromoActive(item)" class="goods-promo">
							<text class="goods-promo-tag">限时特价</text>
							<text class="goods-promo-clock">{{ promoRemainText(item) }}</text>
						</view>
						<view class="goods-head">
							<text class="goods-name">{{ item.name }}</text>
							<view class="goods-price-box">
								<text v-if="hasOriginalPrice(item)" class="goods-price-origin">￥{{ cashPriceText(item.originalPrice) }}</text>
								<text class="goods-price"><text class="goods-price-symbol">￥</text>{{ cashPriceText(item.price) }}</text>
							</view>
						</view>
						<text class="goods-desc">{{ fnGoodsTip(item) }}</text>
						<!-- 券包阶梯：把「单张均价」算给用户看，买过一次的人第二次自然会算这笔账 -->
						<view v-if="unitPriceText(item)" class="goods-unit">
							<text class="goods-unit-text">折合每张 {{ unitPriceText(item) }}</text>
							<text v-if="savingText(item)" class="goods-unit-save">比单张省 {{ savingText(item) }}</text>
						</view>
						<tm-button
							size="s"
							theme="primary"
							block
							:loading="payingIndex === index"
							@click="fnBuy(item, index)"
						>立即购买</tm-button>
					</view>
				</view>
			</view>

			<!-- ===== 积分兑换阅读券 =====
			     券此前只有「花现金」一条路（积分兑换项还要站长手工在积分商城配置），
			     这里是未开通虚拟支付的站点上唯一的免费入口，因此始终展示。 -->
			<view v-if="exchangeTicket" class="section">
				<view class="section-head">
					<text class="section-title">用积分兑换</text>
					<text class="section-sub">当前积分 {{ balance }}</text>
				</view>
				<view class="exchange-card">
					<view class="exchange-main">
						<text class="exchange-name">{{ exchangeTicket.name }}</text>
						<text class="exchange-desc">{{ exchangeTicket.description }}</text>
						<text class="exchange-quota">今日已兑 {{ exchangeTicket.todayCount }}/{{ exchangeTicket.dailyMaxCount }} 次</text>
					</view>
					<view class="exchange-side">
						<text class="exchange-cost">{{ exchangeTicket.points }} 积分</text>
						<tm-button
							size="s"
							:theme="exchangeDisabled ? 'grey' : 'primary'"
							:fllowTheme="!exchangeDisabled"
							:disabled="exchangeDisabled"
							:loading="exchanging"
							@click="fnExchangeTickets"
						>{{ exchangeButtonText }}</tm-button>
					</view>
				</view>
			</view>

			<!-- ===== 装扮商店 ===== -->
			<view v-if="outfitStatus.items.length" class="section">
				<view class="section-head">
					<text class="section-title">装扮商店</text>
					<text class="section-sub">{{ hasLimitedOutfit ? '积分兑换或现金购买 · 限时款到期自动失效' : '积分兑换或现金购买 · 永久持有' }}</text>
				</view>
				<!-- 消费里程碑：让「已经花了多少」变成可见的进度，用户为凑门槛会主动多买 -->
				<view v-if="hasMilestoneOutfit" class="milestone-card">
					<view class="milestone-head">
						<text class="milestone-title">累计消费回馈</text>
						<text class="milestone-value">￥{{ spentYuanText }}</text>
					</view>
					<text class="milestone-tip">{{ milestoneTipText }}</text>
				</view>
				<view class="outfit-grid">
					<view
						v-for="item in outfitStatus.items"
						:key="item.key"
						class="outfit-card"
						:class="{ 'is-active': isActive(item), 'is-owned': isOwned(item) && !isActive(item) }"
					>
						<view class="outfit-preview" :class="outfitPreviewClass(item)">
							<image v-if="item.type === 'avatar_frame'" class="outfit-avatar" :src="previewAvatar" mode="aspectFill"></image>
							<text v-if="item.type === 'nick_frame'" class="outfit-nick">{{ nickPreviewText(item) }}</text>
							<view v-if="item.type === 'theme'" class="outfit-theme" :style="{ background: themeSwatchStyle(item.style) }"></view>
							<text v-if="isActive(item)" class="outfit-state">使用中</text>
						</view>
						<view class="outfit-body">
							<view class="outfit-title-row">
								<text class="outfit-name">{{ item.name }}</text>
								<text class="outfit-tag">{{ outfitTypeLabel(item.type) }}</text>
							</view>
							<text v-if="isMilestoneLocked(item)" class="outfit-milestone">消费满 {{ item.minSpend }} 元解锁</text>
							<text
								v-if="isOwned(item) && (isInvalidThemeSkin(item.style) || isInvalidAvatarFrame(item.style) || isInvalidNickFrame(item.style))"
								class="outfit-notice"
							>该装扮样式已下线，穿戴后不生效</text>
							<text class="outfit-price">
								<text class="outfit-price-num">{{ item.points }}</text> 积分
								<text v-if="isOwned(item) && !isActive(item)" class="outfit-expire"> · {{ ownedExpiryText(item) }}</text>
								<text v-else-if="!isOwned(item) && outfitDurationLabel(item)" class="outfit-duration"> · {{ outfitDurationLabel(item) }}</text>
							</text>
						</view>
						<view class="outfit-actions">
							<template v-if="!isOwned(item)">
								<tm-button
									size="s"
									theme="primary"
									block
									:disabled="outfitBusy(item) || isMilestoneLocked(item)"
									:loading="outfitBusy(item)"
									@click="fnBuyWithPoints(item)"
								>{{ isMilestoneLocked(item) ? '未达门槛' : '积分兑换' }}</tm-button>
								<tm-button
									v-if="payEnabled && cashGoodsOf(item) && !isMilestoneLocked(item)"
									size="s"
									plan
									theme="blue-grey"
									:fllowTheme="false"
									block
									:disabled="outfitBusy(item)"
									:loading="outfitBusy(item)"
									@click="fnBuyWithCash(item)"
								>￥{{ cashPriceText(cashGoodsOf(item).price) }} 购买</tm-button>
							</template>
							<tm-button
								v-else
								size="s"
								:theme="outfitButtonTheme(item)"
								:fllowTheme="outfitButtonTheme(item) !== 'grey'"
								block
								:disabled="outfitBusy(item)"
								:loading="outfitBusy(item)"
								@click="fnToggleEquip(item)"
							>{{ outfitButtonText(item) }}</tm-button>
						</view>
					</view>
				</view>

				<view v-if="outfitStatus.orphanOwned.length" class="orphan-card">
					<text class="orphan-title">已下架装扮</text>
					<text class="orphan-desc">{{ outfitStatus.orphanOwned.join('、') }} 已从商店下架，无法继续穿戴；如需处理请联系站长。</text>
				</view>
			</view>

			<view class="footer-note">
				<text>道具与装扮在支付成功后立即到账或生效，可在「积分明细」中查看记录；若订单需要人工处理，页面会给出提示。</text>
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
	import { syncPointsAfterLogin, emitPointsUpdated, fetchExchangeBalance, exchangePointsItem, normalizePointsApiError } from '@/utils/points.js';
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
		theme: '',
		/** 累计消费（分）；消费里程碑进度与限定装扮的锁定态都基于它 */
		totalSpentCents: 0
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
				outfitBusyKey: '',
				/** 阅读券免费获取规则（插件后台 ticketConfig 下发），用于首单礼文案 */
				ticketConfig: { firstPurchaseTickets: 0 },
				/** 会员状态（服务端 /points/membership 下发） */
				membership: { enabled: false, active: false, benefits: [] },
				/** 积分兑换券的兑换项（来自 /points/exchange/balance 里的 content_ticket） */
				exchangeTicket: null,
				exchanging: false,
				/**
				 * 限时特价倒计时用的「当前秒」。
				 *
				 * 用一个每秒推进的响应式数字驱动所有商品的倒计时，而不是每个商品各起一个定时器，
				 * 后者在商品多时会明显拖慢列表渲染。
				 */
				promoNow: Math.floor(Date.now() / 1000),
				promoTimer: null
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
			},
			/** 会员权益文案：服务端未下发时按「免券阅读」兜底，避免卡片空白 */
			membershipBenefits() {
				const benefits = this.membership?.benefits;
				if (Array.isArray(benefits) && benefits.length) {
					return benefits;
				}
				return this.membership?.active ? ['会员期内全站付费文章免券阅读'] : [];
			},
			/** 首单礼赠送张数：0 表示不展示首单礼横幅 */
			firstPurchaseTicketBonus() {
				return Math.max(0, Number(this.ticketConfig?.firstPurchaseTickets || 0));
			},
			/** 装扮商店里是否有消费里程碑限定款：决定是否展示累计消费进度卡 */
			hasMilestoneOutfit() {
				return this.outfitStatus.items.some((item) => Number(item.minSpend || 0) > 0);
			},
			/** 累计消费（元），保留一位小数 */
			spentYuanText() {
				return (Number(this.outfitStatus.totalSpentCents || 0) / 100).toFixed(1);
			},
			/** 下一档未达成的消费门槛提示；全部已达成时给一句收尾文案 */
			milestoneTipText() {
				const thresholds = this.outfitStatus.items
					.map((item) => Number(item.minSpend || 0))
					.filter((value) => value > 0)
					.sort((a, b) => a - b);
				const next = thresholds.find((value) => value * 100 > Number(this.outfitStatus.totalSpentCents || 0));
				if (!next) {
					return '已解锁全部消费回馈装扮，感谢支持';
				}
				return '累计消费满 ' + next + ' 元可解锁限定装扮';
			},
			/** 兑换是否被每日次数或积分余额挡住 */
			exchangeDisabled() {
				if (!this.exchangeTicket || this.exchanging) return true;
				if (this.exchangeTicket.dailyMaxCount > 0
					&& this.exchangeTicket.todayCount >= this.exchangeTicket.dailyMaxCount) {
					return true;
				}
				return Number(this.balance) < Number(this.exchangeTicket.points || 0);
			},
			exchangeButtonText() {
				if (!this.exchangeTicket) return '兑换';
				if (this.exchangeTicket.dailyMaxCount > 0
					&& this.exchangeTicket.todayCount >= this.exchangeTicket.dailyMaxCount) {
					return '今日已兑完';
				}
				if (Number(this.balance) < Number(this.exchangeTicket.points || 0)) {
					return '积分不足';
				}
				return '立即兑换';
			}
		},
		onLoad() {
			if (!checkHasWxLogin()) {
				uni.showToast({ title: '请先登录', icon: 'none' });
				setTimeout(() => uni.navigateBack(), 800);
				return;
			}
			this.fnLoad();
			// 倒计时用轮询推进：只在页面存续期间跑，离开时在 onUnload 清理
			this.promoTimer = setInterval(() => {
				this.promoNow = Math.floor(Date.now() / 1000);
			}, 1000);
		},
		onUnload() {
			// 必须清理：否则离开页面后定时器仍在跑，白耗电
			if (this.promoTimer) {
				clearInterval(this.promoTimer);
				this.promoTimer = null;
			}
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
				this.fnLoadTicketConfig();
				this.fnLoadExchangeTicket();
				this.fnLoadMembership();
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
					theme: data.theme || '',
					// 累计消费（分）：新版插件才下发，旧版为 0。
					// 漏掉这个字段会让消费里程碑进度永久显示 0，限定装扮的锁定态也就永远解不开。
					totalSpentCents: Number(data.totalSpentCents || 0)
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
				// membership = 会员卡（单次购买的有效期权益），bundle = 套餐。
				// 三者都走同一条下单链路，因此统一在这里放行；
				// points（现金充值积分）仍然被排除，防止形成代币通道。
				const ALLOWED = ['content_ticket', 'membership', 'bundle'];
				return (Array.isArray(goods) ? goods : [])
					.filter((item) => item && item.goodsId && ALLOWED.includes(item.type))
					.map((item) => ({
						goodsId: item.goodsId,
						name: item.name,
						price: Number(item.price || 0),
						type: item.type,
						value: Number(item.value || 0),
						style: item.style || '',
						// 套餐子项（仅 bundle 有值），用于在卡片上列出包含的权益
						items: Array.isArray(item.items) ? item.items : [],
						// 促销字段仅用于展示：实际售价永远是 price（必须与微信后台一致）
						originalPrice: Number(item.originalPrice || 0),
						promoEndsAt: Number(item.promoEndsAt || 0)
					}));
			},
			/** 读取赠券规则（用于首单礼文案）；旧版插件不下发该分组时按 0 处理。 */
			fnLoadTicketConfig() {
				this.ticketConfig = getAppConfigs()?.ticketConfig || { firstPurchaseTickets: 0 };
				// 实时再取一次：站长刚改过配置时本地缓存可能还是旧值
				this.$httpApi.v2.getConfigsByGroup('ticketConfig')
					.then((res) => {
						const body = res?.data || res || {};
						if (body && typeof body === 'object' && !body.error) {
							this.ticketConfig = body;
						}
					})
					.catch(() => {
						// 静默：拿不到只影响首单礼文案，不影响购买
					});
			},
			/**
			 * 拉取会员状态。
			 *
			 * <p>未登录也可能调用（服务端返回「未开通」而不是报错），
			 * 因此失败一律按未开通静默处理——会员卡是附加权益，
			 * 拿不到状态不应影响购买阅读券与装扮。</p>
			 */
			async fnLoadMembership() {
				try {
					const res = await this.$httpApi.v2.getMembershipStatus();
					const body = res?.data || res || {};
					if (body && typeof body === 'object' && !body.error) {
						this.membership = {
							enabled: body.enabled === true,
							active: body.active === true,
							expireAt: body.expireAt || '',
							expireAtEpoch: Number(body.expireAtEpoch || 0),
							daysRemaining: Number(body.daysRemaining || 0),
							freeContent: body.freeContent !== false,
							bonusTickets: Number(body.bonusTickets || 0),
							benefits: Array.isArray(body.benefits) ? body.benefits : []
						};
					}
				} catch (err) {
					// 静默：会员状态拿不到不影响本页其它功能
				}
			},
			/**
			 * 拉取积分兑换券的兑换项。
			 *
			 * 兑换项的 key 固定为 content_ticket（后端内置，或站长在积分商城配置），
			 * 这里只负责展示；能否真正兑换由后端校验（含每日次数与积分余额）。
			 */
			async fnLoadExchangeTicket() {
				try {
					const status = await fetchExchangeBalance(this.$httpApi.v2);
					if (!status.ok) {
						this.exchangeTicket = null;
						return;
					}
					this.exchangeTicket = (status.items || []).find((entry) => entry.key === 'content_ticket') || null;
					if (status.balance != null) {
						this.balance = Number(status.balance);
					}
				} catch (err) {
					// 静默：兑换项不可用不影响现金购买与装扮
					this.exchangeTicket = null;
				}
			},
			/** 用积分兑换阅读券（每次张数由后端 ticketConfig.exchangeTickets 决定）。 */
			async fnExchangeTickets() {
				if (!this.exchangeTicket || this.exchanging) return;
				this.exchanging = true;
				try {
					const result = await exchangePointsItem(this.$httpApi.v2, 'content_ticket');
					if (!result.ok) {
						uni.showToast({ title: result.reason || '兑换失败，请稍后重试', icon: 'none' });
						return;
					}
					if (result.balance != null) {
						this.balance = Number(result.balance);
						emitPointsUpdated({ balance: this.balance });
					}
					// 后端回传兑换后的券余额，直接采用可避免「兑换成功但卡片还是旧数字」
					if (result.contentTickets) {
						this.contentTickets = Number(result.contentTickets);
					} else {
						await this.fnLoadContentTickets();
					}
					uni.showToast({ title: result.message || '兑换成功', icon: 'success' });
					await this.fnLoadExchangeTicket();
				} catch (err) {
					uni.showToast({ title: normalizePointsApiError(err), icon: 'none' });
				} finally {
					this.exchanging = false;
				}
			},
			/** 该商品是否配了有效的划线原价（后端已过滤倒挂/等于现价的配置）。 */
			hasOriginalPrice(item) {
				return Number(item.originalPrice || 0) > Number(item.price || 0);
			},
			/** 限时活动是否仍在进行中。 */
			isPromoActive(item) {
				const endsAt = Number(item.promoEndsAt || 0);
				return endsAt > 0 && endsAt > this.promoNow;
			},
			/** 限时倒计时文案（剩余超过一天显示「N 天后结束」，否则 HH:mm:ss）。 */
			promoRemainText(item) {
				if (!this.isPromoActive(item)) return '';
				const remain = Number(item.promoEndsAt) - this.promoNow;
				if (remain <= 0) return '';
				const days = Math.floor(remain / 86400);
				if (days > 0) {
					return days + ' 天后结束';
				}
				const pad = (n) => (n < 10 ? '0' + n : String(n));
				return pad(Math.floor(remain / 3600)) + ':' + pad(Math.floor((remain % 3600) / 60))
					+ ':' + pad(remain % 60);
			},
			/**
			 * 券包单张均价（元/张）。
			 *
			 * 只在「阅读券且张数大于 1」时给出：单张装算出来等于原价，展示出来纯属噪音；
			 * 非券类权益也没有「每张」的概念。
			 */
			unitPriceText(item) {
				const count = Number(item.value || 0);
				if (item.type !== 'content_ticket' || count <= 1) return '';
				return '￥' + ((Number(item.price || 0) / 100) / count).toFixed(2);
			},
			/**
			 * 相比「按单张价买同样多张」省下多少钱。
			 *
			 * 参照价取同页最便宜的单张装；页面上没有单张装时不给对比，
			 * 免得用臆造的参照价误导用户。
			 */
			savingText(item) {
				const count = Number(item.value || 0);
				if (item.type !== 'content_ticket' || count <= 1) return '';
				const unit = Number(item.price || 0) / count;
				const singleUnit = this.singleTicketUnitPrice();
				if (!singleUnit || singleUnit <= unit) return '';
				return '￥' + (((singleUnit - unit) * count) / 100).toFixed(2);
			},
			/** 同页最小单张价（分）；没有单张装时返回 0 表示不做对比。 */
			singleTicketUnitPrice() {
				const singles = this.goodsList
					.filter((item) => item.type === 'content_ticket' && Number(item.value || 0) === 1)
					.map((item) => Number(item.price || 0))
					.filter((price) => price > 0);
				return singles.length ? Math.min.apply(null, singles) : 0;
			},
			/** 该装扮是否为「消费里程碑限定款且尚未达成门槛」。 */
			isMilestoneLocked(item) {
				const threshold = Number(item.minSpend || 0);
				if (threshold <= 0) return false;
				return Number(this.outfitStatus.totalSpentCents || 0) < threshold * 100;
			},
			fnGoodsTip(item) {
				if (item.type === 'content_ticket') {
					return `阅读券 x${item.value || 1}（解锁付费文章，每张解锁一篇）`;
				}
				if (item.type === 'membership') {
					const days = Number(item.value || 0) || 30;
					// 免券权益是否开启由服务端配置决定，这里如实反映，不夸大
					const benefit = this.membership?.freeContent === false
						? '' : '，期间付费文章免券阅读';
					return `会员 ${days} 天${benefit}`;
				}
				if (item.type === 'bundle') {
					return this.bundleItemsText(item) || '套餐（支付成功后逐项到账）';
				}
				// 已下架的 publish_ticket / dashboard 不会由后端下发，此处仅作兜底
				return '权益道具（支付成功后立即到账）';
			},
			/** 套餐包含的权益文案；子项缺失时返回空串，由调用方兜底。 */
			bundleItemsText(item) {
				const items = Array.isArray(item.items) ? item.items : [];
				if (!items.length) {
					return '';
				}
				const labels = items.map((entry) => {
					if (entry.type === 'content_ticket') {
						return `阅读券 x${entry.value || 1}`;
					}
					if (entry.type === 'membership') {
						return `会员 ${entry.value || 30} 天`;
					}
					if (entry.type === 'outfit') {
						return '限定装扮';
					}
					return '';
				}).filter(Boolean);
				return labels.length ? `含 ${labels.join(' + ')}` : '';
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
		padding: 24rpx 24rpx 48rpx;
	}

	.loading-wrap {
		padding-top: 24rpx;
	}

	/* ===== 资产总览 ===== */
	.asset-card {
		display: flex;
		align-items: stretch;
		justify-content: space-between;
		padding: 36rpx 32rpx;
		border-radius: 24rpx;
		background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
		box-shadow: 0 16rpx 40rpx rgba(102, 126, 234, 0.28);
		color: #fff;
	}

	.asset-main {
		display: flex;
		flex-direction: column;
		justify-content: center;
	}

	.asset-label {
		font-size: 24rpx;
		opacity: 0.85;
	}

	.asset-value {
		font-size: 64rpx;
		font-weight: 600;
		line-height: 1.2;
		letter-spacing: 1rpx;
	}

	.asset-side {
		display: flex;
		flex-direction: column;
		align-items: flex-end;
		justify-content: center;
		flex: 1;
		margin-left: 24rpx;
	}

	.asset-chip {
		display: flex;
		align-items: baseline;
		padding: 8rpx 20rpx;
		border-radius: 999rpx;
		background: rgba(255, 255, 255, 0.2);
	}

	.asset-chip-label {
		font-size: 22rpx;
		opacity: 0.9;
	}

	.asset-chip-value {
		margin-left: 8rpx;
		font-size: 32rpx;
		font-weight: 600;
	}

	.asset-note {
		margin-top: 12rpx;
		font-size: 20rpx;
		line-height: 1.5;
		text-align: right;
		opacity: 0.72;
	}

	/* ===== 区块 ===== */
	.section {
		margin-top: 36rpx;
	}

	.section-head {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		margin-bottom: 20rpx;
		padding: 0 4rpx;
	}

	.section-title {
		font-size: 32rpx;
		font-weight: 600;
		color: #1f2333;
	}

	.section-sub {
		max-width: 62%;
		font-size: 22rpx;
		color: #9aa1b5;
		text-align: right;
	}

	/* ===== 权益道具 ===== */
	.goods-list {
		display: flex;
		flex-direction: column;
	}

	.goods-card {
		margin-bottom: 20rpx;
		padding: 28rpx 28rpx 24rpx;
		border-radius: 20rpx;
		background: #fff;
		box-shadow: 0 6rpx 24rpx rgba(30, 40, 90, 0.06);
	}

	.goods-head {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
	}

	.goods-name {
		font-size: 30rpx;
		font-weight: 600;
		color: #1f2333;
	}

	.goods-price {
		font-size: 40rpx;
		font-weight: 600;
		color: #f2542d;
	}

	.goods-price-symbol {
		margin-right: 2rpx;
		font-size: 24rpx;
	}

	.goods-desc {
		display: block;
		margin: 10rpx 0 20rpx;
		font-size: 23rpx;
		line-height: 1.6;
		color: #8b90a0;
	}

	/* ===== 会员卡 ===== */
	.member-card {
		margin-bottom: 20rpx;
		padding: 28rpx;
		border-radius: 20rpx;
		background: linear-gradient(135deg, #2c2f4a 0%, #4a3f6b 100%);
		color: #fff;
		box-shadow: 0 12rpx 32rpx rgba(44, 47, 74, 0.28);
	}

	.member-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
	}

	.member-badge {
		padding: 4rpx 18rpx;
		border-radius: 999rpx;
		background: linear-gradient(135deg, #f7d774, #e0a44a);
		color: #4a3a12;
		font-size: 22rpx;
		font-weight: 700;
	}

	.member-expire {
		font-size: 22rpx;
		opacity: 0.82;
	}

	.member-days {
		display: block;
		margin-top: 16rpx;
		font-size: 40rpx;
		font-weight: 700;
		letter-spacing: 1rpx;
	}

	.member-benefits {
		margin-top: 12rpx;
	}

	.member-benefit {
		display: block;
		font-size: 22rpx;
		line-height: 1.7;
		opacity: 0.88;
	}

	.member-note {
		display: block;
		margin-top: 12rpx;
		font-size: 20rpx;
		opacity: 0.62;
	}

	/* ===== 首单礼横幅 / 限时特价 / 均价 ===== */
	.promo-banner {
		display: flex;
		align-items: center;
		margin-bottom: 16rpx;
		padding: 18rpx 24rpx;
		border-radius: 16rpx;
		background: linear-gradient(135deg, #fff2e0 0%, #ffe6ea 100%);
	}

	.promo-banner-tag {
		padding: 2rpx 14rpx;
		border-radius: 6rpx;
		background: #f2542d;
		color: #fff;
		font-size: 20rpx;
	}

	.promo-banner-text {
		margin-left: 14rpx;
		font-size: 23rpx;
		color: #b4472a;
	}

	.goods-promo {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-bottom: 14rpx;
		padding: 8rpx 16rpx;
		border-radius: 12rpx;
		background: #fff1e8;
	}

	.goods-promo-tag {
		font-size: 21rpx;
		font-weight: 600;
		color: #f2542d;
	}

	.goods-promo-clock {
		font-size: 22rpx;
		color: #e04b4b;
		letter-spacing: 1rpx;
	}

	.goods-price-box {
		display: flex;
		align-items: baseline;
	}

	.goods-price-origin {
		margin-right: 10rpx;
		font-size: 22rpx;
		color: #b6bac7;
		text-decoration: line-through;
	}

	.goods-unit {
		display: flex;
		align-items: center;
		margin: 0 0 20rpx;
		font-size: 21rpx;
	}

	.goods-unit-text {
		color: #7b81a0;
	}

	.goods-unit-save {
		margin-left: 14rpx;
		padding: 2rpx 12rpx;
		border-radius: 6rpx;
		background: #eaf7f1;
		color: #17a673;
	}

	/* ===== 积分兑换阅读券 ===== */
	.exchange-card {
		display: flex;
		align-items: center;
		padding: 28rpx;
		border-radius: 20rpx;
		background: #fff;
		box-shadow: 0 6rpx 24rpx rgba(30, 40, 90, 0.06);
	}

	.exchange-main {
		flex: 1;
		min-width: 0;
	}

	.exchange-name {
		display: block;
		font-size: 29rpx;
		font-weight: 600;
		color: #1f2333;
	}

	.exchange-desc {
		display: block;
		margin-top: 8rpx;
		font-size: 22rpx;
		line-height: 1.6;
		color: #8b90a0;
	}

	.exchange-quota {
		display: block;
		margin-top: 8rpx;
		font-size: 20rpx;
		color: #b6bac7;
	}

	.exchange-side {
		display: flex;
		flex-direction: column;
		align-items: flex-end;
		margin-left: 20rpx;
	}

	.exchange-cost {
		margin-bottom: 14rpx;
		font-size: 30rpx;
		font-weight: 700;
		color: #f2542d;
	}

	/* ===== 消费里程碑 ===== */
	.milestone-card {
		margin-bottom: 20rpx;
		padding: 22rpx 24rpx;
		border-radius: 16rpx;
		background: linear-gradient(135deg, #eef1fb 0%, #f6eeff 100%);
	}

	.milestone-head {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
	}

	.milestone-title {
		font-size: 25rpx;
		font-weight: 600;
		color: #4c5480;
	}

	.milestone-value {
		font-size: 30rpx;
		font-weight: 700;
		color: #667eea;
	}

	.milestone-tip {
		display: block;
		margin-top: 8rpx;
		font-size: 21rpx;
		color: #8b90a0;
	}

	.outfit-milestone {
		display: block;
		margin-top: 6rpx;
		font-size: 20rpx;
		color: #7b81a0;
	}

	/* ===== 装扮商店 ===== */
	.outfit-grid {
		display: flex;
		flex-wrap: wrap;
		justify-content: space-between;
	}

	.outfit-card {
		position: relative;
		display: flex;
		flex-direction: column;
		width: 48.5%;
		margin-bottom: 20rpx;
		border-radius: 20rpx;
		background: #fff;
		overflow: hidden;
		border: 2rpx solid transparent;
		box-shadow: 0 6rpx 24rpx rgba(30, 40, 90, 0.06);
	}

	.outfit-card.is-active {
		border-color: #667eea;
		box-shadow: 0 10rpx 28rpx rgba(102, 126, 234, 0.22);
	}

	.outfit-card.is-owned .outfit-preview {
		opacity: 0.7;
	}

	.outfit-preview {
		position: relative;
		display: flex;
		align-items: center;
		justify-content: center;
		height: 200rpx;
		background: linear-gradient(160deg, #eef1fb 0%, #e6e9f7 100%);
	}

	.outfit-avatar {
		width: 120rpx;
		height: 120rpx;
		border-radius: 50%;
	}

	.outfit-nick {
		font-size: 28rpx;
		font-weight: 600;
		color: #4c5480;
	}

	.outfit-theme {
		width: 120rpx;
		height: 120rpx;
		border-radius: 20rpx;
		box-shadow: inset 0 0 0 2rpx rgba(255, 255, 255, 0.6);
	}

	.outfit-state {
		position: absolute;
		top: 0;
		left: 0;
		padding: 4rpx 16rpx;
		border-bottom-right-radius: 16rpx;
		background: #667eea;
		color: #fff;
		font-size: 20rpx;
	}

	.outfit-body {
		flex: 1;
		padding: 20rpx 20rpx 0;
	}

	.outfit-title-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
	}

	.outfit-name {
		font-size: 27rpx;
		font-weight: 600;
		color: #1f2333;
	}

	.outfit-tag {
		padding: 2rpx 10rpx;
		border-radius: 6rpx;
		background: #f2f4fa;
		color: #7b81a0;
		font-size: 20rpx;
	}

	.outfit-notice {
		display: block;
		margin-top: 8rpx;
		font-size: 20rpx;
		color: #d1851a;
	}

	.outfit-price {
		display: block;
		margin-top: 10rpx;
		font-size: 22rpx;
		color: #8b90a0;
	}

	.outfit-price-num {
		font-size: 30rpx;
		font-weight: 600;
		color: #f2542d;
	}

	.outfit-expire,
	.outfit-duration {
		font-size: 21rpx;
	}

	.outfit-actions {
		display: flex;
		flex-direction: column;
		padding: 20rpx;
	}

	.outfit-actions > *:not(:last-child) {
		margin-bottom: 12rpx;
	}

	/* ===== 已下架装扮 ===== */
	.orphan-card {
		margin-top: 4rpx;
		padding: 22rpx 24rpx;
		border-radius: 16rpx;
		background: #fff7e8;
	}

	.orphan-title {
		display: block;
		font-size: 24rpx;
		font-weight: 600;
		color: #b4761a;
	}

	.orphan-desc {
		display: block;
		margin-top: 8rpx;
		font-size: 22rpx;
		line-height: 1.6;
		color: #a08349;
	}

	.footer-note {
		margin-top: 36rpx;
		padding: 0 8rpx;
		font-size: 21rpx;
		line-height: 1.7;
		color: #a1a6b8;
	}
</style>
