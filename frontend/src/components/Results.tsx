import type { TableView } from '../api'
import { Avatar } from './Avatar'
import { formatChips } from '../game-demo'
import type { CareerStats } from '../careerStats'

export function Results({table,stats,onReplay,onLobby,onCareer}:{table:TableView;stats:CareerStats;onReplay:()=>void;onLobby:()=>void;onCareer:()=>void}) {
  const ranks = [...table.rankings].sort((a,b)=>(a.position??7)-(b.position??7))
  const winner = ranks[0]
  return <section className="page-content results-page"><span className="eyebrow">茶凉了，胜负已定</span><h1>今夜的赢家</h1>
    {winner && <div className="champion"><Avatar sprite={winner.sprite} name={winner.name} large/><h2>{winner.name}</h2><strong>{formatChips(winner.stack)} 筹码</strong></div>}
    <p>六位牌友 · {table.handNumber} 手牌 · 一场完整的较量</p>
    <ol className="final-ranks">{ranks.map(s=><li key={s.seat}><b>#{s.position}</b><Avatar sprite={s.sprite} name={s.name}/><span>{s.name}</span><strong>{formatChips(s.stack)}</strong></li>)}</ol>
    {table.mode==='PLAYER'&&<p className="career-result">本地累计：{stats.games} 局 · {stats.wins} 次夺冠 · 盈亏 {stats.netChips>=0?'+':''}{formatChips(stats.netChips)}</p>}
    <div className="result-actions"><button className="primary-button" onClick={onReplay}>回看这场较量</button>{table.mode==='PLAYER'&&<button onClick={onCareer}>查看本地战绩</button>}<button onClick={onLobby}>回大厅，再约一桌</button></div>
  </section>
}
