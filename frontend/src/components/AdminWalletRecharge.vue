<script setup>
import { ref } from 'vue'
import { api } from '../api.js'
import { adminMoney, adminUsers } from '../admin.js'
import { useWorkspaceConfirm } from '../workspace.js'

const props = defineProps({ user: { type: Object, required: true } })
const amount = ref('')
const reason = ref('')
const pending = ref(null)
const receipt = ref(null)
const busy = ref(false)
const error = ref('')
const confirm = useWorkspaceConfirm()

async function submit() {
  if (busy.value) return
  error.value = ''
  receipt.value = null
  if (!pending.value) {
    if (!/^[0-9]{1,12}(\.[0-9]{1,2})?$/.test(amount.value) || Number(amount.value) <= 0 || !reason.value) {
      error.value = '请输入正数金额（最多两位小数）和充值原因。'
      return
    }
    if (!(await confirm(`确认向「${props.user.loginName}」充值 ${adminMoney(amount.value)}？`, {
      label: '确认充值', detail: '账号、金额和原因将写入充值审计。',
    }))) return
    pending.value = { amount: amount.value, reason: reason.value, requestKey: crypto.randomUUID() }
  }
  busy.value = true
  try {
    receipt.value = await api.post(`${adminUsers}/${props.user.id}/wallet/recharges`, pending.value)
    pending.value = null
    amount.value = ''
    reason.value = ''
  } catch (failure) {
    if (failure.status >= 400 && failure.status < 500) {
      pending.value = null
      error.value = failure.message
    } else {
      error.value = '充值结果尚未确认。请保留此页面并重试原请求。'
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <form class="admin-top-gap wallet-recharge" @submit.prevent="submit">
    <h3>钱包充值</h3>
    <div class="fields">
      <label>充值金额（元）<input v-model.trim="amount" required inputmode="decimal" maxlength="15" :disabled="!!pending"></label>
      <label>充值原因<input v-model.trim="reason" required maxlength="500" :disabled="!!pending"></label>
    </div>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>
    <p v-if="receipt" class="notice success" role="status">
      已入账 {{ adminMoney(receipt.amount) }}，流水 #{{ receipt.entryId }}，当前余额 {{ adminMoney(receipt.currentBalance) }}。
    </p>
    <div><button class="button button-outline" type="submit" :disabled="busy">
      {{ busy ? '正在处理…' : pending ? '重试原充值' : '检查并充值' }}
    </button></div>
  </form>
</template>
