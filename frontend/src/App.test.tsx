import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from './api'
import { App } from './App'
import { roster, tableView } from './test/fixtures'
vi.mock('./api',()=>({enterTable:vi.fn(),subscribeTable:vi.fn(()=>()=>{}),getRoster:vi.fn(),createTable:vi.fn(),command:vi.fn(),getReplay:vi.fn()}))
beforeEach(()=>{localStorage.clear();vi.clearAllMocks();vi.mocked(api.enterTable).mockResolvedValue(tableView());vi.mocked(api.getRoster).mockResolvedValue(roster);vi.mocked(api.command).mockResolvedValue(tableView({sequence:4}));vi.mocked(api.getReplay).mockResolvedValue({frames:[tableView()],nextSequence:3,hasMore:false})})
afterEach(cleanup)
describe('live game flow',()=>{
  it('starts muted and persists motion and volume preferences',async()=>{
    localStorage.clear();const rendered=render(<App/>);
    await screen.findByRole('button',{name:/加注至 816/});
    fireEvent.click(screen.getByText('声效'));
    expect(screen.getByRole('button',{name:'开启音效'})).toHaveAttribute('aria-pressed','false');
    fireEvent.click(screen.getByRole('checkbox',{name:'减少动态效果'}));
    fireEvent.change(screen.getByRole('slider',{name:'音效音量'}),{target:{value:'25'}});
    expect(rendered.container.querySelector('main')).toHaveClass('reduced-motion');
    rendered.unmount();render(<App/>);fireEvent.click(screen.getByText('声效'));
    expect(screen.getByRole('checkbox',{name:'减少动态效果'})).toBeChecked();
    expect(screen.getByRole('slider',{name:'音效音量'})).toHaveValue('25');
    localStorage.clear();
  })
  it('shows public emotional state as Chinese seat labels',async()=>{
    const view=tableView();view.seats[0].emotion='DELIGHTED';view.seats[1].emotion='NERVOUS';
    vi.mocked(api.enterTable).mockResolvedValue(view);render(<App/>);
    await screen.findByText('兴致正高');expect(screen.getByText('有些紧张')).toBeInTheDocument();
  })
  it('shows the lobby without silently creating a table',async()=>{
    vi.mocked(api.enterTable).mockResolvedValue(null);render(<App/>);
    await screen.findByRole('button',{name:'选择阿绯'});expect(api.createTable).not.toHaveBeenCalled();
  })
  it('opens anonymous local career and requires a second click to clear it',async()=>{
    localStorage.setItem('agent-tavern.career.v1',JSON.stringify({games:2,wins:1,hands:8,netChips:3000,biggestPot:4200,actions:{FOLD:2,CHECK:1,CALL:4,RAISE:2,ALL_IN:0},opponents:{},cursors:{},completedTables:[],tablePots:{}}))
    vi.mocked(api.enterTable).mockResolvedValue(null);render(<App/>);await screen.findByRole('button',{name:'本地战绩'});fireEvent.click(screen.getByRole('button',{name:'本地战绩'}))
    expect(await screen.findByRole('heading',{name:'我的茶馆战绩'})).toHaveFocus();expect(screen.getByText('50%')).toBeInTheDocument();expect(screen.getByText('+3,000')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button',{name:'清除本地战绩'}));expect(screen.getByRole('alert')).toHaveTextContent('此操作无法撤销。');expect(screen.getByRole('button',{name:'确认清除'})).toHaveFocus()
    fireEvent.click(screen.getByRole('button',{name:'取消'}));expect(screen.queryByRole('alert')).not.toBeInTheDocument();expect(screen.getByRole('button',{name:'清除本地战绩'})).toHaveFocus();expect(screen.getByText('完赛').parentElement).toHaveTextContent('2局')
  })
  it('creates the selected roster',async()=>{
    vi.mocked(api.enterTable).mockResolvedValue(null);vi.mocked(api.createTable).mockResolvedValue(tableView());render(<App/>);
    await screen.findByRole('button',{name:'选择阿绯'});fireEvent.click(screen.getByRole('button',{name:'落座，开局 →'}));
    await screen.findByRole('button',{name:/加注至 816/});expect(api.createTable).toHaveBeenCalledWith('PLAYER','旅人',roster.slice(0,5).map(p=>p.key));
  })
  it('clamps shortcuts and submits the exact all-in',async()=>{
    render(<App/>);await screen.findByRole('button',{name:/加注至 816/});fireEvent.click(screen.getByRole('button',{name:'2 BB'}));
    expect(screen.getByRole('spinbutton',{name:'加注金额'})).toHaveValue(816);
    fireEvent.change(screen.getByRole('spinbutton',{name:'加注金额'}),{target:{value:'300'}});expect(screen.getByRole('button',{name:/加注至 300/})).toBeDisabled();
    fireEvent.click(screen.getByRole('button',{name:'全下'}));await waitFor(()=>expect(api.command).toHaveBeenCalledWith(expect.objectContaining({version:3}),'actions',{type:'ALL_IN',amount:10000}));
  })
  it('ignores stale stream frames',async()=>{
    render(<App/>);await screen.findByRole('button',{name:/加注至 816/});const update=vi.mocked(api.subscribeTable).mock.calls[0][1];
    act(()=>update(tableView({version:8,sequence:9,handNumber:2})));act(()=>update(tableView({sequence:4,handNumber:1})));
    expect(screen.getByText('第 2 手牌')).toBeInTheDocument();
  })
  it('shows final rankings without a next-hand action',async()=>{
    vi.mocked(api.enterTable).mockResolvedValue(tableView({status:'COMPLETE',rankings:[{seat:5,name:'旅人',sprite:7,stack:60000,position:1}]}));render(<App/>);
    expect(await screen.findByRole('heading',{name:'今夜的赢家'})).toHaveFocus();expect(screen.queryByRole('button',{name:/开始下一手牌/})).not.toBeInTheDocument();expect(screen.queryByRole('button',{name:'动态'})).not.toBeInTheDocument();expect(screen.getByText('#1')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button',{name:'查看本地战绩'}));expect(await screen.findByRole('heading',{name:'我的茶馆战绩'})).toHaveFocus();
  })
  it('offers spectator controls and removes table talk after elimination',async()=>{
    vi.mocked(api.enterTable).mockResolvedValue(tableView({canAdvance:true,holeCards:[],legalActions:{types:[],callAmount:0,minRaiseTo:null,maxRaiseTo:0}}));render(<App/>);
    await screen.findByRole('button',{name:'推进一步'});expect(screen.queryByRole('button',{name:/FOLD/})).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox',{name:'牌桌发言'})).not.toBeInTheDocument();
  })
  it('keeps shortcuts out of the chat editor',async()=>{
    render(<App/>);await screen.findByRole('button',{name:/加注至 816/});fireEvent.keyDown(screen.getByRole('textbox',{name:'牌桌发言'}),{key:'f'});expect(api.command).not.toHaveBeenCalled();
    fireEvent.keyDown(window,{key:'f'});await waitFor(()=>expect(api.command).toHaveBeenCalledWith(expect.anything(),'actions',{type:'FOLD',amount:undefined}));
  })
  it('opens a session-scoped replay',async()=>{
    render(<App/>);await screen.findByRole('button',{name:/加注至 816/});fireEvent.click(screen.getByRole('button',{name:'回放'}));
    await screen.findByRole('slider',{name:'回放时间轴'});expect(api.getReplay).toHaveBeenCalledWith(expect.objectContaining({tableId:'table-1'}),0,expect.any(AbortSignal));
  })
})
