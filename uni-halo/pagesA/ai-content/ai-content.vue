<template>
	<view :class="skinClass" class="app-page">
		<view v-if="loading !== 'success'" class="loading-wrap">
			<tm-skeleton model="listAvatr"></tm-skeleton>
			<tm-skeleton model="listAvatr"></tm-skeleton>
		</view>

		<block v-else>
			<!-- 顶部 hero：图标 + 状态 + 一句话，先把「这是什么」立住 -->
			<view class="hero-card">
				<view class="hero-main">
					<view class="hero-icon">
						<text class="hero-icon_text">AI</text>
					</view>
					<view class="hero-meta">
						<view class="hero-title-row">
							<text class="hero-title">AI 定制内容</text>
							<text class="hero-badge">{{ statusBadge }}</text>
						</view>
						<text class="hero-desc">{{ statusDesc }}</text>
					</view>
				</view>
				<view v-if="status && status.active" class="hero-meta-row">
					<text v-if="status.viaPrivilege" class="hero-pill">管理员</text>
					<text v-else-if="status.viaMember" class="hero-pill">会员期内免费</text>
					<block v-else>
						<text v-if="status.permanent" class="hero-pill">永久有效</text>
						<text v-else class="hero-pill">到期 {{ status.expireAt }}</text>
						<text v-if="!status.permanent" class="hero-pill">剩余 {{ status.daysRemaining }} 天</text>
					</block>
				</view>
			</view>

			<!--
				未开通 / 已过期：购买区紧跟 hero（用户最想先看到「怎么用上」），说明放到后面；
				具体长什么样收进弹窗，页面本身不放示例。
			-->
			<block v-if="!status || !status.active">
				<view class="section-card">
					<view class="section-head">
						<text class="section-title">{{ status && status.expired ? '续费' : '开通' }}</text>
						<text class="section-sub">有效期内不限次查看{{ memberFreeEnabled ? '；会员期内免费' : '' }}</text>
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
								<text class="goods-tip">{{ fnGoodsPeriod(goods) }}，随时可改主题</text>
							</view>
							<view class="goods-cta">
								<text class="goods-price">￥{{ fnPrice(goods.price) }}</text>
								<text class="goods-btn">{{ payingIndex === i ? '处理中…' : '立即开通' }}</text>
							</view>
						</view>
					</view>

					<view v-if="canRedeem" class="redeem-row" hover-class="redeem-row-hover" @click="fnRedeem">
						<view class="redeem-main">
							<text class="redeem-title">也可用 {{ redeem.pointsPrice }} 积分兑换 {{ redeem.pointsDays }} 天</text>
							<text class="redeem-desc">{{ redeemBalanceText }}</text>
						</view>
						<text class="redeem-btn">{{ redeeming ? '兑换中…' : '兑换' }}</text>
					</view>

					<view v-if="!goodsList.length && !canRedeem" class="empty-tip">
						站长还没有开放获取入口。请先在 Halo 后台配置「虚拟支付 → 权益道具」（类型 AI 定制内容）或设置积分解锁价格。
					</view>

					<!-- 先看样例再下单：示例收进弹窗，页面保持干净 -->
					<view class="demo-entry" hover-class="demo-entry-hover" @click="demoShow = true">
						<text class="demo-entry_text">先看看效果示例</text>
						<text class="demo-entry_arrow">›</text>
					</view>

					<!-- 站长可配的补充说明：默认不显示，避免与上面的要点重复 -->
					<view v-for="(line, i) in benefitLines" :key="i" class="benefit-line">
						<text class="benefit-dot">·</text>
						<text class="benefit-text">{{ line }}</text>
					</view>
				</view>

				<!-- 三行说清「能得到什么」：每行一句短话，不写二级解释 -->
				<view class="section-card">
					<view v-for="(point, i) in introPoints" :key="i" class="point-row">
						<text class="point-num">{{ i + 1 }}</text>
						<text class="point-text">{{ point }}</text>
					</view>
				</view>
			</block>

			<!-- 已开通：定制输入（重设计重点）+ 高级筛选 + 简报 -->
			<block v-if="status && status.active">
				<!-- 定制主题：手输 + 推荐观点一键选，简报优先围绕它生成 -->
				<view class="section-card">
					<view class="section-head">
						<text class="section-title">你想看什么？</text>
						<text v-if="limits.maxTopics > 0" class="section-sub">手输你最关心的主题（最多 {{ limits.maxTopics }} 个，点已添加的可删除），简报会优先围绕它生成</text>
					</view>
					<block v-if="limits.maxTopics > 0">
						<view class="keyword-row">
							<input
								v-model="topicDraft"
								class="keyword-input"
								type="text"
								:maxlength="30"
								placeholder="如：大模型在教育行业的落地"
								placeholder-class="keyword-placeholder"
								confirm-type="done"
								@confirm="fnAddTopic"
							/>
							<view class="keyword-add" hover-class="keyword-add-hover" @click="fnAddTopic">
								<text class="keyword-add-text">添加</text>
							</view>
						</view>
						<view v-if="picked.customTopics.length" class="chip-wrap">
							<view
								v-for="(topic, i) in picked.customTopics"
								:key="topic + i"
								class="chip chip-on"
								@click="fnRemoveTopic(i)"
							>
								<text class="chip-text">{{ topic }} ×</text>
							</view>
						</view>
						<view v-if="presetTopicList.length" class="suggest-block">
							<text class="suggest-label">不知道写什么？试试这些：</text>
							<view class="chip-wrap">
								<view
									v-for="topic in presetTopicList"
									:key="topic"
									class="chip chip-ghost"
									@click="fnAddPresetTopic(topic)"
								>
									<text class="chip-text">{{ topic }} ＋</text>
								</view>
							</view>
						</view>
					</block>
					<view v-else class="empty-tip">站长已关闭自定义主题，请用下方的高级筛选表达你的方向</view>
					<view class="advanced-toggle" hover-class="advanced-toggle-hover" @click="showAdvanced = !showAdvanced">
						<text class="advanced-toggle-text">{{ showAdvanced ? '收起高级筛选 ▲' : '展开高级筛选（主题分类 / 来源 / 关键词）▼' }}</text>
					</view>
				</view>

				<!-- 高级筛选：默认收起，定制主题已经覆盖了大多数人的需求 -->
				<view v-show="showAdvanced">
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
				</view>

				<view class="save-bar">
					<view
						:class="['save-btn', saving ? 'save-btn-busy' : '']"
						hover-class="save-btn-hover"
						@click="fnSave"
					>
						<text class="save-btn-text">{{ saving ? '生成中…' : '保存并生成今日简报' }}</text>
					</view>
				</view>

				<!--
					推送设置：站长配好简报模板才出现（否则是个永远推不出去的开关）。
					微信一次性订阅是「一次授权一条」，所以额度要看得见、可续。
				-->
				<view v-if="pushSettings.ready" class="section-card">
					<view class="section-head">
						<text class="section-title">推送到微信</text>
						<text class="section-sub">一次性订阅：一次授权可收到一条，剩余 {{ pushQuota }} 条</text>
					</view>

					<view class="push-row" hover-class="push-row-hover" @click="fnPushNow">
						<view class="push-main">
							<text class="push-title">{{ pushing ? '正在推送…' : '立即生成并推送给我的微信' }}</text>
							<text class="push-desc">今天还没生成会先生成，已有则直接推（不重复消耗 AI）</text>
						</view>
						<text class="push-btn">{{ pushing ? '处理中' : '立即推送' }}</text>
					</view>

					<view class="push-row push-row-switch">
						<view class="push-main">
							<text class="push-title">每天定时推送</text>
							<text class="push-desc">{{ picked.pushEnabled ? '到点自动生成并推给你' : '开启后每天自动生成一份推给你' }}</text>
						</view>
						<switch
							:checked="picked.pushEnabled"
							color="#2f54eb"
							@change="fnToggleDailyPush"
						/>
					</view>

					<view v-if="picked.pushEnabled" class="push-hours">
						<text class="push-hours_label">选一个推送时间</text>
						<view class="chip-wrap">
							<view
								v-for="hour in pushSettings.hourOptions"
								:key="hour"
								:class="['chip', picked.pushHour === hour ? 'chip-on' : '']"
								@click="fnPickPushHour(hour)"
							>
								<text class="chip-text">{{ hour }}:00</text>
							</view>
						</view>
						<text class="push-hours_tip">到点会自动为你生成简报并推送；改了时间记得点下面的保存</text>
					</view>

					<view class="push-foot">
						<text class="push-foot_text">{{ pushHint }}</text>
						<text v-if="pushBanned" class="push-foot_link" @click="fnOpenPushSetting">去设置</text>
					</view>
				</view>

				<!-- 我的简报 -->
				<view class="section-card">
					<view class="section-head section-head-with-action">
						<view class="section-head-main">
							<text class="section-title">{{ feedTitle }}</text>
							<text class="section-sub">{{ feedLoading ? '正在为你生成今天的简报…' : (feed && feed.summary ? feed.summary : '') }}</text>
						</view>
						<text
							v-if="feedItems.length && !feedLoading"
							class="feed-poster"
							hover-class="feed-poster-hover"
							@click="fnGeneratePoster"
						>生成海报</text>
					</view>

					<view v-if="feedLoading" class="feed-loading">
						<text class="feed-loading-text">首次打开会调用一次 AI 生成，通常几秒到十几秒</text>
					</view>

					<block v-else>
						<view v-if="feed && feed.intro" class="feed-intro">
							<text class="feed-intro-text">{{ feed.intro }}</text>
						</view>

						<view v-if="!feedItems.length" class="empty-tip">
							{{ feed && feed.summary ? feed.summary : '暂时没有可展示的内容' }}
						</view>

						<view v-for="(item, i) in feedItems" :key="(item.postName || item.title) + i">
							<!-- AI 按定制主题生成的专属内容：私有，可做海报分享 -->
							<view v-if="item.generated" class="feed-generated">
								<view class="feed-generated-head">
									<text class="feed-generated-tag">定制</text>
									<text class="feed-generated-title">{{ item.title }}</text>
								</view>
								<text class="feed-generated-body">{{ item.body }}</text>
								<text class="feed-generated-note">以上内容由 AI 按你的定制主题原创生成，仅你自己可见</text>
							</view>
							<view v-else class="feed-item">
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
						</view>

						<!-- 空态给出一键放宽：偏好太窄是「没有内容」最常见的原因 -->
						<view v-if="!feedItems.length && categoryOptions.length" class="chip-wrap">
							<view
								v-for="option in categoryOptions.slice(0, 6)"
								:key="option.key"
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
				<text>简报按你的定制主题与关注方向生成：优先从站长已精选的日报内容里检索并重写，素材不足时由 AI 原创补充；内容仅你自己可见，可生成海报分享。每天最多生成一次，反复打开不会重复消耗 AI 额度。</text>
			</view>

			<!-- 效果示例弹窗：页面保持简洁，细节按需展开 -->
			<tm-poup v-model="demoShow" width="88vw" height="auto" :round="6" :over-close="true" position="center">
				<view class="demo-popup">
					<view class="demo-head">
						<text class="demo-title">我的定制简报 · 09-29</text>
						<text class="demo-tag">效果示意</text>
					</view>
					<scroll-view scroll-y class="demo-scroll">
						<text class="demo-intro">{{ demoIntro }}</text>
						<view v-for="(item, i) in demoItems" :key="i" class="demo-item">
							<view class="demo-item-meta">
								<text class="demo-item-cat">{{ item.category }}</text>
								<text class="demo-item-source">{{ item.source }}</text>
							</view>
							<text class="demo-item-title">{{ item.title }}</text>
							<text class="demo-item-reason">推荐理由：{{ item.reason }}</text>
						</view>
						<view class="demo-take-block">
							<text class="demo-take-label">✍️ 小编看法</text>
							<text class="demo-take">{{ demoTake }}</text>
						</view>
						<text class="demo-note">每天上午为你更新 · 内容仅你自己可见</text>
					</scroll-view>
					<view class="demo-foot">
						<tm-button size="m" theme="bg-gradient-indigo-accent" @click="demoShow = false">知道了</tm-button>
					</view>
				</view>
			</tm-poup>

			<!-- 简报海报：canvas 绘制后可预览保存（复用文章详情页的海报能力） -->
			<liu-poster ref="liuPoster" :width="674" :height="1000" @change="handleOnPosterChange"></liu-poster>
		</block>
	</view>
</template>

<script>
	import tmSkeleton from '@/tm-vuetify/components/tm-skeleton/tm-skeleton.vue';
	import tmPoup from '@/tm-vuetify/components/tm-poup/tm-poup.vue';
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
	import liuPoster from '@/uni_modules/liu-poster/components/liu-poster/liu-poster.vue';
	import { checkHasWxLogin } from '@/utils/auth.js';
	import { getAppConfigs } from '@/config/index.js';
	import { payVirtualGoods } from '@/utils/virtualPay.js';
	import {
		requestSubscribe,
		accumulatePushQuota,
		openSubscribeSetting,
		pushRemindState,
		PUSH_KINDS
	} from '@/utils/subscribeMessage.js';

	export default {
		components: {
			tmSkeleton,
			tmPoup,
			tmButton,
			liuPoster
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
					maxTopics: 3,
					maxItems: 10,
					windowDays: 7,
					aiIntroEnabled: true
				},
				benefitNote: '',
				categoryOptions: [],
				sourceOptions: [],
				/** 站长配置的预设定制主题（点一下即加入） */
				presetTopics: [],
				/** 积分兑换通道参数（pointsPrice=0 表示未开启） */
				redeem: { pointsPrice: 0, pointsDays: 30 },
				/** 正在编辑的关注方向与推送设置（保存前的本地状态） */
				picked: { categories: [], sources: [], keywords: [], customTopics: [], pushEnabled: false, pushHour: null },
				/** 推送可选项与状态（服务端下发） */
				pushSettings: { hourOptions: [], ready: false },
				/** 用户当前的推送额度与授权状态 */
				pushQuota: 0,
				pushBanned: false,
				pushing: false,
				topicDraft: '',
				showAdvanced: false,
				demoShow: false,
				/** 效果示例的内容（静态示意，不调接口）。改成自家站点的题材会更有代入感 */
				demoIntro: '你关注的「大模型在教育行业的落地」今天有 3 条值得看：政策端刚放开试点，两家公司同一天发布课堂产品，还有一篇一线教师的实测反思值得留意。',
				demoItems: [
					{
						category: '🤖 AI / ML',
						source: '36氪',
						title: '某地放开校内 AI 助教试点，秋季学期开始落地',
						reason: '政策放开是这条赛道的转折点，建议先看它'
					},
					{
						category: '🛠 工具',
						source: '少数派',
						title: '两款课堂 AI 产品同期发布，都主打「给老师减负」',
						reason: '同一天两家的取舍不同，对比着看更有意思'
					},
					{
						category: '💬 观点',
						source: '量子位',
						title: '一线教师的实测反思：AI 助教进课堂后，谁更累了',
						reason: '唯一来自真实课堂的声音，比发布会更值得看'
					}
				],
				demoTake: '卡住落地的往往不是模型能力，而是课堂时间怎么分配——这决定了接下来一年谁能跑出来。三条里建议从第 3 条开始读。',
				redeeming: false,
				posterBusy: false,
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
				if (this.status.viaPrivilege) return '管理员';
				if (this.status.viaMember) return '会员免费';
				return this.status.permanent ? '永久' : '生效中';
			},
			/** 积分兑换通道是否开启 */
			canRedeem() {
				return Number(this.redeem?.pointsPrice || 0) > 0;
			},
			/** 兑换行副标题：能拿到余额就显示「当前 N 积分」，不足时直接说还差多少 */
			redeemBalanceText() {
				const balance = Number(this.redeem?.pointsBalance);
				const price = Number(this.redeem?.pointsPrice) || 0;
				if (!Number.isFinite(balance) || balance < 0) {
					return '与购买的时长叠加';
				}
				const short = price - balance;
				return short > 0
					? `当前 ${balance} 积分，还差 ${short} 积分`
					: `当前 ${balance} 积分 · 与购买的时长叠加`;
			},
			/** 会员是否免条件使用（站长配置）：售前页据此说明「会员期内免费」 */
			memberFreeEnabled() {
				return getAppConfigs()?.aiContentConfig?.memberFree !== false;
			},
			/** 推送状态一句话：让用户知道「现在到底能不能收到」 */
			pushHint() {
				if (this.pushBanned) {
					return '你在微信里关闭了订阅消息，需要去设置里打开';
				}
				if (this.pushQuota > 0) {
					return '剩余 ' + this.pushQuota + ' 条推送额度';
				}
				return '还没有推送额度：点「立即推送」时会向你申请授权';
			},
			/** 预设主题里还没被用户加进去的（避免重复推荐） */
			presetTopicList() {
				return (this.presetTopics || []).filter(
					(topic) => this.picked.customTopics.indexOf(topic) < 0
				);
			},
			statusDesc() {
				if (!this.status || !this.status.enabled) {
					return '站长暂未开放这个功能';
				}
				if (!this.status.active) {
					return this.status.expired
						? '权益已到期，你设过的主题与关注方向仍为你保留'
						: '每天一份为你定制的技术简报';
				}
				return this.status.permanent
					? '永久有效，随时调整你的关注方向'
					: '生效中，随时调整你的关注方向';
			},
			/** 售前三点：每行一句短话，不写第二行解释（字越少越有人读完） */
			introPoints() {
				return [
					'自己定主题：输入你想看的，随时能改',
					'每天一份：站内精选里挑 + AI 写点评',
					'只属于你：仅自己可见，可做海报分享'
				];
			},
			/** 站长在后台配了补充说明才展示（默认不显示，避免与上面三点重复） */
			benefitLines() {
				const note = (this.benefitNote || '').trim();
				if (!note) {
					return [];
				}
				return note.split('\n').map((line) => line.trim()).filter(Boolean);
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
			// 打开页面顺手静默续一次简报额度（一天最多一次）；
			// 没同意过授权的用户不会被弹窗——那是页面上的按钮负责的事
			accumulatePushQuota(this.$httpApi.v2, PUSH_KINDS.aiDigest);
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
					this.presetTopics = Array.isArray(body.presetTopics) ? body.presetTopics : [];
					this.redeem = body.redeem || { pointsPrice: 0, pointsDays: 30 };
					this.pushSettings = body.push || { hourOptions: [], ready: false };
					const preference = body.preference || {};
					this.picked = {
						categories: Array.isArray(preference.categories) ? preference.categories.slice() : [],
						sources: Array.isArray(preference.sources) ? preference.sources.slice() : [],
						keywords: Array.isArray(preference.keywords) ? preference.keywords.slice() : [],
						customTopics: Array.isArray(preference.customTopics) ? preference.customTopics.slice() : [],
						pushEnabled: preference.pushEnabled === true,
						pushHour: typeof preference.pushHour === 'number' ? preference.pushHour : null
					};
					this.fnLoadPushStatus();
					// 首页运营位卡的「已开通」徽章从这里取（避免首页多打一次接口）
					try {
						uni.setStorageSync('ai.content.active',
							this.status && this.status.active ? '1' : '0');
					} catch (e) { /* 存储不可用就算了 */ }
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
			// ── 定制主题（手输 + 预设推荐） ──
			fnAddTopic() {
				const topic = (this.topicDraft || '').trim();
				if (!topic) return;
				if (topic.length > 30) {
					uni.showToast({ title: '主题请控制在 30 个字符以内', icon: 'none' });
					return;
				}
				if (this.picked.customTopics.indexOf(topic) >= 0) {
					this.topicDraft = '';
					return;
				}
				if (this.picked.customTopics.length >= this.limits.maxTopics) {
					uni.showToast({
						title: `最多 ${this.limits.maxTopics} 个定制主题，点已添加的可删除`,
						icon: 'none'
					});
					return;
				}
				this.picked.customTopics.push(topic);
				this.topicDraft = '';
			},
			fnRemoveTopic(index) {
				this.picked.customTopics.splice(index, 1);
			},
			/** 预设主题一键加入：超过 30 字的截断到上限以内 */
			fnAddPresetTopic(topic) {
				const value = (topic || '').trim().slice(0, 30);
				if (!value) return;
				this.topicDraft = value;
				this.fnAddTopic();
			},
			// ── 积分兑换 ──
			/**
			 * 点「兑换」：先用已知余额判断够不够，够了才弹确认。
			 *
			 * <p>余额由服务端 view 接口下发（登录时带上）。不足时直接给「还差多少」与
			 * 「去赚积分」入口，而不是等用户确认完再被服务端拒绝。余额可能已变，
			 * 服务端仍会做权威校验。</p>
			 */
			fnRedeem() {
				if (this.redeeming) return;
				if (!checkHasWxLogin()) {
					uni.showToast({ title: '请先登录', icon: 'none' });
					return;
				}
				const price = Number(this.redeem.pointsPrice) || 0;
				const balance = Number(this.redeem.pointsBalance);
				// 余额为 -1 表示未登录/读不到：不做本地判断，交给服务端权威校验
				if (Number.isFinite(balance) && balance >= 0 && balance < price) {
					const short = price - balance;
					uni.showModal({
						title: '积分不足',
						content: `兑换需要 ${price} 积分，你当前有 ${balance} 积分，还差 ${short} 积分。`
							+ '签到、浏览文章、发表评论都能赚积分。',
						confirmText: '去赚积分',
						cancelText: '知道了',
						success: (res) => {
							if (res.confirm) {
								uni.navigateTo({
									url: '/pagesA/task-center/task-center',
									fail: () => uni.switchTab({ url: '/pages/tabbar/about/about' })
								});
							}
						}
					});
					return;
				}
				const after = (Number.isFinite(balance) && balance >= 0)
					? `兑换后剩余 ${balance - price} 积分。` : '';
				uni.showModal({
					title: '积分兑换',
					content: `将消耗 ${price} 积分，把「AI 定制内容」权益延长 `
						+ `${this.redeem.pointsDays} 天。${after}确认兑换？`,
					confirmText: '兑换',
					success: (res) => {
						if (res.confirm) {
							this.fnDoRedeem();
						}
					}
				});
			},
			async fnDoRedeem() {
				this.redeeming = true;
				try {
					const res = await this.$httpApi.v2.redeemAiContent();
					const body = res?.data || res || {};
					if (body.error) {
						throw body;
					}
					uni.showToast({ title: body.message || '兑换成功', icon: 'success' });
					if (body.balance != null) {
						uni.$emit('points_updated', { balance: body.balance });
					}
					this.fnLoad();
				} catch (err) {
					uni.showToast({
						title: err?.error || err?.detail || '兑换失败，请稍后重试',
						icon: 'none'
					});
				} finally {
					this.redeeming = false;
				}
			},
			// ── 推送 ──

			/** 读推送额度与授权状态（决定提示语与是否需要引导授权） */
			async fnLoadPushStatus() {
				if (!checkHasWxLogin()) {
					this.pushQuota = 0;
					return;
				}
				try {
					const res = await this.$httpApi.v2.getPushStatus();
					const body = res?.data || res || {};
					this.pushQuota = Number(body.aiDigestQuota || 0);
					this.pushBanned = pushRemindState().banned;
				} catch (err) {
					// 静默：拿不到就按 0 显示，用户点推送时服务端还会再判一次
					this.pushQuota = 0;
				}
			},

			/** 立即生成并推送：没有额度时当场申请一次授权，成功就继续推 */
			async fnPushNow() {
				if (this.pushing) {
					return;
				}
				if (!checkHasWxLogin()) {
					uni.showToast({ title: '请先登录', icon: 'none' });
					return;
				}
				this.pushing = true;
				try {
					if (this.pushQuota <= 0) {
						const granted = await this.fnAskPushPermission();
						if (!granted) {
							return;
						}
					}
					const res = await this.$httpApi.v2.pushAiContentNow();
					const body = res?.data || res || {};
					if (body.error) {
						throw body;
					}
					if (body.pushed) {
						uni.showToast({ title: body.message || '已推送到你的微信', icon: 'none' });
					} else {
						uni.showToast({
							title: body.message || '推送额度不足，请先授权',
							icon: 'none'
						});
					}
					this.fnLoadPushStatus();
				} catch (err) {
					uni.showToast({
						title: err?.error || err?.detail || '推送失败，请稍后重试',
						icon: 'none'
					});
				} finally {
					this.pushing = false;
				}
			},

			/** 开关每日推送：打开时先争一次授权（没授权就开了也推不出去） */
			async fnToggleDailyPush(e) {
				const on = e?.detail?.value === true;
				if (!on) {
					this.picked.pushEnabled = false;
					return;
				}
				if (!checkHasWxLogin()) {
					uni.showToast({ title: '请先登录', icon: 'none' });
					return;
				}
				this.picked.pushEnabled = true;
				if (this.picked.pushHour == null && this.pushSettings.hourOptions.length) {
					// 默认给第一个可选时段，避免用户开了却没选时间
					this.picked.pushHour = this.pushSettings.hourOptions[0];
				}
				await this.fnAskPushPermission();
			},

			fnPickPushHour(hour) {
				this.picked.pushHour = hour;
			},

			/** 申请一次简报推送授权；已同意过的用户会被微信静默放行（不再弹窗） */
			async fnAskPushPermission() {
				const push = getAppConfigs()?.pushConfig || {};
				const tmplId = push.aiDigestTemplateId || '';
				if (!tmplId) {
					uni.showToast({ title: '站长还没有配好简报推送模板', icon: 'none' });
					return false;
				}
				const state = pushRemindState();
				if (state.banned) {
					this.pushBanned = true;
					uni.showModal({
						title: '需要在小程序设置里打开',
						content: '你之前关闭了该小程序的订阅消息。点「去设置」打开「接收订阅消息」后即可接收简报推送。',
						confirmText: '去设置',
						success: (res) => {
							if (res.confirm) {
								openSubscribeSetting();
							}
						}
					});
					return false;
				}
				const result = await requestSubscribe(this.$httpApi.v2, [
					{ kind: PUSH_KINDS.aiDigest, tmplId }
				]);
				this.fnLoadPushStatus();
				return !!result.ok;
			},

			fnOpenPushSetting() {
				openSubscribeSetting();
			},

			// ── 简报海报（钩子式：只带日期/导语摘录/条目标题，不含全文） ──
			fnGeneratePoster() {
				if (this.posterBusy) return;
				const siteTitle = getAppConfigs()?.basicConfig?.startConfig?.title || '';
				const date = this.activeDate || (this.feed && this.feed.date) || '';
				const intro = ((this.feed && this.feed.intro) || '').slice(0, 90);
				const titles = this.feedItems
					.filter((item) => !item.generated)
					.slice(0, 3)
					.map((item) => '· ' + (item.title || ''));
				const configs = [
					{
						type: 'color', width: 674, height: 1000, x: 0, y: 0,
						radius: 24, lineWidth: 0, lineColor: '#ffffff',
						colorObj: { colorList: ['#FFFFFF'], direction: 2 }
					},
					{
						type: 'text', width: 626, height: 44, x: 24, y: 40,
						color: '#1f1f1f', fontSize: 36, lineHeight: 44, bold: true,
						content: '我的定制简报' + (date ? ' · ' + date : '')
					},
					{
						type: 'text', width: 626, height: 34, x: 24, y: 96,
						color: '#9a9a94', fontSize: 24, lineHeight: 34,
						content: siteTitle ? '来自 ' + siteTitle : '我的 AI 定制简报'
					}
				];
				let y = 150;
				if (intro) {
					configs.push({
						type: 'text', width: 626, height: 150, x: 24, y: y,
						color: '#333333', fontSize: 25, lineHeight: 40,
						content: intro
					});
					y += 170;
				}
				titles.forEach((title) => {
					configs.push({
						type: 'text', width: 626, height: 60, x: 24, y: y,
						color: '#1f1f1f', fontSize: 26, lineHeight: 40,
						content: title
					});
					y += 60;
				});
				y += 20;
				configs.push({
					type: 'text', width: 626, height: 80, x: 24, y: y,
					color: '#9a9a94', fontSize: 22, lineHeight: 34,
					content: '简报内容仅主人可见；打开小程序定制一份你自己的每日简报'
				});
				this.posterBusy = true;
				this.$refs.liuPoster.init(configs);
			},
			handleOnPosterChange(posterUrl) {
				this.posterBusy = false;
				if (!posterUrl) {
					uni.showToast({ title: '海报生成失败，请稍后重试', icon: 'none' });
					return;
				}
				uni.previewImage({ urls: [posterUrl] });
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
						keywords: this.picked.keywords,
						customTopics: this.picked.customTopics,
						pushEnabled: this.picked.pushEnabled,
						pushHour: this.picked.pushHour
					});
					const body = res?.data || res || {};
					if (body.error) {
						throw body;
					}
					// 以服务端返回为准回写：避免本地与服务端不一致
					this.picked = {
						categories: (body.categories || []).slice(),
						sources: (body.sources || []).slice(),
						keywords: (body.keywords || []).slice(),
						customTopics: (body.customTopics || []).slice(),
						pushEnabled: body.pushEnabled === true,
						pushHour: typeof body.pushHour === 'number' ? body.pushHour : this.picked.pushHour
					};
					this.fnLoadPushStatus();
					uni.showToast({ title: '已保存，正在按新的方向生成简报', icon: 'none' });
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
			/**
			 * 保存偏好 / 购买成功后的顺手授权。
			 *
			 * <p>与被拒用户不再纠缠：拒绝且不再询问的、已经有额度的一律跳过，
			 * 交给推送设置卡里的「立即推送 / 开启推送」去处理（那里才有明确的用户意图）。</p>
			 */
			async fnMaybeAskPushPermission() {
				if (this.pushBanned || this.pushQuota >= 2) {
					return;
				}
				await this.fnAskPushPermission();
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

	/* ── 顶部 hero：渐变底 + 装饰圆 + 白底图标方块 ──
	   皮肤生成器不会重染 color 上的 #ffffff，深色皮肤下依然是「白字 + 品牌色底」。 */
	.hero-card {
		position: relative;
		overflow: hidden;
		margin: 24rpx 28rpx 16rpx;
		padding: 32rpx 28rpx;
		border-radius: 24rpx;
		background-image: $uh-grad-accent;
		color: #ffffff;
		box-shadow: 0 12rpx 32rpx rgba(47, 84, 235, 0.26);
	}

	/* 右上大圆 + 左下小圆：给纯渐变加层次，避免一整块平铺的色块 */
	.hero-card:before {
		content: '';
		position: absolute;
		top: -100rpx;
		right: -70rpx;
		width: 260rpx;
		height: 260rpx;
		border-radius: 50%;
		background-color: rgba(255, 255, 255, 0.12);
	}

	.hero-card:after {
		content: '';
		position: absolute;
		left: -40rpx;
		bottom: -70rpx;
		width: 160rpx;
		height: 160rpx;
		border-radius: 50%;
		background-color: rgba(255, 255, 255, 0.08);
	}

	.hero-main {
		position: relative;
		display: flex;
		align-items: center;
	}

	.hero-icon {
		flex-shrink: 0;
		display: flex;
		align-items: center;
		justify-content: center;
		width: 88rpx;
		height: 88rpx;
		border-radius: 22rpx;
		background-color: rgba(255, 255, 255, 0.2);
		border: 2rpx solid rgba(255, 255, 255, 0.28);
	}

	.hero-icon_text {
		font-size: 34rpx;
		font-weight: 800;
		color: #ffffff;
	}

	.hero-meta {
		flex: 1;
		min-width: 0;
		margin-left: 22rpx;
	}

	.hero-title-row {
		display: flex;
		align-items: center;
	}

	.hero-title {
		font-size: 36rpx;
		font-weight: 700;
		color: #ffffff;
	}

	.hero-badge {
		flex-shrink: 0;
		margin-left: 14rpx;
		padding: 4rpx 18rpx;
		border-radius: $uh-radius-pill;
		background-color: rgba(255, 255, 255, 0.22);
		font-size: 21rpx;
		color: #ffffff;
	}

	.hero-desc {
		display: block;
		margin-top: 8rpx;
		font-size: 23rpx;
		line-height: 1.5;
		color: rgba(255, 255, 255, 0.88);
	}

	.hero-meta-row {
		position: relative;
		display: flex;
		margin-top: 20rpx;
	}

	.hero-pill {
		margin-right: 16rpx;
		padding: 6rpx 18rpx;
		border-radius: $uh-radius-pill;
		background-color: rgba(255, 255, 255, 0.18);
		font-size: 21rpx;
		color: #ffffff;
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
		font-size: 30rpx;
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

	/* ── 售前：三点要点（每行一句短话，紧凑） ── */
	.point-row {
		display: flex;
		align-items: baseline;
		margin-top: 14rpx;
	}

	.point-num {
		flex-shrink: 0;
		width: 34rpx;
		height: 34rpx;
		margin-right: 14rpx;
		border-radius: 50%;
		background: $uh-accent-weak;
		color: $uh-accent;
		font-size: 20rpx;
		font-weight: 700;
		text-align: center;
		line-height: 34rpx;
	}

	.point-text {
		flex: 1;
		min-width: 0;
		font-size: 25rpx;
		line-height: 1.5;
		color: $uh-ink-1;
	}

	/* ── 推送设置 ── */
	.push-row {
		display: flex;
		align-items: center;
		margin-top: 14rpx;
		padding: 20rpx 22rpx;
		border-radius: 16rpx;
		background: $uh-accent-weak;
	}

	.push-row-hover {
		opacity: 0.86;
	}

	/* 开关那一行用卡片底色：它是个设置项，不是一个「可点的动作」 */
	.push-row-switch {
		background: $uh-fill;
	}

	.push-main {
		flex: 1;
		min-width: 0;
	}

	.push-title {
		display: block;
		font-size: 26rpx;
		font-weight: 600;
		color: $uh-ink-1;
	}

	.push-desc {
		display: block;
		margin-top: 4rpx;
		font-size: 21rpx;
		line-height: 1.5;
		color: $uh-ink-3;
	}

	.push-btn {
		flex-shrink: 0;
		margin-left: 16rpx;
		padding: 10rpx 24rpx;
		border-radius: $uh-radius-pill;
		background: $uh-accent;
		color: #ffffff;
		font-size: 23rpx;
		font-weight: 600;
	}

	.push-hours {
		margin-top: 16rpx;
	}

	.push-hours_label {
		display: block;
		font-size: 23rpx;
		color: $uh-ink-2;
	}

	.push-hours_tip {
		display: block;
		margin-top: 10rpx;
		font-size: 20rpx;
		line-height: 1.5;
		color: $uh-ink-3;
	}

	.push-foot {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-top: 16rpx;
	}

	.push-foot_text {
		flex: 1;
		min-width: 0;
		font-size: 21rpx;
		color: $uh-ink-3;
	}

	.push-foot_link {
		flex-shrink: 0;
		margin-left: 12rpx;
		font-size: 22rpx;
		font-weight: 600;
		color: $uh-accent;
	}

	/* ── 售前：示例入口（示例本身在弹窗里，页面只留一行链） ── */
	.demo-entry {
		display: flex;
		align-items: center;
		justify-content: center;
		margin-top: 18rpx;
		padding-top: 18rpx;
		border-top: 1rpx solid $uh-line;
	}

	.demo-entry-hover {
		opacity: 0.7;
	}

	.demo-entry_text {
		font-size: 24rpx;
		font-weight: 600;
		color: $uh-accent;
	}

	.demo-entry_arrow {
		margin-left: 6rpx;
		font-size: 28rpx;
		line-height: 1;
		color: $uh-accent;
	}

	/* ── 效果示例弹窗 ── */
	.demo-popup {
		padding: 32rpx 28rpx 24rpx;
	}

	.demo-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
	}

	.demo-title {
		font-size: 28rpx;
		font-weight: 700;
		color: $uh-ink-1;
	}

	.demo-tag {
		flex-shrink: 0;
		margin-left: 12rpx;
		padding: 2rpx 14rpx;
		border-radius: $uh-radius-pill;
		background: $uh-accent-weak;
		font-size: 20rpx;
		color: $uh-accent;
	}

	/* 固定高度的滚动区：示例内容变长也不会把弹窗撑出屏幕 */
	.demo-scroll {
		height: 820rpx;
		margin-top: 16rpx;
	}

	.demo-intro {
		display: block;
		font-size: 25rpx;
		line-height: 1.75;
		color: $uh-ink-2;
	}

	.demo-item {
		margin-top: 22rpx;
		padding: 18rpx 20rpx;
		border-radius: 14rpx;
		background: $uh-fill;
	}

	.demo-item-meta {
		display: flex;
		align-items: center;
	}

	.demo-item-cat {
		font-size: 20rpx;
		color: $uh-ink-3;
	}

	.demo-item-source {
		margin-left: 12rpx;
		font-size: 20rpx;
		color: $uh-ink-3;
	}

	.demo-item-title {
		display: block;
		margin-top: 8rpx;
		font-size: 26rpx;
		font-weight: 600;
		line-height: 1.5;
		color: $uh-ink-1;
	}

	.demo-item-reason {
		display: block;
		margin-top: 8rpx;
		font-size: 22rpx;
		line-height: 1.6;
		color: $uh-accent;
	}

	.demo-take-block {
		margin-top: 22rpx;
		padding: 20rpx;
		border-radius: 14rpx;
		background: $uh-accent-weak;
	}

	.demo-take-label {
		display: block;
		font-size: 23rpx;
		font-weight: 700;
		color: $uh-ink-1;
	}

	.demo-take {
		display: block;
		margin-top: 8rpx;
		font-size: 24rpx;
		line-height: 1.75;
		color: $uh-ink-1;
	}

	.demo-note {
		display: block;
		margin-top: 20rpx;
		text-align: center;
		font-size: 21rpx;
		color: $uh-ink-3;
	}

	.demo-foot {
		display: flex;
		justify-content: center;
		margin-top: 24rpx;
	}

	.demo-take {
		display: block;
		margin-top: 18rpx;
		padding-top: 16rpx;
		border-top: 1rpx solid $uh-line;
		font-size: 24rpx;
		line-height: 1.7;
		color: $uh-ink-1;
	}

	/* ── 售前：购买按钮 ── */
	.goods-cta {
		flex-shrink: 0;
		margin-left: 16rpx;
		display: flex;
		flex-direction: column;
		align-items: flex-end;
	}

	.goods-btn {
		margin-top: 8rpx;
		padding: 8rpx 22rpx;
		border-radius: $uh-radius-pill;
		background: $uh-accent;
		color: #ffffff;
		font-size: 22rpx;
		font-weight: 600;
	}

	.empty-tip {
		padding: 40rpx 0;
		font-size: 23rpx;
		line-height: 1.7;
		color: $uh-ink-3;
		text-align: center;
	}

	/* ── 积分兑换 ── */
	.redeem-row {
		display: flex;
		align-items: center;
		margin-top: 14rpx;
		padding: 20rpx 24rpx;
		border-radius: 16rpx;
		background: $uh-warning-weak;
	}

	.redeem-row-hover {
		opacity: 0.86;
	}

	.redeem-main {
		flex: 1;
		min-width: 0;
	}

	.redeem-title {
		display: block;
		font-size: 25rpx;
		font-weight: 600;
		color: $uh-ink-1;
	}

	.redeem-desc {
		display: block;
		margin-top: 4rpx;
		font-size: 20rpx;
		color: $uh-ink-3;
	}

	.redeem-btn {
		margin-left: 16rpx;
		padding: 10rpx 26rpx;
		border-radius: 999rpx;
		background: $uh-warning;
		color: #fff;
		font-size: 24rpx;
		font-weight: 600;
	}

	/* ── 定制主题 ── */
	.suggest-block {
		margin-top: 16rpx;
		padding-top: 4rpx;
	}

	.suggest-label {
		display: block;
		margin-bottom: 2rpx;
		font-size: 21rpx;
		color: $uh-ink-3;
	}

	.advanced-toggle {
		margin-top: 16rpx;
		padding: 10rpx 0;
		text-align: center;
		border-top: 1rpx solid #f0f1f5;
	}

	.advanced-toggle-hover {
		opacity: 0.7;
	}

	.advanced-toggle-text {
		font-size: 22rpx;
		color: $uh-accent;
	}

	/* ── 简报海报入口与定制内容卡 ── */
	.section-head-with-action {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
	}

	.section-head-main {
		flex: 1;
		min-width: 0;
	}

	.feed-poster {
		flex-shrink: 0;
		margin-left: 12rpx;
		padding: 8rpx 22rpx;
		border-radius: 999rpx;
		background: $uh-accent-weak;
		font-size: 22rpx;
		font-weight: 600;
		color: $uh-accent;
	}

	.feed-poster-hover {
		opacity: 0.7;
	}

	.feed-generated {
		padding: 20rpx 22rpx;
		margin-top: 14rpx;
		border-radius: 14rpx;
		background: $uh-accent-weak;
	}

	.feed-generated-head {
		display: flex;
		align-items: center;
	}

	.feed-generated-tag {
		flex-shrink: 0;
		margin-right: 12rpx;
		padding: 2rpx 14rpx;
		border-radius: 999rpx;
		background: $uh-accent;
		color: #fff;
		font-size: 20rpx;
	}

	.feed-generated-title {
		font-size: 27rpx;
		font-weight: 700;
		color: $uh-ink-1;
	}

	.feed-generated-body {
		display: block;
		margin-top: 12rpx;
		font-size: 24rpx;
		line-height: 1.75;
		color: $uh-ink-1;
	}

	.feed-generated-note {
		display: block;
		margin-top: 12rpx;
		font-size: 20rpx;
		color: $uh-ink-3;
	}

	.footer-tip {
		padding: 0 36rpx;
		font-size: 21rpx;
		line-height: 1.7;
		color: #a1a6b8;
	}
</style>
