import { definePlugin } from "@halo-dev/ui-shared";
import BiliCookieIcon from "./icons/BiliCookieIcon";
import CookieManageView from "./views/CookieManageView.vue";
import AdminLogsView from "./views/AdminLogsView.vue";

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: "Root",
      route: {
        path: "/bili-cookie-logs",
        name: "BiliCookieAdminLogs",
        component: AdminLogsView,
        meta: {
          title: "Cookie 管理日志",
          permissions: [],
          menu: {
            name: "Cookie 管理日志",
            group: "system",
            icon: BiliCookieIcon,
            priority: 10,
          },
        },
      },
    },
  ],
  ucRoutes: [
    {
      parentName: "Root",
      route: {
        path: "/bili-cookie",
        name: "BiliCookie",
        component: CookieManageView,
        meta: {
          title: "B站 Cookie",
          permissions: [],
          menu: {
            name: "B站 Cookie",
            group: "content",
            icon: BiliCookieIcon,
            priority: 50,
          },
        },
      },
    },
  ],
  extensionPoints: {},
});
