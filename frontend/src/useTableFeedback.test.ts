import { act,renderHook,cleanup } from '@testing-library/react'
import { afterEach,expect,it,vi } from 'vitest'
import { useTableFeedback } from './useTableFeedback'
import { TableAudio } from './tableFeedback'
import { tableView } from './test/fixtures'

afterEach(()=>{cleanup();vi.restoreAllMocks();vi.useRealTimers()})
it('coalesces rapid frames and stays quiet after muting',async()=>{
  vi.useFakeTimers()
  vi.spyOn(TableAudio.prototype,'enable').mockResolvedValue()
  const play=vi.spyOn(TableAudio.prototype,'play').mockImplementation(()=>{})
  const {result,rerender}=renderHook(({table})=>useTableFeedback(table,true,true),{initialProps:{table:tableView()}})
  expect(play).not.toHaveBeenCalled()
  await act(async()=>{await result.current.toggleAudio()})
  play.mockClear()
  for(let sequence=4;sequence<=8;sequence++) act(()=>rerender({table:tableView({sequence,
    actionLog:[{sequence,seat:0,name:'阿绯',action:'跟注',summary:''}]})}))
  act(()=>vi.advanceTimersByTime(70))
  expect(play).toHaveBeenCalledTimes(1)
  await act(async()=>{await result.current.toggleAudio()})
  play.mockClear()
  act(()=>rerender({table:tableView({sequence:9,handNumber:2})}))
  act(()=>vi.advanceTimersByTime(100))
  expect(play).not.toHaveBeenCalled()
})
