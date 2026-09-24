import type { TableView } from './api'

export type CareerStats = {
  games: number; wins: number; hands: number; netChips: number; biggestPot: number
  actions: Record<'FOLD'|'CHECK'|'CALL'|'RAISE'|'ALL_IN',number>
  opponents: Record<string,{name:string;sprite:number;games:number;defeated:number}>
  cursors: Record<string,number>; completedTables: string[]; tablePots: Record<string,number>
}

export const CAREER_KEY='agent-tavern.career.v1'
export const emptyCareer=():CareerStats=>({games:0,wins:0,hands:0,netChips:0,biggestPot:0,
  actions:{FOLD:0,CHECK:0,CALL:0,RAISE:0,ALL_IN:0},opponents:{},cursors:{},completedTables:[],tablePots:{}})

function validNumber(value:unknown) { return typeof value==='number'&&Number.isSafeInteger(value)&&value>=0 }
export function loadCareer(storage:Pick<Storage,'getItem'>=localStorage):CareerStats {
  try {
    const raw=JSON.parse(storage.getItem(CAREER_KEY)??'null')
    if(!raw||!validNumber(raw.games)||!validNumber(raw.wins)||!validNumber(raw.hands)||!Number.isSafeInteger(raw.netChips)||!validNumber(raw.biggestPot))return emptyCareer()
    const base=emptyCareer()
    const actions=Object.fromEntries(Object.keys(base.actions).map(key=>[key,validNumber(raw.actions?.[key])?raw.actions[key]:0])) as CareerStats['actions']
    const opponents:CareerStats['opponents']={}
    if(raw.opponents&&typeof raw.opponents==='object')for(const [key,value] of Object.entries(raw.opponents) as [string,any][]) {
      if(key&&typeof value?.name==='string'&&validNumber(value.sprite)&&validNumber(value.games)&&validNumber(value.defeated))
        opponents[key]={name:value.name.slice(0,40),sprite:value.sprite,games:value.games,defeated:Math.min(value.games,value.defeated)}
    }
    const numericRecord=(value:unknown)=>Object.fromEntries(value&&typeof value==='object'?Object.entries(value).filter(([key,item])=>key&&validNumber(item)).slice(-50):[])
    return {...base,games:raw.games,wins:Math.min(raw.games,raw.wins),hands:raw.hands,netChips:raw.netChips,biggestPot:raw.biggestPot,
      actions,opponents,cursors:numericRecord(raw.cursors),completedTables:Array.isArray(raw.completedTables)?raw.completedTables.filter((v:unknown)=>typeof v==='string').slice(-50):[],tablePots:numericRecord(raw.tablePots)}
  } catch { return emptyCareer() }
}
export function saveCareer(stats:CareerStats,storage:Pick<Storage,'setItem'>=localStorage) {
  try { storage.setItem(CAREER_KEY,JSON.stringify(stats)) } catch { /* Private mode or quota: the current page still works. */ }
}

function actionType(label:string):keyof CareerStats['actions']|null {
  if(label.includes('全下'))return 'ALL_IN';if(label.includes('弃牌'))return 'FOLD'
  if(label.includes('过牌'))return 'CHECK';if(label.includes('跟注'))return 'CALL';if(/加注|下注/.test(label))return 'RAISE'
  return null
}

export function recordCareer(previous:CareerStats,table:TableView):CareerStats {
  if(table.mode!=='PLAYER')return previous
  let changed=false
  const next:CareerStats={...previous,actions:{...previous.actions},opponents:{...previous.opponents},
    cursors:{...previous.cursors},completedTables:[...previous.completedTables],tablePots:{...previous.tablePots}}
  let cursor=next.cursors[table.tableId]??0
  const self=table.seats.find(s=>s.self)
  const tableMax=Math.max(next.tablePots[table.tableId]??0,table.pot)
  if(tableMax!==(next.tablePots[table.tableId]??0)){next.tablePots[table.tableId]=tableMax;changed=true}
  for(const log of [...table.actionLog].sort((a,b)=>a.sequence-b.sequence)) {
    if(log.sequence<=cursor)continue
    const type=self&&log.seat===self.seat?actionType(log.action):null
    if(type){next.actions[type]++;changed=true}
    if(log.action==='本手结算'){
      next.hands++;next.biggestPot=Math.max(next.biggestPot,next.tablePots[table.tableId]??table.pot)
      next.tablePots[table.tableId]=0;changed=true
    }
    cursor=Math.max(cursor,log.sequence);next.cursors[table.tableId]=cursor;changed=true
  }
  if(table.status==='COMPLETE'&&!next.completedTables.includes(table.tableId)) {
    const selfRank=table.rankings.find(r=>r.seat===table.selfSeat)
    next.games++;if(selfRank?.position===1)next.wins++
    next.netChips+=(self?.stack??selfRank?.stack??0)-10000
    for(const seat of table.seats.filter(s=>!s.self)) {
      const before=next.opponents[seat.persona]??{name:seat.name,sprite:seat.sprite,games:0,defeated:0}
      const opponentRank=table.rankings.find(r=>r.seat===seat.seat)
      next.opponents[seat.persona]={...before,name:seat.name,sprite:seat.sprite,games:before.games+1,
        defeated:before.defeated+(selfRank?.position&&opponentRank?.position&&selfRank.position<opponentRank.position?1:0)}
    }
    next.completedTables.push(table.tableId);delete next.tablePots[table.tableId];changed=true
  }
  if(!changed)return previous
  next.completedTables=next.completedTables.slice(-50)
  next.cursors=Object.fromEntries(Object.entries(next.cursors).slice(-50))
  const pots=Object.entries(next.tablePots).slice(-10);next.tablePots=Object.fromEntries(pots)
  return next
}
