import { useState } from 'react'
import type { CareerStats } from '../careerStats'
import { formatChips } from '../game-demo'
import { Avatar } from './Avatar'

const labels={FOLD:'弃牌',CHECK:'过牌',CALL:'跟注',RAISE:'加注',ALL_IN:'全下'}
export function Career({stats,onBack,onReset}:{stats:CareerStats;onBack:()=>void;onReset:()=>void}) {
  const [confirm,setConfirm]=useState(false)
  const actions=Object.entries(stats.actions) as [keyof typeof labels,number][]
  const common=[...actions].sort((a,b)=>b[1]-a[1])[0]
  const opponents=Object.entries(stats.opponents).sort((a,b)=>b[1].games-a[1].games)
  const totalActions=actions.reduce((sum,[,count])=>sum+count,0)
  return <section className="page-content career-page"><button className="text-button" onClick={onBack}>← 返回大厅</button>
    <span className="eyebrow">只留在这台设备上的茶馆账本</span><h1>我的茶馆战绩</h1>
    <p>仅统计亲自上桌且已经确认的行动；刷新和断线补发不会重复记账，AI 观战不计入玩家胜率。</p>
    <div className="career-summary">
      <div><span>完赛</span><strong>{stats.games}</strong><small>局</small></div>
      <div><span>胜率</span><strong>{stats.games?Math.round(stats.wins/stats.games*100):0}%</strong><small>{stats.wins} 次夺冠</small></div>
      <div><span>累计盈亏</span><strong className={stats.netChips>=0?'positive':'negative'}>{stats.netChips>=0?'+':''}{formatChips(stats.netChips)}</strong><small>游戏积分</small></div>
      <div><span>最大底池</span><strong>{formatChips(stats.biggestPot)}</strong><small>{stats.hands} 手已结算</small></div>
    </div>
    <div className="career-sections"><article><h2>行动习惯</h2>{totalActions?<><p>最常使用：<b>{labels[common[0]]}</b></p><div className="action-totals">{actions.map(([type,count])=><div key={type}><span>{labels[type]}</span><b>{count}</b></div>)}</div></>:<p>完成几次行动后，这里会出现你的打法轮廓。</p>}</article>
      <article><h2>角色交手</h2>{opponents.length?<div className="opponent-records">{opponents.map(([key,value])=><div key={key}><Avatar sprite={value.sprite} name={value.name}/><span>{value.name}<small>{value.games} 局 · 胜过 {value.defeated} 次</small></span></div>)}</div>:<p>完成一场玩家锦标赛后，这里会记录与各位牌友的交手结果。</p>}</article></div>
    <div className="career-privacy"><p>数据保存在当前浏览器的本地存储中，不发送到服务端。清除站点数据或更换浏览器后无法恢复。</p>
      {!confirm?<button className="text-button" onClick={()=>setConfirm(true)}>清除本地战绩</button>:<div><span>此操作无法撤销。</span><button onClick={()=>{onReset();setConfirm(false)}}>确认清除</button><button onClick={()=>setConfirm(false)}>取消</button></div>}</div>
  </section>
}
