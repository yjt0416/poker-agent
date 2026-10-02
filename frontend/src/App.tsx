import { useEffect, useState } from 'react'
import { useTable } from './useTable'
import { useTableFeedback } from './useTableFeedback'
import { useCareerStats } from './useCareerStats'
import { AgentSeatView } from './components/AgentSeatView'
import { Avatar } from './components/Avatar'
import { PlayingCardView } from './components/PlayingCardView'
import { Lobby } from './components/Lobby'
import { Replay } from './components/Replay'
import { Results } from './components/Results'
import { Career } from './components/Career'
import { formatChips } from './game-demo'

const streetNames: Record<string,string> = { PREFLOP:'翻牌前',FLOP:'翻牌圈',TURN:'转牌圈',RIVER:'河牌圈',BETWEEN_HANDS:'手牌结束',COMPLETE:'锦标赛结束' }
const emotionNames: Record<string,string> = { CALM:'沉着',THINKING:'斟酌',CONFIDENT:'自信',SUSPICIOUS:'警觉',NERVOUS:'有些紧张',DELIGHTED:'兴致正高' }

export function App() {
  const game = useTable()
  const {table,pending,online,notice} = game
  const [page,setPage] = useState<'lobby'|'table'|'replay'|'career'>('lobby')
  const [logOpen,setLogOpen] = useState(false)
  const [raise,setRaise] = useState(0)
  const [chat,setChat] = useState('')
  const [autoPlay,setAutoPlay] = useState(false)
  const [speed,setSpeed] = useState(1)
  const [focus,setFocus] = useState<number | null>(null)
  const feedback = useTableFeedback(table,page==='table',online)
  const career = useCareerStats(table)
  const legal = table?.legalActions
  const canRaise = legal?.types.includes('RAISE') ?? false
  const min = legal?.minRaiseTo ?? 0
  const max = legal?.maxRaiseTo ?? 0
  const validRaise = canRaise && Number.isSafeInteger(raise) && raise >= min && raise <= max
  const self = table?.seats.find(s=>s.self)
  const watching = table?.canAdvance ?? false
  const actor = table?.seats.find(s=>s.seat===table.actorSeat)
  const disabled = pending || !online
  useEffect(()=>{setPage(table?'table':'lobby');setAutoPlay(false);setFocus(null);setChat('')},[table?.tableId])
  useEffect(()=>{setRaise(min)},[table?.version,min])
  useEffect(()=>{
    if (!autoPlay || !watching || disabled || page !== 'table' || !table) return
    if (table.status === 'COMPLETE') { setAutoPlay(false); return }
    const timer = window.setTimeout(()=>void game.send(table.status === 'BETWEEN_HANDS'?'next-hand':'advance').then(ok=>{if(!ok)setAutoPlay(false)}),
      (table.status === 'BETWEEN_HANDS'?1800:900)/speed)
    return ()=>window.clearTimeout(timer)
  },[autoPlay,watching,disabled,page,speed,table?.version,table?.status])

  async function act(type:string,amount?:number) { return game.send('actions',{type,amount}) }
  useEffect(()=>{
    const key = (event: KeyboardEvent) => {
      if (event.repeat || event.ctrlKey || event.altKey || event.metaKey || disabled || page !== 'table' || watching || logOpen) return
      if (event.target instanceof Element && event.target.closest('input,textarea,select,button,[contenteditable=true]')) return
      const type = event.key.toLowerCase()==='f'?'FOLD':event.key.toLowerCase()==='c'?(legal?.types.includes('CHECK')?'CHECK':'CALL'):event.key.toLowerCase()==='r'&&validRaise?'RAISE':null
      if(type && legal?.types.includes(type)) {event.preventDefault();void act(type,type==='RAISE'?raise:undefined)}
    }
    window.addEventListener('keydown',key)
    return ()=>window.removeEventListener('keydown',key)
  },[disabled,page,watching,logOpen,legal,raise,validRaise])
  async function talk() { if(chat.trim() && await game.send('chat',{text:chat.trim()}))setChat('') }
  function lobby() {setAutoPlay(false);setPage('lobby');setLogOpen(false)}
  function replay() {setAutoPlay(false);setPage('replay');setLogOpen(false)}
  const latestChat = table?.chat.at(-1)
  const speaker = table?.seats.find(s=>s.seat===latestChat?.seat)
  const result = table && [...table.actionLog].reverse().find(l=>l.action==='本手结算')

  return <main className={`app-shell ${feedback.reduced?'reduced-motion':''} ${watching?'spectator-mode':'player-mode'} ${logOpen?'drawer-open':''} ${page!=='table'?'flow-page':''}`}>
    <header className="topbar">
      <button className="brand brand-button" onClick={lobby} aria-label="返回百兽茶馆大厅"><span className="brand-mark">兽</span><span><b>百兽茶馆</b><small>AGENT TAVERN</small></span></button>
      <div className="table-title"><span className="eyebrow">戌时 · 临江厅</span><h1>{page==='lobby'?'今夜茶局':page==='replay'?'重看风云':page==='career'?'茶馆账本':watching?'AI 决策剧场':'无上限德州扑克'}</h1>
        {table && page==='table' && <div className="compact-toolbar"><span>第 {table.handNumber} 手 · {streetNames[table.street]}</span>
          <button onClick={lobby}>大厅</button><button onClick={replay}>回放</button>{table.status!=='COMPLETE'&&<button aria-expanded={logOpen} onClick={()=>setLogOpen(v=>!v)}>动态</button>}</div>}
      </div>
      <div className="header-actions"><span className={`connection connection-${online?'online':'demo'}`} role="status" title={notice}><i/>{notice}</span>
        {table && <button className="retry-button" onClick={()=>void game.refresh()}>{online?'同步':'重连'}</button>}
        <details className="feedback-settings"><summary>声效</summary><div className="feedback-options">
          <button aria-pressed={feedback.enabled} onClick={()=>void feedback.toggleAudio()}>{feedback.enabled?'静音':'开启音效'}</button>
          <label>音量 <input aria-label="音效音量" type="range" min="0" max="100" value={feedback.volume} onChange={e=>feedback.setVolume(Number(e.target.value))}/><span>{feedback.volume}%</span></label>
          <label><input type="checkbox" checked={feedback.reduced} onChange={e=>feedback.setReduced(e.target.checked)}/>减少动态效果</label>
          <p>每次打开页面后手动开启声音；动态效果同时遵循系统设置。</p>
          {feedback.error && <p role="status">{feedback.error}</p>}
        </div></details></div>
    </header>
    {page==='career'?<Career stats={career.stats} onBack={lobby} onReset={career.reset}/>:page==='lobby' || !table ? <Lobby table={table} busy={pending} notice={notice} onRetry={()=>void game.refresh()} onResume={()=>setPage('table')} onCareer={()=>setPage('career')}
      onStart={(mode,name,personas)=>{setAutoPlay(false);void game.start(mode,name,personas)}}/> : page==='replay' ? <Replay key={table.tableId} table={table} onBack={()=>setPage('table')}/> : table.status==='COMPLETE' ?
      <Results table={table} stats={career.stats} onReplay={replay} onLobby={lobby} onCareer={()=>setPage('career')}/> : <>
      {!online && <div className="connection-banner" role="alert">连接中断，正在恢复已确认的牌局。<button onClick={()=>void game.refresh()}>立即同步</button></div>}
      <section className="game-layout" id="table">
        <aside className="side-panel left-panel"><PanelTitle title="本桌情报" subtitle="TABLE INTEL"/>
          <dl className="stats-grid"><div><dt>牌局</dt><dd>第 {table.handNumber} 手牌</dd></div><div><dt>阶段</dt><dd>{streetNames[table.street]}</dd></div><div><dt>盲注</dt><dd>{table.blinds.small} / {table.blinds.big}</dd></div><div><dt>在局</dt><dd>{table.seats.filter(s=>!['FOLDED','ELIMINATED'].includes(s.status)).length} / 6</dd></div></dl>
          <div className="intel-card"><div><b>{actor?`${actor.name}行动`:'本手落定'}</b><p>{watching?'逐步观察每位牌友的选择。也可以开启自动播放，直至决出冠军。':'发言会成为对手判断的线索。筹码投入由服务端校验，轮到你时才能下注。'}</p></div></div>
          <div className="side-navigation"><button onClick={lobby}>返回大厅</button><button onClick={replay}>牌局回放</button></div>
          {self && <div className="player-mini-card"><Avatar sprite={7} name="你"/><div><span>你的筹码</span><b>{formatChips(self.stack)}</b></div></div>}
        </aside>
        <section className="table-stage" aria-label="扑克牌桌" data-feedback={feedback.effect??undefined}>
          <div className="tavern-glow"/>
          <div className="poker-table"><div className="felt-texture"/><div className="table-emblem"><span>兽</span><small>BAISHOU TEAHOUSE</small></div>
            <div className="pot-pill"><i className="gold-chip"/><span>底池</span><b>{formatChips(table.pot)}</b></div>
            <div className="community-cards">{table.board.map((card,index)=><PlayingCardView key={`${table.handNumber}-${index}`} card={card}/>)}{Array.from({length:5-table.board.length},(_,i)=><PlayingCardView hidden key={`h${i}`}/>)}</div>
            <div className="round-marker">{streetNames[table.street]}</div>
          </div>
          {table.seats.filter(s=>!s.self || watching).map(s=><AgentSeatView key={s.seat} agent={{id:s.persona,name:s.name,subtitle:s.self?'已淘汰，继续观战':s.status==='ALL_IN'?'全下':s.seat===table.actorSeat?'正在行动':emotionNames[s.emotion??'']??'',stack:s.stack,bet:s.streetCommitted,mood:s.status,sprite:s.sprite,
            seat:['seat-left','seat-top','seat-right-top','seat-right','seat-left-bottom','seat-bottom'][s.seat],folded:['FOLDED','ELIMINATED'].includes(s.status),thinking:s.seat===table.actorSeat,dealer:s.seat===table.buttonSeat}}/>)}
          {table.status==='BETWEEN_HANDS' && result && <div className="hand-result" role="status"><span>本手落定</span><strong>{result.summary}</strong></div>}
          <div className="agent-speech" aria-live="polite"><Avatar sprite={speaker?.sprite??0} name={speaker?.name??'茶馆荷官'}/><div><strong>{speaker?.name??'茶馆荷官'}</strong><p>{latestChat?.text??'各位落座，先喝口茶，再慢慢过招。'}</p></div></div>
          {!watching && <section className="hero-seat"><div className="hero-cards">{table.holeCards.map((card,i)=><PlayingCardView key={`${table.handNumber}-${i}`} card={card} glowing/>)}</div><div className="hero-plate"><Avatar sprite={7} name="你"/><div><span>{self?.name}（玩家）</span><strong>{formatChips(self?.stack??0)}</strong></div></div></section>}
          <div className="action-dock">
            {watching ? <><div className="spectator-controls"><div><span>{self?'你已淘汰 · 继续观战':'AI 决策剧场'}</span><small>{pending?'正在思考……':`第 ${table.handNumber} 手`}</small></div>
              <button disabled={disabled} onClick={()=>void game.send(table.status==='BETWEEN_HANDS'?'next-hand':'advance')}>{table.status==='BETWEEN_HANDS'?'下一手':'推进一步'}</button>
              <button disabled={!online} className={autoPlay?'active':''} onClick={()=>setAutoPlay(v=>!v)}>{autoPlay?'暂停':'自动播放'}</button></div>
              <div className="director-controls"><label>速度 <select aria-label="观战速度" value={speed} onChange={e=>setSpeed(Number(e.target.value))}>{[.5,1,2,4].map(s=><option key={s} value={s}>{s}×</option>)}</select></label>
                <label>关注 <select aria-label="关注角色" value={focus??''} onChange={e=>setFocus(e.target.value===''?null:Number(e.target.value))}><option value="">全桌</option>{table.seats.map(s=><option value={s.seat} key={s.seat}>{s.name}</option>)}</select></label></div></>
            : table.status==='BETWEEN_HANDS' ? <button className="next-hand-button" disabled={disabled} onClick={()=>void game.send('next-hand')}>开始下一手牌 →</button>
            : <><div className="turn-meta"><span>{table.actorSeat===table.selfSeat?'轮到你行动':`${actor?.name??'牌友'}正在思考……`}</span><b>{pending?'正在确认行动':'想好再出手'}</b></div>
              <div className="action-buttons"><button className="action-button fold" disabled={disabled||!legal?.types.includes('FOLD')} onClick={()=>void act('FOLD')}><span>弃牌</span><small>FOLD · F</small></button>
                <button className="action-button check" disabled={disabled||!legal?.types.some(t=>['CHECK','CALL'].includes(t))} onClick={()=>void act(legal?.types.includes('CHECK')?'CHECK':'CALL')}><span>{legal?.types.includes('CHECK')?'过牌':`跟注 ${legal?.callAmount??0}`}</span><small>CHECK / CALL · C</small></button>
                <button className="action-button raise" disabled={disabled||!validRaise} onClick={()=>void act('RAISE',raise)}><span>加注至 {raise}</span><small>RAISE · R</small></button></div>
              <div className="raise-control"><button disabled={disabled||!canRaise} onClick={()=>setRaise(Math.min(max,Math.max(min,2*table.blinds.big)))}>2 BB</button><button disabled={disabled||!canRaise} onClick={()=>setRaise(Math.min(max,Math.max(min,3*table.blinds.big)))}>3 BB</button>
                <input aria-label="加注滑块" type="range" min={min} max={max} step={1} value={raise} disabled={disabled||!canRaise} onChange={e=>setRaise(Number(e.target.value))}/>
                <input aria-label="加注金额" className="raise-number" type="number" min={min} max={max} step={1} value={raise} disabled={disabled||!canRaise} onChange={e=>setRaise(Number(e.target.value))}/>
                <button disabled={disabled||!legal?.types.includes('ALL_IN')} onClick={()=>void act('ALL_IN',max)}>全下</button></div></>}
          </div>
        </section>
        <aside className={`side-panel right-panel ${logOpen?'compact-open':''}`}><button className="drawer-close" aria-label="关闭牌桌动态" onClick={()=>setLogOpen(false)}>×</button><PanelTitle title="牌桌动态" subtitle="ACTION LOG"/>
          <div className="log-list" role="region" aria-label="行动日志" tabIndex={0}>{[...table.actionLog].reverse().filter(l=>focus===null||l.seat===focus||l.seat===-1).map(log=><article className="log-item" key={log.sequence}><i className={`log-dot ${log.action.includes('弃牌')?'green':log.action.includes('加注')?'red':'gold'}`}/><div><span><b>{log.name}</b> {log.action}</span><p>{log.summary}</p></div></article>)}</div>
          {table.mode==='PLAYER' && !watching && <div className="table-talk"><div className="talk-heading"><label htmlFor="table-chat">牌桌发言</label><small>{[...chat].length}/240</small></div><textarea id="table-chat" value={chat} maxLength={480} onChange={e=>setChat([...e.target.value].slice(0,240).join(''))} placeholder="说点什么影响对手的判断……" onKeyDown={e=>{if(e.key==='Enter'&&!e.shiftKey&&!e.nativeEvent.isComposing){e.preventDefault();void talk()}}}/><button disabled={disabled||!chat.trim()} onClick={()=>void talk()}>发送到牌桌 ↗</button><p className="talk-note">5 秒一次 · 对手会听，但未必相信</p></div>}
        </aside>
      </section>
    </>}
    <footer className="statusbar"><span><i className="online-dot"/>{online?'牌桌服务已连接':'等待牌桌服务'}</span><span>{table?`已确认事件 ${table.sequence}`:'虚拟筹码 · 六人淘汰赛'}</span><span>百兽茶馆 · 先行版</span></footer>
  </main>
}
function PanelTitle({title,subtitle}:{title:string;subtitle:string}) {return <div className="panel-title"><span>✦</span><div><b>{title}</b><small>{subtitle}</small></div></div>}
