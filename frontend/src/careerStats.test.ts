import { describe,expect,it } from 'vitest'
import { CAREER_KEY,emptyCareer,loadCareer,recordCareer } from './careerStats'
import { tableView } from './test/fixtures'

describe('anonymous local career',()=>{
  it('counts confirmed player events once through reload and completion',()=>{
    const action={sequence:3,seat:5,name:'旅人',action:'跟注',summary:''}
    let stats=recordCareer(emptyCareer(),tableView({pot:900,actionLog:[action]}))
    expect(stats.actions.CALL).toBe(1)
    expect(recordCareer(stats,tableView({pot:900,actionLog:[action]}))).toBe(stats)
    const settlement={sequence:4,seat:-1,name:'荷官',action:'本手结算',summary:'旅人赢得 2,500'}
    stats=recordCareer(stats,tableView({sequence:4,status:'BETWEEN_HANDS',pot:2500,actionLog:[action,settlement]}))
    expect(stats.hands).toBe(1);expect(stats.biggestPot).toBe(2500)
    const complete=tableView({sequence:5,status:'COMPLETE',pot:0,actionLog:[action,settlement],rankings:[
      {seat:5,name:'旅人',sprite:7,stack:60000,position:1},...Array.from({length:5},(_,seat)=>({seat,name:`Agent ${seat}`,sprite:seat,stack:0,position:seat+2}))]})
    complete.seats[5].stack=60000;complete.seats.slice(0,5).forEach(s=>s.stack=0)
    stats=recordCareer(stats,complete)
    expect(stats).toMatchObject({games:1,wins:1,hands:1,netChips:50000,biggestPot:2500})
    expect(Object.values(stats.opponents)).toHaveLength(5)
    expect(Object.values(stats.opponents).every(v=>v.defeated===1)).toBe(true)
    expect(recordCareer(stats,complete)).toBe(stats)
  })
  it('ignores spectator tables and corrupt storage',()=>{
    const empty=emptyCareer()
    expect(recordCareer(empty,tableView({mode:'SPECTATOR'}))).toBe(empty)
    const storage={getItem:(key:string)=>key===CAREER_KEY?'{broken':null}
    expect(loadCareer(storage)).toEqual(emptyCareer())
    const malformed={getItem:()=>JSON.stringify({games:2,wins:9,hands:0,netChips:0,biggestPot:0,actions:{CALL:'many'},opponents:{bad:{name:7}},cursors:{x:-1},completedTables:[4]})}
    expect(loadCareer(malformed)).toMatchObject({games:2,wins:2,actions:{CALL:0},opponents:{},cursors:{},completedTables:[]})
  })
})
