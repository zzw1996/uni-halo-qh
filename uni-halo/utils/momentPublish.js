/**
 * 瞬间相关的展示辅助。
 *
 * 【重要变更】这里原来是一整套「客户端权限判定」：
 * 读配置里的 personalToken → 调 Halo 接口拿令牌对应博主 → 比对 openid → 决定能否发布。
 * 令牌不再下发到客户端后，这套判定必须作废：
 * - 能不能发、发出来是直发还是待审，一律由服务端 `/userPublish/status` 与 `/userPublish/moment` 决定；
 * - 「是不是自己发的」只用于界面提示（删除按钮等），**不作为权限依据**——
 *   删除权限由服务端校验（作者本人或博主/管理员），前端判错了也不会造成越权。
 */

function trimText(value) {
	return (value || '').toString().trim();
}

/** 是否为当前用户自己发布的瞬间（仅用于界面展示，不代表有权删除） */
export function isOwnMoment(moment, ownerName) {
	const owner = trimText(ownerName);
	if (!owner) {
		return false;
	}
	const momentOwner = trimText(moment?.spec?.owner || moment?.owner?.name);
	return !!momentOwner && momentOwner === owner;
}

/**
 * 界面上是否展示「删除」按钮的提示值。
 *
 * <p>真正的删除权限在服务端：博主/管理员可删任意瞬间，普通用户只能删自己的。
 * 这里只避免给用户看到一个必然失败的按钮。</p>
 */
export function canDeleteMoment(moment, { ownerName = '', privileged = false } = {}) {
	return privileged || isOwnMoment(moment, ownerName);
}

/**
 * 瞬间列表/详情的数据整形（展示用）。
 *
 * <p>把服务端返回的 Moment 资源整理成页面直接可渲染的结构：补全 content.medium、
 * 注入展示用户、生成图片/视频/音频分组，并顺手把媒体地址换成缩略图。
 * 与权限无关，纯展示层职责——所以即使权限判定已移到服务端，这个函数仍然要保留。</p>
 */
export function normalizeMomentListItem(item, {
	displayUser,
	checkThumbnailUrl,
	removeTagLinksCompletely,
	generateUUID
}) {
	if (!item?.spec) {
		return item;
	}
	const content = item.spec.content || {};
	const medium = Array.isArray(content.medium) ? content.medium : [];
	item.spec.content = {
		...content,
		medium
	};
	item.spec.user = displayUser;
	if (!item.stats) {
		item.stats = {
			upvote: 0,
			totalComment: 0,
			approvedComment: 0
		};
	}
	medium.forEach((media) => {
		if (media?.url) {
			media.url = checkThumbnailUrl(media.url, true);
		}
	});
	const htmlSource = content.html || content.raw || '';
	item.spec.newHtml = removeTagLinksCompletely(htmlSource);
	item.images = medium.filter((media) => media.type === 'PHOTO');
	item.videos = medium
		.filter((media) => media.type === 'VIDEO')
		.map((media) => ({
			...media,
			id: generateUUID()
		}));
	item.audios = medium.filter((media) => media.type === 'AUDIO');
	return item;
}

export default {
	isOwnMoment,
	canDeleteMoment,
	normalizeMomentListItem
};
