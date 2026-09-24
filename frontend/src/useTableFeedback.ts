import { useEffect, useRef, useState } from 'react'
import type { TableView } from './api'
import { feedbackCue, TableAudio, type Cue } from './tableFeedback'

const key = 'tavern.feedback.v1'
function savedPreferences(): { volume: number; reduced: boolean } {
  try {
    const saved = JSON.parse(localStorage.getItem(key) ?? '{}')
    return { volume: typeof saved.volume === 'number' && Number.isFinite(saved.volume) ? Math.max(0,Math.min(100,saved.volume)) : 40, reduced: saved.reduced === true }
  } catch { return { volume:40, reduced:false } }
}
export function useTableFeedback(table: TableView | null, active: boolean, online: boolean) {
  const [preferences, setPreferences] = useState(savedPreferences)
  const [enabled, setEnabled] = useState(false)
  const [error, setError] = useState('')
  const [effect, setEffect] = useState<Cue | null>(null)
  const previous = useRef<TableView | null>(null)
  const ready = useRef(false)
  const audio = useRef<TableAudio | null>(null)
  const generation = useRef(0)
  const getAudio = () => audio.current ??= new TableAudio()
  useEffect(() => { try { localStorage.setItem(key,JSON.stringify(preferences)) } catch { /* Storage may be disabled. */ }
    audio.current?.setVolume(enabled ? preferences.volume : 0)
  },[preferences,enabled])
  useEffect(() => () => { generation.current++; audio.current?.close(); audio.current=null },[])
  useEffect(() => {
    const visible = () => { if (document.hidden) { ready.current=false; audio.current?.mute() } else if(enabled) audio.current?.setVolume(preferences.volume) }
    document.addEventListener('visibilitychange',visible)
    return () => document.removeEventListener('visibilitychange',visible)
  },[enabled,preferences.volume])
  useEffect(() => {
    const available = active && online && !document.hidden
    const cue = available && ready.current && table ? feedbackCue(previous.current,table) : null
    previous.current=table; ready.current=available
    setEffect(cue)
    if (!cue) return
    // Coalesce bursts of catch-up frames; never overlap a backlog of sounds.
    const sound=window.setTimeout(()=>{ if(enabled && !document.hidden) audio.current?.play(cue) },60)
    const timer=window.setTimeout(()=>setEffect(null),180)
    return ()=>{ window.clearTimeout(sound);window.clearTimeout(timer) }
  },[table,active,online])
  async function toggleAudio() {
    const request=++generation.current
    if(enabled) { audio.current?.mute();setEnabled(false);return }
    try {
      await getAudio().enable(preferences.volume)
      if(request!==generation.current)return
      setEnabled(true);setError('');getAudio().play('check')
    } catch { if(request===generation.current) {setEnabled(false);setError('浏览器暂不支持音效，请稍后重试')} }
  }
  return { ...preferences, enabled, error, effect, toggleAudio,
    setVolume:(volume:number)=>setPreferences(p=>({...p,volume})),
    setReduced:(reduced:boolean)=>setPreferences(p=>({...p,reduced})) }
}
