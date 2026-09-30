<template>
	<view class="app-page" :class="[skinClass, { 'is-balck grey-darken-6': isBlackTheme }]">
		<tm-form v-if="!loading" ref="formData">
			<!--
				⓪ 外观：明暗偏好。放在最前是因为它决定整页配色，
				用户找「怎么变深色」时第一眼就能看到；皮肤（买的配色）在装扮商店里，两者分开。
			-->
			<tm-sheet :shadow="6" :padding="[0, 0]" :margin="[24, 20]">
				<view class="group-head">
					<view class="group-title-wrap">
						<view class="group-bar"></view>
						<text class="group-title">外观</text>
					</view>
					<text class="group-desc">明暗与跟随系统</text>
				</view>
				<view class="group-body">
					<tm-pickers title="请选择外观模式" btn-color="indigo"
						:default-value.sync="appearance.selectDefault" rang-key="name"
						:list="appearance.list" @confirm="fnOnAppearanceConfirm">
						<uh-setting-row title="外观模式" :desc="appearanceHint">
							<view class="row-value">
								<text class="row-value-text">{{ appearance.selectLabel }}</text>
								<text class="row-arrow">›</text>
							</view>
						</uh-setting-row>
					</tm-pickers>
				</view>
			</tm-sheet>

			<!-- ① 布局与卡片：决定首页列表长什么样 -->
			<tm-sheet :shadow="6" :padding="[0, 0]" :margin="[24, 20]">
				<view class="group-head">
					<view class="group-title-wrap">
						<view class="group-bar"></view>
						<text class="group-title">布局与卡片</text>
					</view>
					<text class="group-desc">首页列表长什么样</text>
				</view>
				<view class="group-body">
					<tm-pickers title="请选择首页布局" btn-color="indigo"
						:default-value.sync="homeLayout.selectDefault" rang-key="name" :list="homeLayout.list"
						@confirm="fnOnHomeLayoutConfirm">
						<uh-setting-row title="首页文章布局"
							:desc="homeLayoutHint">
							<view class="row-value">
								<text class="row-value-text">{{ homeLayout.selectLabel }}</text>
								<text class="row-arrow">›</text>
							</view>
						</uh-setting-row>
					</tm-pickers>

					<tm-pickers title="请选择文章卡片样式" btn-color="indigo"
						:default-value.sync="articleCardStyle.selectDefault" rang-key="name"
						:list="articleCardStyle.list" @confirm="fnOnArticleCardStyleConfirm">
						<uh-setting-row title="文章卡片样式" desc="图与标题的排布方式">
							<view class="row-value">
								<text class="row-value-text">{{ articleCardStyle.selectLabel }}</text>
								<text class="row-arrow">›</text>
							</view>
						</uh-setting-row>
					</tm-pickers>

					<uh-setting-row title="显示文章封面" desc="关掉后列表只留标题与摘要">
						<tm-switch v-model="appSettings.articleCard.showCover" color="indigo"
							:text="['显示', '隐藏']"></tm-switch>
					</uh-setting-row>
				</view>
			</tm-sheet>

			<!-- ② 阅读体验 -->
			<tm-sheet :shadow="6" :padding="[0, 0]" :margin="[24, 20]">
				<view class="group-head">
					<view class="group-title-wrap">
						<view class="group-bar"></view>
						<text class="group-title">阅读体验</text>
					</view>
					<text class="group-desc">文章页与首页的细节</text>
				</view>
				<view class="group-body">
					<uh-setting-row title="显示文章详情统计" desc="文章底部的阅读量等数据">
						<tm-switch v-model="appSettings.articleDetail.showStats" color="indigo"
							:text="['显示', '隐藏']"></tm-switch>
					</uh-setting-row>

					<uh-setting-row title="全局悬浮按钮" desc="右下角一直显示的回顶按钮">
						<tm-switch v-model="appSettings.flotbutton.show" color="indigo"
							:text="['显示', '隐藏']"></tm-switch>
					</uh-setting-row>

					<uh-setting-row title="轮播图指示器" desc="首页顶部大图上的圆点">
						<tm-switch v-model="appSettings.banner.useDot" color="indigo"
							:text="['显示', '隐藏']"></tm-switch>
					</uh-setting-row>

					<!-- 圆点位置：两个选项横排，放在标题下方一整行更宽松，不挤在右侧 -->
					<view v-if="appSettings.banner.useDot" class="stack-row">
						<text class="stack-title">指示器位置</text>
						<tm-groupradio name="dotPosition" @change="fnOnBannerDotChange">
							<tm-radio :name="item.name" :size="28" color="indigo"
								v-for="(item, index) in dotPositionList" :key="item.value" v-model="item.checked"
								:label="item.name"></tm-radio>
						</tm-groupradio>
					</view>
				</view>
			</tm-sheet>

			<!-- ③ 提醒与导航：会带来外部效果（微信通知 / 底栏）的两项放一起 -->
			<tm-sheet :shadow="6" :padding="[0, 0]" :margin="[24, 20]">
				<view class="group-head">
					<view class="group-title-wrap">
						<view class="group-bar"></view>
						<text class="group-title">提醒与导航</text>
					</view>
					<text class="group-desc">微信通知与底部导航</text>
				</view>
				<view class="group-body">
					<!--
						推送开关：只在站长开了「消息推送」时出现。
						它同时写本地设置与服务端（服务端才是权威——决定要不要给你发消息），
						所以用 @change 而不是 v-model：点一下就要立刻同步过去。
					-->
					<uh-setting-row v-if="calcPushAvailable" title="接收更新提醒"
						desc="订阅的分类有新文章时，微信通知你">
						<tm-switch :checked="pushEnabled" @change="fnOnPushToggle" color="indigo"
							:text="['接收', '不接收']"></tm-switch>
					</uh-setting-row>

					<!--
						底部导航：站长在插件后台「底部导航（TabBar）」里定好默认显示哪几项，
						用户可以在下面按自己的习惯再调。固定项（首页/我的）不提供开关；
						总数限制在 3~5 项（微信原生底栏最多 5 项；最少 3 项是本站的口径，可调）。
					-->
					<uh-setting-row v-if="calcTabbarEnabled" title="自定义底部导航"
						desc="按自己的习惯决定底栏显示哪几项">
						<tm-switch v-model="appSettings.tabbar.useCustom" color="indigo"
							:text="['是', '否']"></tm-switch>
					</uh-setting-row>

					<block v-if="calcTabbarEnabled && appSettings.tabbar.useCustom">
						<uh-setting-row v-for="item in calcTabbarToggles" :key="item.key"
							:title="`显示「${item.label}」`">
							<tm-switch v-model="appSettings.tabbar.show[item.key]" color="indigo"
								:text="['显示', '隐藏']" @change="fnOnTabbarToggle(item)"></tm-switch>
						</uh-setting-row>

						<view class="tip-box">
							<text class="tip-text">「首页」与「我的」固定显示；其余项按习惯增减，总数保持在 3~5 项之间。站长在后台关闭的项不在列表里，也无法在这里打开。</text>
							<view class="tip-action" @click="fnOnTabbarResetToAdmin">
								<text class="tip-action-text">按站长配置恢复</text>
							</view>
						</view>
					</block>
				</view>
			</tm-sheet>
		</tm-form>

		<!-- 操作区：保存是主操作，另外两个退成描边按钮 -->
		<view v-if="!loading" class="action-bar">
			<text v-if="!isSaved" class="unsaved-hint">有未保存的改动</text>
			<view class="action-row">
				<view class="action-item is-minor">
					<tm-button size="m" plan theme="blue-grey" :fllowTheme="false" :shadow="0" block
						@click="fnOnBack">返回</tm-button>
				</view>
				<view class="action-item is-minor">
					<tm-button size="m" plan theme="red" :fllowTheme="false" :shadow="0" block
						@click="fnOnSaveDefault">恢复默认</tm-button>
				</view>
				<view class="action-item is-primary">
					<tm-button size="m" theme="indigo" :shadow="0" block @click="fnOnSave">保存设置</tm-button>
				</view>
			</view>
		</view>
	</view>
</template>

<script>
	import {
		_DefaultAppSettings
	} from '@/utils/app.js';
	import {
		TABBAR_MAX_OPTIONAL,
		TABBAR_MIN_OPTIONAL,
		countUserPicked,
		getTabbarCandidates,
		getToggleableCandidates
	} from '@/utils/tabbar.js';
	import { requestSubscribe } from '@/utils/subscribeMessage.js';
	import { getAppConfigs } from '@/config/index.js';
	// 「接收更新提醒」的两个入口都要用它判断登录态：早前漏了这一行，
	// 站长一开推送、进这个页面就报 checkHasWxLogin is not defined
	import { checkHasWxLogin } from '@/utils/auth.js';
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
	import tmForm from '@/tm-vuetify/components/tm-form/tm-form.vue';
	import tmPickers from '@/tm-vuetify/components/tm-pickers/tm-pickers.vue';
	import tmInput from '@/tm-vuetify/components/tm-input/tm-input.vue';
	import tmSwitch from '@/tm-vuetify/components/tm-switch/tm-switch.vue';
	import tmSheet from '@/tm-vuetify/components/tm-sheet/tm-sheet.vue';
	import tmGroupradio from '@/tm-vuetify/components/tm-groupradio/tm-groupradio.vue';
	import tmRadio from '@/tm-vuetify/components/tm-radio/tm-radio.vue';
	import uhSettingRow from '@/components/uh-setting-row/uh-setting-row.vue';
	import { APPEARANCE_MODES, appearanceState, appearanceLabelOf, applyAppearanceMode } from '@/utils/skin.js';
	import { pushAppearanceMode } from '@/utils/appSettingsSync.js';

	export default {
		components: {
			tmButton,
			tmForm,
			tmPickers,
			tmInput,
			tmSwitch,
			tmSheet,
			tmGroupradio,
			tmRadio,
			uhSettingRow
		},
		data() {
			return {
				isBlackTheme: false,
				loading: true,
				appSettings: {},
				isSaved: true,
				firstLoad: true,
				appearance: {
					// 选项来自 utils/skin.js 的 APPEARANCE_MODES：加模式只改那一处
					list: APPEARANCE_MODES.map((item) => ({ name: item.label, value: item.id })),
					selectDefault: [appearanceLabelOf(appearanceState.mode)],
					selectLabel: appearanceLabelOf(appearanceState.mode),
					selectValue: appearanceState.mode
				},
				homeLayout: {
					list: [{
						name: '一行一列',
						value: 'h_row_col1'
					}, {
						name: '一行两列',
						value: 'h_row_col2'
					}],
					selectDefault: ['一行一列'],
					selectLabel: '一行一列',
					selectValue: 'h_row_col1'
				},
				articleCardStyle: {
					list: [{
							name: '左图右文',
							value: 'lr_image_text'
						},
						{
							name: '左文右图',
							value: 'lr_text_image'
						},
						{
							name: '上图下文',
							value: 'tb_image_text'
						},
						{
							name: '上文下图',
							value: 'tb_text_image'
						},
						{
							name: '只有文字',
							value: 'only_text'
						}
					],
					selectDefault: ['左图右文'],
					selectLabel: '左图右文',
					selectValue: 'lr_image_text'
				},
				dotPositionList: [{
					name: '右边',
					value: 'right',
					checked: true
				}, {
					name: '下边',
					value: 'bottom',
					checked: false
				}],
				/** 是否接收更新提醒（服务端为准；本地设置里也留一份用于离线显示） */
				pushEnabled: true,
				/** 服务端是否已返回过推送状态（决定用哪一份值） */
				pushStatusLoaded: false
			};
		},

		watch: {
			appSettings: {
				deep: true,
				handler() {
					if (this.firstLoad) {
						this.firstLoad = false;
					} else {
						this.isSaved = false;
					}
				}
			}
		},

		onLoad(e) {
			this.fnSetPageTitle('应用设置');
		},
		created() {
			this.appSettings = Object.assign({}, _DefaultAppSettings, uni.$tm.vx.getters().getSettings);
			// 上面是浅合并：老版本存下来的设置里没有 tabbar 字段，会把默认值覆盖成 undefined，
			// 模板读 appSettings.tabbar.show[...] 就会抛错、整页白掉，所以新增的嵌套字段要单独补一层。
			const storedTabbar = this.appSettings.tabbar || {};
			this.appSettings.tabbar = Object.assign({}, _DefaultAppSettings.tabbar, storedTabbar, {
				show: Object.assign({}, _DefaultAppSettings.tabbar.show, storedTabbar.show || {})
			});
			this.fnHandleFormatSelect();
			this.fnLoadPushStatus();
			uni.showLoading({
				title: '加载中...',
				mask: true
			});
			setTimeout(() => {
				this.loading = false;
				uni.hideLoading();
			}, 500);
		},

		computed: {
			/**
			 * 是否显示「接收更新提醒」开关。
			 *
			 * 只在站长开启了消息推送（且模板已配置）时出现——没开这个功能时露出开关，
			 * 用户点了也收不到消息，只会以为功能坏了。
			 */
			calcPushAvailable() {
				const push = this.haloConfig?.pushConfig;
				return push?.enabled === true && !!push?.templateId;
			},
			/** 站长是否开启了可配置底部导航：没开或服务端没下发候选清单时，整块不展示（改了也没意义） */
			calcTabbarEnabled() {
				return this.haloConfig?.tabbarConfig?.enabled === true && this.calcTabbarToggles.length > 0;
			},
			/** 用户可以自己勾选的可选项（首页与「我的」是固定项，不给开关） */
			calcTabbarToggles() {
				return getToggleableCandidates(this.haloConfig);
			},
			/** 当前已勾选的可选项个数：需介于 1~3 之间（固定项占 2 席，总数即 3~5）。
				按「站长允许勾选的项」计数——残留的偏好（站长已关掉的项）不计入，
				否则上下限守卫会被看不见的开关顶满。 */
			calcTabbarPickedCount() {
				return countUserPicked(getToggleableCandidates(this.haloConfig), this.appSettings.tabbar);
			},
			/** 首页布局的说明跟着选中项走：光看「一行一列 / 一行两列」看不出区别在哪 */
			/** 外观提示：跟随系统时把「当前系统是深是浅」说出来，用户才知道为什么现在是这个配色 */
			appearanceHint() {
				if (appearanceState.mode !== 'auto') {
					return '不跟随系统，界面固定用这个明暗';
				}
				return appearanceState.systemDark
					? '跟随系统（当前系统：深色）'
					: '跟随系统（当前系统：浅色）';
			},
			homeLayoutHint() {
				return this.appSettings?.layout?.home === 'h_row_col2'
					? '一行两列：一屏看得更多'
					: '一行一列：每篇的信息更完整';
			}
		},

		methods: {
			/**
			 * 读取服务端的推送开关。
			 *
			 * 以服务端为准：它决定要不要真的给你发消息，本地那份只是显示用的缓存。
			 * 未登录或没开通时不请求（服务端会返回未开通，没必要多打一次接口）。
			 */
			async fnLoadPushStatus() {
				if (!this.calcPushAvailable || !checkHasWxLogin()) {
					return;
				}
				try {
					const res = await this.$httpApi.v2.getPushStatus();
					const body = res?.data || res || {};
					if (!body.error) {
						this.pushEnabled = body.userEnabled !== false;
						this.pushStatusLoaded = true;
					}
				} catch (err) {
					// 读不到就按本地那份显示，不打扰用户
				}
			},
			/** 切换「接收更新提醒」：先写服务端，失败则回滚 UI（不能让显示与事实相反） */
			async fnOnPushToggle(e) {
				// tm-switch 的 change 事件载荷固定是 { checked, value }：checked 才是开关状态，
				// value 是 name 自定义数据（不是布尔值），别拿它当状态用
				const next = !!(e && e.checked);
				const previous = this.pushEnabled;
				this.pushEnabled = next;
				if (!checkHasWxLogin()) {
					this.pushEnabled = previous;
					uni.$tm.toast('请先登录');
					return;
				}
				// 打开时先争一次微信授权：这时候用户刚点完开关，正是他愿意收提醒的时刻，
				// 不拿到授权的话这个开关是「虚」的——服务端没有额度可发，用户却以为已经开了
				if (next) {
					const push = getAppConfigs()?.pushConfig || {};
					if (push.enabled === true && push.templateId) {
						const result = await requestSubscribe(this.$httpApi.v2, [
							{ kind: 'newPost', tmplId: push.templateId }
						]);
						if (result.ok) {
							uni.$tm.toast('已开启，有更新时通知你');
						} else if (Array.isArray(result.banned) && result.banned.length > 0) {
							this.pushEnabled = previous;
							uni.$tm.toast('你已关闭订阅消息，请在小程序设置里重新打开');
							return;
						} else {
							// 用户这次没同意：开关仍然打开（服务端会记着），但先别报「已开启」
							uni.$tm.toast(result.error || '未获得提醒授权，可在「发现」页重新开启');
						}
					}
				}
				try {
					const res = await this.$httpApi.v2.setPushEnabled(next);
					const body = res?.data || res || {};
					if (body.error) {
						throw body;
					}
					this.pushEnabled = body.userEnabled !== false;
					if (!next) {
						uni.$tm.toast('已关闭（剩余提醒额度会保留）');
					}
				} catch (err) {
					this.pushEnabled = previous;
					uni.$tm.toast(err?.error || '设置失败，请稍后重试');
				}
			},
			// 统一处理选择框的内容
			fnHandleFormatSelect() {
				// 首页布局
				const _homeLayout = this.fnFindObjInList(this.homeLayout.list, 'value', this.appSettings.layout.home);
				this.homeLayout.selectDefault = [_homeLayout.name];
				this.homeLayout.selectLabel = _homeLayout.name;
				this.homeLayout.selectValue = _homeLayout.value;

				const _articleCardStyle = this.fnFindObjInList(this.articleCardStyle.list, 'value', this.appSettings.layout
					.cardType);
				this.articleCardStyle.selectDefault = [_articleCardStyle.name];
				this.articleCardStyle.selectLabel = _articleCardStyle.name;
				this.articleCardStyle.selectValue = _articleCardStyle.value;
			},
			// 在集合中找匹配的对象
			fnFindObjInList(list, key, value) {
				return list.find(x => x[key] == value);
			},
			fnOnBannerDotChange(e) {
				const _select = e[0];
				if (_select.index == 0 && _select.checked) {
					this.appSettings.banner.dotPosition = 'right';
				}
				if (_select.index == 1 && _select.checked) {
					this.appSettings.banner.dotPosition = 'bottom';
				}
			},
			// 首页布局
			/** 切换外观模式：立即生效（根类名与原生栏一起变），并存本地偏好 */
			fnOnAppearanceConfirm(e) {
				const _select = e[0].data;
				applyAppearanceMode(_select.value);
				// 外观模式不在 APP_GLOBAL_SETTINGS 里，走不了 store 的那个口子，单独同步一次
				pushAppearanceMode(_select.value);
				this.appearance.selectDefault = [_select.name];
				this.appearance.selectLabel = _select.name;
				this.appearance.selectValue = _select.value;
				uni.showToast({ title: `外观：${_select.name}`, icon: 'none' });
			},
			fnOnHomeLayoutConfirm(e) {
				const _select = e[0].data;
				this.homeLayout.selectDefault = [_select.name];
				this.homeLayout.selectLabel = _select.name;
				this.homeLayout.selectValue = _select.value;
				this.appSettings.layout.home = _select.value;
			},
			// 文章卡片样式
			fnOnArticleCardStyleConfirm(e) {
				const _select = e[0].data;
				this.articleCardStyle.selectDefault = [_select.name];
				this.articleCardStyle.selectLabel = _select.name;
				this.articleCardStyle.selectValue = _select.value;
				this.appSettings.layout.cardType = _select.value;
			},
			// 保存
			/**
			 * 勾选变化后守住 3~5 项：v-model 已经先写进去了，越界就改回来并提示，
			 * 而不是让用户存下一个原生底栏装不下的组合。
			 */
			fnOnTabbarToggle(item) {
				const count = this.calcTabbarPickedCount;
				if (count < TABBAR_MIN_OPTIONAL) {
					this.appSettings.tabbar.show[item.key] = true;
					uni.$tm.toast(`底栏至少显示 ${TABBAR_MIN_OPTIONAL + 2} 项：首页与「我的」固定，请再留一项`);
					return;
				}
				if (count > TABBAR_MAX_OPTIONAL) {
					this.appSettings.tabbar.show[item.key] = false;
					uni.$tm.toast(`底栏最多显示 ${TABBAR_MAX_OPTIONAL + 2} 项，请先隐藏其它项`);
				}
			},
			/** 按站长后台那份配置重置勾选（只改本页状态，点「保存设置」才生效） */
			fnOnTabbarResetToAdmin() {
				getTabbarCandidates(this.haloConfig).forEach((item) => {
					this.appSettings.tabbar.show[item.key] = item.adminOn === true;
				});
				uni.$tm.toast('已按站长配置重置，点「保存设置」生效');
			},
			fnOnSave() {
				this.isSaved = true;
				this.$tm.vx.commit('setting/setSettings', this.appSettings);
				uni.$tm.toast('保存成功，部分设置在重启后生效！');
			},
			// 使用默认配置
			fnOnSaveDefault() {
				uni.$eShowModal({
						title: '提示',
						content: '您确定要恢复为默认的设置吗？',
						showCancel: true,
						cancelText: '否',
						cancelColor: '#999999',
						confirmText: '是',
						confirmColor: '#2F54EB'
					})
					.then(res => {
						this.isSaved = true;
						uni.$tm.vx.actions('setting/updateDefaultAppSettings');
						uni.$tm.toast('系统设置已恢复为默认配置，部分设置在重启后生效！');
					})
					.catch(err => {});
			},
			fnOnBack() {
				if (this.isSaved) {
					uni.navigateBack();
					return;
				}
				uni.$eShowModal({
						title: '提示',
						content: '您当前可能有未保存的数据，确定返回吗？',
						showCancel: true,
						cancelText: '否',
						cancelColor: '#999999',
						confirmText: '是',
						confirmColor: '#2F54EB'
					})
					.then(res => {
						uni.navigateBack();
						this.isSaved = true;
					})
					.catch(err => {});
			}
		}
	};
</script>

<style scoped lang="scss">
	.app-page {
		box-sizing: border-box;
		/* 给固定在底部的操作栏留位置：栏高 ≈ 按钮 + 内边距，再加一点余量 */
		padding: 8rpx 0 200rpx;
	}

	/* ===== 分组 ===== */
	.group-head {
		padding: 28rpx 28rpx 10rpx;
	}

	.group-title-wrap {
		display: flex;
		align-items: center;
	}

	/* 品牌色小竖条：比整块灰底标题轻，也比纯文字有层级 */
	.group-bar {
		width: 8rpx;
		height: 30rpx;
		margin-right: 16rpx;
		border-radius: 4rpx;
		background-image: $uh-grad-accent;
	}

	.group-title {
		font-size: 30rpx;
		font-weight: 700;
		color: $uh-ink-1;
	}

	.group-desc {
		display: block;
		margin-top: 6rpx;
		font-size: 21rpx;
		color: $uh-ink-3;
	}

	.group-body {
		padding-bottom: 10rpx;
	}

	/* 选择项右侧的当前值 + 箭头（比原来的表单输入框轻，也更像「设置」） */
	.row-value {
		display: flex;
		align-items: center;
	}

	.row-value-text {
		font-size: 26rpx;
		color: $uh-ink-2;
	}

	.row-arrow {
		margin-left: 10rpx;
		font-size: 30rpx;
		line-height: 1;
		color: $uh-ink-4;
	}

	/* 选项横排的一整行（例如指示器位置）：挤在右侧会太窄 */
	.stack-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 20rpx 28rpx 26rpx;
		border-top: 1rpx solid $uh-line;
	}

	.stack-title {
		font-size: 28rpx;
		color: $uh-ink-1;
	}

	/* ===== 提示与按钮 ===== */
	.tip-box {
		margin: 6rpx 28rpx 26rpx;
		padding: 20rpx 22rpx;
		border-radius: $uh-radius-md;
		background: $uh-accent-weak;
	}

	.tip-text {
		display: block;
		font-size: 21rpx;
		line-height: 1.6;
		color: $uh-ink-2;
	}

	.tip-action {
		margin-top: 16rpx;
		align-self: flex-start;
	}

	.tip-action-text {
		padding: 8rpx 24rpx;
		border-radius: $uh-radius-pill;
		background: $uh-card;
		font-size: 22rpx;
		color: $uh-accent;
	}

	/* ===== 底部操作栏 ===== */
	.action-bar {
		position: fixed;
		left: 0;
		bottom: 0;
		width: 100vw;
		padding: 16rpx 24rpx;
		/* 有安全区时再往下垫一层；基础库不认 env() 时保留上一行，不会把内边距整个丢掉 */
		padding-bottom: calc(16rpx + env(safe-area-inset-bottom));
		background: $uh-card;
		box-shadow: $uh-shadow-md;
	}

	.unsaved-hint {
		display: block;
		padding-bottom: 10rpx;
		font-size: 21rpx;
		color: $uh-warning;
	}

	.action-row {
		display: flex;
		align-items: center;
		gap: 16rpx;
	}

	/* 保存是主操作，占最宽；另外两个退成描边，避免三个按钮平均分配注意力 */
	.action-item.is-minor {
		flex: 1;
	}

	.action-item.is-primary {
		flex: 1.6;
	}
</style>