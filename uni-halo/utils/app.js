export const Platform = {
	ios: 'ios',
	android: 'android'
}

/**
 * 检查当前环境是什么环境
 */
export const checkPlatform = (name) => {
	return uni.getSystemInfoSync().platform === name;
}

// 默认的应用设置
export const _DefaultAppSettings = {
	showStartPage: false, // 是否每次启动都显示启动页
	banner: {
		useDot: true,
		dotPosition: 'right'
	},
	/**
	 * 底部导航的用户级偏好（「我的 → 系统设置 → 底部导航」）。
	 *
	 * useCustom=false 时完全跟随站长在插件后台配的那份；
	 * 打开后 show 里的开关生效，但总数仍被限制在 3~5 项（固定项首页/我的不可取消），
	 * 收敛规则见 utils/tabbar.js 的 normalizeKeys。
	 */
	tabbar: {
		useCustom: false,
		show: {
			category: true,
			moments: true,
			rank: true,
			gallery: false,
			store: false
		}
	},
	// 文章卡片
	articleCard: {
		showCover: true, // 是否显示文章封面
	},
	// 文章详情
	articleDetail: {
		showStats: true, // 是否显示文章统计条（阅读/喜欢/评论/字数）
	},
	// 悬浮返回顶部按钮
	flotbutton: {
		show: true, // 是否显示全局悬浮按钮（如返回顶部）
	},
	// 布局配置
	layout: {
		// h_row_col1 = 一行一列
		// h_row_col2 = 一行两列
		home: 'h_row_col1',
		// lr_image_text=左图右文
		// lr_text_image=左文右图
		// tb_image_text=上图下文
		// tb_text_image=上文下图
		// only_text=仅文字
		cardType: 'lr_text_image',
	},
	// 广告配置（todo）
	ad: {
		timeout: 3, // 屏蔽广告时长，时间到后自动恢复展示（单位小时）
		disabled: false, // 是否屏蔽广告（看广告可以关闭应用内设置的广告）
	},
	// 评论弹幕（文章详情）
	barrage: {
		use: false, // 是否启用
		type: 'leftBottom' // 弹幕位置（rightToLeft leftBottom）
	},
	links: {
		useGroup: false,
	},
	about: {
		showAdmin: false, // 显示后台登录入口
		showAllCount: false, // 默认显示所有的统计信息（关于页面）
	},
	// 文章配置
	article: {

	},
}
// 应用设置存储key值
export const _AppSettingsKey = 'APP_GLOBAL_SETTINGS';
/**
 * 获取应用设置
 *
 * <p>没有存过设置时必须返回「默认设置」，不能返回读到的空值。
 * 之前这里是 {@code return _appSettings}，而 {@code uni.getStorageSync} 读不到 key 时返回的是
 * 空字符串，于是首次启动（或用户清了小程序缓存）时全局设置成了 `''`，
 * 首页/文章卡片里的 {@code globalAppSettings.layout.xxx} 直接在渲染期抛错——
 * 表现就是「首页显示不正常 + 控制台一片红」，而且很难联想到是设置项读取的问题。</p>
 */
export const getAppSettings = () => {
	const stored = uni.getStorageSync(_AppSettingsKey)
	if (stored) {
		try {
			const parsed = JSON.parse(stored)
			// 存过但内容坏掉（比如被写成了 "null"）时同样回落到默认值
			if (parsed && typeof parsed === 'object') return parsed
		} catch (e) {
			console.warn('[app] 应用设置解析失败，使用默认设置', e)
		}
	}

	const fallback = JSON.parse(JSON.stringify(_DefaultAppSettings))
	uni.setStorageSync(_AppSettingsKey, JSON.stringify(fallback))
	return fallback
}

/** 一份默认设置的深拷贝：给读取设置的地方兜底用，避免各处共用同一个默认对象被改坏 */
export const getDefaultAppSettings = () => JSON.parse(JSON.stringify(_DefaultAppSettings))

/**
 * 保存应用设置
 */
export const setAppSettings = (appSettings) => {
	uni.setStorageSync(_AppSettingsKey, JSON.stringify(appSettings))
}
