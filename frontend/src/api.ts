import type { PlayingCard } from './game-demo'

export type TableView = {
  tableId: string
  version: number
  mode: 'PLAYER' | 'SPECTATOR'
  status: 'IN_HAND' | 'BETWEEN_HANDS' | 'COMPLETE'
  handNumber: number
  street: string
  pot: number
  buttonSeat: number
  actorSeat: number | null
  selfSeat: number
  blinds: { small: number; big: number }
  seats: Array<{
    seat: number
    name: string
    persona: string
    sprite: number
    stack: number
    streetCommitted: number
    handCommitted: number
    status: string
    self: boolean
  }>
  board: PlayingCard[]
  holeCards: PlayingCard[]
  legalActions: {
    types: string[]
    callAmount: number
    minRaiseTo: number | null
    maxRaiseTo: number
  }
  actionLog: Array<{
    sequence: number
    seat: number
    name: string
    action: string
    summary: string
  }>
  chat: Array<{
    seat: number
    name: string
    text: string
    occurredAt: string
  }>
}

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message)
  }
}

async function request(path: string, init?: RequestInit): Promise<TableView> {
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), 10_000)
  let response: Response
  try {
    response = await fetch(path, {
      credentials: 'include',
      ...init,
      headers: { 'Content-Type': 'application/json', ...init?.headers },
      signal: init?.signal ?? controller.signal,
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new ApiError(0, '连接牌桌超时，请检查后端服务后重试')
    }
    throw new ApiError(0, '无法连接牌桌服务，请确认后端已启动')
  } finally {
    window.clearTimeout(timeout)
  }
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { message?: string }
    throw new ApiError(response.status, body.message ?? `牌桌服务暂时不可用（HTTP ${response.status}）`)
  }
  return response.json() as Promise<TableView>
}

let pendingEntry: Promise<TableView> | undefined

export function enterTable(): Promise<TableView> {
  if (pendingEntry) return pendingEntry
  pendingEntry = enterTableOnce().finally(() => {
    pendingEntry = undefined
  })
  return pendingEntry
}

async function enterTableOnce(): Promise<TableView> {
  try {
    return await request('/api/tables/current')
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401) throw error
    return createTable('PLAYER')
  }
}

export function createTable(mode: 'PLAYER' | 'SPECTATOR') {
  return request('/api/tables', {
    method: 'POST',
    body: JSON.stringify({ displayName: '旅人', mode }),
  })
}

export function submitAction(type: string, amount?: number) {
  return request('/api/tables/current/actions', {
    method: 'POST',
    body: JSON.stringify({ type, amount }),
  })
}

export function submitTalk(text: string) {
  return request('/api/tables/current/chat', {
    method: 'POST',
    body: JSON.stringify({ text }),
  })
}

export function startNextHand() {
  return request('/api/tables/current/next-hand', { method: 'POST', body: '{}' })
}

export function advanceSpectator() {
  return request('/api/tables/current/advance', { method: 'POST', body: '{}' })
}
