export const upvote = {
	UPVOTE_CANCEL_COOLDOWN_MS: 60 * 1000,
	get(key) {
		const data = uni.getStorageSync(`upvote.${key}.halo.run`)
		if (data) {
			return JSON.parse(data)
		} else {
			return []
		}
	},
	has(key, name) {
		const list = this.get(key)
		if (list.length == 0) return false;
		return list.includes(name)
	},
	set(key, name) {
		const list = this.get(key)
		if (list.includes(name)) return;
		uni.setStorageSync(`upvote.${key}.halo.run`, JSON.stringify([...list, name]))
	},
	remove(key, name) {
		const list = this.get(key).filter((item) => item !== name)
		uni.setStorageSync(`upvote.${key}.halo.run`, JSON.stringify(list))
	},
	sync(key, name, upvoted) {
		if (upvoted) {
			this.set(key, name)
		} else {
			this.remove(key, name)
		}
	},
	getUpvoteTimeKey(key, name) {
		return `upvote.time.${key}.${name}.halo.run`
	},
	setUpvoteTime(key, name, timestamp = Date.now()) {
		uni.setStorageSync(this.getUpvoteTimeKey(key, name), timestamp)
	},
	clearUpvoteTime(key, name) {
		uni.removeStorageSync(this.getUpvoteTimeKey(key, name))
	},
	getUpvoteTime(key, name) {
		const value = uni.getStorageSync(this.getUpvoteTimeKey(key, name))
		return value ? Number(value) : 0
	},
	getCancelCooldownRemainingMs(key, name, cooldownMs = this.UPVOTE_CANCEL_COOLDOWN_MS) {
		const upvoteTime = this.getUpvoteTime(key, name)
		if (!upvoteTime) return 0
		const remain = cooldownMs - (Date.now() - upvoteTime)
		return remain > 0 ? remain : 0
	},
	canCancelUpvote(key, name, cooldownMs = this.UPVOTE_CANCEL_COOLDOWN_MS) {
		return this.getCancelCooldownRemainingMs(key, name, cooldownMs) <= 0
	}
}