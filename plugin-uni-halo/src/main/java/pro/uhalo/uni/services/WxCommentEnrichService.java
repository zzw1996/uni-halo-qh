package pro.uhalo.uni.services;

import pro.uhalo.uni.scheme.WxUser;

public interface WxCommentEnrichService {

    byte[] enrichCommentBody(byte[] rawBody, WxUser wxUser, String openid);
}
