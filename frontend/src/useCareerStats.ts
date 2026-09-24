import { useEffect,useState } from 'react'
import type { TableView } from './api'
import { CAREER_KEY,emptyCareer,loadCareer,recordCareer,saveCareer } from './careerStats'

export function useCareerStats(table:TableView|null) {
  const [stats,setStats]=useState(loadCareer)
  useEffect(()=>{
    if(!table)return
    setStats(current=>{const latest=loadCareer();const next=recordCareer(latest.games>=current.games?latest:current,table);if(next!==current)saveCareer(next);return next})
  },[table])
  useEffect(()=>{const sync=(event:StorageEvent)=>{if(event.key===CAREER_KEY)setStats(loadCareer())};window.addEventListener('storage',sync);return()=>window.removeEventListener('storage',sync)},[])
  return {stats,reset:()=>{const next=emptyCareer();saveCareer(next);setStats(next)}}
}
