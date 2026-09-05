/**
 * 每日一句文案库：按日期轮换，支持一键复制/分享
 */
const QUOTES = [
	{ text: '生活明朗，万物可爱，人间值得，未来可期。', author: '未知' },
	{ text: '把每一天当作生命中最年轻的一天来珍惜。', author: '作家札记' },
	{ text: '少年不惧岁月长，彼方尚有荣光在。', author: '佚名' },
	{ text: '万物皆有裂痕，那是光照进来的地方。', author: '莱昂纳德·科恩' },
	{ text: '保持热爱，奔赴山海。', author: '未知' },
	{ text: '星光不问赶路人，时光不负有心人。', author: '佚名' },
	{ text: '慢一点没关系，只要在走，就在变好。', author: '未知' },
	{ text: '所有的努力，都会在未来加倍发光。', author: '修身录' },
	{ text: '人生如逆旅，我亦是行人。', author: '苏轼' },
	{ text: '凡是过往，皆为序章。', author: '莎士比亚' },
	{ text: '热爱可抵岁月漫长。', author: '未知' },
	{ text: '心之所向，素履以往；生如逆旅，一苇以航。', author: '木心' },
	{ text: '不能则学，不知则问，耻于问人，决无长进。', author: '《围炉夜话》' },
	{ text: '山川是不卷收的文章，日月为你掌灯伴读。', author: '简媜' },
	{ text: '知不足而奋进，望远山而前行。', author: '未知' },
	{ text: '你若盛开，蝴蝶自来；你若精彩，天自安排。', author: '三毛' },
	{ text: '悟已往之不谏，知来者之可追。', author: '陶渊明' },
	{ text: '不驰于空想，不骛于虚声。', author: '李大钊' },
	{ text: '生活的理想，就是为了理想的生活。', author: '张闻天' },
	{ text: '追光的人，终会光芒万丈。', author: '未知' },
	{ text: '书山有路勤为径，学海无涯苦作舟。', author: '韩愈' },
	{ text: '凡事都要脚踏实地去做，不驰于空想。', author: '李大钊' },
	{ text: '最慢的步伐不是跬步，而是徘徊。', author: '未知' },
	{ text: '路虽远，行则将至；事虽难，做则必成。', author: '荀子' },
	{ text: '一个人可以被毁灭，但不能被打败。', author: '海明威' },
	{ text: '你当温柔，且有力量。', author: '未知' },
	{ text: '腹有诗书气自华，读书万卷始通神。', author: '苏轼' },
	{ text: '笑看风云淡，坐对云起时。', author: '罗隐' },
	{ text: '眼里有光，心中有爱，目之所及，皆是美好。', author: '未知' },
	{ text: '以蝼蚁之行，展鸿鹄之志。', author: '未知' }
];

/** 获取今日份文案（按当天日期轮换，同一天稳定一致） */
export const getTodayQuote = () => {
	const now = new Date();
	const start = new Date(now.getFullYear(), 0, 0);
	const dayOfYear = Math.floor((now - start) / 86400000);
	return QUOTES[dayOfYear % QUOTES.length] || QUOTES[0];
};

export default QUOTES;
