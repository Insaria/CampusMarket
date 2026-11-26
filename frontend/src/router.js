import { createRouter, createWebHistory } from 'vue-router'
import { refreshSellerAccess, restoreSession, sellerHistoryAllowed, session } from './auth.js'
import HomeView from './views/HomeView.vue'
import LoginView from './views/LoginView.vue'
import RegisterView from './views/RegisterView.vue'
import SectionView from './views/SectionView.vue'
import CatalogView from './views/CatalogView.vue'
import ProductView from './views/ProductView.vue'
import SellerHomeView from './views/SellerHomeView.vue'
import SellerApplyView from './views/SellerApplyView.vue'
import SellerProductsView from './views/SellerProductsView.vue'
import SellerProductFormView from './views/SellerProductFormView.vue'
import AdminLayout from './views/admin/AdminLayout.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    { path: '/login', name: 'login', component: LoginView, meta: { guest: true } },
    { path: '/register', name: 'register', component: RegisterView, meta: { guest: true } },
    { path: '/categories', alias: '/products', name: 'categories', component: CatalogView },
    { path: '/products/sold', name: 'sold-products', component: () => import('./views/SoldProductsView.vue') },
    { path: '/products/:productId', name: 'product', component: ProductView },
    { path: '/cart', name: 'cart', component: () => import('./views/CartView.vue'), meta: { buyer: true } },
    { path: '/checkout', name: 'checkout', component: () => import('./views/CheckoutView.vue'), meta: { buyer: true } },
    { path: '/checkout/result/:batchId', name: 'checkout-result',
      component: () => import('./views/CheckoutResultView.vue'), meta: { buyer: true } },
    { path: '/checkout/pay/:batchId', name: 'checkout-pay',
      component: () => import('./views/CheckoutPayView.vue'), meta: { buyer: true } },
    { path: '/orders/pending', name: 'pending-payments', component: () => import('./views/PendingPaymentsView.vue'),
      meta: { buyer: true } },
    { path: '/orders', name: 'orders', component: () => import('./views/OrderListView.vue'), meta: { buyer: true } },
    { path: '/orders/:orderId', name: 'order', component: () => import('./views/OrderDetailView.vue'), meta: { buyer: true } },
    { path: '/orders/:orderId/return', name: 'apply-return', component: () => import('./views/ReturnApplyView.vue'),
      meta: { buyer: true } },
    { path: '/wallet', name: 'wallet', component: () => import('./views/FinanceLedgerView.vue'),
      props: { kind: 'wallet' }, meta: { buyer: true } },
    { path: '/points', name: 'points', component: () => import('./views/FinanceLedgerView.vue'),
      props: { kind: 'points' }, meta: { buyer: true } },
    { path: '/reputation', name: 'reputation', component: () => import('./views/ReputationView.vue'), meta: { buyer: true } },
    { path: '/notifications', name: 'notifications', component: () => import('./views/NotificationsView.vue'), meta: { login: true } },
    { path: '/product-messages/:messageId', name: 'product-messages',
      component: () => import('./views/ProductMessagesView.vue'), meta: { buyer: true } },
    { path: '/notifications/:notificationId', name: 'notification',
      component: () => import('./views/NotificationsView.vue'), meta: { login: true } },
    { path: '/account', name: 'account', component: () => import('./views/AccountView.vue'), meta: { login: true } },
    { path: '/seller/apply', name: 'seller-apply', component: SellerApplyView, meta: { seller: true } },
    { path: '/seller', component: () => import('./views/SellerLayout.vue'), meta: { seller: true }, children: [
      { path: '', name: 'seller', component: SellerHomeView },
      { path: 'products', name: 'seller-products', component: SellerProductsView, meta: { merchant: true } },
      { path: 'products/new', name: 'seller-product-new', component: SellerProductFormView, meta: { merchant: true } },
      { path: 'products/:productId/edit', name: 'seller-product-edit', component: SellerProductFormView, meta: { merchant: true } },
      { path: 'orders', name: 'seller-orders', component: () => import('./views/OrderListView.vue'),
        props: { seller: true }, meta: { sellerHistory: true } },
      { path: 'orders/:orderId', name: 'seller-order', component: () => import('./views/OrderDetailView.vue'),
        props: { seller: true }, meta: { sellerHistory: true } },
      { path: 'returns', name: 'seller-returns', component: () => import('./views/SellerReturnListView.vue'), meta: { sellerHistory: true } },
      { path: 'returns/:requestId', name: 'seller-return', component: () => import('./views/SellerReturnDetailView.vue'), meta: { sellerHistory: true } },
    ] },
    { path: '/admin', component: AdminLayout, meta: { access: 'ADMIN' }, children: [
      { path: '', name: 'admin', component: () => import('./views/admin/AdminHome.vue') },
      { path: 'users', name: 'admin-users', component: () => import('./views/admin/AdminUsers.vue') },
      { path: 'sellers', name: 'admin-sellers', component: () => import('./views/admin/AdminSellers.vue') },
      { path: 'seller-levels', name: 'admin-seller-levels', component: () => import('./views/admin/AdminSellerLevels.vue') },
      { path: 'products', name: 'admin-products', component: () => import('./views/admin/AdminProducts.vue') },
      { path: 'settlements', name: 'admin-settlements', component: () => import('./views/admin/AdminSettlements.vue') },
    ] },
    { path: '/:pathMatch(.*)*', component: SectionView, props: { title: '页面未找到' } },
  ],
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.name === 'account' && to.hash === '#favorites') return { el: '#favorites', top: 12 }
    if (to.name !== 'product' || to.path !== from.path) return { top: 0 }
  },
})

export function landingRoute() {
  if (session.access === 'ADMIN') return 'admin'
  if (sellerHistoryAllowed()) return 'seller'
  return 'account'
}

export function loginDestination(value) {
  if (typeof value === 'string' && /^\/(products\/|(?:seller|admin|account|cart|checkout|orders|wallet|points|reputation|notifications|product-messages)(?:[/?#]|$))/.test(value)) {
    const target = router.resolve(value)
    if (target.name === 'product' || (target.meta.seller && session.user?.roleCode === 'USER')) return value
    if (target.meta.access === 'ADMIN' && session.access === 'ADMIN') return value
    if (target.meta.buyer && session.user?.roleCode === 'USER') return value
    if (['account', 'notifications', 'notification'].includes(target.name)) return value
  }
  return { name: landingRoute() }
}

router.beforeEach(async (to, from) => {
  await restoreSession()
  if (!session.user && (to.meta.login || to.meta.access || to.meta.seller || to.meta.buyer)) {
    const preserve = to.meta.seller || to.meta.buyer || to.meta.access === 'ADMIN'
      || ['account', 'notifications', 'notification'].includes(to.name)
    return { name: 'login', query: preserve ? { redirect: to.fullPath } : {} }
  }
  if (session.user && to.meta.guest) return loginDestination(to.query.redirect)
  if (to.meta.buyer && session.user?.roleCode !== 'USER') return { name: landingRoute() }
  if (to.meta.seller && session.user.roleCode !== 'USER') return { name: landingRoute() }
  if ((to.meta.merchant || to.meta.sellerHistory) && (to.path !== from.path
      || (to.meta.merchant ? session.access !== 'MERCHANT' : !sellerHistoryAllowed()))) {
    try {
      await refreshSellerAccess()
      if (to.meta.merchant ? session.access === 'MERCHANT' : sellerHistoryAllowed()) return
    } catch {
      if (!session.user) return { name: 'login', query: { redirect: to.fullPath } }
    }
    return { name: 'seller' }
  }
  if (to.meta.access && to.meta.access !== session.access) return { name: 'account' }
})
