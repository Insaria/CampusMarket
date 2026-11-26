<script setup>
import { adminMoney } from '../admin.js'
import { orderStates } from '../finance.js'

defineProps({ orders: { type: Array, required: true } })
</script>

<template>
  <div class="admin-table-wrap admin-top-gap">
    <table class="admin-table checkout-orders">
      <thead><tr><th>订单编号</th><th>商家编号</th><th>订单金额</th><th>状态</th></tr></thead>
      <tbody>
        <tr v-for="order in orders" :key="order.orderId">
          <th scope="row"><RouterLink :to="{ name: 'order', params: { orderId: order.orderId } }">{{ order.orderNo }}</RouterLink></th>
          <td>#{{ order.sellerId }}</td>
          <td>{{ adminMoney(order.quotedAmount) }}</td>
          <td><span class="status-tag" :data-state="order.orderStatus">{{ orderStates[order.orderStatus] || order.orderStatus }}</span></td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
