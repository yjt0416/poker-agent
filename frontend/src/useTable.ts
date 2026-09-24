import { useCallback, useEffect, useRef, useState } from 'react'
import { command, createTable, enterTable, subscribeTable, type TableView } from './api'

export function useTable() {
  const [table, setTable] = useState<TableView | null>(null)
  const [pending, setPending] = useState(false)
  const [online, setOnline] = useState(false)
  const [notice, setNotice] = useState('正在查看牌桌会话……')
  const [waitingVersion, setWaitingVersion] = useState<number | null>(null)
  const inFlight = useRef(false)
  const epoch = useRef(0)
  const apply = useCallback((next: TableView) => setTable(current => {
    if (current && (next.tableId !== current.tableId || next.sequence < current.sequence)) return current
    return next
  }), [])
  useEffect(() => {
    let cancelled = false
    const generation = epoch.current
    enterTable().then(view => {
      if (!cancelled && generation === epoch.current) { setTable(view); setOnline(true); setNotice(view ? '已恢复牌桌' : '选好牌友，落座开局') }
    }).catch(error => { if (!cancelled) setNotice(error.message) })
    return () => { cancelled = true }
  }, [])
  useEffect(() => {
    if (!table) return
    return subscribeTable(table, apply, available => {
      setOnline(available)
      if (!available) setNotice('连接中断，正在按序号重连……')
      else setNotice('牌桌已连接，行动已同步')
    })
  }, [table?.tableId, apply])
  useEffect(() => {
    if (!table || online) return
    let cancelled = false
    let checking = false
    const generation = epoch.current
    // SSE cannot expose HTTP 401. Reconcile the cookie after reconnect failures.
    const timer = window.setInterval(async () => {
      if (checking) return
      checking = true
      try {
        const next = await enterTable()
        if (cancelled || generation !== epoch.current) return
        if (!next || next.tableId !== table.tableId) {
          setTable(next); setWaitingVersion(null); setOnline(true)
          setNotice(next ? '已恢复当前牌桌' : '会话已结束，请重新落座')
        } else apply(next)
      } catch { /* The stream and reconciliation each retry while offline. */ }
      finally { checking = false }
    }, 3000)
    return () => { cancelled = true; window.clearInterval(timer) }
  }, [table?.tableId, online, apply])
  useEffect(() => { if (table && waitingVersion !== null && table.version !== waitingVersion) setWaitingVersion(null) }, [table?.version, waitingVersion])

  async function refresh() {
    const generation = epoch.current
    try {
      const next = await enterTable()
      if (generation !== epoch.current) return
      if (next) setTable(current => !current || current.tableId !== next.tableId || next.sequence >= current.sequence ? next : current)
      else setTable(null)
      setOnline(true); setWaitingVersion(null); setNotice(next ? '已同步最新牌局' : '会话已结束，请重新落座')
    } catch (error) { if (generation === epoch.current) { setOnline(false); setNotice(error instanceof Error ? error.message : '同步失败') } }
  }
  async function start(mode: 'PLAYER' | 'SPECTATOR', name: string, personas: string[]) {
    if (inFlight.current) return false
    inFlight.current = true; setPending(true); epoch.current++
    try {
      const next = await createTable(mode, name, personas)
      setTable(next); setOnline(true); setWaitingVersion(null); setNotice('牌友已落座，祝你好运')
      return true
    } catch (error) { setNotice(error instanceof Error ? error.message : '开桌失败'); return false }
    finally { inFlight.current = false; setPending(false) }
  }
  async function send(endpoint: string, payload: object = {}) {
    if (!table || inFlight.current || !online) return false
    inFlight.current = true; setPending(true)
    if (endpoint === 'advance') setWaitingVersion(table.version)
    try {
      const next = await command(table, endpoint, payload)
      apply(next); setNotice(endpoint === 'chat' ? '发言已送达' : '行动已确认')
      return true
    } catch (error) {
      setWaitingVersion(null)
      const message = error instanceof Error ? error.message : '行动失败'
      await refresh()
      setNotice(message + '；已尝试同步最新牌局')
      return false
    } finally { inFlight.current = false; setPending(false) }
  }
  return { table, pending: pending || waitingVersion !== null, online, notice, start, send, refresh }
}
