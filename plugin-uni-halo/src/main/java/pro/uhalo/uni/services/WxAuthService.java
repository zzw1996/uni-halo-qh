package pro.uhalo.uni.services;



import org.springframework.http.codec.multipart.FilePart;

import pro.uhalo.uni.dto.BindHaloAccountByCodeRequest;

import pro.uhalo.uni.dto.SendHaloBindCodeRequest;

import pro.uhalo.uni.dto.SendHaloBindCodeResponse;

import pro.uhalo.uni.dto.UpdateProfileRequest;

import pro.uhalo.uni.dto.WxAppConfigDTO;

import pro.uhalo.uni.dto.WxAvatarUploadDTO;

import pro.uhalo.uni.dto.WxLoginRequest;

import pro.uhalo.uni.dto.WxUserDTO;

import reactor.core.publisher.Mono;



public interface WxAuthService {



    Mono<WxAppConfigDTO> getWxAppConfig();



    Mono<WxUserDTO> wxLogin(WxLoginRequest request);



    Mono<WxUserDTO> updateProfile(String openid, UpdateProfileRequest request);



    Mono<SendHaloBindCodeResponse> sendHaloBindCode(String openid, SendHaloBindCodeRequest request);



    Mono<WxUserDTO> bindHaloAccountByCode(String openid, BindHaloAccountByCodeRequest request);



    Mono<WxUserDTO> createHaloAccount(String openid);



    Mono<WxUserDTO> unbindHaloAccount(String openid);



    Mono<WxAvatarUploadDTO> uploadWxAvatar(String openid, FilePart filePart);

}

