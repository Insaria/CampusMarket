<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api.js'
import { session } from '../auth.js'
import { percent } from '../catalog.js'
import { useFinancePage } from '../finance.js'
import PageNav from '../components/PageNav.vue'
import TradeReviewList from '../components/TradeReviewList.vue'

const { data, page, pages, loading, error, reload } = useFinancePage(() => '/users/me/trade-reviews')
const buyer = ref(null)
const buyerError = ref('')
const buyerLoading = ref(true)
const roles = { USER: '普通用户', MERCHANT: '卖家', SUSPENDED_SELLER: '卖家（店铺暂时关闭）' }
const cards = [
  { title: '买家信誉', stats: () => buyer.value },
  { title: '商家满意度', stats: () => data.value?.sellerSatisfaction },
]

async function loadBuyer() {
  buyerLoading.value = true
  buyerError.value = ''
  try {
    buyer.value = await api.get('/users/me/reputation')
  } catch (failure) {
    buyerError.value = failure.message
  } finally {
    buyerLoading.value = false
  }
}

onMounted(loadBuyer)
</script>

<template>
  <section class="seller-workspace trade-page">
    <RouterLink class="catalog-back" :to="{ name: 'account' }">← 个人中心</RouterLink>
    <header class="trade-panel-heading"><h1>我的信誉</h1><span class="status-tag">{{ roles[session.access] ?? session.user?.roleCode }}</span></header>
    <p class="muted">4 至 5 星计为好评。好评率以有星级的评价为分母，纯文字评价计入评价总数。</p>
    <p v-if="buyerLoading" role="status">正在读取买家信誉…</p>
    <p v-if="buyerError" class="notice error" role="alert">
      {{ buyerError }} <button class="text-button" type="button" @click="loadBuyer">重试</button>
    </p>
    <div class="trade-reputation-grid">
      <template v-for="card in cards" :key="card.title">
        <section v-if="card.stats()" class="seller-panel">
          <h2>{{ card.title }}</h2>
          <dl class="seller-metrics trade-metrics">
            <div><dt>好评率</dt><dd>{{ card.stats().positiveRate == null ? '暂无星级评价' : percent(card.stats().positiveRate) }}</dd></div>
            <div><dt>评价总数</dt><dd>{{ card.stats().reviewCount }}</dd></div>
            <div><dt>有星级评价</dt><dd>{{ card.stats().ratedCount }}</dd></div>
            <div><dt>平均星级</dt><dd>{{ card.stats().averageRating ?? '—' }}</dd></div>
          </dl>
        </section>
      </template>
    </div>
    <section class="seller-panel admin-top-gap">
      <h2>交易评价历史</h2>
      <p v-if="loading" role="status">正在查询评价…</p>
      <p v-else-if="error" class="notice error" role="alert">
        {{ error }} <button class="text-button" type="button" @click="reload++">重试</button>
      </p>
      <template v-else-if="data">
        <TradeReviewList :items="data.items" />
        <PageNav :page="page" :pages="pages" label="信誉评价分页" @change="page = $event" />
      </template>
    </section>
  </section>
</template>
