import { useEffect, useState } from 'react'
import { getRoster, type Persona, type TableView } from '../api'
import { Avatar } from './Avatar'

export function Lobby({ table, busy, notice, onStart, onResume, onRetry, onCareer }: {
  table: TableView | null; busy: boolean; notice: string
  onStart: (mode: 'PLAYER' | 'SPECTATOR', name: string, personas: string[]) => void
  onResume: () => void; onRetry: () => void; onCareer: () => void
}) {
  const [roster, setRoster] = useState<Persona[]>([])
  const [mode, setMode] = useState<'PLAYER' | 'SPECTATOR'>('PLAYER')
  const [selected, setSelected] = useState<string[]>([])
  const [name, setName] = useState('旅人')
  const [error, setError] = useState('')
  const count = mode === 'PLAYER' ? 5 : 6
  function load() { getRoster().then(values => { setRoster(values); setSelected(values.slice(0, 5).map(p => p.key)); setError('') }).catch(e => setError(e.message)) }
  useEffect(load, [])
  function chooseMode(next: 'PLAYER' | 'SPECTATOR') {
    setMode(next); setSelected(roster.slice(0, next === 'PLAYER' ? 5 : 6).map(p => p.key))
  }
  function toggle(key: string) { setSelected(current => current.includes(key) ? current.filter(k => k !== key) : current.length < count ? [...current, key] : current) }
  return <section className="lobby page-content">
    <div className="lobby-intro"><span className="eyebrow">一盏茶的工夫，一桌人的心思</span>
      <h1>今夜，和谁过招？</h1><p>牌有大小，话有真假。选几位性格迥异的牌友，在百兽茶馆坐一场。</p>
      {table && <button className="primary-button resume-button" onClick={onResume}>继续第 {table.handNumber} 手 · {table.mode === 'PLAYER' ? '玩家牌桌' : 'AI 剧场'} →</button>}
    </div>
    <div className="lobby-setup">
      <div className="setup-copy"><span className="section-kicker">今晚的局</span>
        <div className="mode-switch"><button disabled={busy || !roster.length} aria-pressed={mode === 'PLAYER'} className={mode === 'PLAYER' ? 'active' : ''} onClick={() => chooseMode('PLAYER')}>亲自上桌</button>
          <button disabled={busy || !roster.length} aria-pressed={mode === 'SPECTATOR'} className={mode === 'SPECTATOR' ? 'active' : ''} onClick={() => chooseMode('SPECTATOR')}>AI 决策剧场</button></div>
        <label className="field-label">你的称呼<input value={name} onChange={e => setName(e.target.value)} maxLength={20} /></label>
        <h2>六人淘汰赛</h2><p>每人 10,000 筹码 · 起始盲注 50 / 100<br />每 8 手升盲 · 无前注 · 不限注</p>
        <p>筹码归零后可继续观战，直至决出冠军。所有筹码均为游戏积分。</p>
        <button className="primary-button" disabled={busy || selected.length !== count || !name.trim()} onClick={() => onStart(mode, name.trim(), selected)}>
          {busy ? '正在安排座位……' : table ? '用所选阵容另开一桌 →' : '落座，开局 →'}</button>
        {table && <small>另开一桌会替换当前会话；原桌的回放入口将不再可用。</small>}
        <p role="status">{notice}</p><div className="lobby-links"><button className="text-button" onClick={() => { onRetry(); if (!roster.length) load() }}>重新连接</button><button className="text-button" onClick={onCareer}>本地战绩</button></div>
      </div>
      <div className="roster-section"><div className="roster-heading"><h2>挑选你的牌友 <small>{selected.length} / {count}</small></h2>
        <button className="text-button" onClick={() => setSelected([...roster].map(p => ({p, n: crypto.getRandomValues(new Uint32Array(1))[0]})).sort((a,b) => a.n-b.n).slice(0,count).map(v=>v.p.key))}>随机组桌 ↻</button></div>
        {error && <p role="alert">{error}</p>}
        <div className="roster-grid">{roster.map((persona, sprite) => <button key={persona.key} className={`roster-card ${selected.includes(persona.key) ? 'selected' : ''}`}
          aria-pressed={selected.includes(persona.key)} aria-label={`选择${persona.name}`} onClick={() => toggle(persona.key)} disabled={!selected.includes(persona.key) && selected.length >= count}>
          <Avatar sprite={sprite} name={persona.name} large /><strong>{persona.name}</strong><span>{persona.species}</span><p>{persona.tagline}</p>
          <small>激进 {persona.aggression} · 耐心 {persona.patience}</small>
        </button>)}</div>
      </div>
    </div>
  </section>
}
