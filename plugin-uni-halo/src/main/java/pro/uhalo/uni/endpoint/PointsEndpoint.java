package pro.uhalo.uni.endpoint;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import pro.uhalo.uni.constants.Constants;
import pro.uhalo.uni.controller.PointsController;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;

@Component
@RequiredArgsConstructor
public class PointsEndpoint implements CustomEndpoint {

    private final PointsController pointsController;

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion(Constants.PUBLIC_CUSTOM_API_GROUP_NAME);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final String apiTag = Constants.PUBLIC_CUSTOM_API_GROUP_NAME + "/Points";
        final String basePath = Constants.END_POINT_API_BASE_PATH;

        return SpringdocRouteBuilder.route()
            .GET(basePath + "/points/overview", pointsController::overview,
                builder -> builder
                    .operationId("GetPointsOverview")
                    .description("Get user points overview and sign-in status.")
                    .tag(apiTag))
            .GET(basePath + "/points/history", pointsController::history,
                builder -> builder
                    .operationId("ListPointsHistory")
                    .description("List user points history with pagination, optionally filtered by action.")
                    .tag(apiTag)
                    .parameter(parameterBuilder()
                        .name("action")
                        .in(ParameterIn.QUERY)
                        .description("Optional action filter, e.g. lucky_draw (lottery spending) or sign_in")
                        .required(false)
                        .implementation(String.class))
                    .parameter(parameterBuilder()
                        .name("page")
                        .in(ParameterIn.QUERY)
                        .description("Page index, starts from 0")
                        .required(false)
                        .implementation(Integer.class))
                    .parameter(parameterBuilder()
                        .name("size")
                        .in(ParameterIn.QUERY)
                        .description("Page size")
                        .required(false)
                        .implementation(Integer.class)))
            .GET(basePath + "/points/earn/status", pointsController::earnStatus,
                builder -> builder
                    .operationId("GetPointsEarnStatus")
                    .description("Check whether points can be earned for an article action today.")
                    .tag(apiTag)
                    .parameter(parameterBuilder()
                        .name("action")
                        .in(ParameterIn.QUERY)
                        .description("browse | comment | upvote")
                        .required(true)
                        .implementation(String.class))
                    .parameter(parameterBuilder()
                        .name("articleName")
                        .in(ParameterIn.QUERY)
                        .description("Article name")
                        .required(true)
                        .implementation(String.class)))
            .POST(basePath + "/points/earn", pointsController::earn,
                builder -> builder
                    .operationId("EarnPoints")
                    .description("Earn points by browse/comment/upvote action.")
                    .tag(apiTag))
            .POST(basePath + "/points/sign-in", pointsController::signIn,
                builder -> builder
                    .operationId("PointsSignIn")
                    .description("Daily sign-in to earn points.")
                    .tag(apiTag))
            .POST(basePath + "/points/new-user-gift", pointsController::newUserGift,
                builder -> builder
                    .operationId("ClaimNewUserGift")
                    .description("Claim one-time new-user gift points (idempotent).")
                    .tag(apiTag))
            .POST(basePath + "/points/draw", pointsController::draw,
                builder -> builder
                    .operationId("DrawLuckyPoints")
                    .description("Daily weighted draw with points cost, once per day.")
                    .tag(apiTag))
            .GET(basePath + "/points/draw/status", pointsController::drawStatus,
                builder -> builder
                    .operationId("GetDrawStatus")
                    .description("Check today's draw status without consuming anything.")
                    .tag(apiTag))
            .POST(basePath + "/points/exchange", pointsController::exchange,
                builder -> builder
                    .operationId("ExchangePointsItem")
                    .description("Exchange points for publish ticket / dashboard advanced.")
                    .tag(apiTag))
            .GET(basePath + "/points/exchange/balance", pointsController::exchangeStatus,
                builder -> builder
                    .operationId("GetExchangeBalance")
                    .description("Get exchange center balance and today's quota.")
                    .tag(apiTag))
            .GET(basePath + "/points/tasks/daily", pointsController::dailyTasks,
                builder -> builder
                    .operationId("GetDailyTasks")
                    .description("List today's daily tasks with progress.")
                    .tag(apiTag))
            .POST(basePath + "/points/tasks/claim", pointsController::claimTask,
                builder -> builder
                    .operationId("ClaimDailyTask")
                    .description("Claim today's reward for a completed daily task.")
                    .tag(apiTag))
            .GET(basePath + "/points/level", pointsController::levelStatus,
                builder -> builder
                    .operationId("GetLevelStatus")
                    .description("Get user level, worn title and title progress.")
                    .tag(apiTag))
            .POST(basePath + "/points/level/title", pointsController::wearTitle,
                builder -> builder
                    .operationId("WearLevelTitle")
                    .description("Wear an unlocked title.")
                    .tag(apiTag))
            .GET(basePath + "/points/outfit", pointsController::outfitStatus,
                builder -> builder
                    .operationId("GetOutfitStatus")
                    .description("Get outfit shop, owned items and active cosmetics.")
                    .tag(apiTag))
            .POST(basePath + "/points/outfit/buy", pointsController::buyOutfit,
                builder -> builder
                    .operationId("BuyOutfit")
                    .description("Buy an outfit item with points.")
                    .tag(apiTag))
            .POST(basePath + "/points/outfit/equip", pointsController::equipOutfit,
                builder -> builder
                    .operationId("EquipOutfit")
                    .description("Equip or unequip an outfit item.")
                    .tag(apiTag))
            .GET(basePath + "/points/invite/info", pointsController::inviteInfo,
                builder -> builder
                    .operationId("GetInviteInfo")
                    .description("Get invite code and reward config.")
                    .tag(apiTag))
            .POST(basePath + "/points/invite/poster/generate", pointsController::invitePosterGenerate,
                builder -> builder
                    .operationId("MarkInvitePosterGenerated")
                    .description("Report that the user generated the invite poster (for admin summary).")
                    .tag(apiTag))
            .POST(basePath + "/points/invite/poster/upload", pointsController::invitePosterUpload,
                builder -> builder
                    .operationId("UploadInvitePoster")
                    .description("Upload invite poster image to attachments (multipart field: file).")
                    .tag(apiTag))
            .GET(basePath + "/points/lucky/config", pointsController::luckyConfig,
                builder -> builder
                    .operationId("GetLuckyDrawConfig")
                    .description("Get lucky draw config for rule display.")
                    .tag(apiTag))
            .POST(basePath + "/points/lucky/draw", pointsController::luckyDraw,
                builder -> builder
                    .operationId("DrawLuckyGift")
                    .description("Draw once with points cost, weighted random prize.")
                    .tag(apiTag))
            .POST(basePath + "/points/lucky/submit", pointsController::luckySubmit,
                builder -> builder
                    .operationId("SubmitLuckyClaim")
                    .description("Submit contact info for a won physical prize.")
                    .tag(apiTag))
            .GET(basePath + "/points/lucky/mine", pointsController::luckyMine,
                builder -> builder
                    .operationId("GetMyLuckyClaims")
                    .description("List my lucky winning orders with status.")
                    .tag(apiTag))
            .GET(basePath + "/points/content/status", pointsController::contentStatus,
                builder -> builder
                    .operationId("GetContentLockStatus")
                    .description("Check whether an article is paid and unlocked for current user. "
                        + "Callable without login (returns locked state).")
                    .tag(apiTag)
                    .parameter(parameterBuilder()
                        .name("articleName")
                        .in(ParameterIn.QUERY)
                        .description("Article metadata.name (not slug)")
                        .required(true)
                        .implementation(String.class)))
            .POST(basePath + "/points/content/unlock", pointsController::contentUnlock,
                builder -> builder
                    .operationId("UnlockPaidContent")
                    .description("Unlock a paid article with reading tickets (idempotent).")
                    .tag(apiTag))
            .GET(basePath + "/points/content/tickets", pointsController::contentTickets,
                builder -> builder
                    .operationId("GetContentTicketBalance")
                    .description("Get current user's reading ticket balance.")
                    .tag(apiTag))
            .GET(basePath + "/points/membership", pointsController::membershipStatus,
                builder -> builder
                    .operationId("GetMembershipStatus")
                    .description("Get current user's membership status (single-purchase "
                        + "time-limited entitlement). Returns 'not a member' when anonymous.")
                    .tag(apiTag))
            .GET(basePath + "/points/content/unlocked", pointsController::unlockedArticles,
                builder -> builder
                    .operationId("ListUnlockedArticles")
                    .description("List paid articles the current user has unlocked, "
                        + "newest first, with the remaining ticket balance.")
                    .tag(apiTag)
                    .parameter(parameterBuilder()
                        .name("limit")
                        .in(ParameterIn.QUERY)
                        .description("Max items to return, default 100, max 200")
                        .required(false)
                        .implementation(Integer.class)))
            .build();
    }
}
