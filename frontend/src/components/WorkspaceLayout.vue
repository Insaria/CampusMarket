<script setup>
import { useRoute } from 'vue-router'

defineProps({
  title: { type: String, required: true },
  home: { type: String, required: true },
  groups: { type: Array, required: true },
})

const route = useRoute()

function active(entry) {
  return route.name === entry.name || entry.pages?.includes(route.name)
}
</script>

<template>
  <div class="workspace-layout">
    <aside class="workspace-sidebar">
      <RouterLink class="workspace-brand" :to="{ name: home }">{{ title }}</RouterLink>
      <nav :aria-label="title + '导航'" class="workspace-nav">
        <section v-for="group in groups" :key="group.label || group.entries[0].name" class="workspace-nav-group">
          <p v-if="group.label">{{ group.label }}</p>
          <RouterLink v-for="entry in group.entries" :key="entry.name + (entry.query?.kind || '')"
            :to="{ name: entry.name, query: entry.query }" :class="{ 'workspace-current': active(entry) }"
            :aria-current="active(entry) ? 'page' : undefined">{{ entry.label }}</RouterLink>
        </section>
      </nav>
    </aside>
    <div class="workspace-main"><slot /></div>
  </div>
</template>
