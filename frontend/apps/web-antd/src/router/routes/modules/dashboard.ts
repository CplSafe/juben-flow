import type { RouteRecordRaw } from 'vue-router';

import { $t } from '#/locales';

const routes: RouteRecordRaw[] = [
  { path: '/dashboard/:pathMatch(.*)*', redirect: '/bpm/task/create', meta: { hideInMenu: true } },
  {
    name: 'Profile',
    path: '/profile',
    component: () => import('#/views/_core/profile/index.vue'),
    meta: {
      icon: 'ant-design:profile-outlined',
      title: $t('ui.widgets.profile'),
      hideInMenu: true,
    },
  },
];

export default routes;
