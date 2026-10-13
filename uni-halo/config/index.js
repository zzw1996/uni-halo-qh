// 应用设置存储key值
export const _AppConfigKey = 'APP_GLOBAL_CONFIGS';
export const _AppMockJsonKey = 'APP_GLOBAL_MOCK_JSON';

// 默认的应用设置
export const DefaultAppConfigs = {
	basicConfig: {
		tokenConfig: {
			personalToken: "",
		}
	},
	imagesConfig: {},
	appConfig: {},
	/**
	 * 第三方 Halo 插件页面的开关（纯前端，后台已无对应配置组）。
	 * links  = 友情链接入口
	 * douban = 文章内豆瓣卡片位置
	 */
	pluginIntegrations: {
		links: false,
		douban: {
			position: 'bottom'
		},
	},
	wxAppConfig: {
			appId: '',
			loginEnabled: true,
			/** 是否开启 Halo 账号关联（验证码绑定 / 一键创建） */
			haloBindEnabled: false,
			/** 博主微信 openid（可选，与 personalToken 对应用户一致时可手动填写） */
			ownerOpenid: '',
			profileModifyLimit: 0,
		},
	/**
	 * 视频号配置（由插件后台 channelsConfig 下发）。
	 *
	 * enabled 为 false 或没填 finderUserName 时，首页不展示视频号入口卡片——
	 * 没有视频号 ID 的卡片点不动，展示出来只会让人误以为功能坏了。
	 */
	channelsConfig: {
		enabled: false,
		finderUserName: '',
		nickname: '',
		desc: '',
		avatarUrl: '',
		buttonText: '进入视频号',
		tip: ''
	},
	/**
	 * 底部导航配置（由插件后台 tabbarConfig 下发）。
	 *
	 * 真实值以服务端下发的 items[] 为准：服务端已经把「最少 3 项、最多 5 项」的规则
	 * 和固定顺序都算好了，前端只负责画。
	 *
	 * ⚠️ 这里的 items 必须是**空数组**：配置合并走 utils.deepMerge，而它对数组是
	 * `concat`（不是替换）——写死默认项会和服务端下发的项拼在一起，底栏就会把
	 * 5 项渲染成 10 项（同一个「首页」出现两次、两处都高亮）。缺省项请放在
	 * utils/tabbar.js 的 FALLBACK_TABBAR_ITEMS 里。
	 */
	tabbarConfig: {
		enabled: false,
		items: [],
		store: {
			appId: '',
			name: '',
			desc: '',
			coverUrl: '',
			qrCodeUrl: '',
			guideUrl: ''
		}
	},
	auditConfig: {
		auditModeEnabled: false,
		auditModeData: {
			jsonUrl: "",
			jsonData: ""
		}
	},
	/** 积分系统配置（enabled / rules 由插件后台 getConfigs 下发） */
	pointsConfig: {
		showInProfile: true,
		/** false=优先服务端；API 不可用时 fallbackOnApiError=true 自动降级本地临时模式 */
		localFallback: false,
		fallbackOnApiError: true,
		minBrowseDuration: 5,
		rules: {}
	},
	/** 虚拟支付配置（enabled / goods 由插件后台 getConfigs 下发） */
	payConfig: {
		enabled: false,
		goods: []
	},
	/** 等级/称号/装扮配置（由插件后台 levelConfig 下发） */
	levelConfig: {
		enabled: true,
		levels: [],
		titles: [],
		outfits: []
	},
	/**
	 * AI 定制内容配置（由插件后台 aiContentConfig 下发）。
	 *
	 * 这是一个付费权益：用户自行配置关注方向（主题/来源/关键词），
	 * 服务端据此从已生成的 AI 日报内容里筛出他的每日简报。
	 * 默认关闭——没配商品就露出一个买不了的入口，比不显示更糟。
	 */
	pushConfig: {
		enabled: false,
		templateId: '',
		aiDigestTemplateId: '',
		aiDigestEnabled: true,
		aiDigestScheduleEnabled: true,
		aiDigestPushHours: '8,12,18,21'
	},
	aiContentConfig: {
		enabled: false,
		aiIntroEnabled: true,
		maxCategories: 5,
		maxSources: 5,
		maxKeywords: 10,
		maxTopics: 3,
		maxItems: 10,
		windowDays: 7,
		pointsPrice: 0,
		pointsDays: 30,
		presetTopics: '',
		homeCardEnabled: true,
		homeCardTitle: 'AI 定制内容',
		homeCardDesc: '每天一份为你定制的技术简报',
		fallbackGenerateEnabled: true,
		memberFree: true,
		benefitNote: ''
	},
	/** 邀请奖励配置（由插件后台 inviteConfig 下发） */
	inviteConfig: {
		enabled: true,
		rewardPoints: 30
	},
	/**
	 * 阅读券免费获取规则（由插件后台 ticketConfig 下发）。
	 *
	 * 这里只用于「首单礼」等前端文案的本地兜底：真实值以服务端下发为准。
	 * 全为 0 表示不展示任何赠券提示。
	 */
	ticketConfig: {
		newUserTickets: 0,
		firstPurchaseTickets: 0,
		signInTickets: 0,
		streakDays: 0,
		streakTickets: 0,
		exchangeTickets: 1,
		exchangePoints: 30
	},
	/**
	 * 会员配置（由插件后台 memberConfig 下发）。
	 *
	 * 这里只是本地兜底：真实值以服务端 /points/membership 返回为准，
	 * enabled 默认 false 表示不展示会员入口。
	 */
	memberConfig: {
		enabled: false,
		defaultDays: 30,
		freeContent: true,
		bonusTickets: 0
	},
	/** 榜单配置（由插件后台 rankConfig 下发） */
	rankConfig: {
		enabled: true,
		showScore: true,
		enableInviteBoard: true,
		wallEnabled: true,
		wallLimit: 10,
		wallIncludeUpvote: false,
		weeklyRewardEnabled: false,
		weeklyRewardTopN: 3,
		weeklyRewardPoints: 60,
		weeklyRewardStep: 20
	}
}

/**
 * 获取应用设置
 */
export const getAppConfigs = () => {
	let configs = uni.getStorageSync(_AppConfigKey)
	if (configs) return JSON.parse(configs)

	uni.setStorageSync(_AppConfigKey, JSON.stringify(DefaultAppConfigs))
	return DefaultAppConfigs;
}

/**
 * 保存应用设置
 */
export const setAppConfigs = (configs) => {
	uni.setStorageSync(_AppConfigKey, JSON.stringify(configs))
}


/**
 * 获取应用模拟数据
 */
export const getAppMockJson = () => {
	let json = uni.getStorageSync(_AppMockJsonKey)
	if (json) return JSON.parse(json)

	uni.setStorageSync(_AppMockJsonKey, JSON.stringify({}))
	return {};
}

/**
 * 保存应用模拟数据
 */
export const setAppMockJson = (json) => {
	uni.setStorageSync(_AppMockJsonKey, JSON.stringify(json))
}