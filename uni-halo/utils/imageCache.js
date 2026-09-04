/**
 * 图片缓存（仅 APP-PLUS 使用）
 */

function getImageCache(filePath, fileMd5) {
	const storageKey = 'IMAGE_CACHE_INFO_' + fileMd5;
	const cacheFileInfo = uni.getStorageSync(storageKey);
	if (cacheFileInfo) {
		return Promise.resolve(cacheFileInfo);
	}
	return new Promise((resolve) => {
		uni.downloadFile({
			url: filePath,
			success: (res) => {
				if (res.statusCode === 200) {
					uni.saveFile({
						tempFilePath: res.tempFilePath,
						success: (res2) => {
							uni.setStorageSync(storageKey, res2.savedFilePath);
							resolve(res2.savedFilePath);
						},
						fail: () => resolve(filePath)
					});
				} else {
					resolve(filePath);
				}
			},
			fail: () => resolve(filePath)
		});
	});
}

export default {
	getImageCache
};
