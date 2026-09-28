<template>
	<view class="article-card " :class="cardType" @click="fnClickEvent('card')">
		<view class="left" v-if="calcShowArticleCover">
			<cache-image class="thumbnail" radius="6rpx" :url="$utils.checkThumbnailUrl(article.spec.cover)" :fileMd5="article.spec.cover" mode="aspectFill"></cache-image>
			<!-- <image class="thumbnail" lazy-load :src="$utils.checkThumbnailUrl(article.spec.cover)" mode="aspectFill"></image> -->
			<!-- 分类标识：浮在封面左上角，绝对定位，不占卡片任何布局空间 -->
			<text class="cat-badge" v-if="calcShowCategoryBadge">{{ categoryLabel }}</text>
		</view>
		<view class="right">
			<view class="title">
				<text class="is-top" v-if="article.spec.pinned">置顶</text>
				<!-- 没有封面（或卡片是纯文字）时退回标题行：高度压在标题行高之内，同样不撑高卡片 -->
				<text class="cat-badge-inline" v-if="calcShowCategoryInline">{{ categoryLabel }}</text>
				<text class="title-text text-overflow">{{ article.spec.title }}</text>
			</view>
			<view class="content text-overflow-2">{{ article.status.excerpt }}</view>
			<view class="foot">
				<view class="create-time">
					<text class="time-label">发布时间：</text>
					{{ { d: article.spec.publishTime, f: 'yyyy-MM-dd' } | formatTime }}
				</view>
				<view class="visits">
					浏览
					<text class="number">{{ article.stats.visit }}</text>
					次
				</view>
			</view>
		</view>
	</view>
</template>

<script>
	import tmTags from '@/tm-vuetify/components/tm-tags/tm-tags.vue';
	import CacheImage from '@/components/cache-image/cache-image.vue';
	export default {
		name: 'article-card',
		components: { tmTags, CacheImage },
		props: {
			from: {
				type: String,
				default: ''
			},
			article: {
				type: Object,
				default: () => {}
			},
			/**
			 * 是否显示分类标识。
			 *
			 * <p>默认关闭：分类详情页里每篇都属于同一个分类，挂个标签只是噪音；
			 * 首页与邀请落地页的列表是混合分类，需要它来回答「这篇是哪一类」。</p>
			 */
			showCategory: {
				type: Boolean,
				default: false
			}
		},
		computed: {
			// 应用设置：是否显示文章封面
			calcShowArticleCover() {
				return this.globalAppSettings?.articleCard?.showCover !== false;
			},
			/**
			 * 分类标识的文案。
			 *
			 * <p>列表接口的 categories 已被 {@code $utils.stripDataUnsafeList} 洗成
			 * {@code { name, displayName }}，一篇文章可能属于多个分类，只取第一个当主分类。</p>
			 */
			categoryLabel() {
				const categories = this.article?.categories;
				if (!Array.isArray(categories) || !categories.length) {
					return '';
				}
				return categories[0].displayName || '';
			},
			/** 卡片实际生效的类型（宫格模式下会被强制成 tb_image_text，不能只看设置项） */
			effectiveCardType() {
				const type = this.cardType;
				return Array.isArray(type) ? type[type.length - 1] : type;
			},
			/** 封面浮标：封面真的显示出来时才用（纯文字卡片里封面被 display:none 掉了） */
			calcShowCategoryBadge() {
				return this.showCategory && !!this.categoryLabel
					&& this.calcShowArticleCover && this.effectiveCardType !== 'only_text';
			},
			/** 标题行版本：封面没显示时的回退，两种形态互斥 */
			calcShowCategoryInline() {
				return this.showCategory && !!this.categoryLabel && !this.calcShowCategoryBadge;
			},
			cardType() {
				// tb_image_text=上图下文
				// tb_text_image=上文下图
				if (this.from == 'home' && this.globalAppSettings.layout.home == 'h_row_col2') {
					if (!['tb_image_text', 'tb_text_image', 'only_text'].some(x => x == this.globalAppSettings.layout.cardType)) {
						return [this.from, this.globalAppSettings.layout.home, 'tb_image_text'];
					}
					return [this.from, this.globalAppSettings.layout.home, this.globalAppSettings.layout.cardType];
				}
				return [this.globalAppSettings.layout.home, this.globalAppSettings.layout.cardType];
			}
		},
		methods: {
			fnClickEvent() {
				this.$emit('on-click', this.article);
			}
		}
	};
</script>

<style scoped lang="scss">
	.article-card {
		display: flex;
		box-sizing: border-box;
		margin: 0 24rpx;
		padding: 24rpx;
		border-radius: $uh-radius-lg;
		background-color: $uh-card;
		box-shadow: $uh-shadow-sm;
		overflow: hidden;
		margin-bottom: 24rpx;

		&.h_row_col1 {
			display: flex;
			align-items: center;
		}

		&.home {
			&.h_row_col2 {
				margin: 12rpx;

				.left {
					width: 100%;
					height: 200rpx;

					.thumbnail {

						::v-deep uni-image {
							border-radius: $uh-radius-sm !important;
						}
					}
				}

				.right {
					.title {
						display: flex;
						align-items: center;
						font-size: 26rpx;
						font-weight: bold;

						.is-top {
							height: 36rpx;
							margin-right: 10rpx;
							line-height: 36rpx;
							vertical-align: 4rpx;
							transform: scale(0.9);
						}
					}

					.foot {
						justify-content: space-between;

						.create-time {
							font-size: 24rpx;

							.time-label {
								display: none;
							}
						}

						.visits {
							font-size: 24rpx;
							margin-left: 0;
						}
					}
				}

				&.tb_text_image {
					padding: 12rpx;

					.left .thumbnail {
						::v-deep {
							uni-image {
								border-radius: 6rpx !important;
							}
						}
					}
				}

				&.only_text {
					padding: 24rpx;

					.right .foot {
						.create-time {
							.time-label {
								display: none;
							}
						}

						.visits {
							font-size: 24rpx;
						}
					}
				}
			}
		}

		&.lr_image_text {}

		&.lr_text_image {
			.left {
				order: 2;
				padding-left: 30rpx;
			}

			.right {
				order: 1;
				padding-left: 0;
			}
		}

		&.tb_image_text {
			flex-direction: column;
			padding: 24rpx;

			.left {
				width: 100%;
				height: 340rpx;

				.thumbnail {
					::v-deep uni-image {
						border-radius: $uh-radius-sm !important;
					}
				}
			}

			.right {
				padding-left: 0;
				padding: 24rpx 0;
				padding-bottom: 0;
				width: 100%;

				.foot {
					justify-content: flex-start;

					.create-time {
						.time-label {
							display: inline-block;
						}
					}

					.visits {
						margin-left: 24rpx;
					}
				}
			}
		}

		&.tb_text_image {
			flex-direction: column;

			.left {
				width: 100%;
				height: 340rpx;
				order: 2;
				margin-top: 24rpx;
			}

			.right {
				padding-left: 0;
				width: 100%;
				order: 1;

				.foot {
					justify-content: flex-start;

					.create-time {
						.time-label {
							display: inline-block;
						}
					}

					.visits {
						margin-left: 24rpx;
					}
				}
			}
		}

		&.only_text {
			padding: 36rpx;

			.left {
				display: none;
			}

			.right {
				padding-left: 0;

				.content {
					margin-top: 24rpx;
				}

				.foot {
					justify-content: flex-start;
					margin-top: 24rpx;

					.create-time {
						.time-label {
							display: inline-block;
						}
					}

					.visits {
						margin-left: 24rpx;
					}
				}
			}
		}

		.left {
			position: relative; // 分类浮标的定位基准
			width: 240rpx;
			height: 180rpx;

			.thumbnail {
				width: 100%;
				height: 100%;
				border-radius: $uh-radius-sm;
			}
		}

		// ── 分类标识 ──
		// 封面浮标：绝对定位，完全不参与布局，所以可以比标题行那个稍大一点、
		// 也就能在缩略图上看得清
		.cat-badge {
			position: absolute;
			left: 10rpx;
			top: 10rpx;
			z-index: 2;
			max-width: 70%;
			height: 34rpx;
			padding: 0 14rpx;
			line-height: 34rpx;
			font-size: 20rpx;
			color: #fff;
			background-color: rgba(0, 0, 0, 0.42);
			border-radius: $uh-radius-pill;
			white-space: nowrap;
			overflow: hidden;
			text-overflow: ellipsis;
		}

		// 标题行版本（无封面时）：高度必须小于标题行现有行高（列表 30rpx 字、宫格 26rpx 字），
		// 否则会把卡片撑高哪怕一两像素——「不改变卡片尺寸」是这次改动的硬要求
		.cat-badge-inline {
			flex-shrink: 0;
			max-width: 40%;
			height: 28rpx;
			padding: 0 10rpx;
			margin-right: 10rpx;
			line-height: 28rpx;
			font-size: 18rpx;
			color: $uh-accent;
			background-color: $uh-accent-weak;
			border-radius: $uh-radius-pill;
			white-space: nowrap;
			overflow: hidden;
			text-overflow: ellipsis;
		}

		.right {
			width: 0;
			flex-grow: 1;
			display: flex;
			flex-direction: column;
			padding-left: 30rpx;
			box-sizing: border-box;

			.title {
				display: flex;
				align-items: center;
				font-size: 30rpx;
				font-weight: 600;
				color: $uh-ink-1;

				.is-top {
					height: 40rpx;
					padding: 0 12rpx;
					margin-right: 10rpx;
					line-height: 40rpx;
					font-size: 22rpx;
					white-space: nowrap;
					vertical-align: 4rpx;
					color: #fff;
					background-image: $uh-grad-accent;
					border-radius: 8rpx;
					box-shadow: none !important;
				}

				&-text {
					color: $uh-ink-1;
				}
			}

			.content {
				display: -webkit-box;
				font-size: 26rpx;
				color: $uh-ink-3;
				height: 80rpx;
				margin-top: 14rpx;
				line-height: 42rpx;
			}

			.foot {
				display: flex;
				font-size: 24rpx;
				justify-content: space-between;
				align-items: center;
				color: $uh-ink-3;
				margin-top: 18rpx;

				.create-time {
					font-size: 26rpx;

					.time-label {
						display: none;
					}
				}

				.visits {
					.number {
						padding: 0 6rpx;
						font-size: 26rpx;
					}
				}
			}
		}
	}
</style>