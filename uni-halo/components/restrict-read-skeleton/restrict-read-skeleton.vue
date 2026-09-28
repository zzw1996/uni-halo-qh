<script>
export default {
  name: 'restrict-read-skeleton',
  props: {
    loading: {
      type: Boolean,
      default: true
    },
    hover: {
      type: Boolean,
      default: false
    },
    buttonText: {
      type: String,
      default: '刷新'
    },
    buttonColor: {
      type: String,
      default: '#07c160'
    },
    buttonSize: {
      type: String,
      default: 'normal', // 'small', 'normal', 'large'
      validator: value => ['small', 'normal', 'large'].includes(value)
    },
    lines: {
      type: Number,
      default: 4,
      validator: value => value >= 1 && value <= 6
    },
    /**
     * 骨架条的两个颜色。
     *
     * <p>默认留空 = 用样式里的设计 Token（$uh-fill / $uh-line），深色皮肤下会跟着变暗。
     * 只有调用方显式传色才走内联渐变。之前调用方一律传浅灰（#f0f0f0 / #e0e0e0），
     * 而内联样式皮肤生成器换不到 —— 深色下「此处内容已隐藏」就是一块发亮的白条。</p>
     */
    skeletonColor: {
      type: String,
      default: ''
    },
    skeletonHighlight: {
      type: String,
      default: ''
    },
    animationDuration: {
      type: Number,
      default: 1.5
    },
    showButton: {
      type: Boolean,
      default: true
    },
    tipText: {
      type: String,
      default: '' // 默认不显示提示文字
    },
    tipColor: {
      type: String,
      default: '#666666' // 提示文字颜色
    },
    tipSize: {
      type: Number,
      default: 24 // 提示文字大小，单位rpx
    }
  },
  computed: {
    /**
     * 骨架条的渐变。
     *
     * <p>调用方没传颜色时返回空串：此时不写内联样式，交给样式表里的 Token 渐变
     * （$uh-fill → $uh-line），深色皮肤由皮肤生成器一并换掉。传了颜色才走内联。</p>
     */
    calcLineBackground() {
      if (!this.skeletonColor && !this.skeletonHighlight) {
        return '';
      }
      const base = this.skeletonColor || '#f0f0e9';
      const highlight = this.skeletonHighlight || '#e9e9e2';
      return `linear-gradient(90deg, ${base} 25%, ${highlight} 50%, ${base} 75%)`;
    }
  },
  methods: {
    handleRefresh() {
      this.$emit('refresh');
    },
    onTouchStart() {
      this.$emit('touchstart');
    },
    onTouchEnd() {
      this.$emit('touchend');
    }
  }
};
</script>

<template>
  <!-- 骨架屏容器 -->
  <view class="container">
    <!-- 骨架屏内容 -->
    <view class="skeleton" v-if="loading">
      <!-- 内容区域 -->
      <view class="skeleton-body">
        <view
            v-for="(item, index) in Array(lines).fill(0)"
            :key="index"
            class="skeleton-line"
            :class="{
            'short': index === lines - 2,
            'shorter': index === lines - 1
          }"
          :style="{
            background: calcLineBackground,
            animationDuration: `${animationDuration}s`
          }">
        </view>
      </view>
    </view>

    <!-- 实际内容 -->
    <view v-else>
      <slot></slot>
    </view>

    <!-- 提示文字和按钮容器 -->
    <view v-if="showButton" class="button-container">
      <!-- 提示文字 -->
      <text 
        v-if="tipText" 
        class="tip-text"
        :style="{
          color: tipColor,
          fontSize: tipSize + 'rpx'
        }"
      >{{ tipText }}</text>
      
      <!-- 按钮 -->
      <button
        class="overlay-button"
        :class="[buttonSize, { 'button-hover': hover }]"
        hover-class="none"
        @touchstart="onTouchStart"
        @touchend="onTouchEnd"
        @click="handleRefresh"
        :style="{ backgroundColor: buttonColor }"
      >
        {{ buttonText }}
      </button>
    </view>
  </view>
</template>

<style scoped lang="scss">
/* 容器样式 */
.container {
  position: relative;
  width: 100%;
  min-height: 200rpx;
  background-color: $uh-card;
  border-radius: 16rpx;
  overflow: hidden;
  box-shadow: $uh-shadow-sm;
  transition: all 0.3s ease;

  &:hover {
    box-shadow: $uh-shadow-sm;
  }
}

/* 骨架屏样式 */
.skeleton {
  width: 100%;
  height: 100%;
  padding: 30rpx;

  &-body {
    margin: 20rpx 0;
  }

  &-line {
    height: 32rpx;
    /* 默认渐变走 Token（深色皮肤下自动变暗）；调用方传色时会被内联样式覆盖 */
    background: linear-gradient(90deg, $uh-fill 25%, $uh-line 50%, $uh-fill 75%);
    background-size: 400% 100%;
    border-radius: 8rpx;
    margin-bottom: 20rpx;
    animation: skeleton-loading 1.5s ease infinite;

    &.short {
      width: 70%;
    }

    &.shorter {
      width: 50%;
    }
  }
}

/* 按钮容器样式 */
.button-container {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  display: flex;
  flex-direction: column;
  align-items: center;
  z-index: 2;
}

/* 提示文字样式 */
.tip-text {
  margin-bottom: 20rpx;
  text-align: center;
  line-height: 1.4;
}

/* 按钮样式 */
.overlay-button {
  position: relative; // 改为相对定位
  transform: none; // 移除transform
  color: white;
  border-radius: 50rpx;
  padding: 0 40rpx;
  height: 80rpx;
  line-height: 80rpx;
  font-size: 28rpx;
  box-shadow: $uh-shadow-md;
  transition: all 0.3s ease;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;

  &:active {
    transform: scale(0.95); // 简化active状态的transform
  }

  &.small {
    height: 60rpx;
    line-height: 60rpx;
    font-size: 24rpx;
    padding: 0 30rpx;
  }

  &.large {
    height: 100rpx;
    line-height: 100rpx;
    font-size: 32rpx;
    padding: 0 50rpx;
  }
}

.button-hover {
  transform: translateY(-4rpx); // 简化hover状态的transform
  box-shadow: $uh-shadow-md;
}

/* 骨架屏动画 */
@keyframes skeleton-loading {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}
</style>
