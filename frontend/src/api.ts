import type { PlayingCard } from './game-demo'

export type TableView = {
  tableId: string; version: number; sequence: number
  mode: 'PLAYER' | 'SPECTATOR'; status: 'IN_HAND' | 'BETWEEN_HANDS' | 'COMPLETE'
  handNumber: number; street: string; pot: number; buttonSeat: number; actorSeat: number | null; selfSeat: number
  canAdvance: boolean
  blinds: { small: number; big: number }
  seats: Array<{ seat: number; name: string; persona: string; sprite: number; stack: number
    streetCommitted: number; handCommitted: number; status: string; self: boolean; emotion?: string | null }>
  board: PlayingCard[]; holeCards: PlayingCard[]
  legalActions: { types: string[]; callAmount: number; minRaiseTo: number | null; maxRaiseTo: number }
  actionLog: Array<{ sequence: number; seat: number; name: string; action: string; summary: string }>
  chat: Array<{ seat: number; name: string; text: string; occurredAt: string }>
  rankings: Array<{ seat: number; name: string; sprite: number; stack: number; position: number | null }>
}
export type Persona = { key: string; name: string; species: string; tagline: string; aggression: number; bluffing: number; patience: number }
export type ReplayPage = { frames: TableView[]; nextSequence: number; hasMore: boolean }
export class ApiError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}

export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const controller = new AbortController()
  const abort = () => controller.abort()
  init?.signal?.addEventListener('abort', abort, { once: true })
  if (init?.signal?.aborted) controller.abort()
  const timeout = window.setTimeout(() => controller.abort(), 10_000)
  try {
    const response = await fetch(path, { credentials: 'include', ...init,
      headers: { 'Content-Type': 'application/json', 'X-Table-Client': 'web', ...init?.headers }, signal: controller.signal })
    const body = await response.json().catch(() => null)
    if (!response.ok) throw new ApiError(response.status, body?.message ?? `牌桌服务暂时不可用（HTTP ${response.status}）`)
    if (body === null) throw new ApiError(0, '牌桌服务返回了无效数据')
    return body as T
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new ApiError(0, error instanceof DOMException && error.name === 'AbortError'
      ? '连接超时，正在核对最新牌局，请勿重复下注' : '无法连接牌桌服务，请确认后端已启动')
  } finally {
    window.clearTimeout(timeout)
    init?.signal?.removeEventListener('abort', abort)
  }
}

let pendingEntry: Promise<TableView | null> | undefined
export function enterTable(): Promise<TableView | null> {
  return pendingEntry ??= request<TableView>('/api/tables/current')
    .catch(error => { if (error instanceof ApiError && error.status === 401) return null; throw error })
    .finally(() => { pendingEntry = undefined })
}
export const getRoster = () => request<Persona[]>('/api/tables/roster')
export function createTable(mode: 'PLAYER' | 'SPECTATOR', displayName: string, personas: string[]) {
  return request<TableView>('/api/tables', { method: 'POST', body: JSON.stringify({ mode, displayName, personas }) })
}
export function command(view: TableView, endpoint: string, payload: object = {}, commandId: string = crypto.randomUUID()) {
  return request<TableView>(`/api/tables/current/${endpoint}`, { method: 'POST', body: JSON.stringify({
    ...payload, commandId, tableId: view.tableId, expectedVersion: view.version,
  }) })
}
export function getReplay(view: TableView, after = 0, signal?: AbortSignal) {
  return request<ReplayPage>(`/api/tables/current/replay?tableId=${encodeURIComponent(view.tableId)}&after=${after}`, { signal })
}

/** Reconnect with a durable cursor. Both live updates and catch-up frames use the same reducer. */
export function subscribeTable(view: TableView, apply: (view: TableView) => void, status: (online: boolean) => void) {
  let cursor = view.sequence
  let attempt = 0
  let closed = false
  let timer: number | undefined
  let stream: EventSource
  function disconnected() {
    if (closed) return
    stream?.close(); status(false)
    window.clearTimeout(timer)
    timer = window.setTimeout(connect, Math.min(15_000, 1000 * 2 ** attempt++))
  }
  function reconnect() { window.clearTimeout(timer); stream?.close(); connect() }
  function connect() {
    if (closed) return
    if (!navigator.onLine) { disconnected(); return }
    stream = new EventSource(`/api/tables/current/events?tableId=${encodeURIComponent(view.tableId)}&after=${cursor}`, { withCredentials: true })
    stream.onopen = () => { attempt = 0; status(true) }
    const receive = (event: MessageEvent<string>) => {
      try {
        const next = JSON.parse(event.data) as TableView
        if (next.tableId !== view.tableId || !Number.isSafeInteger(next.sequence)) return
        if (next.sequence >= cursor) { cursor = next.sequence; apply(next); status(true) }
      } catch { status(false) }
    }
    stream.addEventListener('table', receive as EventListener)
    stream.addEventListener('reset', receive as EventListener)
    stream.onerror = disconnected
  }
  window.addEventListener('offline', disconnected)
  window.addEventListener('online', reconnect)
  connect()
  return () => {
    closed = true; stream?.close(); window.clearTimeout(timer)
    window.removeEventListener('offline', disconnected)
    window.removeEventListener('online', reconnect)
  }
}
