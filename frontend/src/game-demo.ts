export type Suit = '♠' | '♥' | '♦' | '♣'

export type PlayingCard = {
  rank: string
  suit: Suit
}

export type AgentSeat = {
  id: string
  name: string
  subtitle: string
  stack: number
  bet: number
  mood: string
  sprite: number
  seat: string
  folded?: boolean
  thinking?: boolean
  dealer?: boolean
}

export type TableLog = {
  id: number
  name: string
  action: string
  detail: string
  tone: 'gold' | 'green' | 'red' | 'blue'
}

export const initialAgents: AgentSeat[] = [
  { id: 'vesper', name: '阿绯', subtitle: '临江茶馆掌柜', stack: 2100, bet: 0, mood: '笑着看你', sprite: 0, seat: 'seat-left', dealer: true },
  { id: 'hogarth', name: '豪哥', subtitle: '北地矿场工头', stack: 2450, bet: 150, mood: '兴致正高', sprite: 1, seat: 'seat-top' },
  { id: 'mirelle', name: '沈听澜', subtitle: '江南票号账房', stack: 2220, bet: 150, mood: '心里有数', sprite: 2, seat: 'seat-right-top' },
  { id: 'bruno', name: '杜叔', subtitle: '退隐镖师', stack: 2760, bet: 150, mood: '稳如老钟', sprite: 3, seat: 'seat-right' },
  { id: 'bunji', name: '小满', subtitle: '岭南药铺学徒', stack: 1980, bet: 0, mood: '耐心候着', sprite: 4, seat: 'seat-left-bottom', folded: true },
]

export const communityCards: PlayingCard[] = [
  { rank: '10', suit: '♥' },
  { rank: 'J', suit: '♣' },
  { rank: 'Q', suit: '♦' },
]

export const holeCards: PlayingCard[] = [
  { rank: 'A', suit: '♠' },
  { rank: 'K', suit: '♥' },
]

export const initialLogs: TableLog[] = [
  { id: 1, name: '豪哥', action: '加注至 150', detail: '“想看下一张？先把茶钱补上。”', tone: 'red' },
  { id: 2, name: '沈听澜', action: '跟注 150', detail: '“价钱合适，我再看一张。”', tone: 'blue' },
  { id: 3, name: '杜叔', action: '跟注 150', detail: '“我还坐得住，跟上。”', tone: 'gold' },
  { id: 4, name: '小满', action: '弃牌', detail: '“药可以慢熬，牌不能硬追。”', tone: 'green' },
]

export const formatChips = (value: number) => new Intl.NumberFormat('zh-CN').format(value)

export function nextDemoHand(hand: number) {
  const even = hand % 2 === 0
  return {
    cards: even
      ? [{ rank: 'A', suit: '♠' }, { rank: 'K', suit: '♥' }] as PlayingCard[]
      : [{ rank: '9', suit: '♣' }, { rank: '9', suit: '♦' }] as PlayingCard[],
    board: even
      ? [{ rank: '10', suit: '♥' }, { rank: 'J', suit: '♣' }, { rank: 'Q', suit: '♦' }] as PlayingCard[]
      : [{ rank: '2', suit: '♠' }, { rank: '7', suit: '♦' }, { rank: 'K', suit: '♣' }] as PlayingCard[],
  }
}
