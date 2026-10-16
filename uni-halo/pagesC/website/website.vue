<template>
    <view :class="skinClass" class="theme-page-pagesC-website-website app-page">
        <web-view :src="webUrl"></web-view>
    </view>
</template>

<script>
export default {
    data() {
        return {
            title: '',
            webUrl: ''
        };
    },
    onLoad(e) {
        const {title, url} = JSON.parse(e.data);
        this.webUrl = decodeURIComponent(url);
		console.log("webUrl" + this.webUrl)
        if (title) {
            this.fnSetPageTitle(title);
        }
    }
};
</script>

<style scoped lang="scss">
.app-page {
    width: 100vw;
}
@import '@/common/styles/redesign/pagesC-website-website-themes.scss';
</style>
