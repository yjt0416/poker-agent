import type { TableView, Persona } from '../api'
export const roster: Persona[] = ['阿绯','豪哥','沈听澜','杜叔','小满','墨羽','熊镇山','阿拾'].map((name,i)=>({key:`agent-${i}`,name,species:'茶馆牌友',tagline:'各有各的打法',aggression:50,bluffing:40,patience:60}))
export function tableView(overrides: Partial<TableView> = {}): TableView {
  return {tableId:'table-1',version:3,sequence:3,mode:'PLAYER',status:'IN_HAND',handNumber:1,street:'PREFLOP',pot:933,buttonSeat:0,actorSeat:5,selfSeat:5,canAdvance:false,
    blinds:{small:50,big:100},seats:Array.from({length:6},(_,i)=>({seat:i,name:i===5?'旅人':roster[i].name,persona:i===5?'human':roster[i].key,sprite:i,stack:10000,streetCommitted:0,handCommitted:0,status:'ACTIVE',self:i===5})),
    board:[],holeCards:[{rank:'A',suit:'♠'},{rank:'K',suit:'♥'}],legalActions:{types:['FOLD','CALL','RAISE','ALL_IN'],callAmount:533,minRaiseTo:816,maxRaiseTo:10000},actionLog:[],chat:[],rankings:[],...overrides}
}
