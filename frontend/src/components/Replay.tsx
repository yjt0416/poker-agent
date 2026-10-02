import { useCallback, useEffect, useRef, useState } from 'react'
import { getReplay, type TableView } from '../api'
import { PlayingCardView } from './PlayingCardView'
import { formatChips } from '../game-demo'

const streetLabels: Record<string, string> = {
  PREFLOP: '翻牌前', FLOP: '翻牌圈', TURN: '转牌圈', RIVER: '河牌圈',
  BETWEEN_HANDS: '手牌结束', COMPLETE: '锦标赛结束',
}
const seatLabels: Record<string, string> = {
  ACTIVE: '在局', FOLDED: '已弃牌', ALL_IN: '已全下', ELIMINATED: '已淘汰',
  FUNDED: '等待下一手', WINNER: '冠军',
}

export function Replay({ table, onBack }: { table: TableView; onBack: () => void }) {
  const [frames, setFrames] = useState<TableView[]>([])
  const [index, setIndex] = useState(0)
  const [playing, setPlaying] = useState(false)
  const [speed, setSpeed] = useState(1)
  const [more, setMore] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const framesRef = useRef<TableView[]>([])
  const tableRef = useRef(table)
  tableRef.current = table
  const generation = useRef(0)
  const checkedSequence = useRef(0)
  const pending = useRef(false)
  const controllerRef = useRef<AbortController | null>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const load = useCallback(async (all = false) => {
    if (pending.current) return
    pending.current = true
    const currentGeneration = generation.current
    const controller = new AbortController()
    controllerRef.current = controller
    const active = () => generation.current === currentGeneration && !controller.signal.aborted
    setBusy(true)
    try {
      let hasMore: boolean
      do {
        const cursor = framesRef.current.at(-1)?.sequence ?? 0
        const targetSequence = tableRef.current.sequence
        const page = await getReplay(tableRef.current, cursor, controller.signal)
        if (!active()) return
        checkedSequence.current = targetSequence
        const appended = page.frames.filter(frame => frame.sequence > cursor)
        framesRef.current = [...framesRef.current, ...appended]
        setFrames(framesRef.current)
        hasMore = page.hasMore && appended.length > 0
        setMore(hasMore)
        setError('')
      } while (all && hasMore && active())
    } catch (e) {
      if (!active()) return
      setPlaying(false)
      setError(e instanceof Error ? e.message : '回放读取失败')
    } finally {
      if (active()) { pending.current = false; setBusy(false) }
    }
  }, [])
  useEffect(() => {
    generation.current++
    pending.current = false
    framesRef.current = []
    checkedSequence.current = 0
    setFrames([]); setIndex(0); setPlaying(false); setMore(false); setError('')
    headingRef.current?.focus()
    void load()
    return () => { generation.current++; controllerRef.current?.abort() }
  }, [table.tableId, load])
  useEffect(() => {
    const last = frames.at(-1)?.sequence
    if (last !== undefined && table.sequence > last && table.sequence > checkedSequence.current && !more && !busy && !error) void load()
  }, [table.sequence, frames, more, busy, error, load])
  useEffect(() => {
    if (!playing) return
    if (index >= frames.length - 1) {
      if (more && !busy && !error) void load()
      else if (!more && !busy) setPlaying(false)
      return
    }
    const timer = window.setTimeout(() => setIndex(i => i + 1), 1000 / speed)
    return () => window.clearTimeout(timer)
  }, [playing, index, frames.length, speed, more, busy, error, load])
  const frame = frames[index]
  const hands = [...new Set(frames.map(f => f.handNumber))]
  const latestChat = frame?.chat.at(-1)
  return <section className="page-content replay-page"><button className="text-button" onClick={onBack}>← 返回当前牌桌</button>
    <span className="eyebrow">每一次试探，都有迹可循</span><h1 ref={headingRef} tabIndex={-1}>牌局回放</h1>
    <p>保留原会话权限：{table.mode === 'PLAYER' ? '仅展示你的底牌和公共信息' : '公共观战视角，隐藏所有底牌'}。回放操作不会影响正在进行的牌局。</p>
    {error && <p role="alert">{error}</p>}
    {frame && <>
      <div className="replay-summary"><strong>第 {frame.handNumber} 手 · {streetLabels[frame.street] ?? '牌局进行中'}</strong><span>底池 {formatChips(frame.pot)} · 事件 {frame.sequence}</span></div>
      <div className="replay-board">{frame.board.map((card, i) => <PlayingCardView card={card} key={i} />)}{Array.from({length:5-frame.board.length},(_,i)=><PlayingCardView hidden key={`h${i}`} />)}</div>
      {frame.holeCards.length > 0 && <div className="replay-own"><span>你的底牌</span>{frame.holeCards.map((card,i)=><PlayingCardView card={card} key={i} />)}</div>}
      <div className="replay-seats">{frame.seats.map(s => <div key={s.seat}><b>{s.name}</b><span>{formatChips(s.stack)}</span><small>{seatLabels[s.status] ?? '在桌'}</small></div>)}</div>
      <div className="replay-event" aria-live="polite">{frame.actionLog.at(-1)?.name} · {frame.actionLog.at(-1)?.action ?? '盲注已下，牌局开始'}<p>{frame.actionLog.at(-1)?.summary}</p></div>
      {latestChat && <div className="replay-chat"><strong>最近发言 · {latestChat.name}</strong><span>{latestChat.text}</span></div>}
      <input className="timeline" type="range" aria-label="回放时间轴" min={0} max={Math.max(0,frames.length-1)} value={index} onChange={e=>{setPlaying(false);setIndex(Number(e.target.value))}} />
      <div className="replay-controls"><button disabled={index===0} onClick={()=>{setPlaying(false);setIndex(i=>i-1)}}>上一步</button><button disabled={busy || (index===frames.length-1 && !more)} onClick={()=>setPlaying(p=>!p)}>{playing?'暂停回放':'播放回放'}</button><button disabled={index===frames.length-1} onClick={()=>{setPlaying(false);setIndex(i=>i+1)}}>下一步</button>
        <label>速度 <select aria-label="回放速度" value={speed} onChange={e=>setSpeed(Number(e.target.value))}>{[.5,1,2,4].map(s=><option key={s} value={s}>{s}×</option>)}</select></label>
        <label>手牌 <select aria-label="跳转手牌" value={frame.handNumber} onChange={e=>{setPlaying(false);setIndex(frames.findIndex(f=>f.handNumber===Number(e.target.value)))}}>{hands.map(h=><option value={h} key={h}>第 {h} 手</option>)}</select></label>
      </div>
    </>}
    {busy && <p role="status">正在读取已提交的牌局记录……</p>}
    {(more || error) && <button className="primary-button" disabled={busy} onClick={()=>void load(true)}>{error?'重试并加载剩余记录':'加载全部后续记录'}</button>}
  </section>
}
