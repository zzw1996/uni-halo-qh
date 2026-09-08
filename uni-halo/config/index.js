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
	loveConfig: {},
	imagesConfig: {},
	authorConfig: {},
	appConfig: {},
	pluginConfig: {
		votePlugin: {},
		toolsPlugin: {},
		linksPlugin:{},
		linksSubmitPlugin: {},
		doubanPlugin: {
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
	pageConfig: {
		homeConfig: {
			pageTitle: "首页",
			useCategory: true,
			bannerConfig: {
				enabled: true,
				showTitle: true,
				showIndicator: true,
				height: "400rpx",
				dotPosition: "right",
				type: "post",
				list: []
			}
		},
		categoryConfig: {
			type: "list"
		},
		momentConfig: {
			useTagRandomColor: true
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
	/** 邀请奖励配置（由插件后台 inviteConfig 下发） */
	inviteConfig: {
		enabled: true,
		rewardPoints: 30
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