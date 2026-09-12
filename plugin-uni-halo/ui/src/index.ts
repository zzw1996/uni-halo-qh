import { definePlugin } from "@halo-dev/console-shared";
import WxUsers from "./views/WxUsers.vue";
import UserActivities from "./views/UserActivities.vue";
import PointsRecords from "./views/PointsRecords.vue";
import LuckyWinnings from "./views/LuckyWinnings.vue";
import InviteFriends from "./views/InviteFriends.vue";
import AiDigest from "./views/AiDigest.vue";

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: "Root",
      route: {
        path: "/uni-halo/users",
        name: "UniHaloWxUsers",
        component: WxUsers,
        meta: {
          title: "小程序用户",
          searchable: true,
          permissions: ["plugin:plugin-uni-halo:users:view"],
          menu: { name: "小程序用户", group: "UniHalo", priority: 0 },
        },
      },
    },
    {
      parentName: "Root",
      route: {
        path: "/uni-halo/activities",
        name: "UniHaloUserActivities",
        component: UserActivities,
        meta: {
          title: "用户行为",
          searchable: true,
          permissions: ["plugin:plugin-uni-halo:users:view"],
          menu: { name: "用户行为", group: "UniHalo", priority: 1 },
        },
      },
    },
    {
      parentName: "Root",
      route: {
        path: "/uni-halo/points-records",
        name: "UniHaloPointsRecords",
        component: PointsRecords,
        meta: {
          title: "积分记录",
          searchable: true,
          permissions: ["plugin:plugin-uni-halo:users:view"],
          menu: { name: "积分记录", group: "UniHalo", priority: 2 },
        },
      },
    },
    {
      parentName: "Root",
      route: {
        path: "/uni-halo/lucky-winnings",
        name: "UniHaloLuckyWinnings",
        component: LuckyWinnings,
        meta: {
          title: "兑换礼品",
          searchable: true,
          permissions: ["plugin:plugin-uni-halo:users:view"],
          menu: { name: "兑换礼品", group: "UniHalo", priority: 3 },
        },
      },
    },
    {
      parentName: "Root",
      route: {
        path: "/uni-halo/invite-friends",
        name: "UniHaloInviteFriends",
        component: InviteFriends,
        meta: {
          title: "邀请好友",
          searchable: true,
          permissions: ["plugin:plugin-uni-halo:users:view"],
          menu: { name: "邀请好友", group: "UniHalo", priority: 4 },
        },
      },
    },
    {
      parentName: "Root",
      route: {
        path: "/uni-halo/ai-digest",
        name: "UniHaloAiDigest",
        component: AiDigest,
        meta: {
          title: "AI 日报",
          searchable: true,
          // 与其它页面共用同一个 UI 权限：新增权限串需要重新登录才会聚合到当前用户，
          // 复用已有的权限可以保证菜单立刻可见
          permissions: ["plugin:plugin-uni-halo:users:view"],
          menu: { name: "AI 日报", group: "UniHalo", priority: 5 },
        },
      },
    },
  ],
});
