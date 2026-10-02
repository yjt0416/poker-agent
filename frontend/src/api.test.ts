import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, command, enterTable, getReplay, subscribeTable } from './api'
import { tableView } from './test/fixtures'
afterEach(()=>{vi.unstubAllGlobals();vi.useRealTimers()})
describe('table protocol',()=>{
  it('deduplicates entry and treats missing sessions as the lobby',async()=>{
    const fetch=vi.fn().mockResolvedValue({ok:false,status:401,json:async()=>({message:'expired'})});vi.stubGlobal('fetch',fetch);
    expect(await Promise.all([enterTable(),enterTable()])).toEqual([null,null]);expect(fetch).toHaveBeenCalledTimes(1);
  })
  it('includes identity, version and a caller-supplied command ID',async()=>{
    const fetch=vi.fn().mockResolvedValue({ok:true,json:async()=>tableView()});vi.stubGlobal('fetch',fetch);await command(tableView(),'actions',{type:'CALL'},'same-command');
    expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({tableId:'table-1',expectedVersion:3,commandId:'same-command',type:'CALL'});
  })
  it('reports connection errors without creating another table',async()=>{
    vi.stubGlobal('fetch',vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));await expect(enterTable()).rejects.toEqual(new ApiError(0,'无法连接牌桌服务，请确认后端已启动'));
  })
  it('cancels the underlying replay request when the page leaves',async()=>{
    let transportSignal!: AbortSignal
    vi.stubGlobal('fetch',vi.fn((_path, init)=>new Promise((_resolve,reject)=>{
      transportSignal=init.signal
      transportSignal.addEventListener('abort',()=>reject(new DOMException('Aborted','AbortError')))
    })))
    const controller=new AbortController()
    const result=getReplay(tableView(),3,controller.signal).catch(error=>error)
    controller.abort()
    expect(transportSignal.aborted).toBe(true)
    expect(await result).toBeInstanceOf(ApiError)
  })
  it('reconnects from the received cursor and cancels retries on unmount',()=>{
    vi.useFakeTimers();const instances:FakeStream[]=[];
    class FakeStream {onopen=()=>{};onerror=()=>{};listeners:Record<string,(e:{data:string})=>void>={};close=vi.fn();constructor(public url:string){instances.push(this)}addEventListener(name:string,fn:(e:{data:string})=>void){this.listeners[name]=fn}}
    vi.stubGlobal('EventSource',FakeStream);const update=vi.fn();const stop=subscribeTable(tableView(),update,vi.fn());
    instances[0].listeners.table({data:JSON.stringify(tableView({sequence:8}))});instances[0].onerror();vi.advanceTimersByTime(1000);
    expect(instances[1].url).toContain('after=8');expect(update).toHaveBeenCalledTimes(1);instances[1].onerror();stop();vi.advanceTimersByTime(30000);expect(instances).toHaveLength(2);
  })
})
