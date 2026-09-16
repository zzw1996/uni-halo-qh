/**
 *  功能：工具类
 *  作者：小莫唐尼
 *  邮箱：studio@925i.cn
 *  时间：2022年07月21日 17:30:14
 *  版本：v0.1.0
 *  修改记录：
 *  修改内容：
 *  修改人员：
 *  修改时间：
 */

import HaloTokenConfig from '@/config/uhalo.config.js'
import {getAppConfigs} from '@/config/index.js'

const utils = {
    /**
     * 检查是否为http/https链接
     */
    checkIsUrl: function (s) {
        var reg = /^(http(s)?:\/\/)/i;
        return reg.test(s)
    },

    // 检查链接
    checkUrl: function (url) {
        if (!url) return '';
        if (!this.checkIsUrl(url)) return HaloTokenConfig.BASE_API + url;
        return url
    },

    // 检查封面图
    checkThumbnailUrl: function (thumbnail, mustRealUrl = false) {
        if (!thumbnail && mustRealUrl) {
            return this.checkUrl(getAppConfigs().imagesConfig.defaultStaticThumbnailUrl);
        }
        let _url = this.checkUrl(getAppConfigs().imagesConfig.defaultThumbnailUrl);
        if (_url) {
            if (_url.indexOf('?') === -1) {
                _url += `?next-v=${new Date().getTime()}`
            } else {
                _url += `&next-v=${new Date().getTime()}`
            }
        }
        if (!thumbnail) return _url;
        if (!this.checkIsUrl(thumbnail)) return HaloTokenConfig.BASE_API + thumbnail;
        return thumbnail
    },

    // 检查图片
    checkImageUrl: function (image) {
        let _url = this.checkUrl(getAppConfigs().imagesConfig.defaultImageUrl);
        if (_url) {
            if (_url.indexOf('?') === -1) {
                _url += `?next-v=${new Date().getTime()}`
            } else {
                _url += `&next-v=${new Date().getTime()}`
            }
        }
        if (!image) return _url;
        if (!this.checkIsUrl(image)) return HaloTokenConfig.BASE_API + image;
        return image
    },

    // 检查头像
    checkAvatarUrl: function (avatar) {
        if (!avatar) {
            let _url = this.checkUrl(getAppConfigs().imagesConfig.defaultAvatarUrl);
            if (_url) {
                if (_url.indexOf('?') === -1) {
                    _url += `?next-v=${new Date().getTime()}`
                } else {
                    _url += `&next-v=${new Date().getTime()}`
                }
            }
            return _url;
        }
        if (!this.checkIsUrl(avatar)) return HaloTokenConfig.BASE_API + avatar;
        return avatar
    },

    // 检查文件类型
    fnCheckIsFileType(type, attachment) {
        if (!attachment) return false;
        if (!attachment.mediaType) return false;
        if (type === 'video') return attachment.mediaType.indexOf('video/') !== -1;
        else if (type === 'image') return attachment.mediaType.indexOf('image/') !== -1;
        else return false;
    },
    groupData: function (arr, fun) {
        const groups = {}
        arr.forEach((el) => {
            const group = fun(el)
            groups[group] = groups[group] || []
            groups[group].push(el)
        })
        return Object.keys(groups).map((group) => {
            // 更改data的数据结构  可以改变新数据的结构 data即为newData的数组中每个数据的结构
            let data = {
                groupName: group,
                data: groups[group]
            }
            return data
        })
    },
    /**
     * 数据分组
     * @param {Object} oldData 数据源
     * @param {Object} prop 分组属性
     */
    arrayGroupBy: function (oldData, prop) {
        const newData = this.groupData(oldData, (item) => {
            return item[prop]
        })
        return newData
    },
    /**
     * 深克隆
     * @param {Object} obj 数据源
     */
    deepClone(obj) {
        var objClone = Array.isArray(obj) ? [] : {};
        if (obj && typeof obj === "object") {
            for (const key in obj) {
                if (obj.hasOwnProperty(key)) {
                    if (obj[key] && typeof obj[key] === "object") {
                        objClone[key] = this.deepClone(obj[key]);
                    } else {
                        objClone[key] = obj[key];
                    }
                }
            }
        }
        return objClone;
    },
    /**
     * 复制内容到剪贴板
     * @param {Object} obj 数据源
     */
    copyText(content, tips = '复制成功') {
        uni.setClipboardData({
            data: content,
            showToast: false,
            success: () => {
                if (tips) {
                    uni.showToast({
                        icon: 'none',
                        title: tips
                    })
                }
            }
        })
    },
    previewImage(list) {
        list = list.map(item => {
            return this.checkUrl(item)
        })
        uni.previewImage({
            urls: list,
            loop: true
        })
    },
    /**
     * 检查是否为json字符串
     * @param {Object} jsonStr 数据源
     */
    checkJsonAndParse(jsonStr) {
        try {
            const jsonResult = JSON.parse(jsonStr);
            return {
                ok: true,
                jsonData: jsonResult,
            }
        } catch (e) {
            return {
                ok: false,
                jsonData: {},
            }
        }
    },
    isObject(obj) {
        return obj && typeof obj === 'object' && !Array.isArray(obj);
    },
    deepMerge(target, source) {
        let output = Object.assign({}, target);

        if (this.isObject(target) && this.isObject(source)) {
            Object.keys(source).forEach(key => {
                const targetValue = target[key];
                const sourceValue = source[key];

                if (Array.isArray(targetValue) && Array.isArray(sourceValue)) {
                    output[key] = targetValue.concat(sourceValue);
                } else if (this.isObject(targetValue) && this.isObject(sourceValue)) {
                    output[key] = this.deepMerge(targetValue, sourceValue);
                } else {
                    output[key] = sourceValue;
                }
            });
        }
        return output;
    },

    /**
     * 把接口返回的文章/分类对象「洗」成可以安全放进页面 data 的形状。
     *
     * <p><b>为什么必须做</b>：微信的 setData 会把数据里的<b>键名</b>当作路径来解析，
     * 而 Halo 的 labels / annotations 键名是带点的，例如
     * {@code content.halo.run/owner}、{@code content.halo.run/published}。
     * 解析到 {@code content} 这一段时若拿到的不是对象（Halo 文章在列表接口里
     * `content` 就是空的），紧接着读 {@code .halo} 就会抛
     * {@code Cannot read properties of null (reading 'halo')}。
     * 后果不是"一条警告"：<b>这一次 setData 失败，页面数据就不再更新</b>——
     * 表现是点了订阅按钮但界面不翻转、看起来像"点了没反应/无法取消订阅"。</p>
     *
     * <p>而这些 label / annotation 前端一个都用不上（渲染只用到 spec / status / stats /
     * metadata.name 等），所以在数据进 data 之前先摘掉，既避开上面这个坑，
     * 也让每次 setData 的数据量小一截。</p>
     */
    stripDataUnsafeFields: function (item) {
        if (!item || typeof item !== 'object') return item;
        const clone = { ...item };

        // metadata 里只有 name 是渲染需要的
        if (clone.metadata && typeof clone.metadata === 'object') {
            clone.metadata = {
                name: clone.metadata.name,
                creationTimestamp: clone.metadata.creationTimestamp
            };
        }
        // 文章正文不进列表（详情页单独取），留着只会把 data 撑大
        delete clone.content;
        return clone;
    },

    /** 批量清洗（列表接口返回的 items） */
    stripDataUnsafeList: function (list) {
        if (!Array.isArray(list)) return [];
        return list.map((item) => this.stripDataUnsafeFields(item));
    }

};


export default utils;
