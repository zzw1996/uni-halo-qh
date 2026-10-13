<template>
	<view :class="[skinClass, 'publish-page', zenMode ? 'publish-page--zen' : '']">
		<view v-if="!statusChecked" class="loading-wrap flex flex-center">
			<tm-loadding color="indigo" :size="48"></tm-loadding>
		</view>

		<view v-else-if="editingLoading" class="loading-wrap flex flex-center">
			<tm-loadding color="indigo" :size="48"></tm-loadding>
		</view>

		<!-- 编辑模式不看「能否新投稿」：改自己的旧稿不该被当前的投稿策略挡在门外 -->
		<view v-else-if="!canPublish && !isEditing" class="denied-wrap flex flex-center flex-col">
			<tm-empty icon="icon-article" label="暂时不能投稿"></tm-empty>
			<text class="denied-tip">{{ disabledReason }}</text>
			<tm-button class="mt-24" plan theme="grey" :fllowTheme="false" :shadow="0"
				@click="fnBack">返回</tm-button>
		</view>

		<!--
			提交成功后不直接退回上页：给「查看文章 / 再写一篇」两个出口，
			否则用户还得自己回忆刚发的标题去站内找。
		-->
		<view v-else-if="resultPanel" class="publish-body">
			<view class="result-card">
				<view class="result-head">
					<text class="result-mark">✓</text>
					<text class="result-badge">{{ resultPanel.editing ? '已保存' : (resultPanel.mode === 'direct' ? '已发布' : '已提交') }}</text>
				</view>
				<text class="result-title">{{ resultPanel.title }}</text>
				<text class="result-desc">{{ resultPanel.statusText }}</text>
				<view class="result-rows">
					<view class="result-row">
						<text class="result-label">正文字数</text>
						<text class="result-value">{{ resultPanel.words }} 字</text>
					</view>
					<view class="result-row">
						<text class="result-label">积分消耗</text>
						<text class="result-value">{{ resultPanel.pointsCost > 0 ? ('-' + resultPanel.pointsCost) : '未扣分' }}</text>
					</view>
					<view v-if="resultPanel.mode !== 'direct' && !resultPanel.editing" class="result-row">
						<text class="result-label">审核通过后</text>
						<text class="result-value">可在站内查看文章</text>
					</view>
				</view>
				<tm-button
					v-if="resultPanel.canOpen"
					block
					theme="bg-gradient-indigo-accent"
					:shadow="4"
					@click="fnOpenArticle"
				>查看文章</tm-button>
				<tm-button v-if="resultPanel.editing" class="mt-16" block plan theme="grey"
					:fllowTheme="false" :shadow="0" @click="fnBackToList">返回我的投稿</tm-button>
				<tm-button v-else class="mt-16" block plan theme="grey" :fllowTheme="false" :shadow="0"
					@click="fnWriteAnother">再写一篇</tm-button>
				<tm-button class="mt-16" block plan theme="grey" :fllowTheme="false" :shadow="0"
					@click="fnBack">返回</tm-button>
			</view>
		</view>

		<view v-else class="publish-body">
			<!-- 极简写作：只留字数与退出入口，小屏写长文时工具条不抢视线 -->
			<view v-if="zenMode" class="zen-bar">
				<text class="zen-count">{{ contentLength }} 字 · 约 {{ readMinutes }} 分钟</text>
				<text class="zen-exit" @click="fnToggleZen">退出极简</text>
			</view>

			<view v-else class="status-card">
				<view class="status-line">
					<text class="status-mode" :class="isDraftMode ? 'is-draft' : 'is-direct'">{{ modeLabel }}</text>
					<text v-if="authorName" class="status-author">作者 {{ authorName }}</text>
				</view>
				<view class="status-chips">
					<text class="status-chip">{{ quotaText }}</text>
					<text class="status-chip">{{ costText }}</text>
					<text class="status-chip">正文上限 {{ maxContentLength }} 字</text>
				</view>
				<text v-if="thresholdHint" class="status-hint">{{ thresholdHint }}</text>
				<text v-if="securityHint" class="status-hint">{{ securityHint }}</text>
			</view>

			<view v-if="!zenMode" class="seg-bar">
				<view class="seg-item" :class="{ 'is-on': mode === 'edit' }" @click="fnSetMode('edit')">编辑</view>
				<view class="seg-item" :class="{ 'is-on': mode === 'preview' }" @click="fnSetMode('preview')">预览</view>
				<text class="seg-zen" @click="fnToggleZen">极简写作</text>
			</view>

			<block v-if="mode === 'edit'">
				<view class="publish-card">
					<input
						class="publish-title"
						v-model="form.title"
						:maxlength="maxTitleLength"
						placeholder="标题"
						:disabled="submitting"
					/>
					<view class="title-meta">
						<text class="title-len" :class="{ 'is-over': titleAtLimit }">{{ titleLength }}/{{ maxTitleLength }}</text>
					</view>
					<textarea
						class="publish-input"
						v-model="form.content"
						:maxlength="maxContentLength"
						placeholder="正文（支持 Markdown，例如 ## 小标题、**加粗**、- 列表）"
						:disabled="submitting"
						:auto-height="true"
					></textarea>
					<view class="count-row">
						<text class="count-item">{{ contentLength }}/{{ maxContentLength }}</text>
						<text class="count-item">约 {{ readMinutes }} 分钟阅读</text>
						<text class="count-item">{{ paragraphCount }} 段</text>
					</view>
				</view>

				<!-- 极简写作只留正文：以下都是「元信息」类卡片，zen 模式下整块收起 -->
				<block v-if="!zenMode">
					<view class="tool-card">
					<view class="card-head">
						<text class="card-title">Markdown 工具</text>
						<text class="card-sub">插入到正文末尾</text>
					</view>
					<scroll-view class="tool-scroll" scroll-x>
						<view class="tool-list">
							<view v-for="tool in toolbar" :key="tool.key" class="tool-chip"
								@click="fnTool(tool)">{{ tool.label }}</view>
						</view>
					</scroll-view>
					<view class="tool-foot">
						<text class="tool-act" :class="{ 'is-off': !canUndo }" @click="fnUndo">撤销</text>
						<text class="tool-act" @click="fnTidy">规范排版</text>
						<text class="tool-act" @click="fnInsertImage">插入图片</text>
						<text class="tool-act tool-act--danger" @click="fnClear">清空</text>
					</view>
				</view>

				<view class="tpl-card">
					<view class="card-head">
						<text class="card-title">快速模板</text>
						<text class="card-sub">填好的标题与骨架，照着写就行</text>
					</view>
					<view class="tpl-list">
						<view v-for="tpl in templates" :key="tpl.key" class="tpl-chip"
							@click="fnApplyTemplate(tpl)">{{ tpl.name }}</view>
					</view>
				</view>

				<view class="tax-card">
					<view class="card-head">
						<text class="card-title">分类</text>
						<text class="card-sub">最多 {{ maxCategories }} 个 · 已订阅的排前面</text>
					</view>
					<view v-if="categoryChips.length" class="chip-wrap">
						<view v-for="chip in categoryChips" :key="chip.name" class="pick-chip"
							:class="{ 'is-on': chip.selected, 'is-sub': chip.subscribed && !chip.selected }"
							@click="fnToggleCategory(chip)">
							<text class="pick-chip-text">{{ chip.label }}</text>
							<text v-if="chip.subscribed" class="pick-chip-tag">已订</text>
						</view>
					</view>
					<text v-else-if="categoryLoading" class="pick-hint">分类加载中…</text>
					<text v-else class="pick-hint">站点还没有分类，可先去 Halo 后台建一个</text>
					<view v-if="calcUnsubscribedSelected.length" class="sub-warn">
						<text class="sub-warn-text">所选分类不在你的订阅里，发布后首页「最新列表」看不到这篇</text>
						<text class="sub-warn-act" @click="fnSubscribeSelected">
							{{ subscribing ? '处理中…' : '加入订阅' }}
						</text>
					</view>
					<text class="pick-foot">分类从站点已有分类里选；写错名字不会新建分类</text>
				</view>

				<view class="tax-card">
					<view class="card-head">
						<text class="card-title">标签</text>
						<text class="card-sub">最多 {{ maxTags }} 个 · 从已有标签里选</text>
					</view>
					<view v-if="selectedTagItems.length" class="chip-wrap">
						<view v-for="tag in selectedTagItems" :key="tag.name" class="pick-chip is-on"
							@click="fnToggleTag(tag)">
							<text class="pick-chip-text">{{ tag.label }}</text>
							<text class="pick-chip-tag">×</text>
						</view>
					</view>
					<input class="tag-search" v-model="tagKeyword" placeholder="搜索标签（如 travel、亲子）" />
					<view v-if="calcTagMatches.length" class="chip-wrap">
						<view v-for="tag in calcTagMatches" :key="tag.name" class="pick-chip"
							@click="fnToggleTag(tag)">
							<text class="pick-chip-text">{{ tag.label }}</text>
						</view>
					</view>
					<text v-else-if="tagLoading" class="pick-hint">标签加载中…</text>
					<text v-else class="pick-hint">{{ tagKeyword ? '没有匹配的标签' : '站点还没有标签' }}</text>
					<text class="pick-foot">标签只匹配已有，不会因为写错而新建</text>
				</view>

				<view class="cover-card">
					<view class="card-head">
						<text class="card-title">封面图</text>
						<text class="card-sub">可选，进列表页有图更醒目</text>
					</view>
					<view class="cover-body">
						<view v-if="calcCoverUrl" class="cover-preview-wrap">
							<image class="cover-preview" :src="calcCoverUrl" mode="aspectFill" @click="fnPreviewCover"></image>
							<view v-if="!submitting" class="cover-remove" @click.stop="fnRemoveCover">
								<tm-icons name="icon-times" :size="20" color="#fff"></tm-icons>
							</view>
						</view>
						<view v-else class="cover-add" @click="fnChooseCover">
							<tm-icons name="icon-plus" :size="40" color="#c6c6c0"></tm-icons>
							<text class="cover-add-text">选择封面</text>
						</view>
					</view>
					<text class="cover-tip">封面会在提交时随文章一起上传，不会留下无用图片</text>
				</view>

				<!-- 只在编辑时给：Halo 的 allowComment 是文章级设置，新投稿一律按站点默认（允许评论） -->
				<view v-if="isEditing" class="comment-card">
					<view class="card-head">
						<text class="card-title">允许评论</text>
						<text class="card-sub">关掉后读者只能看，不能留言</text>
					</view>
					<view class="comment-body">
						<text class="comment-label">{{ allowComment ? '读者可以评论' : '已关闭评论' }}</text>
						<tm-switch v-model="allowComment" color="indigo" :text="['允许', '关闭']"></tm-switch>
					</view>
				</view>

				<view v-if="!isEditing" class="history-card">
					<view class="card-head">
						<text class="card-title">我的投稿</text>
						<text class="card-sub" @click="fnToMyArticles">查看全部 ›</text>
					</view>
					<block v-if="history.length">
						<view v-for="(item, index) in history" :key="index" class="history-item"
							@click="fnOpenHistory(item)">
							<view class="history-main">
								<text class="history-title">{{ item.title }}</text>
								<text class="history-meta">{{ item.timeText }} · {{ item.statusText }}</text>
							</view>
							<text class="history-arrow" :class="{ 'is-off': !item.canOpen }">{{ item.canOpen ? '查看' : '待审' }}</text>
						</view>
						<view class="history-foot">
							<text class="history-tip">本机最近 {{ history.length }} 条，仅作离线速览</text>
							<text class="history-clear" @click="fnClearHistory">清空</text>
						</view>
					</block>
					<text v-else class="history-hint">投过的文章（含待审）都在「查看全部」里，可在那边删除</text>
				</view>
				</block>
			</block>

			<view v-else class="preview-card">
				<text class="preview-title">{{ form.title || '（未填写标题）' }}</text>
				<image v-if="calcCoverUrl" class="preview-cover" :src="calcCoverUrl" mode="widthFix"
					@click="fnPreviewCover"></image>
				<mp-html
					v-if="contentLength > 0"
					class="evan-markdown"
					:domain="markdownConfig.domain"
					:loading-img="markdownConfig.loadingGif"
					:scroll-table="true"
					:selectable="true"
					:tag-style="markdownConfig.tagStyle"
					:container-style="markdownConfig.containStyle"
					:content="form.content"
					:markdown="true"
					:showLineNumber="true"
					:showLanguageName="true"
					:copyByLongPress="true"
				/>
				<view v-else class="preview-empty">
					<text class="preview-empty-text">正文还是空的，切回「编辑」写点内容</text>
				</view>
				<!-- 预览里带上分类/标签：作者能一眼确认这篇会被挂到哪儿 -->
				<view v-if="selectedCategories.length || selectedTags.length" class="preview-meta">
					<view v-for="chip in selectedCategoryChips" :key="chip.name"
						class="preview-tag preview-tag--cat">
						<text>{{ chip.label }}</text>
					</view>
					<view v-for="tag in selectedTagItems" :key="tag.name" class="preview-tag">
						<text>{{ tag.label }}</text>
					</view>
				</view>
				<text v-if="authorName" class="preview-foot">本文作者：{{ authorName }}</text>
			</view>

			<view v-if="!zenMode" class="check-card">
				<view class="card-head">
					<text class="card-title">发布前检查</text>
					<text class="card-sub">{{ passCount }}/{{ calcChecks.length }} 项通过</text>
				</view>
				<view v-for="(check, index) in calcChecks" :key="index" class="check-row">
					<text class="check-mark" :class="'is-' + check.state">{{ check.mark }}</text>
					<text class="check-label">{{ check.label }}</text>
					<text class="check-value" :class="'is-' + check.state">{{ check.value }}</text>
				</view>
			</view>

			<!-- 直发与待审对用户的预期完全不同，提交前必须说清楚 -->
			<view class="mode-tip" :class="{ 'mode-tip--draft': isDraftMode }">
				<text class="mode-tip-text">{{ modeTipText }}</text>
			</view>

			<view class="publish-actions">
				<tm-button
					block
					theme="bg-gradient-indigo-accent"
					:shadow="4"
					:loading="submitting"
					:disabled="!canSubmit"
					@click="fnSubmit"
				>{{ submitText }}</tm-button>
				<text v-if="blockHint" class="block-hint">{{ blockHint }}</text>
				<view class="sub-actions">
					<text v-if="!isEditing" class="sub-act" @click="fnSaveDraft(true)">存草稿</text>
					<text class="sub-act" @click="fnToggleZen">{{ zenMode ? '退出极简' : '极简写作' }}</text>
					<text class="sub-act" @click="fnBack">返回</text>
				</view>
				<text v-if="draftSavedAt && !isEditing" class="autosave-tip">草稿已自动保存于 {{ draftSavedAt }}</text>
			</view>
		</view>
	</view>
</template>

<script>
	import tmEmpty from '@/tm-vuetify/components/tm-empty/tm-empty.vue';
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
	import tmLoadding from '@/tm-vuetify/components/tm-loadding/tm-loadding.vue';
	import tmIcons from '@/tm-vuetify/components/tm-icons/tm-icons.vue';
	import tmSwitch from '@/tm-vuetify/components/tm-switch/tm-switch.vue';
	import mpHtml from '@/components/mp-html/components/mp-html/mp-html.vue';
	import MarkdownConfig from '@/common/markdown/markdown.config.js';
	import { checkHasWxLogin, getWxLoginInfo } from '@/utils/auth.js';
	import { emitPointsUpdated } from '@/utils/points.js';
	import { formatTimeSafe } from '@/utils/time.js';
	import { fetchCategorySubscription, subscribeCategory } from '@/utils/categorySubscription.js';
	import {
		buildThresholdHint,
		fetchUserArticleDetail,
		fetchUserPublishStatus,
		saveUserArticle,
		submitUserArticle,
		uploadUserPublishImage
	} from '@/utils/userPublish.js';

	/** 草稿与投稿记录都只存本机：服务端没有「草稿箱/我的投稿」接口，不假装有 */
	const DRAFT_KEY = 'unihalo_article_draft';
	const HISTORY_KEY = 'unihalo_article_history';
	const HISTORY_LIMIT = 10;
	const UNDO_LIMIT = 30;
	const AUTOSAVE_DELAY = 1500;
	/** 中文阅读速度取 350 字/分钟，只用于「约 N 分钟」的粗略估计 */
	const READ_WORDS_PER_MINUTE = 350;
	const MAX_INLINE_IMAGES = 3;
	/** 分类/标签一次能挂几个（服务端同口径再挡一道） */
	const MAX_CATEGORIES = 3;
	const MAX_TAGS = 5;
	/** 标签有 160+ 个，不搜索时最多铺这么多，避免刷屏 */
	const TAG_MATCH_LIMIT = 12;
	/** 分类/标签一次拉多少：站点量级（十几个分类 / 一两百标签）够用 */
	const TAXONOMY_FETCH_SIZE = 200;

	/**
	 * Markdown 工具条。
	 *
	 * <p>按钮一律「追加到正文末尾」而不是插到光标处：小程序的 textarea 不提供
	 * selectionStart/光标位置，客户端拿不到插入点（App/H5 才有）。与其做个
	 * 位置随机的能力，不如把规则说清楚——所以工具条旁边写了「插入到正文末尾」。</p>
	 */
	const TOOLBAR = [
		{ key: 'h2', label: 'H2', snippet: '## 小标题' },
		{ key: 'h3', label: 'H3', snippet: '### 小标题' },
		{ key: 'bold', label: '加粗', snippet: '**加粗文字**' },
		{ key: 'italic', label: '斜体', snippet: '*斜体文字*' },
		{ key: 'code', label: '行内代码', snippet: '`代码`' },
		{ key: 'quote', label: '引用', snippet: '> 引用内容' },
		{ key: 'ul', label: '无序列表', snippet: '- 列表项' },
		{ key: 'ol', label: '有序列表', snippet: '1. 列表项' },
		{ key: 'codeblock', label: '代码块', snippet: '```\n代码\n```' },
		{ key: 'table', label: '表格', snippet: '| 列 1 | 列 2 |\n| --- | --- |\n| 内容 | 内容 |' },
		{ key: 'link', label: '链接', snippet: '[链接文字](https://)' },
		{ key: 'hr', label: '分割线', snippet: '---' },
		{ key: 'image', label: '图片上传', snippet: '' }
	];

	/** 常用骨架：把「怎么起头」这个最费劲的环节省掉 */
	const TEMPLATES = [
		{
			key: 'tech',
			name: '技术分享',
			title: '',
			body: '## 背景\n\n\n\n## 实现要点\n\n- \n- \n\n## 踩到的坑\n\n\n\n## 小结\n\n'
		},
		{
			key: 'bug',
			name: '踩坑记录',
			title: '',
			body: '## 问题现象\n\n\n\n## 排查过程\n\n\n\n## 根因\n\n\n\n## 解决办法\n\n'
		},
		{
			key: 'weekly',
			name: '周报总结',
			title: '',
			body: '## 本周完成\n\n- \n\n## 进行中\n\n- \n\n## 下周计划\n\n- \n'
		},
		{
			key: 'review',
			name: '工具盘点',
			title: '',
			body: '## 为什么推荐\n\n\n\n## 核心能力\n\n- \n- \n\n## 适用场景\n\n\n\n## 上手方式\n\n'
		}
	];

	export default {
		components: {
			tmEmpty,
			tmButton,
			tmLoadding,
			tmIcons,
			tmSwitch,
			mpHtml
		},
		data() {
			return {
				statusChecked: false,
				canPublish: false,
				disabledReason: '暂不能投稿',
				status: null,
				submitting: false,
				toolbar: TOOLBAR,
				templates: TEMPLATES,
				markdownConfig: MarkdownConfig,
				/** 作者署名（服务端结论优先，登录资料兜底） */
				authorName: '',
				/** 编辑 / 预览 */
				mode: 'edit',
				/** 极简写作（隐藏状态卡、工具条、模板、封面、检查清单） */
				zenMode: false,
				form: {
					title: '',
					content: '',
					/** 提交时才上传：封面还没提交就先传会产生孤儿附件 */
					coverUrl: ''
				},
				coverLocalPath: '',
				coverUploading: false,
				/** 撤销栈：工具条/模板/排版都基于「文本整体替换」，没有它就只能手动改回去 */
				undoStack: [],
				draftSavedAt: '',
				history: [],
				/** 站点已有分类（含订阅态），点选挂到文章上，最多 3 个 */
				categoryOptions: [],
				selectedCategories: [],
				categoryLoading: false,
				maxCategories: MAX_CATEGORIES,
				/** 站点已有标签 + 关键词搜索（160+ 个，靠搜索收敛） */
				tagOptions: [],
				selectedTags: [],
				tagKeyword: '',
				tagLoading: false,
				maxTags: MAX_TAGS,
				/** 已订阅的分类（首页「最新列表」只显示订阅分类的文章，这里用来提示） */
				subscribedCategories: [],
				subscriptionLimits: null,
				subscribing: false,
				/** 提交成功后的结果面板；null 表示还在编辑态 */
				resultPanel: null,
				/**
				 * 编辑模式：非空表示正在修改这篇文章（服务端的 metadata.name）。
				 *
				 * <p>复用同一个页面而不是另做一个编辑页：正文编辑、Markdown 工具、预览、
				 * 分类标签选择这些能力一模一样，分叉两份必然会有一边慢慢落后。</p>
				 */
				editingName: '',
				/** 编辑对象的状态（已发布 / 有未生效的修改 / 链接），只用于文案与按钮 */
				editingArticle: null,
				/** 正在拉编辑对象的详情：占位期间不渲染表单，避免用户对着空表单开写 */
				editingLoading: false,
				/** 是否允许评论（Halo 原生字段）：只在编辑已有文章时可改 */
				allowComment: true
			};
		},
		computed: {
			maxTitleLength() {
				return this.status?.maxArticleTitleLength || 60;
			},
			maxContentLength() {
				return this.status?.maxArticleContentLength || 5000;
			},
			isDraftMode() {
				return this.status?.articleMode !== 'direct';
			},
			isPrivileged() {
				return this.status?.privileged === true;
			},
			/** 是否在编辑已有文章（编辑不再扣积分、不占每日额度） */
			isEditing() {
				return !!this.editingName;
			},
			/** 能否自行发布/撤回：直发策略或博主/管理员，决定「保存后改动是否立刻生效」 */
			canDirectPublish() {
				if (!this.status) {
					return this.isPrivileged;
				}
				return this.isPrivileged || this.status.articleMode === 'direct';
			},
			titleLength() {
				return this.form.title.length;
			},
			contentLength() {
				return this.form.content.length;
			},
			titleAtLimit() {
				return this.titleLength >= this.maxTitleLength;
			},
			readMinutes() {
				return Math.max(1, Math.round(this.contentLength / READ_WORDS_PER_MINUTE));
			},
			paragraphCount() {
				const blocks = this.form.content.split(/\n\s*\n/).filter((item) => item.trim());
				return blocks.length;
			},
			calcCoverUrl() {
				return this.coverLocalPath || this.form.coverUrl;
			},
			/** 分类筹码：已订阅的排在最前（首页只显示订阅分类的文章，先给能看到的） */
			categoryChips() {
				const subscribed = this.subscribedCategories;
				const chips = this.categoryOptions
					.map((item) => ({
						name: item.name,
						label: item.label,
						subscribed: subscribed.includes(item.name),
						selected: this.selectedCategories.includes(item.name)
					}));
				// 编辑旧文章时可能选中了已被站长删掉的分类：补一个用原始名字的筹码。
				// 不补的话它会「看不见但仍在提交列表里」，保存时被服务端静默忽略，用户无从察觉
				this.selectedCategories.forEach((name) => {
					if (!chips.some((chip) => chip.name === name)) {
						chips.push({ name, label: name, subscribed: false, selected: true });
					}
				});
				return chips.sort((a, b) => Number(b.subscribed) - Number(a.subscribed));
			},
			selectedTagItems() {
				return this.selectedTags.map((name) => ({
					name,
					label: this.fnTagLabel(name)
				}));
			},
			/** 预览里只显示已选中的分类（避免 v-for 与 v-if 同节点，小程序端更稳） */
			selectedCategoryChips() {
				return this.categoryChips.filter((chip) => chip.selected);
			},
			/** 标签候选：关键词过滤（名称/slug/显示名），已选的不重复出现 */
			calcTagMatches() {
				const keyword = this.tagKeyword.trim().toLowerCase();
				const matched = this.tagOptions.filter((item) => {
					if (this.selectedTags.includes(item.name)) {
						return false;
					}
					if (!keyword) {
						return true;
					}
					return item.label.toLowerCase().includes(keyword)
						|| item.name.toLowerCase().includes(keyword);
				});
				return matched.slice(0, TAG_MATCH_LIMIT);
			},
			/**
			 * 所选分类里「没订阅」的那些。
			 *
			 * <p>首页「最新列表」按订阅分类筛选，选了没订阅的分类，作者自己都看不到这篇——
			 * 这里把差异摆出来，并提供一键加入订阅。</p>
			 */
			calcUnsubscribedSelected() {
				if (!this.subscribedCategories.length) {
					return [];
				}
				return this.selectedCategories.filter(
					(name) => !this.subscribedCategories.includes(name));
			},
			modeLabel() {
				if (this.isEditing) {
					return this.editingArticle?.published ? '修改已发布文章' : '修改待审文章';
				}
				if (this.isDraftMode) {
					return '待审投稿';
				}
				return this.isPrivileged ? '直接发布 · 免审' : '直接发布';
			},
			modeTipText() {
				if (this.isEditing) {
					if (!this.editingArticle?.published) {
						return '修改只落在草稿上：站长通过审核后按最新内容发布';
					}
					// 已发布的文章改完是否立刻生效，取决于本人有没有直发权限——必须说清楚
					return this.canDirectPublish
						? '保存后会自动重新发布，读者立刻看到新内容'
						: '保存后需要站长重新发布，站点上暂时仍是旧内容';
				}
				if (this.isDraftMode) {
					return '本次投稿将进入待审，站长审核通过后才会公开';
				}
				if (this.isPrivileged) {
					return '你是站点所有者/管理员：直接发布，不消耗积分，不限每日篇数';
				}
				return '本次投稿将直接发布到站点';
			},
			quotaText() {
				if (this.isEditing) {
					return '修改不占每日额度';
				}
				if (!this.status) {
					return '';
				}
				if (this.isPrivileged || !this.status.dailyArticleLimit) {
					return '今日不限篇数';
				}
				return `今日已投 ${this.status.todayArticleCount || 0}/${this.status.dailyArticleLimit}`;
			},
			costText() {
				if (this.isEditing) {
					return '修改不消耗积分';
				}
				if (!this.status) {
					return '';
				}
				if (this.isPrivileged) {
					return '投稿免积分';
				}
				return `消耗 ${this.status.articleCost} 积分 · 余 ${this.status.balance}`;
			},
			thresholdHint() {
				return buildThresholdHint(this.status);
			},
			securityHint() {
				return this.status?.contentSecurityEnabled
					? '发布前会经过微信内容安全检测，请注意用词'
					: '';
			},
			quotaLeft() {
				if (!this.status || !this.status.dailyArticleLimit) {
					return -1;
				}
				return Math.max(0, this.status.dailyArticleLimit - (this.status.todayArticleCount || 0));
			},
			/**
			 * 发布前检查清单：把服务端会拦的条件提前摆出来。
			 * 只做展示，真正的判定仍在服务端（这里放过的，服务端仍可能拒绝）。
			 */
			calcChecks() {
				const rows = [];
				const title = this.form.title.trim();
				const content = this.form.content.trim();

				if (!title) {
					rows.push({ state: 'block', mark: '✗', label: '标题', value: '还没填' });
				} else if (this.titleLength > this.maxTitleLength) {
					rows.push({
						state: 'block', mark: '✗', label: '标题',
						value: `超出 ${this.titleLength - this.maxTitleLength} 字`
					});
				} else {
					rows.push({ state: 'ok', mark: '✓', label: '标题', value: `${this.titleLength}/${this.maxTitleLength} 字` });
				}

				if (!content) {
					rows.push({ state: 'block', mark: '✗', label: '正文', value: '还没写' });
				} else if (this.contentLength > this.maxContentLength) {
					rows.push({
						state: 'block', mark: '✗', label: '正文',
						value: `超出 ${this.contentLength - this.maxContentLength} 字`
					});
				} else {
					rows.push({
						state: 'ok', mark: '✓', label: '正文',
						value: `${this.contentLength} 字 · 约 ${this.readMinutes} 分钟`
					});
				}

				rows.push(this.calcCoverUrl
					? { state: 'ok', mark: '✓', label: '封面', value: '已选（提交时上传）' }
					: { state: 'warn', mark: '○', label: '封面', value: '未选，可不填' });

				// 分类：不选也能发，但首页与分类页都难找到它——提示而不拦
				const categoryCount = this.selectedCategories.length;
				rows.push(categoryCount
					? { state: 'ok', mark: '✓', label: '分类', value: `已选 ${categoryCount} 个` }
					: { state: 'warn', mark: '○', label: '分类', value: '未选，首页/分类页不好找' });

				// 选了没订阅的分类：作者自己的首页「最新列表」看不到这篇
				if (this.calcUnsubscribedSelected.length) {
					rows.push({
						state: 'warn', mark: '○', label: '订阅',
						value: `${this.calcUnsubscribedSelected.length} 个所选分类未订阅，首页看不到`
					});
				}

				rows.push(this.selectedTags.length
					? { state: 'ok', mark: '✓', label: '标签', value: `已选 ${this.selectedTags.length} 个` }
					: { state: 'warn', mark: '○', label: '标签', value: '未选，可不填' });

				// 额度与积分只管「新投稿」：编辑已有文章不扣分也不占额度，摆出来只会让人以为要再交一次
				if (this.status && !this.isEditing) {
					if (this.isPrivileged) {
						rows.push({ state: 'ok', mark: '✓', label: '发布额度', value: '站长/管理员不限' });
					} else if (this.quotaLeft === 0) {
						rows.push({
							state: 'block', mark: '✗', label: '今日额度',
							value: `${this.status.todayArticleCount}/${this.status.dailyArticleLimit} 已用完`
						});
					} else {
						rows.push({
							state: 'ok', mark: '✓', label: '今日额度',
							value: this.quotaLeft < 0 ? '不限' : `还剩 ${this.quotaLeft} 篇`
						});
					}

					if (this.isPrivileged || !this.status.articleCost) {
						rows.push({ state: 'ok', mark: '✓', label: '积分', value: '本次不消耗' });
					} else if (this.status.balance < this.status.articleCost) {
						rows.push({
							state: 'block', mark: '✗', label: '积分',
							value: `还差 ${this.status.articleCost - this.status.balance}（现有 ${this.status.balance}）`
						});
					} else {
						rows.push({
							state: 'ok', mark: '✓', label: '积分',
							value: `消耗 ${this.status.articleCost}，余 ${this.status.balance}`
						});
					}
				}
				return rows;
			},
			calcBlockers() {
				return this.calcChecks.filter((item) => item.state === 'block');
			},
			passCount() {
				return this.calcChecks.filter((item) => item.state === 'ok').length;
			},
			blockHint() {
				const first = this.calcBlockers[0];
				return first ? `请先处理：${first.label} ${first.value}` : '';
			},
			canUndo() {
				return this.undoStack.length > 0;
			},
			canSubmit() {
				return !this.submitting && this.calcBlockers.length === 0;
			},
			submitText() {
				if (this.isEditing) {
					return '保存修改';
				}
				if (!this.status) {
					return '提交投稿';
				}
				const costText = this.isPrivileged ? '免积分' : `${this.status.articleCost} 积分`;
				return this.isDraftMode ? `提交审核（${costText}）` : `发布（${costText}）`;
			}
		},
		watch: {
			'form.title'() {
				this.fnScheduleAutosave();
			},
			'form.content'() {
				this.fnScheduleAutosave();
			}
		},
		async onLoad(options) {
			const editingName = options && options.name ? decodeURIComponent(options.name) : '';
			await this.fnLoadStatus();
			// 编辑模式先于「能不能投稿」的判定：改自己已投过的稿，不该被当前的投稿策略挡住
			if (editingName) {
				this.editingName = editingName;
				await this.fnLoadArticleForEdit(editingName);
				return;
			}
			this.fnLoadHistory();
			// 没有投稿权限（未登录/被站长关闭）时不弹「恢复草稿」，避免登出后被草稿打断
			if (this.canPublish) {
				this.fnLoadDraft();
				this.fnLoadTaxonomy();
			}
		},
		onHide() {
			this.fnFlushAutosave();
		},
		onUnload() {
			this.fnFlushAutosave();
			clearTimeout(this._autosaveTimer);
		},
		methods: {
			async fnLoadStatus() {
				if (!checkHasWxLogin()) {
					this.statusChecked = true;
					this.canPublish = false;
					this.disabledReason = '请先登录后再投稿';
					return;
				}
				const status = await fetchUserPublishStatus(this.$httpApi.v2);
				this.statusChecked = true;
				this.status = status;
				// 能不能发以服务端结论为准（服务端会区分角色、门槛、余额、每日上限）
				this.canPublish = !!(status && status.canPublishArticle);
				this.disabledReason = (status && status.articleDisabledReason)
					|| '站长未开启投稿，或当前账号暂无投稿权限';
				const loginInfo = getWxLoginInfo() || {};
				this.authorName = (status && status.haloUserName) || loginInfo.haloUserName || '';
			},

			/**
			 * 编辑模式：把已投稿的文章读回来回填表单。
			 *
			 * <p>正文取的是 head 快照（作者最后一次保存的那份内容）。读失败必须整页退出而不是
			 * 给一张空表：保存是「整份替换」，空表一提交就把文章正文清掉了。</p>
			 */
			async fnLoadArticleForEdit(name) {
				this.editingLoading = true;
				uni.setNavigationBarTitle({ title: '编辑投稿' });
				const result = await fetchUserArticleDetail(this.$httpApi.v2, name);
				this.editingLoading = false;
				if (!result.ok) {
					uni.showModal({
						title: '打开失败',
						content: result.reason || '读取文章失败，请稍后重试',
						showCancel: false,
						confirmText: '返回',
						success: () => this.fnBack()
					});
					return;
				}
				const data = result.data || {};
				this.editingArticle = {
					published: data.published === true,
					hasPendingChanges: data.hasPendingChanges === true,
					permalink: data.permalink || '',
					stateText: data.stateText || ''
				};
				this.form.title = data.title || '';
				this.form.content = data.content || '';
				this.form.coverUrl = data.cover || '';
				this.allowComment = data.allowComment !== false;
				this.selectedCategories = Array.isArray(data.categories)
					? data.categories.slice(0, MAX_CATEGORIES) : [];
				this.selectedTags = Array.isArray(data.tags) ? data.tags.slice(0, MAX_TAGS) : [];
				// 分类/标签选项要等这里加载完，选中态才能对上显示名
				await this.fnLoadTaxonomy();
				this.authorName = this.authorName || (this.status && this.status.haloUserName) || '';
				this.mode = 'edit';
			},

			fnBack() {
				uni.navigateBack({
					fail: () => {
						uni.switchTab({ url: '/pages/tabbar/home/home' });
					}
				});
			},
			/** 保存修改后回「我的投稿」：那边 onShow 会重拉列表，状态与数据跟着刷新 */
			fnBackToList() {
				uni.navigateBack({
					fail: () => {
						uni.redirectTo({ url: '/pagesA/my-articles/my-articles' });
					}
				});
			},
			fnSetMode(mode) {
				this.mode = mode === 'preview' ? 'preview' : 'edit';
			},
			fnToggleZen() {
				this.zenMode = !this.zenMode;
				if (this.zenMode) {
					this.mode = 'edit';
				}
			},
			// ==================== 撤销与插入 ====================

			fnPushSnapshot() {
				const snapshot = {
					title: this.form.title,
					content: this.form.content,
					coverUrl: this.form.coverUrl,
					coverLocalPath: this.coverLocalPath,
					categories: this.selectedCategories.slice(),
					tags: this.selectedTags.slice()
				};
				const stack = this.undoStack.concat([snapshot]);
				this.undoStack = stack.length > UNDO_LIMIT ? stack.slice(stack.length - UNDO_LIMIT) : stack;
			},
			fnUndo() {
				if (!this.undoStack.length || this.submitting) {
					return;
				}
				const stack = this.undoStack.slice();
				const last = stack.pop();
				this.undoStack = stack;
				this.form.title = last.title;
				this.form.content = last.content;
				this.form.coverUrl = last.coverUrl;
				this.coverLocalPath = last.coverLocalPath;
				if (Array.isArray(last.categories)) {
					this.selectedCategories = last.categories.slice();
				}
				if (Array.isArray(last.tags)) {
					this.selectedTags = last.tags.slice();
				}
				uni.showToast({ icon: 'none', title: '已撤销上一步' });
			},
			/** 追加一段 Markdown 到正文末尾（工具条、模板片段、插图都走这里） */
			fnAppendText(snippet) {
				const content = this.form.content;
				const base = content.trim()
					? content.replace(/[ \t]+$/gm, '').replace(/\n+$/, '') + '\n\n'
					: '';
				this.form.content = (base + snippet).slice(0, this.maxContentLength);
			},
			fnTool(tool) {
				if (this.submitting) {
					return;
				}
				if (tool.key === 'image') {
					this.fnInsertImage();
					return;
				}
				this.fnPushSnapshot();
				this.fnAppendText(tool.snippet);
				uni.showToast({ icon: 'none', title: `${tool.label}已插入正文末尾` });
			},
			fnApplyTemplate(tpl) {
				if (this.submitting) {
					return;
				}
				const apply = () => {
					this.fnPushSnapshot();
					if (!this.form.title.trim() && tpl.title) {
						this.form.title = tpl.title;
					}
					this.form.content = tpl.body;
					this.mode = 'edit';
					uni.showToast({ icon: 'none', title: `已套用「${tpl.name}」` });
				};
				if (!this.form.content.trim()) {
					apply();
					return;
				}
				uni.showModal({
					title: '套用模板',
					content: '正文已有内容，套用会替换掉当前正文（可用「撤销」恢复）。继续吗？',
					confirmText: '替换',
					confirmColor: '#2F54EB',
					success: (res) => {
						if (res.confirm) {
							apply();
						}
					}
				});
			},
			/** 规范排版：行尾空格、连续空行、列表符号统一 —— 复制来的内容常见这三种脏格式 */
			fnTidy() {
				if (this.submitting) {
					return;
				}
				const raw = this.form.content;
				if (!raw.trim()) {
					uni.showToast({ icon: 'none', title: '正文还是空的' });
					return;
				}
				let next = raw
					.replace(/[ \t]+$/gm, '')
					.replace(/\n{3,}/g, '\n\n')
					// 列表符号与符号后的空格统一（保留行首缩进：嵌套列表靠它）
					.replace(/^([ \t]*)[-*+][ \t]+/gm, '$1- ');
				if (next === raw) {
					uni.showToast({ icon: 'none', title: '排版已经很规整了' });
					return;
				}
				this.fnPushSnapshot();
				this.form.content = next;
				uni.showToast({ icon: 'none', title: '已规范排版（可撤销）' });
			},
			fnClear() {
				if (this.submitting) {
					return;
				}
				if (!this.form.title && !this.form.content && !this.form.coverUrl) {
					uni.showToast({ icon: 'none', title: '当前没有内容' });
					return;
				}
				uni.showModal({
					title: '清空内容',
					content: '标题、正文与封面都会被清空（可用「撤销」恢复）。确定吗？',
					confirmText: '清空',
					confirmColor: '#d9483b',
					success: (res) => {
						if (!res.confirm) {
							return;
						}
						this.fnPushSnapshot();
						this.form.title = '';
						this.form.content = '';
						this.form.coverUrl = '';
						this.coverLocalPath = '';
					}
				});
			},
			// ==================== 图片 ====================

			fnChooseCover() {
				if (this.submitting || this.coverUploading) {
					return;
				}
				uni.chooseImage({
					count: 1,
					sizeType: ['compressed'],
					sourceType: ['album', 'camera'],
					success: (res) => {
						const path = (res.tempFilePaths || [])[0];
						if (!path) {
							return;
						}
						this.fnPushSnapshot();
						this.coverLocalPath = path;
						this.form.coverUrl = '';
					}
				});
			},
			fnRemoveCover() {
				if (this.submitting) {
					return;
				}
				this.fnPushSnapshot();
				this.coverLocalPath = '';
				this.form.coverUrl = '';
			},
			fnPreviewCover() {
				if (!this.calcCoverUrl) {
					return;
				}
				uni.previewImage({ current: 0, urls: [this.calcCoverUrl] });
			},
			/**
			 * 正文插图：选图后立即上传，把远端地址写成 `![]()` 插进正文。
			 *
			 * <p>正文里的图片必须当场拿到 URL（Markdown 里存的是远端地址），
			 * 所以这里不能像封面那样推迟到提交时上传。</p>
			 */
			fnInsertImage() {
				if (this.submitting) {
					return;
				}
				const remain = this.maxContentLength - this.contentLength;
				if (remain < 40) {
					uni.showToast({ icon: 'none', title: '正文快满了，插图放不下了' });
					return;
				}
				uni.chooseImage({
					count: MAX_INLINE_IMAGES,
					sizeType: ['compressed'],
					sourceType: ['album', 'camera'],
					success: async (res) => {
						const paths = res.tempFilePaths || [];
						if (!paths.length) {
							return;
						}
						this.fnPushSnapshot();
						let ok = 0;
						for (let i = 0; i < paths.length; i++) {
							uni.showLoading({ mask: true, title: `上传图片 ${i + 1}/${paths.length}` });
							try {
								const uploaded = await uploadUserPublishImage(this.$httpApi.v2, paths[i]);
								if (uploaded.ok && uploaded.url) {
									this.fnAppendText(`![图片](${uploaded.url})`);
									ok++;
								}
							} catch (err) {
								// 单张失败不影响其它张：循环结束后统一报数量
							}
						}
						uni.hideLoading();
						if (ok === paths.length) {
							uni.showToast({ icon: 'none', title: `已插入 ${ok} 张图片` });
						} else {
							uni.showToast({ icon: 'none', title: `成功 ${ok} 张，失败 ${paths.length - ok} 张` });
						}
					}
				});
			},
			// ==================== 分类 / 标签 ====================

			/**
			 * 拉站点分类与标签。
			 *
			 * <p>两者都只做「选已有」：服务端按 name / slug / 显示名匹配，匹配不到就忽略，
			 * 不会因为作者笔误造出分类/标签（自动创建只留给 AI 日报那条路径）。</p>
			 */
			async fnLoadTaxonomy() {
				this.fnLoadSubscriptionState();
				this.categoryLoading = true;
				this.tagLoading = true;
				try {
					const res = await this.$httpApi.v2.getCategoryList({
						size: TAXONOMY_FETCH_SIZE,
						page: 1,
						fieldSelector: ['spec.hideFromList=false'],
						sort: ['spec.priority,asc', 'metadata.creationTimestamp,asc']
					});
					const items = this.$utils.stripDataUnsafeList(res?.items || []);
					this.categoryOptions = items.map((item) => {
						const spec = item?.spec || {};
						return {
							name: item?.metadata?.name || '',
							label: spec.displayName || spec.slug || item?.metadata?.name || ''
						};
					}).filter((item) => !!item.name);
				} catch (err) {
					this.categoryOptions = [];
				}
				this.categoryLoading = false;

				try {
					const res = await this.$httpApi.v2.getTagList({
						size: TAXONOMY_FETCH_SIZE,
						page: 1
					});
					const items = this.$utils.stripDataUnsafeList(res?.items || []);
					this.tagOptions = items.map((item) => {
						const spec = item?.spec || {};
						return {
							name: item?.metadata?.name || '',
							label: spec.displayName || spec.slug || item?.metadata?.name || ''
						};
					}).filter((item) => !!item.name);
				} catch (err) {
					this.tagOptions = [];
				}
				this.tagLoading = false;
				this.fnApplyDefaultCategory();
			},

			/** 订阅状态：用来提示「所选分类不在订阅里」并提供一键加入 */
			async fnLoadSubscriptionState() {
				const state = await fetchCategorySubscription(this.$httpApi.v2);
				this.subscribedCategories = state.subscribed || [];
				this.subscriptionLimits = state.limits || null;
			},

			/**
			 * 默认挂上「订阅里的第一个分类」。
			 *
			 * <p>首页最新列表只显示订阅分类的文章：不预选的话，作者发完回首页看不到自己的文章，
			 * 会以为是发布失败。只在作者还没选过任何分类时生效，选了就不动他的选择。</p>
			 */
			fnApplyDefaultCategory() {
				// 编辑时不预选：文章原本没挂分类就别替作者加一个，改动应当只来自作者本人
				if (this.isEditing || this.selectedCategories.length
					|| !this.subscribedCategories.length) {
					return;
				}
				const first = this.subscribedCategories[0];
				if (this.categoryOptions.some((item) => item.name === first)) {
					this.selectedCategories = [first];
				}
			},

			fnToggleCategory(chip) {
				if (this.submitting) {
					return;
				}
				const index = this.selectedCategories.indexOf(chip.name);
				if (index >= 0) {
					this.selectedCategories.splice(index, 1);
					return;
				}
				if (this.selectedCategories.length >= MAX_CATEGORIES) {
					uni.showToast({ icon: 'none', title: `最多选 ${MAX_CATEGORIES} 个分类` });
					return;
				}
				this.selectedCategories.push(chip.name);
			},

			fnTagLabel(name) {
				const found = this.tagOptions.find((item) => item.name === name);
				return found ? found.label : name;
			},

			fnToggleTag(tag) {
				if (this.submitting) {
					return;
				}
				const index = this.selectedTags.indexOf(tag.name);
				if (index >= 0) {
					this.selectedTags.splice(index, 1);
					return;
				}
				if (this.selectedTags.length >= MAX_TAGS) {
					uni.showToast({ icon: 'none', title: `最多选 ${MAX_TAGS} 个标签` });
					return;
				}
				this.selectedTags.push(tag.name);
			},

			/**
			 * 把所选分类加入订阅。
			 *
			 * <p>首页最新列表按订阅分类筛选：不订阅的话，作者刷新首页都看不到刚发的这篇。
			 * 逐个订阅（订阅接口是「读全量 → 改一份 → 整体提交」的口径，工具函数已处理），
			 * 额度满等失败原因原样透出，不吞掉。</p>
			 */
			async fnSubscribeSelected() {
				const targets = this.calcUnsubscribedSelected;
				if (this.subscribing || !targets.length) {
					return;
				}
				this.subscribing = true;
				uni.showLoading({ mask: true, title: '加入订阅...' });
				let ok = 0;
				let reason = '';
				for (const name of targets) {
					try {
						const result = await subscribeCategory(this.$httpApi.v2, name);
						if (result.ok) {
							ok += 1;
						} else if (result.reason === 'not-logged-in') {
							reason = '请先登录后再订阅';
							break;
						} else if (result.reason === 'quota-full') {
							reason = '订阅额度已满，可到分类页解锁额度';
							break;
						} else {
							reason = result.reason || '订阅失败';
							break;
						}
					} catch (err) {
						reason = err?.error || '订阅失败';
						break;
					}
				}
				uni.hideLoading();
				this.subscribing = false;
				await this.fnLoadSubscriptionState();
				if (ok && !reason) {
					uni.showToast({
						icon: 'none',
						title: `已订阅 ${ok} 个分类，首页会显示这些分类的文章`
					});
					return;
				}
				uni.showToast({ icon: 'none', title: reason || `已订阅 ${ok} 个分类` });
			},

			// ==================== 草稿 ====================

			fnScheduleAutosave() {
				clearTimeout(this._autosaveTimer);
				this._autosaveTimer = setTimeout(() => this.fnSaveDraft(false), AUTOSAVE_DELAY);
			},
			fnFlushAutosave() {
				clearTimeout(this._autosaveTimer);
				this.fnSaveDraft(false);
			},
			fnSaveDraft(manual) {
				// 编辑已有文章时不写本机草稿：那是「还没发出去的新稿」的暂存区，
				// 混进一篇已投稿的副本，下次写新稿时会被当成草稿弹出来恢复
				if (this.isEditing) {
					return;
				}
				const title = this.form.title.trim();
				const content = this.form.content.trim();
				if (!title && !content && !this.coverLocalPath) {
					if (manual) {
						uni.showToast({ icon: 'none', title: '还没有内容可存' });
					}
					return;
				}
				const payload = {
					title: this.form.title,
					content: this.form.content,
					categories: this.selectedCategories.slice(),
					tags: this.selectedTags.slice(),
					savedAt: Date.now()
				};
				try {
					uni.setStorageSync(DRAFT_KEY, JSON.stringify(payload));
				} catch (err) {
					if (manual) {
						uni.showToast({ icon: 'none', title: '草稿保存失败' });
					}
					return;
				}
				this.draftSavedAt = formatTimeSafe(payload.savedAt);
				if (manual) {
					uni.showToast({ icon: 'none', title: '草稿已保存（仅本机）' });
				}
			},
			fnLoadDraft() {
				const raw = uni.getStorageSync(DRAFT_KEY);
				if (!raw) {
					return;
				}
				let draft = null;
				try {
					draft = typeof raw === 'string' ? JSON.parse(raw) : raw;
				} catch (err) {
					return;
				}
				if (!draft || (!draft.title && !draft.content)) {
					return;
				}
				uni.showModal({
					title: '有未完成的草稿',
					content: `保存于 ${formatTimeSafe(draft.savedAt)}\n标题：${draft.title || '（未填）'}\n正文 ${String(draft.content || '').length} 字`,
					confirmText: '恢复',
					cancelText: '不用了',
					confirmColor: '#2F54EB',
					success: (res) => {
						if (res.confirm) {
							// 封面不入草稿：临时文件路径跨启动会失效，恢复一个坏图更让人困惑
							this.form.title = draft.title || '';
							this.form.content = draft.content || '';
							this.selectedCategories = Array.isArray(draft.categories)
								? draft.categories.slice(0, MAX_CATEGORIES) : [];
							this.selectedTags = Array.isArray(draft.tags)
								? draft.tags.slice(0, MAX_TAGS) : [];
							this.draftSavedAt = formatTimeSafe(draft.savedAt);
							uni.showToast({ icon: 'none', title: '草稿已恢复' });
						} else {
							this.fnDiscardDraft();
						}
					}
				});
			},
			fnDiscardDraft() {
				try {
					uni.removeStorageSync(DRAFT_KEY);
				} catch (err) {
					// 清不掉也不影响使用，下次进来会再问一次
				}
				this.draftSavedAt = '';
			},
			// ==================== 投稿记录 ====================

			fnLoadHistory() {
				const raw = uni.getStorageSync(HISTORY_KEY);
				if (!raw) {
					return;
				}
				let list = null;
				try {
					list = typeof raw === 'string' ? JSON.parse(raw) : raw;
				} catch (err) {
					return;
				}
				if (!Array.isArray(list)) {
					return;
				}
				this.history = this.fnMapHistory(list.slice(0, HISTORY_LIMIT));
			},
			/** 展示字段（时间文案、能否打开）在读取时现算，避免存进 storage 后过期 */
			fnMapHistory(list) {
				return list.map((item) => ({
					title: item.title || '（无标题）',
					mode: item.mode || 'direct',
					statusText: item.statusText || '',
					name: item.name || '',
					permalink: item.permalink || '',
					at: item.at || 0,
					timeText: formatTimeSafe(item.at),
					canOpen: !!(item.name && item.mode === 'direct')
				}));
			},
			fnPushHistory(record) {
				const next = [record, ...this.history].slice(0, HISTORY_LIMIT);
				try {
					// 只落原始字段：展示字段（timeText/canOpen）下次进来重新算
					uni.setStorageSync(HISTORY_KEY, JSON.stringify(next.map((item) => ({
						title: item.title,
						mode: item.mode,
						statusText: item.statusText,
						name: item.name,
						permalink: item.permalink,
						at: item.at
					}))));
				} catch (err) {
					// 记录失败不影响发布结果
				}
				this.history = this.fnMapHistory(next);
			},
			fnClearHistory() {				uni.showModal({
					title: '清空记录',
					content: '只清空本机的投稿记录，站点上的文章不受影响。',
					confirmText: '清空',
					success: (res) => {
						if (!res.confirm) {
							return;
						}
						try {
							uni.removeStorageSync(HISTORY_KEY);
						} catch (err) {
							// 同上：清不掉下次还会显示，不阻断
						}
						this.history = [];
					}
				});
			},
			/** 全部投稿记录（服务端列表，含待审，可在那边删除） */
			fnToMyArticles() {
				uni.navigateTo({ url: '/pagesA/my-articles/my-articles' });
			},
			fnOpenHistory(item) {
				if (!item.canOpen) {
					uni.showToast({ icon: 'none', title: '文章还在待审，通过后可在站内查看' });
					return;
				}
				this.fnToArticle(item.name);
			},
			fnToArticle(name) {
				if (!name) {
					return;
				}
				uni.navigateTo({
					url: `/pagesA/article-detail/article-detail?name=${encodeURIComponent(name)}`
				});
			},
			// ==================== 提交 ====================

			async fnSubmit() {
				if (this.submitting) {
					return;
				}
				if (this.calcBlockers.length) {
					uni.showToast({ icon: 'none', title: this.blockHint });
					return;
				}
				const confirmed = await this.fnConfirm();
				if (!confirmed) {
					return;
				}
				if (this.isEditing) {
					await this.fnSubmitEdit();
					return;
				}
				this.submitting = true;
				try {
					const cover = await this.fnResolveCover();
					const title = this.form.title.trim();
					const content = this.form.content.trim();
					uni.showLoading({ mask: true, title: '提交中...' });
					const result = await submitUserArticle(this.$httpApi.v2, {
						title,
						content,
						cover,
						categories: this.selectedCategories.slice(),
						tags: this.selectedTags.slice()
					});
					if (!result.ok) {
						throw { error: result.reason || '投稿失败，请稍后重试' };
					}
					const data = result.data || {};
					// 服务端文章结果的 balance 恒为 0（不参与余额回传），只有有效值才广播，
					// 否则会把「我的」页的余额显示清零
					if (typeof data.balance === 'number' && data.balance > 0) {
						emitPointsUpdated({ balance: data.balance });
					}
					const mode = data.mode || (this.isDraftMode ? 'draft' : 'direct');
					const pointsCost = typeof data.pointsCost === 'number'
						? data.pointsCost
						: (this.isPrivileged ? 0 : this.status.articleCost);
					this.fnPushHistory({
						title,
						mode,
						statusText: data.statusText || '已提交',
						name: data.name || '',
						permalink: data.permalink || '',
						at: Date.now()
					});
					this.fnDiscardDraft();
					this.undoStack = [];
					// 通知首页列表重拉：刚发的文章要立刻出现在用户返回时看到的那份列表里
					uni.$emit('article_list_should_refresh');
					this.resultPanel = {
						title,
						mode,
						statusText: data.statusText || '已提交',
						name: data.name || '',
						permalink: data.permalink || '',
						words: content.length,
						pointsCost,
						canOpen: !!(data.name && mode === 'direct')
					};
					uni.hideLoading();
				} catch (err) {
					uni.hideLoading();
					this.fnReportPublishFailure(err);
					this.fnSaveDraft(false);
				} finally {
					this.submitting = false;
					uni.hideLoading();
				}
			},
			/**
			 * 保存修改：不扣积分、不写本机投稿记录，也不碰本机草稿。
			 *
			 * <p>「改动有没有在站点上生效」由服务端的 statusText 说清楚（待审投稿保存后站点上仍是旧内容），
			 * 这里原样展示，不自己拼文案——前端拼出来的结论后台一改策略就会变成假话。</p>
			 */
			async fnSubmitEdit() {
				this.submitting = true;
				try {
					const cover = await this.fnResolveCover();
					const title = this.form.title.trim();
					const content = this.form.content.trim();
					uni.showLoading({ mask: true, title: '保存中...' });
					const result = await saveUserArticle(this.$httpApi.v2, this.editingName, {
						title,
						content,
						cover,
						categories: this.selectedCategories.slice(),
						tags: this.selectedTags.slice(),
						allowComment: this.allowComment
					});
					if (!result.ok) {
						throw { error: result.reason || '保存失败，请稍后重试' };
					}
					const data = result.data || {};
					// 详情页/首页列表都按这篇文章的名字展示：改完要让它们重拉
					uni.$emit('article_list_should_refresh');
					this.resultPanel = {
						title,
						mode: data.mode || (this.isDraftMode ? 'draft' : 'direct'),
						statusText: data.statusText || '修改已保存',
						name: data.name || this.editingName,
						permalink: data.permalink || (this.editingArticle?.permalink || ''),
						words: content.length,
						pointsCost: 0,
						// 待审文章在站内本来就看不到，只有已发布的才给「查看文章」
						canOpen: !!(this.editingArticle && this.editingArticle.published),
						editing: true
					};
					uni.hideLoading();
				} catch (err) {
					uni.hideLoading();
					this.fnReportPublishFailure(err, '保存失败');
				} finally {
					this.submitting = false;
					uni.hideLoading();
				}
			},

			/**
			 * 投稿失败的提示。
			 *
			 * <p>服务端会把 Halo 的原始响应一起带回来（可能几百字 JSON），弹窗放不下、
			 * 用户读不完也复制不了。所以原文复制到剪贴板，弹窗里只留结论 + Halo 日志检索键
			 * requestId——出问题时把剪贴板内容或这个 id 给开发者，就能直接定位。</p>
			 */
			fnReportPublishFailure(err, title) {
				const detail = String(err?.error || err?.detail || err?.message || '投稿失败，请稍后重试');
				const matched = detail.match(/requestId["']?\s*[:=]\s*["']?([\w-]+)/);
				const requestId = matched ? matched[1] : '';
				const brief = detail.replace(/\s+/g, ' ').trim();
				const shown = brief.length > 240 ? `${brief.slice(0, 240)}…` : brief;
				// setClipboardData 自带「内容已复制」提示，这里不再重复提示
				uni.setClipboardData({ data: detail });
				const lines = [shown, '', '完整报错已复制到剪贴板，可直接粘贴给开发者。'];
				if (requestId) {
					lines.push(`站长可在 Halo 后台「系统 - 日志」搜索 requestId：${requestId}`);
				}
				uni.showModal({
					title: title || '投稿失败',
					content: lines.join('\n'),
					showCancel: false,
					confirmText: '知道了'
				});
			},
			/** 封面在提交时才上传（避免用户只是试试看就留下孤儿附件） */
			async fnResolveCover() {
				if (!this.coverLocalPath) {
					return this.form.coverUrl;
				}
				this.coverUploading = true;
				uni.showLoading({ mask: true, title: '上传封面...' });
				try {
					const uploaded = await uploadUserPublishImage(this.$httpApi.v2, this.coverLocalPath);
					if (!uploaded.ok || !uploaded.url) {
						throw { error: uploaded.reason || '封面上传失败，可先去掉封面再发布' };
					}
					this.form.coverUrl = uploaded.url;
					return uploaded.url;
				} finally {
					this.coverUploading = false;
				}
			},
			fnConfirm() {
				if (this.isEditing) {
					return new Promise((resolve) => {
						uni.showModal({
							title: '保存修改',
							content: `${this.modeTipText}\n标题：${this.form.title.trim()}\n正文 ${this.contentLength} 字`,
							confirmText: '保存',
							confirmColor: '#2F54EB',
							success: (res) => resolve(!!res.confirm),
							fail: () => resolve(false)
						});
					});
				}
				const cost = this.isPrivileged ? 0 : (this.status ? this.status.articleCost : 0);
				const actionText = this.isDraftMode ? '提交审核' : '发布';
				const costText = cost > 0
					? `本次将消耗 ${cost} 积分，发布失败会自动退还。`
					: '本次不消耗积分。';
				return new Promise((resolve) => {
					uni.showModal({
						title: `确认${actionText}`,
						content: `${costText}\n标题：${this.form.title.trim()}\n正文 ${this.contentLength} 字`,
						confirmText: actionText,
						confirmColor: '#2F54EB',
						success: (res) => resolve(!!res.confirm),
						fail: () => resolve(false)
					});
				});
			},
			fnOpenArticle() {
				this.fnToArticle(this.resultPanel && this.resultPanel.name);
			},
			fnWriteAnother() {
				this.resultPanel = null;
				this.form.title = '';
				this.form.content = '';
				this.form.coverUrl = '';
				this.coverLocalPath = '';
				this.undoStack = [];
				// 分类/标签回到默认（订阅里的第一个分类），第二篇不用再从零挑
				this.selectedCategories = [];
				this.selectedTags = [];
				this.fnApplyDefaultCategory();
				this.mode = 'edit';
				this.fnDiscardDraft();
				uni.pageScrollTo({ scrollTop: 0, duration: 0 });
				// 额度与余额都变了，重新取一次结论（比如刚好用完今日额度）
				this.fnLoadStatus();
			}
		}
	};
</script>

<style lang="scss" scoped>
	.publish-page {
		min-height: 100vh;
		background-color: $uh-bg;
	}

	.loading-wrap {
		padding-top: 30vh;
	}

	.denied-wrap {
		padding: 20vh 48rpx 0;
	}

	.denied-tip {
		margin-top: 16rpx;
		font-size: 26rpx;
		color: $uh-ink-3;
		text-align: center;
	}

	.publish-body {
		padding: 24rpx;
		padding-bottom: calc(40rpx + env(safe-area-inset-bottom));
	}

	/* ========== 提交成功 ========== */
	.result-card {
		padding: 32rpx 28rpx;
		background-color: $uh-card;
		border-radius: $uh-radius-lg;
		box-shadow: $uh-shadow-sm;
	}

	.result-head {
		display: flex;
		align-items: center;
	}

	.result-mark {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 56rpx;
		height: 56rpx;
		border-radius: $uh-radius-pill;
		background-color: $uh-success-weak;
		color: $uh-success;
		font-size: 32rpx;
		font-weight: 700;
	}

	.result-badge {
		margin-left: 16rpx;
		font-size: 30rpx;
		font-weight: 600;
		color: $uh-ink-1;
	}

	.result-title {
		display: block;
		margin-top: 24rpx;
		font-size: 34rpx;
		font-weight: 600;
		color: $uh-ink-1;
		line-height: 1.5;
	}

	.result-desc {
		display: block;
		margin-top: 10rpx;
		font-size: 26rpx;
		color: $uh-ink-3;
		line-height: 1.6;
	}

	.result-rows {
		margin: 24rpx 0 28rpx;
		padding: 20rpx 22rpx;
		border-radius: $uh-radius-md;
		background-color: $uh-fill;
	}

	.result-row {
		display: flex;
		align-items: center;
		justify-content: space-between;

		& + & {
			margin-top: 14rpx;
		}
	}

	.result-label {
		font-size: 25rpx;
		color: $uh-ink-3;
	}

	.result-value {
		font-size: 25rpx;
		color: $uh-ink-1;
	}

	/* ========== 顶部状态 ========== */
	.status-card {
		padding: 22rpx 24rpx;
		background-color: $uh-card;
		border-radius: $uh-radius-lg;
		box-shadow: $uh-shadow-sm;
	}

	.status-line {
		display: flex;
		align-items: center;
		justify-content: space-between;
	}

	.status-mode {
		font-size: 27rpx;
		font-weight: 600;

		&.is-direct {
			color: $uh-accent;
		}

		&.is-draft {
			color: $uh-warning;
		}
	}

	.status-author {
		font-size: 24rpx;
		color: $uh-ink-3;
	}

	.status-chips {
		display: flex;
		flex-wrap: wrap;
		margin-top: 16rpx;
	}

	.status-chip {
		margin: 0 12rpx 8rpx 0;
		padding: 6rpx 16rpx;
		border-radius: $uh-radius-pill;
		background-color: $uh-fill;
		font-size: 22rpx;
		color: $uh-ink-2;
	}

	.status-hint {
		display: block;
		margin-top: 6rpx;
		font-size: 23rpx;
		color: $uh-ink-3;
		line-height: 1.6;
	}

	/* ========== 编辑 / 预览切换 ========== */
	.seg-bar {
		display: flex;
		align-items: center;
		margin-top: 20rpx;
		padding: 8rpx;
		border-radius: $uh-radius-pill;
		background-color: $uh-card;
		box-shadow: $uh-shadow-sm;
	}

	.seg-item {
		flex: 1;
		padding: 14rpx 0;
		border-radius: $uh-radius-pill;
		font-size: 27rpx;
		color: $uh-ink-3;
		text-align: center;

		&.is-on {
			background-color: $uh-accent-weak;
			color: $uh-accent;
			font-weight: 600;
		}
	}

	.seg-zen {
		padding: 0 22rpx;
		font-size: 24rpx;
		color: $uh-accent;
	}

	/* ========== 撰写区 ========== */
	.publish-card {
		margin-top: 20rpx;
		padding: 24rpx;
		background-color: $uh-card;
		border-radius: $uh-radius-lg;
		box-shadow: $uh-shadow-sm;
	}

	.publish-title {
		width: 100%;
		font-size: 34rpx;
		font-weight: 600;
		color: $uh-ink-1;
		padding-bottom: 20rpx;
		border-bottom: 1rpx solid $uh-line;
	}

	.title-meta {
		display: flex;
		justify-content: flex-end;
		margin-top: 10rpx;
	}

	.title-len {
		font-size: 22rpx;
		color: $uh-ink-4;

		&.is-over {
			color: $uh-danger;
		}
	}

	.publish-input {
		width: 100%;
		min-height: 520rpx;
		margin-top: 20rpx;
		font-size: 29rpx;
		line-height: 1.75;
		color: $uh-ink-1;
	}

	.count-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-top: 12rpx;
		padding-top: 16rpx;
		border-top: 1rpx solid $uh-line;
	}

	.count-item {
		font-size: 22rpx;
		color: $uh-ink-4;
	}

	/* ========== 卡片通用 ========== */
	.card-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
	}

	.card-title {
		font-size: 27rpx;
		font-weight: 600;
		color: $uh-ink-1;
	}

	.card-sub {
		font-size: 22rpx;
		color: $uh-ink-3;
	}

	/* ========== 工具条 ========== */
	.tool-card {
		margin-top: 20rpx;
		padding: 22rpx 24rpx;
		background-color: $uh-card;
		border-radius: $uh-radius-lg;
		box-shadow: $uh-shadow-sm;
	}

	.tool-scroll {
		width: 100%;
		margin-top: 16rpx;
		white-space: nowrap;
	}

	.tool-list {
		display: inline-flex;
	}

	.tool-chip {
		margin-right: 12rpx;
		padding: 12rpx 22rpx;
		border-radius: $uh-radius-pill;
		background-color: $uh-fill;
		font-size: 24rpx;
		color: $uh-ink-1;

		&:last-child {
			margin-right: 0;
		}
	}

	.tool-foot {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-top: 18rpx;
		padding-top: 16rpx;
		border-top: 1rpx solid $uh-line;
	}

	.tool-act {
		font-size: 25rpx;
		color: $uh-accent;

		&.is-off {
			color: $uh-ink-4;
		}

		&--danger {
			color: $uh-danger;
		}
	}

	/* ========== 模板 ========== */
	.tpl-card {
		margin-top: 20rpx;
		padding: 22rpx 24rpx;
		background-color: $uh-card;
		border-radius: $uh-radius-lg;
		box-shadow: $uh-shadow-sm;
	}

	.tpl-list {
		display: flex;
		flex-wrap: wrap;
		margin-top: 12rpx;
	}

	.tpl-chip {
		margin: 8rpx 14rpx 0 0;
		padding: 14rpx 24rpx;
		border: 1rpx solid $uh-line;
		border-radius: $uh-radius-md;
		font-size: 25rpx;
		color: $uh-ink-1;
	}

	/* ========== 分类 / 标签 ========== */
	.tax-card {
		margin-top: 20rpx;
		padding: 22rpx 24rpx;
		background-color: $uh-card;
		border-radius: $uh-radius-lg;
		box-shadow: $uh-shadow-sm;
	}

	.chip-wrap {
		display: flex;
		flex-wrap: wrap;
		margin-top: 12rpx;
	}

	.pick-chip {
		display: flex;
		align-items: center;
		margin: 8rpx 14rpx 0 0;
		padding: 12rpx 22rpx;
		border: 1rpx solid $uh-line;
		border-radius: $uh-radius-pill;
		background-color: $uh-fill;

		&.is-on {
			border-color: $uh-accent;
			background-color: $uh-accent-weak;
		}

		/* 已订阅但没选中：给个描边提示，一眼看出哪些是「首页看得到」的 */
		&.is-sub {
			border-color: $uh-accent;
			background-color: transparent;
		}
	}

	.pick-chip-text {
		font-size: 25rpx;
		color: $uh-ink-1;
	}

	.pick-chip.is-on .pick-chip-text {
		color: $uh-accent;
		font-weight: 600;
	}

	.pick-chip-tag {
		margin-left: 8rpx;
		font-size: 20rpx;
		color: $uh-accent;
	}

	.pick-hint {
		display: block;
		margin-top: 12rpx;
		font-size: 23rpx;
		color: $uh-ink-3;
	}

	.pick-foot {
		display: block;
		margin-top: 14rpx;
		font-size: 22rpx;
		color: $uh-ink-4;
	}

	/* 所选分类不在订阅里：作者自己的首页看不到这篇，给一键加入订阅 */
	.sub-warn {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-top: 16rpx;
		padding: 16rpx 20rpx;
		border-radius: $uh-radius-md;
		background-color: $uh-warning-weak;
	}

	.sub-warn-text {
		flex: 1;
		font-size: 23rpx;
		color: $uh-warning;
		line-height: 1.5;
	}

	.sub-warn-act {
		margin-left: 16rpx;
		padding: 8rpx 20rpx;
		border-radius: $uh-radius-pill;
		background-color: $uh-warning;
		color: #fff;
		font-size: 23rpx;
	}

	.tag-search {
		width: 100%;
		margin-top: 16rpx;
		padding: 14rpx 20rpx;
		border-radius: $uh-radius-md;
		background-color: $uh-fill;
		font-size: 25rpx;
		color: $uh-ink-1;
	}

	.preview-meta {
		display: flex;
		flex-wrap: wrap;
		margin-top: 20rpx;
	}

	.preview-tag {
		margin: 0 12rpx 8rpx 0;
		padding: 6rpx 16rpx;
		border-radius: $uh-radius-pill;
		background-color: $uh-fill;
		font-size: 22rpx;
		color: $uh-ink-2;
	}

	.preview-tag--cat {
		background-color: $uh-accent-weak;
		color: $uh-accent;
	}

	/* ========== 封面 ========== */
	.cover-card {
		margin-top: 20rpx;
		padding: 22rpx 24rpx;
		background-color: $uh-card;
		border-radius: $uh-radius-lg;
		box-shadow: $uh-shadow-sm;
	}

	.comment-card {
		margin-top: 20rpx;
		padding: 22rpx 24rpx;
		background-color: $uh-card;
		border-radius: $uh-radius-lg;
		box-shadow: $uh-shadow-sm;
	}

	.comment-body {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-top: 16rpx;
	}

	.comment-label {
		flex: 1;
		font-size: 25rpx;
		color: $uh-ink-2;
	}

	.cover-body {
		margin-top: 16rpx;
	}

	.cover-preview-wrap {
		position: relative;
		width: 100%;
		height: 300rpx;
	}

	.cover-preview {
		width: 100%;
		height: 300rpx;
		border-radius: $uh-radius-md;
	}

	.cover-remove {
		position: absolute;
		top: -12rpx;
		right: -12rpx;
		display: flex;
		align-items: center;
		justify-content: center;
		width: 48rpx;
		height: 48rpx;
		border-radius: $uh-radius-pill;
		background-color: rgba(31, 31, 31, 0.6);
	}

	.cover-add {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		height: 240rpx;
		border: 2rpx dashed $uh-line;
		border-radius: $uh-radius-md;
		background-color: $uh-fill;
	}

	.cover-add-text {
		margin-top: 10rpx;
		font-size: 24rpx;
		color: $uh-ink-3;
	}

	.cover-tip {
		display: block;
		margin-top: 14rpx;
		font-size: 22rpx;
		color: $uh-ink-4;
	}

	/* ========== 最近投稿 ========== */
	.history-card {
		margin-top: 20rpx;
		padding: 22rpx 24rpx;
		background-color: $uh-card;
		border-radius: $uh-radius-lg;
		box-shadow: $uh-shadow-sm;
	}

	.history-item {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-top: 20rpx;
	}

	.history-main {
		flex: 1;
		overflow: hidden;
	}

	.history-title {
		display: block;
		font-size: 27rpx;
		color: $uh-ink-1;
		overflow: hidden;
		white-space: nowrap;
		text-overflow: ellipsis;
	}

	.history-meta {
		display: block;
		margin-top: 6rpx;
		font-size: 22rpx;
		color: $uh-ink-3;
	}

	.history-arrow {
		margin-left: 16rpx;
		font-size: 24rpx;
		color: $uh-accent;

		&.is-off {
			color: $uh-ink-4;
		}
	}

	.history-tip {
		flex: 1;
		font-size: 22rpx;
		color: $uh-ink-4;
	}

	.history-foot {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-top: 18rpx;
	}

	.history-clear {
		margin-left: 16rpx;
		font-size: 22rpx;
		color: $uh-ink-3;
	}

	.history-hint {
		display: block;
		margin-top: 16rpx;
		font-size: 23rpx;
		color: $uh-ink-3;
		line-height: 1.6;
	}

	/* ========== 预览 ========== */
	.preview-card {
		margin-top: 20rpx;
		padding: 24rpx;
		background-color: $uh-card;
		border-radius: $uh-radius-lg;
		box-shadow: $uh-shadow-sm;
	}

	.preview-title {
		display: block;
		font-size: 38rpx;
		font-weight: 600;
		color: $uh-ink-1;
		line-height: 1.45;
	}

	.preview-cover {
		width: 100%;
		margin-top: 20rpx;
		border-radius: $uh-radius-md;
	}

	.preview-empty {
		display: flex;
		align-items: center;
		justify-content: center;
		height: 320rpx;
	}

	.preview-empty-text {
		font-size: 26rpx;
		color: $uh-ink-4;
	}

	.preview-foot {
		display: block;
		margin-top: 24rpx;
		padding-top: 18rpx;
		border-top: 1rpx solid $uh-line;
		font-size: 24rpx;
		color: $uh-ink-3;
	}

	/* ========== 发布前检查 ========== */
	.check-card {
		margin-top: 20rpx;
		padding: 22rpx 24rpx;
		background-color: $uh-card;
		border-radius: $uh-radius-lg;
		box-shadow: $uh-shadow-sm;
	}

	.check-row {
		display: flex;
		align-items: center;
		margin-top: 16rpx;
	}

	.check-mark {
		width: 36rpx;
		font-size: 26rpx;
		text-align: center;

		&.is-ok {
			color: $uh-success;
		}

		&.is-warn {
			color: $uh-warning;
		}

		&.is-block {
			color: $uh-danger;
		}
	}

	.check-label {
		margin-left: 12rpx;
		font-size: 25rpx;
		color: $uh-ink-1;
	}

	.check-value {
		flex: 1;
		margin-left: 16rpx;
		font-size: 23rpx;
		color: $uh-ink-3;
		text-align: right;

		&.is-block {
			color: $uh-danger;
		}
	}

	/* ========== 提示与操作 ========== */
	.mode-tip {
		margin-top: 20rpx;
		padding: 18rpx 22rpx;
		border-radius: $uh-radius-md;
		background-color: $uh-accent-weak;

		&--draft {
			background-color: $uh-warning-weak;
		}
	}

	.mode-tip-text {
		font-size: 24rpx;
		color: $uh-accent;
		line-height: 1.6;
	}

	.mode-tip--draft .mode-tip-text {
		color: $uh-warning;
	}

	.publish-actions {
		margin-top: 28rpx;
	}

	.block-hint {
		display: block;
		margin-top: 14rpx;
		font-size: 23rpx;
		color: $uh-danger;
		text-align: center;
	}

	.sub-actions {
		display: flex;
		align-items: center;
		justify-content: center;
		margin-top: 20rpx;
	}

	.sub-act {
		padding: 0 28rpx;
		font-size: 25rpx;
		color: $uh-accent;
	}

	.autosave-tip {
		display: block;
		margin-top: 16rpx;
		font-size: 22rpx;
		color: $uh-ink-4;
		text-align: center;
	}

	/* ========== 极简写作 ========== */
	.zen-bar {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 0 4rpx 4rpx;
	}

	.zen-count {
		font-size: 23rpx;
		color: $uh-ink-3;
	}

	.zen-exit {
		font-size: 24rpx;
		color: $uh-accent;
	}

	.publish-page--zen {
		.publish-input {
			min-height: 70vh;
		}
	}
</style>
