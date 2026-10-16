<template>
	<view :class="skinClass" class="app-page ai-content-theme">
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
						<text class="advanced-toggle-text">{{ showAdvanced ? '收起高级筛选 ▲' : '展开高级筛选（主题分类 / 关键词）▼' }}</text>
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

					<!-- 来源维度已取消：简报从实时抓取的源里挑内容，用户只表达「看什么」，不选「从哪看」 -->
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
							<text class="section-sub">{{ feedLoading ? '正在读取今天的简报…' : (feed && feed.summary ? feed.summary : '') }}</text>
							<!-- 剩余生成次数：付费功能的成本阀门，用户随时能看到还能生成几次 -->
							<text v-if="quotaText" class="section-quota">{{ quotaText }}</text>
						</view>
						<text
							v-if="feedItems.length && !feedLoading"
							class="feed-poster"
							hover-class="feed-poster-hover"
							@click="fnGeneratePoster"
						>生成海报</text>
					</view>

					<view v-if="feedLoading" class="feed-loading">
						<text class="feed-loading-text">正在读取今天的简报…</text>
					</view>

					<block v-else>
						<!-- 生成中：进度由服务端写入，这里只负责显示（退出页面不影响生成） -->
						<view v-if="feedStatus === 'RUNNING'" class="feed-progress">
							<view class="feed-progress-head">
								<text class="feed-progress-stage">{{ feedStage || '正在生成…' }}</text>
								<text class="feed-progress-num">{{ feedProgress }}%</text>
							</view>
							<view class="feed-progress-track">
								<view class="feed-progress-fill" :style="{ width: feedProgress + '%' }"></view>
							</view>
							<text class="feed-progress-tip">可以离开这个页面：生成在服务端继续，完成后回来就能看到</text>
						</view>

						<!-- 还没生成过：给一个明确的起点，而不是自动烧掉一次 AI -->
						<view v-else-if="feedStatus === 'NONE'" class="feed-empty">
							<text class="feed-empty-title">{{ isViewingToday ? '今天还没有生成简报' : '这一天没有生成简报' }}</text>
							<text class="feed-empty-desc">{{ feed && feed.summary ? feed.summary : '按你的关注方向实时抓取最新内容，生成一份只属于你的简报' }}</text>
							<text v-if="quotaText" class="feed-empty-desc">{{ quotaText }}</text>
							<view v-if="isViewingToday" class="feed-empty-btn"
								:class="{ 'is-off': !canGenerateNow }" hover-class="save-btn-hover"
								@click="fnGenerate">
								<text class="feed-empty-btn-text">{{ generating ? '正在开始…' : (canGenerateNow ? '生成今日简报' : '生成次数已用完') }}</text>
							</view>
						</view>

						<!-- 生成失败/中断：给原因与重试入口（往期补不回来，只给原因） -->
						<view v-else-if="feedStatus === 'FAILED'" class="feed-empty">
							<text class="feed-empty-title">{{ isViewingToday ? '今天的简报没有生成成功' : '这一天的简报没有生成成功' }}</text>
							<text class="feed-empty-desc">{{ feedError || '生成中断了，可以再试一次' }}</text>
							<text v-if="quotaText" class="feed-empty-desc">{{ quotaText }}</text>
							<view v-if="isViewingToday" class="feed-empty-btn"
								:class="{ 'is-off': !canGenerateNow }" hover-class="save-btn-hover"
								@click="fnGenerate">
								<text class="feed-empty-btn-text">{{ generating ? '正在开始…' : (canGenerateNow ? '重新生成' : '生成次数已用完') }}</text>
							</view>
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
											<view class="feed-item-head-main">
												<text class="feed-item-title">{{ item.title }}</text>
												<text v-if="item.postName" class="feed-item-arrow">›</text>
											</view>
											<!-- 原文头图（已转存进站点附件库）：点图可放大看 -->
											<image v-if="item.cover" class="feed-item-cover" :src="fnItemCover(item)"
												mode="aspectFill" lazy-load @click.stop="fnPreviewCover(item)"></image>
										</view>
									<view class="feed-item-meta">
										<text v-if="item.categoryLabel" class="feed-item-tag">{{ item.categoryEmoji }} {{ item.categoryLabel }}</text>
										<!-- 显示发布时间而不是来源：简报的卖点是「刚抓到的」，来源对读者不是有效信息 -->
										<text v-if="item.publishedAt" class="feed-item-time">{{ item.publishedAt }}</text>
									</view>
									<text v-if="item.reason" class="feed-item-reason">{{ item.reason }}</text>
									<text v-else-if="item.summary" class="feed-item-summary">{{ item.summary }}</text>
									<text v-if="item.matchedReason" class="feed-item-match">{{ item.matchedReason }}</text>
									</view>
								</view>
							</view>

							<!-- AI 小结：这一份里唯一的原创观点段落，放在条目之后收尾 -->
						<view v-if="feed && feed.outro" class="feed-outro">
							<text class="feed-outro-label">✍️ AI 小结</text>
							<text class="feed-outro-text">{{ feed.outro }}</text>
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
				<text>简报按你的定制主题与关键词生成：服务端<b>实时抓取各源最近 {{ limits.liveHours }} 小时的内容</b>，再由 AI 挑出属于你的几条并写成中文标题与摘要；内容仅你自己可见，可生成海报分享。每天最多生成一次，反复打开不会重复消耗 AI 额度。</text>
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
								<text class="demo-item-time">{{ item.time }}</text>
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
	import { getCommentAuthorInfo } from '@/utils/comment.js';
	import { getAppConfigs } from '@/config/index.js';
	import { payVirtualGoods } from '@/utils/virtualPay.js';
	import {
		requestSubscribe,
		accumulatePushQuota,
		openSubscribeSetting,
		pushRemindState,
		PUSH_KINDS
	} from '@/utils/subscribeMessage.js';

	/** 海报上最多列几条：条数再多就成了「列表截图」，而不是一张能发出去的海报 */
	const POSTER_ITEM_LIMIT = 3;
	/** 海报绘制的兜底超时：画布在图片异常时不会回调，得自己收尾 */
	const POSTER_TIMEOUT_MS = 20000;

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
					maxKeywords: 10,
					maxTopics: 3,
					maxItems: 10,
					/** 实时素材时间窗：页面要说清「内容取自最近 N 小时」 */
					liveHours: 48,
					windowDays: 7,
					aiIntroEnabled: true
				},
				benefitNote: '',
				categoryOptions: [],
				/** 站长配置的预设定制主题（点一下即加入） */
				presetTopics: [],
				/** 积分兑换通道参数（pointsPrice=0 表示未开启） */
				redeem: { pointsPrice: 0, pointsDays: 30 },
				/** 正在编辑的关注方向与推送设置（保存前的本地状态） */
				picked: { categories: [], keywords: [], customTopics: [], pushEnabled: false, pushHour: null },
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
						time: '今天 08:40',
						title: '某地放开校内 AI 助教试点，秋季学期开始落地',
						reason: '政策放开是这条赛道的转折点，建议先看它'
					},
					{
						category: '🛠 工具',
						time: '今天 07:55',
						title: '两款课堂 AI 产品同期发布，都主打「给老师减负」',
						reason: '同一天两家的取舍不同，对比着看更有意思'
					},
					{
						category: '💬 观点',
						time: '昨天 21:10',
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
				/** 生成状态：NONE（今天还没生成）/ RUNNING / READY / FAILED */
				feedStatus: 'NONE',
				/** 生成中的阶段文案与进度（服务端写入，轮询读到） */
				feedStage: '',
				feedProgress: 0,
				feedError: '',
				/** 正在调用「触发生成」接口（防连点） */
				generating: false,
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
			/**
			 * 剩余生成次数文案。
			 *
			 * <p>{@code remainingGenerations = -1} 表示不限（特权用户）或本次未查到——
			 * 后者不显示，宁可少一行字也不显示一个错的数字。</p>
			 */
			quotaText() {
				const feed = this.feed;
				if (!feed || typeof feed.remainingGenerations !== 'number' || feed.remainingGenerations < 0) {
					return '';
				}
				const parts = ['本期还可生成 ' + feed.remainingGenerations + ' 次'];
				if (feed.dailyGenerationLimit > 0) {
					parts.push('今日已生成 ' + (feed.todayGenerations || 0) + '/' + feed.dailyGenerationLimit + ' 次');
				}
				return parts.join(' · ');
			},
			/** 现在还能不能生成（前端先挡一道，服务端仍是权威判定） */
			canGenerateNow() {
				const feed = this.feed;
				if (!feed) {
					return true;
				}
				if (typeof feed.remainingGenerations === 'number' && feed.remainingGenerations === 0) {
					return false;
				}
				return !(feed.dailyGenerationLimit > 0
					&& (feed.todayGenerations || 0) >= feed.dailyGenerationLimit);
			},
			/** 当前看的是不是今天（往期只读：没生成过就补不回来，别给生成按钮） */
			isViewingToday() {
				if (!this.activeDate) {
					return true;
				}
				const now = new Date();
				const pad = (n) => (n < 10 ? '0' + n : '' + n);
				const today = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
				return this.activeDate === today;
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
		onShow() {
			// 生成在服务端跑：离开期间它照跑（也可能是定时推送线生成的），回来对齐一次状态。
			// 首次进页面时权益状态还没到，交给 fnLoad 负责
			if (this.status) {
				this.fnRefreshFeedQuietly();
			}
		},
		onUnload() {
			// 海报还没画完就离开页面：把兜底定时器收掉，别在别的页面上弹提示
			clearTimeout(this._posterTimer);
			this.posterBusy = false;
			// 轮询也停掉：生成本身在服务端，停的只是「显示进度」这件事
			this.fnStopPolling();
		},
		onHide() {
			this.fnStopPolling();
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
					this.presetTopics = Array.isArray(body.presetTopics) ? body.presetTopics : [];
					this.redeem = body.redeem || { pointsPrice: 0, pointsDays: 30 };
					this.pushSettings = body.push || { hourOptions: [], ready: false };
					const preference = body.preference || {};
					this.picked = {
						categories: Array.isArray(preference.categories) ? preference.categories.slice() : [],

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
			/**
			 * 读取某一天的简报（<b>只读</b>：打开页面不再自动生成）。
			 *
			 * <p>生成由用户点按钮触发，进度靠轮询这条接口拿——所以这里既要把状态写进本地，
			 * 也要根据状态决定是否开始轮询（生成中时每 2.5 秒看一次）。</p>
			 */
			async fnLoadFeed(date) {
				this.feedLoading = true;
				try {
					const res = await this.$httpApi.v2.getAiContentFeed(date || undefined);
					const body = res?.data || res || {};
					if (body.error) {
						throw { error: body.error };
					}
					this.fnApplyFeed(body, date);
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

			/** 把服务端读模型落到本地状态，并据此决定要不要继续轮询 */
			fnApplyFeed(body, date) {
				this.feed = body;
				this.feedItems = Array.isArray(body.items) ? body.items : [];
				this.activeDate = body.date || date || '';
				this.feedStatus = body.status || 'NONE';
				this.feedStage = body.stage || '';
				this.feedProgress = Number(body.progress || 0);
				this.feedError = body.error || '';
				if (this.feedStatus === 'RUNNING' && !date) {
					// 生成在服务端跑，退出页面也不会中断；页面上只要把进度显示出来
					this.fnStartPolling();
				} else {
					this.fnStopPolling();
				}
			},

			/** 生成中：每 2.5 秒问一次进度（服务端在后台跑，不依赖这个定时器） */
			fnStartPolling() {
				if (this._pollTimer) {
					return;
				}
				this._pollTimer = setInterval(() => {
					if (this.feedStatus !== 'RUNNING') {
						this.fnStopPolling();
						return;
					}
					this.fnRefreshFeedQuietly();
				}, 2500);
			},

			fnStopPolling() {
				if (this._pollTimer) {
					clearInterval(this._pollTimer);
					this._pollTimer = null;
				}
			},

			/** 轮询用：失败不弹提示（网络抖动不该在生成过程中打扰用户） */
			async fnRefreshFeedQuietly() {
				try {
					const res = await this.$httpApi.v2.getAiContentFeed();
					const body = res?.data || res || {};
					if (body.error) {
						return;
					}
					const wasRunning = this.feedStatus === 'RUNNING';
					this.fnApplyFeed(body, '');
					if (wasRunning && this.feedStatus === 'READY') {
						uni.showToast({ title: '今天的简报已生成', icon: 'none' });
						this.fnLoadIssues();
					} else if (wasRunning && this.feedStatus === 'FAILED') {
						this.fnStopPolling();
					}
				} catch (err) {
					// 静默：下一轮再试
				}
			},

			/** 用户点「生成」：服务端后台跑，这里只负责把状态与进度显示出来 */
			async fnGenerate() {
				if (this.generating || this.feedStatus === 'RUNNING') {
					return;
				}
				if (!this.canGenerateNow) {
					uni.showModal({
						title: '生成次数已用完',
						content: '本次权益内的生成次数已经用完，可以再购买或积分兑换；'
							+ '站长也能在后台调整「每个权益日赠送几次」。',
						showCancel: false,
						confirmText: '知道了'
					});
					return;
				}
				this.generating = true;
				try {
					const res = await this.$httpApi.v2.generateAiContent();
					const body = res?.data || res || {};
					if (body.error) {
						throw { error: body.error };
					}
					this.fnApplyFeed(body, '');
					if (this.feedStatus === 'READY') {
						// 走到这里说明服务端判定「已有一份可用内容」（例如上一次生成还在收尾）——
						// 措辞不能再写成「已是最新」：那句话会让用户以为「点生成被跳过了」
						uni.showToast({ title: '当前显示的是最近一次生成的简报', icon: 'none' });
					} else {
						uni.showToast({ title: '已开始生成，可离开页面，生成好再回来查看', icon: 'none' });
					}
				} catch (err) {
					uni.showToast({
						title: err?.error || err?.detail || '生成失败，请稍后重试',
						icon: 'none'
					});
				} finally {
					this.generating = false;
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
					} else if (body.needQuota) {
						// 额度是「一次授权一条」：这里直接把人引到微信的授权弹窗，
						// 授权成功后自动重试一次，省掉「回页面再点一遍」
						uni.showModal({
							title: '没有推送额度',
							content: body.message || '微信要求每次推送前授权一次，点「去授权」继续',
							confirmText: '去授权',
							confirmColor: '#2F54EB',
							success: (modal) => {
								if (!modal.confirm) {
									return;
								}
								this.fnAskPushPermission().then((granted) => {
									if (granted) {
										this.fnPushNow();
									}
								});
							}
						});
					} else {
						// 文案由服务端给全（已推过 / 站点日上限 / 微信拒收的原始原因），
						// 前端不再自己拼「额度不足」——那句错话曾让用户以为功能坏了
						uni.showModal({
							title: '暂时不能推送',
							content: body.message || '请稍后重试',
							showCancel: false,
							confirmText: '知道了'
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

			// ── 简报海报 ──
			/**
			 * 生成简报海报。
			 *
			 * <p>版式：品牌渐变头部（日期 + 署名）→ 导语卡（左侧强调条）→ 条目卡（序号 / 分类与时间 /
			 * 标题）→ 小程序码。旧的「白底 + 几行文字」在海报场景里等于没设计——海报是要发给别人看的，
			 * 所以头部与卡片必须有层次，并给出扫码入口。</p>
			 *
			 * <p>图片先下载成本地文件再交给画布：`liu-poster` 只对含 http 的地址走 downloadFile，
			 * 相对路径会被原样丢进 canvas（开发者工具里解析成 pageframe 地址 → 永远下载不完 →
			 * 绘制链挂住，loading 一直转）。站长的码图又常配成相对路径，所以这里统一兜住。</p>
			 */
			async fnGeneratePoster() {
				if (this.posterBusy) return;
				this.posterBusy = true;
				// 定时器先上：图片下载或画布任一步不回调，都能收尾（否则按钮会永久卡住）
				this.fnArmPosterTimeout();
				try {
					const configs = await this.fnBuildPosterConfigs();
					this.$refs.liuPoster.init(configs);
				} catch (err) {
					this.posterBusy = false;
					uni.hideLoading();
					uni.showToast({ title: '海报生成失败，请稍后重试', icon: 'none' });
				}
			},

			/**
			 * 组装海报元素。
			 *
			 * <p>画布吃坐标不吃样式，四条硬约束必须自己守住（改坐标前先读这一条）：</p>
			 * <ul>
			 *   <li><b>不能用换行符</b>：渲染层按字符逐个测量，`\n` 不会换行，两句话会挤成一行
			 *       —— 要分行就分开写两个 text 元素；</li>
			 *   <li><b>y 不是第一行的基线</b>：实际画的是 {@code y + lineHeight × (第几行+1)}，
			 *       所以「想让首行基线落在 B」就传 {@code y = B - lineHeight}。
			 *       早前按「y 即基线」摆坐标，序号数字被推出了方块、标题第二行压到卡片外；</li>
			 *   <li><b>高度按 lineHeight 的整数倍给</b>：放不下时只画前 N 行、末行自动加省略号；想完整显示
			 *       两行就给 {@code 2 × lineHeight} 的高度；</li>
			 *   <li>文字顶端 ≈ 首行基线 − fontSize：方块与标题之间的间隙要按这个估算，不能只留几像素。</li>
			 * </ul>
			 */
			async fnBuildPosterConfigs() {
				const appConfigs = getAppConfigs() || {};
				const siteTitle = appConfigs?.basicConfig?.startConfig?.title || '';
				const appInfo = appConfigs?.appConfig?.appInfo || {};
				const qrPath = await this.fnPreparePosterImage(appInfo.qrCodeImageUrl);
				const nickname = (getCommentAuthorInfo()?.displayName || '').trim();
				const date = this.activeDate || (this.feed && this.feed.date) || '';
				const intro = ((this.feed && this.feed.intro) || '').slice(0, 130);
				const items = this.feedItems.slice(0, POSTER_ITEM_LIMIT).map((item) => ({
					title: item.title || '（无标题）',
					meta: [item.categoryLabel, item.publishedAt].filter(Boolean).join(' · ')
				}));

				const configs = [
					// 卡片底
					{
						type: 'color', width: 674, height: 1000, x: 0, y: 0,
						radius: 28, lineWidth: 0, lineColor: '#ffffff',
						colorObj: { colorList: ['#FFFFFF'], direction: 2 }
					},
					// 头部渐变卡：24..204
					{
						type: 'color', width: 626, height: 180, x: 24, y: 24,
						radius: 24, lineWidth: 0, lineColor: '#ffffff',
						colorObj: { colorList: ['#2f54eb', '#6b4df0'], direction: 3 }
					},
					// 头部装饰圆
					{
						type: 'color', width: 180, height: 180, x: 440, y: 10,
						radius: 90, lineWidth: 0, lineColor: '#ffffff',
						colorObj: { colorList: ['rgba(255,255,255,0.10)'], direction: 2 }
					},
					{
						type: 'color', width: 120, height: 120, x: 30, y: 140,
						radius: 60, lineWidth: 0, lineColor: '#ffffff',
						colorObj: { colorList: ['rgba(255,255,255,0.08)'], direction: 2 }
					},
					// 首行基线 92（y = 92 - 34）
					{
						type: 'text', width: 260, height: 34, x: 52, y: 58,
						color: 'rgba(255,255,255,0.88)', fontSize: 22, lineHeight: 34, bold: false,
						content: '✦ AI 定制简报'
					},
					// 首行基线 152（y = 152 - 56）
					{
						type: 'text', width: 460, height: 56, x: 52, y: 96,
						color: '#FFFFFF', fontSize: 38, lineHeight: 56, bold: true,
						content: date || '我的简报'
					},
					// 首行基线 190（y = 190 - 34）
					{
						type: 'text', width: 520, height: 34, x: 52, y: 156,
						color: 'rgba(255,255,255,0.82)', fontSize: 21, lineHeight: 34, bold: false,
						content: (nickname ? nickname + ' 的专属简报' : '我的专属简报')
							+ (siteTitle ? ' · ' + siteTitle : '')
					}
				];

				let y = 220;
				if (intro) {
					// 导语卡 220..360：三行（37×3=111），首行基线 271（y = 234）
					configs.push({
						type: 'color', width: 626, height: 140, x: 24, y: y,
						radius: 20, lineWidth: 0, lineColor: '#ffffff',
						colorObj: { colorList: ['#F3F5FC'], direction: 2 }
					});
					configs.push({
						type: 'color', width: 6, height: 96, x: 46, y: y + 28,
						radius: 3, lineWidth: 0, lineColor: '#ffffff',
						colorObj: { colorList: ['#2f54eb'], direction: 2 }
					});
					configs.push({
						type: 'text', width: 552, height: 111, x: 70, y: y + 14,
						color: '#3c3c46', fontSize: 23, lineHeight: 37, bold: false,
						content: intro
					});
					y += 154;
				}

				items.forEach((item, index) => {
					// 条目卡 136 高：方块 y+16..y+60
					configs.push({
						type: 'color', width: 626, height: 136, x: 24, y: y,
						radius: 18, lineWidth: 0, lineColor: '#ffffff',
						colorObj: { colorList: ['#FAFBFF'], direction: 2 }
					});
					configs.push({
						type: 'color', width: 44, height: 44, x: 44, y: y + 16,
						radius: 14, lineWidth: 0, lineColor: '#ffffff',
						colorObj: { colorList: ['#2f54eb', '#6b4df0'], direction: 3 }
					});
					// 序号：方块的竖直中线 ≈ y+38，字号 22 → 首行基线给到 y+47（y = y+17）
					configs.push({
						type: 'text', width: 60, height: 30, x: 53, y: y + 17,
						color: '#FFFFFF', fontSize: 22, lineHeight: 30, bold: true,
						content: '0' + (index + 1)
					});
					// 分类 / 发布时间：与方块同一行（首行基线 y+46 → y = y+16）
					if (item.meta) {
						configs.push({
							type: 'text', width: 420, height: 30, x: 104, y: y + 16,
							color: '#2f54eb', fontSize: 20, lineHeight: 30, bold: true,
							content: item.meta
						});
					}
					// 标题：两行（30×2=60），首行基线 y+88 → 文字顶端 ≈ y+64，方块下沿 y+60 之上留 4px
					configs.push({
						type: 'text', width: 566, height: 60, x: 44, y: y + 58,
						color: '#1f1f28', fontSize: 24, lineHeight: 30, bold: true,
						content: item.title
					});
					y += 144;
				});

				// 页脚：跟着内容走，但不越过固定底线（条目少时上移，避免中间一大块空白）
				const footerTop = Math.min(Math.max(y + 10, 760), 812);
				configs.push({
					type: 'line', width: 1, color: '#E4E6F0', lineType: 'dash',
					startX: 44, startY: footerTop, endX: 630, endY: footerTop
				});
				if (qrPath) {
					configs.push({
						type: 'image', width: 96, height: 96, x: 24, y: footerTop + 14,
						radius: 12, lineWidth: 0, lineColor: '#ffffff',
						path: qrPath
					});
				}
				// 说明文字分两行写：画布不认换行符。首行基线 footerTop+56（y = footerTop+22）、
				// 第二行基线 footerTop+90（y = footerTop+56），与二维码竖直居中对齐
				configs.push({
					type: 'text', width: 470, height: 34, x: 140, y: footerTop + 22,
					color: '#5a5a66', fontSize: 23, lineHeight: 34, bold: false,
					content: qrPath ? '长按识别小程序码' : '在小程序里搜索「' + (siteTitle || '本站') + '」'
				});
				configs.push({
					type: 'text', width: 470, height: 34, x: 140, y: footerTop + 56,
					color: '#5a5a66', fontSize: 23, lineHeight: 34, bold: false,
					content: '定制一份你自己的每日简报'
				});
				// 底部小字固定在最下方（首行基线 962 → y = 932）
				configs.push({
					type: 'text', width: 626, height: 30, x: 24, y: 932,
					color: '#9a9aa6', fontSize: 20, lineHeight: 30, bold: false,
					content: '内容仅你自己可见 · 每天一份按你的方向挑的最新内容'
				});

				return configs;
			},

			/**
			 * 把海报里的图片准备成「本地临时文件路径」。
			 *
			 * <p>两步都不能省：① 站长的码图常配成相对路径（{@code /upload/xxx.jpg}），
			 * 先补成站点绝对地址；② 画布只能画本地文件，所以自己先 downloadFile——
			 * `liu-poster` 对不含 http 的地址会原样丢给 canvas，失败时那条 Promise 不落地，
			 * 整个绘制链就挂住（表现是「正在绘制」一直转）。</p>
			 */
			async fnPreparePosterImage(raw) {
				const url = this.$utils.checkUrl(String(raw || '').trim());
				if (!/^https?:\/\//i.test(url)) {
					return '';
				}
				try {
					const res = await new Promise((resolve, reject) => {
						uni.downloadFile({ url, success: resolve, fail: reject });
					});
					if (res && res.statusCode === 200 && res.tempFilePath) {
						return res.tempFilePath;
					}
					console.warn('[ai-content] 海报图片下载异常，已跳过：', res && res.statusCode);
					return '';
				} catch (err) {
					console.warn('[ai-content] 海报图片下载失败，已跳过：', err?.errMsg || err);
					return '';
				}
			},

			/** 绘制兜底超时：图片出问题时不至于让「正在绘制」永远转下去 */
			fnArmPosterTimeout() {
				clearTimeout(this._posterTimer);
				this._posterTimer = setTimeout(() => {
					if (!this.posterBusy) {
						return;
					}
					this.posterBusy = false;
					uni.hideLoading();
					uni.showToast({ title: '海报绘制超时，请稍后重试', icon: 'none' });
				}, POSTER_TIMEOUT_MS);
			},

			handleOnPosterChange(posterUrl) {
				clearTimeout(this._posterTimer);
				this.posterBusy = false;
				if (!posterUrl) {
					uni.showToast({ title: '海报生成失败，请稍后重试', icon: 'none' });
					return;
				}
				uni.previewImage({ urls: [posterUrl] });
			},
			/**
			 * 条目封面地址。
			 *
			 * <p>检索结果的图已转存进站点附件库（相对路径），不补域名在真机上加载不出来；
			 * 已经是绝对地址的不动。</p>
			 */
			fnItemCover(item) {
				const cover = (item && item.cover) || '';
				return this.$utils.checkIsUrl(cover) ? cover : this.$utils.checkUrl(cover);
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
						keywords: (body.keywords || []).slice(),
						customTopics: (body.customTopics || []).slice(),
						pushEnabled: body.pushEnabled === true,
						pushHour: typeof body.pushHour === 'number' ? body.pushHour : this.picked.pushHour
					};
					this.fnLoadPushStatus();
					// 刚配好关注方向是最自然的授权时机：此时问「简报就绪时通知你」不会被当成打扰
					this.fnMaybeAskPushPermission();
					// 保存后直接触发生成（服务端后台跑）：改过方向时当天那份已被作废，
					// 没改过或已生成则由服务端原样返回，不会重复烧 AI 额度
					await this.fnGenerate();
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
			/**
			 * 打开一条内容。
			 *
			 * <p>实时素材来自站外源头（没有站内文章），小程序又不能直接开外链，
			 * 所以这类条目走「弹窗看摘要 + 复制原文链接」：至少让用户能把链接带走。
			 * 只有兜底路径（站内日报内容）的条目才跳详情页。</p>
			 */
			fnOpenItem(item) {
				if (item.postName) {
					uni.navigateTo({
						url: '/pagesA/article-detail/article-detail?name=' + item.postName
					});
					return;
				}
				if (!item.link) {
					uni.showToast({ title: '这条内容没有可跳转的原文', icon: 'none' });
					return;
				}
				uni.showModal({
					title: item.title || '原文链接',
					content: (item.summary ? item.summary + '\n\n' : '')
						+ '复制链接后可在浏览器里打开原文。',
					confirmText: '复制链接',
					confirmColor: '#2F54EB',
					success: (res) => {
						if (res.confirm) {
							uni.setClipboardData({ data: item.link });
						}
					}
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
		padding-top: 1rpx;
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

	.feed-item-head-main {
		flex: 1;
		min-width: 0;
	}

	/* 缩略图：有图更醒目；没图时这一块整个不渲染，标题照旧占满 */
	.feed-item-cover {
		flex: none;
		width: 150rpx;
		height: 104rpx;
		margin-left: 16rpx;
		border-radius: $uh-radius-md;
		background-color: $uh-fill;
	}

	/* AI 小结：与上面的条目用一条浅分隔线隔开，像编辑手记 */
	.feed-outro {
		margin-top: 18rpx;
		padding: 18rpx 20rpx;
		border-radius: $uh-radius-md;
		background-color: $uh-fill;
	}

	.feed-outro-label {
		display: block;
		font-size: 22rpx;
		font-weight: 600;
		color: $uh-accent;
	}

	.feed-outro-text {
		display: block;
		margin-top: 10rpx;
		font-size: 24rpx;
		line-height: 1.7;
		color: $uh-ink-2;
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

	/* ── 生成进度（服务端写入状态，前端只读） ── */
	.feed-progress {
		padding: 8rpx 0 4rpx;
	}

	.feed-progress-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
	}

	.feed-progress-stage {
		font-size: 25rpx;
		color: $uh-ink-1;
	}

	.feed-progress-num {
		font-size: 24rpx;
		color: $uh-accent;
	}

	.feed-progress-track {
		height: 10rpx;
		margin-top: 16rpx;
		border-radius: $uh-radius-pill;
		background-color: $uh-fill;
		overflow: hidden;
	}

	.feed-progress-fill {
		height: 10rpx;
		border-radius: $uh-radius-pill;
		background-image: $uh-grad-accent;
		transition: width 0.3s ease;
	}

	.feed-progress-tip {
		display: block;
		margin-top: 14rpx;
		font-size: 22rpx;
		color: $uh-ink-3;
	}

	/* ── 空态（今天还没生成 / 生成失败）：给一个明确的起点与重试入口 ── */
	.feed-empty {
		padding: 12rpx 0 4rpx;
	}

	.feed-empty-title {
		display: block;
		font-size: 27rpx;
		font-weight: 600;
		color: $uh-ink-1;
	}

	.feed-empty-desc {
		display: block;
		margin-top: 10rpx;
		font-size: 24rpx;
		line-height: 1.6;
		color: $uh-ink-3;
	}

	.feed-empty-btn {
		margin-top: 22rpx;
		padding: 20rpx 0;
		border-radius: $uh-radius-pill;
		background-image: $uh-grad-accent;
		text-align: center;
	}

	.section-quota {
		display: block;
		margin-top: 8rpx;
		font-size: 22rpx;
		color: $uh-accent;
	}

	.feed-empty-btn.is-off {
		background-image: none;
		background-color: $uh-fill;
	}

	.feed-empty-btn.is-off .feed-empty-btn-text {
		color: $uh-ink-3;
	}

	.feed-empty-btn-text {
		font-size: 27rpx;
		font-weight: 600;
		color: #ffffff;
	}

	.feed-item-tag {
		margin-right: 14rpx;
	}

	/* 发布时间与分类同一行：让读者一眼看出「这是刚抓到的」 */
	.feed-item-time {
		font-size: 20rpx;
		color: $uh-ink-4;
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

	.demo-item-time {
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
@import '@/common/styles/redesign/ai-content-themes.scss';
</style>
