const serviceIcons = {
	invite: ['user-round-plus', 'green'],
	comment: ['message-circle', 'blue'],
	browse: ['history', 'amber'],
	upvote: ['heart', 'coral'],
	'my-articles': ['file-pen-line', 'green'],
	'my-orders': ['receipt-text', 'blue'],
	'unlocked-articles': ['book-open-check', 'green'],
	settings: ['settings-2', 'blue'],
	'data-visual': ['chart-no-axes-combined', 'blue'],
	archives: ['archive', 'amber'],
	'friend-links': ['link', 'green'],
	disclaimers: ['shield-check', 'blue'],
	session: ['headset', 'green'],
	feedback: ['message-square-text', 'blue'],
	about: ['info', 'blue']
};
export const withServiceIcon = (item) => {
	const path = item.path || '';
	const key = item.action || item.key || (path.includes('user-activities') ? ((path.split('type=')[1] || '').split('&')[0]) : path.split('/')[2]);
	const icon = serviceIcons[key];
	return icon ? { ...item, uiIcon: icon[0], uiTone: icon[1] } : item;
};
