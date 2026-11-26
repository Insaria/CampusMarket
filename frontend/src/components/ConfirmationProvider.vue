<script setup>
import { onBeforeUnmount, provide, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { workspaceConfirmation } from '../workspace.js'

const route = useRoute()
const dialog = ref(null)
const question = ref(null)
let settle

function finish(approved) {
  if (!settle) return
  const resolve = settle
  settle = null
  dialog.value?.close()
  resolve(approved)
}

provide(workspaceConfirmation, (title, { label = '确认操作', detail = '' } = {}) => {
  if (settle || !dialog.value) return Promise.resolve(false)
  question.value = { title, label, detail }
  return new Promise(resolve => {
    settle = resolve
    dialog.value.showModal()
  })
})

watch(() => route.fullPath, () => finish(false))
onBeforeUnmount(() => finish(false))
</script>

<template>
  <slot />
  <Teleport to="body">
    <dialog ref="dialog" class="workspace-confirm" aria-labelledby="workspace-confirm-title"
      @cancel.prevent="finish(false)" @close="!dialog?.open && finish(false)">
      <h2 id="workspace-confirm-title">{{ question?.title }}</h2>
      <p v-if="question?.detail" class="muted">{{ question.detail }}</p>
      <div class="actions">
        <button type="button" class="button button-outline" autofocus @click="finish(false)">取消</button>
        <button type="button" class="button" @click="finish(true)">{{ question?.label }}</button>
      </div>
    </dialog>
  </Teleport>
</template>
