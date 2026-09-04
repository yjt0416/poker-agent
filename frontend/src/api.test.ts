import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, enterTable } from './api'

const table = {
  tableId: 'table-1', version: 1, mode: 'PLAYER', status: 'IN_HAND', handNumber: 1,
  street: 'PREFLOP', pot: 150, buttonSeat: 0, actorSeat: 5, selfSeat: 5,
  blinds: { small: 50, big: 100 }, seats: [], board: [], holeCards: [],
  legalActions: { types: [], callAmount: 0, minRaiseTo: null, maxRaiseTo: 0 },
  actionLog: [], chat: [],
}

afterEach(() => vi.unstubAllGlobals())

describe('table entry', () => {
  it('deduplicates React StrictMode concurrent entry attempts', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: false, status: 401, json: async () => ({ message: 'expired' }) })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => table })
    vi.stubGlobal('fetch', fetchMock)

    const [first, second] = await Promise.all([enterTable(), enterTable()])

    expect(first.tableId).toBe('table-1')
    expect(second.tableId).toBe('table-1')
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls.filter(([path]) => path === '/api/tables')).toHaveLength(1)
  })

  it('turns a broken backend connection into an actionable Chinese error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    await expect(enterTable()).rejects.toEqual(
      new ApiError(0, '无法连接牌桌服务，请确认后端已启动'),
    )
  })
})
