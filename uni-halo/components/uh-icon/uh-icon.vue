<template>
	<image class="uh-icon" :src="iconSrc" :style="{ width: size + 'rpx', height: size + 'rpx' }" mode="aspectFit"></image>
</template>
<script>
import { effectiveSkinId } from '@/utils/skin.js';
export default {
	name: 'uh-icon',
	props: {
		name: { type: String, default: 'sparkles' },
		size: { type: Number, default: 44 },
		state: { type: String, default: '' }
	},
	computed: {
		iconSrc() {
			const id = effectiveSkinId();
            const dark = ['sky','mint','ink','sunrise'].includes(id) ? '--'+id : '';
			const state = this.state === 'active' ? '--active' : this.state === 'muted' ? '--muted' : '';
			return `/static/ui-icons/${this.name}${dark}${state}.png`;
		}
	}
};
</script>
<style scoped>
.uh-icon { display: inline-block; flex-shrink: 0; vertical-align: middle; }
</style>
