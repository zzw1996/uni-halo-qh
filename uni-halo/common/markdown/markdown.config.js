/**
 * markdown配置
 */

import {getAppConfigs} from '@/config/index.js'
import HaloTokenConfig from '@/config/uhalo.config.js'
import utils from '@/utils/index.js'
import { resolveTheme } from '@/common/themes/index.js'
import { effectiveSkinId } from '@/utils/skin.js'

const lightConfig = {
    domain: HaloTokenConfig.BASE_API,
    tagStyle: {
        table: ` 
			table-layout: fixed;
			border-collapse:collapse;
			margin-bottom: 18px;
			overflow: hidden;
			font-size: 13px;
			color: #1A1A1F;
			background: #f2f6fc;
			border: 1px solid #dcdcdc;
			border-radius: 4px;
		`,
        th: `
			padding: 8px;
			border-right: 1px solid #ECE6DD;
			border-bottom: 1px solid #ECE6DD;
		`,
        td: `
			padding: 8px;
			border-right: 1px solid #ECE6DD;
			border-bottom: 1px solid #ECE6DD;
		`,
        blockquote: `
			padding: 10px 15px;
		    color: #4a4a48;
		    background: #FDEBE3;
		    border-left: 5px solid #C2410C;
		    border-radius: 4px;
			line-height: 26px;
			margin-bottom: 18px;
		`,
        ul: 'padding-left: 15px;line-height: 1.9;',
        ol: 'padding-left: 15px;line-height: 1.9;',
        li: 'margin-bottom: 12px;line-height: 1.9;',
        h1: `
			margin: 30px 0 20px;
			color: #1A1A1F;
			line-height: 1.5;
			position: relative;
			font-size:1.25em;
			font-family: "Noto Serif SC", "Source Han Serif SC", "Songti SC", "SimSun", serif;
			`,
        h2: `
			color: #1A1A1F;
			line-height: 1.5;
			position: relative;
			margin: 24px 0 16px;
			font-size: 1.2em;
			font-family: "Noto Serif SC", "Source Han Serif SC", "Songti SC", "SimSun", serif;
		`,
        h3: `
		    color: #1A1A1F;
		    line-height: 1.5;
		    position: relative;
			margin: 26px 0 18px;
			font-size: 1.15em;
			font-family: "Noto Serif SC", "Source Han Serif SC", "Songti SC", "SimSun", serif;
		`,
        h4: `
			color: #1A1A1F;
		    line-height: 1.5;
		    margin-bottom: 18px;
		    position: relative;
			font-size: 1.12em;
		`,
        h5: `
			color: #1A1A1F;
		    line-height: 1.5;
		    margin-bottom: 14px;
		    position: relative;
			font-size: 1.1em;
		`,
        h6: `
			color: #1A1A1F;
			line-height: 1.5;
			margin-bottom: 14px;
			position: relative;
			font-size: 14px;
		`,
        p: `
		    line-height: 1.9;
		    margin-bottom: 16px;
			font-size: 16px;
			color: #1A1A1F;
		`,
        'code': `  `,
        strong: 'font-weight: 700;color: inherit;',
        video: 'width: 100%',
    },
    containStyle: 'font-family: "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Helvetica Neue", sans-serif;padding:16px;font-size: 16px;color: #1A1A1F;word-spacing: 0.8px;letter-spacing: 0.6px;line-height: 1.9;border-radius: 6px;background-color:#FFFFFF;',

    loadingGif: utils.checkImageUrl(getAppConfigs().imagesConfig.loadingGifUrl),
    emptyGif: utils.checkImageUrl(getAppConfigs().imagesConfig.loadingEmptyUrl),
}

// mp-html 把标签样式写成内联值，不能只依赖全局皮肤 CSS。
const tintContent = (style) => {
    const t=resolveTheme(effectiveSkinId());
    const colors={'#1a1a1f':t.text,'#4a4a48':t.muted,'#f2f6fc':t.soft,'#dcdcdc':t.line,'#ece6dd':t.line,'#fdebe3':t.soft,'#c2410c':t.accentText,'#ffffff':t.card};
    return style.replace(/#[0-9a-f]{6}/gi,c=>colors[c.toLowerCase()] || c);
}
// 冻结外壳，避免 Vue 把 getter 替换为首次读取的静态配色。
export default Object.freeze({
    ...lightConfig,
    get tagStyle() { return Object.keys(lightConfig.tagStyle).reduce((styles,tag)=>{styles[tag]=tintContent(lightConfig.tagStyle[tag]);return styles},{}) },
    get containStyle() { return tintContent(lightConfig.containStyle) }
})
