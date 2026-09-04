import { useEffect, useMemo, useRef, useState } from 'react'
import { advanceSpectator, createTable, enterTable, startNextHand, submitAction, submitTalk, type TableView } from './api'
import { AgentSeatView } from './components/AgentSeatView'
import { Avatar } from './components/Avatar'
import { PlayingCardView } from './components/PlayingCardView'
import {
  communityCards,
  formatChips,
  holeCards,
  initialAgents,
  initialLogs,
  nextDemoHand,
  type AgentSeat,
  type PlayingCard,
  type TableLog,
} from './game-demo'

type PlayerAction = '弃牌' | '过牌' | '跟注' | '加注' | '全下'

const aiResponses = [
  { id: 'vesper', action: '跟注 300', line: '“行，这个故事我跟着听一段。”', tone: 'blue' as const },
  { id: 'hogarth', action: '加注至 600', line: '“别绕弯子，咱们拿筹码说话。”', tone: 'red' as const },
  { id: 'mirelle', action: '弃牌', line: '“这笔账不划算，到此为止。”', tone: 'green' as const },
]

export function App() {
  const [agents, setAgents] = useState<AgentSeat[]>(initialAgents)
  const [logs, setLogs] = useState<TableLog[]>(initialLogs)
  const [pot, setPot] = useState(600)
  const [stack, setStack] = useState(2500)
  const [raise, setRaise] = useState(300)
  const [chat, setChat] = useState('')
  const [thinking, setThinking] = useState(false)
  const [lastSpeech, setLastSpeech] = useState('“坐都坐下了，先喝口茶。牌桌上慢慢聊。”')
  const [lastSpeaker, setLastSpeaker] = useState({ name: '阿绯', sprite: 0 })
  const [hand, setHand] = useState(18)
  const [playerCards, setPlayerCards] = useState<PlayingCard[]>(holeCards)
  const [board, setBoard] = useState<PlayingCard[]>(communityCards)
  const [roundDone, setRoundDone] = useState(false)
  const [serverTable, setServerTable] = useState<TableView | null>(null)
  const [connection, setConnection] = useState<'connecting' | 'online' | 'demo'>('connecting')
  const [notice, setNotice] = useState('正在进入百兽茶馆……')
  const [autoPlay, setAutoPlay] = useState(false)
  const timers = useRef<number[]>([])

  useEffect(() => {
    let cancelled = false
    enterTable()
      .then((view) => {
        if (cancelled) return
        hydrate(view)
        setConnection('online')
        setNotice('Java 对局服务已连接')
      })
      .catch((error: unknown) => {
        if (cancelled) return
        setConnection('demo')
        setNotice(`演示模式 · ${error instanceof Error ? error.message : 'Java 后端未连接'}`)
      })
    return () => {
      cancelled = true
      timers.current.forEach(window.clearTimeout)
    }
  }, [])

  useEffect(() => {
    if (!autoPlay || serverTable?.mode !== 'SPECTATOR' || thinking) return
    const timer = window.setTimeout(() => {
      if (serverTable.status === 'IN_HAND') void advanceSpectatorTurn()
      else if (serverTable.status === 'BETWEEN_HANDS') void dealNextHand()
      else setAutoPlay(false)
    }, serverTable.status === 'IN_HAND' ? 850 : 1500)
    return () => window.clearTimeout(timer)
  }, [autoPlay, serverTable?.version, serverTable?.status, thinking])

  const activePlayers = useMemo(() => serverTable
    ? serverTable.seats.filter((seat) => !['FOLDED', 'OUT', 'ELIMINATED'].includes(seat.status)).length
    : agents.filter((agent) => !agent.folded).length + (roundDone ? 0 : 1), [agents, roundDone, serverTable])
  const handResult = useMemo(() => [...logs].reverse().find((log) => log.action === '本手结算'), [logs])
  const legalTypes = serverTable?.legalActions.types ?? ['FOLD', 'CALL', 'RAISE']
  const canCheck = legalTypes.includes('CHECK')
  const canCall = legalTypes.includes('CALL')
  const canRaise = legalTypes.includes('RAISE')
  const canAllIn = legalTypes.includes('ALL_IN')
  const callAmount = serverTable?.legalActions.callAmount ?? 150
  const maxRaise = serverTable?.legalActions.maxRaiseTo || stack
  const streetLabel = toStreetLabel(serverTable?.street ?? 'FLOP')

  function appendLog(name: string, action: string, detail: string, tone: TableLog['tone']) {
    setLogs((current) => [...current, { id: Date.now() + Math.random(), name, action, detail, tone }].slice(-8))
  }

  function resolveAiTurn(seed: number) {
    setThinking(true)
    setAgents((current) => current.map((agent, index) => ({ ...agent, thinking: index === seed % 3 })))
    const response = aiResponses[seed % aiResponses.length]
    const timer = window.setTimeout(() => {
      setThinking(false)
      setAgents((current) => current.map((agent) => ({
        ...agent,
        thinking: false,
        folded: agent.id === response.id && response.action === '弃牌' ? true : agent.folded,
      })))
      setLastSpeech(response.line)
      const speakingAgent = initialAgents.find((agent) => agent.id === response.id)
      setLastSpeaker({ name: speakingAgent?.name ?? '牌友', sprite: speakingAgent?.sprite ?? 0 })
      appendLog(speakingAgent?.name ?? '牌友', response.action, response.line, response.tone)
      setPot((current) => current + (response.action.includes('600') ? 600 : response.action.includes('300') ? 300 : 0))
      setRoundDone(true)
    }, 1100)
    timers.current.push(timer)
  }

  function hydrate(view: TableView) {
    const positions = ['seat-left', 'seat-top', 'seat-right-top', 'seat-right', 'seat-left-bottom', 'seat-bottom']
    setServerTable(view)
    setAgents(view.seats.filter((seat) => !seat.self).map((seat) => ({
      id: seat.persona,
      name: seat.name,
      subtitle: personaSubtitle(seat.persona),
      stack: seat.stack,
      bet: seat.streetCommitted,
      mood: seat.status,
      sprite: seat.sprite,
      seat: positions[seat.seat] ?? positions[0],
      folded: ['FOLDED', 'OUT', 'ELIMINATED'].includes(seat.status),
      dealer: seat.seat === view.buttonSeat,
    })))
    const self = view.seats.find((seat) => seat.self)
    setStack(self?.stack ?? 0)
    setPot(view.pot)
    setHand(view.handNumber)
    setPlayerCards(view.holeCards)
    setBoard(view.board)
    setRoundDone(view.status !== 'IN_HAND')
    setRaise(view.legalActions.minRaiseTo ?? view.legalActions.maxRaiseTo)
    setLogs(view.actionLog.map((entry) => ({
      id: entry.sequence,
      name: entry.name,
      action: entry.action,
      detail: entry.summary,
      tone: toneForAction(entry.action),
    })))
    const latestChat = view.chat.at(-1)
    const latestAction = view.actionLog.at(-1)
    if (latestChat) {
      setLastSpeech(`“${latestChat.text}”`)
      const speaker = view.seats.find((seat) => seat.name === latestChat.name)
      setLastSpeaker({ name: latestChat.name, sprite: speaker?.sprite ?? 7 })
    } else if (latestAction) {
      setLastSpeech(latestAction.summary)
      const speaker = view.seats.find((seat) => seat.name === latestAction.name)
      setLastSpeaker({ name: latestAction.name, sprite: speaker?.sprite ?? 0 })
    }
  }

  async function act(action: PlayerAction) {
    if (connection !== 'online') {
      actDemo(action)
      return
    }
    if (thinking || roundDone) return
    const type = action === '弃牌' ? 'FOLD'
      : action === '过牌' ? 'CHECK'
        : action === '跟注' ? 'CALL'
          : action === '全下' ? 'ALL_IN' : 'RAISE'
    setThinking(true)
    setNotice('对手正在分析牌局与发言……')
    try {
      const view = await submitAction(type, type === 'RAISE' ? raise : type === 'ALL_IN' ? maxRaise : undefined)
      hydrate(view)
      setNotice('Java 对局服务已连接')
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '行动提交失败')
    } finally {
      setThinking(false)
    }
  }

  function actDemo(action: PlayerAction) {
    if (thinking || roundDone) return
    let amount = 0
    let detail = chat.trim() ? `“${chat.trim()}”` : '你把目光投向牌桌中央。'
    if (action === '弃牌') {
      detail = chat.trim() ? `“${chat.trim()}” 你随后扣下手牌。` : '你扣下手牌，退出这一轮。'
      setRoundDone(true)
    } else if (action === '跟注') {
      amount = 150
    } else if (action === '加注') {
      amount = raise
    }
    setPot((current) => current + amount)
    setStack((current) => Math.max(0, current - amount))
    appendLog('你', action === '加注' ? `加注至 ${raise}` : action, detail, action === '弃牌' ? 'green' : 'gold')
    setChat('')
    if (action !== '弃牌') resolveAiTurn(logs.length)
  }

  async function sendTableTalk() {
    const value = chat.trim()
    if (!value || thinking) return
    if (connection === 'online') {
      setThinking(true)
      try {
        hydrate(await submitTalk(value))
        setChat('')
        setNotice('你的话已经传到整张牌桌')
      } catch (error) {
        setNotice(error instanceof Error ? error.message : '发言发送失败')
      } finally {
        setThinking(false)
      }
      return
    }
    appendLog('你', '牌桌发言', `“${value}”`, 'gold')
    setLastSpeech('“嘴上说得挺稳。可你推筹码的时候，手也得一样稳。”')
    setLastSpeaker({ name: '阿绯', sprite: 0 })
    setChat('')
  }

  async function dealNextHand() {
    if (connection === 'online') {
      setThinking(true)
      try {
        hydrate(await startNextHand())
        setNotice('新一手牌已经开始')
      } catch (error) {
        setNotice(error instanceof Error ? error.message : '无法开始下一手牌')
      } finally {
        setThinking(false)
      }
      return
    }
    const nextHand = hand + 1
    const next = nextDemoHand(nextHand)
    setHand(nextHand)
    setPlayerCards(next.cards)
    setBoard(next.board)
    setPot(300)
    setRaise(300)
    setRoundDone(false)
    setAgents(initialAgents.map((agent) => ({ ...agent, folded: false, bet: agent.id === 'hogarth' || agent.id === 'mirelle' ? 100 : 0 })))
    setLogs([{ id: Date.now(), name: '茶馆荷官', action: `第 ${nextHand} 手牌开始`, detail: '盲注已下，卡牌已发出。', tone: 'gold' }])
    setLastSpeech('“新一局，旧账不算。来，咱们重新过招。”')
    setLastSpeaker({ name: '豪哥', sprite: 1 })
  }

  async function advanceSpectatorTurn() {
    if (thinking || serverTable?.mode !== 'SPECTATOR' || serverTable.status !== 'IN_HAND') return
    setThinking(true)
    setNotice('Agent 正在推演下一步……')
    try {
      hydrate(await advanceSpectator())
      setNotice('AI 决策剧场 · 实时观战')
    } catch (error) {
      setAutoPlay(false)
      setNotice(error instanceof Error ? error.message : '无法推进牌局')
    } finally {
      setThinking(false)
    }
  }

  async function switchMode(mode: 'PLAYER' | 'SPECTATOR') {
    setThinking(true)
    setAutoPlay(false)
    setNotice(mode === 'PLAYER' ? '正在安排玩家牌桌……' : '正在点亮 AI 决策剧场……')
    try {
      hydrate(await createTable(mode))
      setConnection('online')
      setNotice(mode === 'PLAYER' ? 'Java 对局服务已连接' : 'AI 决策剧场 · 实时观战')
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '无法创建牌桌')
    } finally {
      setThinking(false)
    }
  }

  return (
    <main className={`app-shell ${serverTable?.mode === 'SPECTATOR' ? 'spectator-mode' : 'player-mode'}`}>
      <div className="ambient-lamp lamp-left" />
      <div className="ambient-lamp lamp-right" />

      <header className="topbar">
        <a className="brand" href="#table" aria-label="百兽茶馆首页">
          <span className="brand-mark">兽</span>
          <span><b>百兽茶馆</b><small>AGENT TAVERN</small></span>
        </a>
        <div className="table-title">
          <span className="eyebrow">戌时 · 临江厅 · 七号桌</span>
          <h1>{serverTable?.mode === 'SPECTATOR' ? 'AI 决策剧场' : '无上限德州扑克'}</h1>
        </div>
        <div className="header-actions">
          <span className={`connection connection-${connection}`}><i /> {notice}</span>
          <button className="icon-button" aria-label="游戏设置">⚙</button>
        </div>
      </header>

      <section className="game-layout" id="table">
        <aside className="side-panel left-panel">
          <PanelTitle icon="♜" title="本桌情报" subtitle="TABLE INTEL" />
          <dl className="stats-grid">
            <div><dt>牌局</dt><dd>第 {hand} 手牌</dd></div>
            <div><dt>阶段</dt><dd className="accent">{streetLabel}</dd></div>
            <div><dt>盲注</dt><dd>{serverTable?.blinds.small ?? 50} / {serverTable?.blinds.big ?? 100}</dd></div>
            <div><dt>在局</dt><dd>{activePlayers} / 6</dd></div>
          </dl>

          <div className="divider" />
          <span className="section-kicker">牌桌气氛</span>
          <div className="tension-meter"><span style={{ width: '68%' }} /></div>
          <div className="tension-copy"><b>暗流涌动</b><span>68%</span></div>

          <div className="intel-card">
            <span className="intel-icon">◈</span>
            <div><b>读牌提示</b><p>豪哥连续两局在翻牌前加注。他未必总有好牌，也可能只是在拿桌上形象压人。</p></div>
          </div>

          <div className="mode-switch" aria-label="游戏模式">
            <button className={serverTable?.mode !== 'SPECTATOR' ? 'active' : ''} onClick={() => void switchMode('PLAYER')} disabled={thinking}>玩家对战</button>
            <button className={serverTable?.mode === 'SPECTATOR' ? 'active' : ''} onClick={() => void switchMode('SPECTATOR')} disabled={thinking}>AI 互战</button>
          </div>

          {serverTable?.mode !== 'SPECTATOR' && <div className="player-mini-card">
            <Avatar sprite={7} name="你" />
            <div><span>你的筹码</span><b>{formatChips(stack)}</b></div>
            <span className="rank-badge">#2</span>
          </div>}
        </aside>

        <section className="table-stage" aria-label="扑克牌桌">
          <div className="tavern-glow" />
          <div className="poker-table">
            <div className="felt-texture" />
            <div className="table-emblem"><span>兽</span><small>BAISHOU TEAHOUSE</small></div>
            <div className="pot-pill"><i className="gold-chip" /><span>底池</span><b>{formatChips(pot)}</b></div>
            <div className="community-cards">
              {board.map((card, index) => <PlayingCardView key={`${card.rank}${card.suit}`} card={card} glowing={index === 2} />)}
              {Array.from({ length: Math.max(0, 5 - board.length) }, (_, index) => <PlayingCardView hidden key={`hidden-${index}`} />)}
            </div>
            <div className="round-marker"><span>{streetLabel}</span>{[0, 1, 2, 3].map((step) => <i className={step <= streetStep(serverTable?.street ?? 'FLOP') ? 'active' : ''} key={step} />)}</div>
          </div>

          {agents.map((agent) => <AgentSeatView agent={agent} key={agent.id} />)}

          {roundDone && handResult && <div className="hand-result" role="status">
            <span>本手落定</span>
            <strong>{handResult.detail}</strong>
          </div>}

          <div className="agent-speech" aria-live="polite">
            <Avatar sprite={lastSpeaker.sprite} name={lastSpeaker.name} />
            <div><strong>{lastSpeaker.name}</strong><p>{lastSpeech}</p></div>
          </div>

          {serverTable?.mode !== 'SPECTATOR' && <section className={`hero-seat ${roundDone ? 'round-complete' : ''}`}>
            <div className="hero-cards">
              {playerCards.map((card) => <PlayingCardView key={`${card.rank}${card.suit}`} card={card} glowing />)}
            </div>
            <div className="hero-plate">
              <Avatar sprite={7} name="你" />
              <div><span>你（玩家）</span><strong>{formatChips(stack)}</strong></div>
              <span className="hero-style">沉着</span>
            </div>
          </section>}

          <div className="action-dock">
            {serverTable?.mode === 'SPECTATOR' && !roundDone ? (
              <div className="spectator-controls">
                <div><span>AI 决策剧场</span><small>{thinking ? '思考中……' : `行动版本 ${serverTable.version}`}</small></div>
                <button onClick={() => void advanceSpectatorTurn()} disabled={thinking}>推进一步</button>
                <button className={autoPlay ? 'active' : ''} onClick={() => setAutoPlay((value) => !value)}>{autoPlay ? '暂停' : '自动播放'}</button>
              </div>
            ) : roundDone ? (
              <button className="next-hand-button" onClick={dealNextHand}>开始下一手牌 <span>→</span></button>
            ) : (
              <>
                <div className="turn-meta"><span>轮到你行动</span><b>{thinking ? 'AI 思考中…' : '剩余 28 秒'}</b></div>
                <div className="action-buttons">
                  <button className="action-button fold" onClick={() => act('弃牌')} disabled={thinking || !legalTypes.includes('FOLD')}><span>弃牌</span><small>FOLD · F</small></button>
                  <button className="action-button check" onClick={() => act(canCheck ? '过牌' : '跟注')} disabled={thinking || (!canCheck && !canCall)}><span>{canCheck ? '过牌' : `跟注 ${callAmount}`}</span><small>{canCheck ? 'CHECK' : 'CALL'} · C</small></button>
                  <button className="action-button raise" onClick={() => act(canRaise ? '加注' : '全下')} disabled={thinking || (!canRaise && !canAllIn)}><span>{canRaise ? `加注 ${raise}` : '全下'}</span><small>{canRaise ? 'RAISE' : 'ALL IN'} · R</small></button>
                </div>
                <div className="raise-control">
                  <button onClick={() => setRaise(300)}>2×</button>
                  <button onClick={() => setRaise(450)}>3×</button>
                  <input aria-label="加注金额" type="range" min={serverTable?.legalActions.minRaiseTo ?? 300} max={Math.max(serverTable?.legalActions.minRaiseTo ?? 300, maxRaise)} step="50" value={raise} onChange={(event) => setRaise(Number(event.target.value))} disabled={!canRaise} />
                  <button onClick={() => setRaise(maxRaise)} disabled={!canAllIn}>全下</button>
                </div>
              </>
            )}
          </div>
        </section>

        <aside className="side-panel right-panel">
          <PanelTitle icon="✦" title="牌桌动态" subtitle="ACTION LOG" />
          <div className="log-list" aria-label="行动日志">
            {[...logs].reverse().map((log) => (
              <article className="log-item" key={log.id}>
                <i className={`log-dot ${log.tone}`} />
                <div><span><b>{log.name}</b> {log.action}</span><p>{log.detail}</p></div>
              </article>
            ))}
          </div>

          {serverTable?.mode !== 'SPECTATOR' && <div className="table-talk">
            <div className="talk-heading"><span>牌桌发言</span><small>{chat.length}/120</small></div>
            <textarea
              aria-label="牌桌发言"
              value={chat}
              maxLength={120}
              onChange={(event) => setChat(event.target.value)}
              placeholder="说点什么影响对手的判断……"
              onKeyDown={(event) => {
                if (event.key === 'Enter' && !event.shiftKey) {
                  event.preventDefault()
                  sendTableTalk()
                }
              }}
            />
            <button onClick={sendTableTalk} disabled={!chat.trim() || thinking}>发送到牌桌 <span>↗</span></button>
            <p className="talk-note">AI 会把你的发言作为线索，但不会盲目相信。</p>
          </div>}
        </aside>
      </section>

      <footer className="statusbar">
        <span><i className="online-dot" />{connection === 'online' ? 'Java 权威对局引擎' : '离线演示引擎'}</span>
        <span>{connection === 'online' ? `安全会话 · 版本 ${serverTable?.version ?? 0}` : '演示模式 · 请启动 Java 后端'}</span>
        <span>百兽茶馆 · 先行版</span>
      </footer>
    </main>
  )
}

function PanelTitle({ icon, title, subtitle }: { icon: string; title: string; subtitle: string }) {
  return <div className="panel-title"><span>{icon}</span><div><b>{title}</b><small>{subtitle}</small></div></div>
}

function toneForAction(action: string): TableLog['tone'] {
  if (action.includes('弃牌')) return 'green'
  if (action.includes('加注') || action.includes('全下')) return 'red'
  if (action.includes('过牌')) return 'blue'
  return 'gold'
}

function personaSubtitle(persona: string) {
  return ({
    vesper: '临江茶馆掌柜',
    hogarth: '北地矿场工头',
    mirelle: '江南票号账房',
    bruno: '退隐镖师',
    bunji: '岭南药铺学徒',
    nyx: '茶楼说书人',
    ragnar: '北地商队主',
    pip: '码头机灵跑堂',
  } as Record<string, string>)[persona] ?? '神秘牌手'
}

function streetStep(street: string) {
  return ({ PREFLOP: 0, FLOP: 1, TURN: 2, RIVER: 3, SHOWDOWN: 3 } as Record<string, number>)[street] ?? 0
}

function toStreetLabel(street: string) {
  return ({ PREFLOP: '翻牌前', FLOP: '翻牌圈', TURN: '转牌圈', RIVER: '河牌圈',
    SHOWDOWN: '摊牌', BETWEEN_HANDS: '手牌结束', COMPLETE: '锦标赛结束' } as Record<string, string>)[street] ?? street
}
