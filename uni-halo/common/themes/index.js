export const THEMES = [
 {id:'paper',styleId:'maple',name:'枫光',description:'纸色枫红 · 夜墨日光',subtitle:'记录爱与成长的每一程',bg:'#F6F3EE',card:'#ffffff',text:'#1A1A1F',muted:'#6A6672',accent:'#C2410C',accentText:'#C2410C',warm:'#FFC53D',soft:'#FDEBE3',line:'#ECE6DD',radius:'40rpx',shadow:'0 2rpx 8rpx rgba(31,31,31,.04)',hero:'linear-gradient(135deg,#C2410C,#E2502A)',font:'sans-serif',dark:false},
 {id:'sky',styleId:'sky',name:'晴空日记',description:'明亮蓝橙 · 圆润插画',subtitle:'记录爱与成长的每一程',bg:'#eef7ff',card:'#ffffff',text:'#20374e',muted:'#647a91',accent:'#168bfa',accentText:'#0874d5',warm:'#ffac38',soft:'#e4f3ff',line:'#d9eafa',radius:'30rpx',shadow:'0 8rpx 28rpx rgba(38,116,176,.08)',hero:'linear-gradient(135deg,#dff3ff,#f5fbff)',font:'sans-serif',dark:false},
 {id:'mint',styleId:'oriental',name:'东方雅集',description:'米白朱红 · 水墨留白',subtitle:'在平凡生活中，收藏诗意',bg:'#f4f0e7',card:'#fffdf7',text:'#313d36',muted:'#7c8174',accent:'#a83e32',accentText:'#a83e32',warm:'#b48a4b',soft:'#efe9dc',line:'#ded8c9',radius:'10rpx',shadow:'0 3rpx 14rpx rgba(75,60,39,.04)',hero:'linear-gradient(135deg,#e2e9df,#f6f1e6)',font:'serif',dark:false},
 {id:'ink',styleId:'night',name:'极夜流光',description:'深蓝青紫 · 极光几何',subtitle:'探索更大的世界，也探索自己',bg:'#081528',card:'#10213b',text:'#eaf3ff',muted:'#9bafcc',accent:'#31dbed',accentText:'#56e3ef',warm:'#b496ff',soft:'#192d4a',line:'#254465',radius:'24rpx',shadow:'0 8rpx 26rpx rgba(0,0,0,.18)',hero:'linear-gradient(135deg,#143c59,#252555)',font:'sans-serif',dark:true},
 {id:'sunrise',styleId:'journal',name:'时光手账',description:'奶油可可 · 纸张印记',subtitle:'收藏时光里，最珍贵的日常',bg:'#f6ecdd',card:'#fffaf0',text:'#5c3c2c',muted:'#8f7864',accent:'#bc6745',accentText:'#a34f31',warm:'#cd9658',soft:'#f4e5d2',line:'#ddc6a9',radius:'16rpx',shadow:'3rpx 7rpx 0 rgba(128,94,58,.08)',hero:'linear-gradient(135deg,#f0d9be,#fff4e2)',font:'serif',dark:false}
];
export function resolveTheme(id) { return THEMES.find(t => t.id === id) || THEMES[0]; }
export function themeVariables(t) {
 const pairs={bg:t.bg,card:t.card,text:t.text,muted:t.muted,accent:t.accent,'accent-text':t.accentText,warm:t.warm,soft:t.soft,line:t.line,radius:t.radius,shadow:t.shadow,hero:t.hero,font:t.font};
 return Object.keys(pairs).map(k=>`--yf-${k}:${pairs[k]}`).join(';')+';';
}
