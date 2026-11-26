<script setup>
import { adminMoney } from '../admin.js'

defineProps({ lines: { type: Array, required: true }, catalogLinks: Boolean })
</script>

<template>
  <ul class="order-lines">
    <li v-for="line in lines" :key="line.orderItemId">
      <div>
        <strong><RouterLink v-if="catalogLinks" :to="{ name: 'product', params: { productId: line.productId } }">{{ line.productName }}</RouterLink>
          <template v-else>{{ line.productName }}</template></strong>
        <p class="muted">{{ adminMoney(line.unitPrice) }} × {{ line.quantity }}</p>
      </div>
      <span>{{ adminMoney(line.lineAmount) }}</span>
    </li>
  </ul>
</template>
