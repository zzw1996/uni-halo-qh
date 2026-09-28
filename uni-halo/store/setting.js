/**
 *  功能：应用设置
 *  作者：小莫唐尼
 *  邮箱：studio@925i.cn
 *  时间：2022年12月04日 12:00:44
 *  版本：v0.1.0 
 */
import {
	getAppSettings,
	setAppSettings,
	_DefaultAppSettings,
} from '@/utils/app.js'
import { queueAppSettingsDiff } from '@/utils/appSettingsSync.js'

export default {
	namespaced: true,
	state: {
		settings: getAppSettings(),
	},
	getters: {
		getSettings(state) {
			return state.settings
		},
	},
	mutations: {
		setSettings(state, data) {
			const previous = state.settings;
			state.settings = data;
			setAppSettings(data)
			// 本地已经生效，这里只把改动的键排队上传到账号副本（去抖、失败保留待下次启动补传）。
			// 所有走 store 的改动都会经过这一个口子，所以不必在每个页面各记一次「改了什么」。
			queueAppSettingsDiff(previous, data)
		},
	},
	actions: {
		// 设置默认的数据
		updateDefaultAppSettings(context) {
			context.commit('setSettings', JSON.parse(JSON.stringify(_DefaultAppSettings)))
		},
		// 检查并设置默认的数据
		checkAndSetDefaultAppSettings(context) {
			if (!context.state.settings) {
				context.commit('setSettings', getAppSettings())
			}
		}
	}
};
