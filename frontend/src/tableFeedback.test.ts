import { describe,expect,it } from 'vitest'
import { feedbackCue } from './tableFeedback'
import { tableView } from './test/fixtures'

describe('committed table feedback',()=>{
  it('keeps startup, duplicate frames, other tables and catch-up silent',()=>{
    const before=tableView()
    for(const after of [before,tableView({sequence:9}),tableView({tableId:'other',sequence:4})])
      expect(feedbackCue(before,after)).toBeNull()
    expect(feedbackCue(null,tableView())).toBeNull()
  })
  it('does not replay the last action on a chat-only update',()=>{
    const log=[{sequence:2,seat:0,name:'阿绯',action:'加注',summary:''}]
    expect(feedbackCue(tableView({actionLog:log}),tableView({sequence:4,actionLog:log}))).toBeNull()
  })
  it('uses one cue for settlement or board reveal even with another action',()=>{
    const before=tableView()
    expect(feedbackCue(before,tableView({sequence:4,status:'BETWEEN_HANDS'}))).toBe('win')
    expect(feedbackCue(before,tableView({sequence:4,board:[{rank:'A',suit:'♠'}]}))).toBe('deal')
    expect(feedbackCue(before,tableView({sequence:4,handNumber:2}))).toBe('deal')
  })
  it('distinguishes confirmed actions',()=>{
    for(const [action,cue] of [['全下','all-in'],['弃牌','fold'],['过牌','check'],['跟注','chips'],['加注','chips']])
      expect(feedbackCue(tableView(),tableView({sequence:4,actionLog:[{sequence:4,seat:0,name:'阿绯',action,summary:''}]}))).toBe(cue)
  })
})
