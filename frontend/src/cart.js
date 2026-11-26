import { api } from './api.js'

const attempts = new Map()
let selection
let receipt

export function clearCartViews() {
  selection = undefined
  receipt = undefined
}

export function selectForCheckout(buyerId, productIds) {
  selection = { buyerId, productIds: [...productIds] }
}

export function selectedProducts(buyerId) {
  return selection?.buyerId === buyerId ? selection.productIds : []
}

export function rememberCheckout(buyerId, result) {
  receipt = { buyerId, result }
}

export function checkoutReceipt(buyerId, batchId) {
  if (receipt?.buyerId === buyerId && String(receipt.result.batchId) === batchId) return receipt.result
  return null
}

function storageKey(buyerId) {
  return `campus-checkout-attempt:${buyerId}`
}

export function pendingCheckout(buyerId) {
  if (attempts.has(buyerId)) return attempts.get(buyerId)
  try {
    const raw = sessionStorage.getItem(storageKey(buyerId))
    if (!raw) return null
    const restored = JSON.parse(raw)
    if (typeof restored?.requestKey !== 'string' || !Array.isArray(restored.items) || !restored.items.length) return null
    attempts.set(buyerId, restored)
    return restored
  } catch {
    return null
  }
}

export function setPendingCheckout(buyerId, attempt) {
  // Persist just the replayable request, without account or payment credentials.
  attempts.set(buyerId, attempt)
  try {
    if (attempt) sessionStorage.setItem(storageKey(buyerId), JSON.stringify(attempt))
    else sessionStorage.removeItem(storageKey(buyerId))
  } catch {
    // In-memory retry remains available when browser storage is disabled.
  }
}

export function cartQuantity(value) {
  const quantity = Number(value)
  return Number.isInteger(quantity) && quantity >= 1 && quantity <= 2147483647 ? quantity : null
}

export function setCartQuantity(id, quantity) {
  return api.put(`/cart/items/${id}`, { quantity })
}

export async function readCart(productIds, signal) {
  const cart = await api.get('/cart', { signal })
  const selected = productIds ? new Set(productIds) : null
  const rows = selected ? cart.filter(row => selected.has(row.productId)) : cart
  const missing = productIds?.filter(id => !rows.some(row => row.productId === id)) ?? []
  const lines = []
  // The cart DTO lacks visibility and image metadata. Check the catalog in bounded groups.
  for (let i = 0; i < rows.length; i += 6) {
    const checked = await Promise.all(rows.slice(i, i + 6).map(async row => {
      try {
        const product = await api.get(`/products/${row.productId}`, { signal })
        return {
          ...row, name: product.name, shopName: product.shopName, sellerId: product.sellerId,
          availableQuantity: product.availableQuantity, price: product.price, visible: true,
          issue: product.availableQuantity < row.quantity ? '可售库存不足，请调整数量。' : '',
        }
      } catch (failure) {
        if (signal?.aborted) throw failure
        if (failure.code === 'SHOP_SUSPENDED') return { ...row, issue: '店铺暂时关闭，商品不可购买。' }
        if (failure.code === 'PRODUCT_NOT_FOUND') return { ...row, issue: '商品已下架或不可购买。' }
        return { ...row, issue: '暂时无法核对商品状态，请刷新后重试。' }
      }
    }))
    lines.push(...checked)
  }
  return { lines, missing }
}

function cents(price) {
  const [whole, fraction = ''] = String(price).split('.')
  return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'))
}

export function lineAmount(line) {
  return cents(line.price) * BigInt(line.quantity)
}

export function formatCents(value) {
  return `¥${value / 100n}.${String(value % 100n).padStart(2, '0')}`
}

export function cartTotals(lines) {
  return lines.reduce((totals, line) => ({
    quantity: totals.quantity + line.quantity,
    amount: totals.amount + lineAmount(line),
  }), { quantity: 0, amount: 0n })
}

export function groupCartLines(lines) {
  const groups = new Map()
  for (const line of lines) {
    if (!groups.has(line.sellerId)) groups.set(line.sellerId, [])
    groups.get(line.sellerId).push(line)
  }
  return groups
}

export function checkoutEstimate(groups, choices) {
  let gross = 0n
  let discount = 0n
  for (const [sellerId, items] of groups) {
    const subtotal = cartTotals(items).amount
    const raw = String(choices[sellerId] ?? '').trim()
    if (raw && (!/^[0-9]+$/.test(raw) || !Number.isSafeInteger(Number(raw)))) return null
    // 100 points buy one yuan, so each group of 100 points is also 100 cents.
    const redeemed = BigInt(raw || '0')
    if (redeemed % 100n !== 0n || (redeemed > 0n && redeemed >= subtotal)) return null
    gross += subtotal
    discount += redeemed
  }
  return { discount, cash: gross - discount }
}

export function quoteMatches(previous, latest) {
  return previous.length === latest.length && previous.every(line => latest.some(row =>
    row.productId === line.productId && row.sellerId === line.sellerId
      && row.quantity === line.quantity && cents(row.price) === cents(line.price)))
}
