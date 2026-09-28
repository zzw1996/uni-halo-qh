/**
 * 上拉加载（load more）统一入口
 *
 * <p><b>为什么需要它</b>：此前 12 个页面的 `onReachBottom` 都是同一套写法——
 *
 * <pre>
 * if (this.hasNext) { this.queryParams.page += 1; this.isLoadMore = true; this.fnGetData(); }
 * else { uni.showToast({ title: '没有更多数据了' }); }
 * </pre>
 *
 * 都没有「已有请求在飞」的判断。而小程序滚动到底部时 `onReachBottom` 会**连续触发**
 * （手指每滑动一点就触发一次），于是出现三件肉眼可见的事：
 *
 * <ol>
 *   <li><b>并发请求</b>：同一个下一页被请求多次，响应回来后各自往列表里追加，
 *       出现重复条目、顺序错乱；</li>
 *   <li><b>一卡一卡</b>：每次追加都把整个列表重新 setData（一次性序列化全部条目），
 *       次数一多就掉帧；</li>
 *   <li><b>提示太敏感</b>：列表已经到底时，每触发一次就弹一次「没有更多数据了」，
 *       手指在底部一停就连弹。</li>
 * </ol>
 *
 * <p>这个 mixin 把三件事收口：<b>进行中互斥</b>（同一时刻只受理一次上拉）、
 * <b>结束冷却</b>（吸收同一次滚动的尾随事件）、<b>到底提示只弹一次</b>
 * （之后靠列表底部那行「没有更多数据啦~」的常驻文案）。</p>
 *
 * <p>用法：页面 `mixins: [loadMoreMixin]`，然后</p>
 *
 * <pre>
 * onReachBottom() {
 *     this.fnLoadMore(this.hasNext, () => {
 *         this.queryParams.page += 1;
 *         this.isLoadMore = true;
 *         this.fnGetData();
 *     });
 * },
 * </pre>
 *
 * <p>并在请求结束（成功/失败都要）时调一次 `this.fnLoadMoreDone()` 释放互斥；
 * 万一某条路径漏了，内部还有超时兜底，不会把上拉永久卡死。</p>
 */

/** 一次加载结束后的冷却时间：吸收同一次滚动产生的尾随 reach-bottom 事件 */
const COOLDOWN_MS = 600;

/** 安全阀：页面忘记调 fnLoadMoreDone 时，最多占用这么久就自动释放 */
const FLIGHT_TIMEOUT_MS = 8000;

export default {
    data() {
        return {
            /** 是否有一次上拉加载正在进行（mixin 内部状态，页面不要直接改） */
            _loadMoreFlight: false,
            /** 冷却截止时间戳 */
            _loadMoreCooldownUntil: 0,
            /** 「到底了」是否已经提示过：每页只弹一次，避免在底部反复弹窗 */
            _loadMoreTipShown: false
        };
    },
    beforeDestroy() {
        if (this._loadMoreTimer) {
            clearTimeout(this._loadMoreTimer);
            this._loadMoreTimer = null;
        }
    },
    methods: {
        /**
         * 受理一次上拉。
         *
         * @param {boolean}  hasNext   页面自己的「还有下一页」判断
         * @param {Function} load      真正发起加载（页面上拉时只做「页码 +1、置加载中、请求」）
         * @param {string}   noMoreTip 到底时的提示文案（可选，默认「没有更多数据了」）
         * @returns {boolean} 这次上拉是否被受理
         */
        fnLoadMore(hasNext, load, noMoreTip) {
            if (this._loadMoreFlight) return false;
            if (Date.now() < this._loadMoreCooldownUntil) return false;
            if (!hasNext) {
                this.fnLoadMoreNoMore(noMoreTip);
                return false;
            }
            this._loadMoreFlight = true;
            // 兜底释放：任何一条路径漏调 fnLoadMoreDone 都不会让上拉永久失效
            if (this._loadMoreTimer) clearTimeout(this._loadMoreTimer);
            this._loadMoreTimer = setTimeout(() => {
                this._loadMoreTimer = null;
                if (this._loadMoreFlight) {
                    this.fnLoadMoreDone();
                }
            }, FLIGHT_TIMEOUT_MS);
            try {
                load();
            } catch (err) {
                this.fnLoadMoreDone();
                throw err;
            }
            return true;
        },

        /** 页面在加载结束（成功或失败）时调用：释放互斥并进入冷却 */
        fnLoadMoreDone() {
            if (this._loadMoreTimer) {
                clearTimeout(this._loadMoreTimer);
                this._loadMoreTimer = null;
            }
            this._loadMoreFlight = false;
            this._loadMoreCooldownUntil = Date.now() + COOLDOWN_MS;
        },

        /**
         * 「到底了」提示。
         *
         * 每个页面只弹一次：列表底部的常驻文案已经在说明这件事，
         * 每次都弹只会让用户在底部滑动时被反复打断。
         */
        fnLoadMoreNoMore(noMoreTip) {
            if (this._loadMoreTipShown) return;
            this._loadMoreTipShown = true;
            uni.showToast({
                icon: 'none',
                title: noMoreTip || '没有更多数据了'
            });
        }
    }
};
