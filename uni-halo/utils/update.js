/**
 * 检查微信小程序更新
 */

/**
 * 需要跨版本保留的本地键：登录态与用户自己的设置。
 *
 * <p>更新重启时清缓存的<b>本意</b>是「别让旧版本的列表/配置缓存影响新版本」，
 * 但原来是 {@code uni.clearStorageSync()} 一把清空——连登录态（APP_WX_LOGIN_INFO、openid）
 * 和用户设置一起清掉了：每次发版用户点一次「确定」，就要重新登录、设置也回到默认。</p>
 *
 * <p>所以改成「保留清单」而不是「清理清单」：清单内的键留下，其余全部清掉。
 * 保留清单能让新增的缓存键自动被下一次更新清理（用清理清单则每次加缓存都要记得补上）。</p>
 */
const PRESERVE_KEYS = [
	// 登录态
	'APP_WX_LOGIN_INFO',
	'openid',
	'Visitor',
	// 应用设置与外观（含「跟账号走」的同步元数据、框架配色与深色开关）
	'APP_GLOBAL_SETTINGS',
	'unihalo_appearance_mode',
	'unihalo_skin',
	'unihalo_app_settings_sync',
	'setTmVuetifyColor',
	'setTmVuetifyBlack',
	// 启动页的「只显示一次」标记：清了会让老用户更新后又被引导页拦一次
	'APP_HAS_STARTED',
	// 邀请关系（用户在站外扫码带来的，丢了很难补）
	'invite.pending',
	'invite.code'
];

/**
 * 更新重启前清缓存：只清易失的列表/配置/图片缓存，保留登录态与用户设置。
 */
export const clearVolatileStorage = () => {
	let keys = [];
	try {
		keys = (uni.getStorageInfoSync() || {}).keys || [];
	} catch (err) {
		// 读不到键名列表（极少见）：宁可这次不清缓存，也不能把登录态清了
		return;
	}
	keys.forEach((key) => {
		if (PRESERVE_KEYS.indexOf(key) >= 0) {
			return;
		}
		try {
			uni.removeStorageSync(key);
		} catch (err) {
			// 单个键删不掉不影响其它键
		}
	});
};

export const CheckWxUpdate = (useTip = false) => {
	if (uni.canIUse('getUpdateManager')) {
		const updateManager = wx.getUpdateManager();
		updateManager && updateManager.onCheckForUpdate((res) => {
			if (res.hasUpdate) {
				updateManager.onUpdateReady(() => {
					uni.showModal({
						title: '更新提示',
						content: '新版本已经准备就绪，是否需要重新启动应用？',
						success: (res) => {
							if (res.confirm) {
								// 刷新缓存但保留登录态与设置，见 clearVolatileStorage
								clearVolatileStorage()
								updateManager.applyUpdate()
							}
						}
					})
				})

				updateManager.onUpdateFailed(() => {
					uni.showModal({
						title: '已有新版本上线',
						content: '小程序自动更新失败，请删除该小程序后重新搜索打开哟~~~',
						showCancel: false
					})
				})
			} else {
				if (useTip) {
					//没有更新
					uni.showToast({
						icon: 'none',
						title: '已经是最新版本！'
					})
				}
			}
		})
	} else {

		uni.showModal({
			title: '提示',
			content: '当前微信版本过低，无法使用该功能，请更新到最新的微信后再重试。',
			showCancel: false
		})
	}
}
