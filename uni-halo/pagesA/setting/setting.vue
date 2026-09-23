<template>
	<view class="app-page" :class="[skinClass, { 'is-balck grey-darken-6': isBlackTheme }]">
		<tm-form v-if="!loading" ref="formData">
			<!-- <tm-sheet :shadow="24" :padding="[12, 24]" :margin="[24, 24]">
					<view class="py-12 px-24 mx-12 round-3 border-b-1 grey text">
						<text class="text-size-n text-weight-b ">标题</text>
						<text class="text-grey text-size-xs px-10">副标题/描述</text>
					</view>
					<view class="sheet-content">内容区域</view>
				</tm-sheet> -->
			<tm-sheet :shadow="6" :padding="[12, 24]" :margin="[24, 24]">
				<view class="py-12 px-24 mx-12 round-3  grey text">
					<text class="text-size-n text-weight-b text-grey-darken-3">布局</text>
					<text class="text-grey text-size-xs px-10 ml-12">应用以及文章列表布局设置</text>
				</view>
				<view class="sheet-content">
					<tm-pickers title="请选择首页布局" btn-color="indigo" :default-value.sync="homeLayout.selectDefault"
						rang-key="name" :list="homeLayout.list" @confirm="fnOnHomeLayoutConfirm">
						<tm-input name="status" required title="首页文章布局" placeholder="请选择首页文章布局" disabled align="right"
							:value="homeLayout.selectLabel" right-icon="icon-angle-right"></tm-input>
					</tm-pickers>
					<tm-pickers title="请选择文章卡片样式" btn-color="indigo"
						:default-value.sync="articleCardStyle.selectDefault" rang-key="name"
						:list="articleCardStyle.list" @confirm="fnOnArticleCardStyleConfirm">
						<tm-input name="status" required title="文章卡片样式" placeholder="请选择文章卡片样式" disabled align="right"
							:value="articleCardStyle.selectLabel" right-icon="icon-angle-right"></tm-input>
					</tm-pickers>
				</view>
			</tm-sheet>

			<tm-sheet v-if="false" :shadow="24" :padding="[12, 24]" :margin="[24, 24]">
				<view class="py-12 px-24 mx-12 round-3 grey text">
					<text class="text-size-n text-weight-b ">外观</text>
					<text class="text-grey text-size-xs px-10">设置应用主题色/暗黑模式等</text>
				</view>
				<view class="sheet-content">
					<!-- 内容区域 -->
					<text class="ma-24">todo</text>
				</view>
			</tm-sheet>

			<tm-sheet :shadow="6" :padding="[12, 24]" :margin="[24, 24]">
				<view class="py-12 px-24 mx-12 round-3 grey text">
					<text class="text-size-n text-weight-b text-grey-darken-3">功能</text>
					<text class="text-grey text-size-xs px-10  ml-12">一些常用的功能性设置</text>
				</view>
				<view class="sheet-content">
						<!-- 	<view class="mx-32 mt-24 mb-0 border-b-1 pb-24 flex-between">
						<text class="text-size-m">启用评论弹幕</text>
						<tm-switch v-model="appSettings.barrage.use" color="indigo" :text="['是', '否']"></tm-switch>
					</view>
					<tm-pickers
						v-if="appSettings.barrage.use"
						title="评论弹幕位置"
						btn-color="indigo"
						:default-value.sync="barrage.selectDefault"
						rang-key="name"
						:list="barrage.list"
						@confirm="fnOnBarrageConfirm"
					>
						<tm-input
							name="status"
							title="评论弹幕位置"
							placeholder="请选择评论弹幕位置"
							disabled
							align="right"
							:value="barrage.selectLabel"
							right-icon="icon-angle-right"
						></tm-input>
					</tm-pickers> -->
				<view class="mx-32 my-24 border-b-1  pb-24 flex-between">
						<text class="text-size-m">显示文章封面</text>
						<tm-switch v-model="appSettings.articleCard.showCover" color="indigo"
							:text="['显示', '隐藏']"></tm-switch>
					</view>
					<view class="mx-32 my-24 border-b-1 pb-24 flex-between">
						<text class="text-size-m">显示文章详情统计</text>
						<tm-switch v-model="appSettings.articleDetail.showStats" color="indigo"
							:text="['显示', '隐藏']"></tm-switch>
					</view>
					<view class="mx-32 my-24 border-b-1 pb-24 flex-between">
						<text class="text-size-m">全局悬浮按钮</text>
						<tm-switch v-model="appSettings.flotbutton.show" color="indigo"
							:text="['显示', '隐藏']"></tm-switch>
					</view>
					<view class="mx-32 my-24 border-b-1  pb-24 flex-between">
						<text class="text-size-m ">轮播图指示器</text>
						<tm-switch v-model="appSettings.banner.useDot" color="indigo"
							:text="['是', '否']"></tm-switch>
					</view>
					<view v-if="appSettings.banner.useDot" class="mx-32 my-24 border-b-1  pb-24 flex-between">
						<text class="text-size-m ">轮播图指示器位置</text>
						<tm-groupradio name="dotPosition" @change="fnOnBannerDotChange">
							<tm-radio :name="item.name" :size="28" color="indigo"
								v-for="(item, index) in dotPositionList" :key="index" v-model="item.checked"
								:label="item.name"></tm-radio>
						</tm-groupradio>
					</view>

					<!--
						底部导航：站长在插件后台「底部导航（TabBar）」里定好默认显示哪几项，
						用户可以在下面按自己的习惯再调。固定项（首页/我的）不提供开关；
						总数限制在 3~5 项（微信原生底栏最多 5 项；最少 3 项是本站的口径，可调）。
					-->
					<view v-if="calcTabbarEnabled" class="mx-32 my-24 border-b-1 pb-24 flex-between">
						<text class="text-size-m">自定义底部导航</text>
						<tm-switch v-model="appSettings.tabbar.useCustom" color="indigo"
							:text="['是', '否']"></tm-switch>
					</view>
					<block v-if="calcTabbarEnabled && appSettings.tabbar.useCustom">
						<view v-for="(item, index) in calcTabbarToggles" :key="item.key"
							class="mx-32 my-24 border-b-1 pb-24 flex-between">
							<text class="text-size-m">显示「{{ item.label }}」</text>
							<tm-switch v-model="appSettings.tabbar.show[item.key]" color="indigo"
								:text="['显示', '隐藏']" @change="fnOnTabbarToggle(item)"></tm-switch>
						</view>
						<text class="setting-tabbar-tip">
							「首页」与「我的」固定显示；其余项随意增减，但底栏总数需保持在 3~5 项之间
						</text>
						<view class="mx-32 my-24">
							<tm-button size="m" plan theme="indigo" :fllowTheme="false" :shadow="0"
								@click="fnOnTabbarResetToAdmin">按站长配置恢复</tm-button>
						</view>
					</block>

					<!-- 	<view class="mx-32 my-24 border-b-1  pb-24 flex-between">
						<text class="text-size-m">显示完整统计</text>
						<tm-switch v-model="appSettings.about.showAllCount" color="indigo" :text="['是', '否']"></tm-switch>
					</view>
					<view class="mx-32 my-24 border-b-1  pb-24 flex-between">
						<text class="text-size-m ">链接直接打开</text>
						<tm-switch v-model="appSettings.contact.isLinkCopy" color="indigo" :text="['是', '否']"></tm-switch>
					</view>
					<view v-if="false" class="mx-32 my-24 border-b-1  pb-24 flex-between">
						<text class="text-size-m ">显示后台入口</text>
						<tm-switch v-model="appSettings.about.showAdmin" color="indigo" :text="['是', '否']"></tm-switch>
					</view> -->
				</view>
			</tm-sheet>
		</tm-form>

		<!-- 操作区域 -->
		<view v-if="!loading" class="btn-bar flex flex-center bg-white">
			<!-- 保存为主操作，跟随主题皮肤变色；恢复默认（危险）与返回需保持语义色 -->
			<tm-button theme="indigo" :shadow="0" block :height="70" @click="fnOnSave()">保存设置</tm-button>
			<tm-button theme="red" :fllowTheme="false" :shadow="0" block :height="70" @click="fnOnSaveDefault()">恢复默认设置</tm-button>
			<tm-button theme="blue-grey" :fllowTheme="false" :shadow="0" block :height="70" @click="fnOnBack()">返回</tm-button>
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
	import tmButton from '@/tm-vuetify/components/tm-button/tm-button.vue';
	import tmForm from '@/tm-vuetify/components/tm-form/tm-form.vue';
	import tmPickers from '@/tm-vuetify/components/tm-pickers/tm-pickers.vue';
	import tmInput from '@/tm-vuetify/components/tm-input/tm-input.vue';
	import tmSwitch from '@/tm-vuetify/components/tm-switch/tm-switch.vue';
	import tmSheet from '@/tm-vuetify/components/tm-sheet/tm-sheet.vue';
	import tmGroupradio from '@/tm-vuetify/components/tm-groupradio/tm-groupradio.vue';
	import tmRadio from '@/tm-vuetify/components/tm-radio/tm-radio.vue';

	export default {
		components: {
			tmButton,
			tmForm,
			tmPickers,
			tmInput,
			tmSwitch,
			tmSheet,
			tmGroupradio,
			tmRadio
		},
		data() {
			return {
				isBlackTheme: false,
				loading: true,
				appSettings: {},
				isSaved: true,
				firstLoad: true,
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
				barrage: {
					list: [{
						name: '顶部',
						value: 'rightToLeft'
					}, {
						name: '左下',
						value: 'leftBottom'
					}],
					selectDefault: ['顶部'],
					selectLabel: '顶部',
					selectValue: 'rightToLeft'
				}
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
			/** 站长是否开启了可配置底部导航：没开或服务端没下发候选清单时，整块不展示（改了也没意义） */
			calcTabbarEnabled() {
				return this.haloConfig?.tabbarConfig?.enabled === true && this.calcTabbarToggles.length > 0;
			},
			/** 用户可以自己勾选的可选项（首页与「我的」是固定项，不给开关） */
			calcTabbarToggles() {
				return getToggleableCandidates(this.haloConfig);
			},
			/** 当前已勾选的可选项个数：需介于 1~3 之间（固定项占 2 席，总数即 3~5） */
			calcTabbarPickedCount() {
				return countUserPicked(getTabbarCandidates(this.haloConfig), this.appSettings.tabbar);
			}
		},

		methods: {
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

				const _barrage = this.fnFindObjInList(this.barrage.list, 'value', this.appSettings.barrage.type);
				this.barrage.selectDefault = [_barrage.name];
				this.barrage.selectLabel = _barrage.name;
				this.barrage.selectValue = _barrage.value;
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
			// 弹幕设置
			fnOnBarrageConfirm(e) {
				const _select = e[0].data;
				this.barrage.selectDefault = [_select.name];
				this.barrage.selectLabel = _select.name;
				this.barrage.selectValue = _select.value;
				this.appSettings.barrage.type = _select.value;
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
		padding-bottom: 120rpx;

		.btn-bar {
			width: 100vw;
			position: fixed;
			left: 0;
			bottom: 0;
			box-sizing: border-box;
			padding: 24rpx;
			gap: 0 24rpx;
			box-shadow: $uh-shadow-sm;
		}
	}

	.required {
		position: relative;
		padding-left: 18rpx;

		&:before {
			content: '*';
			position: absolute;
			left: -4rpx;
			top: 50%;
			transform: translateY(-50%);
			font-size: 34rpx;
			color: rgba(244, 67, 54, 1);
		}
	}

	.setting-tabbar-tip {
		display: block;
		padding: 0 32rpx;
		font-size: 22rpx;
		line-height: 1.6;
		color: #9a9a94;
	}
</style>