import { StrictMode } from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '../api'
import { tableView } from '../test/fixtures'
import { Replay } from './Replay'

vi.mock('../api', async importOriginal => {
  const actual = await importOriginal<typeof import('../api')>()
  return { ...actual, getReplay: vi.fn() }
})

beforeEach(() => vi.clearAllMocks())
afterEach(cleanup)

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

describe('replay pagination', () => {
  it('continues playback across pages and shows the latest table talk', async () => {
    const first = tableView({ sequence: 1, actionLog: [], chat: [] })
    const second = tableView({ sequence: 2, actionLog: [], chat: [{seat:5,name:'旅人',text:'这句话应出现在复盘里',occurredAt:'2026-09-12T00:00:00Z'}] })
    vi.mocked(api.getReplay)
      .mockResolvedValueOnce({ frames: [first], nextSequence: 1, hasMore: true })
      .mockResolvedValueOnce({ frames: [second], nextSequence: 2, hasMore: false })

    render(<Replay table={first} onBack={() => {}} />)
    const play = await screen.findByRole('button', { name: '播放回放' })
    fireEvent.change(screen.getByRole('combobox', { name: '回放速度' }), { target: { value: '4' } })
    fireEvent.click(play)

    await waitFor(() => expect(api.getReplay).toHaveBeenNthCalledWith(2, expect.objectContaining({tableId:'table-1'}), 1, expect.any(AbortSignal)))
    await screen.findByText('这句话应出现在复盘里')
    await waitFor(() => expect(screen.getByText(/事件 2/)).toBeInTheDocument())
  })

  it('ignores an aborted StrictMode response that arrives after the replacement load', async () => {
    const first = tableView({ sequence: 1 })
    const second = tableView({ sequence: 2 })
    const stale = deferred<api.ReplayPage>()
    vi.mocked(api.getReplay)
      .mockReturnValueOnce(stale.promise)
      .mockResolvedValueOnce({ frames: [first, second], nextSequence: 2, hasMore: false })

    render(<StrictMode><Replay table={second} onBack={() => {}} /></StrictMode>)
    const timeline = await screen.findByRole('slider', { name: '回放时间轴' })
    await waitFor(() => expect(api.getReplay).toHaveBeenCalledTimes(2))
    expect(timeline).toHaveAttribute('max', '1')
    fireEvent.click(screen.getByRole('button', { name: '下一步' }))
    await screen.findByText(/事件 2/)

    await act(async () => {
      stale.resolve({ frames: [first], nextSequence: 1, hasMore: false })
      await stale.promise
    })
    expect(screen.getByText(/事件 2/)).toBeInTheDocument()
    expect(screen.getByRole('slider', { name: '回放时间轴' })).toHaveAttribute('max', '1')
  })

  it('stops bulk pagination when the replay unmounts', async () => {
    const first = tableView({ sequence: 1 })
    const second = tableView({ sequence: 2 })
    const pendingPage = deferred<api.ReplayPage>()
    vi.mocked(api.getReplay)
      .mockResolvedValueOnce({ frames: [first], nextSequence: 1, hasMore: true })
      .mockReturnValueOnce(pendingPage.promise)
      .mockResolvedValue({ frames: [tableView({ sequence: 3 })], nextSequence: 3, hasMore: false })

    const { unmount } = render(<Replay table={first} onBack={() => {}} />)
    fireEvent.click(await screen.findByRole('button', { name: '加载全部后续记录' }))
    await waitFor(() => expect(api.getReplay).toHaveBeenCalledTimes(2))
    unmount()

    await act(async () => {
      pendingPage.resolve({ frames: [second], nextSequence: 2, hasMore: true })
      await pendingPage.promise
      await Promise.resolve()
    })
    expect(api.getReplay).toHaveBeenCalledTimes(2)
  })

  it('loads the live tail when the same table sequence advances', async () => {
    const first = tableView({ sequence: 1 })
    const second = tableView({ sequence: 2 })
    vi.mocked(api.getReplay)
      .mockResolvedValueOnce({ frames: [first], nextSequence: 1, hasMore: false })
      .mockResolvedValueOnce({ frames: [second], nextSequence: 2, hasMore: false })

    const { rerender } = render(<Replay table={first} onBack={() => {}} />)
    await screen.findByRole('slider', { name: '回放时间轴' })
    rerender(<Replay table={second} onBack={() => {}} />)

    await waitFor(() => expect(api.getReplay).toHaveBeenNthCalledWith(2, expect.objectContaining({tableId:'table-1'}), 1, expect.any(AbortSignal)))
    await waitFor(() => expect(screen.getByRole('slider', { name: '回放时间轴' })).toHaveAttribute('max', '1'))
    fireEvent.click(screen.getByRole('button', { name: '下一步' }))
    await screen.findByText(/事件 2/)
  })
})
