import { inject } from 'vue'

export const workspaceConfirmation = Symbol('workspace confirmation')

export function useWorkspaceConfirm() {
  return inject(workspaceConfirmation, title => Promise.resolve(window.confirm(title)))
}
