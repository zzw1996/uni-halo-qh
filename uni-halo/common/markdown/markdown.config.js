/**
 * markdown配置
 */

import {getAppConfigs} from '@/config/index.js'
import HaloTokenConfig from '@/config/uhalo.config.js'
import utils from '@/utils/index.js'

export default {
    domain: HaloTokenConfig.BASE_API,
    tagStyle: {
        table: ` 
			table-layout: fixed;
			border-collapse:collapse;
			margin-bottom: 18px;
			overflow: hidden;
			font-size: 13px;
			color: #1f1f1f;
			background: #f2f6fc;
			border: 1px solid #dcdcdc;
			border-radius: 4px;
		`,
        th: `
			padding: 8px;
			border-right: 1px solid var(--classE);
			border-bottom: 1px solid var(--classE);
		`,
        td: `
			padding: 8px;
			border-right: 1px solid var(--classE);
			border-bottom: 1px solid var(--classE);
		`,
        blockquote: `
			padding: 10px 15px;
		    color: #4a4a48;
		    background: #eef2fe;
		    border-left: 5px solid #2f54eb;
		    border-radius: 4px;
			line-height: 26px;
			margin-bottom: 18px;
		`,
        ul: 'padding-left: 15px;line-height: 1.9;',
        ol: 'padding-left: 15px;line-height: 1.9;',
        li: 'margin-bottom: 12px;line-height: 1.9;',
        h1: `
			margin: 30px 0 20px;
			color: #1f1f1f;
			line-height: 1.5;
			position: relative;
			font-size:1.25em;
			font-family: "Noto Serif SC", "Source Han Serif SC", "Songti SC", "SimSun", serif;
			`,
        h2: `
			color: #1f1f1f;
			line-height: 1.5;
			position: relative;
			margin: 24px 0 16px;
			font-size: 1.2em;
			font-family: "Noto Serif SC", "Source Han Serif SC", "Songti SC", "SimSun", serif;
		`,
        h3: `
		    color: #1f1f1f;
		    line-height: 1.5;
		    position: relative;
			margin: 26px 0 18px;
			font-size: 1.15em;
			font-family: "Noto Serif SC", "Source Han Serif SC", "Songti SC", "SimSun", serif;
		`,
        h4: `
			color: #1f1f1f;
		    line-height: 1.5;
		    margin-bottom: 18px;
		    position: relative;
			font-size: 1.12em;
		`,
        h5: `
			color: #1f1f1f;
		    line-height: 1.5;
		    margin-bottom: 14px;
		    position: relative;
			font-size: 1.1em;
		`,
        h6: `
			color: #1f1f1f;
			line-height: 1.5;
			margin-bottom: 14px;
			position: relative;
			font-size: 14px;
		`,
        p: `
		    line-height: 1.9;
		    margin-bottom: 16px;
			font-size: 15px;
			color: #3a3a38;
		`,
        'code': `  `,
        strong: 'font-weight: 700;color: inherit;',
        video: 'width: 100%',
    },
    containStyle: 'font-family: "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Helvetica Neue", sans-serif;padding:16px;font-size: 15px;color: #3a3a38;word-spacing: 0.8px;letter-spacing: 0.6px;line-height: 1.9;border-radius: 6px;background-color:#FFFFFF;',

    loadingGif: utils.checkImageUrl(getAppConfigs().imagesConfig.loadingGifUrl),
    emptyGif: utils.checkImageUrl(getAppConfigs().imagesConfig.loadingEmptyUrl),
}
