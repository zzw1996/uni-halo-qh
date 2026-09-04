import { isPointsEnabled } from '@/utils/points.js';

export default {
	namespaced: true,
	state: {
		balance: 0,
		todayEarned: 0,
		signInStatus: null,
		loaded: false
	},
	getters: {
		getPointsBalance(state) {
			return state.balance;
		},
		getSignInStatus(state) {
			return state.signInStatus;
		},
		getTodayEarned(state) {
			return state.todayEarned;
		},
		isPointsLoaded(state) {
			return state.loaded;
		}
	},
	mutations: {
		setBalance(state, balance) {
			state.balance = Number(balance) || 0;
		},
		setTodayEarned(state, value) {
			state.todayEarned = Number(value) || 0;
		},
		setSignInStatus(state, status) {
			state.signInStatus = status || null;
		},
		setLoaded(state, loaded) {
			state.loaded = !!loaded;
		},
		resetPoints(state) {
			state.balance = 0;
			state.todayEarned = 0;
			state.signInStatus = null;
			state.loaded = false;
		}
	},
	actions: {
		async refreshPoints({ commit, rootGetters }, httpApi) {
			if (!isPointsEnabled() || !httpApi?.getPointsOverview) {
				commit('resetPoints');
				return null;
			}
			try {
				const res = await httpApi.getPointsOverview();
				const data = res?.data !== undefined && res?.statusCode !== undefined
					? (res.data || {})
					: (res || {});
				commit('setBalance', data.balance);
				commit('setTodayEarned', data.todayEarned);
				commit('setSignInStatus', data.signInStatus);
				commit('setLoaded', true);
				return data;
			} catch (err) {
				console.warn('[points store] refresh failed:', err?.error || err);
				return null;
			}
		},
		applyPointsPayload({ commit }, payload = {}) {
			if (payload.balance != null) {
				commit('setBalance', payload.balance);
			}
			if (payload.todayEarned != null) {
				commit('setTodayEarned', payload.todayEarned);
			}
			if (payload.signInStatus) {
				commit('setSignInStatus', payload.signInStatus);
			}
			commit('setLoaded', true);
		}
	}
};
