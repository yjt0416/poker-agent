import { useEffect, useMemo, useRef, useState } from 'react'
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

type PlayerAction = '弃牌' | '过牌' | '跟注' | '加注'

const aiResponses = [
  { id: 'vesper', action: '跟注 300', line: '“有意思。你讲的故事，我暂时愿意听下去。”', tone: 'blue' as const },
  { id: 'hogarth', action: '加注至 600', line: '“声音挺大，但筹码说的话才算数。”', tone: 'red' as const },
  { id: 'mirelle', action: '弃牌', line: '她凝视你片刻，把牌推回中央。', tone: 'green' as const },
]

export function App() {
  const [agents, setAgents] = useState<AgentSeat[]>(initialAgents)
  const [logs, setLogs] = useState<TableLog[]>(initialLogs)
  const [pot, setPot] = useState(600)
  const [stack, setStack] = useState(2500)
  const [raise, setRaise] = useState(300)
  const [chat, setChat] = useState('')
  const [thinking, setThinking] = useState(false)
  const [lastSpeech, setLastSpeech] = useState('“轮到你了，陌生人。别让我们等太久。”')
  const [hand, setHand] = useState(18)
  const [playerCards, setPlayerCards] = useState<PlayingCard[]>(holeCards)
  const [board, setBoard] = useState<PlayingCard[]>(communityCards)
  const [roundDone, setRoundDone] = useState(false)
  const timers = useRef<number[]>([])

  useEffect(() => () => timers.current.forEach(window.clearTimeout), [])

  const activePlayers = useMemo(() => agents.filter((agent) => !agent.folded).length + (roundDone ? 0 : 1), [agents, roundDone])

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
      appendLog(initialAgents.find((agent) => agent.id === response.id)?.name ?? 'Agent', response.action, response.line, response.tone)
      setPot((current) => current + (response.action.includes('600') ? 600 : response.action.includes('300') ? 300 : 0))
      setRoundDone(true)
    }, 1100)
    timers.current.push(timer)
  }

  function act(action: PlayerAction) {
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

  function sendTableTalk() {
    const value = chat.trim()
    if (!value || thinking) return
    appendLog('你', '牌桌发言', `“${value}”`, 'gold')
    setLastSpeech('薇斯珀眯起眼睛，像是在重新衡量你的下注范围。')
    setChat('')
  }

  function dealNextHand() {
    const nextHand = hand + 1
    const next = nextDemoHand(nextHand)
    setHand(nextHand)
    setPlayerCards(next.cards)
    setBoard(next.board)
    setPot(300)
    setRaise(300)
    setRoundDone(false)
    setAgents(initialAgents.map((agent) => ({ ...agent, folded: false, bet: agent.id === 'hogarth' || agent.id === 'mirelle' ? 100 : 0 })))
    setLogs([{ id: Date.now(), name: '酒馆荷官', action: `第 ${nextHand} 手牌开始`, detail: '盲注已下，卡牌已发出。', tone: 'gold' }])
    setLastSpeech('“新的一局，新的一次犯错机会。”霍加斯咧嘴笑了。')
  }

  return (
    <main className="app-shell">
      <div className="ambient-lamp lamp-left" />
      <div className="ambient-lamp lamp-right" />

      <header className="topbar">
        <a className="brand" href="#table" aria-label="Agent Tavern 首页">
          <span className="brand-mark">A</span>
          <span><b>AGENT TAVERN</b><small>多智能体扑克酒馆</small></span>
        </a>
        <div className="table-title">
          <span className="eyebrow">暮色大厅 · 07 号桌</span>
          <h1>无上限德州扑克</h1>
        </div>
        <div className="header-actions">
          <span className="connection"><i /> 本地演示已连接</span>
          <button className="icon-button" aria-label="游戏设置">⚙</button>
        </div>
      </header>

      <section className="game-layout" id="table">
        <aside className="side-panel left-panel">
          <PanelTitle icon="♜" title="本桌情报" subtitle="TABLE INTEL" />
          <dl className="stats-grid">
            <div><dt>牌局</dt><dd>第 {hand} 手牌</dd></div>
            <div><dt>阶段</dt><dd className="accent">翻牌圈</dd></div>
            <div><dt>盲注</dt><dd>50 / 100</dd></div>
            <div><dt>在局</dt><dd>{activePlayers} / 6</dd></div>
          </dl>

          <div className="divider" />
          <span className="section-kicker">牌桌气氛</span>
          <div className="tension-meter"><span style={{ width: '68%' }} /></div>
          <div className="tension-copy"><b>暗流涌动</b><span>68%</span></div>

          <div className="intel-card">
            <span className="intel-icon">◈</span>
            <div><b>读牌提示</b><p>霍加斯连续两局在翻牌前加注。他可能在利用桌上形象施压。</p></div>
          </div>

          <div className="player-mini-card">
            <Avatar sprite={7} name="你" />
            <div><span>你的筹码</span><b>{formatChips(stack)}</b></div>
            <span className="rank-badge">#2</span>
          </div>
        </aside>

        <section className="table-stage" aria-label="扑克牌桌">
          <div className="tavern-glow" />
          <div className="poker-table">
            <div className="felt-texture" />
            <div className="table-emblem"><span>AT</span><small>AGENT TAVERN</small></div>
            <div className="pot-pill"><i className="gold-chip" /><span>底池</span><b>{formatChips(pot)}</b></div>
            <div className="community-cards">
              {board.map((card, index) => <PlayingCardView key={`${card.rank}${card.suit}`} card={card} glowing={index === 2} />)}
              <PlayingCardView hidden />
              <PlayingCardView hidden />
            </div>
            <div className="round-marker"><span>翻牌圈</span><i /><i /><i className="muted" /><i className="muted" /></div>
          </div>

          {agents.map((agent) => <AgentSeatView agent={agent} key={agent.id} />)}

          <div className="agent-speech" aria-live="polite">
            <Avatar sprite={0} name="薇斯珀" />
            <p>{lastSpeech}</p>
          </div>

          <section className={`hero-seat ${roundDone ? 'round-complete' : ''}`}>
            <div className="hero-cards">
              {playerCards.map((card) => <PlayingCardView key={`${card.rank}${card.suit}`} card={card} glowing />)}
            </div>
            <div className="hero-plate">
              <Avatar sprite={7} name="你" />
              <div><span>你（玩家）</span><strong>{formatChips(stack)}</strong></div>
              <span className="hero-style">沉着</span>
            </div>
          </section>

          <div className="action-dock">
            {roundDone ? (
              <button className="next-hand-button" onClick={dealNextHand}>开始下一手牌 <span>→</span></button>
            ) : (
              <>
                <div className="turn-meta"><span>轮到你行动</span><b>{thinking ? 'AI 思考中…' : '剩余 28 秒'}</b></div>
                <div className="action-buttons">
                  <button className="action-button fold" onClick={() => act('弃牌')} disabled={thinking}><span>弃牌</span><small>FOLD · F</small></button>
                  <button className="action-button check" onClick={() => act('跟注')} disabled={thinking}><span>跟注 150</span><small>CALL · C</small></button>
                  <button className="action-button raise" onClick={() => act('加注')} disabled={thinking}><span>加注 {raise}</span><small>RAISE · R</small></button>
                </div>
                <div className="raise-control">
                  <button onClick={() => setRaise(300)}>2×</button>
                  <button onClick={() => setRaise(450)}>3×</button>
                  <input aria-label="加注金额" type="range" min="300" max={Math.max(300, stack)} step="50" value={raise} onChange={(event) => setRaise(Number(event.target.value))} />
                  <button onClick={() => setRaise(stack)}>全下</button>
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

          <div className="table-talk">
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
          </div>
        </aside>
      </section>

      <footer className="statusbar">
        <span><i className="online-dot" />离线决策引擎</span>
        <span>演示模式 · 后端实时 API 接入中</span>
        <span>Agent Tavern α</span>
      </footer>
    </main>
  )
}

function PanelTitle({ icon, title, subtitle }: { icon: string; title: string; subtitle: string }) {
  return <div className="panel-title"><span>{icon}</span><div><b>{title}</b><small>{subtitle}</small></div></div>
}
